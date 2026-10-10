package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档脱敏的**保 identity** 契约（回归护栏）。
 *
 * 背景（真缺陷，见 `docs/adapter/日志分析-第5批-2026-10-10.md` §3.1）：
 * 旧实现把所有 ≥24 位 hex 一律替换成**同一个字面量** `<hex-id>`，于是
 * `native_hook_status.json` 的
 * `redirect{Configured,Published,Applied}Revision` **三值全等** ——
 * 归档再也无法回答「applied 是否 == configured」，而那正是该文件存在的意义
 * （一次真实归档里出现 37 处 `<hex-id>`）。
 *
 * 这些用例把三件事钉死：
 * 1. **同值同别名、异值异别名**（否则"revision 变了"不可观测）；
 * 2. 别名**跨调用、跨文件**一致（否则无法拿 `redirect_policy.json` 对 native 段）；
 * 3. 别名**跨运行**稳定 —— 盐是编译期常量而非随机值（否则两次归档不能互比）。
 */
class DiagnosticArchiveRedactTest {

    private val revisionA = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val revisionB = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

    private val hexAlias = Regex("<hex-id:([0-9a-f]{8})>")
    private val appAlias = Regex("/data/app/<id:([0-9a-f]{8})>")

    private fun hexAliases(text: String): List<String> =
        hexAlias.findAll(text).map { it.groupValues[1] }.toList()

    @Test
    fun `同一 token 必得同一别名`() {
        val a = hexAliases(DiagnosticArchive.redact("""{"redirectRevision":"$revisionA"}"""))
        val b = hexAliases(DiagnosticArchive.redact("""{"readOnlyRevision":"$revisionA"}"""))
        assertEquals(1, a.size)
        assertEquals(a, b)
    }

    @Test
    fun `不同 token 必得不同别名`() {
        val aliases = hexAliases(
            DiagnosticArchive.redact("""{"a":"$revisionA","b":"$revisionB"}"""),
        )
        assertEquals(2, aliases.size)
        assertNotEquals(aliases[0], aliases[1])
    }

    @Test
    fun `configured 与 applied 相等时可判定相等`() {
        // 这正是旧实现做不到的事：三值全等时无法区分"已收敛"与"未收敛"。
        val aliases = hexAliases(
            DiagnosticArchive.redact(
                """{"configured":"$revisionA","published":"$revisionA","applied":"$revisionA"}""",
            ),
        )
        assertEquals(3, aliases.size)
        assertEquals(1, aliases.toSet().size)
    }

    @Test
    fun `configured 与 applied 不等时可判定不等`() {
        val aliases = hexAliases(
            DiagnosticArchive.redact(
                """{"configured":"$revisionA","published":"$revisionA","applied":"$revisionB"}""",
            ),
        )
        assertEquals(3, aliases.size)
        assertEquals(2, aliases.toSet().size)
    }

    @Test
    fun `别名跨运行稳定且不泄露原值`() {
        // 盐是编译期常量 ⇒ 别名只由 token 决定，两次独立导出可比。
        // 下面两个期望值是常量契约：若它们变了，跨归档比对就断了。
        assertEquals(listOf("3379ff4a"), hexAliases(DiagnosticArchive.redact(revisionA)))
        assertEquals(listOf("bf32a314"), hexAliases(DiagnosticArchive.redact(revisionB)))

        val out = DiagnosticArchive.redact("""{"redirectRevision":"$revisionA"}""")
        assertFalse("别名里不得残留原 token", out.contains(revisionA))
        assertTrue("必须保留 <hex-id 前缀，否则既有 grep 工具会失效", out.contains("<hex-id:"))
    }

    @Test
    fun `短 hex 串不受影响`() {
        // 模式是 ≥24 位；短串（uid / flags / generation）必须原样保留，
        // 否则会把大量正常字段一起糊掉。
        val input = """{"uid":10438,"flags":1073742080,"generation":3}"""
        assertEquals(input, DiagnosticArchive.redact(input))
    }

    @Test
    fun `data app 路径不再整段塌成同一字面量`() {
        val a = DiagnosticArchive.redact(
            "/data/app/~~AAAABBBBCCCCDDDD==/me.gm.cleaner-EEEEFFFFGGGGHHHH==/base.apk",
        )
        val b = DiagnosticArchive.redact(
            "/data/app/~~ZZZZYYYYXXXXWWWW==/com.other-EEEEFFFFGGGGHHHH==/base.apk",
        )
        assertTrue(a.startsWith("/data/app/<id:"))
        assertTrue(b.startsWith("/data/app/<id:"))
        assertNotEquals("不同安装路径必须得到不同别名", a, b)
        assertEquals("/data/app/<id:3f205934>", a)
        assertFalse("随机安装目录与包目录都不得泄露", b.contains("com.other"))
    }

    @Test
    fun `同一 data app 安装目录在 manifest 与日志里得到同一别名`() {
        // 这是 §3.5 那个证据缺口的回归护栏：EMBEDDED 模式下
        // "libinline.so 来自本模块自己的 base.apk" 只能靠别名同源来证明。
        val dir = "/data/app/~~AAAABBBBCCCCDDDD==/me.gm.cleaner-EEEEFFFFGGGGHHHH=="
        val manifest = DiagnosticArchive.redact("sourceDir=$dir/base.apk")
        val log = DiagnosticArchive.redact("Load $dir/base.apk!/lib/arm64-v8a/libinline.so")

        val inManifest = appAlias.find(manifest)?.groupValues?.get(1)
        val inLog = appAlias.find(log)?.groupValues?.get(1)
        assertEquals("两次出现必须同别名", inManifest, inLog)
        // 库名必须保留 —— 它才是"注入了什么"的直接信息。
        assertTrue(log.contains("libinline.so"))
    }
}
