package me.gm.cleaner.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionStalenessTest {

    @Test
    fun `四端一致为OK`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 310, 310, 310, true),
        )
    }

    @Test
    fun `App进程旧判STALE_APP`() {
        assertEquals(
            VersionStaleness.STALE_APP,
            evaluateVersionStaleness(309, 310, 310, 310, true),
        )
    }

    @Test
    fun `服务旧判STALE_SERVER`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 310, 310, 0, false),
        )
        assertEquals(
            VersionStaleness.STALE_SERVER,
            evaluateVersionStaleness(310, 310, 309, 310, true),
        )
    }

    @Test
    fun `Hook旧判STALE_HOOK`() {
        assertEquals(
            VersionStaleness.STALE_HOOK,
            evaluateVersionStaleness(310, 310, 310, 309, true),
        )
    }

    @Test
    fun `无Hook环境不判Hook`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 310, 310, 0, false),
        )
    }

    @Test
    fun `未知版本永不判stale`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 0, 309, 308, true),
        )
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(0, 310, 0, 0, true),
        )
    }

    @Test
    fun `多端过期按App优先`() {
        assertEquals(
            VersionStaleness.STALE_APP,
            evaluateVersionStaleness(309, 310, 309, 309, true),
        )
    }
}
