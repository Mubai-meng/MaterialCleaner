package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProviderRecoveryPolicyTest {
    private fun state(
        hookConnected: Boolean = false,
        episodeStartMs: Long = 1_000L,
        thresholdReached: Boolean = true,
        lastRound: MediaProviderRecoveryPolicy.RoundRecord? = null,
        destructiveRounds: Int = 0,
        currentMediaPids: Map<Int, Long> = mapOf(100 to 50L),
    ) = MediaProviderRecoveryPolicy.State(
        hookConnected, episodeStartMs, thresholdReached,
        lastRound, destructiveRounds, currentMediaPids,
    )

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
                now, state(lastRound = round, currentMediaPids = mapOf(100 to 50L)),
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
                now, state(
                    lastRound = round, destructiveRounds = 1,
                    currentMediaPids = mapOf(100 to 99L),
                ),
            ),
        )
    }

    @Test
    fun `无法观测实例禁盲杀`() {
        val now = 500_000L
        assertEquals(
            MediaProviderRecoveryPolicy.Decision.PROBE_ONLY,
            MediaProviderRecoveryPolicy.decide(now, state(currentMediaPids = emptyMap())),
        )
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
}
