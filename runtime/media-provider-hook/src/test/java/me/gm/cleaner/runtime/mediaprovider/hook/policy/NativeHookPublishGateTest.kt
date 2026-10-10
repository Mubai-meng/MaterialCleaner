package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NativeHookPublishGate] 的行为与**跨模块安全不变量**验证。
 *
 * 关键背景：`native_hook_status.json` 的存活判据是 payload 里的 `createdAt`，
 * cleaner-server 侧 `NativeHookLayerReporter` 只接受 age ≤ 15s 的快照
 * （见 [READER_MAX_AGE_MILLIS]）。因此「保活」不是可选兜底，而是刚需 ——
 * 本测试的核心断言就是**保活周期必须始终落在读者的判活窗口之内**。
 */
class NativeHookPublishGateTest {

    /** 镜像 cleaner-server 的 `NativeHookLayerReporter.NATIVE_HOOK_STATUS_MAX_AGE_MS`。 */
    private val readerMaxAgeMillis = 15_000L

    /** 镜像 `HookPolicyRefreshScheduler.POLL_INTERVAL_MS`。 */
    private val pollIntervalMillis = 5_000L

    private fun gate() = NativeHookPublishGate(
        coalesceWindowMillis = NativeHookStatus.PUBLISH_COALESCE_WINDOW_MILLIS,
        heartbeatIntervalMillis = NativeHookStatus.HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS,
    )

    /** 逐拍推进时钟；`mark*` 与轮询各自走对应入口，成功落盘才推进状态。 */
    private fun simulate(
        gate: NativeHookPublishGate,
        fromMs: Long,
        toMs: Long,
        stepMs: Long = pollIntervalMillis,
        semanticChangeAtMs: Set<Long> = emptySet(),
    ): List<Long> {
        val published = mutableListOf<Long>()
        var now = fromMs
        while (now <= toMs) {
            val allowed = if (now in semanticChangeAtMs) {
                gate.onSemanticChange(now)
            } else {
                gate.onPoll(now)
            }
            if (allowed) {
                published += now
                gate.onPublishResult(succeeded = true)
            }
            now += stepMs
        }
        return published
    }

    // ── 1. 放行判据 ────────────────────────────────────────────────

    @Test
    fun `首次语义变更立即放行`() {
        assertTrue(gate().onSemanticChange(0L))
    }

    @Test
    fun `前沿合并窗口内的连续变更只放行一次`() {
        val gate = gate()
        assertTrue(gate.onSemanticChange(0L))
        gate.onPublishResult(succeeded = true)

        assertTrue(!gate.onSemanticChange(100L))
        assertTrue(!gate.onSemanticChange(500L))
        assertTrue(!gate.onSemanticChange(999L))
        // 越过窗口后，语义变更放行。
        assertTrue(gate.onSemanticChange(1_000L))
    }

    @Test
    fun `78毫秒内连打8次mark只落盘一次且末态不丢`() {
        val gate = gate()
        val published = mutableListOf<Long>()
        for (i in 0 until 8) {
            val now = i * 10L // 0,10,…,70 —— 复现实测的 mark* 风暴
            if (gate.onSemanticChange(now)) {
                published += now
                gate.onPublishResult(succeeded = true)
            }
        }

        assertEquals(listOf(0L), published)
        // 末态不丢：被合并掉的变更由下一轮轮询补落盘。
        assertTrue(gate.onPoll(pollIntervalMillis))
    }

    @Test
    fun `成功落盘后窗口内的轮询不重发`() {
        val gate = gate()
        assertTrue(gate.onSemanticChange(0L))
        gate.onPublishResult(succeeded = true)

        // 恰好越过合并窗口，但语义未变且未到保活下限 ⇒ 不放行。
        assertTrue(!gate.onPoll(1_000L))
        assertTrue(!gate.onPoll(pollIntervalMillis))
    }

    // ── 2. 保活下限 ────────────────────────────────────────────────

    @Test
    fun `语义未变时按保活下限落盘而非每轮必写`() {
        val gate = gate()
        assertTrue(gate.onSemanticChange(0L))
        gate.onPublishResult(succeeded = true)

        val heartbeat = NativeHookStatus.HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS
        assertTrue(!gate.onPoll(heartbeat - 1))
        assertTrue(gate.onPoll(heartbeat))
    }

    @Test
    fun `落盘失败保留待落盘标记并在下一轮重试`() {
        val gate = gate()
        assertTrue(gate.onSemanticChange(0L))
        // 模拟写盘失败：不调用 onPublishResult(succeeded = true)。
        assertTrue(!gate.onSemanticChange(100L))
        assertTrue(!gate.onSemanticChange(200L))

        // 虽未到保活下限，但「有未落盘的语义变更」⇒ 立即补落盘。
        assertTrue(gate.onPoll(1_000L))
    }

    @Test
    fun `对照 保活下限等于轮询周期时退化为每轮必写`() {
        // 这正是本次修改前的行为：窗口(1s) < 轮询周期(5s) ⇒ 轮询必然放行。
        val oldStyleGate = NativeHookPublishGate(
            coalesceWindowMillis = NativeHookStatus.PUBLISH_COALESCE_WINDOW_MILLIS,
            heartbeatIntervalMillis = pollIntervalMillis,
        )
        val published = simulate(
            oldStyleGate,
            fromMs = 0L,
            toMs = 60_000L,
            semanticChangeAtMs = setOf(0L),
        )
        // 0,5000,…,60000 ⇒ 13 次无条件写。
        assertEquals(13, published.size)
        assertEquals(
            "旧行为下每轮都写",
            List(13) { it * pollIntervalMillis },
            published,
        )
    }

    // ── 3. 跨模块安全不变量（本轮修改的核心约束） ─────────────────

    @Test
    fun `保活下限加轮询周期必须严格小于读者判活窗口`() {
        val worstCaseAge =
            NativeHookStatus.HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS + pollIntervalMillis

        assertTrue(
            "保活下限(${NativeHookStatus.HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS}) + " +
                    "轮询周期($pollIntervalMillis) = $worstCaseAge " +
                    "必须严格小于读者判活窗口($readerMaxAgeMillis)，否则状态卡会偶发显示不可用",
            worstCaseAge < readerMaxAgeMillis,
        )
    }

    @Test
    fun `稳态下的实际落盘周期与最坏陈旧度都落在判活窗口内`() {
        val published = simulate(
            gate(),
            fromMs = 0L,
            toMs = 300_000L,
            semanticChangeAtMs = setOf(0L),
        )

        // 语义只在 t=0 变更一次；此后全靠保活往返。
        assertEquals(0L, published.first())
        val gaps = published.zipWithNext { a, b -> b - a }
        assertTrue("应至少产生若干个保活周期", gaps.size >= 10)
        assertEquals("稳态落盘周期应为 10s（8s 下限 + 5s 轮询粒度）", setOf(10_000L), gaps.toSet())
        assertTrue("最坏落盘间隔必须 < 判活窗口", gaps.max() < readerMaxAgeMillis)

        // 相对修改前的「每轮必写」，落盘次数减半。
        assertEquals(31, published.size)
    }
}
