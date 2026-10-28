package me.gm.cleaner.runtime.server.vfs.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可回收目录集合的展开与过滤（第 51 批，6.0.2）。
 *
 * 真机症状（PLK110 / 6.0.1）：相册规则的目标 `/storage/emulated/0/oua_classifier`
 * 在相册关闭后会从存储里消失，下次打开又出现。根因不在挂载，而在**回收侧**把
 * 「为了建目录而展开的父级」和「本模块创建的目录」混成了同一个集合：
 * `bindMountLocked` 用 `record()` 递归展开父级，于是 `/storage/emulated/0`、
 * `Android`、`Android/data` 都进了 mkdirRecords；而另两条路径
 * （`checkAndRecordLocked` / `remountLocked`）直接 `putAll(plan.mkdirList)`，
 * 只含挂载点与承载源。同一个包走不同路径 ⇒ 回收行为不同。
 *
 * 本类钉死统一后的三条不变式：只接受严格子路径、递归展开父级、结构目录永不回收。
 */
class MountDirRecordsTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `卷根永不出现在可回收集合里`() {
        // 相册规则：source=.../gallery3d/cache, target=/storage/emulated/0/oua_classifier
        val result = MountDirRecords.expandForReclaim(
            root,
            listOf(
                "/storage/emulated/0/oua_classifier",
                "/storage/emulated/0/Android/data/com.coloros.gallery3d/cache",
            ),
        )
        assertFalse("卷根归 vold 管，本模块不建不删", result.contains(root))
    }

    @Test
    fun `结构目录被滤掉但挂载点与承载源保留`() {
        val result = MountDirRecords.expandForReclaim(
            root,
            listOf(
                "/storage/emulated/0/oua_classifier",
                "/storage/emulated/0/Android/data/com.coloros.gallery3d/cache",
            ),
        )
        assertTrue("目标目录是本模块建的，必须可回收", result.contains("/storage/emulated/0/oua_classifier"))
        assertTrue(
            "承载源是本模块建的，必须可回收",
            result.contains("/storage/emulated/0/Android/data/com.coloros.gallery3d/cache"),
        )
        assertFalse(result.contains("/storage/emulated/0/Android"))
        assertFalse(result.contains("/storage/emulated/0/Android/data"))
        assertFalse(result.contains("/storage/emulated/0/Android/obb"))
        assertFalse(result.contains("/storage/emulated/0/Android/media"))
        assertFalse(result.contains("/storage/emulated/0/Android/sandbox"))
    }

    @Test
    fun `父级展开会带上可回收的中间层`() {
        // DCIM/Camera 的父级 DCIM 不是结构目录 ⇒ 应保留（它可能正是本模块建的）。
        val result = MountDirRecords.expandForReclaim(
            root,
            listOf("/storage/emulated/0/DCIM/Camera"),
        )
        assertTrue(result.contains("/storage/emulated/0/DCIM/Camera"))
        assertTrue(result.contains("/storage/emulated/0/DCIM"))
        assertFalse(result.contains(root))
    }

    @Test
    fun `名字前缀相同不算子路径`() {
        // /storage/emulated/0/Android2 不是结构目录，必须可回收 —— 否则它建了删不掉。
        val result = MountDirRecords.expandForReclaim(
            root,
            listOf("/storage/emulated/0/Android2"),
        )
        assertTrue(result.contains("/storage/emulated/0/Android2"))
        assertFalse(result.contains("/storage/emulated/0/Android"))
    }

    @Test
    fun `空根 fail-closed 返回空集`() {
        // 拿不到卷根时宁可什么都不回收：rmdir 的后果不可逆。
        assertEquals(
            emptySet<String>(),
            MountDirRecords.expandForReclaim("", listOf("/storage/emulated/0/oua_classifier")),
        )
    }

    @Test
    fun `卷根自身与外人路径不进集合`() {
        val result = MountDirRecords.expandForReclaim(
            root,
            listOf(root, "/data/local/tmp/cleaner_logs", "/storage/emulated/0/DCIM"),
        )
        assertFalse(result.contains(root))
        assertFalse("/data 下的路径不属于用户存储，绝不能被本模块回收", result.contains("/data/local/tmp/cleaner_logs"))
        assertTrue(result.contains("/storage/emulated/0/DCIM"))
    }

    @Test
    fun `isStrictChild 按路径段比较`() {
        assertTrue(MountDirRecords.isStrictChild("/a/b", "/a/b/c"))
        assertFalse(MountDirRecords.isStrictChild("/a/b", "/a/bc"))
        assertFalse(MountDirRecords.isStrictChild("/a/b", "/a/b"))
        assertTrue(MountDirRecords.isStrictChild("/a/b/", "/a/b/c"))
    }
}
