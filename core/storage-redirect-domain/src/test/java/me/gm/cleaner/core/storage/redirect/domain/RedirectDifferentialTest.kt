package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 语义护栏：规范解释器与投影桥的行为冻结。
 *
 * 旧双实现已退役并删除；等价性由 oracle 回放与投影桥断言锁定。
 * 校验严格度：非规范输入在投影器与钩子解析期过滤，桥内兜底丢弃，永不抛异常。
 */
class RedirectDifferentialTest {

    @Test
    fun `oracle全量回放解释一致`() {
        oracleCases().forEach { case ->
            val ordered = case.rules.mapIndexed { index, (source, target) ->
                rule(index, source, target)
            }
            assertEquals(
                "${case.name}: interpret",
                case.mountedPath,
                OrderedRedirectInterpreter.interpret(case.path, ordered).derivedPath,
            )
            assertEquals(
                "${case.name}: deriveMountPoints",
                case.mountPoints,
                OrderedRedirectInterpreter.deriveMountPoints(ordered)
                    .map(RedirectMountPoint::derivedPath),
            )
        }
    }

    @Test
    fun `无命中一致透传`() {
        assertBridged(
            name = "no-match",
            rules = listOf("/real/photos" to "/visible/DCIM"),
            paths = listOf("/other/file.txt", "/visible/DCIM2/a.jpg"),
            expectedMountPoints = listOf("/visible/DCIM"),
            expectedResolved = listOf("/other/file.txt", "/visible/DCIM2/a.jpg"),
        )
    }

    @Test
    fun `最后匹配与尾链改写一致`() {
        assertBridged(
            name = "last-match-wins",
            rules = listOf("/real/first" to "/visible/A", "/real/last" to "/visible/A"),
            paths = listOf("/visible/A/file.txt"),
            expectedMountPoints = listOf("/visible/A", "/real/first"),
            expectedResolved = listOf("/real/last/file.txt"),
        )
        assertBridged(
            name = "tail-chain",
            rules = listOf("/real/A" to "/visible/A", "/real/B" to "/real/A/B"),
            paths = listOf("/visible/A/B/file.txt", "/visible/A/other.txt"),
            expectedMountPoints = listOf("/visible/A", "/real/A/B"),
            expectedResolved = listOf("/real/B/file.txt", "/real/A/other.txt"),
        )
        assertBridged(
            name = "preserve-in-middle",
            rules = listOf(
                "/backing" to "/visible/A",
                "/visible/A" to "/visible/A",
                "/final" to "/backing/sub",
            ),
            paths = listOf("/visible/A/file.txt", "/backing/sub/file.txt"),
            expectedMountPoints = listOf("/visible/A", "/backing", "/backing/sub"),
            expectedResolved = listOf("/visible/A/file.txt", "/final/file.txt"),
        )
    }

    @Test
    fun `同名前缀与路径边界一致`() {
        assertBridged(
            name = "segment-boundary",
            rules = listOf("/real/photos" to "/visible/DCIM"),
            paths = listOf(
                "/visible/DCIM/a.jpg",
                "/visible/DCIM2/a.jpg",
                "/visible/DCIM",
                "/real/photos/a.jpg",
            ),
            expectedMountPoints = listOf("/visible/DCIM"),
            expectedResolved = listOf(
                "/real/photos/a.jpg",
                "/visible/DCIM2/a.jpg",
                "/real/photos",
                "/real/photos/a.jpg",
            ),
        )
    }

