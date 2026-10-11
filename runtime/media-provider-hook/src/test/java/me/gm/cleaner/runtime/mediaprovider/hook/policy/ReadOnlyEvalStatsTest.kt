package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 只读判定的三段式计数单测：evaluated / matched / denied*。
 *
 * 存在的意义是让下一批实机材料能直接回答「规则有没有被求值 / 有没有命中 / 拒绝有没有生效」，
 * 而不是像前几批那样只能看到一条恒为 0 的日志然后靠猜。
 */
class ReadOnlyEvalStatsTest {

    @Before
    fun setUp() {
        ReadOnlyEvalStats.resetForTest()
    }

    @Test
    fun `未命中只累加evaluated`() {
        ReadOnlyEvalStats.recordEval("com.taobao.taobao", false, null)
        val s = ReadOnlyEvalStats.snapshot()
        assertEquals(1L, s["evaluated"])
        assertEquals(0L, s["matched"])
        assertEquals("com.taobao.taobao", s["lastEvaluatedPackage"])
    }

    @Test
    fun `命中累加matched并记录规则路径`() {
        ReadOnlyEvalStats.recordEval("com.tencent.mobileqq", true, "/storage/emulated/0/Download")
        val s = ReadOnlyEvalStats.snapshot()
        assertEquals(1L, s["evaluated"])
        assertEquals(1L, s["matched"])
        assertEquals("com.tencent.mobileqq", s["lastMatchedPackage"])
        assertEquals("/storage/emulated/0/Download", s["lastMatchedRule"])
    }

    @Test
    fun `命中但规则路径为空时保留上一条`() {
        ReadOnlyEvalStats.recordEval("a.b.c", true, "/storage/emulated/0/DCIM")
        ReadOnlyEvalStats.recordEval("a.b.c", true, null)
        assertEquals("/storage/emulated/0/DCIM", ReadOnlyEvalStats.snapshot()["lastMatchedRule"])
        assertEquals(2L, ReadOnlyEvalStats.snapshot()["matched"])
    }

    @Test
    fun `拒绝按点分别计数`() {
        ReadOnlyEvalStats.recordDenial(ReadOnlyEvalStats.DenyPoint.FILE)
        ReadOnlyEvalStats.recordDenial(ReadOnlyEvalStats.DenyPoint.FILE)
        ReadOnlyEvalStats.recordDenial(ReadOnlyEvalStats.DenyPoint.OPEN)
        val s = ReadOnlyEvalStats.snapshot()
        assertEquals(2L, s["deniedFile"])
        assertEquals(1L, s["deniedOpen"])
        assertEquals(0L, s["deniedDir"])
        assertEquals(0L, s["deniedRename"])
    }

    @Test
    fun `四个拒绝点键名稳定`() {
        val keys = ReadOnlyEvalStats.snapshot().keys
        listOf("deniedFile", "deniedDir", "deniedOpen", "deniedRename").forEach {
            assertTrue("missing $it", keys.contains(it))
        }
    }

    @Test
    fun `resetForTest清零全部`() {
        ReadOnlyEvalStats.recordEval("p", true, "/x")
        ReadOnlyEvalStats.recordDenial(ReadOnlyEvalStats.DenyPoint.RENAME)
        ReadOnlyEvalStats.resetForTest()
        val s = ReadOnlyEvalStats.snapshot()
        assertEquals(0L, s["evaluated"])
        assertEquals(0L, s["matched"])
        assertEquals(0L, s["deniedRename"])
        assertEquals("", s["lastMatchedPackage"])
    }
}
