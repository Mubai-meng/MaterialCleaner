package me.gm.cleaner.runtime.mediaprovider.hook.err

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HookErrorJournal] 行为契约测试。
 *
 * 覆盖点按「反向证据 + 边界」优先（验证纪律）：
 * - 合并窗口内**不**新增槽位、窗口外**必须**新增；
 * - 容量上限时淘汰的必须是**最旧**槽位；
 * - 空流水 / 非法参数 / 边界值（count=1、窗口恰好闭合）。
 */
class HookErrorJournalTest {

    @Before
    fun setUp() {
        HookErrorJournal.clear()
    }

    @Test
    fun `record 写入一条并可由 snapshot 读回`() {
        val entry = HookErrorJournal.record(
            code = "HOOK.JAVA.GUARD.HANDLER_FAILED",
            severity = HookErrorJournal.SEVERITY_ERROR,
            detail = "boom",
            subject = "QueryHooker",
            errno = 0,
            generation = 7L,
            nowElapsed = 1_000L,
        )
        assertEquals("HOOK.JAVA.GUARD.HANDLER_FAILED", entry!!.code)
        assertEquals("E", entry.severity)
        assertEquals("QueryHooker", entry.subject)
        assertEquals(7L, entry.generation)
        assertEquals(1_000L, entry.firstAtElapsed)
        assertEquals(1_000L, entry.lastAtElapsed)
        assertEquals(1L, entry.count)

        val snapshot = HookErrorJournal.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(1L, HookErrorJournal.total())
        assertEquals(1, HookErrorJournal.size())
    }

    @Test
    fun `同一 key 重复出现只累加计数不新增槽位`() {
        repeat(5) { i ->
            HookErrorJournal.record(
                code = "HOOK.JAVA.FUSE.HANDLER_FAILED",
                severity = HookErrorJournal.SEVERITY_ERROR,
                detail = "cannot find uid",
                subject = "FuseJavaGate",
                errno = 0,
                generation = 0L,
                nowElapsed = 10_000L + i * 1_000L,
            )
        }
        assertEquals("同类合并后只应占一个槽位", 1, HookErrorJournal.size())
        assertEquals("累计次数必须保留", 5L, HookErrorJournal.total())
        val only = HookErrorJournal.snapshot().single()
        assertEquals(5L, only.count)
        assertEquals(10_000L, only.firstAtElapsed)
        assertEquals(14_000L, only.lastAtElapsed)
    }

    @Test
    fun `同一 key 间隔很久再次出现时保留首次时刻并累加计数`() {
        HookErrorJournal.record(
            code = "C", severity = "W", detail = "d", subject = null,
            errno = 0, generation = 0L, nowElapsed = 1_000L,
        )
        HookErrorJournal.record(
            code = "C", severity = "W", detail = "d", subject = null,
            errno = 0, generation = 0L, nowElapsed = 5_000_000L,
        )
        // 聚合而非新开槽位：槽位数不变，计数累加，首次时刻不得被重置。
        assertEquals(1, HookErrorJournal.size())
        assertEquals(2L, HookErrorJournal.total())
        val only = HookErrorJournal.snapshot().single()
        assertEquals(1_000L, only.firstAtElapsed)
        assertEquals(5_000_000L, only.lastAtElapsed)
        assertEquals(2L, only.count)
    }

    @Test
    fun `时钟回拨时末次时刻不倒退`() {
        HookErrorJournal.record(
            code = "C", severity = "W", detail = "d", subject = null,
            errno = 0, generation = 0L, nowElapsed = 5_000L,
        )
        HookErrorJournal.record(
            code = "C", severity = "W", detail = "d", subject = null,
            errno = 0, generation = 0L, nowElapsed = 1_000L,
        )
        val only = HookErrorJournal.snapshot().single()
        assertEquals(5_000L, only.firstAtElapsed)
        assertEquals(5_000L, only.lastAtElapsed)
        assertEquals(2L, only.count)
    }

