package me.gm.cleaner.runtime.mediaprovider.hook.err

import android.os.SystemClock

/**
 * MediaProvider（LSPosed 模块）进程内的结构化错误流水。
 *
 * **为什么需要它**：模块进程此前只有 `android.util.Log` 一条可观测路径，而
 * ColorOS 的 `LOG_FLOWCTRL` 按进程配额丢日志——实测单次会话 MediaProvider 丢
 * 237 行、cleaner_server 丢 2124 行，自动日志从 18 MB 截到 512 KB。于是
 * 「日志里没有」根本不能证明「没发生」（硬约束⑨）。
 *
 * 三个进程各有各的 journal，互不共享内存，本对象是**模块进程**那一份：
 * - `ServerErrorJournal`（server 进程）→ 诊断包 `errors/journal.jsonl`
 * - `ClientErrorJournal`（app 进程）→ 诊断包 `client/errors/journal.jsonl`
 * - `HookErrorJournal`（MediaProvider 进程，本对象）→ 随 `native_hook_status.json`
 *   的 `hookErrors` 段落盘，诊断包 `status/native_hook_status_pretty.json` 与
 *   `errors/hook_errors.jsonl` 均可见。
 *
 * **为什么走快照而不是新增 DataBus 事件队列**：新增队列必须配一个真实消费者，
 * 否则 `pendingEventCounts` 会无界增长并长期触发积压告警（既有教训）。
 * 快照通道是模块侧唯一已通、且已被诊断包消费的写入路径，复用它零新增告警面。
 *
 * 设计约束：
 * - **永不抛出、永不阻塞**：hot path（FUSE 方法 hook）上的调用只允许一次
 *   `synchronized` 与若干字段写入；
 * - **按 key 聚合**：同一 `(code, severity, subject, detail)` 永远只占一个槽位，
 *   累加 [Entry.count] 并刷新末次时刻、保留首次时刻。这使 "cannot find uid"
 *   这类高频预期告警无法把 50 个槽位刷满、把真正的错误挤出去；
 *   同时 [Entry.firstAtElapsed] / [Entry.lastAtElapsed] / [Entry.count] 三者
 *   已完整表达「何时开始、持续多久、发生了几次」，无需再引入合并时间窗——
 *   时间窗反而会在窗口外复发时制造「同 key 覆盖、槽位数不增长」的缺陷；
 * - **容量有界**：超过 [CAPACITY] 淘汰最旧槽位，防异常风暴下的内存无界增长；
 * - **纯 Kotlin、零 android 依赖（除时钟默认值）**：不写 logcat（调用点各自已
 *   `Log.*`，不制造重复日志），也不碰 `org.json`，因此可直接进 JVM 单测。
 */
object HookErrorJournal {

    /** 单测可见的槽位上限。 */
    internal const val CAPACITY: Int = 50

    /** 明细文本截断长度，与 NativeHookStatus 的 MAX_TEXT_LENGTH 保持同一口径。 */
    private const val MAX_DETAIL_LENGTH: Int = 200

    // ── 级别：取 android.util.Log 优先级首字母，便于人读与 logcat 对齐 ──
    const val SEVERITY_WARN: String = "W"
    const val SEVERITY_ERROR: String = "E"

    /**
     * 一条错误流水条目。
     *
     * 字段与 `core:common` 的 `ErrorEvent`（code/errno/subject/generation/detail）
     * 对齐，另加三个本进程专属字段：[severity] 原始级别、
     * [firstAtElapsed]/[lastAtElapsed] 首次与末次发生时刻、[count] 累计条数。
     * [detail] 语义为「最近一次的明细」——同 key 聚合下不保留历史明细。
     */
    data class Entry(
        val code: String,
        val severity: String = SEVERITY_WARN,
        val detail: String? = null,
        val subject: String? = null,
        val errno: Int = 0,
        val generation: Long = 0L,
        val firstAtElapsed: Long = 0L,
        val lastAtElapsed: Long = 0L,
        val count: Long = 1L,
    ) {
        /** 一行紧凑表示；不含明文路径，可直接写进快照与诊断包。 */
        fun toCompactString(): String = buildString {
            append(severity).append(' ').append(code)
            if (count > 1L) append(" x").append(count)
            if (errno != 0) append(" errno=").append(errno)
            subject?.let { append(" subject=").append(it) }
            if (generation > 0L) append(" gen=").append(generation)
            append(" at=").append(lastAtElapsed)
            detail?.let { append(" detail=").append(it) }
        }
    }

    private val lock = Any()

    /** 插入序即时间序；合并时先删后插，把槽位挪到末尾。 */
    private val slots = LinkedHashMap<String, Entry>(CAPACITY)

    /** 累计发生次数（含被合并的重复），用于回答"是否曾经发生过"。 */
    @Volatile
    private var totalRecorded: Long = 0L

    /**
     * 记录一条模块侧错误。永不抛出。
     *
     * @param nowElapsed 单调时钟毫秒；生产路径取 [SystemClock.elapsedRealtime]，
     *                   单测显式传入以规避未 mock 的 android 调用。
     * @return 本次写入后该槽位的条目（合并场景为更新后的条目）；
     *         参数非法（空 code 或负时间戳）时返回 null 且不改变任何状态。
     */
    fun record(
        code: String,
        severity: String,
        detail: String?,
        subject: String?,
        errno: Int,
        generation: Long,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
    ): Entry? {
        if (code.isEmpty() || nowElapsed < 0L) return null
        val normalizedDetail = detail?.take(MAX_DETAIL_LENGTH)
        val key = buildString {
            append(code).append('').append(severity).append('')
            append(subject ?: "").append('').append(normalizedDetail ?: "")
        }
        return synchronized(lock) {
            val existing = slots[key]
            val merged = if (existing != null) {
                // 同一 key 再次出现：累加计数、刷新末次时刻、保留首次时刻。
                // 先删后插，使该槽位在淘汰序里变成最新（其余槽位相对变旧）。
                slots.remove(key)
                existing.copy(
                    lastAtElapsed = maxOf(existing.lastAtElapsed, nowElapsed),
                    count = existing.count + 1L,
                )
            } else {
                if (slots.size >= CAPACITY) {
                    slots.keys.firstOrNull()?.let { slots.remove(it) }
                }
                Entry(
                    code = code,
                    severity = severity,
                    detail = normalizedDetail,
                    subject = subject,
                    errno = errno,
                    generation = generation,
                    firstAtElapsed = nowElapsed,
                    lastAtElapsed = nowElapsed,
                    count = 1L,
                )
            }
            slots[key] = merged
            totalRecorded += 1L
            merged
        }
    }

    /** 按末次时刻升序的只读快照；调用方持有副本。 */
    fun snapshot(): List<Entry> = synchronized(lock) {
        slots.values.sortedBy { it.lastAtElapsed }
    }

    /** 当前占用槽位数（同类合并后），用于自检容量是否被单一错误刷满。 */
    fun size(): Int = synchronized(lock) { slots.size }

    /** 累计发生次数（含合并）。 */
    fun total(): Long = totalRecorded

    /** 清空流水（模块重新初始化或用户清除诊断数据）。 */
    fun clear() = synchronized(lock) {
        slots.clear()
        totalRecorded = 0L
        Unit
    }
}
