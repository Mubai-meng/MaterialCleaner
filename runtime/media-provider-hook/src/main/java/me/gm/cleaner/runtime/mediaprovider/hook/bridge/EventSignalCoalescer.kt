package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import android.util.Log
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

/**
 * DataBus **事件类 signal** 的合并器（尾沿保证 / 限流发射）。
 *
 * ## 解决什么
 * 三个事件生产者原先都是「写一个事件 + 立刻发一条 signal」：
 * - `FuseJavaGate.dispatchFileSystemEvent` → `filesystem_events_changed`
 * - `InsertHooker` / `QuerySessionCache.emitMediaNotFound` → `redirect_notice_events_changed`
 * - `QuerySessionCache.maybeRegister...` → `query_session_leases_changed`
 *
 * 实测单个突发事件窗口内 `filesystem_events_changed` 发了 **50 条** signal，
 * 对应的那一秒里 DataBus 自身打了 **96 行**日志（每事件一次写 + 每次写一条 `Signal sent:`）。
 * 而消费者是**固定 2s 轮询**、signal 只做「本轮别跳过」的门闸
 * （无唤醒作用，见 [SignalCoalesceState]），因此同一窗口内的重复 signal 是纯冗余。
 *
 * ## 语义
 * 同一 signal 名的两次发射至少间隔 [WINDOW_MILLIS]。**尾沿必达**：无论请求如何交错，
 * 每个请求之后都一定会有一次发射（由 [SignalCoalesceState.endFlush] 保证）。
 *
 * ## 为什么不影响时延
 * [WINDOW_MILLIS] = 250ms，远小于消费者轮询周期（2s）与 policy 刷新周期（5s），
 * 对端到端时延不可观测；稳态突发下 signal 数被限流到 ≤4/s。
 *
 * ## 为什么不是「严格去重（内容不变就不发）」
 * 消费者判据是 signal 文件**时间戳是否前进**，而不是内容；不发就等于让事件滞留。
 */
object EventSignalCoalescer {
    private const val TAG = "EventSignalCoalescer"

    /** 同一 signal 名的最小发射间隔（毫秒）。 */
    internal const val WINDOW_MILLIS = 250L

    private val states = ConcurrentHashMap<String, SignalCoalesceState>()
    private val timer = Timer("EventSignalCoalesce", true)

    /**
     * 请求发射一个 signal（合并后异步发射）。
     *
     * 本方法被 FUSE 热路径调用，**绝不能向上抛异常**：任何内部失败都退化为直发。
     */
    fun signal(name: String) {
        try {
            val state = states.computeIfAbsent(name) { SignalCoalesceState() }
            if (!state.onRequest()) {
                // 已有在途 flush：本次请求由它（或它触发的补一轮）覆盖。
                return
            }
            try {
                scheduleFlush(name)
            } catch (e: Exception) {
                // 排程失败（如 Timer 线程已死）：复位后直发，避免状态卡死导致事件永久滞留。
                state.onScheduleFailed()
                Log.w(TAG, "schedule failed for $name, emitting directly", e)
                runCatching { HookDataBusBridge.signal(name) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "signal coalescing failed for $name, emitting directly", e)
            runCatching { HookDataBusBridge.signal(name) }
        }
    }

    private fun scheduleFlush(name: String) {
        timer.schedule(object : TimerTask() {
            override fun run() {
                try {
                    flush(name)
                } catch (e: Exception) {
                    // 与 HookPolicyRefreshScheduler 一致：单次失败只记录，不让 Timer 线程静默死掉。
                    Log.e(TAG, "flush failed: $name", e)
                }
            }
        }, WINDOW_MILLIS)
    }

    private fun flush(name: String) {
        val state = states[name] ?: return
        // 先取水位、再发射：任何在本行之前登记的请求都被这次发射的时间戳覆盖。
        val epochBeforeFlush = state.beginFlush()
        HookDataBusBridge.signal(name)
        if (state.endFlush(epochBeforeFlush)) {
            // 发射期间又有新请求进来 ⇒ 再补一轮，保证尾沿必达。
            scheduleFlush(name)
        }
    }
}
