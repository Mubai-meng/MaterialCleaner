package me.gm.cleaner.runtime.server.orchestrator

/**
 * MediaProvider 恢复准入策略（Fix 2，纯函数）。
 *
 * 职责：只判定“本次允许做到哪一步”，不执行 wake/force-stop，
 * 不触碰 Binder、文件与 DataBus。IO 与执行归
 * [MediaProviderRecoveryStrategy]。
 *
 * 不变量：
 * - episode 以 Hook 可用性为中心：connected→missing 开，确认连上才结束；
 *   桥重连、server 重启都不重置 [destructiveRounds]（重启延续由落盘总账保证）。
 * - Stage 1（episode 内 75s，覆盖媒体侧约 61s 累计退避）：只允许非破坏性 probe。
 * - 破坏轮：同进程实例（PID+starttime）硬约束最多 1 次；轮后 75s 内禁下一轮；
 *   总量 3 轮熔断后只允许 WAKE_ONLY。
 */
object MediaProviderRecoveryPolicy {
    /** Stage 1 非破坏性观察窗：覆盖媒体侧累计退避 + 调度余量。 */
    const val STAGE1_WINDOW_MS = 75_000L

    /** 破坏轮后强制观察窗：覆盖新进程启动 + 完整突发重试。 */
    const val POST_ROUND_WINDOW_MS = 75_000L

    /** 破坏性恢复总量上限，达到后转 WAKE_ONLY。 */
    const val MAX_DESTRUCTIVE_ROUNDS = 3

    enum class Decision {
        /** 已连接，无需动作。 */
        CONNECTED,

        /** 只允许非破坏性 probe（wake + 轮询 Hook Binder）。 */
        PROBE_ONLY,

        /** 允许 probe，probe 失败后允许一次 force-stop。 */
        MAY_FORCE_STOP,

        /** 熔断：只允许低频 wake，禁止强杀。 */
        WAKE_ONLY,
    }

    data class RoundRecord(
        val timeMs: Long,
        val targetPids: Set<Int> = emptySet(),
        val targetStarts: Map<Int, Long> = emptyMap(),
    )

    data class State(
        val hookConnected: Boolean,
        /** episode 起点（首次确认缺失）；0 表示无 episode。 */
        val episodeStartMs: Long,
        /** 缺失计数是否达到阈值。 */
        val thresholdReached: Boolean,
        val lastRound: RoundRecord?,
        val destructiveRounds: Int,
        /** 当前观测到的媒体进程 PID→starttime；空表示无法观测。 */
        val currentMediaPids: Map<Int, Long>,
    )

    fun decide(now: Long, state: State): Decision {
        if (state.hookConnected) return Decision.CONNECTED
        if (state.destructiveRounds >= MAX_DESTRUCTIVE_ROUNDS) return Decision.WAKE_ONLY
        if (state.episodeStartMs <= 0L || now - state.episodeStartMs < STAGE1_WINDOW_MS) {
            return Decision.PROBE_ONLY
        }
        if (!state.thresholdReached) return Decision.PROBE_ONLY
        val lastRound = state.lastRound
        if (lastRound != null && now - lastRound.timeMs < POST_ROUND_WINDOW_MS) {
            return Decision.PROBE_ONLY
        }
        if (state.currentMediaPids.isEmpty()) {
            // 无法观测进程实例身份：禁止盲杀，只探。
            return Decision.PROBE_ONLY
        }
        if (lastRound != null && lastRound.targetPids.isNotEmpty()) {
            val overlap = state.currentMediaPids.any { (pid, start) ->
                pid in lastRound.targetPids && lastRound.targetStarts[pid] == start
            }
            // 同一进程实例已承受过一次破坏性恢复：硬约束禁杀。
            // PID 被复用（starttime 不同）则视为新实例，不在此限。
            if (overlap) return Decision.PROBE_ONLY
        }
        return Decision.MAY_FORCE_STOP
    }
}
