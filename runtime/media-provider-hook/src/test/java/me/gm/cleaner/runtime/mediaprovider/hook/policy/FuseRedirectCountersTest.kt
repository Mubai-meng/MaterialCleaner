package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FuseRedirectCounters] 的语义契约，重点是「同路径短路」不改变任何可观测结果。
 *
 * 背景：该类的 `record()` 跑在 FUSE 热路径上（每个未命中路径一次）。短路优化
 * 只是把「重算前缀」换成「内容比较」，因此必须证明：
 * 1. 计数（evaluated / redirected / preserved / missed）逐次累加，不受短路影响；
 * 2. `lastMissPrefix` 的最终取值与「每次都重算」完全一致；
 * 3. 命中路径不污染 `lastMiss*`（它只描述未命中）；
 * 4. **恒等 carve-out（PRESERVED）不得计入 `missed`** —— 否则配了 PRESERVE
 *    规则的包命中率恒为 0%（2026-11-02 实测相册 654 次全被判成未命中）。
 */
class FuseRedirectCountersTest {

    private fun snapshot(counters: FuseRedirectCounters): JSONObject =
        JSONObject().also { counters.appendRedirectTo(it) }

    @Test
    fun `counters accumulate on every evaluation including repeats`() {
        val counters = FuseRedirectCounters()
        val path = "/storage/emulated/0/Download/a.apk"
        repeat(5) { counters.record("com.quark", path, FuseRedirectStats.Outcome.MISS) }
        counters.record("com.quark", path, FuseRedirectStats.Outcome.REDIRECTED)

        val json = snapshot(counters)
        assertEquals(6L, json.getLong("evaluated"))
        assertEquals(1L, json.getLong("redirected"))
        assertEquals(5L, json.getLong("missed"))
    }

    @Test
    fun `repeated identical path keeps the same prefix`() {
        val counters = FuseRedirectCounters()
        val path = "/storage/emulated/0/Android/media/a.mp4"
        repeat(20) { counters.record("com.quark", path, FuseRedirectStats.Outcome.MISS) }

        val json = snapshot(counters)
        // 与「每次都重算」的结果必须一致：目录前缀（去掉文件名、最多 5 段）
        assertEquals("/storage/emulated/0/Android/media", json.getString("lastMissPrefix"))
        assertEquals("com.quark", json.getString("lastMissPackage"))
    }

    @Test
    fun `different path with same prefix keeps first observation`() {
        val counters = FuseRedirectCounters()
        counters.record("com.quark", "/storage/emulated/0/Download/a.apk", FuseRedirectStats.Outcome.MISS)
        counters.record("com.quark", "/storage/emulated/0/Download/b.apk", FuseRedirectStats.Outcome.MISS)

        val json = snapshot(counters)
        assertEquals("/storage/emulated/0/Download", json.getString("lastMissPrefix"))
        assertEquals(2L, json.getLong("missed"))
    }

    @Test
    fun `different prefix updates the observation`() {
        val counters = FuseRedirectCounters()
        counters.record("com.quark", "/storage/emulated/0/Download/a.apk", FuseRedirectStats.Outcome.MISS)
        counters.record("com.quark", "/storage/emulated/0/Android/media/b.mp4", FuseRedirectStats.Outcome.MISS)

        val json = snapshot(counters)
        assertEquals("/storage/emulated/0/Android/media", json.getString("lastMissPrefix"))
    }

    @Test
    fun `redirected evaluations do not touch last miss fields`() {
        val counters = FuseRedirectCounters()
        counters.record("com.quark", "/storage/emulated/0/Download/a.apk", FuseRedirectStats.Outcome.REDIRECTED)

        val json = snapshot(counters)
        assertEquals("", json.getString("lastMissPrefix"))
        assertEquals("", json.getString("lastMissPackage"))
        assertEquals(1L, json.getLong("evaluated"))
        assertEquals(0L, json.getLong("missed"))
    }

    @Test
    fun `empty path still records package on first miss`() {
        // 短路必须显式排除空路径：否则「首个空 path 记 lastMissPackage」的行为改变。
        val counters = FuseRedirectCounters()
        counters.record("com.quark", "", FuseRedirectStats.Outcome.MISS)

        val json = snapshot(counters)
        assertEquals("com.quark", json.getString("lastMissPackage"))
        assertEquals("", json.getString("lastMissPrefix"))
        assertEquals(1L, json.getLong("missed"))
    }

    @Test
    fun `dedup counter is independent from redirect counters`() {
        val counters = FuseRedirectCounters()
        counters.setDedupedCount(42L)
        counters.record("com.quark", "/storage/emulated/0/x/y.bin", FuseRedirectStats.Outcome.MISS)

        assertEquals(42L, counters.dedupedEvents)
        assertEquals(1L, snapshot(counters).getLong("evaluated"))
    }

    @Test
    fun `preserved evaluations are counted separately from misses`() {
        val counters = FuseRedirectCounters()
        val path = "/storage/emulated/0/Pictures/a.jpg"
        repeat(3) {
            counters.record("com.coloros.gallery3d", path, FuseRedirectStats.Outcome.PRESERVED)
        }
        counters.record(
            "com.coloros.gallery3d",
            "/storage/emulated/0/Music/a.mp3",
            FuseRedirectStats.Outcome.MISS,
        )

        val json = snapshot(counters)
        assertEquals(4L, json.getLong("evaluated"))
        assertEquals(0L, json.getLong("redirected"))
        assertEquals(3L, json.getLong("preserved"))
        assertEquals(1L, json.getLong("missed"))
        // 恒等命中不得污染「未命中」观测：否则 lastMissPrefix 会把规则**正确生效**
        // 的目录报成"没匹配到"。
        assertEquals("/storage/emulated/0/Music", json.getString("lastMissPrefix"))
        assertEquals("/storage/emulated/0/Pictures", json.getString("lastPreservePrefix"))
        assertEquals("com.coloros.gallery3d", json.getString("lastPreservePackage"))
    }

    @Test
    fun `preserve only workload never reports a miss`() {
        // 真机形态：相册 654 次评估全部落在 Pictures/DCIM/Download 的恒等 carve-out 上。
        val counters = FuseRedirectCounters()
        repeat(654) {
            counters.record(
                "com.coloros.gallery3d",
                "/storage/emulated/0/Pictures/p.jpg",
                FuseRedirectStats.Outcome.PRESERVED,
            )
        }

        val json = snapshot(counters)
        assertEquals(654L, json.getLong("evaluated"))
        assertEquals(654L, json.getLong("preserved"))
        assertEquals(0L, json.getLong("redirected"))
        assertEquals("PRESERVE 规则正确生效时 missed 必须为 0", 0L, json.getLong("missed"))
        assertEquals("", json.getString("lastMissPrefix"))
        assertEquals("", json.getString("lastMissPackage"))
    }
}
