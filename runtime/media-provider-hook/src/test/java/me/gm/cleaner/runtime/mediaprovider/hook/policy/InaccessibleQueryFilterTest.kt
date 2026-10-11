package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InaccessibleQueryFilterTest {

    // ────────────────────────────────────────────────────────────
    // buildExclusion
    // ────────────────────────────────────────────────────────────

    @Test
    fun `空根集合返回 null`() {
        assertNull(InaccessibleQueryFilter.buildExclusion(emptyList()))
        assertNull(InaccessibleQueryFilter.buildExclusion(listOf("", "/", "  ")))
    }

    @Test
    fun `单根生成自身加子孙两个条件`() {
        val (selection, args) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/Pictures"))!!
        assertTrue(selection.startsWith("(_data IS NULL OR NOT ("))
        assertTrue(selection.contains("_data = ?"))
        assertTrue(selection.contains("_data LIKE ? ESCAPE '\\'"))
        assertTrue(selection.endsWith("))"))
        assertEquals(
            listOf("/storage/emulated/0/Pictures", "/storage/emulated/0/Pictures/%"),
            args
        )
    }

    @Test
    fun `多根用 OR 串联且参数数量翻倍`() {
        val (selection, args) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/Pictures", "/storage/emulated/0/DCIM/Camera"))!!
        assertEquals(2, selection.split("_data = ?").size - 1)
        assertEquals(4, args.size)
        assertTrue(selection.contains(" OR "))
    }

    @Test
    fun `百分号与下划线被转义`() {
        // 用户路径里真的可能有 % 或 _；不转义会让 "100%" 匹配到 "100xx"。
        val (_, args) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/100%_a"))!!
        assertEquals("/storage/emulated/0/100%_a", args[0])
        assertEquals("/storage/emulated/0/100\\%\\_a/%", args[1])
    }

    @Test
    fun `反斜杠自身被转义`() {
        val (_, args) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/a\\b"))!!
        assertEquals("/storage/emulated/0/a\\\\b/%", args[1])
    }

    @Test
    fun `重复根去重`() {
        val (_, args) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/Pictures", "/storage/emulated/0/Pictures"))!!
        assertEquals(2, args.size)
    }

    @Test
    fun `结尾斜杠不影响参数`() {
        val (_, args) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/Pictures/"))!!
        assertEquals("/storage/emulated/0/Pictures", args[0])
        assertEquals("/storage/emulated/0/Pictures/%", args[1])
    }

    @Test
    fun `NULL 的 _data 行必须被放行`() {
        // NOT (_data LIKE ...) 对 NULL 求值为 NULL，会把 pending 行误删。
        val (selection, _) = InaccessibleQueryFilter.buildExclusion(
            listOf("/storage/emulated/0/Pictures"))!!
        assertTrue(selection.contains("_data IS NULL"))
    }

    // ────────────────────────────────────────────────────────────
    // combine
    // ────────────────────────────────────────────────────────────

    @Test
    fun `无原 selection 时直接用排除条件`() {
        val exclusion = InaccessibleQueryFilter.buildExclusion(listOf("/p"))!!
        assertEquals(exclusion, InaccessibleQueryFilter.combine(null, null, exclusion))
        assertEquals(exclusion, InaccessibleQueryFilter.combine("   ", null, exclusion))
    }

    @Test
    fun `有原 selection 时按 AND 合并且参数顺序正确`() {
        val exclusion = InaccessibleQueryFilter.buildExclusion(listOf("/p"))!!
        val (selection, args) = InaccessibleQueryFilter.combine(
            "_data LIKE ? AND is_pending = ?",
            listOf("%x%", "0"),
            exclusion,
        )
        assertTrue(selection.startsWith("(_data LIKE ? AND is_pending = ?) AND ("))
        // 原参数在前，排除参数在后 —— 与 ? 出现顺序一致，否则会绑错列。
        assertEquals(listOf("%x%", "0", "/p", "/p/%"), args)
    }

    // ────────────────────────────────────────────────────────────
    // shouldAttempt
    // ────────────────────────────────────────────────────────────

    @Test
    fun `媒体与文件 URI 参与过滤`() {
        assertTrue(InaccessibleQueryFilter.shouldAttempt("/external/images/media"))
        assertTrue(InaccessibleQueryFilter.shouldAttempt("/external/video/media"))
        assertTrue(InaccessibleQueryFilter.shouldAttempt("/external/audio/media"))
        assertTrue(InaccessibleQueryFilter.shouldAttempt("/external/file"))
    }

    @Test
    fun `无 _data 列的 URI 不参与过滤`() {
        // 拼到没有 _data 列的表上必然报错，必须按形态先筛掉。
        assertFalse(InaccessibleQueryFilter.shouldAttempt("/external/volume"))
        assertFalse(InaccessibleQueryFilter.shouldAttempt("/external/album"))
    }

    @Test
    fun `空与空白路径不参与过滤`() {
        assertFalse(InaccessibleQueryFilter.shouldAttempt(null))
        assertFalse(InaccessibleQueryFilter.shouldAttempt(""))
        assertFalse(InaccessibleQueryFilter.shouldAttempt("   "))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(InaccessibleQueryFilter.shouldAttempt("/external/Images/Media"))
    }

    @Test
    fun `原 selection 有参数但列表为 null 时不丢位`() {
        val exclusion = InaccessibleQueryFilter.buildExclusion(listOf("/p"))!!
        val (_, args) = InaccessibleQueryFilter.combine("_data = ?", null, exclusion)
        assertEquals(listOf("/p", "/p/%"), args)
    }

    // ────────────────────────────────────────────────────────────
    // isHidden —— SQL 下推与结果集兜底共用的唯一判据
    // ────────────────────────────────────────────────────────────

    private val roots21 = listOf(
        "/storage/emulated/0/DCIM",
        "/storage/emulated/0/Pictures",
    )

    @Test
    fun isHidden命中根自身与任意深度子孙() {
        assertTrue(InaccessibleQueryFilter.isHidden(
            roots21, "/storage/emulated/0/DCIM"))
        assertTrue(InaccessibleQueryFilter.isHidden(
            roots21, "/storage/emulated/0/DCIM/Camera/a.jpg"))
        // 第 21 批实机被拒的真值：多级子孙必须命中
        assertTrue(InaccessibleQueryFilter.isHidden(
            roots21, "/storage/emulated/0/DCIM/MyAlbums/走访二改/IMG20250422093532.jpg"))
        assertTrue(InaccessibleQueryFilter.isHidden(
            roots21, "/storage/emulated/0/Pictures/AyuGram/x/y/z.jpg"))
    }

    @Test
    fun isHidden不误伤同前缀与无关目录() {
        assertFalse(InaccessibleQueryFilter.isHidden(roots21, "/storage/emulated/0/DCIMX"))
        assertFalse(InaccessibleQueryFilter.isHidden(roots21, "/storage/emulated/0/PicturesX"))
        assertFalse(InaccessibleQueryFilter.isHidden(roots21, "/storage/emulated/0/Download"))
        assertFalse(InaccessibleQueryFilter.isHidden(roots21, "/storage/emulated/0/Music/a.mp3"))
    }

    @Test
    fun isHidden对NULL与空白恒为false() {
        // _data 为 NULL 的行（pending / 云端条目）与文件路径无关，不能被误删。
        assertFalse(InaccessibleQueryFilter.isHidden(roots21, null))
        assertFalse(InaccessibleQueryFilter.isHidden(roots21, ""))
        assertFalse(InaccessibleQueryFilter.isHidden(roots21, "   "))
    }

    @Test
    fun isHidden空规则集合恒为false() {
        assertFalse(InaccessibleQueryFilter.isHidden(
            emptyList(), "/storage/emulated/0/DCIM/a.jpg"))
    }

    @Test
    fun isHidden大小写不敏感() {
        // FUSE / MediaProvider 给回的路径大小写未必与用户手填一致
        assertTrue(InaccessibleQueryFilter.isHidden(
            roots21, "/storage/emulated/0/dcim/Camera/a.jpg"))
        assertTrue(InaccessibleQueryFilter.isHidden(
            listOf("/storage/emulated/0/dcim"), "/storage/emulated/0/DCIM/a.jpg"))
    }

    @Test
    fun `selection键首选本ROM键并兼容AOSP键`() {
        // 二进制实证：com.android.providers.media.module 的 classes.dex 与 framework.jar 里
        // "android:query-selection" 均出现 0 次，本 ROM 只认 "android:query-arg-sql-selection"。
        // 只写 AOSP 键 ⇒ 下推从未生效（第 18~21 批）。顺序不可交换：首选的必须是本机键。
        assertEquals(
            listOf("android:query-arg-sql-selection", "android:query-selection"),
            InaccessibleQueryFilter.selectionKeys.toList(),
        )
        assertEquals(
            listOf("android:query-arg-sql-selection-args", "android:query-selection-args"),
            InaccessibleQueryFilter.selectionArgsKeys.toList(),
        )
    }

    @Test
    fun `selection键与参数键一一对应`() {
        assertEquals(
            InaccessibleQueryFilter.selectionKeys.size,
            InaccessibleQueryFilter.selectionArgsKeys.size,
        )
    }
}
