package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * Hook 侧策略刷新的**间隔决策**纯策略（无 android 依赖，便于 JVM 单测）。
 *
 * 问题：状态页里「FUSE 兼容层 · 状态过期 · 挂载点代次 6/7」每次都比其他层慢一会儿才变正常。
 * 原因（2026-10-10 第 15 批日志实测时序）：
 * ```
 * 19:16:58.084  server 发布 configured_mount_points（首次）
 * 19:16:59.660  HookPolicyRefreshScheduler 才启动（MediaProvider hook 引导完成）
 * 19:17:06.238  hook 才把 gen 3 推给 native      ← 其中 5s 是调度器首轮固定延迟
 * ```
 * 其他层（VFS / DataBus / 控制面）不依赖「hook 应用 + 上报」这条链，
 * 所以只有 FUSE 层要等，也就只有它显得慢。
 *
 * 两个可压缩点，且都不增加长期开销：
 * 1. **调度器首轮立即执行**（旧实现 `initialDelay = 5s`，白等一段固定时间）；
 * 2. **收敛期用短间隔**：从「调度器启动」或「最近一次 generation 真的前进」起算
 *   [CONVERGENCE_WINDOW_MS]，期间用 [CONVERGE_INTERVAL_MS]；窗口结束回到
 *   [STEADY_INTERVAL_MS] —— 稳态开销与旧实现**逐字节一致**。
 *
 * 锚点取 `max(启动时刻, 最近一次 generation 前进时刻)`：配置在 5 分钟后才变更时，
 * 变更本身会把锚点前移，快速轮询随之再持续一个窗口；配置长期不变时锚点逐渐过期，
 * 自动退回稳态。**不做任何永久性的高频轮询。**
 */
internal object HookRefreshIntervalPolicy {

    /** 稳态轮询间隔：与旧实现一致。 */
    const val STEADY_INTERVAL_MS: Long = 5_000L

    /** 收敛期轮询间隔。 */
    const val CONVERGE_INTERVAL_MS: Long = 2_000L

    /** 收敛窗口：自锚点（启动 / 最近一次 generation 前进）起算。 */
    const val CONVERGENCE_WINDOW_MS: Long = 60_000L

    /**
     * @param nowElapsed 当前单调时钟毫秒。
     * @param anchorElapsed 锚点（启动或最近一次 generation 前进）；0 表示尚无锚点。
     */
    fun nextInterval(nowElapsed: Long, anchorElapsed: Long): Long = when {
        anchorElapsed <= 0L -> STEADY_INTERVAL_MS
        nowElapsed < anchorElapsed -> STEADY_INTERVAL_MS // 时钟异常：宁可保守
        nowElapsed - anchorElapsed < CONVERGENCE_WINDOW_MS -> CONVERGE_INTERVAL_MS
        else -> STEADY_INTERVAL_MS
    }
}
