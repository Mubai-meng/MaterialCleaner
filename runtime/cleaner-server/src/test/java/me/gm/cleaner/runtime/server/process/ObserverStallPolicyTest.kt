package me.gm.cleaner.runtime.server.process

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
}
