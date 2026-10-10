package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [FilesystemProbe] 的**决定性**语义。
 *
 * 背景：诊断归档此前的挂载采集全部带 grep 过滤，`/data/local/tmp`（DataBus 根）
 * 从未出现在任何一条输出里，导致「DataBus 根所在文件系统类型」只能靠 adb 确认。
 * 新探针把结论写进归档与 logcat，而它的正确性完全取决于两条纯逻辑：
 *
 * 1. **最长前缀匹配 + 路径分量边界**：若边界写成 `startsWith(mp)` 而非
 *    `startsWith("$mp/")`，`/database/x` 会被判成落在 `/data` 之内；
 * 2. **措辞与判据一致**：若 `ownMount` 判据与输出文案不一致，日志会给出
 *    与数据**相反**的可读结论 —— 这比没有结论更危险。
 *
 * 另外钉住一个安全默认：fsid 取不到时必须回 `unknown` 而**不能**回 `false`，
 * 因为 `false` 会被读作「不是同一文件系统」，正好把结论推向 tmpfs 假说。
 *
 * 本测试只覆盖纯函数，不触碰 `android.system.Os` / `android.os.StatFs`
 * （单元测试环境里它们是 not-mocked 桩）。
 */
class FilesystemProbeTest {

    /** 一加15 / ColorOS17 上真实存在的挂载形态（取自诊断包 /proc/mounts 片段）。 */
    private val realMountLines = listOf(
        "rootfs / rootfs rw 0 0",
        "tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0",
        "/dev/block/dm-41 /data f2fs rw,seclabel,nosuid,nodev,noatime,inline_xattr 0 0",
        "tmpfs /storage tmpfs rw,seclabel,nosuid,nodev,noexec,relatime,mode=755 0 0",
        "/dev/block/dm-42 /data/persist_log ext4 rw,seclabel,nosuid,nodev,noatime 0 0",
    )

    private fun mountsOf(vararg extra: String): List<FilesystemProbe.MountEntry> =
        FilesystemProbe.parseMountTable(realMountLines + extra.toList(), "mounts")

    @Test
    fun resolveMount_treatsPlainDirectoryAsInsideData() {
        val entry = FilesystemProbe.resolveMount("/data/local/tmp/cleaner", mountsOf())
        assertEquals("/data", entry?.mountPoint)
        assertEquals("f2fs", entry?.fsType)
    }

    @Test
    fun resolveMount_reportsSeparateMountWhenTargetIsItsOwnMountPoint() {
        val mounts = mountsOf(
            "tmpfs /data/local/tmp tmpfs rw,seclabel,nosuid,nodev,noexec,relatime,mode=755 0 0",
        )
        val entry = FilesystemProbe.resolveMount("/data/local/tmp/cleaner", mounts)
        assertEquals("/data/local/tmp", entry?.mountPoint)
        assertEquals("tmpfs", entry?.fsType)
    }

    @Test
    fun resolveMount_longestPrefixWins() {
        val mounts = mountsOf(
            "tmpfs /data/local/tmp tmpfs rw 0 0",
            "/dev/block/dm-41 /data/local/tmp/cleaner f2fs rw 0 0",
        )
        assertEquals("/data/local/tmp/cleaner",
            FilesystemProbe.resolveMount("/data/local/tmp/cleaner/bus", mounts)?.mountPoint)
        assertEquals("/data/local/tmp",
            FilesystemProbe.resolveMount("/data/local/tmp/other", mounts)?.mountPoint)
        assertEquals("/data",
            FilesystemProbe.resolveMount("/data/local", mounts)?.mountPoint)
    }

    @Test
    fun resolveMount_respectsPathComponentBoundary() {
        // `/database` 与 `/data` 共享字符串前缀，但**不**共享路径分量。
        val mounts = mountsOf()
        assertEquals("/", FilesystemProbe.resolveMount("/database/x", mounts)?.mountPoint)
        assertEquals("/data", FilesystemProbe.resolveMount("/data/x", mounts)?.mountPoint)
        assertEquals("/data", FilesystemProbe.resolveMount("/data", mounts)?.mountPoint)
    }

