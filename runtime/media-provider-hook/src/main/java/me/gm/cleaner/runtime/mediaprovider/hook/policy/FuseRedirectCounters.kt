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
    private val missed = AtomicLong(0L)

    @Volatile
    private var lastMissPackage = ""

    @Volatile
    private var lastMissPrefix = ""

    /**
     * 上一次观测到的**原始**未命中路径。
     *
     * 只用于「同一路径被反复访问」的零分配短路：MediaProvider 的常态是同一条
     * 路径在一次查询链里被问很多次（FUSE 每个 entry 都会走一次评估）。旧实现
     * 每次都重算前缀，而重算的开销远大于一次字符串内容比较。
     * 命中短路时前缀必然未变（同一字符串），故对诊断语义**完全等价**。
     */
    @Volatile
    private var lastMissPath = ""

    val dedupedEvents: Long
        get() = dedupedCount

    fun setDedupedCount(total: Long) {
        dedupedCount = total
    }

    fun record(packageName: String, path: String, isRedirected: Boolean) {
        evaluated.incrementAndGet()
        if (isRedirected) {
            redirected.incrementAndGet()
            return
        }
        missed.incrementAndGet()
        // 同一路径反复访问是常态：内容比较（零分配）比重算前缀便宜一个数量级。
        // 短路不改变任何可观测语义 —— 同一 path 的前缀必然相同。
        // `isNotEmpty()` 这一半是刻意的：空路径（真实 FUSE 不会给，但单测会）必须
        // 继续走原逻辑，否则「首个空 path 也要记 lastMissPackage」的行为会被悄悄改掉。
        if (lastMissPath.isNotEmpty() && path == lastMissPath) return
        lastMissPath = path
        if (lastMissPackage != packageName || lastMissPrefix.isEmpty()) {
            lastMissPackage = packageName
            lastMissPrefix = FuseRedirectStats.prefixOf(path)
            return
        }
        val prefix = FuseRedirectStats.prefixOf(path)
        if (prefix != lastMissPrefix) {
            lastMissPrefix = prefix
            lastMissPackage = packageName
        }
    }

    /** 快照序列化：`fuseRedirect` 段。 */
    fun appendRedirectTo(json: JSONObject) {
        json.put("evaluated", evaluated.get())
        json.put("redirected", redirected.get())
        json.put("missed", missed.get())
        json.put("lastMissPackage", lastMissPackage)
        json.put("lastMissPrefix", lastMissPrefix)
        json.put("prefixSegments", FuseRedirectStats.DEFAULT_PREFIX_SEGMENTS)
    }
}
