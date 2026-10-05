package me.gm.cleaner.runtime.server.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * logcat 行解析门禁测试：threadtime 的 pid/tid 列非定宽，
 * 解析禁止依赖 tag 下标（历史教训：INDEX_OF_TAG 按发射线程位宽随机丢行）。
 */
class AmLogLineParserTest {
    private val startTag = "ActivityManager: Start proc"
    private val killingTag = "ActivityManager: Killing"
    private val phantom = "PhantomProcessRecord"

    @Test
    fun `四加四位宽命中`() {
        val parsed = ActivityManagerLogsObserver.parseStartProcLine(
            "10-05 11:19:30.676  2725  3421 I ActivityManager: Start proc " +
                "340:com.miui.calculator/u0a254 for next-top-activity {x/y} caller=com.android.shell",
            startTag,
        )
        assertNotNull(parsed)
        assertEquals(340, parsed!!.pid)
        assertEquals("com.miui.calculator", parsed.processName)
        assertEquals("u0a254", parsed.principal)
    }

    @Test
    fun `mixed 位宽全部命中`() {
        val cases = listOf(
            // 4+5
            "10-05 11:16:15.713  2735 14366 I ActivityManager: Start proc " +
                "11847:com.coolapk.market/u0a283 for service {a/b} caller=c" to 11847,
            // 5+4
            "10-05 11:16:15.713 27350  3421 I ActivityManager: Start proc " +
                "11847:com.coolapk.market/u0a283 for service {a/b} caller=c" to 11847,
            // 5+5
            "10-05 11:16:15.713 26053 29754 I ActivityManager: Start proc " +
                "11847:com.coolapk.market/u0a283 for service {a/b} caller=c" to 11847,
        )
        for ((line, pid) in cases) {
            val parsed = ActivityManagerLogsObserver.parseStartProcLine(line, startTag)
            assertNotNull("missed: $line", parsed)
            assertEquals(pid, parsed!!.pid)
            assertEquals("com.coolapk.market", parsed.processName)
            assertEquals("u0a283", parsed.principal)
        }
    }

    @Test
    fun `异形行返回空`() {
        // 无 tag（大小写敏感，前缀需精确）
        assertNull(
            ActivityManagerLogsObserver.parseStartProcLine(
                "10-05 11:19:30.676  2725  3421 I ActivityManager: StartProc 1:a/b {c}",
                startTag,
            ),
        )
        // 无大括号
        assertNull(
            ActivityManagerLogsObserver.parseStartProcLine(
                "10-05 11:19:30.676  2725  3421 I ActivityManager: Start proc 1:a/b no-brace",
                startTag,
            ),
        )
        // pid 非数字
        assertNull(
            ActivityManagerLogsObserver.parseStartProcLine(
                "10-05 11:19:30.676  2725  3421 I ActivityManager: Start proc abc:a/b {c}",
                startTag,
            ),
        )
    }

    @Test
    fun `killing 行解析与幽灵进程过滤`() {
        val parsed = ActivityManagerLogsObserver.parseKillingLine(
            "10-05 11:20:00.000  2735 14366 I ActivityManager: Killing " +
                "1234:com.foo/u0a100 (adj 0): stop com.foo due to from pid 1234",
            killingTag,
            phantom,
        )
        assertNotNull(parsed)
        assertEquals(1234, parsed!!.pid)
        assertEquals("com.foo", parsed.processName)
        assertEquals("u0a100", parsed.principal)

        assertNull(
            ActivityManagerLogsObserver.parseKillingLine(
                "10-05 11:20:00.000  2735 14366 I ActivityManager: Killing " +
                    "PhantomProcessRecord:foo",
                killingTag,
                phantom,
            ),
        )
    }
}
