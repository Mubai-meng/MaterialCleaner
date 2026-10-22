package me.gm.cleaner.runtime.server.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverStallPolicyTest {

    @Test
    fun `未启动不判`() {
        assertFalse(ObserverStallPolicy.isStalled(1_000_000L, 0L, 0L))
    }

    @Test
    fun `宽限期内不判`() {
        assertFalse(ObserverStallPolicy.isStalled(100_000L, 0L + 1L, 0L))
        assertFalse(ObserverStallPolicy.isStalled(100_000L, 1L, 90_000L))
    }

    @Test
    fun `宽限期后从未读到行判假活`() {
        assertTrue(ObserverStallPolicy.isStalled(500_000L, 1L, 0L))
    }

    @Test
    fun `心跳新鲜不判`() {
        assertTrue(
            ObserverStallPolicy.isStalled(
                nowMs = 500_000L,
                startAtMs = 1L,
                lastReadAtMs = 499_000L,
                graceMs = 0L,
                stallMs = 5_000L,
            ).not(),
        )
    }

    @Test
    fun `心跳停滞超阈判假活`() {
        assertTrue(
            ObserverStallPolicy.isStalled(
                nowMs = 500_000L,
                startAtMs = 1L,
                lastReadAtMs = 100_000L,
                graceMs = 0L,
                stallMs = 5_000L,
            ),
        )
    }

    @Test
    fun `logd不响应时不判死`() {
        assertFalse(
            ObserverStallPolicy.isStalled(
                nowMs = 500_000L,
                startAtMs = 1L,
                lastReadAtMs = 100_000L,
                graceMs = 0L,
                stallMs = 5_000L,
                logdResponsive = false,
            ),
        )
    }

    // ── 旁路证据（第32批）：停滞判定必须区分「我们漏读」与「本来就没行」 ──

    @Test
    fun `epoch 行解析出毫秒`() {
        assertEquals(
            1_792_651_708_588L,
            ObserverStallPolicy.parseEpochMillis(
                "1792651708.588  9471  9471 I ActivityManager: Start proc 1:u0a363"
            ),
        )
    }

    @Test
    fun `分隔行与空行解析为不可用`() {
        assertEquals(-1L, ObserverStallPolicy.parseEpochMillis("--------- beginning of main"))
        assertEquals(-1L, ObserverStallPolicy.parseEpochMillis(""))
        assertEquals(-1L, ObserverStallPolicy.parseEpochMillis(null))
        assertEquals(-1L, ObserverStallPolicy.parseEpochMillis("0.000 x"))
    }

    @Test
    fun `过滤流没有更新的行时判空闲而非停滞`() {
        // lastRead 换算成墙钟 = 1_200_000 - (900_000 - 300_000) = 600_000
        // 最新 AM 行 550_000 早于它 ⇒ 空闲，不是我们漏读
        assertFalse(
            ObserverStallPolicy.isProbeLineFresh(
                lineEpochMs = 550_000L,
                nowWallMs = 1_200_000L,
                nowElapsedMs = 900_000L,
                lastReadAtMs = 300_000L,
            ),
        )
    }

    @Test
    fun `过滤流存在更新的行时判真停滞`() {
        // lastRead 换算成墙钟 = 1_400_000 - (400_000 - 300_000) = 1_300_000
        // 探针行 1_350_000 晚于它 ⇒ 我们漏读了新行
        assertTrue(
            ObserverStallPolicy.isProbeLineFresh(
                lineEpochMs = 1_350_000L,
                nowWallMs = 1_400_000L,
                nowElapsedMs = 400_000L,
                lastReadAtMs = 300_000L,
            ),
        )
    }

    @Test
    fun `探针没拿到行时不判死`() {
        assertFalse(
            ObserverStallPolicy.isProbeLineFresh(
                lineEpochMs = -1L,
                nowWallMs = 1_400_000L,
                nowElapsedMs = 400_000L,
                lastReadAtMs = 300_000L,
            ),
        )
    }

    @Test
    fun `松弛窗口内的行不算漏读`() {
        // lastRead 墙钟 = 1_300_000；行恰好晚 500ms，落在 1s 松弛内
        assertFalse(
            ObserverStallPolicy.isProbeLineFresh(
                lineEpochMs = 1_300_500L,
                nowWallMs = 1_400_000L,
                nowElapsedMs = 400_000L,
                lastReadAtMs = 300_000L,
            ),
        )
    }
}
