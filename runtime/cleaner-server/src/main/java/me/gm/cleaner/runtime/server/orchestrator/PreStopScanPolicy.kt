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
     * @return 要记录的观测实例；null 表示无法确认身份或确认无活进程，
     *   调用方必须中止本轮破坏操作（回退探测）。
     *
     * 成功但空集合同样中止：决策层对 Success(empty) 走 PROBE_ONLY，
     * 执行层不得因“包已安装”扩大到包级强杀。无活进程时正确路径是
     * 唤醒+重探测，包级清理需独立准入，不搭本轮便车。
     */
    fun resolve(preStopScan: MediaProcessScan): Map<Int, Long>? = when (preStopScan) {
        is MediaProcessScan.Success ->
            if (preStopScan.instances.isEmpty()) null else preStopScan.instances
        is MediaProcessScan.Unavailable -> null
    }

    /**
     * 从 uid 推导 userId（纯函数，可单测）。
     *
     * 等价于 UserHandle.getUserId(uid) = uid / 100000，避免在策略层
     * 引入 Android 框架依赖。Strategy 侧用此函数填充观测目标。
     */
    fun userIdOf(uid: Int): Int = uid / 100000
}

/**
 * 观测目标（执行层证据）：扫描时刻确实发现的进程实例。
 *
 * 与操作目标区分：本类只证明“当时看到过”，最终 API 实际影响的是
 * 包+用户范围（见 forceStopMediaProviderPackages），两者关系必须在
 * 执行层显式检查并日志留痕，不得用本类冒充精确杀灭保证。
 */
internal data class ObservedTarget(
    val packageName: String,
    val userId: Int,
    val pid: Int,
    val startTime: Long,
)

/**
 * 操作目标：最终下发 force-stop 的包+用户组合。
 *
 * 由 resolveOperationTargets 从观测目标裁决而来（观测 ∩ 已安装），
 * 是包级 API 实际杀伤面的精确描述。不得包含无观测依据的组合。
 */
internal data class OperationTarget(
    val packageName: String,
    val userId: Int,
)

/** 同一份执行前快照派生的 pid→starttime 视图，供同实例守卫与记账使用。 */
internal fun List<ObservedTarget>.pidMap(): Map<Int, Long> =
    associate { it.pid to it.startTime }

/** 扫描结果：区分"确认无进程"与"无法确认进程状态"。 */
internal sealed interface MediaProcessScan {
    data class Success(val instances: Map<Int, Long>) : MediaProcessScan
    data object Unavailable : MediaProcessScan
}
