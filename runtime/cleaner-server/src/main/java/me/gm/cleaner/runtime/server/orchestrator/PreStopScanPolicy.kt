package me.gm.cleaner.runtime.server.orchestrator

/**
 * 破坏性恢复前的进程实例观测裁决（纯函数）。
 *
 * 职责：只判定"能否确认当前进程身份"，不执行扫描、不执行 force-stop。
 * 扫描归 [MediaProviderRecoveryStrategy.scanMediaProcessInstances]，
 * 执行归 [MediaProviderRecoveryStrategy.forceStopRound]。
 *
 * 硬约束：无法确认身份时禁止盲杀。决策阶段的快照只证明"当时观察过"，
 * probe 的 wake 与等待期间实例可能已变，因此不能拿它充当执行前身份依据。
 */
internal object PreStopScanPolicy {

    /**
     * @param preStopScan 执行前最后一次扫描结果（唯一身份依据）
     * @return 要记录的观测实例；null 表示无法确认身份，调用方必须中止本轮破坏操作
     */
    fun resolve(preStopScan: MediaProcessScan): Map<Int, Long>? = when (preStopScan) {
        // 成功即权威：Success(empty) 表示确认没有进程，
        // 记录空目标并继续包级 force-stop（幂等），不是“无法确认”。
        is MediaProcessScan.Success -> preStopScan.instances
        is MediaProcessScan.Unavailable -> null
    }
}

/** 扫描结果：区分"确认无进程"与"无法确认进程状态"。 */
internal sealed interface MediaProcessScan {
    data class Success(val instances: Map<Int, Long>) : MediaProcessScan
    data object Unavailable : MediaProcessScan
}
