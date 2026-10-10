package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProviderRecoveryPolicyTest {
    private fun state(
        hookConnected: Boolean = false,
        episodeStartMs: Long = 1_000L,
        thresholdReached: Boolean = true,
        lastRound: MediaProviderRecoveryPolicy.RoundRecord? = null,
        destructiveRounds: Int = 0,
        mediaScan: MediaProcessScan =
            MediaProcessScan.Success(mapOf(100 to 50L)),
        ledgerCorrupted: Boolean = false,
    ) = MediaProviderRecoveryPolicy.State(
        hookConnected, episodeStartMs, thresholdReached,
        lastRound, destructiveRounds, mediaScan, ledgerCorrupted,
    )

    private fun round(pids: Set<Int>, starts: Map<Int, Long>, timeMs: Long = 1_000L) =
        MediaProviderRecoveryPolicy.RoundRecord(timeMs, pids, starts)

    @Test
    fun `已连接无需动作`() {
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.CONNECTED,
            MediaProviderRecoveryPolicy.decide(100_000L, state(hookConnected = true)),
        )
    }

    @Test
    fun `Stage1内只探不杀`() {
        val now = 1_000L + MediaProviderRecoveryPolicy.STAGE1_WINDOW_MS - 1L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(now, state()),
        )
    }

    @Test
    fun `未达阈值只探不杀`() {
        val now = 1_000L + MediaProviderRecoveryPolicy.STAGE1_WINDOW_MS + 1L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(now, state(thresholdReached = false)),
        )
    }

    @Test
    fun `窗口外且实例新鲜允许强杀`() {
        val now = 200_000L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.MAY_FORCE_STOP,
            MediaProviderRecoveryPolicy.decide(now, state()),
        )
    }

    @Test
    fun `轮后窗口内禁杀`() {
        val now = 200_000L
        val round = MediaProviderRecoveryPolicy.RoundRecord(
            timeMs = now - 1_000L, targetPids = setOf(99), targetStarts = mapOf(99 to 10L),
        )
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(
                now,
                state(
                    lastRound = round,
                    mediaScan = MediaProcessScan.Success(mapOf(100 to 50L)),
                ),
            ),
        )
    }

    @Test
    fun `同一实例禁二次强杀`() {
        val now = 500_000L
        val round = MediaProviderRecoveryPolicy.RoundRecord(
            timeMs = now - 100_000L, targetPids = setOf(100), targetStarts = mapOf(100 to 50L),
        )
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(
                now, state(lastRound = round, destructiveRounds = 1),
            ),
        )
    }

    @Test
    fun `PID复用视为新实例`() {
        val now = 500_000L
        val round = MediaProviderRecoveryPolicy.RoundRecord(
            timeMs = now - 100_000L, targetPids = setOf(100), targetStarts = mapOf(100 to 50L),
        )
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.MAY_FORCE_STOP,
            MediaProviderRecoveryPolicy.decide(
                now,
                state(
                    lastRound = round, destructiveRounds = 1,
                    mediaScan = MediaProcessScan.Success(mapOf(100 to 99L)),
                ),
            ),
        )
    }

    @Test
    fun `无法观测实例禁盲杀`() {
        val now = 500_000L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(
                now, state(mediaScan = MediaProcessScan.Unavailable),
            ),
        )
    }

    @Test
    fun `确认无活进程走只探`() {
        // Success(empty) 是“确认没有”，不是“无法确认”：probe-only 自带 wake，
        // 正是死进程的正确恢复路径，不应也无需进入破坏性准入。
        val now = 500_000L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(
                now, state(mediaScan = MediaProcessScan.Success(emptyMap())),
            ),
        )
    }

    @Test
    fun `扫描失败与确认无进程语义不同`() {
        // 两者都走 PROBE_ONLY，但必须是不同输入类型抵达同一结论，
        // 而不是在中途被退化成同一个空 Map。
        val now = 500_000L
        val unavailable = MediaProviderRecoveryPolicy.decide(
            now, state(mediaScan = MediaProcessScan.Unavailable),
        )
        val empty = MediaProviderRecoveryPolicy.decide(
            now, state(mediaScan = MediaProcessScan.Success(emptyMap())),
        )
        assertEquals(MediaProviderRecoveryPolicy.Decision.PROBE_ONLY, unavailable)
        assertEquals(MediaProviderRecoveryPolicy.Decision.PROBE_ONLY, empty)
    }

    @Test
    fun `三轮熔断转WAKE_ONLY且桥抖动不重置`() {
        // episode 刚开（模拟桥重连后新计数），但总账已满 → 仍熔断。
        val now = 2_000L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.WAKE_ONLY,
            MediaProviderRecoveryPolicy.decide(
                now, state(episodeStartMs = now - 1L, destructiveRounds = 3),
            ),
        )
    }

    @Test
    fun `总账腐败强制只探不杀`() {
        // 轮次未知时不得破坏，即使熔断未满、实例新鲜。
        val now = 500_000L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(now, state(ledgerCorrupted = true)),
        )
    }

    @Test
    fun `总账腐败优先于熔断态`() {
        // 腐败走探测而非低频唤醒，以便尽快发现恢复；两者都不允许破坏。
        val now = 500_000L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(
                now, state(destructiveRounds = 3, ledgerCorrupted = true),
            ),
        )
    }

    // ── mayTargetInstances：执行前同实例复检 ──

    @Test
    fun `无上轮记录时允许`() {
        assertTrue(
            MediaProviderRecoveryPolicy.mayTargetInstances(null, mapOf(100 to 50L)),
        )
    }

    @Test
    fun `上轮目标为空时允许`() {
        val r = round(emptySet(), emptyMap())
        assertTrue(
            MediaProviderRecoveryPolicy.mayTargetInstances(r, mapOf(100 to 50L)),
        )
    }

    @Test
    fun `执行前仍为上轮实例则禁止`() {
        // 决策时扫描过期：wake/等待期间实例未变，执行前复检必须拦住。
        val r = round(setOf(100), mapOf(100 to 50L))
        assertFalse(
            MediaProviderRecoveryPolicy.mayTargetInstances(r, mapOf(100 to 50L)),
        )
    }

    @Test
    fun `执行前实例已更替则允许`() {
        val r = round(setOf(100), mapOf(100 to 50L))
        assertTrue(
            MediaProviderRecoveryPolicy.mayTargetInstances(r, mapOf(200 to 70L)),
        )
    }

    @Test
    fun `执行前确认无进程不构成同实例冲突`() {
        val r = round(setOf(100), mapOf(100 to 50L))
        assertTrue(
            MediaProviderRecoveryPolicy.mayTargetInstances(r, emptyMap()),
        )
    }

    @Test
    fun `总账JSON组装往返一致`() {
        val r = round(setOf(100), mapOf(100 to 50L), timeMs = 777L)
        val json = MediaProviderRecoveryPolicy.buildLedgerJson(2, r)
        assertTrue(json != null && json.contains("\"destructiveRounds\":2"))
        assertTrue(json!!.contains("777"))
    }

    @Test
    fun `总账JSON空轮次组装不崩`() {
        val json = MediaProviderRecoveryPolicy.buildLedgerJson(0, null)
        assertTrue(json != null && json.contains("\"destructiveRounds\":0"))
    }

    @Test
    fun `合法总账解析通过`() {
        val r = round(setOf(100), mapOf(100 to 50L), timeMs = 777L)
        val json = MediaProviderRecoveryPolicy.buildLedgerJson(2, r)!!
        val parsed = MediaProviderRecoveryPolicy.parseLedger(json)
        assertTrue(parsed is MediaProviderRecoveryPolicy.LedgerParsed.Valid)
        val valid = parsed as MediaProviderRecoveryPolicy.LedgerParsed.Valid
        assertEquals(2, valid.rounds)
    }

    @Test
    fun `空对象不是空账本而是腐败`() {
        // {} 意味着历史不可确认，静默归零会重新放行破坏，方向偏危险。
        assertTrue(
            MediaProviderRecoveryPolicy.parseLedger("{}") is
                MediaProviderRecoveryPolicy.LedgerParsed.Corrupted,
        )
    }

    @Test
    fun `类型错误与负轮次判腐败`() {
        assertTrue(
            MediaProviderRecoveryPolicy.parseLedger(
                "{\"destructiveRounds\":\"invalid\",\"lastRoundAt\":0}",
            ) is MediaProviderRecoveryPolicy.LedgerParsed.Corrupted,
        )
        assertTrue(
            MediaProviderRecoveryPolicy.parseLedger(
                "{\"destructiveRounds\":-1,\"lastRoundAt\":0}",
            ) is MediaProviderRecoveryPolicy.LedgerParsed.Corrupted,
        )
        assertTrue(
            MediaProviderRecoveryPolicy.parseLedger(
                "{\"destructiveRounds\":99,\"lastRoundAt\":0}",
            ) is MediaProviderRecoveryPolicy.LedgerParsed.Corrupted,
        )
    }

    @Test
    fun `语法错误判腐败`() {
        assertTrue(
            MediaProviderRecoveryPolicy.parseLedger("{not json") is
                MediaProviderRecoveryPolicy.LedgerParsed.Corrupted,
        )
    }

    @Test
    fun `pids与starts失配判腐败`() {
        assertTrue(
            MediaProviderRecoveryPolicy.parseLedger(
                "{\"destructiveRounds\":1,\"lastRoundAt\":7," +
                    "\"lastRoundPids\":[100]," +
                    "\"lastRoundStarts\":{\"200\":50}}",
            ) is MediaProviderRecoveryPolicy.LedgerParsed.Corrupted,
        )
    }

    @Test
    fun `操作目标仅含观测且已安装组合`() {
        val observed = listOf(
            ObservedTarget("pkg.a", 0, 100, 50L),
            ObservedTarget("pkg.a", 10, 200, 60L),
            ObservedTarget("pkg.b", 0, 300, 70L),
        )
        val ops = MediaProviderRecoveryPolicy.resolveOperationTargets(
            observed,
        ) { pkg, user -> !(pkg == "pkg.a" && user == 10) && pkg != "pkg.b" }
        assertEquals(setOf(OperationTarget("pkg.a", 0)), ops)
    }

    @Test
    fun `无观测无操作`() {
        val ops = MediaProviderRecoveryPolicy.resolveOperationTargets(
            emptyList(),
        ) { _, _ -> true }
        assertTrue(ops.isEmpty())
    }
}
