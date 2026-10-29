package me.gm.cleaner.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较护栏（6.0.5 第55批引入）。
 *
 * ## 被守的缺陷
 *
 * 本仓库的 GitHub release 是用中文标题建的，**`tag_name` 就是「正式版」**，
 * 真正的版本号在 `name`（`Cleaner_6.0.0`）里。旧实现直接拿 `tag_name` 比较：
 *
 * ```
 * "正式版".toLong()            → NumberFormatException
 * → 退化到 "6".compareTo("正")  → 0x36 < 0x6B63 → 负数
 * → 判定"有新版本"
 * ```
 *
 * 结果：本地 **6.0.5** 被告知"发现新版本 正式版"，而仓库里其实只有 6.0.0。
 * 只要 tag 里不含可解析的点分版本号，旧实现就**恒定误报**。
 *
 * ## 两条断言方向
 *
 * ① 能抽出版本号时正常比较（含中文 tag + name 带版本号的真机形态）；
 * ② 抽不出时**必须判"无更新"** —— 宁可漏报不可误报：误报会把用户反复推到
 *    一个不存在的更新上，漏报只是让用户自己去仓库看一眼。
 */
class UpdateCheckerVersionTest {

    @Test
    fun `从中文 tag 与带前缀的 name 里都能抽出点分版本号`() {
        // 真机形态：tag 是中文，版本号在 name 里。
        assertNull(UpdateChecker.extractVersion("正式版"))
        assertEquals("6.0.0", UpdateChecker.extractVersion("Cleaner_6.0.0"))
        // 常见前缀形态。
        assertEquals("1.2.3", UpdateChecker.extractVersion("v1.2.3"))
        assertEquals("1.2.3", UpdateChecker.extractVersion("V1.2.3"))
        assertEquals("2.0", UpdateChecker.extractVersion("release-2.0"))
        // 单段数字不算版本号：它更可能是编号或日期，认了会和 6_0_5 的前缀撞车。
        assertNull(UpdateChecker.extractVersion("6"))
        assertNull(UpdateChecker.extractVersion("2026"))
        assertNull(UpdateChecker.extractVersion(""))
        assertNull(UpdateChecker.extractVersion(null))
    }

    @Test
    fun `中文 tag 不再让本地新版本被判成旧`() {
        // 6.0.5 的实际故障复现：旧实现此时返回 true（误报新版本）。
        assertFalse(UpdateChecker.isNewer("6.0.5", "正式版"))
        // tag 抽不出但 name 抽出 6.0.0 时，按 6.0.0 比较 ⇒ 本地仍更新。
        assertFalse(UpdateChecker.isNewer("6.0.5", "Cleaner_6.0.0"))
        // 真的落后时必须能报出来。
        assertTrue(UpdateChecker.isNewer("6.0.0", "Cleaner_6.0.5"))
        assertTrue(UpdateChecker.isNewer("6.0.0", "v6.0.1"))
        // 相等不报。
        assertFalse(UpdateChecker.isNewer("6.0.5", "v6.0.5"))
        // 段数不齐时缺位补 0：6.0 < 6.0.1，6.0.0 == 6.0
        assertTrue(UpdateChecker.isNewer("6.0", "6.0.1"))
        assertFalse(UpdateChecker.isNewer("6.0.0", "6.0"))
        // 多位数按数值比较，不能按字符串（10 > 9）。
        assertTrue(UpdateChecker.isNewer("6.0.9", "6.0.10"))
    }

    @Test
    fun `任一侧不可解析时一律判无更新`() {
        // 这条是「宁可漏报」策略的钉子：改动这里必须先想清楚误报代价。
        assertFalse(UpdateChecker.isNewer("正式版", "正式版"))
        assertFalse(UpdateChecker.isNewer("6.0.5", ""))
        assertFalse(UpdateChecker.isNewer("", "6.0.0"))
        assertFalse(UpdateChecker.isNewer("正式版", "6.0.0"))
        assertFalse(UpdateChecker.isNewer("6.0.5", "最新版"))
    }
}
