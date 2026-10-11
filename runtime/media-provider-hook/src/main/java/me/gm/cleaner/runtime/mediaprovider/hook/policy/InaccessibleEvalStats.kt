package me.gm.cleaner.runtime.mediaprovider.hook.policy

import java.util.concurrent.atomic.AtomicLong

/**
 * 「不可访问的路径」的**可观测性**计数器。
 *
 * ⚠️ 与 [ReadOnlyEvalStats] 是两个不同的能力，不要混用：
 * - 只读：允许读、拒绝写，路径依然可见；
 * - 不可访问：连读都拒绝，目标是让应用**完全识别不到**该文件夹或文件。
 *
 * 动机同 [ReadOnlyEvalStats]（纪律第 27 条）：没有计数器时，「规则没被求值 /
 * 求值了没匹配 / 匹配了拒绝没生效」三种情况在产物里完全无法区分。本计数器给出
 * 三段式证据：evaluated → matched → denied{Open,Dir,File,Rename,Query}。
 *
 * 设计约束（纪律第 29 条）：热路径上只做原子自增 + volatile 写，不发布快照，
 * 交给既有心跳；调用方先用 O(1) 的 map 查表确认该包确实配了不可访问规则再记账。
 */
object InaccessibleEvalStats {

    /** 不可访问拒绝发生的位置，用于定位"哪条链路没生效"。 */
    enum class DenyPoint(val key: String) {
        /** insertFileIfNecessaryForFuse / deleteFileForFuse（品种 A） */
        FILE("deniedFile"),

        /** isDirAccessAllowedForFuse（品种 E） */
        DIR("deniedDir"),

        /** openWithFuse（品种 C2，抛 FileNotFoundException → 应用侧即"文件不存在"） */
        OPEN("deniedOpen"),

        /** renameForFuse（品种 B） */
        RENAME("deniedRename"),

        /** MediaProvider query：命中不可访问路径的行被过滤掉的行数（按查询次数计） */
        QUERY("filteredQueries"),
    }

    private val evaluated = AtomicLong(0L)
    private val matched = AtomicLong(0L)

    /** 结果集兜底真正剔除掉的行数（SQL 下推生效时为 0）。 */
    private val hiddenRows = AtomicLong(0L)

    /**
     * 探针在**未过滤**的同一条查询里看到的、位于该包应用专属存储内的行数。
     *
     * ⚠️ 第 25/26 批实测**恒为 0**：本机不存在这类行，相册里多出来的 assets(6)
     * 是共享存储（Documents）下的真实目录，与本计数无关 —— 定位这类问题要看
     * `media bucket census` 的 `small=`，不要看这里。
     * 保留该计数用于防御性观测：一旦出现 > 0，说明确有应用把专属目录的图收进了库。
     */
    private val appSpecificRows = AtomicLong(0L)
    private val denied = HashMap<DenyPoint, AtomicLong>().apply {
        DenyPoint.values().forEach { put(it, AtomicLong(0L)) }
    }

    @Volatile
    private var lastEvaluatedPackage = ""

    @Volatile
    private var lastMatchedPackage = ""

    @Volatile
    private var lastMatchedRule = ""

    /** 记录一次判定：只对"该包确实配了不可访问规则"的调用方有意义。 */
    fun recordEval(packageName: String, hit: Boolean, matchedRulePath: String?) {
        evaluated.incrementAndGet()
        if (lastEvaluatedPackage != packageName) {
            lastEvaluatedPackage = packageName
        }
        if (!hit) return
        matched.incrementAndGet()
        lastMatchedPackage = packageName
        // 规则路径是用户自己的配置，不是用户文件内容，可以明文入快照。
        if (!matchedRulePath.isNullOrEmpty()) {
            lastMatchedRule = matchedRulePath
        }
    }

    /** 记录一次真正的拒绝 / 过滤。 */
    fun recordDenial(point: DenyPoint) {
        denied[point]?.incrementAndGet()
    }

    /** 累加结果集兜底剔除的行数。 */
    fun recordHiddenRows(count: Int) {
        if (count > 0) {
            hiddenRows.addAndGet(count.toLong())
        }
    }

    /** 累加探针观测到的"应用专属存储"行数（实测恒为 0，防御性观测，见字段注释）。 */
    fun recordAppSpecificRows(count: Int) {
        if (count > 0) {
            appSpecificRows.addAndGet(count.toLong())
        }
    }

    /** 快照投影。 */
    fun snapshot(): Map<String, Any> = buildMap {
        put("evaluated", evaluated.get())
        put("matched", matched.get())
        put("hiddenRows", hiddenRows.get())
        put("appSpecificRows", appSpecificRows.get())
        DenyPoint.values().forEach { put(it.key, denied[it]?.get() ?: 0L) }
        put("lastEvaluatedPackage", lastEvaluatedPackage)
        put("lastMatchedPackage", lastMatchedPackage)
        put("lastMatchedRule", lastMatchedRule)
    }

    /** 仅测试使用：清零全部计数。 */
    fun resetForTest() {
        evaluated.set(0L)
        matched.set(0L)
        hiddenRows.set(0L)
        appSpecificRows.set(0L)
        denied.values.forEach { it.set(0L) }
        lastEvaluatedPackage = ""
        lastMatchedPackage = ""
        lastMatchedRule = ""
    }
}
