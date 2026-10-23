package me.gm.cleaner.runtime.mediaprovider.hook.err

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * P3-4（5.5.9）：`HOOK.JAVA.BINDER.DEAD` 的**分级口径**守卫。
 *
 * 背景：该错误码在 4 个调用点被复用，语义却分成两类完全相反的场合——
 *
 * | 场合 | 触发者 | 是否故障 | 级别 |
 * |---|---|---|---|
 * | `registry_binder_death` | app 进程退出（被划掉/回收/覆盖安装） | **否**，必然且紧接着重注册 | W |
 * | `server_callback_death` | cleaner-server 进程退出（重启/回收） | **否**，同上 | W |
 * | `link_to_death` + RemoteException | 连死亡监听都建立不起来 | **是** | E |
 * | `link_to_death` + RuntimeException | 同上 | **是** | E |
 *
 * 前两类是**设计内恢复路径**：第 33 批出现 3 次、第 40 批 2 次，每次都对应一次
 * app pid 变更、每次都在 generation 递增后恢复，从未指向真实故障。把它们记为 E
 * 会让每次开关 app 页都往诊断概览顶一条红，把 `link_to_death` 那类真故障淹掉。
 *
 * 为什么用源码守卫而不是反射/单测调用：severity 是**调用点字面量**，运行时无法
 * 在不触发死亡回调的前提下观测；而触发死亡回调需要真实 Binder，JVM 单测做不
 * 到。唯一能防住「后人手滑改回 E」的地方就是源码本身。
 */
class BinderDeadSeverityContractTest {

    private fun source(rel: String): String {
        // Gradle 的 Test 任务 workingDir 默认为模块目录；IDE 或其他入口可能不同，
        // 因此多候选探测，实在找不到就跳过，绝不误报失败。
        val candidates = listOf(
            File("src/main/java/$rel"),
            File("repo/runtime/media-provider-hook/src/main/java/$rel"),
            File("runtime/media-provider-hook/src/main/java/$rel"),
        )
        val hit = candidates.firstOrNull { it.isFile }
        assumeTrue("源码不可达，跳过守卫（${candidates.first().absolutePath}）", hit != null)
        return hit!!.readText()
    }

    /**
     * 取某段文本中「最靠近 `anchor` 之后的**一个**」severity 字面量。
     *
     * 5.5.11 起 `registerHooksCallback` 的 catch 分成 retryable(W) / 非 retryable(E)
     * 两条相邻分支，旧的「WARN 只要出现就赢」判据会把两条都读成 W。改为取最近者：
     * 哪个字面量先出现就属于哪个分支。
     */
    private fun severityAfter(text: String, anchor: String): String {
        val at = text.indexOf(anchor)
        assertTrue("锚点缺失：$anchor", at >= 0)
        val window = text.substring(at, minOf(text.length, at + 900))
        val warn = window.indexOf("HookErrorJournal.SEVERITY_WARN").let { if (it < 0) Int.MAX_VALUE else it }
        val error = window.indexOf("HookErrorJournal.SEVERITY_ERROR").let { if (it < 0) Int.MAX_VALUE else it }
        return when {
            warn == Int.MAX_VALUE && error == Int.MAX_VALUE -> "?"
            warn < error -> "W"
            else -> "E"
        }
    }

    private val registrar =
        "me/gm/cleaner/runtime/mediaprovider/hook/bridge/HookBridgeRegistrar.java"
    private val service =
        "me/gm/cleaner/runtime/mediaprovider/hook/media/MediaProviderHooksService.java"

    @Test
    fun `registry binder 死亡是预期路径记 W`() {
        val src = source(registrar)
        assertEquals("stage=registry_binder_death 必须记 W", "W", severityAfter(src, "stage=registry_binder_death"))
    }

    @Test
    fun `server callback 死亡是预期路径记 W`() {
        val src = source(service)
        assertEquals("stage=server_callback_death 必须记 W", "W", severityAfter(src, "stage=server_callback_death"))
    }

    /**
     * 反向保护：降级**不得**波及真正的失败路径。
     * `link_to_death` 代表连死亡监听都建立不起来 —— 那时重注册也不会发生，
     * 桥就此断掉，必须保持 E。
     */
    @Test
    fun `link_to_death 失败仍是 E 不得被一起降级`() {
        val src = source(service)
        val occurrences = Regex("stage=link_to_death").findAll(src).count()
        assertEquals("link_to_death 应有两个分支（RemoteException / RuntimeException）", 2, occurrences)
        assertEquals("link_to_death 必须记 E", "E", severityAfter(src, "stage=link_to_death"))
    }

    /**
     * 5.5.11：`register_hooks_callback` 按成因分成三支，必须各归各位。
     *
     * - `provider_acknowledged_false`：Provider 明确回 `registered=false` ⇒ 真失败，E。
     * - `retryable=true`：只在 `BridgeRetryableFailurePolicy` 认定「Provider 还没注册」
     *   时出现（开机 / 覆盖安装窗口）⇒ 预期竞态，W，且不打堆栈。
     * - `retryable=false`：认不出的异常 ⇒ 一律 E，宁可多一条红也不静音。
     */
    @Test
    fun `provider 明确拒绝注册仍是 E`() {
        val src = source(registrar)
        assertEquals(
            "reason=provider_acknowledged_false 必须记 E",
            "E",
            severityAfter(src, "reason=provider_acknowledged_false"),
        )
    }

    @Test
    fun `provider 未就绪是可重试路径记 W`() {
        val src = source(registrar)
        assertEquals(
            "retryable=true 必须记 W",
            "W",
            severityAfter(src, "stage=register_hooks_callback, retryable=true"),
        )
    }

    @Test
    fun `认不出的注册失败仍是 E 不得被一起降级`() {
        val src = source(registrar)
        assertEquals(
            "retryable=false 必须记 E",
            "E",
            severityAfter(src, "stage=register_hooks_callback, retryable=false"),
        )
    }

    /**
     * 反向保护：per-attempt 失败降 W 之后，**重试预算耗尽**必须升 E ——
     * 否则「Provider 永远不出现」这种真故障会全程只有 W。
     */
    @Test
    fun `重试预算耗尽升级为 E`() {
        val src = source(
            "me/gm/cleaner/runtime/mediaprovider/hook/bootstrap/MediaProviderRuntime.java"
        )
        assertEquals(
            "HOOK.JAVA.REG.EXHAUSTED 必须记 E",
            "E",
            severityAfter(src, "stage=bridge_register_retry"),
        )
    }
}
