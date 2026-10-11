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

    /** 总账语义解析结果：只有 Valid 可恢复轮次，其余一律保守。 */
    sealed interface LedgerParsed {
        data class Valid(val rounds: Int, val round: RoundRecord?) : LedgerParsed
        data object Corrupted : LedgerParsed
    }

    /**
     * 解析并校验总账 JSON（纯函数，可单测）。
     *
     * 不用 opt* 默认值：缺失字段、类型错误、负轮次、异常时间戳、
     * pids/starts 失配一律 Corrupted。`{}` 不是空账本，而是不可确认的历史，
     * 必须保守——静默归零会重新放行破坏，方向偏危险。
     *
     * 校验规则按当前写入格式（见 buildLedgerJson）制定，不做版本框架。
     */
    fun parseLedger(json: String): LedgerParsed {
        val root = try {
            org.json.JSONObject(json)
        } catch (e: Exception) {
            return LedgerParsed.Corrupted
        }
        if (!root.has("destructiveRounds")) return LedgerParsed.Corrupted
        val rounds = try {
            root.getInt("destructiveRounds")
        } catch (e: Exception) {
            return LedgerParsed.Corrupted
        }
        if (rounds < 0 || rounds > MAX_DESTRUCTIVE_ROUNDS) return LedgerParsed.Corrupted
        val lastAt = try {
            if (!root.has("lastRoundAt")) 0L else root.getLong("lastRoundAt")
        } catch (e: Exception) {
            return LedgerParsed.Corrupted
        }
        if (lastAt < 0L) return LedgerParsed.Corrupted
        val pids = try {
            if (!root.has("lastRoundPids")) {
                mutableSetOf<Int>()
            } else {
                val arr = root.getJSONArray("lastRoundPids")
                (0 until arr.length()).map { arr.getInt(it) }.toMutableSet()
            }
        } catch (e: Exception) {
            return LedgerParsed.Corrupted
        }
        if (pids.any { it <= 0 }) return LedgerParsed.Corrupted
        val starts = try {
            if (!root.has("lastRoundStarts")) {
                mutableMapOf<Int, Long>()
            } else {
                val obj = root.getJSONObject("lastRoundStarts")
                val map = mutableMapOf<Int, Long>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val pid = key.toIntOrNull() ?: return LedgerParsed.Corrupted
                    map[pid] = obj.getLong(key)
                }
                map
            }
        } catch (e: Exception) {
            return LedgerParsed.Corrupted
        }
        if (starts.any { it.key <= 0 || it.value < 0L }) return LedgerParsed.Corrupted
        if (!starts.keys.all { it in pids }) return LedgerParsed.Corrupted
        val round = if (rounds > 0 || lastAt > 0L) {
            RoundRecord(timeMs = lastAt, targetPids = pids, targetStarts = starts)
        } else {
            null
        }
        return LedgerParsed.Valid(rounds, round)
    }

    /**
     * 由观测目标裁决操作目标（纯函数，可单测）。
     *
     * 操作集合 = 观测到的 (package,userId) ∩ 该用户下已安装。
     * 无观测的包/用户不杀——包级 API 杀伤面不得大于准入证据。
     * 安装检查以函数参数注入，保持纯函数可测。
     */
    fun resolveOperationTargets(
        observed: List<ObservedTarget>,
        isInstalled: (packageName: String, userId: Int) -> Boolean,
    ): Set<OperationTarget> =
        observed
            .map { OperationTarget(it.packageName, it.userId) }
            .toSet()
            .filter { isInstalled(it.packageName, it.userId) }
            .toSet()
}
