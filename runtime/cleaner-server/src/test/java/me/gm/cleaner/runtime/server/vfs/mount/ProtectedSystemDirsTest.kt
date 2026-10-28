package me.gm.cleaner.runtime.server.vfs.mount

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构目录保护：`mkdirList` 的父级展开会把 Android / Android/data 带进回收集合，
 * 而 rmdir 对空目录会成功 —— 保护判定必须逐项钉死。
 */
class ProtectedSystemDirsTest {
    private val root = "/storage/emulated/0"

    @Test
    fun `结构目录受保护`() {
        assertTrue(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/Android"))
        assertTrue(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/Android/data"))
        assertTrue(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/Android/obb"))
        assertTrue(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/Android/media"))
        assertTrue(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/Android/sandbox"))
    }

    @Test
    fun `卷根本身受保护`() {
        assertTrue(ProtectedSystemDirs.isProtected(root, root))
    }

    @Test
    fun `挂载点与承载源目录可回收`() {
        assertFalse(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/oua_classifier"))
        assertFalse(
            ProtectedSystemDirs.isProtected(
                root,
                "/storage/emulated/0/Android/data/com.coloros.gallery3d/cache",
            )
        )
        assertFalse(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/DCIM/Camera"))
    }

    @Test
    fun `名字前缀相同不算结构目录`() {
        // 只做整段相等，避免把 "Android2" / "Android/data2" 误保护。
        assertFalse(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/Android2"))
        assertFalse(ProtectedSystemDirs.isProtected(root, "/storage/emulated/0/Android/data2"))
    }

    @Test
    fun `根路径为空时一律保护`() {
        assertTrue(ProtectedSystemDirs.isProtected("", "/storage/emulated/0/oua_classifier"))
    }
}
