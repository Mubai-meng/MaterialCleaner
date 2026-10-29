package me.gm.cleaner.runtime.mediaprovider.hook.policy

import java.util.concurrent.atomic.AtomicLong

/**
 * 只读规则的**可观测性**计数器。
 *
 * ⚠️ 只读 ≠ 不可访问：只读是「允许读、拒绝写」，不可访问是「连读都拒绝、目标是让
 * 应用完全识别不到」。后者的计数器是 [InaccessibleEvalStats]，两者不可混用。
 *
 * 动机（与 [FuseRedirectStats] 同源，纪律第 27 条）：只读判定此前**只在成功路径上打日志**
 * （`denied by read-only rule`），而历批实机材料里这条日志恒为 0 次 —— 于是
 * 「规则从未被求值」「求值了但没匹配上」「匹配了但拒绝没生效」三种可能
 * **在产物里完全无法区分**，只能靠猜。
 *
 * 本计数器给出三段式证据：
 * - `evaluated` 判定被真正调用的累计次数（>0 ⇒ 规则确实参与求值）；
 * - `matched` 其中命中规则的次数（>0 但 denied=0 ⇒ 拒绝链路断了）；
 * - `denied*` 按拒绝点分的次数（>0 ⇒ 端到端生效）。
 *
 * 设计约束（纪律第 29 条）：热路径上只做原子自增 + volatile 写，**不发布快照**，
 * 交给既有心跳；调用方先用 O(1) 的 map 查表确认该包确实配了只读规则再记账。
 */
object ReadOnlyEvalStats {

    /** 只读拒绝发生的位置，用于定位"哪条链路没生效"。 */
    enum class DenyPoint(val key: String) {
        /** insertFileIfNecessaryForFuse / deleteFileForFuse（品种 A） */
        FILE("deniedFile"),

        /** isDirAccessAllowedForFuse（品种 E） */
        DIR("deniedDir"),

        /** openWithFuse（品种 C2，抛 FileNotFoundException） */
        OPEN("deniedOpen"),

        /** renameForFuse（品种 B） */
        RENAME("deniedRename"),

        /**
         * MediaProvider insertFile：应用经 MediaStore 建条目被只读规则拒绝的次数。
         *
         * 与 [FILE] 的区别同 [InaccessibleEvalStats.DenyPoint.INSERT]：FILE 在 FUSE 层，
         * INSERT 在 MediaProvider 的 `insertFile` 层。只读规则（允许读、拒绝写）
         * 此前只在 FUSE 层生效，应用可经 MediaStore 绕过。
         */
        INSERT("deniedInsert"),
    }

    private val evaluated = AtomicLong(0L)
    private val matched = AtomicLong(0L)
    private val denied = HashMap<DenyPoint, AtomicLong>().apply {
        DenyPoint.values().forEach { put(it, AtomicLong(0L)) }
    }

    @Volatile
    private var lastEvaluatedPackage = ""

    @Volatile
    private var lastMatchedPackage = ""

    @Volatile
    private var lastMatchedRule = ""

    /** 记录一次判定：只对"该包确实配了只读规则"的调用方有意义。 */
    fun recordEval(packageName: String, hit: Boolean, matchedRulePath: String?) {
        evaluated.incrementAndGet()
        if (lastEvaluatedPackage != packageName) {
            lastEvaluatedPackage = packageName
        }
        if (!hit) return
        matched.incrementAndGet()
        lastMatchedPackage = packageName
        // 规则路径是用户自己的配置，不是用户文件内容，可以明文入快照（read_only.json 里本就是明文）。
        if (!matchedRulePath.isNullOrEmpty()) {
            lastMatchedRule = matchedRulePath
        }
    }

    /** 记录一次真正的拒绝。 */
    fun recordDenial(point: DenyPoint) {
        denied[point]?.incrementAndGet()
    }

    /** 快照投影。 */
    fun snapshot(): Map<String, Any> = buildMap {
        put("evaluated", evaluated.get())
        put("matched", matched.get())
        DenyPoint.values().forEach { put(it.key, denied[it]?.get() ?: 0L) }
        put("lastEvaluatedPackage", lastEvaluatedPackage)
        put("lastMatchedPackage", lastMatchedPackage)
        put("lastMatchedRule", lastMatchedRule)
    }

    /** 仅测试使用：清零全部计数。 */
    fun resetForTest() {
        evaluated.set(0L)
        matched.set(0L)
        denied.values.forEach { it.set(0L) }
        lastEvaluatedPackage = ""
        lastMatchedPackage = ""
        lastMatchedRule = ""
    }
}
