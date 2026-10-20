package me.gm.cleaner.runtime.mediaprovider.hook.bridge

/**
 * 有界事件缓冲（纯内存、纯逻辑，便于单测）。
 *
 * 动机：MediaProvider 的 SELinux 域写不了 `/data/local/tmp/cleaner/bus`，
 * server Binder 断开时文件兜底必然失败。旧实现失败一次就进入 30s 冷却，
 * 冷却期内**所有**事件直接丢弃 —— server 恢复后也补不回来。
 * 这里把失败事件按 FIFO 暂存（有界），待 Binder 恢复后由 [drainTo] 补写。
 *
 * 容量满时丢弃**最旧**的一条，保留最近事件（诊断价值更高）。
 */
internal class PendingEventBuffer(private val capacity: Int) {
    data class Event(val queue: String, val content: String)

    private val events = java.util.ArrayDeque<Event>()
    private val drainLock = Any()

    /**
     * 追加一条事件。
     * @return true 表示容量已满、本次丢弃了最旧的一条
     */
    @Synchronized
    fun offer(queue: String, content: String): Boolean {
        val overflow = events.size >= capacity
        if (overflow) {
            events.removeFirst()
        }
        events.addLast(Event(queue, content))
        return overflow
    }

    @Synchronized
    fun size(): Int = events.size

    /**
     * 按 FIFO 顺序把事件交给 [writer]；[writer] 返回 false 时停止并保留剩余事件。
     *
     * 同一时刻只允许一个 drain（[drainLock]），但 [offer] 可并发追加到队尾；
     * writer 调用期间不持有 buffer 锁，避免 Binder 调用阻塞事件写入线程。
     *
     * @return 成功写出的条数
     */
    fun drainTo(writer: (String, String) -> Boolean): Int {
        if (size() == 0) return 0
        synchronized(drainLock) {
            var written = 0
            while (true) {
                // 先摘除再写：writer 调用期间事件不在队列里，
                // 并发 offer 的溢出淘汰不会误删这条正在补写的事件。
                val event = synchronized(this) { events.pollFirst() } ?: break
                if (!writer(event.queue, event.content)) {
                    // 写失败：放回队首，保持原有顺序等下次补写。
                    synchronized(this) { events.addFirst(event) }
                    break
                }
                written++
            }
            return written
        }
    }
}
