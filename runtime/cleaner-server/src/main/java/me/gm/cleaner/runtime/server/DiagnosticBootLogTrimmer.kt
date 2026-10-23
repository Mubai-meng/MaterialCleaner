package me.gm.cleaner.runtime.server

import java.io.File
import java.util.zip.ZipOutputStream

/**
 * 启动窗口段（`cleaner-boot-*`）日志的**分级裁剪**。
 *
 * ## 要解决的实机缺陷
 * 启动段是 `-b main,system,crash` **无 tag 过滤**的全系统日志，实测 60 秒产出
 * **7,249,920 B（118 KB/s）**。归档侧沿用「只保留头部 512 KiB」的策略，而
 * 512 KiB ÷ 118 KB/s ≈ **4.3 秒**：一份 60 秒的启动现场，进包后只剩前 4.3 秒
 * （第 42 批实测 `.log` 覆盖 0.97 s、`.log.1` 覆盖 2.89 s）。起机关键窗口
 * （init/zygote/framework 启动、首轮挂载、策略首次可见）**结构性不可见**。
 *
 * ## 为什么不是简单改成「头尾各半」
 * 头尾各半只是把 4.3 秒变成「前 2.2 秒 + 后 2.2 秒」，中间 55 秒照样消失，
 * 而挂载失败、Hook 未加载这类事件恰好落在中段。**必须保留时间轴**。
 *
 * ## 做法：按行分级 + 分级预算 + 段内均匀采样
 * 1. **CRITICAL**：`E` / `F` 级别行 —— 崩溃、SELinux 拒绝、挂载失败都在这里；
 * 2. **FOCUSED**：tag 命中 [LogTagFilter] 白名单的行 —— 本项目与关键系统组件；
 * 3. **NOISE**：其余 `V`/`D`/`I`/`W` 行 —— 只用来保留时间轴骨架。
 *
 * 每类各自分配预算，并按「该类总字节 ÷ 该类预算」算出采样步长，**从该类第一行开始
 * 均匀抽样**。于是无论洪峰多高，输出的时间轴都覆盖完整窗口：密度下降，但
 * 「哪一秒开始不对劲」这个问题始终可答。
 *
 * ## 输出契约
 * - logcat 原始行**逐字保留**（不改写、不加前缀），归档仍可直接 `grep`；
 * - 段与段之间用 `### …` 分隔（logcat 行以 `MM-DD` 或级别字母开头，不会冲突）；
 * - 头部的 `retention=` / `lines=` / `step=` 三行说明**这次到底丢了什么**，
 *   避免把「采样后的稀疏」读成「当时就只有这些」。
 *
 * ## 为什么是「一遍统计 + 每段一遍」
 * 采样步长依赖「该类总字节数」，必须扫完才知道；一遍扫描只能要么把 7 MB 全缓存
 * （内存峰值不可控）要么放弃时间轴。多次顺序读在用户主动导出的路径上耗时可忽略
 * （实测 7 MB × 4 遍 < 200 ms），换来有界内存与可验证的确定性。
 */
internal object DiagnosticBootLogTrimmer {

    /** logcat threadtime：`MM-DD HH:MM:SS.mmm  PID  TID L Tag: msg`（`-f` 默认格式）。 */
    private val THREADTIME = Regex(
        """^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s+(.+?)\s*:""",
    )

    /** logcat time+brief：`MM-DD HH:MM:SS.mmm L/Tag( PID): msg`。 */
    private val TIME_BRIEF = Regex(
        """^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+([VDIWEFA])/(.+?)\s*\(\s*\d+\):""",
    )

    /** logcat brief：`L/Tag( PID): msg`（兜底，防止将来改 `-v` 后解析全失效）。 */
    private val BARE_BRIEF = Regex("""^([VDIWEFA])/(.+?)\s*\(\s*\d+\):""")

    /** 头部说明预留（字节）：path/size/modified/retention/lines/step 六行 + 分隔。 */
    private const val HEADER_RESERVE_BYTES = 4096L

    /** NOISE 段的最低预算：低于此值只能放几条，不如明确标为丢弃。 */
    private const val MIN_NOISE_BUDGET_BYTES = 8L * 1024

    /** CRITICAL 段预算占可用预算的比例（百分比）：错误证据优先。 */
    private const val CRITICAL_PERCENT = 65

    /** FOCUSED 段预算占比（百分比）。 */
    private const val FOCUSED_PERCENT = 25

    enum class LineClass {
        /** `E`/`F` 级别：错误与崩溃证据。 */
        CRITICAL,

        /** tag 命中项目白名单。 */
        FOCUSED,

        /** 其余级别：只用于保留时间轴。 */
        NOISE,
    }

