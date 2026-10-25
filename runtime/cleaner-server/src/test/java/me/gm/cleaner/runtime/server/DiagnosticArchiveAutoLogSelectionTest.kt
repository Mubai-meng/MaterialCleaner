package me.gm.cleaner.runtime.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 钉住诊断包的日志选择口径：
 * - 启动窗口段（`cleaner-boot-*`）**优先**，即使它比主环形旧也不能被挤掉；
 * - 启动段与主环形各自独立限额（2 / 8），不能互相占位。
 *
 * 背景：实测挂载失败发生在 server 启动后 1 秒内，而主环形几分钟内就会把它滚掉。
 * 若归档仍按纯 mtime `take(8)`，启动段永远进不了包。
 */
class DiagnosticArchiveAutoLogSelectionTest {

    private val dir = File("build/auto-log-select-test")

    // 用 2023 年的基准时间 + 秒级间隔，避免 Windows 上 mtime 精度/纪元边界问题。
    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, mtime: Long): File {
        val f = File(dir, name)
        f.writeText("x")
        check(f.setLastModified(mtime)) { "cannot set mtime on $f" }
        return f
    }

    @Test
    fun `boot file is kept even when every ring file is newer`() {
        val boot = file("cleaner-boot-1.log", base)
        val ring = file("cleaner.log", base + 60_000L)

        val selected = DiagnosticArchive.selectAutoLogFiles(listOf(boot, ring))

        assertEquals(listOf(boot, ring), selected)
    }

    @Test
    fun `boot and ring caps are applied independently`() {
        val boots = (1..4).map { file("cleaner-boot-$it.log", base + it * 1_000L) }
        val rings = (1..10).map { file("ring-$it.log", base + 100_000L + it * 1_000L) }

        val selected = DiagnosticArchive.selectAutoLogFiles(boots + rings)

        assertEquals(2 + 8, selected.size)
        // 启动段按时间倒序取最新 2 个
        assertEquals(boots[3], selected[0])
        assertEquals(boots[2], selected[1])
        // 主环形按时间倒序取最新 8 个
        assertEquals(rings[9], selected[2])
        assertEquals(rings[2], selected[9])
    }

    @Test
    fun `only boot files still works`() {
        val boots = (1..3).map { file("cleaner-boot-$it.log", base + it * 1_000L) }

        val selected = DiagnosticArchive.selectAutoLogFiles(boots)

        assertEquals(2, selected.size)
        assertEquals(boots[2], selected[0])
        assertEquals(boots[1], selected[1])
    }

    @Test
    fun `excluded files are reported with the oldest first`() {
        // 第 48 批 §4.2：manifest 只打 total=5 / included=4，被丢的那份
        //（含重启前 86 秒现场）在清单里完全不存在，排障者只能另翻 tree 文件。
        val boots = (1..4).map { file("cleaner-boot-$it.log", base + it * 1_000L) }

        val selected = DiagnosticArchive.selectAutoLogFiles(boots)
        val excluded = DiagnosticArchive.excludedAutoLogFiles(boots, selected)

        assertEquals(2, excluded.size)
        // 按 mtime 降序：新者在前，故最旧的落在末尾
        assertEquals(boots[1], excluded[0])
        assertEquals(boots[0], excluded[1])
    }

    @Test
    fun `excluded keeps boot and ring overflow in one list`() {
        // 3 份启动段只留 2、10 份主环形只留 8 ⇒ 共丢 3 份；
        // 混合清单必须把两类都列出来，否则「到底谁被丢」依旧是猜。
        val boots = (1..3).map { file("cleaner-boot-$it.log", base + it * 1_000L) }
        val rings = (1..10).map { file("ring-$it.log", base + 100_000L + it * 1_000L) }

        val selected = DiagnosticArchive.selectAutoLogFiles(boots + rings)
        val excluded = DiagnosticArchive.excludedAutoLogFiles(boots + rings, selected)

        assertEquals(3, excluded.size)
        assertTrue("被丢的最旧一份应是启动段", excluded.last() in boots)
        assertTrue("没超限时不得凭空产生过期项", selected.none { it in excluded })
    }

    @Test
    fun `nothing is excluded when everything fits`() {
        // 回归护栏：清单的 excluded 段不能把「已经收进包的文件」重复列一遍。
        val boots = (1..2).map { file("cleaner-boot-$it.log", base + it * 1_000L) }
        val rings = (1..3).map { file("ring-$it.log", base + 100_000L + it * 1_000L) }

        val selected = DiagnosticArchive.selectAutoLogFiles(boots + rings)

        assertEquals(5, selected.size)
        assertEquals(emptyList<File>(), DiagnosticArchive.excludedAutoLogFiles(boots + rings, selected))
    }
}
