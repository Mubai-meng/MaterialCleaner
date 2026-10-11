package me.gm.cleaner.runtime.server.vfs

import me.gm.cleaner.model.PackageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VfsProcessCensusBuilder 纯函数单测：分母闭合、异常优先截断。
 */
class VfsProcessCensusBuilderTest {

    private fun entry(pkg: String, pid: Int, flag: Int) =
        CensusEntry(pkg, pid, 10000 + pid, flag)

    @Test
    fun `分母闭合`() {
        val census = VfsProcessCensusBuilder.build(
            sampledAt = 1L,
            entries = listOf(
                entry("a", 1, PackageStatus.PID_FLAG_MOUNTED),
                entry("a", 2, PackageStatus.PID_FLAG_PARTIALLY_MOUNTED),
                entry("b", 3, PackageStatus.PID_FLAG_NOT_MOUNTED),
                entry("b", 4, PackageStatus.PID_FLAG_UNKNOWN),
                entry("c", 5, PackageStatus.PID_FLAG_DELETED),
                entry("c", 6, PackageStatus.PID_FLAG_UNMANAGED),
                entry("d", 7, PackageStatus.PID_FLAG_MOUNTED or PackageStatus.PID_FLAG_MOUNT_FAILED),
            ),
            unattributedPids = 10,
        )
        assertEquals(2 + 1 + 1 + 1 + 1, census.managedPids)
        assertEquals(10 + 1, census.unmanagedPids)
        assertEquals(census.managedPids + census.unmanagedPids, census.observedPids)
        assertEquals(2, census.mountedPids)
        assertEquals(1, census.partialPids)
        assertEquals(1, census.notMountedPids)
        assertEquals(1, census.unknownPids)
        assertEquals(1, census.driftPids)
        assertEquals(1, census.mountFailedEvidence)
        assertFalse(census.truncated)
    }

    @Test
    fun `异常优先截断并标记`() {
        val entries = listOf(
            entry("ok", 1, PackageStatus.PID_FLAG_MOUNTED),
            entry("bad", 2, PackageStatus.PID_FLAG_UNKNOWN),
            entry("mid", 3, PackageStatus.PID_FLAG_PARTIALLY_MOUNTED),
        )
        val census = VfsProcessCensusBuilder.build(
            sampledAt = 1L, entries = entries, unattributedPids = 0, maxDetailEntries = 2,
        )
        assertTrue(census.truncated)
        assertEquals(2, census.detailShown)
        assertEquals(3, census.detailTotal)
        // UNKNOWN 与 PARTIAL 优先保留，MOUNTED 被截掉。
        assertTrue(census.detail.contains("bad:2/"))
        assertTrue(census.detail.contains("mid:3/"))
        assertFalse(census.detail.contains("ok:1/"))
    }

    @Test
    fun `空普查闭合`() {
        val census = VfsProcessCensusBuilder.build(1L, emptyList(), 0)
        assertEquals(0, census.observedPids)
        assertEquals("", census.detail)
        assertFalse(census.truncated)
    }
}
