package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InaccessiblePathDeriverTest {

    private val sd = "/storage/emulated/0"
    private val pkg = "com.unistrong.hnmpm.fj"
    private val cache = "$sd/Android/data/$pkg/cache"

    // ────────────────────────────────────────────────────────────
    // derive
    // ────────────────────────────────────────────────────────────

    @Test
    fun `实机 batch18 unistrong 三条 q4 规则全部识别`() {
        // 设备实测 redirect_policy.json（generation=6）里 com.unistrong.hnmpm.fj 的规则原文。
        val rules = listOf(
            RedirectRule("$cache/Pictures", "$sd/Pictures"),
            RedirectRule("$cache/DCIM/Camera", "$sd/DCIM/Camera"),
            RedirectRule("$cache/DCIM/MyAlbums/走访已使用", "$sd/DCIM/MyAlbums/走访已使用"),
        )
        assertEquals(
            setOf("$sd/Pictures", "$sd/DCIM/Camera", "$sd/DCIM/MyAlbums/走访已使用"),
            InaccessiblePathDeriver.derive(rules, sd, cache),
        )
    }

    @Test
    fun `q1 根重定向不得被误判为不可访问`() {
        // q1 打开时的首条规则形态与 q4 完全一致，唯一区别是 target == sdDir。
        // 若这里误判，整个共享存储会被判为不可访问 —— 应用将彻底打不开存储。
        val rules = listOf(
            RedirectRule(cache, sd),
            RedirectRule("$sd/Android/data/$pkg", "$sd/Android/data/$pkg"),
        )
        assertTrue(InaccessiblePathDeriver.derive(rules, sd, cache).isEmpty())
    }

    @Test
    fun `q11 用 filesDir 或 sandbox 承载时不影响判定`() {
        // inaccessibleMarker 恒为 cacheDir→sdDir，与 q1 选哪个承载目录无关。
        val rules = listOf(
            RedirectRule("$sd/Android/data/$pkg/files", sd),
            RedirectRule("$cache/Pictures", "$sd/Pictures"),
        )
        assertEquals(
            setOf("$sd/Pictures"),
            InaccessiblePathDeriver.derive(rules, sd, cache),
        )
    }

    @Test
    fun `q2 恒等规则不得被误判`() {
        val rules = listOf(
            RedirectRule("$sd/Pictures", "$sd/Pictures"),
            RedirectRule("$sd/DCIM/Camera", "$sd/DCIM/Camera"),
        )
        assertTrue(InaccessiblePathDeriver.derive(rules, sd, cache).isEmpty())
    }

    @Test
    fun `q3 自定义规则指向别的目录不得被误判`() {
        val rules = listOf(
            RedirectRule("$cache/Download", "$sd/Music"),
            RedirectRule("$cache/Pictures", "$sd/Pictures"),
        )
        assertEquals(setOf("$sd/Pictures"), InaccessiblePathDeriver.derive(rules, sd, cache))
    }

    @Test
    fun `target 不在 sdDir 下则跳过`() {
        val rules = listOf(RedirectRule("$cache/data", "/data/local/tmp/x"))
        assertTrue(InaccessiblePathDeriver.derive(rules, sd, cache).isEmpty())
    }

    @Test
    fun `相邻同名目录不得误命中`() {
        // PicturesExtra 不能因为以 Pictures 为前缀（字符串层面）就被判为不可访问。
        val rules = listOf(RedirectRule("$cache/Pictures", "$sd/Pictures"))
        val derived = InaccessiblePathDeriver.derive(rules, sd, cache)
        assertNull(InaccessiblePathDeriver.matchesAny(derived, "$sd/PicturesExtra/a.jpg"))
        assertEquals("$sd/Pictures", InaccessiblePathDeriver.matchesAny(derived, "$sd/Pictures/a.jpg"))
    }

    @Test
    fun `空目录参数返回空集`() {
        val rules = listOf(RedirectRule("$cache/Pictures", "$sd/Pictures"))
        assertTrue(InaccessiblePathDeriver.derive(rules, "", cache).isEmpty())
        assertTrue(InaccessiblePathDeriver.derive(rules, sd, "").isEmpty())
    }

    @Test
    fun `多用户路径按 userId 展开后同样成立`() {
        val sd10 = "/storage/emulated/10"
        val cache10 = "/storage/emulated/10/Android/data/$pkg/cache"
        val rules = listOf(RedirectRule("$cache10/Pictures", "$sd10/Pictures"))
        assertEquals(setOf("$sd10/Pictures"), InaccessiblePathDeriver.derive(rules, sd10, cache10))
        // 用 user0 的 cacheDir 去匹配 user10 的规则必须落空，防止跨用户误判。
        assertTrue(InaccessiblePathDeriver.derive(rules, "/storage/emulated/10", cache).isEmpty())
    }

    @Test
    fun `结尾斜杠不影响判定`() {
        val rules = listOf(RedirectRule("$cache/Pictures/", "$sd/Pictures/"))
        assertEquals(setOf("$sd/Pictures"), InaccessiblePathDeriver.derive(rules, "$sd/", "$cache/"))
    }

    // ────────────────────────────────────────────────────────────
    // matchesAny
    // ────────────────────────────────────────────────────────────

    @Test
    fun `matchesAny 命中自身`() {
        assertEquals("$sd/Pictures", InaccessiblePathDeriver.matchesAny(setOf("$sd/Pictures"), "$sd/Pictures"))
    }

    @Test
    fun `matchesAny 命中任意深度子孙`() {
        val targets = setOf("$sd/Pictures")
        // 真实写入路径永远比规则深至少两层 —— 这是旧只读判据失效的根因，这里必须覆盖。
        assertEquals("$sd/Pictures", InaccessiblePathDeriver.matchesAny(targets, "$sd/Pictures/Screenshots/a.jpg"))
        assertEquals("$sd/Pictures", InaccessiblePathDeriver.matchesAny(targets, "$sd/Pictures/a/b/c/d.jpg"))
    }

    @Test
    fun `matchesAny 命中更具体的规则`() {
        val targets = setOf("$sd/DCIM", "$sd/DCIM/Camera")
        assertEquals("$sd/DCIM/Camera", InaccessiblePathDeriver.matchesAny(targets, "$sd/DCIM/Camera/x.jpg"))
        assertEquals("$sd/DCIM", InaccessiblePathDeriver.matchesAny(targets, "$sd/DCIM/other/y.jpg"))
    }

    @Test
    fun `matchesAny 空输入返回 null`() {
        assertNull(InaccessiblePathDeriver.matchesAny(emptySet(), "$sd/Pictures"))
        assertNull(InaccessiblePathDeriver.matchesAny(setOf("$sd/Pictures"), ""))
    }

    // ────────────────────────────────────────────────────────────
    // deriveByMarker（形态判据：不依赖运行时 externalStorageDir）
    // ────────────────────────────────────────────────────────────

    @Test
    fun `形态判据识别 batch19 实机三条 q4 规则`() {
        // 设备实测 redirect_policy.json（generation=3）里 com.unistrong.hnmpm.fj 的规则原文。
        val rules = listOf(
            RedirectRule("$cache/Pictures", "$sd/Pictures"),
            RedirectRule("$cache/DCIM/Camera", "$sd/DCIM/Camera"),
            RedirectRule("$cache/DCIM/MyAlbums/走访已使用", "$sd/DCIM/MyAlbums/走访已使用"),
        )
        assertEquals(
            setOf("$sd/Pictures", "$sd/DCIM/Camera", "$sd/DCIM/MyAlbums/走访已使用"),
            InaccessiblePathDeriver.deriveByMarker(rules, pkg),
        )
    }

    @Test
    fun `server 进程 externalStorageDir 取值异常时形态判据仍生效`() {
        // 第 19 批的根因：环境值判据依赖运行时卷路径，root app_process 下取值与
        // 配置坐标不同源时整体落空，导致 inaccessibleRules 为空、链路从未激活。
        // 这里固化：环境值错误 ⇒ derive 落空，但 deriveAll 必须仍由形态判据兜住。
        val rules = listOf(RedirectRule("$cache/Pictures", "$sd/Pictures"))
        val wrongSd = "/storage/emulated"
        val wrongCache = "$wrongSd/Android/data/$pkg/cache"
        assertTrue(InaccessiblePathDeriver.derive(rules, wrongSd, wrongCache).isEmpty())
        assertEquals(
            setOf("$sd/Pictures"),
            InaccessiblePathDeriver.deriveAll(rules, pkg, wrongSd, wrongCache),
        )
    }

    @Test
    fun `形态判据排除别的包的 cache 目录`() {
        // q4 的 marker 段必须含本包名，否则 A 包的规则会被 B 包的 cache 路径误命中。
        val rules = listOf(
            RedirectRule("$sd/Android/data/other.app/cache/Pictures", "$sd/Pictures"),
        )
        assertTrue(InaccessiblePathDeriver.deriveByMarker(rules, pkg).isEmpty())
    }

    @Test
    fun `形态判据排除 q1 根重定向`() {
        // source 正好是 cacheDir、target 正好是 sdRoot ⇒ 与 q1 完全同形。
        // 误判会把整个共享存储判成不可访问，应用将彻底打不开存储。
        val rules = listOf(RedirectRule(cache, sd))
        assertTrue(InaccessiblePathDeriver.deriveByMarker(rules, pkg).isEmpty())
    }

    @Test
    fun `形态判据要求 target 与相对路径严格一致`() {
        // source 在 cache 下、但 target 不是 sdRoot+rel ⇒ 只是普通自定义重定向。
        val rules = listOf(RedirectRule("$cache/Download", "$sd/Music"))
        assertTrue(InaccessiblePathDeriver.deriveByMarker(rules, pkg).isEmpty())
    }

    @Test
    fun `形态判据不把 cacheExtra 之类前缀当作 cache 段`() {
        val rules = listOf(
            RedirectRule("$sd/Android/data/$pkg/cacheExtra/Pictures", "$sd/Pictures"),
        )
        assertTrue(InaccessiblePathDeriver.deriveByMarker(rules, pkg).isEmpty())
    }

    @Test
    fun `形态判据对空包名返回空集`() {
        val rules = listOf(RedirectRule("$cache/Pictures", "$sd/Pictures"))
        assertTrue(InaccessiblePathDeriver.deriveByMarker(rules, "").isEmpty())
    }

    @Test
    fun `形态判据支持非 user0 存储根`() {
        val sd10 = "/storage/emulated/10"
        val cache10 = "$sd10/Android/data/$pkg/cache"
        val rules = listOf(RedirectRule("$cache10/Pictures", "$sd10/Pictures"))
        assertEquals(setOf("$sd10/Pictures"), InaccessiblePathDeriver.deriveByMarker(rules, pkg))
    }
}
