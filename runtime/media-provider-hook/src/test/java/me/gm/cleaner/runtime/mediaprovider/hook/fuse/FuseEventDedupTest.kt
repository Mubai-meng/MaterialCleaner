package me.gm.cleaner.runtime.mediaprovider.hook.fuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUSE 文件事件前置去重的契约（回归护栏）。
 *
 * 动机（第 6 批设备证据）：`events/filesystem` 归档 124 条，最近 20 条**完全相同**
 * 且只跨 1.6 s；每条 2 次 fsync 而下游 `FileSystemRecord` 用 `upsertRecords=true`
 * 的 `UPDATE OR IGNORE (package_name, path, flags)` 本来就会把同 key 折叠成一行。
 *
 * 关键契约：
 * 1. 窗口内同 key 只放行第一条；
 * 2. **过窗即恢复放行**（不能变成永久抑制，否则会真的丢事件）；
 * 3. 不同 key 互不影响（flags / method 参与身份）；
 * 4. 有界（不随路径数无界增长）；
 * 5. `suppressedCount` 单调累计 —— 它是诊断归档里证明机制生效的唯一凭据。
 */
class FuseEventDedupTest {

    private val key = "pkg\n/storage/emulated/0/a.log\n256\ninsertFileIfNecessaryForFuse"

    @Test
    fun `窗口内同 key 只放行第一条`() {
        val dedup = FuseEventDedup(windowMs = 2_000L)
        assertTrue(dedup.shouldEmit(key, 1_000L))
        assertFalse(dedup.shouldEmit(key, 1_500L))
        assertFalse(dedup.shouldEmit(key, 2_999L))
        assertEquals(2L, dedup.suppressedCount)
    }

    @Test
    fun `过窗即恢复放行`() {
        val dedup = FuseEventDedup(windowMs = 2_000L)
        assertTrue(dedup.shouldEmit(key, 1_000L))
        // 恰好等于窗口边界：1_000 + 2_000 = 3_000 ⇒ 不再视为重复
        assertTrue(dedup.shouldEmit(key, 3_000L))
        assertEquals(0L, dedup.suppressedCount)
    }

    @Test
    fun `过窗后时间戳刷新而不是按首次发射计时`() {
        val dedup = FuseEventDedup(windowMs = 2_000L)
        assertTrue(dedup.shouldEmit(key, 0L))
        assertTrue(dedup.shouldEmit(key, 2_000L))
        // 若误按"首次发射 + 窗口"判定，这里 3_000 就会被放过；正确实现应被抑制。
        assertFalse("窗口应从最近一次放行起算", dedup.shouldEmit(key, 3_000L))
        assertTrue(dedup.shouldEmit(key, 4_000L))
    }

    @Test
    fun `不同 key 互不影响`() {
        val dedup = FuseEventDedup(windowMs = 2_000L)
        assertTrue(dedup.shouldEmit("pkg\n/p\n1\nm", 0L))
        assertTrue("flags 不同属不同身份", dedup.shouldEmit("pkg\n/p\n2\nm", 0L))
        assertTrue("方法不同属不同身份", dedup.shouldEmit("pkg\n/p\n1\nm2", 0L))
        assertTrue("路径不同属不同身份", dedup.shouldEmit("pkg\n/p2\n1\nm", 0L))
        assertTrue("包名不同属不同身份", dedup.shouldEmit("pkg2\n/p\n1\nm", 0L))
        assertEquals(0L, dedup.suppressedCount)
    }

    @Test
    fun `表有界且淘汰最旧项`() {
        val dedup = FuseEventDedup(windowMs = 2_000L, maxEntries = 8)
        // 造 8 个不同 key 且都未过窗
        for (i in 0 until 8) {
            assertTrue(dedup.shouldEmit("k$i", 0L))
        }
        assertEquals(8, dedup.trackedKeys)
        // 第 9 个触发淘汰，表仍为 8
        assertTrue(dedup.shouldEmit("k8", 0L))
        assertEquals(8, dedup.trackedKeys)
        // k0 已被淘汰 ⇒ 同一时刻再发也被视为首次
        assertTrue("被淘汰的 key 应重新放行而不是永久抑制", dedup.shouldEmit("k0", 0L))
    }

    @Test
    fun `reset 清空状态`() {
        val dedup = FuseEventDedup(windowMs = 2_000L)
        assertTrue(dedup.shouldEmit(key, 0L))
        assertFalse(dedup.shouldEmit(key, 0L))
        dedup.reset()
        assertEquals(0L, dedup.suppressedCount)
        assertEquals(0, dedup.trackedKeys)
        assertTrue(dedup.shouldEmit(key, 0L))
    }

    @Test
    fun `默认窗口与消费者轮询周期一致`() {
        // 窗口 = EventConsumerScheduler 的 2s 轮询周期：语义为"每个 key 每轮最多一条",
        // 正好就是下游 upsert 会留下的那一行。改动它要先改下游语义。
        assertEquals(2_000L, FuseEventDedup.DEFAULT_WINDOW_MS)
    }
}
