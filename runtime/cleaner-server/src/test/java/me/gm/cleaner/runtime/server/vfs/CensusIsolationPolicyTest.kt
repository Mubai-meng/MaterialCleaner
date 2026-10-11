package me.gm.cleaner.runtime.server.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CensusIsolationPolicy] 契约测试。
 *
 * 重点是**反向证据与边界**（验证纪律）：
 * - uid 读取失败（-1/-2/-3）必须排除，不得退化成「非隔离放行」；
 * - uid=0（root）与负值 -1 只差一个符号，必须分别覆盖；
 * - 框架判定与本项目判定不一致时的优先级。
 */
class CensusIsolationPolicyTest {

    @Test
    fun `普通 app uid 纳入统计`() {
        assertEquals(CensusVerdict.INCLUDE, CensusIsolationPolicy.verdict(10369, false))
        assertTrue(CensusIsolationPolicy.shouldInclude(10369, false))
    }

    @Test
    fun `框架判定隔离则排除`() {
        assertEquals(CensusVerdict.EXCLUDE_ISOLATED, CensusIsolationPolicy.verdict(99000, true))
        assertFalse(CensusIsolationPolicy.shouldInclude(99000, true))
    }

    @Test
    fun `uid 读取失败一律排除_不得退化成放行`() {
        // cgroup v2 下 read_uid 恒返回 -1；-2/-3 是 native 的打开失败分支。
        for (broken in listOf(-1, -2, -3, Int.MIN_VALUE)) {
            assertEquals(
                "uid=$broken 必须判为不可读而非放行",
                CensusVerdict.EXCLUDE_UID_UNREADABLE,
                CensusIsolationPolicy.verdict(broken, false),
            )
            assertFalse(CensusIsolationPolicy.shouldInclude(broken, false))
        }
    }

    @Test
    fun `uid 读取失败时即便框架说非隔离也不放行`() {
        // 调用方可能传 false 进来（如 hidden API 对负数返回 false）；
        // 不可读必须优先于该判定。
        assertEquals(
            CensusVerdict.EXCLUDE_UID_UNREADABLE,
            CensusIsolationPolicy.verdict(-1, false),
        )
    }

    @Test
    fun `uid0 root 不是隔离进程`() {
        assertEquals(CensusVerdict.INCLUDE, CensusIsolationPolicy.verdict(0, false))
    }

    @Test
    fun `框架说隔离但 uid 已知时按隔离排除`() {
        // 沙箱渲染进程的真 uid 落在 99000+；即便本项目没再做 appId 判定，
        // 框架判定已足够。
        assertEquals(CensusVerdict.EXCLUDE_ISOLATED, CensusIsolationPolicy.verdict(99123, true))
    }
}
