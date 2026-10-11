package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaBucketCensus] 的行为契约：分桶名提取、降序截断、并列稳定、小相册挑选、
 * 以及空/负/零值等边界。全部是纯函数，无 Android 依赖。
 */
class MediaBucketCensusTest {

    @Test
    fun bucketOf_takesLastSegment() {
        assertEquals("assets", MediaBucketCensus.bucketOf("/storage/emulated/0/assets"))
        assertEquals("Camera", MediaBucketCensus.bucketOf("/storage/emulated/0/DCIM/Camera"))
    }

    @Test
    fun bucketOf_toleratesTrailingSlashAndRoot() {
        assertEquals("assets", MediaBucketCensus.bucketOf("/storage/emulated/0/assets/"))
        assertEquals("", MediaBucketCensus.bucketOf("/"))
    }

    @Test
    fun bucketOf_blankIsEmpty() {
        assertEquals("", MediaBucketCensus.bucketOf(null))
        assertEquals("", MediaBucketCensus.bucketOf(""))
        assertEquals("", MediaBucketCensus.bucketOf("   "))
    }

    @Test
    fun topNames_descendingByCount() {
        val counts = mapOf(
            "/sdcard/DCIM/Camera" to 87,
            "/sdcard/assets" to 6,
            "/sdcard/Pictures/Screenshots" to 12,
        )
        assertEquals(
            listOf("Camera" to 87, "Screenshots" to 12, "assets" to 6),
            MediaBucketCensus.topNames(counts, 3),
        )
    }

    @Test
    fun topNames_mergesSameNameAcrossDirs() {
        // 同一分桶名可能来自不同父目录（多 volume / 多用户），张数要合并。
        val counts = mapOf(
            "/sdcard/assets" to 4,
            "/storage/1234-5678/assets" to 2,
            "/sdcard/DCIM/Camera" to 87,
        )
        assertEquals(
            listOf("Camera" to 87, "assets" to 6),
            MediaBucketCensus.topNames(counts, 3),
        )
    }

    @Test
    fun topNames_tiesAreStableByName() {
        val counts = mapOf("/a/zzz" to 5, "/a/aaa" to 5, "/a/mmm" to 5)
        assertEquals(
            listOf("aaa" to 5, "mmm" to 5, "zzz" to 5),
            MediaBucketCensus.topNames(counts, 3),
        )
    }

    @Test
    fun topNames_respectsLimit() {
        val counts = mapOf("/a" to 3, "/b" to 2, "/c" to 1)
        assertEquals(listOf("a" to 3), MediaBucketCensus.topNames(counts, 1))
        assertTrue(MediaBucketCensus.topNames(counts, 0).isEmpty())
        assertTrue(MediaBucketCensus.topNames(counts, -1).isEmpty())
    }

    @Test
    fun topNames_skipsBlankAndNonPositive() {
        val counts = mapOf("" to 9, "   " to 9, "/real" to 0, "/ok" to 2)
        assertEquals(listOf("ok" to 2), MediaBucketCensus.topNames(counts, 5))
    }

    @Test
    fun smallBuckets_returnsFullPathOfTinyAlbumsOnly() {
        val counts = mapOf(
            "/storage/emulated/0/Android/data/com.unistrong.hnmpm.fj/cache/assets" to 6,
            "/sdcard/DCIM/Camera" to 87,
            "/sdcard/Pictures/tmp" to 1,
        )
        assertEquals(
            listOf(
                "/sdcard/Pictures/tmp",
                "/storage/emulated/0/Android/data/com.unistrong.hnmpm.fj/cache/assets",
            ),
            MediaBucketCensus.smallBuckets(counts, 3, 10),
        )
    }

    @Test
    fun smallBuckets_neverLeaksBigAlbumPath() {
        val counts = mapOf("/sdcard/DCIM/Camera" to 11, "/sdcard/assets" to 10)
        assertEquals(listOf("/sdcard/assets"), MediaBucketCensus.smallBuckets(counts, 3, 10))
    }

    @Test
    fun smallBuckets_emptyOnBlankInput() {
        assertTrue(MediaBucketCensus.smallBuckets(emptyMap(), 3, 10).isEmpty())
        assertTrue(MediaBucketCensus.smallBuckets(mapOf("" to 1), 3, 10).isEmpty())
    }

    @Test
    fun formatTop_isLogReady() {
        val counts = mapOf("/sdcard/assets" to 6, "/sdcard/DCIM/Camera" to 87)
        assertEquals(listOf("Camera=87", "assets=6"), MediaBucketCensus.formatTop(counts, 3))
    }

    @Test
    fun threshold_matchesSmallAlbumDefinition() {
        // assets(6) 这类"多出来的相册"必须落在小相册里，否则拿不到完整路径
        // （第 26 批实证：它其实是 Documents 下的真实目录，不是幽灵行）。
        assertTrue(6 <= MediaBucketCensus.SMALL_BUCKET_MAX)
    }
}
