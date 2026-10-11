package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指标键契约测试：防止 DiagnosticArchive 消费方与 Reporter 产出方再次漂移。
 *
 * 历史缺陷：Archive 手写字面量 "appliedPublisherEpoch" / "wakeOnlyMode" /
 * "destructiveRounds"，与 Reporter 实际输出的 nativeAppliedPublisherEpoch /
 * mediaProviderWakeOnlyMode / mediaProviderDestructiveRounds 不一致，
 * 导致 FUSE 同步摘要与恢复熔断信号在诊断概览中永不显示。
 */
class MetricKeyContractTest {

    @Test
    fun `MediaProviderHook产出含全部契约键`() {
        // collect 为纯函数（无 Android API），可直接 JVM 验证
        val report = MediaProviderHookLayerReporter.collect(
            generation = 1L,
            now = 1_000L,
            hooksBridgeConnected = false,
            mediaProviderHookConnected = false,
            recovery = RuntimeRecoverySnapshot(
                hook = HookRecoveryCoordinator.RecoverySnapshot(),
                mediaProvider = MediaProviderRecoveryStrategy.RecoverySnapshot(
                    destructiveRounds = 3,
                    wakeOnlyMode = true,
                ),
            ),
        )
        val metrics = report.metrics
        // 契约常量必须与产出 map 的键一一对应
        assertTrue(metrics.containsKey(MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE))
        assertTrue(metrics.containsKey(MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS))
        // 常量值即真实输出键名（固定字符串，防将来被改成另一种拼写）
        assertEquals("mediaProviderWakeOnlyMode", MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE)
        assertEquals(
            "mediaProviderDestructiveRounds",
            MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS,
        )
        // 熔断态数值必须真实透出，不恒为默认值
        assertEquals("3", metrics[MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS])
        assertEquals("true", metrics[MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE])
    }

    @Test
    fun `NativeHook契约键名固定`() {
        assertEquals("snapshotPublisherEpoch", NativeHookLayerReporter.KEY_SNAPSHOT_EPOCH)
        assertEquals("nativeAppliedPublisherEpoch", NativeHookLayerReporter.KEY_APPLIED_EPOCH)
        assertEquals("nativePolicySynced", NativeHookLayerReporter.KEY_POLICY_SYNCED)
    }

    @Test
    fun `VfsProcessCensus契约键名固定`() {
        assertEquals("vfsManagedPids", me.gm.cleaner.runtime.server.vfs.VfsProcessCensus.KEY_MANAGED)
        assertEquals(
            "vfsUnmanagedPids",
            me.gm.cleaner.runtime.server.vfs.VfsProcessCensus.KEY_UNMANAGED,
        )
        assertEquals("srStatusTotal", me.gm.cleaner.runtime.server.vfs.VfsProcessCensus.KEY_SR_TOTAL)
        assertEquals(
            "srStatusTruncated",
            me.gm.cleaner.runtime.server.vfs.VfsProcessCensus.KEY_SR_TRUNCATED,
        )
    }
}
