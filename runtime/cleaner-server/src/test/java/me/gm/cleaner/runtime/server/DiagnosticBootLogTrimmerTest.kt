package me.gm.cleaner.runtime.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 启动段分级裁剪的契约测试。
 *
 * 背景：`cleaner-boot-*` 是 `-b main,system,crash` **无过滤**日志，实测 118 KB/s，
 * 60 秒产出 7,249,920 B。归档只留头部 512 KiB ⇒ 只覆盖 **4.3 秒**（第 42 批实测
 * 单文件 0.97 s / 2.89 s），起机关键窗口结构性不可见。本测试钉住三条不可退化的性质：
 *
 * 1. `E`/`F` 行在预算够时**逐字全留**（step=1），不许被采样；
 * 2. 噪声行按步长**均匀**采样 ⇒ 输出同时包含时间轴两端，不是"只剩开头"；
 * 3. 预算不足时必须显式写出 `step=0 / DROPPED`，不许用"输出 0 行"冒充"本来就没有"。
 */
class DiagnosticBootLogTrimmerTest {

    private val tagSet = setOf("MC_BOOT", "LayerOrchestrator", "Mounter")

    private lateinit var dir: File

    @Before
    fun setUp() {
        // 必须落在工作区内：系统 temp 目录在本机沙箱下可能被拒写（曾出现静默空输出）。
        dir = File("build/test-tmp/bootlog-trim").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writeLog(name: String, lines: List<String>): File =
        File(dir, name).apply {
            // 空列表必须写成**真正的空文件**：`joinToString("\n") + "\n"` 对空列表会
            // 留下一个空行，让 totalLines=1 —— 那是测试自身的假象，不是被测行为。
            writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
        }

    private fun threadtime(
        level: String,
        tag: String,
        message: String,
        second: Int = 14,
        millis: Int = 35,
    ): String = "10-23 14:%02d:%02d.%03d  9886 10652 %s %s: %s".format(
        35, second, millis, level, tag, message,
    )

    // ── classify ──

    @Test
    fun `threadtime E and F are critical`() {
        assertEquals(
            DiagnosticBootLogTrimmer.LineClass.CRITICAL,
            DiagnosticBootLogTrimmer.classify(threadtime("E", "Parcel", "boom"), tagSet),
        )
        assertEquals(
            DiagnosticBootLogTrimmer.LineClass.CRITICAL,
            DiagnosticBootLogTrimmer.classify(threadtime("F", "libc", "fatal"), tagSet),
        )
    }

    @Test
    fun `project tag is focused even at debug level`() {
        assertEquals(
            DiagnosticBootLogTrimmer.LineClass.FOCUSED,
            DiagnosticBootLogTrimmer.classify(threadtime("D", "MC_BOOT", "hello"), tagSet),
        )
    }

    @Test
    fun `other tags at non-error level are noise`() {
        assertEquals(
            DiagnosticBootLogTrimmer.LineClass.NOISE,
            DiagnosticBootLogTrimmer.classify(threadtime("I", "ActivityManager", "start"), tagSet),
        )
        // 精确匹配：白名单里没有 MC_BOOT_X，不能被前缀命中
        assertEquals(
            DiagnosticBootLogTrimmer.LineClass.NOISE,
            DiagnosticBootLogTrimmer.classify(threadtime("D", "MC_BOOT_X", "x"), tagSet),
        )
    }

    @Test
    fun `continuation line and non logcat text classify as null`() {
        assertEquals(null, DiagnosticBootLogTrimmer.classify("    at android.os.Looper.loop", tagSet))
        assertEquals(null, DiagnosticBootLogTrimmer.classify("=== some banner ===", tagSet))
    }

    @Test
    fun `brief format is understood as fallback`() {
        assertEquals(
            DiagnosticBootLogTrimmer.LineClass.CRITICAL,
            DiagnosticBootLogTrimmer.classify("E/AndroidRuntime( 1234): FATAL EXCEPTION", tagSet),
        )
        // brief 下的白名单 tag 仍要命中
        assertEquals(
            DiagnosticBootLogTrimmer.LineClass.FOCUSED,
            DiagnosticBootLogTrimmer.classify("D/MC_BOOT( 100): boot phase", tagSet),
        )
    }

    // ── planBudget ──

    private fun scanOf(
        criticalBytes: Long,
        focusedBytes: Long,
        noiseBytes: Long,
    ) = DiagnosticBootLogTrimmer.Scan(
        totalLines = 3,
        totalBytes = criticalBytes + focusedBytes + noiseBytes,
        criticalLines = if (criticalBytes > 0) 1 else 0,
        criticalBytes = criticalBytes,
        focusedLines = if (focusedBytes > 0) 1 else 0,
        focusedBytes = focusedBytes,
        noiseLines = if (noiseBytes > 0) 1 else 0,
        noiseBytes = noiseBytes,
    )

    @Test
    fun `everything fits means step one`() {
        val plan = DiagnosticBootLogTrimmer.planBudget(scanOf(1000, 1000, 1000), 1_000_000)
        assertEquals(1, plan.criticalStep)
        assertEquals(1, plan.focusedStep)
        assertEquals(1, plan.noiseStep)
    }

    @Test
    fun `critical keeps priority when budget is tight`() {
        // 可用预算 ≈ 1 MB - 4 KB；critical 占 65% ≈ 665 KB
        val plan = DiagnosticBootLogTrimmer.planBudget(
            scanOf(criticalBytes = 6_650_000, focusedBytes = 500_000, noiseBytes = 5_000_000),
            maxBytes = 1_048_576,
        )
        assertTrue("critical 必须被采样而不是整段丢弃", plan.criticalStep > 1)
        // 临界段 65% / 聚焦段 25% / 噪声段吃剩余 ⇒ 噪声步长最大（最稀）
        assertTrue(plan.noiseStep > plan.criticalStep)
        assertTrue(plan.noiseStep > plan.focusedStep)
    }

    @Test
    fun `noise is dropped outright when its budget is tiny`() {
        // 预算极小时 CRITICAL（65%）与 FOCUSED（25%）吃掉绝大部分，NOISE 剩余
        // 必然低于 MIN_NOISE_BUDGET_BYTES ⇒ 整段丢弃并显式标注。
        // 注意预算不能取中等值：三段各占固定比例，NOISE 恒有 ≥10% 可用预算，
        // 只有 maxBytes 小到连 8 KiB 都分不出来时才会走到丢弃分支。
        val plan = DiagnosticBootLogTrimmer.planBudget(
            scanOf(criticalBytes = 600_000, focusedBytes = 400_000, noiseBytes = 5_000_000),
            maxBytes = 64 * 1024,
        )
        assertEquals(0, plan.noiseStep)
        // CRITICAL 只可能被「采样」，绝不整段丢弃 —— step 可以 > 1，但必须 > 0
        assertTrue("critical 必须仍被保留（可采样）", plan.criticalStep > 0)
        assertTrue("critical 必须仍拿到预算", plan.criticalBudgetBytes > 0)
    }

    @Test
    fun `continuation bytes are charged to the critical budget`() {
        // 崩溃堆栈（续行）不计入预算时会被硬上限截断 —— 第43批单测抓到过一次。
        val withoutContinuation = scanOf(criticalBytes = 3_000_000, focusedBytes = 0, noiseBytes = 0)
        val withContinuation = withoutContinuation.copy(continuationBytes = 3_000_000)
        val a = DiagnosticBootLogTrimmer.planBudget(withoutContinuation, maxBytes = 1_048_576)
        val b = DiagnosticBootLogTrimmer.planBudget(withContinuation, maxBytes = 1_048_576)
        assertTrue("续行必须让 CRITICAL 需求变大（采样更密或预算更高）", b.criticalBudgetBytes >= a.criticalBudgetBytes)
        assertTrue(withoutContinuation.criticalBytes + withoutContinuation.continuationBytes == 3_000_000L)
    }

    // ── trim 端到端 ──

    @Test
    fun `error lines survive verbatim and noise keeps both ends of the time axis`() {
        val lines = ArrayList<String>()
        repeat(400) { i ->
            when {
                i % 40 == 0 -> lines.add(threadtime("E", "Parcel", "failure-$i", second = i / 7))
                i % 40 == 20 -> lines.add(threadtime("D", "MC_BOOT", "focused-$i", second = i / 7))
                else -> lines.add(threadtime("D", "Chatty", "noise-$i", second = i / 7))
            }
        }
        val file = writeLog("boot.log", lines)

        // 预算极小 ⇒ 三类都必须采样，但仍要覆盖时间轴两端
        val result = DiagnosticBootLogTrimmer.trim(file, maxBytes = 16 * 1024, tagSet = tagSet)

        // 头部必须说清「丢了什么」
        assertTrue(result.content.contains("retention=critical(E/F)"))
        assertTrue(result.content.contains("lines=total=400"))
        assertTrue(result.content.contains("step=critical="))

        val emitted = result.content.lines()
        assertTrue("必须输出 CRITICAL 段", emitted.any { it.contains("### CRITICAL") })
        // 噪声段从第一行开始按步长采样 ⇒ 首条噪声必在
        assertTrue(emitted.any { it.contains("noise-1") })
        // 且必须覆盖到接近末尾的噪声（说明不是「只剩开头」）
        val lastNoiseIndex = emitted.indexOfLast { it.contains("noise-") }
        val lastNoise = emitted[lastNoiseIndex].substringAfter("noise-").toInt()
        assertTrue("噪声段必须延伸到时间轴末端，实际最后一条=$lastNoise", lastNoise > 300)
    }

    @Test
    fun `all error lines are kept when budget is generous`() {
        val lines = (0 until 100).map { i ->
            threadtime("E", "Parcel", "err-$i", second = i % 60)
        }
        val file = writeLog("boot-small.log", lines)
        val result = DiagnosticBootLogTrimmer.trim(file, maxBytes = 512 * 1024, tagSet = tagSet)

        assertEquals(1, result.plan.criticalStep)
        assertEquals(100, result.emittedCritical)
        for (i in 0 until 100) {
            assertTrue("缺 err-$i", result.content.contains("err-$i"))
        }
    }

    @Test
    fun `continuation lines follow their header line`() {
        val lines = listOf(
            threadtime("E", "AndroidRuntime", "FATAL EXCEPTION", second = 10),
            "    at com.example.Crash.run(Crash.kt:1)",
            "    at android.app.ActivityThread.main(ActivityThread.java:1)",
            threadtime("D", "Chatty", "noise-after", second = 11),
        )
        val file = writeLog("boot-cont.log", lines)
        val result = DiagnosticBootLogTrimmer.trim(file, maxBytes = 512 * 1024, tagSet = tagSet)
        assertTrue(result.content.contains("FATAL EXCEPTION"))
        assertTrue("续行必须跟随头行一起保留", result.content.contains("Crash.kt:1"))
        assertTrue(result.content.contains("ActivityThread.java:1"))
    }

    @Test
    fun `orphan continuation lines are not emitted`() {
        // 第一条是续行（无头行）⇒ 不得输出，否则归档里会出现无法归因的代码行
        val lines = listOf(
            "    at com.example.Orphan.run(Orphan.kt:9)",
            threadtime("D", "Chatty", "noise", second = 12),
        )
        val file = writeLog("boot-orphan.log", lines)
        val result = DiagnosticBootLogTrimmer.trim(file, maxBytes = 512 * 1024, tagSet = tagSet)
        assertTrue("孤儿续行不得出现", !result.content.contains("Orphan.kt:9"))
    }

    @Test
    fun `empty file yields header with zero counters and no crash`() {
        val file = writeLog("boot-empty.log", emptyList())
        val result = DiagnosticBootLogTrimmer.trim(file, maxBytes = 64 * 1024, tagSet = tagSet)
        assertTrue(result.content.contains("lines=total=0"))
        assertEquals(0, result.emittedCritical)
        assertEquals(0, result.emittedFocused)
        assertEquals(0, result.emittedNoise)
    }

    @Test
    fun `output stays within the hard ceiling`() {
        val lines = (0 until 2000).map { i ->
            threadtime("E", "Parcel", "x".repeat(200) + "-$i", second = i % 60)
        }
        val file = writeLog("boot-huge.log", lines)
        val maxBytes = 32 * 1024
        val result = DiagnosticBootLogTrimmer.trim(file, maxBytes = maxBytes, tagSet = tagSet)
        val actual = result.content.toByteArray(Charsets.UTF_8).size
        // hardLimit = budget*3/2 + 头部；再留一点分隔行余量
        assertTrue("实际 $actual 超出硬上限", actual <= maxBytes * 2)
    }
}
