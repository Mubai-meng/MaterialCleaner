package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

/** 生产取值。文件级声明，使嵌套的 [SignalCoalesceStateTest.Simulator] 也能引用。 */
private val window = EventSignalCoalescer.WINDOW_MILLIS

/**
 * [SignalCoalesceState] 的**尾沿必达**验证。
 *
 * 这里的核心不变量只有一条：
 * **对每一个 signal 请求 R，都必须存在一次发射 F 且 F > R。**
 * 因为消费者（`FileSystemEventConsumer` / `RedirectNoticeConsumer`）把
 * 「signal 时间戳未前进」当成「没有新事件」而**直接跳过本轮消费** ——
 * 漏发一次的后果是事件一直停在游标之后，直到下一个无关事件把它顺带带出来。
 */
class SignalCoalesceStateTest {

    // ── 状态机单步行为 ─────────────────────────────────────────────

    @Test
    fun `首次请求需要排程`() {
        assertTrue(SignalCoalesceState().onRequest())
    }

    @Test
    fun `在途flush期间的重复请求不再排程`() {
        val state = SignalCoalesceState()
        assertTrue(state.onRequest())
        assertFalse(state.onRequest())
        assertFalse(state.onRequest())
    }

    @Test
    fun `flush覆盖全部在途请求后复位`() {
        val state = SignalCoalesceState()
        state.onRequest()
        state.onRequest()

        val epoch = state.beginFlush()
        assertFalse("flush 期间无新请求，应复位", state.endFlush(epoch))
        assertTrue("复位后新请求可重新排程", state.onRequest())
    }

    @Test
    fun `flush期间到达的请求要求再排一轮`() {
        val state = SignalCoalesceState()
        state.onRequest()

        val epoch = state.beginFlush()
        state.onRequest() // 发射瞬间的竞态请求
        assertTrue("必须再排一轮覆盖它", state.endFlush(epoch))

        // (a) 补的那一轮里**又**有竞态请求 ⇒ 仍需再排一轮。
        val secondEpoch = state.beginFlush()
        state.onRequest()
        assertTrue("补的那轮里再有请求，仍须继续补", state.endFlush(secondEpoch))

        // (b) 补的那一轮**没有**竞态请求 ⇒ 结束后复位，后续请求可重新排程。
        val thirdEpoch = state.beginFlush()
        assertFalse("无竞态请求时，该轮结束后应复位", state.endFlush(thirdEpoch))
        assertTrue("复位后必须能重新排程", state.onRequest())
    }

    @Test
    fun `排程失败后允许重新排程`() {
        val state = SignalCoalesceState()
        assertTrue(state.onRequest())
        state.onScheduleFailed()
        assertTrue("失败复位后必须能重新排程，否则事件永久滞留", state.onRequest())
    }

    // ── 需求侧不变量：尾沿必达 + 有界覆盖 ─────────────────────────

    /**
     * 忠实模拟 [EventSignalCoalescer] 的时序：请求按时间到达，flush 按排程时间执行，
     * 执行时先取水位再发射，若期间有新请求则再补一轮。
     */
    private class Simulator {
        private val state = SignalCoalesceState()
        private val scheduledFlushes = PriorityQueue<Long>()
        private val emissions = mutableListOf<Long>()
        private val requests = mutableListOf<Long>()

        val emittedAt: List<Long> get() = emissions
        val requestedAt: List<Long> get() = requests

        fun runUntil(timeMs: Long) {
            while (scheduledFlushes.isNotEmpty() && scheduledFlushes.peek() <= timeMs) {
                val at = scheduledFlushes.poll()
                val epochBeforeFlush = state.beginFlush()
                emissions += at
                if (state.endFlush(epochBeforeFlush)) {
                    scheduledFlushes += at + window
                }
            }
        }

        fun request(atMs: Long) {
            requests += atMs
            if (!state.onRequest()) return
            scheduledFlushes += atMs + window
        }
    }

    private fun assertTrailingEdgeGuaranteed(sim: Simulator) {
        for (requestedAt in sim.requestedAt) {
            val covering = sim.emittedAt.filter { it > requestedAt }
            assertTrue(
                "请求 @${requestedAt}ms 之后没有任何发射 ⇒ 事件会被消费者永久跳过",
                covering.isNotEmpty(),
            )
            val latency = covering.min() - requestedAt
            assertTrue(
                "请求 @${requestedAt}ms 的覆盖发射迟了 ${latency}ms（上限 ${window}ms）",
                latency <= window,
            )
        }
    }

    @Test
    fun `单次请求在窗口内被覆盖`() {
        val sim = Simulator()
        sim.runUntil(0L)
        sim.request(0L)
        sim.runUntil(100L)
        assertTrue("窗口未到不该发射", sim.emittedAt.isEmpty())

        sim.runUntil(window)
        assertEquals(listOf(window), sim.emittedAt)
        assertTrailingEdgeGuaranteed(sim)
    }

    @Test
    fun `一秒内50个事件的突发被限流到个位数且尾沿必达`() {
        val sim = Simulator()
        val requests = 50
        for (i in 0 until requests) {
            val at = i * 20L // 0,20,…,980 —— 复现实测的 50 event / 1s 突发
            sim.runUntil(at)
            sim.request(at)
        }
        sim.runUntil(10_000L)

        assertTrailingEdgeGuaranteed(sim)
        assertTrue(
            "突发应变被显著限流，实际发射 ${sim.emittedAt.size} 次",
            sim.emittedAt.size <= 5,
        )
        assertTrue(
            "限流比应超过 5 倍（请求 $requests 次，发射 ${sim.emittedAt.size} 次）",
            sim.emittedAt.size * 5 <= requests,
        )
    }

    @Test
    fun `最后一个请求必达 即使它是突发的末尾`() {
        val sim = Simulator()
        val lastRequestAt = 980L
        for (i in 0..49) {
            val at = i * 20L
            sim.runUntil(at)
            sim.request(at)
        }
        sim.runUntil(10_000L)

        assertTrue(
            "突发末尾的请求 @${lastRequestAt}ms 必须被覆盖",
            sim.emittedAt.any { it > lastRequestAt },
        )
    }
}
