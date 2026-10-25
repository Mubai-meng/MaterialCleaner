package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [AutoLogging] 的三条纯逻辑。
 *
 * 1. [AutoLogging.pruneCount] 的双阈值必须**同时**生效。旧实现在
 *    `files.length <= MAX_LOG_FILES` 时直接 return，字节阈值永远不会被检查 ——
 *    「1 个 20 GB 的文件」也删不掉。这正是 `/data/local/tmp/cleaner_logs`
 *    能涨到几个 G 的直接原因之一（另一条是活动文件在会话内完全不受限）。
 * 2. [AutoLogging.isLogFileName] 必须认全部通道的轮转分片
 *    （`cleaner.log.1`、`cleaner-crash.log.1`、`cleaner-boot-*.log.1`）和旧版
 *    时间戳文件，否则诊断归档会漏掉轮转出来的历史。
 * 3. 三条采集命令的轮转契约：`-f` 缺失则 `-r/-n` 不生效；`-r/-n` 缺失则退回
 *    无界增长；主通道缺少 `*:S` 则 tag 过滤失效（回到 ~875 MB/天）。
 */
class AutoLoggingTest {

    private val maxFiles = 12
    private val maxBytes = 64L * 1024 * 1024

    @Test
    fun `single huge file is pruned even when file count is under limit`() {
        // 旧实现：length = 1 <= 8 ⇒ 直接 return，0 删除。
        val oneFile = longArrayOf(20L * 1024 * 1024 * 1024)
        assertEquals(1, AutoLogging.pruneCount(oneFile, maxFiles, maxBytes))
    }

    @Test
    fun `healthy ring is left untouched`() {
        // 12 × 4 MB = 48 MB，低于 64 MB 兜底。
        val ring = LongArray(maxFiles) { 4L * 1024 * 1024 }
        assertEquals(0, AutoLogging.pruneCount(ring, maxFiles, maxBytes))
    }

    @Test
    fun `count threshold deletes oldest first`() {
        // 15 个 1 MB 文件：总字节远低于上限，只超文件数 ⇒ 删最旧 3 个。
        val sizes = LongArray(15) { 1L * 1024 * 1024 }
        assertEquals(3, AutoLogging.pruneCount(sizes, maxFiles, maxBytes))
    }

    @Test
    fun `byte threshold deletes oldest until under cap`() {
        // 13 个 10 MB = 130 MB。删到 12 个（120 MB）仍超 64 MB，
        // 继续删到 6 个（60 MB）才满足。
        val sizes = LongArray(13) { 10L * 1024 * 1024 }
        assertEquals(7, AutoLogging.pruneCount(sizes, maxFiles, maxBytes))
    }

    @Test
    fun `empty list needs no deletion`() {
        assertEquals(0, AutoLogging.pruneCount(longArrayOf(), maxFiles, maxBytes))
    }

    @Test
    fun `main channel pins rotation contract and tag filter`() {
        // `-f` 缺失则 `-r/-n` 不生效；`-r/-n` 缺失则退回无界增长；
        // 末尾缺少 `*:S` 则 tag 过滤不生效（实测 875 MB/天）。
        val path = "/data/local/tmp/cleaner_logs/cleaner.log"
        val argv = AutoLogging.buildLogcatCommand(path)
        assertEquals("/system/bin/logcat", argv[0])
        assertTrue("主通道只采 main+system（crash 已独立成通道）", argv.contains("main,system"))
        assertFalse("主通道不得再采 crash buffer", argv.contains("main,system,crash"))
        val f = argv.indexOf("-f")
        val r = argv.indexOf("-r")
        val n = argv.indexOf("-n")
        assertTrue(f > 0 && r > 0 && n > 0)
        assertEquals(path, argv[f + 1])
        assertEquals("4096", argv[r + 1])
        assertEquals("8", argv[n + 1])
        assertEquals("过滤串必须以 *:S 结尾", "*:S", argv.last())
        assertTrue("主通道必须带本项目 tag 过滤", argv.contains("MC_REDIRECT:*"))
    }

