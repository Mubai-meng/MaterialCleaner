package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「整盘重定向下仍可达子树」的护栏。
 *
 * 规则取自 2026-10-28 第 55 批真机 `redirect_policy.json` 快照（相册包）。
 * 该配置的效果是：进程内 `/storage/emulated/0` 被换成私有 sdcard，只有
 * `DCIM`、`Pictures` 被恒等 carve-out 救回 —— 实机表现就是"有的图片显示、有的空白"。
 */
class MediaReachabilityTest {

    private val pkg = "com.coloros.gallery3d"
    private val selfDir = "/storage/emulated/0/Android/data/$pkg"
    private val sd = "/storage/emulated/0"

    /** 真机快照里的原样规则（含被自包含判定剔除的第 1 条）。 */
    private fun onDeviceRules() = listOf(
        RedirectRule(source = "$selfDir/sdcard", target = sd),
        RedirectRule(source = selfDir, target = selfDir),
        RedirectRule(source = "$sd/DCIM", target = "$sd/DCIM"),
        RedirectRule(source = "$sd/Pictures", target = "$sd/Pictures"),
    )

    /**
     * 真机上除 `DCIM`、`Pictures` 外，`Android/data/<pkg>` 自己也是恒等 carve-out ——
     * 它本来就落在相册自己的沙箱里，解释结果当然恒等，所以它**必须**留在白名单内。
     * 漏掉它会误删本包自己目录下的媒体行（那些行是真的能显示）。
     */
    @Test
    fun `真机相册规则救回 DCIM Pictures 与自身数据目录`() {
        val roots = MediaReachability.deriveReachableRoots(onDeviceRules())
        assertEquals(setOf(selfDir, "$sd/DCIM", "$sd/Pictures"), roots)
    }

    @Test
    fun `可达集合与解释器恒等性逐条对齐`() {
        val rules = onDeviceRules()
        val roots = MediaReachability.deriveReachableRoots(rules)
        // 正例：在集合内的子树，解释结果必须是它自己。
        for (root in roots) {
            assertEquals(root + "/a.jpg", MountPlanDeriver.resolveMountedPath(rules, "$root/a.jpg"))
        }
        // 反例：不在集合内的共享存储目录，解释后必须落到私有沙箱。
        for (path in listOf("$sd/Documents/x/a.jpg", "$sd/Download/a.jpg", "$sd/Movies/a.mp4")) {
            assertTrue(
                "应被改写到私有 sdcard：$path",
                MountPlanDeriver.resolveMountedPath(rules, path).startsWith("$selfDir/sdcard/"),
            )
        }
    }

    @Test
    fun `后置规则吃掉 carve-out 时该子树自动失格`() {
        // Pictures 先被 carve-out 救回，又被最后一条改写到别处 ⇒ 最终不可达。
        // 判定靠解释器复算，不是"有没有出现过恒等规则"——所以它必须自动掉出白名单。
        val rules = onDeviceRules() + RedirectRule(
            source = "$selfDir/sdcard/PicturesBackup",
            target = "$sd/Pictures",
        )
        val roots = MediaReachability.deriveReachableRoots(rules)
        assertFalse("$sd/Pictures 已被后置规则改写，不得留在白名单", "$sd/Pictures" in roots)
        assertTrue("$sd/DCIM 不受影响", "$sd/DCIM" in roots)
    }

    @Test
    fun `无整盘重定向时返回空集合表示不施加限制`() {
        // 普通单目录重定向：绝不能返回"空 ⇒ 全部禁止"。
        val rules = listOf(RedirectRule("$sd/Download/Browser", "$sd/Download/QuarkCloudDrive"))
        assertTrue(MediaReachability.deriveReachableRoots(rules).isEmpty())
        assertNull(MediaReachability.findWholeRootTarget(rules))

        assertTrue(MediaReachability.deriveReachableRoots(emptyList()).isEmpty())
    }

    @Test
    fun `整盘重定向但无任何 carve-out 时返回空集合`() {
        // 此时进程内整个共享存储都是私有目录，配置本身自相矛盾（见 selfContained 文档），
        // 本函数无法给出白名单；返回空集合即"不限制"，把判断权交回调用方。
        val rules = listOf(RedirectRule("$selfDir/sdcard", sd))
        assertTrue(MediaReachability.deriveReachableRoots(rules).isEmpty())
        assertEquals(sd, MediaReachability.findWholeRootTarget(rules))
    }

    @Test
    fun `共享存储根识别与段边界`() {
        assertTrue(MediaReachability.isSharedStorageRoot("/storage/emulated/0"))
        assertTrue(MediaReachability.isSharedStorageRoot("/storage/emulated/999"))
        assertTrue(MediaReachability.isSharedStorageRoot("/sdcard"))
        // 非根 / 非共享盘不得当作整盘重定向目标
        assertFalse(MediaReachability.isSharedStorageRoot("/storage/emulated/0/DCIM"))
        assertFalse(MediaReachability.isSharedStorageRoot("/storage/emulated/0abc"))
        assertFalse(MediaReachability.isSharedStorageRoot("/storage/self/primary"))

        // 段边界：/a/bc 不是 /a/b 的后代
        assertTrue(MediaReachability.isStrictDescendant("$sd/DCIM", sd))
        assertFalse(MediaReachability.isStrictDescendant(sd, sd))
        assertFalse(MediaReachability.isStrictDescendant("$sd/DCIMX", "$sd/DCIM"))
    }
}
