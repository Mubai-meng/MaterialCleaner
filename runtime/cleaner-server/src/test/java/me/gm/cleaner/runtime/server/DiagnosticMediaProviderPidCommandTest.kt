package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断取证命令的语法回归测试（纯字符串，无设备依赖）。
 *
 * 背景：第 39/40 批的诊断包里 `logs/logcat_media_provider_pid.txt` 恒为
 *
 * ```
 * /system/bin/sh: 9539: inaccessible or not found
 * media provider process is not running (no pid found)
 * ```
 *
 * 根因是 `MPID=$($(pidof ...))` 这层**双重命令替换**：外层 `$( )` 会把内层拿到的
 * pid（`9539`）当成命令再执行一次。而同一批的 `media_provider_maps.txt` 用正确的
 * `for p in $(pidof ...)` 就能抓到 pid —— 这个对照正是当年的漏检原因。
 *
 * 该文件是唯一能定向取到 Hook 宿主日志的通道（MediaProvider 自身 tag 不在
 * `LogTagFilter.TAGS` 白名单内），失效等于排查时丢失主要证据，故加骨折级断言。
 */
class DiagnosticMediaProviderPidCommandTest {

    private val cmd: String get() = DiagnosticArchive.mediaProviderPidLogcatCommand()

    @Test
    fun `命令替换只有一层`() {
        // 核心断言：`$(` 后面绝不能紧跟 `$(`，否则就是把输出当命令执行。
        assertFalse(
            "出现双重命令替换 \$(\$(",
            cmd.contains("\$(\$("),
        )
        // 展开后形如 `MPID=$(pidof ...)`
        assertTrue("缺少单层 pidof 赋值", cmd.contains("MPID=\$(pidof "))
    }

    @Test
    fun `三个候选包名齐全`() {
        // 与 media_provider_maps.txt 保持同一口径，避免两处漂移。
        assertTrue(cmd.contains("com.android.providers.media.module"))
        assertTrue(cmd.contains("com.android.providers.media "))
        assertTrue(cmd.contains("com.google.android.providers.media.module"))
    }

    @Test
    fun `空 pid 时给出明确文案而不执行 logcat`() {
        // 分支必须存在：否则 MPID 为空时 `logcat --pid=` 会报参数错误，
        // 又变成一条新的误导性证据。
        assertTrue(cmd.contains("if [ -z "))
        assertTrue(cmd.contains("media provider process is not running"))
        assertTrue(cmd.contains("--pid="))
        assertTrue(cmd.contains("fi"))
    }

    @Test
    fun `取首个 pid 而不是整串`() {
        // pidof 可能返回多个 pid（多进程），不取首列会让 `--pid=1 2 3` 非法。
        assertTrue(cmd.contains("awk"))
        assertTrue(cmd.contains("print \$1"))
    }

    /**
     * 反向验证：真用 sh 跑一遍「双层 vs 单层」，确认双层必然拿空、单层能拿到 pid。
     *
     * host 无 /system/bin/sh，这里用 `sh -c` 与自定义 pidof 替身复刻语义，
     * 只验证**命令替换层数**这一件事，不依赖设备。
     */
    @Test
    fun `双层取空而单层取到值`() {
        val sh = locateSh() ?: return // host 无 sh 时跳过，不阻断构建
        val script = buildString {
            append("pidof() { echo 9539; }; ")
            append("bad=\$(\$(pidof x 2>/dev/null | awk '{print \$1}')); ")
            append("good=\$(pidof x 2>/dev/null | awk '{print \$1}'); ")
            append("echo \"[\$bad][\$good]\"")
        }
        val out = runSh(sh, script)
        // bad 为空（把 9539 当命令执行失败），good 为 9539
        assertEquals("[][9539]", out)
    }

    private fun locateSh(): String? =
        listOf("/bin/sh", "/usr/bin/sh", "/system/bin/sh")
            .firstOrNull { java.io.File(it).canExecute() }

    private fun runSh(sh: String, script: String): String = try {
        val p = ProcessBuilder(sh, "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    } catch (e: Exception) {
        ""
    }
}
