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
