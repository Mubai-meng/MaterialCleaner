package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自包含规则对的护栏。
 *
 * 真机（2026-10-10 第 7 批）包 `lfyunf.uvpdv.dr8a5.mpbjcuh.jfy` 的规则是
 * `X/cache → /storage/emulated/0` 与 `X → X`，后者前缀解析出的目标
 * `X/cache/Android/data/<pkg>` **落在 X 之内** —— 一旦下发就是"自 mount 或挂载环"。
 *
 * 这里把判定与剔除钉死：任何把 `target` 放进自己 `source` 子树内的规则对，
 * 都不得再从 `sources` / `targets` / `mountPoints` / `mkdirList` 里漏出去，
 * 但必须能在 `selfContainedPoints` 里被看到（禁止静默丢弃）。
 */
class SelfContainedMountPointTest {

    private val pkg = "lfyunf.uvpdv.dr8a5.mpbjcuh.jfy"
    private val selfDir = "/storage/emulated/0/Android/data/$pkg"

    /** 真机快照里的原样规则。 */
    private fun onDeviceRules() = listOf(
        RedirectRule(source = "$selfDir/cache", target = "/storage/emulated/0"),
        RedirectRule(source = selfDir, target = selfDir),
    )

    @Test
    fun `真机自包含规则对不再下发`() {
        val plan = MountPlanDeriver.derive(pkg, 0, onDeviceRules()) ?: error("规则非空时不应返回 null")

        // 只保留通用重定向那一对；恒等 carve-out 的自包含派生点被剔除。
        assertEquals(listOf("$selfDir/cache"), plan.sources)
        assertEquals(listOf("/storage/emulated/0"), plan.targets)
        assertEquals(listOf("/storage/emulated/0"), plan.mountPoints)

        // 但必须能被看到（不是静默丢弃）。
        assertEquals(listOf("$selfDir/cache/Android/data/$pkg"), plan.selfContainedPoints)

        // 关键：mkdir 列表里也不能再有那个点，否则会在应用自己的目录里建出嵌套目录。
        assertEquals(listOf("/storage/emulated/0", "$selfDir/cache"), plan.mkdirList)
        assertFalse(plan.isEmpty())
    }

    @Test
    fun `自包含判定是纯路径包含且按段边界`() {
        // 严格后代 ⇒ 自包含
        assertTrue(MountPlanDeriver.isSelfContainedMount("/a/b", "/a/b/c"))
        assertTrue(MountPlanDeriver.isSelfContainedMount(selfDir, "$selfDir/cache/x"))
        // 自身不算（恒等 bind 是合法空转，且被既有 oracle 断言覆盖）
        assertFalse(MountPlanDeriver.isSelfContainedMount("/a/b", "/a/b"))
        // 同级 / 祖先 ⇒ 不算
        assertFalse(MountPlanDeriver.isSelfContainedMount("/a/b", "/a/c"))
        assertFalse(MountPlanDeriver.isSelfContainedMount("/a/b/c", "/a/b"))
        // **段边界**：/a/bc 不是 /a/b 的后代（前缀判定的经典陷阱）
        assertFalse(MountPlanDeriver.isSelfContainedMount("/a/b", "/a/bc"))
        assertFalse(MountPlanDeriver.isSelfContainedMount("/a/b", "/a/bc/d"))
    }

    @Test
    fun `恒等规则对仍照旧保留`() {
        // 只有一条恒等规则：既不产生自包含，也不能被剔除（oracle 已钉死该形状）。
        val plan = MountPlanDeriver.derive("pkg", 0, listOf(RedirectRule("/a/b", "/a/b")))
            ?: error("规则非空时不应返回 null")
        assertEquals(listOf("/a/b"), plan.sources)
        assertEquals(listOf("/a/b"), plan.mountPoints)
        assertTrue(plan.selfContainedPoints.isEmpty())
    }

    @Test
    fun `兄弟节点的前缀解析不受影响`() {
        // 这是上游 oracle 的 `preserve-in-middle` 形状：carve-out 的派生点是**兄弟**
        // 目录（/backing），不是自身子树，必须原样保留。
        val plan = MountPlanDeriver.derive(
            "pkg", 0,
            listOf(
                RedirectRule("/backing", "/visible/A"),
                RedirectRule("/visible/A", "/visible/A"),
                RedirectRule("/final", "/backing/sub"),
            ),
        ) ?: error("规则非空时不应返回 null")
        assertEquals(listOf("/visible/A", "/backing", "/backing/sub"), plan.mountPoints)
        assertTrue(plan.selfContainedPoints.isEmpty())
        assertEquals(3, plan.sources.size)
    }

    @Test
    fun `全部自包含时计划为空但仍带出原因`() {
        val plan = MountPlanDeriver.derive(
            pkg, 0,
            listOf(RedirectRule(source = "/a", target = "/a/b")),
        ) ?: error("规则非空时不应返回 null")
        assertTrue(plan.isEmpty())
        assertTrue(plan.mountPoints.isEmpty())
        assertTrue(plan.sources.isEmpty())
        assertEquals(listOf("/a/b"), plan.selfContainedPoints)
    }

    @Test
    fun `剔除不改变路径解释结果`() {
        // resolveMountedPath 必须继续使用**全量**规则：carve-out 靠 indexOfLast 生效，
        // 与"是否下发挂载"无关。若哪天有人复用剔除后的列表，这条会红。
        val rules = onDeviceRules()
        assertEquals(
            "$selfDir/file",
            MountPlanDeriver.resolveMountedPath(rules, "$selfDir/file"),
        )
        assertEquals(
            "$selfDir/cache/Download/file",
            MountPlanDeriver.resolveMountedPath(rules, "/storage/emulated/0/Download/file"),
        )
    }

    @Test
    fun `推导幂等`() {
        val first = MountPlanDeriver.derive(pkg, 0, onDeviceRules())
        val second = MountPlanDeriver.derive(pkg, 0, onDeviceRules())
        assertEquals(first, second)
    }
}
