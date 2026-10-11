package me.gm.cleaner.core.storage.redirect.databus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源事件删除判定契约测试：直接调用生产函数 [DataBus.shouldPruneEvent]。
 *
 * 约束：测试不得自带一份镜像判定逻辑——镜像与生产实现分叉时测试仍全绿，
 * 等于没有测试。本文件所有断言都经过生产函数。
 *
 * 门禁：删除授权 == “游标可信的前提下 name <= cursor 且超保留期”。
 * 任何一条不满足都不得删；游标比较的字典序与消费端 readEventFiles 同口径。
 *
 * 说明：retentionMs < 0 的拒绝逻辑在调用方 pruneQueueEvents，不在本函数；
 * 本函数只表达“给定水位与时间，是否可删”。
 */
class EventPrunePolicyTest {

    private val now = 10_000_000L
    private val retention = 30 * 60 * 1000L

    private fun prune(name: String, cursor: String, modifiedAgoMs: Long): Boolean =
        DataBus.shouldPruneEvent(
            name = name,
            cursor = cursor,
            lastModified = now - modifiedAgoMs,
            now = now,
            retentionMs = retention,
        )

    @Test
    fun `队首之前且超期才允许删除`() {
        assertTrue(prune("0005.json", "0010.json", 31 * 60 * 1000L))
    }

    @Test
    fun `游标之后即使超期也绝不删`() {
        assertFalse(prune("0011.json", "0010.json", 24 * 60 * 60 * 1000L))
    }

    @Test
    fun `游标相等的事件超期可删`() {
        assertTrue(prune("0010.json", "0010.json", 31 * 60 * 1000L))
    }

    @Test
    fun `保留期内不删`() {
        assertFalse(prune("0005.json", "0010.json", 29 * 60 * 1000L))
    }

    @Test
    fun `retentionMs为0只受游标约束`() {
        assertTrue(
            DataBus.shouldPruneEvent("0005.json", "0010.json", now, now, 0L),
        )
        assertFalse(
            DataBus.shouldPruneEvent("0011.json", "0010.json", now, now, 0L),
        )
    }

    @Test
    fun `空游标删0条`() {
        // "" 是最小字符串，任何非空文件名都 > ""，天然删 0 条。
        assertFalse(prune("0001.json", "", 24 * 60 * 60 * 1000L))
    }

    @Test
    fun `字典序与消费端同口径`() {
        // 消费端 readEventFiles 用 `name > afterCursor` 字符串比较，
        // 清理必须用对偶的 `name <= cursor`，两者覆盖同一分界。
        val names = listOf("0009.json", "0010.json", "0011.json")
        val read = names.filter { it > "0010.json" }
        assertEquals(listOf("0011.json"), read)
        // 已被读取（未确认）的绝不能删；已被确认越过的才允许删
        for (name in names) {
            val shouldRead = name > "0010.json"
            val mayDelete = prune(name, "0010.json", 31 * 60 * 1000L)
            if (shouldRead) assertFalse(mayDelete)
        }
    }

    private fun assertEquals(expected: List<String>, actual: List<String>) {
        assertTrue("expected=$expected actual=$actual", expected == actual)
    }
}
