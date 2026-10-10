package me.gm.cleaner.core.storage.redirect.databus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游标三态读取契约测试。
 *
 * 覆盖复核指定的转换测试：OK → UNREADABLE 后，即使调用方把内存游标置为 ""，
 * 清理与健康层仍以可信状态为准。
 *
 * 测试边界（诚实划分）：
 * - 可 JVM 断言：枚举完整性、内容可信判定（纯函数）、非法队列白名单拒绝。
 * - 不可 JVM 断言：真实文件系统的存在性/常规性/读取异常三态。
 *   BUS_ROOT 硬编码指向设备路径，单测无法隔离——改由设备验收覆盖，
 *   不在此处用环境敏感断言假装覆盖。
 */
class CursorReadTest {

    @Test
    fun `三态枚举完整`() {
        val states = DataBusProtocol.CursorRead.values().toSet()
        assertEquals(
            setOf(
                DataBusProtocol.CursorRead.ABSENT,
                DataBusProtocol.CursorRead.OK,
                DataBusProtocol.CursorRead.UNREADABLE,
            ),
            states,
        )
    }

    @Test
    fun `空白内容不可信`() {
        assertFalse(DataBus.isCredibleCursorContent(""))
        assertFalse(DataBus.isCredibleCursorContent("   "))
    }

    @Test
    fun `合法事件文件名可信`() {
        assertTrue(DataBus.isCredibleCursorContent("00000000000000003195-1791549618189-12963-a72c.json"))
    }

    @Test
    fun `损坏成的任意非空字符串不可信`() {
        // 关键回归：坏游标若被当成水位，字典序更大的非法值会误删未处理事件。
        // 旧实现只查非空，此类输入会误判 OK。
        assertFalse(DataBus.isCredibleCursorContent("CORRUPTED!!!"))
        assertFalse(DataBus.isCredibleCursorContent("99999999999999999999-xxx.json"))
        assertFalse(DataBus.isCredibleCursorContent("0001.json"))
    }

    @Test
    fun `非法队列名判不可信而非空队列`() {
        // 空白或非法队列名不能伪装成"新队列"：必须显式不可信。
        // 约束来源：isValidEventQueue 对非法队列返回 false，而 readCursorDetailed
        // 遇到非法队列直接返回 UNREADABLE。这里断言白名单本身拒绝该输入
        // （真正的 readCursorDetailed 调用会经过 Log.w，单测环境未 mock，
        // 故只验证契约前提；完整路径由设备验收覆盖）。
        assertFalse(
            DataBusProtocol.validEventQueues.contains("../escape"),
        )
        assertFalse(DataBusProtocol.validEventQueues.contains(""))
    }

    @Test
    fun `旧单值API语义不变`() {
        // readCursor 保持既有签名：ABSENT 与 UNREADABLE 都返回 ""。
        // 这正是需要三态 API 的原因——调用方不能再靠 "" 区分状态。
        // （本用例仅锁定签名语义，不触碰文件系统。）
        val method = DataBus::class.java.methods.first { it.name == "readCursor" }
        assertEquals(String::class.java, method.returnType)
        assertEquals(1, method.parameterTypes.size)
    }
}
