package me.gm.cleaner.runtime.server.orchestrator

/**
 * 系统编排状态快照。
 *
 * 聚合所有五层的 [LayerReport]，并计算整体健康评估。
 *
 * 健康评估规则：
 * - 若 VFS 层为 HEALTHY，其他层均不为 UNAVAILABLE → [OverallHealth.HEALTHY]
 * - 若 VFS 层为 HEALTHY，但至少一个兼容层为 UNAVAILABLE/RECOVERING/STALE → [OverallHealth.DEGRADED]
 * - 若 VFS 层为 UNAVAILABLE → [OverallHealth.CRITICAL]
 * - 若 VFS 为 DISABLED → [OverallHealth.CRITICAL]
 *
 * @property health 整体健康评估
 * @property vfs VFS 层报告
 * @property mediaProviderJavaHook MediaProvider Java Hook 层报告
 * @property fuseNativeHook FUSE Native Hook 层报告
 * @property dataBus DataBus 层报告
 * @property controlPlane 控制面报告
 */
data class OrchestratedStatus(
    val health: OverallHealth,
    val vfs: LayerReport,
    val mediaProviderJavaHook: LayerReport,
    val fuseNativeHook: LayerReport,
    val dataBus: LayerReport,
    val controlPlane: LayerReport,
) {
    companion object {
        /**
         * 「初始化中」宽限期（毫秒）。
         *
         * 兼容层处于 RECOVERING / UNINITIALIZED 且持续时间在此以内时，整体健康报
         * [OverallHealth.STARTING] 而不是 [OverallHealth.DEGRADED]。
         *
         * 依据：开机后 native 侧要等 server 下发策略快照并完成一次 apply，
         * 期间 nativeGen 落后于 snapshotGen ⇒ [NativeSyncPolicy] 判 CONVERGING
         * ⇒ FUSE 层 RECOVERING。实测该窗口约 26 秒（2026-10-22 22:06 开机）。
         * 这段时间重定向能力是完整的（旧策略仍在生效），却显示「降级运行」。
         *
         * 超过本阈值仍处于初始化态 ⇒ 真的卡住了，仍报 DEGRADED，避免掩盖故障。
         */
        const val STARTING_GRACE_MS = 30_000L

        /** 兼容层的「真故障」态：出现即降级。 */
        private val COMPAT_FAILURE_STATES = setOf(
            LayerState.DEGRADED,
            LayerState.UNAVAILABLE,
            LayerState.STALE,
            LayerState.DISABLED,
        )

        /** 初始化/收敛态：宽限期内算「启动中」。 */
        private val INITIALIZING_STATES = setOf(
            LayerState.RECOVERING,
            LayerState.UNINITIALIZED,
        )

        /**
         * 根据各层报告计算整体健康状态。
         *
         * @param compatInitializingMs 兼容层已持续处于初始化态的时长（毫秒）。
         *   `0` 表示未知/不启用宽限（调用方未提供时保持历史上「初始化态即降级」的行为）。
         */
        @JvmStatic
        fun evaluate(
            vfs: LayerReport,
            mediaProviderJavaHook: LayerReport,
            fuseNativeHook: LayerReport,
            dataBus: LayerReport,
            controlPlane: LayerReport,
            compatInitializingMs: Long = 0L,
        ): OrchestratedStatus {
            val compat = listOf(mediaProviderJavaHook, fuseNativeHook)
            val health = when {
                vfs.state == LayerState.UNAVAILABLE || vfs.state == LayerState.DISABLED ->
                    OverallHealth.CRITICAL
                vfs.state == LayerState.HEALTHY || vfs.state == LayerState.DEGRADED -> when {
                    // 真故障优先：任何一层 DEGRADED/UNAVAILABLE/STALE/DISABLED 都不算「启动中」
                    compat.any { it.state in COMPAT_FAILURE_STATES } -> OverallHealth.DEGRADED
                    compat.any { it.state in INITIALIZING_STATES } ->
                        if (compatInitializingMs in 1..STARTING_GRACE_MS) {
                            OverallHealth.STARTING
                        } else {
                            OverallHealth.DEGRADED
                        }
                    else -> OverallHealth.HEALTHY
                }
                // VFS 自己尚未就绪：同样是「启动中」，不是降级
                vfs.state in INITIALIZING_STATES -> OverallHealth.STARTING
                else -> OverallHealth.DEGRADED
            }

            return OrchestratedStatus(
                health = health,
                vfs = vfs,
                mediaProviderJavaHook = mediaProviderJavaHook,
                fuseNativeHook = fuseNativeHook,
                dataBus = dataBus,
                controlPlane = controlPlane,
            )
        }

        /**
         * 创建全部未初始化的默认状态。
         */
        @JvmStatic
        fun uninitialized(): OrchestratedStatus = evaluate(
            vfs = LayerReport.uninitialized(LayerId.VFS),
            mediaProviderJavaHook = LayerReport.uninitialized(LayerId.MEDIA_PROVIDER_JAVA_HOOK),
            fuseNativeHook = LayerReport.uninitialized(LayerId.FUSE_NATIVE_HOOK),
            dataBus = LayerReport.uninitialized(LayerId.DATA_BUS),
            controlPlane = LayerReport.uninitialized(LayerId.CONTROL_PLANE),
        )
    }
}
