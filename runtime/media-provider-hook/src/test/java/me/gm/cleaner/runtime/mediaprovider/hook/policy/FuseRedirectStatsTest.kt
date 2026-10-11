package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [FuseRedirectStats] 契约测试。
 *
 * 三条硬要求（每条都有对应用例，缺一不可）：
 * 1. **文件名绝不能出现在前缀里** —— 快照会被导出分享；
 * 2. 必须能区分"落在共享存储根"与"落在某个 target 子目录"；
 * 3. 边界：空串 / 只有根 / 只有一段 / 结尾斜杠 / 重复斜杠 / 自定义段数。
 */
class FuseRedirectStatsTest {

    @Test
    fun `区分到 target 子目录`() {
        assertEquals(
            "/storage/emulated/0/Download",
            FuseRedirectStats.prefixOf("/storage/emulated/0/Download/a.apk"),
        )
    }

    @Test
    fun `共享存储根目录下的文件只到根`() {
        assertEquals(
            "/storage/emulated/0",
            FuseRedirectStats.prefixOf("/storage/emulated/0/dsprobe_m.log"),
        )
    }

    @Test
    fun `文件名绝不出现在前缀里`() {
        // 实测里 deepseek 触碰的两条路径。
        for (p in listOf(
            "/storage/emulated/0/dsprobe_m.log",
            "/storage/emulated/0/deekseep_loaded_m.txt",
        )) {
            val prefix = FuseRedirectStats.prefixOf(p)
            assertEquals("/storage/emulated/0", prefix)
            assertFalse("前缀不得含文件名: $prefix", prefix.contains("dsprobe"))
            assertFalse("前缀不得含文件名: $prefix", prefix.contains("deekseep"))
        }
    }

    @Test
    fun `深路径按段上限截断且仍不含文件名`() {
        val p = "/storage/emulated/0/Android/data/com.deepseek.chat/files/a/b/c.log"
        assertEquals(
            "/storage/emulated/0/Android/data",
            FuseRedirectStats.prefixOf(p),
        )
    }

    @Test
    fun `只有一段时保留原样`() {
        assertEquals("/storage", FuseRedirectStats.prefixOf("/storage"))
        assertEquals("/", FuseRedirectStats.prefixOf("/"))
        assertEquals("relative", FuseRedirectStats.prefixOf("relative/path"))
    }

    @Test
    fun `空串与非正段数返回空串`() {
        assertEquals("", FuseRedirectStats.prefixOf(""))
        assertEquals("", FuseRedirectStats.prefixOf("/storage/emulated/0/x", 0))
        assertEquals("", FuseRedirectStats.prefixOf("/storage/emulated/0/x", -1))
    }

    @Test
    fun `重复斜杠与结尾斜杠被规范化`() {
        assertEquals(
            "/storage/emulated/0",
            FuseRedirectStats.prefixOf("//storage//emulated///0/dsprobe_m.log"),
        )
        assertEquals(
            "/storage/emulated/0/Download",
            FuseRedirectStats.prefixOf("/storage/emulated/0/Download/"),
        )
    }

    @Test
    fun `自定义段数可加深或变浅`() {
        val p = "/storage/emulated/0/Download/sub/a.apk"
        assertEquals("/storage/emulated/0", FuseRedirectStats.prefixOf(p, 3))
        assertEquals("/storage/emulated/0/Download", FuseRedirectStats.prefixOf(p, 4))
        assertEquals("/storage/emulated/0/Download/sub", FuseRedirectStats.prefixOf(p, 5))
    }

    @Test
    fun `outcome 只有真正改写才算命中`() {
        assertEquals(
            FuseRedirectStats.Outcome.REDIRECTED,
            FuseRedirectStats.outcome(
                "/storage/emulated/0/Android/data/x/files", "/storage/emulated/0/Download",
            ),
        )
        // 规则存在但派生结果与原文相同：与"没匹配到"同类，判 MISS。
        assertEquals(
            FuseRedirectStats.Outcome.MISS,
            FuseRedirectStats.outcome("/storage/emulated/0/Download", "/storage/emulated/0/Download"),
        )
        assertEquals(FuseRedirectStats.Outcome.MISS, FuseRedirectStats.outcome(null, "/storage/x"))
    }
}