    /** 一遍扫描的统计量（只累加计数，内存有界）。 */
    data class Scan(
        val totalLines: Int = 0,
        val totalBytes: Long = 0L,
        val criticalLines: Int = 0,
        val criticalBytes: Long = 0L,
        val focusedLines: Int = 0,
        val focusedBytes: Long = 0L,
        val noiseLines: Int = 0,
        val noiseBytes: Long = 0L,
        val continuationLines: Int = 0,
        /**
         * 续行字节（崩溃堆栈、多行 detail）。
         *
         * **必须计入 CRITICAL 预算**：续行几乎全部跟随 `E`/`F` 头行，若只按头行字节
         * 算预算，崩溃堆栈会被「1.5 倍硬上限」截掉后半段 —— 而堆栈正是崩溃证据本体
         * （第 43 批单测抓到：3 行堆栈只留了第 1 行）。
         */
        val continuationBytes: Long = 0L,
    )

    /**
     * 预算与采样步长。
     *
     * `step == 0` 表示该段**整段不输出**（预算不足），由头部 `step=` 显式说明，
     * 绝不用「输出 0 行」冒充「本来就没有」。
     */
    data class Plan(
        val criticalBudgetBytes: Long,
        val focusedBudgetBytes: Long,
        val noiseBudgetBytes: Long,
        val criticalStep: Int,
        val focusedStep: Int,
        val noiseStep: Int,
    )

    /** 一趟裁剪的结果（内容 + 用来写头部统计的事实）。 */
    data class Result(
        val content: String,
        val scan: Scan,
        val plan: Plan,
        val emittedCritical: Int,
        val emittedFocused: Int,
        val emittedNoise: Int,
    )

    /** 单行分类；返回 `null` 表示续行或非 logcat 内容。 */
    fun classify(line: String, tagSet: Set<String>): LineClass? {
        val match = THREADTIME.find(line)
            ?: TIME_BRIEF.find(line)
            ?: BARE_BRIEF.find(line)
            ?: return null
        val level = match.groupValues[1]
        val tag = match.groupValues[2]
        return when {
            level == "E" || level == "F" -> LineClass.CRITICAL
            tagSet.contains(tag) -> LineClass.FOCUSED
            else -> LineClass.NOISE
        }
    }