    @Test
    fun `校验严格度_非法抛`() {
        val trailingSlash = listOf(rule(0, "/real/A", "/visible/A"))
        try {
            OrderedRedirectInterpreter.interpret("/visible/A/", trailingSlash)
            fail("尾斜杠应抛")
        } catch (_: IllegalArgumentException) {
        }

        // 双斜杠与相对段同理。
        listOf("/visible//A/file", "/visible/A/../B").forEach { bad ->
            try {
                OrderedRedirectInterpreter.interpret(bad, trailingSlash)
                fail("非法路径应抛：$bad")
            } catch (_: IllegalArgumentException) {
            }
        }

        // orderIndex 空洞拒绝。
        val gapped = listOf(
            OrderedRedirectRule(RuleId("r0"), RedirectRuleType.MAP, "/a", "/b", 0),
            OrderedRedirectRule(RuleId("r2"), RedirectRuleType.MAP, "/c", "/d", 2),
        )
        try {
            OrderedRedirectInterpreter.interpret("/b/file", gapped)
            fail("空洞索引应抛")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `alias闭包行为冻结`() {
        val pairs = listOf("/real/A" to "/visible/A", "/real/B" to "/real/A/B")
        val ordered = pairs.mapIndexed { index, (source, target) ->
            rule(index, source, target)
        }
        val closure = OrderedRedirectInterpreter.deriveAliasClosure("/real/A", ordered)
        // 旧可达分析已外迁界面（等价由界面测试锁定），此处只冻结新闭包完成性。
        assertTrue(closure.paths.isNotEmpty())
        assertTrue(closure.complete)
    }

    @Test
    fun `推导器挂载点与oracle一致`() {
        oracleCases().forEach { case ->
            val snapshot = snapshotOf(case.rules)
            val actual = RedirectPolicyDeriver.buildConfiguredMountPoints(snapshot).points
            assertEquals("${case.name}: deriver mountPoints", case.mountPoints, actual)
        }
    }

    @Test
    fun `推导器非规范输入丢弃不抛`() {
        val dirty = listOf("/real/A/" to "/visible/A", "/visible/A" to "/visible/A")
        val snapshot = snapshotOf(dirty)
        assertEquals(
            listOf("/visible/A"),
            RedirectPolicyDeriver.buildConfiguredMountPoints(snapshot).points,
        )
        assertEquals(
            "/visible/A/file",
            RedirectPolicyDeriver.getMountedPath(snapshot, "pkg", 0, "/visible/A/file"),
        )
    }

    @Test
    fun `推导器缺包缺用户返回原路径`() {
        val snapshot = snapshotOf(listOf("/real/A" to "/visible/A"))
        assertEquals(
            "/visible/A/file",
            RedirectPolicyDeriver.getMountedPath(snapshot, "other", 0, "/visible/A/file"),
        )
        assertEquals(
            "/visible/A/file",
            RedirectPolicyDeriver.getMountedPath(snapshot, "pkg", 10, "/visible/A/file"),
        )
        assertEquals(
            "/real/A/file",
            RedirectPolicyDeriver.getMountedPath(snapshot, "pkg", 0, "/visible/A/file"),
        )
    }

    @Test
    fun `投影桥挂载计划与oracle一致`() {
        oracleCases().forEach { case ->
            val rules = case.rules.map { (source, target) ->
                RedirectRule(source = source, target = target)
            }
            val plan = MountPlanDeriver.derive("pkg", 0, rules)
                ?: error("${case.name}: 空规则不应返回 null")
            assertEquals("${case.name}: sources", case.rules.map { it.first }, plan.sources)
            assertEquals("${case.name}: targets", case.rules.map { it.second }, plan.targets)
            assertEquals("${case.name}: mountPoints", case.mountPoints, plan.mountPoints)
            assertEquals("${case.name}: mkdirList", case.mountPoints + plan.sources, plan.mkdirList)
            assertEquals(
                "${case.name}: resolve",
                case.mountedPath,
                MountPlanDeriver.resolveMountedPath(rules, case.path),
            )
        }
    }

    @Test
    fun `投影桥空规则返回null脏输入丢弃不抛`() {
        assertEquals(null, MountPlanDeriver.derive("pkg", 0, emptyList()))
        assertEquals("/a", MountPlanDeriver.resolveMountedPath(emptyList(), "/a"))
        val dirty = listOf(RedirectRule(source = "/real/A/", target = "/visible/A"))
        val plan = MountPlanDeriver.derive("pkg", 0, dirty)!!
        assertEquals(emptyList<String>(), plan.mountPoints)
        assertEquals("/visible/A/file", MountPlanDeriver.resolveMountedPath(dirty, "/visible/A/file"))
    }

    private fun snapshotOf(rules: List<Pair<String, String>>): RedirectPolicySnapshot {
        val redirect = rules.map { (source, target) ->
            RedirectRule(source = source, target = target)
        }
        return RedirectPolicySnapshot(
            generation = 1L,
            storage = RuntimeStoragePolicy(
                redirectRules = mapOf("pkg" to mapOf(0 to redirect)),
            ),
        )
    }

    private fun assertBridged(
        name: String,
        rules: List<Pair<String, String>>,
        paths: List<String>,
        expectedMountPoints: List<String>,
        expectedResolved: List<String>,
    ) {
        val redirect = rules.map { (source, target) -> RedirectRule(source, target) }
        val plan = MountPlanDeriver.derive("pkg", 0, redirect)
            ?: error("$name: 空规则不应返回 null")
        assertEquals("$name: mountPoints", expectedMountPoints, plan.mountPoints)
        assertEquals("$name: mkdirList", expectedMountPoints + plan.sources, plan.mkdirList)
        paths.forEachIndexed { index, path ->
            assertEquals(
                "$name: $path",
                expectedResolved[index],
                MountPlanDeriver.resolveMountedPath(redirect, path),
            )
        }
    }

    private fun rule(
        index: Int,
        source: String,
        target: String,
    ): OrderedRedirectRule = OrderedRedirectRule(
        ruleId = RuleId("rule-$index"),
        type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
        source = source,
        target = target,
        orderIndex = index,
    )

    private fun oracleCases(): List<OracleCase> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("mount-rules-v4_0_0-oracle.jsonl")
            ?: error("缺少 mount-rules-v4_0_0-oracle.jsonl")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).map(::parseOracleCase).toList()
        }
    }

    private fun parseOracleCase(line: String): OracleCase {
        val fields = FIELD.findAll(line).associate { match ->
            val name = match.groupValues[1]
            val scalar = match.groupValues[2]
            val array = match.groupValues[3]
            name to if (scalar.isNotEmpty()) {
                listOf(scalar)
            } else {
                STRING.findAll(array).map { it.groupValues[1] }.toList()
            }
        }
        fun scalar(name: String): String = requireNotNull(fields[name]?.singleOrNull())
        fun array(name: String): List<String> = requireNotNull(fields[name])
        return OracleCase(
            name = scalar("name"),
            rules = array("rules").map { encoded ->
                val separator = encoded.indexOf(RULE_SEPARATOR)
                require(separator >= 0)
                encoded.substring(0, separator) to
                    encoded.substring(separator + RULE_SEPARATOR.length)
            },
            path = scalar("path"),
            mountedPath = scalar("mountedPath"),
            mountPoints = array("mountPoints"),
        )
    }

    private data class OracleCase(
        val name: String,
        val rules: List<Pair<String, String>>,
        val path: String,
        val mountedPath: String,
        val mountPoints: List<String>,
    )

    private companion object {
        const val RULE_SEPARATOR = "=>"
        val FIELD = Regex(""""([^"]+)":(?:"([^"]*)"|\[([^]]*)])""")
        val STRING = Regex(""""([^"]*)"""")
    }
}
