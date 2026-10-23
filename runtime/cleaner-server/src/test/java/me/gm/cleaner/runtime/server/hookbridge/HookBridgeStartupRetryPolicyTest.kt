package me.gm.cleaner.runtime.server.hookbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HookBridgeStartupRetryPolicy] 的阶梯与边界。
 *
 * 约束来源：桥宿主是 app 进程，开机时通常只差几十到几百毫秒就绪
 * （第 43 批实测 59 ms）⇒ 前三次必须够快；之后退到 2 s 上限，
 * 避免 app 被冻结时做无意义的跨进程往返。
 */
class HookBridgeStartupRetryPolicyTest {

    @Test
    fun `阶梯是 200 500 1000 然后封顶 2000`() {
        assertEquals(200L, HookBridgeStartupRetryPolicy.delayMillis(1))
        assertEquals(500L, HookBridgeStartupRetryPolicy.delayMillis(2))
        assertEquals(1_000L, HookBridgeStartupRetryPolicy.delayMillis(3))
        assertEquals(2_000L, HookBridgeStartupRetryPolicy.delayMillis(4))
        assertEquals(2_000L, HookBridgeStartupRetryPolicy.delayMillis(8))
        // 越界也不得抛异常（调用方可能因重入把 attempt 算大）
        assertEquals(2_000L, HookBridgeStartupRetryPolicy.delayMillis(99))
    }

    /** 单调不减：不允许出现「后一轮比前一轮更急」这种抖动。 */
    @Test
    fun `阶梯单调不减且有上界`() {
        var prev = 0L
        for (attempt in 1..HookBridgeStartupRetryPolicy.MAX_ATTEMPTS) {
            val d = HookBridgeStartupRetryPolicy.delayMillis(attempt)
            assertTrue("第 $attempt 次延迟应不小于前一次", d >= prev)
            assertTrue("单次延迟不得超过 2s", d <= 2_000L)
            prev = d
        }
    }

    @Test
    fun `尝试次数上界是 8 次`() {
        assertFalse("第 0 次不应继续", HookBridgeStartupRetryPolicy.shouldContinue(0))
        assertTrue(HookBridgeStartupRetryPolicy.shouldContinue(1))
        assertTrue(HookBridgeStartupRetryPolicy.shouldContinue(8))
        assertFalse("第 9 次必须停", HookBridgeStartupRetryPolicy.shouldContinue(9))
        assertFalse(HookBridgeStartupRetryPolicy.shouldContinue(-1))
    }

    /**
     * 总窗口必须**有界且落在十来秒**：
     * 太小 → 盖不住 app 冷启动；太大 → 等于给 app 冻结期加无谓 Binder 往返。
     */
    @Test
    fun `总窗口约十一秒`() {
        val total = HookBridgeStartupRetryPolicy.totalWindowMillis()
        assertEquals(11_700L, total)
        assertTrue("总窗口应在 10–15 s 之间", total in 10_000L..15_000L)
    }

    /**
     * 反向保护：这条阶梯只服务「onStart 刚失败」，因此**第一次尝试必须够快**，
     * 否则又退化成第 43 批那个 10 秒空窗。
     */
    @Test
    fun `首次尝试必须在半秒内`() {
        assertTrue(
            "首次延迟 ${HookBridgeStartupRetryPolicy.delayMillis(1)}ms 过长",
            HookBridgeStartupRetryPolicy.delayMillis(1) <= 500L,
        )
    }
}
