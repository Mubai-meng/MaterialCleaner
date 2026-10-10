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
internal object MediaProviderRecoveryPolicy {
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
        /**
         * 决策时的扫描结果。携带类型而非退化的 Map：
         * Unavailable 与 Success(empty) 是两种不同语义，不得混为一谈。
         */
        val mediaScan: MediaProcessScan,
        /**
         * 总账腐败态（存在但不可确认）。true 时禁止任何破坏性准入，
         * 只能 PROBE_ONLY，直到显式安全重置流程清除总账文件成功。
         * 带默认值以保持既有调用兼容，含义为“确认不腐败”。
         */
        val ledgerCorrupted: Boolean = false,
    )

    fun decide(now: Long, state: State): Decision {
        if (state.hookConnected) return Decision.CONNECTED
        // 腐败态优先于熔断态：熔断是已知轮次用尽，腐败是轮次未知，
        // 两者都不允许破坏，但腐败必须走探测而非低频唤醒，以便尽快发现恢复。
        if (state.ledgerCorrupted) return Decision.PROBE_ONLY
        if (state.destructiveRounds >= MAX_DESTRUCTIVE_ROUNDS) return Decision.WAKE_ONLY
        if (state.episodeStartMs <= 0L || now - state.episodeStartMs < STAGE1_WINDOW_MS) {
            return Decision.PROBE_ONLY
        }
        if (!state.thresholdReached) return Decision.PROBE_ONLY
        val lastRound = state.lastRound
        if (lastRound != null && now - lastRound.timeMs < POST_ROUND_WINDOW_MS) {
            return Decision.PROBE_ONLY
        }
        val currentMediaPids = when (state.mediaScan) {
            // 无法确认进程身份：禁止盲杀，只探。
            is MediaProcessScan.Unavailable -> return Decision.PROBE_ONLY
            // 确认没有活进程：probe-only 自带 wake，正是死进程的正确恢复路径，
            // 无需也未达到破坏性准入。
            is MediaProcessScan.Success ->
                if (state.mediaScan.instances.isEmpty()) {
                    return Decision.PROBE_ONLY
                } else {
                    state.mediaScan.instances
                }
        }
        if (!mayTargetInstances(lastRound, currentMediaPids)) {
            // 同一进程实例已承受过一次破坏性恢复：硬约束禁杀。
            return Decision.PROBE_ONLY
        }
        return Decision.MAY_FORCE_STOP
    }

    /**
     * 同实例守卫：观测集合中任一实例与上轮记录同身份则禁止再次破坏。
     * PID 被复用（starttime 不同）视为新实例，不在此限。
     *
     * 同时对执行前扫描复检：决策时扫描可能在 wake 与等待期间过期，
     * 只有执行前实例才是真正的杀灭对象。
     */
    fun mayTargetInstances(
        lastRound: RoundRecord?,
        observed: Map<Int, Long>,
    ): Boolean {
        if (lastRound == null || lastRound.targetPids.isEmpty()) return true
        val overlap = observed.any { (pid, start) ->
            pid in lastRound.targetPids && lastRound.targetStarts[pid] == start
        }
        return !overlap
    }

    /**
     * 组装总账 JSON（纯函数，无 IO 无日志，可单测）。
     *
     * 失败返回 null。文件原子写与 fsync 语义由 Strategy.persistLedger
     * 与 DataBusRecoveryLedger 保证，本函数只管组装。
     */
    fun buildLedgerJson(rounds: Int, round: RoundRecord?): String? {
        return try {
            org.json.JSONObject()
                .put("destructiveRounds", rounds)
                .put("lastRoundAt", round?.timeMs ?: 0L)
                .put("lastRoundPids", org.json.JSONArray(round?.targetPids?.toList() ?: emptyList<Int>()))
                .put("lastRoundStarts", org.json.JSONObject(
                    round?.targetStarts?.mapKeys { it.key.toString() }
                        ?: emptyMap<String, Long>(),
                ))
                .toString()
        } catch (e: Exception) {
            null
        }
    }
}
