package me.gm.cleaner.runtime.server.hookbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 5.5.12：hooks 桥**握手入口**的分级口径守卫。
 *
 * 背景：开机时 `CleanerHooksBinderRetriever.get()` 拿不到 provider 有两类成因，级别必须分开。
 *
 * | 场合 | 触发 | 是故障 | 级别 |
 * |---|---|---|---|
 * | provider 还没 publish（app 刚 fork / user 未解锁） | 每次阶梯重试各一次 | **否**，必然恢复 | W |
 * | 阶梯耗尽仍拿不到 | 11.7 s 窗口跑完 | **是**（app 被停用 / 崩溃循环 / provider 被裁） | E |
 *
 * 为什么 5.5.12 才降：第 44 批 `HooksBridgeProvider` 缺 `android:directBootAware`，
 * provider 要等**用户解锁**才被 AMS 安装（`UnlockUser finished 21:11:29.077` 之后 4 ms
 * 才是 `Installing ContentProviderInfo{hooks_bridge}`），而 server 的 `onStart` 在
 * 21:11:27.237 就发了请求 ⇒ 3 条 E 全是必然的时序结果，不是异常。
 *
 * 降 W 后**必须**有 E 出口，否则「桥永久不存在」会全程静音 —— 这与 5.5.11
 * `HOOK.JAVA.REG.EXHAUSTED` 由 W 升 E 是同一条原则。下面的反向守卫就是为此存在。
 */
class HooksBridgeSeverityContractTest {

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

    private val retriever =
        "me/gm/cleaner/runtime/server/hookbridge/CleanerHooksBinderRetriever.java"
    private val client =
        "me/gm/cleaner/runtime/server/hookbridge/CleanerHooksClient.kt"

    @Test
    fun `取不到 provider 是预期瞬态记 W`() {
        val src = source(retriever)
        // 只取 `if (provider == null) { … return null; }` 这一小段：
        // 用固定字符窗口会跨到后面的 catch(Exception) 分支（那里必须保留 Log.e）。
        val start = src.indexOf("if (provider == null)")
        assertTrue("provider == null 分支锚点缺失", start >= 0)
        val end = src.indexOf("return null;", start)
        assertTrue("provider == null 分支缺 return null", end > start)
        val window = src.substring(start, end)
        assertTrue("provider == null 分支必须打 W", window.contains("Log.w(TAG,"))
        assertTrue(
            "该分支不得再出现 Log.e —— 它是每次重试都会走到的预期路径",
            !window.contains("Log.e(TAG,"),
        )
    }

    /**
     * 反向保护：降级不得波及真异常。`Failed to get hooks service` 所在 catch
     * 是「拿到了 provider 但 call 抛异常」，与「provider 不存在」完全两回事。
     */
    @Test
    fun `call 抛异常仍是 E 不得被一起降级`() {
        val src = source(retriever)
        val at = src.indexOf("Log.e(TAG, \"Failed to get hooks service\"")
        assertTrue("catch 分支的 E 锚点缺失", at >= 0)
    }

    /**
     * 配对升级：阶梯耗尽 = 11.7 秒内桥都建不起来，且 5.5.12 起 provider 已
     * `directBootAware`（开机即安装）⇒ 此时仍失败属真故障，必须自己是 E。
     */
    @Test
    fun `启动阶梯耗尽升级为 E`() {
        val src = source(client)
        val at = src.indexOf("startup retry exhausted")
        assertTrue("exhausted 日志锚点缺失", at >= 0)
        // 往前找最近的 severity 调用（Log.w / Log.e 紧邻其前）
        val head = src.substring(maxOf(0, at - 600), at)
        val lastW = head.lastIndexOf("Log.w(")
        val lastE = head.lastIndexOf("Log.e(")
        assertTrue("exhausted 之前应能找到 severity 调用", lastW >= 0 || lastE >= 0)
        assertTrue(
            "startup retry exhausted 必须用 Log.e（真故障出口）",
            lastE > lastW,
        )
    }
}