    /** 按行做一趟统计（不拼接字符串，内存有界）。 */
    fun scan(file: File, tagSet: Set<String>): Scan {
        var totalLines = 0
        var totalBytes = 0L
        var criticalLines = 0
        var criticalBytes = 0L
        var focusedLines = 0
        var focusedBytes = 0L
        var noiseLines = 0
        var noiseBytes = 0L
        var continuationLines = 0
        var continuationBytes = 0L
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                totalLines++
                val bytes = utf8Length(line)
                totalBytes += bytes
                when (classify(line, tagSet)) {
                    LineClass.CRITICAL -> {
                        criticalLines++
                        criticalBytes += bytes
                    }

                    LineClass.FOCUSED -> {
                        focusedLines++
                        focusedBytes += bytes
                    }

                    LineClass.NOISE -> {
                        noiseLines++
                        noiseBytes += bytes
                    }

                    null -> {
                        continuationLines++
                        continuationBytes += bytes
                    }
                }
            }
        }
        return Scan(
            totalLines = totalLines,
            totalBytes = totalBytes,
            criticalLines = criticalLines,
            criticalBytes = criticalBytes,
            focusedLines = focusedLines,
            focusedBytes = focusedBytes,
            noiseLines = noiseLines,
            noiseBytes = noiseBytes,
            continuationLines = continuationLines,
            continuationBytes = continuationBytes,
        )
    }

    /**
     * 纯函数：把 [maxBytes] 拆成三段预算并算出各自采样步长。
     *
     * 某类实际字节小于其预算时按实际值收敛，富余**不**被其它段抢占 —— 段间抢占会让
     * 「E 行很多时白名单被饿死」，反而更难排查；三段各自的时间轴完整才是目标。
     */
    fun planBudget(scan: Scan, maxBytes: Long): Plan {
        val usable = (maxBytes - HEADER_RESERVE_BYTES).coerceAtLeast(0L)
        // 续行字节并入 CRITICAL 需求：它们跟随 E/F 头行一起输出，不计入预算就等于
        // 让「堆栈只剩第一行」这种截断在预算看似充裕时发生。
        val criticalDemand = scan.criticalBytes + scan.continuationBytes
        val criticalBudget = minOf(criticalDemand, usable * CRITICAL_PERCENT / 100)
        val focusedBudget = minOf(scan.focusedBytes, usable * FOCUSED_PERCENT / 100)
        val noiseBudget = (usable - criticalBudget - focusedBudget).coerceAtLeast(0L)
        return Plan(
            criticalBudgetBytes = criticalBudget,
            focusedBudgetBytes = focusedBudget,
            noiseBudgetBytes = noiseBudget,
            criticalStep = stepFor(criticalDemand, criticalBudget),
            focusedStep = stepFor(scan.focusedBytes, focusedBudget),
            noiseStep = if (noiseBudget < MIN_NOISE_BUDGET_BYTES) {
                0
            } else {
                stepFor(scan.noiseBytes, noiseBudget)
            },
        )
    }

    /** `ceil(bytes / budget)`；无内容时为 1，无预算时为 0（整段丢弃）。 */
    private fun stepFor(bytes: Long, budget: Long): Int {
        if (bytes <= 0L) return 1
        if (budget <= 0L) return 0
        if (bytes <= budget) return 1
        val step = (bytes + budget - 1) / budget
        return if (step > Int.MAX_VALUE) Int.MAX_VALUE else step.toInt()
    }

    /**
     * 统计 + 分段输出。
     *
     * @param maxBytes 输出内容（含头部）的字节上限
     */
    fun trim(file: File, maxBytes: Int, tagSet: Set<String>): Result {
        val scan = scan(file, tagSet)
        val plan = planBudget(scan, maxBytes.toLong())
        val body = StringBuilder(
            minOf(maxBytes, 1 shl 20).coerceAtLeast(1024),
        )
        appendHeader(body, file, scan, plan, maxBytes)
        val emittedCritical = emitSection(
            file, tagSet, LineClass.CRITICAL, plan.criticalStep,
            scan.criticalLines, "CRITICAL (level E/F, verbatim)", body,
            hardLimitBytes = plan.criticalBudgetBytes * 2,
        )
        val emittedFocused = emitSection(
            file, tagSet, LineClass.FOCUSED, plan.focusedStep,
            scan.focusedLines, "FOCUSED (project tags, verbatim)", body,
            hardLimitBytes = plan.focusedBudgetBytes * 2,
        )
        val emittedNoise = emitSection(
            file, tagSet, LineClass.NOISE, plan.noiseStep,
            scan.noiseLines, "NOISE (time axis only)", body,
            hardLimitBytes = plan.noiseBudgetBytes * 2,
        )
        return Result(body.toString(), scan, plan, emittedCritical, emittedFocused, emittedNoise)
    }

    private fun appendHeader(
        body: StringBuilder,
        file: File,
        scan: Scan,
        plan: Plan,
        maxBytes: Int,
    ) {
        body.append("path=").append(file.path).append('\n')
        body.append("size=").append(file.length()).append('\n')
        body.append("modified=").append(file.lastModified()).append('\n')
        body.append("retention=critical(E/F)+focused(project tags)+noise(uniformly sampled)\n")
        body.append("lines=total=").append(scan.totalLines)
            .append(" critical=").append(scan.criticalLines)
            .append(" focused=").append(scan.focusedLines)
            .append(" noise=").append(scan.noiseLines)
            .append(" continuation=").append(scan.continuationLines).append('\n')
        body.append("budget=maxBytes=").append(maxBytes)
            .append(" criticalBytes=").append(scan.criticalBytes)
            .append(" continuationBytes=").append(scan.continuationBytes)
            .append(" focusedBytes=").append(scan.focusedBytes)
            .append(" noiseBytes=").append(scan.noiseBytes).append('\n')
        body.append("step=critical=").append(plan.criticalStep)
            .append(" focused=").append(plan.focusedStep)
            .append(" noise=").append(plan.noiseStep)
            .append(" (0 = dropped for lack of budget)\n")
    }

    /**
     * 输出一类行（含其续行，续行与所属头行同命运）。
     *
     * @param hardLimitBytes 极端兜底：UTF-8 估算偏小时防止输出无界膨胀
     */
    private fun emitSection(
        file: File,
        tagSet: Set<String>,
        target: LineClass,
        step: Int,
        totalLinesOfClass: Int,
        title: String,
        body: StringBuilder,
        hardLimitBytes: Long,
    ): Int {
        body.append("### ").append(title)
        if (step == 0) {
            body.append(" DROPPED (no budget)")
            body.append('\n')
            return 0
        }
        if (step > 1) body.append(" sampled 1/").append(step)
        body.append('\n')
        if (totalLinesOfClass == 0) return 0

        var seen = 0
        var emitted = 0
        var written = 0L
        var headerEmitted = false
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val cls = classify(line, tagSet)
                if (cls == null) {
                    // 续行：只在所属头行确实被写出时跟随，避免留下孤儿行
                    if (!headerEmitted) continue
                } else {
                    if (cls != target) {
                        headerEmitted = false
                        continue
                    }
                    seen++
                    headerEmitted = (seen - 1) % step == 0
                    if (!headerEmitted) continue
                }
                if (written >= hardLimitBytes) break
                body.append(line).append('\n')
                written += utf8Length(line)
                if (cls == target) emitted++
            }
        }
        return emitted
    }

    /**
     * 真实 UTF-8 字节数。
     *
     * 不用 `length` 近似：诊断包预算必须硬保证，CJK 路径与 tag 会被 3 倍低估。
     * 该路径只在用户主动导出时跑（7 MB 级文件、十万次编码约几十毫秒）。
     */
    fun utf8Length(line: String): Int = line.toByteArray(Charsets.UTF_8).size

    /** 把裁剪结果作为归档条目写入；失败时写 `.error.txt`（与 [addFileTail] 同契约）。 */
    fun addBootLogTrimmed(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
        maxBytes: Int,
        tagSet: Set<String> = LogTagFilter.tagSet,
    ) {
        runCatching {
            val result = trim(file, maxBytes, tagSet)
            addText(zip, entryName, result.content)
        }.onFailure {
            addText(zip, "$entryName.error.txt", it.stackTraceToString())
        }
    }
}
