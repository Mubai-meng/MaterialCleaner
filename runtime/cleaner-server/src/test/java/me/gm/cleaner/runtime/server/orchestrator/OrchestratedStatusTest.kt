package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 整体健康评估单测。
 *
 * 重点覆盖「启动中」与「降级」的边界：2026-10-22 22:06 开机实测，
 * 兼容层在策略收敛期（约 26 秒）处于 RECOVERING，旧口径报 DEGRADED，
 * 状态卡因此显示「存储重定向降级运行」，与实际能力不符。
 */
class OrchestratedStatusTest {

    private fun report(id: LayerId, state: LayerState, error: String? = null) = LayerReport(
        id = id,
        state = state,
        generation = 1L,
        lastError = error,
    )

    private fun evaluate(
        vfs: LayerState = LayerState.HEALTHY,
        mp: LayerState = LayerState.HEALTHY,
        fuse: LayerState = LayerState.HEALTHY,
        bus: LayerState = LayerState.HEALTHY,
        control: LayerState = LayerState.HEALTHY,
        compatInitializingMs: Long = 0L,
    ): OverallHealth = OrchestratedStatus.evaluate(
        vfs = report(LayerId.VFS, vfs),
        mediaProviderJavaHook = report(LayerId.MEDIA_PROVIDER_JAVA_HOOK, mp),
        fuseNativeHook = report(LayerId.FUSE_NATIVE_HOOK, fuse),
        dataBus = report(LayerId.DATA_BUS, bus),
        controlPlane = report(LayerId.CONTROL_PLANE, control),
        compatInitializingMs = compatInitializingMs,
    ).health

    @Test
    fun `全部健康报 HEALTHY`() {
        assertEquals(OverallHealth.HEALTHY, evaluate())
    }

    @Test
    fun `兼容层真故障一律报 DEGRADED`() {
        val failures = listOf(
            LayerState.DEGRADED,
            LayerState.UNAVAILABLE,
            LayerState.STALE,
            LayerState.DISABLED,
        )
        for (state in failures) {
            assertEquals("mp=$state", OverallHealth.DEGRADED, evaluate(mp = state))
            assertEquals("fuse=$state", OverallHealth.DEGRADED, evaluate(fuse = state))
        }
    }

    @Test
    fun `兼容层初始化中且未超宽限报 STARTING`() {
        assertEquals(
            OverallHealth.STARTING,
            evaluate(mp = LayerState.RECOVERING, compatInitializingMs = 1L),
        )
        assertEquals(
            OverallHealth.STARTING,
            evaluate(fuse = LayerState.RECOVERING, compatInitializingMs = 5_000L),
        )
        assertEquals(
            OverallHealth.STARTING,
            evaluate(mp = LayerState.UNINITIALIZED, compatInitializingMs = 200L),
        )
    }

    @Test
    fun `宽限期边界取闭区间`() {
        assertEquals(
            OverallHealth.STARTING,
            evaluate(fuse = LayerState.RECOVERING, compatInitializingMs = OrchestratedStatus.STARTING_GRACE_MS),
        )
        assertEquals(
            OverallHealth.DEGRADED,
            evaluate(
                fuse = LayerState.RECOVERING,
                compatInitializingMs = OrchestratedStatus.STARTING_GRACE_MS + 1,
            ),
        )
    }

    @Test
    fun `未提供宽限时长时保持历史行为`() {
        // compatInitializingMs=0 表示调用方未提供 ⇒ 初始化态仍算降级，
        // 避免不感知时间的调用点（如 uninitialized()）静默改变行为。
        assertEquals(OverallHealth.DEGRADED, evaluate(mp = LayerState.RECOVERING))
    }

    @Test
    fun `真故障优先于初始化态`() {
        assertEquals(
            OverallHealth.DEGRADED,
            evaluate(
                mp = LayerState.RECOVERING,
                fuse = LayerState.DEGRADED,
                compatInitializingMs = 1L,
            ),
        )
    }

    @Test
    fun `VFS 不可用或禁用报 CRITICAL`() {
        assertEquals(OverallHealth.CRITICAL, evaluate(vfs = LayerState.UNAVAILABLE))
        assertEquals(OverallHealth.CRITICAL, evaluate(vfs = LayerState.DISABLED))
        // 核心层挂了就不能只报降级，即使兼容层全好
        assertEquals(OverallHealth.CRITICAL, evaluate(vfs = LayerState.UNAVAILABLE))
    }

    @Test
    fun `VFS 自身初始化中报 STARTING`() {
        assertEquals(OverallHealth.STARTING, evaluate(vfs = LayerState.UNINITIALIZED))
        assertEquals(OverallHealth.STARTING, evaluate(vfs = LayerState.RECOVERING))
    }

    @Test
    fun `非兼容层异常不影响整体健康口径`() {
        // dataBus / controlPlane 不是健康判据的一部分（历史口径），保持不因它们翻脸
        assertEquals(
            OverallHealth.HEALTHY,
            evaluate(bus = LayerState.DEGRADED, control = LayerState.UNAVAILABLE),
        )
    }
}
