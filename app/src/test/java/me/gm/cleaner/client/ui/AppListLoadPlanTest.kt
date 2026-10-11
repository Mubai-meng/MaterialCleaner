package me.gm.cleaner.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主界面列表加载顺序单测。
 *
 * 钉死两条：①首轮已是全量时**不得**重复刷新（避免列表闪烁）；
 * ②首轮降级但恢复失败时**不得**重载（服务仍不可用，白跑一遍全量扫描）。
 */
class AppListLoadPlanTest {

    @Test
    fun `首轮全量且恢复成功不重载`() {
        assertFalse(AppListLoadPlan.shouldReloadAfterRecovery(true, true))
    }

    @Test
    fun `首轮全量且恢复失败不重载`() {
        assertFalse(AppListLoadPlan.shouldReloadAfterRecovery(true, false))
    }

    @Test
    fun `首轮降级但恢复失败不重载`() {
        assertFalse(AppListLoadPlan.shouldReloadAfterRecovery(false, false))
    }

    @Test
    fun `首轮降级且恢复成功要重载`() {
        assertTrue(AppListLoadPlan.shouldReloadAfterRecovery(false, true))
    }
}