    @Test
    fun `main channel placeholder is substituted by real path`() {
        // startRingChannel 先建文件再把真实路径填进模板，模板本身必须可用：
        // `-f` 后面必须就是占位符，否则路径替换会落空、logcat 写到一个字面量
        // 名为 "<log-path>" 的文件里（或直接启动失败）。
        val template = AutoLogging.buildLogcatCommand(null)
        val f = template.indexOf("-f")
        assertTrue(f > 0)
        assertEquals(AutoLogging.PATH_PLACEHOLDER, template[f + 1])
    }

    @Test
    fun `crash channel keeps crash buffer unfiltered`() {
        val path = "/data/local/tmp/cleaner_logs/cleaner-crash.log"
        val argv = AutoLogging.buildCrashLogcatCommand(path)
        assertEquals("/system/bin/logcat", argv[0])
        assertTrue("崩溃通道必须采 crash buffer", argv.contains("crash"))
        assertFalse("崩溃的 tag 无法穷举，施加过滤会直接丢掉崩溃证据", argv.contains("*:S"))
        val f = argv.indexOf("-f")
        val r = argv.indexOf("-r")
        val n = argv.indexOf("-n")
        assertTrue(f > 0 && r > 0 && n > 0)
        assertEquals(path, argv[f + 1])
        assertEquals("2048", argv[r + 1])
        assertEquals("2", argv[n + 1])
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
    fun `crash channel shards are recognised`() {
        // 曾经只有 cleaner.log 一条通道，崩溃通道引入后若漏掉这里的判定，
        // 归档会静默丢掉 cleaner-crash.log.1 这类轮转分片。
        assertTrue(AutoLogging.isLogFileName("cleaner-crash.log"))
        assertTrue(AutoLogging.isLogFileName("cleaner-crash.log.1"))
        assertTrue(AutoLogging.isCrashLogFileName("cleaner-crash.log.1"))
        assertFalse(AutoLogging.isCrashLogFileName("cleaner.log.1"))
        assertFalse(AutoLogging.isLogFileName("cleaner-crash.log.bak"))
    }

    @Test
    fun `boot log command pins the 60s window contract`() {
        val path = "/data/local/tmp/cleaner_logs/cleaner-boot-1792410258947.log"
        val argv = AutoLogging.buildBootLogcatCommand(path)
        assertEquals("/system/bin/logcat", argv[0])
        assertTrue("启动段要全量兜底，必须同时带 main/system/crash", argv.contains("main,system,crash"))
        assertFalse("启动段不做 tag 过滤（白名单漏登记时的兜底现场）", argv.contains("*:S"))
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

    /**
     * 哨兵行是「通道到底有没有在工作」的唯一判据（第 48 批 §4.1）。
     *
     * 缺陷现场：`cleaner-crash.log` 连续多轮会话 0 字节，而同一时段 crash buffer
     * 确实有新条目。0 字节同时意味着「没崩溃」和「通道没落盘」，二者必须可区分 ——
     * 哨兵就是那个区分器，所以它必须自带 boot 起点、pid 和**真实下发的命令行**。
     */
    @Test
    fun `crash sentinel line carries boot pid and full command`() {
        val argv = AutoLogging.buildCrashLogcatCommand(
            "/data/local/tmp/cleaner_logs/cleaner-crash.log",
        )
        val line = AutoLogging.sentinelLine(
            AutoLogging.CRASH_SENTINEL_PREFIX,
            1_792_843_506_051L,
            88_095L,
            9374,
            argv.joinToString(" "),
        )

        assertTrue("必须是注释行，混进日志正文也不会被当成崩溃条目: $line", line.startsWith("#"))
        assertTrue(line.startsWith(AutoLogging.CRASH_SENTINEL_PREFIX + ": "))
        assertTrue("必须能被离线脚本按 boot 分组: $line", line.contains("boot=1792843417956"))
        assertTrue("必须区分 server 实例: $line", line.contains("pid=9374"))
        assertTrue("必须记下真实命令行，否则通道怎么起的只能靠读源码", line.contains("-b crash"))
        assertTrue(line.contains("cleaner-crash.log"))
    }
}
