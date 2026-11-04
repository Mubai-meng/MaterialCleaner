package me.gm.cleaner.runtime.server

import me.gm.cleaner.runtime.server.orchestrator.HookRetryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hook 重连预算的钳制契约。
 *
 * 回归背景（2026-11-02 诊断包 A）：`mediaProviderJavaHook` 段出现
 * `hooksRetryCount=11` 而 `maxHookRetries=10` —— 一个自相矛盾的状态。
 * 根因是 `onHooksBinderDied()` 不受预算门控：预算耗尽后再死一次仍会排一轮重连，
 * 于是计数越过上限并继续无上界增长，"预算耗尽"与"还有余量"再也无法区分。
 */
class HookRetryBudgetTest {

    private val max = 10

    @Test
    fun `计数逐次加一直到上限`() {
        var count = 0
        repeat(max - 1) {
            count = HookRetryBudget.next(count, max)
            assertTrue("第 $count 次必须仍允许重试", HookRetryBudget.shouldContinue(count, max))
        }
        assertEquals(max - 1, count)
        // 第 max 次仍会被执行，但执行完之后不再排下一轮 —— 这正是"预算耗尽"的定义。
        count = HookRetryBudget.next(count, max)
        assertEquals(max, count)
        assertFalse(HookRetryBudget.shouldContinue(count, max))
    }

    @Test
    fun `越过上限后钳制在上限而不是继续增长`() {
        var count = max
        repeat(5) { count = HookRetryBudget.next(count, max) }
        assertEquals("计数不得越过上限（实测快照里出现过 11 > 10）", max, count)
    }

    @Test
    fun `上限处不再排下一轮`() {
        assertFalse(HookRetryBudget.shouldContinue(max, max))
        assertTrue(HookRetryBudget.shouldContinue(max - 1, max))
    }

    @Test
    fun `上限为零时不排也不加`() {
        assertEquals(0, HookRetryBudget.next(0, 0))
        assertFalse(HookRetryBudget.shouldContinue(0, 0))
    }
}
