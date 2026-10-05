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
}
