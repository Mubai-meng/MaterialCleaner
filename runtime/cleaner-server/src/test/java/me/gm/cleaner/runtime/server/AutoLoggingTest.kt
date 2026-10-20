package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [AutoLogging] 的两条纯逻辑。
 *
 * 1. [AutoLogging.pruneCount] 的双阈值必须**同时**生效。旧实现在
 *    `files.length <= MAX_LOG_FILES` 时直接 return，字节阈值永远不会被检查 ——
 *    「1 个 20 GB 的文件」也删不掉。这正是 `/data/local/tmp/cleaner_logs`
 *    能涨到几个 G 的直接原因之一（另一条是活动文件在会话内完全不受限）。
 * 2. [AutoLogging.isLogFileName] 必须同时认环形分片（`cleaner.log.1`）和
 *    旧版时间戳文件，否则诊断归档会漏掉轮转出来的历史。
 */
class AutoLoggingTest {

    private val maxFiles = 8
    private val maxBytes = 64L * 1024 * 1024

    @Test
    fun `single huge file is pruned even when file count is under limit`() {
        // 旧实现：length = 1 <= 8 ⇒ 直接 return，0 删除。
        val oneFile = longArrayOf(20L * 1024 * 1024 * 1024)
        assertEquals(1, AutoLogging.pruneCount(oneFile, maxFiles, maxBytes))
    }

    @Test
    fun `healthy ring is left untouched`() {
        // 8 × 4 MB = 32 MB，恰好等于环形设计上限，低于 64 MB 兜底。
        val ring = LongArray(8) { 4L * 1024 * 1024 }
        assertEquals(0, AutoLogging.pruneCount(ring, maxFiles, maxBytes))
    }

    @Test
    fun `count threshold deletes oldest first`() {
        // 11 个 1 MB 文件：总字节远低于上限，只超文件数 ⇒ 删最旧 3 个。
        val sizes = LongArray(11) { 1L * 1024 * 1024 }
        assertEquals(3, AutoLogging.pruneCount(sizes, maxFiles, maxBytes))
    }

    @Test
    fun `byte threshold deletes oldest until under cap`() {
        // 9 个 10 MB = 90 MB。删到 8 个（80 MB）仍超 64 MB，
        // 继续删到 6 个（60 MB）才满足。
        val sizes = LongArray(9) { 10L * 1024 * 1024 }
        assertEquals(3, AutoLogging.pruneCount(sizes, maxFiles, maxBytes))
    }

    @Test
    fun `empty list needs no deletion`() {
        assertEquals(0, AutoLogging.pruneCount(longArrayOf(), maxFiles, maxBytes))
    }

    @Test
    fun `logcat command pins the rotation contract`() {
        // `-f` 缺失则 `-r/-n` 不生效；`-r/-n` 缺失则退回无界增长。
        val path = "/data/local/tmp/cleaner_logs/cleaner.log"
        val argv = AutoLogging.buildLogcatCommand(path)
        assertEquals("/system/bin/logcat", argv[0])
        assertTrue(argv.contains("main,system,crash"))
        val f = argv.indexOf("-f")
        val r = argv.indexOf("-r")
        val n = argv.indexOf("-n")
        assertTrue(f > 0 && r > 0 && n > 0)
        assertEquals(path, argv[f + 1])
        assertEquals("4096", argv[r + 1])
        assertEquals("8", argv[n + 1])
    }

    @Test
    fun `ring and legacy names are recognised`() {
        assertTrue(AutoLogging.isLogFileName("cleaner.log"))
        assertTrue(AutoLogging.isLogFileName("cleaner.log.1"))
        assertTrue(AutoLogging.isLogFileName("cleaner.log.7"))
        assertTrue(AutoLogging.isLogFileName("1720000000000-123456.log"))
        assertFalse(AutoLogging.isLogFileName("cleaner.log.bak"))
        assertFalse(AutoLogging.isLogFileName("notes.txt"))
        assertFalse(AutoLogging.isLogFileName(null))
    }

    @Test
    fun `boot log command pins the 60s window contract`() {
        val path = "/data/local/tmp/cleaner_logs/cleaner-boot-1792410258947.log"
        val argv = AutoLogging.buildBootLogcatCommand(path)
        assertEquals("/system/bin/logcat", argv[0])
        assertTrue(argv.contains("main,system,crash"))
        val f = argv.indexOf("-f")
        val r = argv.indexOf("-r")
        val n = argv.indexOf("-n")
        assertTrue(f > 0 && r > 0 && n > 0)
        assertEquals(path, argv[f + 1])
        assertEquals("8192", argv[r + 1])
        assertEquals("2", argv[n + 1])
    }

    @Test
    fun `boot log names are recognised for archive`() {
        assertTrue(AutoLogging.isBootLogFileName("cleaner-boot-1792410258947.log"))
        assertTrue(AutoLogging.isBootLogFileName("cleaner-boot-1792410258947.log.1"))
        assertTrue(AutoLogging.isLogFileName("cleaner-boot-1792410258947.log.1"))
        assertFalse(AutoLogging.isBootLogFileName("cleaner.log"))
        assertFalse(AutoLogging.isBootLogFileName("cleaner.log.1"))
    }
}