    @Test
    fun resolveMount_fallsBackToRootWhenNothingElseMatches() {
        val entry = FilesystemProbe.resolveMount("/nonexistent/path", mountsOf())
        assertEquals("/", entry?.mountPoint)
        assertEquals("rootfs", entry?.fsType)
    }

    @Test
    fun resolveMount_returnsNullForEmptyTable() {
        assertNull(FilesystemProbe.resolveMount("/data/local/tmp/cleaner", emptyList()))
    }

    @Test
    fun resolveMount_normalizesTrailingSlash() {
        val mounts = mountsOf()
        assertEquals("/data", FilesystemProbe.resolveMount("/data/local/tmp/", mounts)?.mountPoint)
        assertEquals("/data", FilesystemProbe.resolveMount("/data/", mounts)?.mountPoint)
        assertEquals("/", FilesystemProbe.resolveMount("/", mounts)?.mountPoint)
    }

    @Test
    fun parseMountTable_decodesOctalEscapedSpaces() {
        val entries = FilesystemProbe.parseMountTable(
            listOf("/dev/fuse /mnt/my\\040dir fuse rw,nosuid,nodev 0 0", "garbage"),
            "mounts",
        )
        assertEquals(1, entries.size)
        assertEquals("/mnt/my dir", entries[0].mountPoint)
        assertEquals("fuse", entries[0].fsType)
        assertEquals("/mnt/my dir",
            FilesystemProbe.resolveMount("/mnt/my dir/file", entries)?.mountPoint)
    }

    @Test
    fun parseMountTable_skipsMalformedLines() {
        val entries = FilesystemProbe.parseMountTable(
            listOf("", "   ", "only-two fields", "/dev/x /mnt/y f2fs rw 0 0"),
            "mounts",
        )
        assertEquals(1, entries.size)
        assertEquals("/mnt/y", entries[0].mountPoint)
    }

    @Test
    fun parseMountInfo_extractsMountPointTypeAndSource() {
        val withOptionalFields = FilesystemProbe.parseMountInfo(
            listOf("36 35 98:0 /mnt1 /mnt2 rw,noatime master:1 - ext3 /dev/root rw,errors=continue"),
        )
        assertEquals(1, withOptionalFields.size)
        assertEquals("/mnt2", withOptionalFields[0].mountPoint)
        assertEquals("ext3", withOptionalFields[0].fsType)
        assertEquals("/dev/root", withOptionalFields[0].source)

        val withoutOptionalFields = FilesystemProbe.parseMountInfo(
            listOf("36 35 98:0 /mnt1 /mnt2 rw,noatime - f2fs /dev/block/dm-41 rw"),
        )
        assertEquals("/mnt2", withoutOptionalFields[0].mountPoint)
        assertEquals("f2fs", withoutOptionalFields[0].fsType)
        assertEquals("/dev/block/dm-41", withoutOptionalFields[0].source)
    }

    @Test
    fun relationOf_wordingMatchesOwnMountDecision() {
        val mounts = mountsOf()

        // `/data/local/tmp/cleaner` 不是挂载点本身 ⇒ 文案必须是 "is INSIDE mount /data"。
        val inside = FilesystemProbe.relationOf("/data/local/tmp/cleaner", mounts)
        assertTrue(inside, inside.startsWith("is INSIDE mount /data "))
        assertTrue(inside, inside.contains("fstype=f2fs"))
        assertTrue(inside, !inside.startsWith("IS a separate mount"))

        // `/data` 自身就是挂载点 ⇒ 文案必须是 "IS a separate mount"。
        val own = FilesystemProbe.relationOf("/data", mounts)
        assertTrue(own, own.startsWith("IS a separate mount "))
        assertTrue(own, own.contains("fstype=f2fs"))

        // 空表 ⇒ 不得编造结论。
        assertTrue(
            FilesystemProbe.relationOf("/data/local/tmp", emptyList()).contains("<unresolved>"),
        )
    }

    @Test
    fun fsidComparison_returnsUnknownRatherThanFalseWhenLookupFails() {
        // 单元测试环境里 Os.statvfs 是 not-mocked 桩 ⇒ 必然取不到 fsid。
        // 此时**不能**回 "false"：那会被读成「不是同一文件系统」，把结论推向 tmpfs 假说。
        val result = FilesystemProbe.fsidComparison("/data", "/data/local/tmp")
        assertEquals("unknown", result)
    }
}
