package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PreStopScanPolicy 纯函数单测：覆盖"确认无进程"与"无法确认"两类语义，
 * 以及决策阶段成功但执行前扫描失败的混合场景。
 */
class PreStopScanPolicyTest {

    @Test
    fun `执行前扫描成功返回观测实例`() {
        val instances = mapOf(100 to 50L, 200 to 60L)
        assertEquals(
            instances,
            PreStopScanPolicy.resolve(MediaProcessScan.Success(instances)),
        )
    }

    @Test
    fun `执行前确认无进程必须中止`() {
        // Success(empty) 是“确认没有活进程”：正确路径是唤醒+重探测，
        // 包级清理需独立准入，不搭本轮便车，故同样返回 null 中止。
        assertNull(PreStopScanPolicy.resolve(MediaProcessScan.Success(emptyMap())))
    }

    @Test
    fun `执行前扫描失败必须中止`() {
        // 硬约束：无法确认身份 → 禁止盲杀。
        // 决策阶段扫描成功只证明当时观察过，不充当执行前身份依据，
        // 因此本判定只看执行前扫描结果。
        assertNull(PreStopScanPolicy.resolve(MediaProcessScan.Unavailable))
    }

    @Test
    fun `uid推导userId与系统语义一致`() {
        // UserHandle.getUserId(uid) = uid / 100000。
        assertEquals(0, PreStopScanPolicy.userIdOf(10023))
        assertEquals(10, PreStopScanPolicy.userIdOf(1001023))
    }
}
