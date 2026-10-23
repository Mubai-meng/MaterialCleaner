package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FuseRedirectCounters] 的语义契约，重点是「同路径短路」不改变任何可观测结果。
 *
 * 背景：该类的 `record()` 跑在 FUSE 热路径上（每个未命中路径一次）。短路优化
 * 只是把「重算前缀」换成「内容比较」，因此必须证明：
 * 1. 计数（evaluated / redirected / missed）逐次累加，不受短路影响；
 * 2. `lastMissPrefix` 的最终取值与「每次都重算」完全一致；
 * 3. 命中路径不污染 `lastMiss*`（它只描述未命中）。
 */
class FuseRedirectCountersTest {

    private fun snapshot(counters: FuseRedirectCounters): JSONObject =
        JSONObject().also { counters.appendRedirectTo(it) }

    @Test
    fun `counters accumulate on every evaluation including repeats`() {
        val counters = FuseRedirectCounters()
        val path = "/storage/emulated/0/Download/a.apk"
        repeat(5) { counters.record("com.quark", path, isRedirected = false) }
        counters.record("com.quark", path, isRedirected = true)

        val json = snapshot(counters)
        assertEquals(6L, json.getLong("evaluated"))
        assertEquals(1L, json.getLong("redirected"))
        assertEquals(5L, json.getLong("missed"))
    }

    @Test
    fun `repeated identical path keeps the same prefix`() {
        val counters = FuseRedirectCounters()
        val path = "/storage/emulated/0/Android/media/a.mp4"
        repeat(20) { counters.record("com.quark", path, isRedirected = false) }

        val json = snapshot(counters)
        // 与「每次都重算」的结果必须一致：目录前缀（去掉文件名、最多 5 段）
        assertEquals("/storage/emulated/0/Android/media", json.getString("lastMissPrefix"))
        assertEquals("com.quark", json.getString("lastMissPackage"))
    }

    @Test
    fun `different path with same prefix keeps first observation`() {
        val counters = FuseRedirectCounters()
        counters.record("com.quark", "/storage/emulated/0/Download/a.apk", isRedirected = false)
        counters.record("com.quark", "/storage/emulated/0/Download/b.apk", isRedirected = false)

        val json = snapshot(counters)
        assertEquals("/storage/emulated/0/Download", json.getString("lastMissPrefix"))
        assertEquals(2L, json.getLong("missed"))
    }

    @Test
    fun `different prefix updates the observation`() {
        val counters = FuseRedirectCounters()
        counters.record("com.quark", "/storage/emulated/0/Download/a.apk", isRedirected = false)
        counters.record("com.quark", "/storage/emulated/0/Android/media/b.mp4", isRedirected = false)

        val json = snapshot(counters)
        assertEquals("/storage/emulated/0/Android/media", json.getString("lastMissPrefix"))
    }

    @Test
    fun `redirected evaluations do not touch last miss fields`() {
        val counters = FuseRedirectCounters()
        counters.record("com.quark", "/storage/emulated/0/Download/a.apk", isRedirected = true)

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
        counters.record("com.quark", "", isRedirected = false)

        val json = snapshot(counters)
        assertEquals("com.quark", json.getString("lastMissPackage"))
        assertEquals("", json.getString("lastMissPrefix"))
        assertEquals(1L, json.getLong("missed"))
    }

    @Test
    fun `dedup counter is independent from redirect counters`() {
        val counters = FuseRedirectCounters()
        counters.setDedupedCount(42L)
        counters.record("com.quark", "/storage/emulated/0/x/y.bin", isRedirected = false)

        assertEquals(42L, counters.dedupedEvents)
        assertEquals(1L, snapshot(counters).getLong("evaluated"))
    }
}
