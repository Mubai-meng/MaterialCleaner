package me.gm.cleaner.runtime.server.orchestrator

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [StartupTiming] 快照契约：把「日志行会丢、内存事实不能丢」这件事钉在单测里。
 *
 * 重点覆盖三个易翻车点：
 * 1. 未记录时**不得**产出空对象（空对象会被读成"跑完了但没耗时"）；
 * 2. 缺段必须显式写 `-1`，不能被静默省略成"该段不存在"；
 * 3. 多次 initialize 只保留最后一次，且 `attempt` 能区分是第几次。
 */
class StartupTimingTest {

    @Before
    fun setUp() {
        StartupTiming.resetForTest()
    }

    @After
    fun tearDown() {
        StartupTiming.resetForTest()
    }

    @Test
    fun `unrecorded snapshot is absent rather than empty`() {
        assertNull(StartupTiming.snapshot())
        // 反向断言：调用方写快照前必须先问「有没有」，不能拿空对象冒充
        assertNull(StartupTiming.toJsonOrNull())
    }

    @Test
    fun `record keeps all nine segments in fixed order`() {
        val segments = linkedMapOf(
            StartupTiming.KEY_GATEWAY to 11L,
            StartupTiming.KEY_OBSERVERS to 22L,
            StartupTiming.KEY_PACKAGE_RECEIVER to 33L,
            StartupTiming.KEY_AUTO_LOGGING to 44L,
            StartupTiming.KEY_PUBLISH_ALL to 55L,
            StartupTiming.KEY_REMOUNT to 66L,
            StartupTiming.KEY_HOOK_REFRESH to 77L,
            StartupTiming.KEY_BINDER to 88L,
            StartupTiming.KEY_SCHEDULER to 99L,
        )
        val startedAt = 1_700_000_000_000L
        StartupTiming.record(startedAt, elapsedMs = 495L, segmentsMs = segments)

        val snapshot = StartupTiming.snapshot()
        assertNotNull(snapshot)
        requireNotNull(snapshot)
        assertEquals(1, snapshot.attempt)
        assertEquals(startedAt, snapshot.startedAtWallMillis)
        assertEquals(495L, snapshot.elapsedMs)
        assertTrue(snapshot.completedAtWallMillis >= startedAt)
        assertEquals(StartupTiming.SEGMENT_ORDER, snapshot.segmentsMs.keys.toList())

        val json = snapshot.toJson()
        val jsonSegments = json.getJSONObject("segmentsMs")
        // 键**集合**必须完整覆盖九段。刻意不断言 JSONObject 的键序：
        // Android 的 org.json 用 LinkedHashMap（保序），而 JVM 侧 `org.json:json`
        // 用 HashMap（乱序）—— 断言键序等于把测试绑在某个实现上。
        assertEquals(StartupTiming.SEGMENT_ORDER.toSet(), jsonSegments.keySet())
        assertEquals(11L, jsonSegments.getLong(StartupTiming.KEY_GATEWAY))
        assertEquals(99L, jsonSegments.getLong(StartupTiming.KEY_SCHEDULER))
    }

    @Test
    fun `missing segment is written as -1 instead of being omitted`() {
        // 只给前两段：模拟「某段未采集」而不是「某段零耗时」
        StartupTiming.record(
            startedAtWallMillis = 1L,
            elapsedMs = 2L,
            segmentsMs = mapOf(
                StartupTiming.KEY_GATEWAY to 1L,
                StartupTiming.KEY_OBSERVERS to 1L,
            ),
        )
        val segments = requireNotNull(StartupTiming.toJsonOrNull()).getJSONObject("segmentsMs")
        // 全部九个键都必须存在
        assertEquals(StartupTiming.SEGMENT_ORDER.size, segments.length())
        assertEquals(-1L, segments.getLong(StartupTiming.KEY_BINDER))
        assertEquals(-1L, segments.getLong(StartupTiming.KEY_SCHEDULER))
    }

    @Test
    fun `later initialize overwrites and increments attempt`() {
        StartupTiming.record(100L, 10L, mapOf(StartupTiming.KEY_GATEWAY to 10L))
        StartupTiming.record(200L, 20L, mapOf(StartupTiming.KEY_GATEWAY to 20L))

        val snapshot = requireNotNull(StartupTiming.snapshot())
        assertEquals(2, snapshot.attempt)
        assertEquals(200L, snapshot.startedAtWallMillis)
        assertEquals(20L, snapshot.elapsedMs)
        assertEquals(20L, snapshot.segmentsMs[StartupTiming.KEY_GATEWAY])
    }

    @Test
    fun `recorded map is copied so caller mutation cannot leak in`() {
        val mutable = linkedMapOf(StartupTiming.KEY_GATEWAY to 5L)
        StartupTiming.record(1L, 5L, mutable)
        mutable[StartupTiming.KEY_GATEWAY] = 999L
        mutable[StartupTiming.KEY_BINDER] = 888L

        val snapshot = requireNotNull(StartupTiming.snapshot())
        assertEquals(5L, snapshot.segmentsMs[StartupTiming.KEY_GATEWAY])
        assertNull(snapshot.segmentsMs[StartupTiming.KEY_BINDER])
    }

    @Test
    fun `json exposes attempt and wall clock bounds`() {
        StartupTiming.record(10L, 7L, emptyMap())
        val json: JSONObject = requireNotNull(StartupTiming.toJsonOrNull())
        assertEquals(1, json.getInt("attempt"))
        assertEquals(10L, json.getLong("startedAtWallMillis"))
        assertEquals(7L, json.getLong("elapsedMs"))
        assertTrue(json.getLong("completedAtWallMillis") >= 10L)
    }
}
