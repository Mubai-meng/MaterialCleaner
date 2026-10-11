package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用专属存储（Android/data|media/<pkg>/）里的媒体行判定。
 *
 * ⚠️ 实机更正（第 26 批）：当初加它是为了 assets(6) 坏图相册，但实测该判据
 * 命中数恒为 0 —— assets(6) 实为 Documents 下的真实目录、只是不在用户配置的
 * Pictures/DCIM 规则内。本测试覆盖的是**判据本身的正确性**（段包含、跨包不误判、
 * 空值放行），与 assets(6) 的成因无关；该能力现为防御性保留。
 */
class AppSpecificMediaFilterTest {

    private val pkg = "com.unistrong.hnmpm.fj"

    @Test
    fun `本包cache下的assets命中`() {
        assertTrue(
            AppSpecificMediaFilter.isInside(
                "/storage/emulated/0/Android/data/$pkg/cache/assets/a.jpg", pkg)
        )
    }

    @Test
    fun `本包files与media目录命中`() {
        assertTrue(
            AppSpecificMediaFilter.isInside("/sdcard/Android/data/$pkg/files/x.png", pkg))
        assertTrue(
            AppSpecificMediaFilter.isInside("/storage/emulated/0/Android/media/$pkg/y.jpg", pkg))
    }

    @Test
    fun `共享存储路径不命中`() {
        assertFalse(AppSpecificMediaFilter.isInside("/storage/emulated/0/DCIM/Camera/a.jpg", pkg))
        assertFalse(AppSpecificMediaFilter.isInside("/storage/emulated/0/Pictures/assets/b.jpg", pkg))
    }

    @Test
    fun `其它包的专属目录不命中`() {
        assertFalse(
            AppSpecificMediaFilter.isInside(
                "/storage/emulated/0/Android/data/com.larus.nova/cache/assets/a.jpg", pkg))
    }

    @Test
    fun `包名前缀相同但不是同一段不误判`() {
        // com.unistrong.hnmpm.fjX 是另一个包，不能因为前缀相同被命中
        assertFalse(
            AppSpecificMediaFilter.isInside(
                "/storage/emulated/0/Android/data/${pkg}X/cache/a.jpg", pkg))
    }

    @Test
    fun `空值与空包名不命中`() {
        assertFalse(AppSpecificMediaFilter.isInside(null, pkg))
        assertFalse(AppSpecificMediaFilter.isInside("", pkg))
        assertFalse(AppSpecificMediaFilter.isInside("/storage/emulated/0/Android/data/$pkg/a", ""))
    }

    @Test
    fun `排除条件覆盖data与media两段`() {
        val (selection, args) = AppSpecificMediaFilter.buildExclusion(pkg)
        assertTrue(selection.contains("_data LIKE ? ESCAPE"))
        assertEquals(
            listOf("%/Android/data/$pkg/%", "%/Android/media/$pkg/%"),
            args,
        )
    }

    @Test
    fun `buildExclusion把应用专属条件并入同一NOT组`() {
        val (selection, args) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/Pictures"), pkg)!!
        assertTrue(selection.startsWith("(_data IS NULL OR NOT ("))
        // 1 个不可访问根（2 参）+ 应用专属（2 参）
        assertEquals(4, args.size)
        assertTrue(args.contains("%/Android/data/$pkg/%"))
        assertTrue(args.contains("/storage/emulated/0/Pictures"))
    }

    @Test
    fun `isHidden对应用专属行同样成立且空值放行`() {
        assertTrue(
            InaccessibleQueryFilter.isHidden(
                listOf("/storage/emulated/0/Pictures"),
                "/storage/emulated/0/Android/data/$pkg/cache/assets/a.jpg",
                pkg,
            )
        )
        // _data 为 NULL 的行（pending / 云端条目）不能被路径规则误删
        assertFalse(InaccessibleQueryFilter.isHidden(listOf("/storage/emulated/0/Pictures"), null, pkg))
        // 未配置包的维度为空时，共享路径不受影响
        assertFalse(
            InaccessibleQueryFilter.isHidden(
                listOf("/storage/emulated/0/Pictures"),
                "/storage/emulated/0/DCIM/Camera/c.jpg",
                null,
            )
        )
    }

    @Test
    fun `parentDir只取目录不落文件名`() {
        assertEquals(
            "/storage/emulated/0/Android/data/$pkg/cache/assets",
            AppSpecificMediaFilter.parentDir(
                "/storage/emulated/0/Android/data/$pkg/cache/assets/a.jpg"),
        )
        assertEquals("", AppSpecificMediaFilter.parentDir(""))
        assertEquals("", AppSpecificMediaFilter.parentDir("/"))
    }
}
