package me.gm.cleaner.runtime.mediaprovider.hook.policy

import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整盘重定向下的「不可达媒体行」判定护栏。
 *
 * 规则取自 2026-10-28 第 55 批真机快照（`com.coloros.gallery3d`）。
 * 实机现象：图片选择器里部分图片正常、部分空白，新拍的照片正常——
 * 恰好对应 carve-out 内可达 / carve-out 外不可达。
 */
class RedirectShadowFilterTest {

    private val sd = "/storage/emulated/0"
    private val pkg = "com.coloros.gallery3d"
    private val selfDir = "$sd/Android/data/$pkg"

    private fun onDeviceRules() = listOf(
        RedirectRule("$selfDir/sdcard", sd),
        RedirectRule(selfDir, selfDir),
        RedirectRule("$sd/DCIM", "$sd/DCIM"),
        RedirectRule("$sd/Pictures", "$sd/Pictures"),
    )

    @Test
    fun `真机规则启用且白名单为 DCIM Pictures 自身目录`() {
        val rules = onDeviceRules()
        assertTrue(RedirectShadowFilter.isEnabled(rules))
        assertEquals(
            setOf(selfDir, "$sd/DCIM", "$sd/Pictures"),
            RedirectShadowFilter.reachableRoots(rules),
        )
    }

    @Test
    fun `carve-out 内的行保留 carve-out 外的行剔除`() {
        val rules = onDeviceRules()
        val roots = RedirectShadowFilter.reachableRoots(rules)

        // 判定与实机现象逐项对齐：能显示的是这些。
        for (path in listOf(
            "$sd/DCIM/Camera/20261028.jpg",
            "$sd/Pictures/WeiXin/wx.jpg",
            "$selfDir/x.jpg",
        )) {
            assertFalse("应可达：$path", RedirectShadowFilter.isUnreachable(roots, sd, path))
        }
        // 空白格对应这些。
        for (path in listOf(
            "$sd/Documents/教程/a.jpg",
            "$sd/Download/b.png",
            "$sd/Movies/c.mp4",
            "$sd/Android/media/com.tencent.mm/d.jpg",
            "$sd/DCIMX/e.jpg",
        )) {
            assertTrue("应不可达：$path", RedirectShadowFilter.isUnreachable(roots, sd, path))
        }
    }

    @Test
    fun `无整盘重定向时恒不干预`() {
        // 安全边界：空白名单绝不能理解为"什么都不允许"。
        val rules = listOf(RedirectRule("$sd/Download/Browser", "$sd/Download/QuarkCloudDrive"))
        assertFalse(RedirectShadowFilter.isEnabled(rules))
        val roots = RedirectShadowFilter.reachableRoots(rules)
        assertTrue(roots.isEmpty())
        assertFalse(RedirectShadowFilter.isUnreachable(roots, sd, "$sd/Documents/a.jpg"))
        assertNull(RedirectShadowFilter.buildExclusion(roots, sd))

        // 无规则、空列表同样不干预。
        assertFalse(RedirectShadowFilter.isUnreachable(emptySet(), sd, "/storage/emulated/0/a.jpg"))
        assertFalse(RedirectShadowFilter.isUnreachable(emptySet(), null, "/x/a.jpg"))
    }

    @Test
    fun `非共享存储卷的行不受影响`() {
        val roots = RedirectShadowFilter.reachableRoots(onDeviceRules())
        // 另一个用户的卷、外置 SD、/data 下的行都不在整盘重定向作用域内。
        for (path in listOf(
            "/storage/emulated/999/Documents/a.jpg",
            "/storage/1B0F-3A2E/DCIM/a.jpg",
            "/data/local/tmp/a.jpg",
        )) {
            assertFalse("不该干预：$path", RedirectShadowFilter.isUnreachable(roots, sd, path))
        }
    }

    @Test
    fun `空值与空白行恒放行`() {
        val roots = RedirectShadowFilter.reachableRoots(onDeviceRules())
        assertFalse(RedirectShadowFilter.isUnreachable(roots, sd, null))
        assertFalse(RedirectShadowFilter.isUnreachable(roots, sd, ""))
        assertFalse(RedirectShadowFilter.isUnreachable(roots, sd, "  "))
    }

    @Test
    fun `SQL 文本与参数顺序一致`() {
        val roots = RedirectShadowFilter.reachableRoots(onDeviceRules())
        val exclusion = RedirectShadowFilter.buildExclusion(roots, sd)
        assertNotNull(exclusion)
        val (selection, args) = exclusion!!

        // `?` 个数必须与 args 数严格相等，否则 SQLite 直接报错（这是最容易错的点）。
        assertEquals("占位符数必须等于参数数",
            selection.count { it == '?' }, args.size)
        // 顺序：根前缀先出现，随后是每个白名单根的「精确 + 后代」两参。
        assertEquals("$sd/%", args[0])
        // 白名单根按规则出现顺序排列，具体先后不重要，但必须成对且覆盖全部三个根。
        val pairs = (1 until args.size step 2).map { args[it] }.toSet()
        assertEquals(
            setOf(selfDir, "$sd/DCIM", "$sd/Pictures"),
            pairs,
        )
        for (i in 1 until args.size step 2) {
            assertEquals("后代参数必须紧随根参数", "${args[i]}/%", args[i + 1].replace("\\_", "_").replace("\\%", "%"))
        }
        assertTrue(selection.startsWith("(_data IS NULL OR NOT ("))
        assertTrue(selection.contains("ESCAPE '\\'"))
    }

    @Test
    fun `SQL 元字符按 ESCAPE 转义`() {
        // 花括号/下划线/百分号出现在白名单路径里时必须转义，否则会变成通配。
        val weird = "$sd/a_b%c"
        val roots = setOf(weird)
        val (_, args) = RedirectShadowFilter.buildExclusion(roots, sd)!!
        assertTrue("下划线与百分号必须被转义", "$sd/a\\_b\\%c/%" in args)
    }

    @Test
    fun `诊断描述不含文件名`() {
        val roots = RedirectShadowFilter.reachableRoots(onDeviceRules())
        val text = RedirectShadowFilter.describe(roots, sd)
        assertTrue(text.contains("reachable=3"))
        assertFalse("不得泄露具体文件名", text.contains(".jpg"))
        assertEquals("disabled", RedirectShadowFilter.describe(emptySet(), sd))
    }
}
