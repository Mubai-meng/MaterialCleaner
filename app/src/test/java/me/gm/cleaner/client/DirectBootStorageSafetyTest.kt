package me.gm.cleaner.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Direct Boot 存储安全守卫（5.5.14 引入，对应第 46 批 P0 崩溃）。
 *
 * ## 被守的缺陷
 *
 * 5.5.12 给 `HooksBridgeProvider` 加了 `android:directBootAware="true"`（开机握手必需），
 * 于是**用户解锁之前**就会创建 `me.gm.cleaner` 进程并执行 `App.onCreate`。
 *
 * 5.5.13 在 `App.onCreate` 里加了 `SelfHidingPreferences.reconcileOnStartup(this)`，
 * 而该类的偏好建在 **CE（credential encrypted）存储**上。Direct Boot 窗口内
 * `getSharedPreferences` 直接抛：
 *
 * ```
 * IllegalStateException: SharedPreferences in credential encrypted storage are
 *   not available until after user (id 0) is unlocked
 *   at SelfHidingPreferences.prefs
 *   at App.onCreate(App.kt:47)
 * ```
 *
 * 后果不是"读不到偏好"，而是 `Unable to create application` ⇒ **App 进程创建失败**
 * ⇒ 用户看到「存储重定向未运行」（实质是 App 侧永远拿不到 server binder）。
 *
 * ## 为什么用源码扫描而不是行为单测
 *
 * 这个缺陷的触发条件（未解锁 + `App.onCreate`）在 JVM 单测里造不出来：需要真实
 * ActivityThread + ContextImpl 的 `credentialProtectedStorageCheck`。而缺陷的**形状**
 * 很容易静态识别：`App.onCreate` 调用链上的类，只要出现不带
 * `createDeviceProtectedStorageContext()` 的 `getSharedPreferences(...)` 就不安全。
 *
 * 这是**结构性守卫**，不是行为测试 —— 它防的是"以后又有人顺手写一个 CE 偏好"。
 */
class DirectBootStorageSafetyTest {

    private val repoRoot: File = run {
        // 单测工作目录是 app/（Gradle 默认），源码在 app/src/main/java
        var d = File("").absoluteFile
        while (!File(d, "app/src/main/java").isDirectory) {
            d = d.parentFile ?: error("找不到仓库根目录（从 ${File("").absolutePath} 向上找 app/src/main/java）")
        }
        d
    }

    private fun source(relPath: String): String {
        val f = File(repoRoot, relPath)
        assertTrue("源码不存在: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 取某个顶层声明（object/class）的完整花括号配对体，避免跨进邻居函数。 */
    private fun blockOf(src: String, header: String): String {
        val start = src.indexOf(header)
        assertTrue("找不到声明头: $header", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("声明头后没有左花括号: $header", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("花括号未配对: $header")
    }

    @Test
    fun `SelfHidingPreferences 的偏好必须建在设备加密保护存储上`() {
        val src = source("app/src/main/java/me/gm/cleaner/client/SelfHidingPreferences.kt")
        val body = blockOf(src, "object SelfHidingPreferences")

        // prefs() 必须经由 DE context
        assertTrue(
            "SelfHidingPreferences.prefs 必须使用 createDeviceProtectedStorageContext()：" +
                    "该类由 App.onCreate 调用，而 App.onCreate 会在用户解锁之前执行" +
                    "（HooksBridgeProvider 是 directBootAware）。用 CE 存储会抛 " +
                    "IllegalStateException 并让进程创建失败。",
            body.contains("createDeviceProtectedStorageContext()"),
        )

        // 不允许出现「裸」getSharedPreferences —— 必须链在 DE context 之后。
        //
        // 按行扫描并**跳过注释行**：类文档里会引用这个 API 名做说明，
        // 用整段正则会把文档当代码（第一版就这么误报了）。
        val codeLines = body.lines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        val offenders = ArrayList<String>()
        for (i in codeLines.indices) {
            val line = codeLines[i]
            if (!line.contains("getSharedPreferences")) continue
            // 同行含 DE context：放行
            if (line.contains("createDeviceProtectedStorageContext")) continue
            // 分两行写（`context.createDeviceProtectedStorageContext()` 换行接 `.getSharedPreferences(`）：看上一行
            val prev = if (i > 0) codeLines[i - 1] else ""
            if (line.startsWith(".") && prev.contains("createDeviceProtectedStorageContext")) continue
            offenders.add(line)
        }
        assertTrue(
            "发现未经 createDeviceProtectedStorageContext() 的 getSharedPreferences 调用：" +
                    "Direct Boot 窗口内会抛 IllegalStateException。违规行=$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `SelfHidingPreferences 读写偏好都不得向外抛异常`() {
        val src = source("app/src/main/java/me/gm/cleaner/client/SelfHidingPreferences.kt")
        val body = blockOf(src, "object SelfHidingPreferences")

        // 读路径收口到 readBoolean（内部 try/catch）
        assertTrue(
            "读偏好必须收口到 readBoolean()（内含 try/catch），" +
                    "否则一次异常就会让 App.onCreate 炸掉整个进程",
            body.contains("private fun readBoolean(") &&
                    body.contains("readBoolean(context, KEY_HIDE_DESKTOP_ICON)") &&
                    body.contains("readBoolean(context, KEY_HIDE_RECENTS)"),
        )

        // 两条写路径都必须 runCatching
        assertEquals(
            "两处偏好写入（桌面图标 / 后台任务）都必须用 runCatching 包住",
            2,
            Regex("""runCatching\s*\{\s*prefs\(context\)""").findAll(body).count(),
        )
    }

    @Test
    fun `App onCreate 里不允许出现裸的 CE 偏好读取`() {
        val src = source("app/src/main/java/me/gm/cleaner/app/App.kt")
        val body = blockOf(src, "class App : Application()")
        val onCreate = body.substringAfter("override fun onCreate()")

        // App 自身只允许 DE context；裸 getSharedPreferences 一律不允许
        assertTrue(
            "App.onCreate 中不允许直接调用 getSharedPreferences（CE 存储在解锁前不可用）",
            !onCreate.contains("getSharedPreferences"),
        )
    }

    @Test
    fun `HooksBridgeProvider 仍是 directBootAware`() {
        // 反向守卫：如果有人为了规避这个崩溃而把 directBootAware 去掉，
        // 就会退回第 44 批的"解锁前 provider 不在 AMS 里"⇒ 开机握手 3.76s 全失败。
        // 正确解法是偏好迁 DE，不是撤 provider 的直启属性。
        val manifest = source("app/src/main/AndroidManifest.xml")
        val provider = manifest.substringAfter("HooksBridgeProvider").substringBefore("/>")
        assertTrue(
            "HooksBridgeProvider 必须保持 android:directBootAware=\"true\"：" +
                    "去掉它会退回第 44 批的开机握手失败（解锁前 provider 不在 AMS 里）",
            provider.contains("""android:directBootAware="true""""),
        )
    }
}
