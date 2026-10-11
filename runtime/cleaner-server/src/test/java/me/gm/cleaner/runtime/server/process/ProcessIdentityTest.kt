package me.gm.cleaner.runtime.server.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessIdentityTest {

    @Test
    fun `解析标准stat`() {
        // pid=1234 comm含空格与括号；')' 后第20个token为starttime=987654。
        val stat = "1234 (my app (x)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20 21"
        assertEquals(987654L, ProcessIdentity.parseStartTime(stat))
    }

    @Test
    fun `字段不足返回空`() {
        assertNull(ProcessIdentity.parseStartTime("1234 (x) S 1 2"))
        assertNull(ProcessIdentity.parseStartTime("garbage"))
        assertNull(ProcessIdentity.parseStartTime(""))
    }

    @Test
    fun `非法starttime返回空`() {
        assertNull(ProcessIdentity.parseStartTime("1 (x) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 0 1 2"))
    }

    @Test
    fun `不存在的pid返回空`() {
        assertNull(ProcessIdentity.currentInstance(Int.MAX_VALUE))
        assertNull(ProcessIdentity.currentInstance(-1))
    }
}
