package me.gm.cleaner.runtime.server.hookbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 5.5.13：server 回调**注入点唯一性**守卫。
 *
 * 背景（第 45 批根因）：`CleanerHooksClient.establishConnection` 成功后只打一行
 * `connected` 就 return，回调注入当时只存在于 `whileAlive` 的**重连分支**内：
 *
 * ```
 * if (service == null || !pingBinder()) { tryReconnect(...); service?.setCleanerServerBinder(...) }
 * ```
 *
 * 桥一直活着时 `service != null && pingBinder()` ⇒ 快路径跳过整个分支 ⇒ 回调
 * **永远没有注入过**。后果链：
 *
 * 回调 null → `HookDataBusBridge.callback == null` → MediaProvider 读快照落文件兜底
 * → 无权读 `/data/local/tmp/cleaner/bus` → 四类快照全 null
 * → `loadConfiguredMountPoints` 直接 return → `applyConfiguredMountPoints` 从不被调用
 * → hook 侧 `mountPointsGeneration=0` → `NativeSyncPolicy` 判 STALE
 * → 界面「存储重定向降级运行」、`fuseRedirect 0/2872` 全 miss。
 *
 * 本测试把三件事实钉死，防止再次退化：
 * 1. `establishConnection` 的成功路径必须注入；
 * 2. 注入只能有**一个**调用点（多处注入会让"注入了几次"失去唯一性）；
 * 3. `whileAlive` 只能提供快路径，不得自带第二套注入逻辑。
 */
class HooksServerCallbackInjectionContractTest {

    private fun source(rel: String): String {
        val candidates = listOf(
            File("src/main/java/$rel"),
            File("repo/runtime/cleaner-server/src/main/java/$rel"),
            File("runtime/cleaner-server/src/main/java/$rel"),
        )
        val hit = candidates.firstOrNull { it.isFile }
        assumeTrue("源码不可达，跳过守卫（${candidates.first().absolutePath}）", hit != null)
        return hit!!.readText()
    }

    private val client =
        "me/gm/cleaner/runtime/server/hookbridge/CleanerHooksClient.kt"

    /** 取出 `establishConnection` 整个函数体（按花括号配对，避免固定字符窗口切进邻居）。 */
    private fun establishConnectionBody(src: String): String {
        val head = src.indexOf("private fun establishConnection(")
        assertTrue("找不到 establishConnection 定义", head >= 0)
        val open = src.indexOf('{', src.indexOf(')', head))
        assertTrue("establishConnection 缺少函数体", open > 0)
        var depth = 1
        var i = open + 1
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return src.substring(open, i)
    }

    @Test
    fun `连接建立成功后必须注入 server 回调`() {
        val body = establishConnectionBody(source(client))
        assertTrue(
            "establishConnection 成功路径必须调用 injectServerCallback —— " +
                    "否则桥一直活着时（whileAlive 快路径）回调永远注入不进去，" +
                    "就是第 45 批 fuseNativeHook STALE 的根因",
            body.contains("injectServerCallback("),
        )
        // 注入必须发生在 return true 之前（否则成功路径拿不到回调）。
        val injectAt = body.indexOf("injectServerCallback(")
        val returnAt = body.indexOf("return true")
        assertTrue("establishConnection 里找不到 return true", returnAt > 0)
        assertTrue(
            "注入必须在 return true 之前执行",
            injectAt in 1 until returnAt,
        )
    }

    @Test
    fun `全文件注入点唯一`() {
        val src = source(client)
        // 直接调 setCleanerServerBinder 的地方只允许出现在 injectServerCallback 内部。
        val direct = Regex("""\.setCleanerServerBinder\s*\(""")
            .findAll(src)
            .count { m ->
                // 注释里提到旧写法不算。
                val lineStart = src.lastIndexOf('\n', m.range.first).coerceAtLeast(0)
                val line = src.substring(lineStart, src.indexOf('\n', m.range.first).let {
                    if (it < 0) src.length else it
                })
                !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
            }
        assertEquals(
            "setCleanerServerBinder 的直接调用只应出现在 injectServerCallback 里，实得 $direct 处",
            1,
            direct,
        )
        assertEquals(
            "injectServerCallback 应当有且只有一处**调用**点（establishConnection）；" +
                    "另一处是 `private fun injectServerCallback(` 定义本身",
            2,
            Regex("""injectServerCallback\s*\(""").findAll(src).count(),
        )
    }

    @Test
    fun `whileAlive 不得自带第二套注入逻辑`() {
        val src = source(client)
        val head = src.indexOf("fun whileAlive(")
        assertTrue("找不到 whileAlive", head >= 0)
        val open = src.indexOf('{', head)
        var depth = 1
        var i = open + 1
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        val body = src.substring(open, i)
        assertTrue(
            "whileAlive 不应直接调用 setCleanerServerBinder —— 注入已收口到 " +
                    "establishConnection，重复注入会让调用多一次 Binder 往返",
            !body.contains(".setCleanerServerBinder("),
        )
    }
}
