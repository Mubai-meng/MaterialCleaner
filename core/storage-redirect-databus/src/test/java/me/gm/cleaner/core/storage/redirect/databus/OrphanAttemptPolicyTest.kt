package me.gm.cleaner.core.storage.redirect.databus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤儿重试计数判定契约测试：直接调用生产函数
 * [DataBusEventQuarantine.isOrphanAttempt]。
 *
 * 约束：测试不得自带一份镜像判定逻辑——镜像与生产实现分叉时测试仍全绿，
 * 等于没有测试。本文件所有断言都经过生产函数；存量集合经生产函数
 * [DataBus.sanitizeFileName] 构造（与线上写入/清理同一映射）。
 *
 * 门禁：计数文件名 == sanitize(事件名)；任一存量事件映射命中即保留，
 * 否则视为孤儿可删。
 *
 * 测试边界（诚实划分）：
 * - 可 JVM 断言：纯集合判定 + sanitize 映射一致性（均不触碰 Log/文件系统）。
 * - 不可 JVM 断言：真实目录枚举与物理删除（BUS_ROOT 硬编码指向设备路径）。
 *   pruneOrphanAttempts 的文件系统行为改由设备验收覆盖，
 *   不在此处用环境敏感断言假装覆盖。
 */
class OrphanAttemptPolicyTest {

    private val liveA = "00000000000000000010-1791549618189-12963-a72c.json"
    private val liveB = "00000000000000000011-1791549618190-12963-b83d.json"
    private val gone = "00000000000000000009-1791549618188-12963-c41e.json"

    private fun live(vararg eventNames: String): Set<String> =
        eventNames.map { DataBus.sanitizeFileName(it) }.toSet()

    @Test
    fun `事件仍在则保留计数`() {
        assertFalse(
            DataBusEventQuarantine.isOrphanAttempt(
                DataBus.sanitizeFileName(liveA),
                live(liveA, liveB),
            ),
        )
    }

    @Test
    fun `事件已不在则视为孤儿`() {
        assertTrue(
            DataBusEventQuarantine.isOrphanAttempt(
                DataBus.sanitizeFileName(gone),
                live(liveA, liveB),
            ),
        )
    }

    @Test
    fun `空存量集合下任何计数都是孤儿`() {
        assertTrue(
            DataBusEventQuarantine.isOrphanAttempt(
                DataBus.sanitizeFileName(liveA),
                emptySet(),
            ),
        )
    }

    @Test
    fun `任一存量映射命中即保留`() {
        // 保守语义：只要集合中存在命中项就保留，与集合大小与其他成员无关。
        assertFalse(DataBusEventQuarantine.isOrphanAttempt("a_b", setOf("a_b")))
        assertFalse(DataBusEventQuarantine.isOrphanAttempt("a_b", setOf("c_d", "a_b")))
        assertTrue(DataBusEventQuarantine.isOrphanAttempt("a_b", setOf("c_d")))
    }

    @Test
    fun `真实事件名经sanitize为恒等映射_口径与写入侧一致`() {
        // 真实事件名仅含安全字符（数字/-/点/hex），sanitize 为恒等映射。
        // 本断言锁定该前提：若未来命名规则引入需转义字符，此处失败会提醒
        // 复核孤儿判定的映射口径，而不是静默分叉。
        assertEquals(liveA, DataBus.sanitizeFileName(liveA))
        assertFalse(DataBusEventQuarantine.isOrphanAttempt(liveA, live(liveA)))
    }
}
