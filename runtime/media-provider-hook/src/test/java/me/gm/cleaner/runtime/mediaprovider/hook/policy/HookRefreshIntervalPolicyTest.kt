package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HookRefreshIntervalPolicy] 契约测试。
 *
 * 三条硬要求：
 * 1. 收敛窗口内必须用短间隔（这正是"状态页比其他层慢"的修复本体）；
 * 2. 窗口结束必须回到稳态 —— **不允许永久高频轮询**；
 * 3. 边界：无锚点 / 时钟回退 / 恰好在窗口边界。
 */
class HookRefreshIntervalPolicyTest {

    private val steady = HookRefreshIntervalPolicy.STEADY_INTERVAL_MS
    private val converge = HookRefreshIntervalPolicy.CONVERGE_INTERVAL_MS

    @Test
    fun `无锚点时用稳态间隔`() {
        assertEquals(steady, HookRefreshIntervalPolicy.nextInterval(1_000L, 0L))
    }

    @Test
    fun `锚点后立即进入收敛间隔`() {
        assertEquals(converge, HookRefreshIntervalPolicy.nextInterval(1_000L, 1_000L))
        assertEquals(converge, HookRefreshIntervalPolicy.nextInterval(5_000L, 1_000L))
    }

    @Test
    fun `窗口结束回到稳态`() {
        val anchor = 1_000L
        val boundary = anchor + HookRefreshIntervalPolicy.CONVERGENCE_WINDOW_MS
        // 窗口内
        assertEquals(converge, HookRefreshIntervalPolicy.nextInterval(boundary - 1, anchor))
        // 恰好到边界：now - anchor == WINDOW，不再小于 ⇒ 稳态
        assertEquals(steady, HookRefreshIntervalPolicy.nextInterval(boundary, anchor))
        assertEquals(steady, HookRefreshIntervalPolicy.nextInterval(boundary + 1, anchor))
    }

    @Test
    fun `时钟回退时宁可保守用稳态`() {
        assertEquals(steady, HookRefreshIntervalPolicy.nextInterval(500L, 1_000L))
    }

    @Test
    fun `锚点被最近一次配置前进前移`() {
        // 启动于 0，60s 后已回稳态；此时配置前进（gen 变化）把锚点推到 70s，
        // 于是 70s ~ 130s 之间重新进入收敛间隔。
        val anchor0 = 0L
        val t = HookRefreshIntervalPolicy.CONVERGENCE_WINDOW_MS + 10_000L
        assertEquals(steady, HookRefreshIntervalPolicy.nextInterval(t, anchor0))
        assertEquals(converge, HookRefreshIntervalPolicy.nextInterval(t, t))
        assertEquals(
            converge,
            HookRefreshIntervalPolicy.nextInterval(t + 1_000L, t),
        )
        assertEquals(
            steady,
            HookRefreshIntervalPolicy.nextInterval(t + HookRefreshIntervalPolicy.CONVERGENCE_WINDOW_MS, t),
        )
    }

    @Test
    fun `窗口与间隔的取值约束`() {
        // 收敛间隔必须严格小于稳态间隔，否则整个机制没有意义。
        assert(HookRefreshIntervalPolicy.CONVERGE_INTERVAL_MS < HookRefreshIntervalPolicy.STEADY_INTERVAL_MS)
        // 收敛窗口必须远大于收敛间隔，否则窗口内只轮询一两次。
        assert(HookRefreshIntervalPolicy.CONVERGENCE_WINDOW_MS >= 10 * HookRefreshIntervalPolicy.CONVERGE_INTERVAL_MS)
    }
}
