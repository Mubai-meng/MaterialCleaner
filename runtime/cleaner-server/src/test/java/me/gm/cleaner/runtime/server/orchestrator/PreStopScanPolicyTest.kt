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
    fun `执行前确认无进程返回空集合而非中止`() {
        // Success(empty) 是"确认没有"，不是"无法确认"：记录空目标继续执行。
        val result = PreStopScanPolicy.resolve(MediaProcessScan.Success(emptyMap()))
        assertEquals(emptyMap<Int, Long>(), result)
    }

    @Test
    fun `执行前扫描失败必须中止`() {
        // 硬约束：无法确认身份 → 禁止盲杀。
        // 决策阶段扫描成功只证明当时观察过，不充当执行前身份依据，
        // 因此本判定只看执行前扫描结果。
        assertNull(PreStopScanPolicy.resolve(MediaProcessScan.Unavailable))
    }
}
