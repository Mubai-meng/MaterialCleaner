package me.gm.cleaner.client.ui.storageredirect

import me.gm.cleaner.core.storage.redirect.domain.MountPlanDeriver
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectReachabilityAnalyzerTest {

    private val corpora = listOf(
        emptyList(),
        listOf("/real/photos" to "/visible/DCIM"),
        listOf("/real/first" to "/visible/A", "/real/last" to "/visible/A"),
        listOf("/real/A" to "/visible/A", "/real/B" to "/real/A/B"),
        listOf("/visible/A" to "/visible/A"),
        listOf("/cache" to "/sdcard", "/data" to "/data"),
    )
    private val paths = listOf(
        "/visible/DCIM/a.jpg",
        "/visible/DCIM2/a.jpg",
        "/visible/A/file.txt",
        "/real/A/file.txt",
        "/sdcard/file.txt",
        "/other/file.txt",
    )
    private val redundant = listOf(
        emptyList(),
        emptyList(),
        listOf(0),
        emptyList(),
        listOf(0),
        listOf(1),
    )
    private val mounted = listOf(
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/real/photos/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/real/last/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/real/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/cache/file.txt",
            "/other/file.txt",
        ),
    )
    private val accessible = listOf(
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
    )

    @Test
    fun `外迁前后行为一致`() {
        corpora.forEachIndexed { ci, rules ->
            assertEquals("corpus=$ci redundant", redundant[ci], RedirectReachabilityAnalyzer.redundantIndices(rules))
            paths.forEachIndexed { pi, path ->
                assertEquals(
                    "corpus=$ci path=$path mounted",
                    mounted[ci][pi],
                    RedirectReachabilityAnalyzer.mountedPath(rules, path),
                )
                assertEquals(
                    "corpus=$ci path=$path accessible",
                    accessible[ci][pi],
                    RedirectReachabilityAnalyzer.accessiblePlaces(rules, path),
                )
            }
        }
    }

    @Test
    fun `空规则直接返回原路径`() {
        val emptyRulePaths = listOf(
            "/visible/DCIM/a.jpg",
            "/",
            "",
            "relative/path",
            "/a//b",
            "/visible/DCIM/",
        )
        emptyRulePaths.forEach { path ->
            assertEquals(
                "empty-rules path=$path",
                path,
                RedirectReachabilityAnalyzer.mountedPath(emptyList(), path),
            )
        }
    }

    @Test
    fun `脏输入委托领域解释器行为一致`() {
        val dirtyPaths = listOf(
            "",
            "relative/path",
            "/a//b",
            "/a/./c",
            "/a/../b",
            "/visible/DCIM/",
            "/visible//DCIM/a.jpg",
        )
        val dirtyRuleSets = listOf(
            listOf("/real/photos" to "/visible/DCIM"),
            listOf("/real/A/" to "/visible/A"),
            listOf("/real/A" to "/visible/A/"),
            listOf("" to "/visible/A"),
            listOf("/real/A" to ""),
            listOf("//real/A" to "/visible/A"),
            listOf("/real/A" to "/visible/A", "/real/B/" to "/real/A/B"),
        )
        // 非规范查询路径：领域解释器原样返回，展示层必须一致透传。
        dirtyPaths.forEach { path ->
            val rules = listOf("/real/photos" to "/visible/DCIM")
            val expected = MountPlanDeriver.resolveMountedPath(
                rules.map { (source, target) -> RedirectRule(source, target) },
                path,
            )
            assertEquals(path, expected)
            assertEquals(
                "dirty-path=$path",
                expected,
                RedirectReachabilityAnalyzer.mountedPath(rules, path),
            )
        }
        // 脏规则：尾斜杠/空白/非规范对被领域层丢弃，展示层必须同领域一致。
        dirtyRuleSets.forEachIndexed { ri, rules ->
            val canonicalPath = "/visible/A/file.txt"
            val expected = MountPlanDeriver.resolveMountedPath(
                rules.map { (source, target) -> RedirectRule(source, target) },
                canonicalPath,
            )
            assertEquals(
                "dirty-rules=$ri",
                expected,
                RedirectReachabilityAnalyzer.mountedPath(rules, canonicalPath),
            )
            dirtyPaths.forEach { path ->
                val expectedDirty = MountPlanDeriver.resolveMountedPath(
                    rules.map { (source, target) -> RedirectRule(source, target) },
                    path,
                )
                assertEquals(
                    "dirty-rules=$ri dirty-path=$path",
                    expectedDirty,
                    RedirectReachabilityAnalyzer.mountedPath(rules, path),
                )
            }
        }
        // 脏规则显式期望：尾斜杠与空白规则被忽略，直接透传。
        assertEquals(
            "/visible/A/file.txt",
            RedirectReachabilityAnalyzer.mountedPath(
                listOf("/real/A/" to "/visible/A"),
                "/visible/A/file.txt",
            ),
        )
        assertEquals(
            "/visible/A/file.txt",
            RedirectReachabilityAnalyzer.mountedPath(
                listOf("/real/A" to "/visible/A/"),
                "/visible/A/file.txt",
            ),
        )
        // 脏输入下冗余与可达分析不抛异常，且复用已委托的挂载解释。
        dirtyRuleSets.forEach { rules ->
            RedirectReachabilityAnalyzer.redundantIndices(rules)
            RedirectReachabilityAnalyzer.accessiblePlaces(rules, "/visible/A/file.txt")
        }
    }

    // ───────────────────────── 判据方向回归锁 ─────────────────────────
    //
    // 原 core 模块 MountRulesTest，随 MountRules 外迁至此。
    // 背景：解释器是**后置优先**（先 indexOfLast 命中，再自该索引向尾部链式改写），
    // 因此"通用重定向 + carve-out（例外）"这种规则集里，通用规则恰恰是真正起作用的那条。
    // 上游搬迁版把方向写成了 `startsWithPath(it, target)`（后续落在本规则之内），
    // 该形态在其自带语料里没有覆盖，所以缺陷得以存活；下面把两个方向都钉住。

    private val sd = "/storage/emulated/0"
    private val pkg = "com.example.app"
    private val dataDir = "$sd/Android/data/$pkg"
    private val obbDir = "$sd/Android/obb/$pkg"
    private val cacheDir = "$dataDir/cache"

    /** 向导 q1（含 q12）输出：通用重定向 + 两条恒等 carve-out —— 三条都有效。 */
    @Test
    fun `通用重定向加例外规则不应被判为无意义`() {
        val rules = listOf(cacheDir to sd, dataDir to dataDir, obbDir to obbDir)
        assertEquals(emptyList<Int>(), RedirectReachabilityAnalyzer.redundantIndices(rules))
    }

    /** 反向证据：被判「无意义」的那条一旦移除，映射就变 —— 它确实有效。 */
    @Test
    fun `移除通用重定向会改变映射故其并非无意义`() {
        val all = listOf(cacheDir to sd, dataDir to dataDir)
        val withoutGeneral = listOf(dataDir to dataDir)
        assertEquals(
            "$sd/Download",
            RedirectReachabilityAnalyzer.mountedPath(withoutGeneral, "$sd/Download"),
        )
        assertEquals(
            "$cacheDir/Download",
            RedirectReachabilityAnalyzer.mountedPath(all, "$sd/Download"),
        )
        assertEquals(sd, RedirectReachabilityAnalyzer.mountedPath(withoutGeneral, sd))
        assertEquals(cacheDir, RedirectReachabilityAnalyzer.mountedPath(all, sd))
    }

    /** 真死规则：本规则 target 落在**后续**规则 target 之内 ⇒ 后置优先下永不命中。 */
    @Test
    fun `target 被后续规则覆盖的规则应判为无意义`() {
        val rules = listOf(
            "$sd/Download/A" to "$sd/Download",
            "$sd/Download/B" to sd,
        )
        assertEquals(listOf(0), RedirectReachabilityAnalyzer.redundantIndices(rules))
    }

    /** 纯恒等且没有任何父重定向可被「挖洞」= 空操作。 */
    @Test
    fun `孤立恒等规则应判为无意义`() {
        val rules = listOf(dataDir to dataDir)
        assertEquals(listOf(0), RedirectReachabilityAnalyzer.redundantIndices(rules))
    }

    /** 重复 target：后置者胜出，前者无意义。 */
    @Test
    fun `相同 target 的重复规则前者无意义`() {
        val rules = listOf("/a" to sd, "/b" to sd)
        assertEquals(listOf(0), RedirectReachabilityAnalyzer.redundantIndices(rules))
    }

    /** carve-out 是恒等规则，但它在父重定向下挖了洞，不能算无意义。 */
    @Test
    fun `父重定向下的恒等例外规则有意义`() {
        val rules = listOf(cacheDir to sd, dataDir to dataDir)
        assertEquals(emptyList<Int>(), RedirectReachabilityAnalyzer.redundantIndices(rules))
    }
}
