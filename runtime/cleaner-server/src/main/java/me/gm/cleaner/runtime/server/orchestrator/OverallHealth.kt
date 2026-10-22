package me.gm.cleaner.runtime.server.orchestrator

/**
 * 系统整体健康评估。
 *
 * 评估规则（由 [OrchestratedStatus] 计算）：
 * - [HEALTHY]：所有核心层（VFS）为 HEALTHY，兼容层处于非 CRITICAL 状态
 * - [STARTING]：兼容层仍在初始化/收敛中（RECOVERING / UNINITIALIZED），且未超过宽限期。
 *   这不是故障 —— 旧策略仍在生效，只是新一代策略尚未推到位。
 * - [DEGRADED]：VFS 仍为 HEALTHY，但至少一个兼容层（MediaProvider Hook 或 FUSE Native Hook）
 *   处于 UNAVAILABLE / STALE / DISABLED 状态，或初始化态已超过宽限期。
 *   核心存储重定向能力不受影响。
 * - [CRITICAL]：VFS 层不可用，存储重定向完全失效。
 */
enum class OverallHealth {
    /** 所有层健康 */
    HEALTHY,

    /**
     * 启动/收敛中：兼容层尚未就绪，但没有任何真正的失败。
     *
     * 与 [DEGRADED] 的区别是「预期会自己好」：开机后 native 侧要等策略快照下发并
     * 完成一次 apply，这期间 MediaProvider Hook 或 FUSE Native Hook 会短暂处于
     * RECOVERING。实测该窗口约 26 秒（2026-10-22 22:06 开机）。
     * 旧口径把这些窗口一并报成 [DEGRADED]，状态卡显示「存储重定向降级运行」，
     * 与实际能力（旧策略仍生效）不符。
     */
    STARTING,

    /** 降级运行，至少一个兼容层异常 */
    DEGRADED,

    /** 严重故障，核心层不可用 */
    CRITICAL,
}
