package me.gm.cleaner.runtime.mediaprovider.hook.bridge

/**
 * [EventSignalCoalescer] 的合并状态机（纯逻辑，无 Android / Timer 依赖，可单测）。
 *
 * ## 它要保证什么
 * 对**每一次** signal 请求，都必须存在一次「发射时刻晚于该请求」的 flush —— 否则
 * 消费者会把该事件永久跳过（见下）。
 *
 * ## 为什么合并 signal 是安全的
 * 消费者按**固定周期**轮询，signal 只是「本轮别跳过」的门闸，而不是唤醒机制：
 * - `EventConsumerScheduler.scheduleNext()` 无条件每 2s 调一次 `pollOnce()`
 * - `FileSystemEventConsumer.pollAndConsume()` 仅在
 *   `signal 时间戳 <= 上次已确认时间戳` 时**直接 return 0**（跳过目录扫描）
 * - `CleanerServerCallback.signalDataBus()` 只做「校验 + 写 signal 文件」，**不触发消费**
 *
 * 因此 signal 少发只是让轮询少扫几次目录；但**漏发**会让事件一直停在游标之后 ——
 * 除非后续还有别的 signal 把时间戳推上去。所以「尾沿必达」是硬要求。
 *
 * ## 状态机
 * - [onRequest]：登记一次请求；返回 true 表示调用方**需要排一个 flush**。
 * - [beginFlush] / [endFlush]：flush 前后取水位。若 flush 期间又有新请求（水位上涨），
 *   [endFlush] 返回 true，调用方**必须再排一轮**，从而覆盖那些请求。
 * - [onScheduleFailed]：排程失败时复位，使后续请求能重新排程（退化为直发，绝不进入
 *   「永远 scheduled=true、再也不发」的死锁态）。
 */
internal class SignalCoalesceState {
    private val lock = Any()

    /** 是否有在途（已排程或正在执行）的 flush。 */
    private var scheduled = false

    /** 请求水位：每次 [onRequest] 自增。 */
    private var requestEpoch = 0L

    /** 登记一次 signal 请求。返回 true 表示需要由调用方排一个 flush。 */
    fun onRequest(): Boolean = synchronized(lock) {
        requestEpoch++
        if (scheduled) {
            false
        } else {
            scheduled = true
            true
        }
    }

    /** flush 发射**之前**调用，取当前请求水位。 */
    fun beginFlush(): Long = synchronized(lock) { requestEpoch }

    /**
     * flush 发射**之后**调用。
     *
     * @return true 表示 flush 期间出现了新请求 ⇒ 需要再排一轮；
     *         false 表示已覆盖全部请求且已复位（后续请求可重新排程）。
     */
    fun endFlush(epochBeforeFlush: Long): Boolean = synchronized(lock) {
        if (requestEpoch == epochBeforeFlush) {
            scheduled = false
            false
        } else {
            true
        }
    }

    /** 排程失败：复位，避免永久卡在「已排程」状态。 */
    fun onScheduleFailed() = synchronized(lock) {
        scheduled = false
    }
}
