package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * FUSE 重定向的命中/未命中观测与事件前置去重计数。
 *
 * **为什么需要它**：`redirected:` 日志只在**确实改写**时才打，未命中的路径上
 * 既没有日志也没有计数。于是「hook 没工作」与「目标 app 触碰的路径根本不在
 * 任何已配置 target 之下」在产物里无法区分 —— 连续三批实机材料都因此只能给出
 * 无法证伪的结论（实测：某 app 58 次访问全部落在 `/storage/emulated/0` 根目录，
 * 而其 target 是 `/storage/emulated/0/Download`，一次都不该命中）。
 *
 * **三个互斥计数**：`redirected + preserved + missed == evaluated`。
 * `preserved` 单独成项是必须的 —— 恒等 carve-out（`X → X`）命中的派生结果与原文
 * 相同，若并入 `missed`，配了 PRESERVE 规则的包命中率恒为 0%，每一次实机验证
 * 都退化成无法证伪的观察（2026-11-02 实测：相册 654 次评估全部落在
 * `Pictures`/`DCIM`/`Download` 的恒等规则上，`redirected=0 missed=654`）。
 *
 * **性能约束**：[record] 跑在 FUSE 热路径上，只做原子自增与 volatile 写，
 * **不发布快照**（交给既有 8s 心跳）、不做字符串拼接（前缀只在变化时重算）。
 * 调用方必须先自己确认该包在重定向配置里（`HookPolicyCache.hasRedirectRules`），
 * 否则等于给所有包的文件操作都加了一次开销。
 */
internal class FuseRedirectCounters {
    /** preload 事件前置去重的累计抑制条数（见 `FuseEventDedup`）。 */
    @Volatile
    private var dedupedCount = 0L

    private val evaluated = AtomicLong(0L)
    private val redirected = AtomicLong(0L)
    private val preserved = AtomicLong(0L)
    private val missed = AtomicLong(0L)

    private val lastMiss = LastObservation()
    private val lastPreserve = LastObservation()

    val dedupedEvents: Long
        get() = dedupedCount

    fun setDedupedCount(total: Long) {
        dedupedCount = total
    }

    fun record(packageName: String, path: String, outcome: FuseRedirectStats.Outcome) {
        evaluated.incrementAndGet()
        when (outcome) {
            FuseRedirectStats.Outcome.REDIRECTED -> redirected.incrementAndGet()
            FuseRedirectStats.Outcome.PRESERVED -> {
                preserved.incrementAndGet()
                lastPreserve.observe(packageName, path)
            }

            FuseRedirectStats.Outcome.MISS -> {
                missed.incrementAndGet()
                lastMiss.observe(packageName, path)
            }
        }
    }

    /** 快照序列化：`fuseRedirect` 段。 */
    fun appendRedirectTo(json: JSONObject) {
        json.put("evaluated", evaluated.get())
        json.put("redirected", redirected.get())
        json.put("preserved", preserved.get())
        json.put("missed", missed.get())
        json.put("lastMissPackage", lastMiss.packageName)
        json.put("lastMissPrefix", lastMiss.prefix)
        json.put("lastPreservePackage", lastPreserve.packageName)
        json.put("lastPreservePrefix", lastPreserve.prefix)
        json.put("prefixSegments", FuseRedirectStats.DEFAULT_PREFIX_SEGMENTS)
    }

    /**
     * 最近一次观测到的（包名 + 目录前缀），带「同一路径反复访问」的零分配短路。
     *
     * 未命中与恒等命中各持一个实例：两者回答的是**不同**问题 ——
     * 「为什么没匹配到」与「匹配到了、被原样保留的是哪个目录」。
     *
     * 短路本身不改变任何可观测语义：同一 path 的前缀必然相同，因此把「重算前缀」
     * 换成「内容比较」只是省掉热路径上的字符串分配。`isNotEmpty()` 这一半是刻意的：
     * 空路径（真实 FUSE 不会给，但单测会）必须继续走原逻辑，否则
     * 「首个空 path 也要记 package」的行为会被悄悄改掉。
     */
    private class LastObservation {
        @Volatile
        var packageName = ""

        @Volatile
        var prefix = ""

        /** 上一次观测到的**原始**路径；只用于短路，不进快照。 */
        @Volatile
        private var path = ""

        fun observe(packageName: String, path: String) {
            if (this.path.isNotEmpty() && path == this.path) return
            this.path = path
            if (this.packageName != packageName || prefix.isEmpty()) {
                this.packageName = packageName
                prefix = FuseRedirectStats.prefixOf(path)
                return
            }
            val candidate = FuseRedirectStats.prefixOf(path)
            if (candidate != prefix) {
                prefix = candidate
                this.packageName = packageName
            }
        }
    }
}