    @Test
    fun `detail 不同视为不同槽位`() {
        HookErrorJournal.record(
            code = "C", severity = "W", detail = "a", subject = null,
            errno = 0, generation = 0L, nowElapsed = 0L,
        )
        HookErrorJournal.record(
            code = "C", severity = "W", detail = "b", subject = null,
            errno = 0, generation = 0L, nowElapsed = 10L,
        )
        assertEquals(2, HookErrorJournal.size())
    }

    @Test
    fun `超过容量淘汰最旧槽位`() {
        repeat(HookErrorJournal.CAPACITY + 5) { i ->
            HookErrorJournal.record(
                code = "C$i", severity = "W", detail = null, subject = null,
                errno = 0, generation = 0L,
                // 每条 key 不同（code 含序号），保证各占一槽
                nowElapsed = i.toLong() * 60_000L,
            )
        }
        assertEquals(HookErrorJournal.CAPACITY, HookErrorJournal.size())
        val codes = HookErrorJournal.snapshot().map { it.code }
        assertTrue("最旧的 5 条必须已被淘汰", "C0" !in codes)
        assertTrue("次旧的也必须已被淘汰", "C4" !in codes)
        assertTrue("最新的必须保留", "C${HookErrorJournal.CAPACITY + 4}" in codes)
    }

    @Test
    fun `snapshot 按末次时刻升序`() {
        val times = listOf(30_000L, 0L, 60_000L, 15_000L)
        times.forEachIndexed { i, t ->
            HookErrorJournal.record(
                code = "C$i", severity = "W", detail = null, subject = null,
                errno = 0, generation = 0L, nowElapsed = t,
            )
        }
        val got = HookErrorJournal.snapshot().map { it.lastAtElapsed }
        assertEquals(times.sorted(), got)
    }

    @Test
    fun `非法参数返回 null 且不改变状态`() {
        assertNull(
            HookErrorJournal.record(
                code = "", severity = "W", detail = null, subject = null,
                errno = 0, generation = 0L, nowElapsed = 1L,
            )
        )
        assertNull(
            HookErrorJournal.record(
                code = "C", severity = "W", detail = null, subject = null,
                errno = 0, generation = 0L, nowElapsed = -1L,
            )
        )
        assertEquals(0, HookErrorJournal.size())
        assertEquals(0L, HookErrorJournal.total())
    }

    @Test
    fun `detail 超长被截断`() {
        val entry = HookErrorJournal.record(
            code = "C", severity = "W", detail = "x".repeat(500), subject = null,
            errno = 13, generation = 0L, nowElapsed = 0L,
        )
        assertEquals(200, entry!!.detail!!.length)
        assertEquals(13, entry.errno)
    }

    @Test
    fun `clear 同时清零槽位与累计`() {
        HookErrorJournal.record(
            code = "C", severity = "W", detail = null, subject = null,
            errno = 0, generation = 0L, nowElapsed = 0L,
        )
        HookErrorJournal.clear()
        assertEquals(0, HookErrorJournal.size())
        assertEquals(0L, HookErrorJournal.total())
        assertTrue(HookErrorJournal.snapshot().isEmpty())
    }

    @Test
    fun `toCompactString 不泄露明文路径且含关键字段`() {
        val line = HookErrorJournal.Entry(
            code = "HOOK.JAVA.BUS.WRITE_FAILED",
            severity = "W",
            detail = "writeEvent",
            subject = "HookDataBusBridge",
            errno = 5,
            generation = 3L,
            firstAtElapsed = 0L,
            lastAtElapsed = 99L,
            count = 4L,
        ).toCompactString()
        assertTrue(line.contains("W HOOK.JAVA.BUS.WRITE_FAILED"))
        assertTrue(line.contains("x4"))
        assertTrue(line.contains("errno=5"))
        assertTrue(line.contains("subject=HookDataBusBridge"))
        assertTrue(line.contains("gen=3"))
        assertTrue(line.contains("at=99"))
    }
}
