package me.gm.cleaner.runtime.mediaprovider.hook.fuse

/**
 * FUSE 文件事件的**前置去重**（纯逻辑，零 Android / Xposed 依赖，时钟可注入）。
 *
 * ## 为什么做前置去重，而不是合并事件
 *
 * 实测（第 6 批）`events/filesystem` 归档 **124** 条，而抓到的最近 20 条**完全相同**
 * （同包 `com.deepseek.chat` / 同路径 `/storage/emulated/0/dsprobe_m.log` / 同方法
 * `insertFileIfNecessaryForFuse`），时间跨度只有 **1.6 s**。每条事件要付
 * **2 次 fsync**（counter + event file，bus 根在 f2fs），而这些重复在**下游本来就是被折叠的**：
 *
 * - 消费者每 **2 s** 轮询一轮，一轮内同 key 的多条事件会依次送进 `FileSystemObserver`；
 * - `FileSystemRecord` 表里没有计数字段，且 `upsertRecords=true` 时 DAO 走
 *   `UPDATE OR IGNORE … WHERE package_name=? AND path=? AND flags=?` ⇒
 *   **同 key 只留一行、只刷新时间戳**。
 *
 * 所以"同 key 在**一轮轮询内**重复"的信息量为零，只是白付 fsync 与目录条目。
 * 窗口取 [DEFAULT_WINDOW_MS] = 消费者的轮询周期，语义即：
 * **每个 key 每轮最多产生一条事件**（这一条正好就是下游会留下的那一行）。
 *
 * ⚠️ 这是**前置去重**，不是合并事件：事件的 JSON schema、"一事件一文件"、
 * 游标语义、signal 尾沿必达全部不变；只是不再写出下游注定要折叠掉的重复项。
 *
 * 触发侧 `dispatchFileSystemEvent` 的 `Log.d` 计数**保持在去重之前**，
 * 因此 P4 判据（`Signal sent: filesystem_events_changed` ÷ `dispatchFileSystemEvent`）
 * 的分母语义不受影响。
 */
internal class FuseEventDedup @JvmOverloads constructor(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    companion object {
        /**
         * 去重窗口 = 消费者轮询周期（`EventConsumerScheduler` 固定 2 s）。
         * 取大于它的值会开始吞掉跨轮事件，取更小则留不住同一轮内的重复。
         */
        const val DEFAULT_WINDOW_MS = 2_000L

        /**
         * 表上限。FUSE 事件热路径上同时活跃的 key（pkg × path × flags × method）
         * 数量很小（实测一个会话几十个），512 足够；超出按插入序淘汰最旧项，
         * 淘汰只会让某个 key 更早恢复发射，不会丢事件。
         */
        const val DEFAULT_MAX_ENTRIES = 512
    }

    private class Entry(val lastEmitAt: Long)

    /** 插入序 LinkedHashMap：淘汰时取表头即最旧。 */
    private val entries = LinkedHashMap<String, Entry>()

    private var suppressed = 0L

    /** 累计抑制（未写出）的事件数。用于在诊断归档里证明本机制确实在生效。 */
    val suppressedCount: Long
        get() = synchronized(this) { suppressed }

    /** 当前表内 key 数（诊断/单测用）。 */
    val trackedKeys: Int
        get() = synchronized(this) { entries.size }

    /**
     * 判定本次事件是否应当写出。
     *
     * @param key 事件身份（`pkg\npath\nflags\nmethodName`），**不含时间**
     * @param nowMillis 当前时刻
     * @return `true` = 写出（并刷新该 key 的时间戳）；`false` = 与窗口内同 key 重复，抑制
     */
    fun shouldEmit(key: String, nowMillis: Long): Boolean = synchronized(this) {
        val hit = entries[key]
        if (hit != null && nowMillis - hit.lastEmitAt < windowMs) {
            suppressed++
            return false
        }
        // 过窗：移除后按新时间戳重新插入，同时把它挪到表尾（LRU 语义）。
        entries.remove(key)
        while (entries.size >= maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        entries[key] = Entry(nowMillis)
        return true
    }

    /** 清空状态（单测用）。 */
    fun reset() = synchronized(this) {
        entries.clear()
        suppressed = 0L
    }
}
