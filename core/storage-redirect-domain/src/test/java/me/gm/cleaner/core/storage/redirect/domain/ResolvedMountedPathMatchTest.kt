package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MountPlanDeriver.resolve] 的 `matched` 位契约。
 *
 * 为什么需要它：`derivedPath == path` 有两种**成因相反**的情形 ——
 * 命中恒等 carve-out（PRESERVE，规则正确生效）与一条规则都没匹配上。
 * FUSE 重定向命中率完全由这一位决定，而此前 `resolveMountedPath` 只回派生路径，
 * 调用方只能把两者混记成"未命中"（2026-11-02 实测相册 654/654 全判 miss）。
 */
class ResolvedMountedPathMatchTest {

    private val root = "/storage/emulated/0"

    /** 复刻真机 com.coloros.gallery3d 的 5 条规则（2026-11-02 诊断包 B）。 */
    private val galleryRules = listOf(
        RedirectRule("$root/Android/data/com.coloros.gallery3d/sdcard", root),
        RedirectRule(
            "$root/Android/data/com.coloros.gallery3d",
            "$root/Android/data/com.coloros.gallery3d",
        ),
        RedirectRule("$root/DCIM", "$root/DCIM"),
        RedirectRule("$root/Pictures", "$root/Pictures"),
        RedirectRule("$root/Download", "$root/Download"),
    )

    @Test
    fun `恒等 carve-out 命中时报 matched 且派生路径不变`() {
        val resolved = MountPlanDeriver.resolve(galleryRules, "$root/Pictures/a.jpg")
        assertEquals("$root/Pictures/a.jpg", resolved.derivedPath)
        assertTrue("Pictures→Pictures 是 PRESERVE 命中，不是未命中", resolved.matched)
    }

    @Test
    fun `MAP 规则命中时报 matched 且派生路径被改写`() {
        val resolved = MountPlanDeriver.resolve(galleryRules, "$root/Music/a.mp3")
        assertEquals(
            "$root/Android/data/com.coloros.gallery3d/sdcard/Music/a.mp3",
            resolved.derivedPath,
        )
        assertTrue(resolved.matched)
    }

    @Test
    fun `一条规则都没匹配上时报 matched=false`() {
        val rules = listOf(
            RedirectRule("$root/Download/Browser", "$root/Download/QuarkCloudDrive"),
        )
        val resolved = MountPlanDeriver.resolve(rules, "$root/Pictures/a.jpg")
        assertEquals("$root/Pictures/a.jpg", resolved.derivedPath)
        assertFalse(resolved.matched)
    }

    @Test
    fun `空规则与非规范路径一律 matched=false`() {
        assertFalse(MountPlanDeriver.resolve(emptyList(), "$root/Pictures/a.jpg").matched)
        val dirty = MountPlanDeriver.resolve(galleryRules, "relative/path")
        assertFalse(dirty.matched)
        assertEquals("relative/path", dirty.derivedPath)
    }

    @Test
    fun `resolveMountedPath 与 resolve 的派生路径逐字一致`() {
        listOf(
            "$root/Pictures/a.jpg",
            "$root/DCIM/a.jpg",
            "$root/Download/a.apk",
            "$root/Music/a.mp3",
            "$root/Android/data/com.coloros.gallery3d/sdcard/x.bin",
        ).forEach { path ->
            assertEquals(
                "resolveMountedPath 必须保持既有语义：$path",
                MountPlanDeriver.resolveMountedPath(galleryRules, path),
                MountPlanDeriver.resolve(galleryRules, path).derivedPath,
            )
        }
    }
}
