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
    ) = MediaProviderRecoveryPolicy.State(
        hookConnected, episodeStartMs, thresholdReached,
        lastRound, destructiveRounds, mediaScan,
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
}
