package me.gm.cleaner.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 偏好 XML 变体一致性守卫（5.5.15 引入，对应第 47 批「开关不见了」）。
 *
 * ## 被守的缺陷
 *
 * `service_more_options_preferences` 同时存在两个资源变体：
 *
 * - `app/src/main/res/xml/service_more_options_preferences.xml`    （默认，API < 30）
 * - `app/src/main/res/xml-v30/service_more_options_preferences.xml`（API ≥ 30）
 *
 * Android 的资源选择规则是**同级目录里配置最匹配的那一个整份胜出**，不做节点级合并。
 * 因此在 Android 11+ 设备上 `res/xml` 那份**完全不参与**，只有 `xml-v30` 生效。
 *
 * 5.5.13 增加「本模块」隐身开关时只改了 `res/xml` 那份，于是：
 *
 * - 编译期：两份 XML 都合法，`R.string.hide_desktop_icon_key` 存在，无任何告警；
 * - 运行期：实机加载 `xml-v30`，里面没有这两个节点，`findPreference` 返回 null，
 *   `bindSelfHidingPreferences` 里的 `?: return` 静默跳过 ⇒ **界面上就是"设置页
 *   最底下没有这两个开关"，日志里一条线索都没有**。
 *
 * 只用 `aapt2 dump xmltree res/hg.xml` 验证「XML 里有节点」还会得到**假阳性**结论
 * —— 那只是默认变体。第47批就是这么绕进去的。
 *
 * ## 为什么用源码扫描
 *
 * 这个缺陷的形状是纯静态的（两份文件里的节点集合不一致），JVM 单测造不出
 * 资源选择过程。守卫直接比对两份 XML 的关键节点集合。
 */
class PreferenceVariantParityTest {

    private val repoRoot: File = run {
        var d = File("").absoluteFile
        while (!File(d, "app/src/main/res/xml").isDirectory) {
            d = d.parentFile
                ?: error("找不到仓库根目录（从 ${File("").absolutePath} 向上找 app/src/main/res/xml）")
        }
        d
    }

    /** 必须先于 android:key 分组解析：这里只关心「哪些 key 出现在哪个变体里」。 */
    private fun keysIn(relPath: String): Set<String> {
        val f = File(repoRoot, relPath)
        assertTrue("偏好 XML 变体不存在: ${f.absolutePath}", f.isFile)
        return KEY_RE.findAll(f.readText())
            .map { it.groupValues[1].trim() }
            .toSet()
    }

    private companion object {
        /**
         * 匹配 `android:key="@string/xxx_key"`。
         *
         * 用资源名而非解析后的字面量：两份变体引用同一个 `@string/`，比字面量更稳
         * （字符串值以后可能被翻译/改动，资源名不会）。
         */
        val KEY_RE = Regex("""android:key="@string/([A-Za-z0-9_]+)"""")
    }

    @Test
    fun `默认变体与 v30 变体的偏好 key 集合必须完全一致`() {
        val base = keysIn("app/src/main/res/xml/service_more_options_preferences.xml")
        val v30 = keysIn("app/src/main/res/xml-v30/service_more_options_preferences.xml")

        val onlyBase = (base - v30).sorted()
        val onlyV30 = (v30 - base).sorted()

        // 方向性很重要：v30 变体**允许**比默认多（它是给新系统加的 NewPipe 特性，
        // 比如 filesystem_record 整组只在 API≥30 有意义；默认变体给不了旧系统的
        // 功能，硬塞过去只会渲染出不可用的开关）。
        //
        // 但反向绝不允许：默认变体里有的节点，v30 必须全部有 —— 否则在 Android 11+
        // 设备上（也就是目标设备 Android 17）该节点会**静默消失**。
        // 第47批的「本模块」两个开关就是这样丢的。
        assertTrue(
            "res/xml 有而 res/xml-v30 没有的 key（API≥30 设备上会消失）: $onlyBase",
            onlyBase.isEmpty()
        )
        assertTrue(
            "v30 变体不应缺任何默认节点（base=${base.size} v30=${v30.size}）",
            v30.containsAll(base),
        )
        // 记录已知的 v30 独有节点，防"以后又有人只改一个变体"却不被察觉：
        // 如果这里出现新增项，说明有人往 v30 加了节点但没加进默认变体，
        // 需要人工判断是「API≥30 专属特性」（更新本清单）还是「漏改」（修默认变体）。
        assertEquals(
            "v30 独有节点集合发生变化，请确认是「API≥30 专属」还是「漏改默认变体」",
            listOf(
                "apply_read_only_template_to_key",
                // 第52批：媒体库写入审计，只在 v30 —— InsertHooker 在 SDK_INT < Q
                // 直接 return，该开关对 res/xml 服务的 API 26–29 设备无实际功能。
                "audit_media_insert_key",
                // 第52批：媒体库写入拒绝，同样只在 v30（理由同上）。
                "deny_media_insert_writes_key",
                "edit_read_only_template_key",
                "record_external_app_specific_storage_key",
                "record_shared_storage_key",
                "upsert_key",
            ).sorted(),
            onlyV30,
        )
    }

    @Test
    fun `隐身开关必须同时存在于两个变体`() {
        // 单独再守一次：这是第47批实际丢失的两个节点。把它们写死在这里，
        // 是因为「key 集合一致」这条在极端情况下（两份同时被删）仍然通过。
        val required = listOf("hide_desktop_icon_key", "hide_recents_key")
        for (variant in listOf(
            "app/src/main/res/xml/service_more_options_preferences.xml",
            "app/src/main/res/xml-v30/service_more_options_preferences.xml",
        )) {
            val keys = keysIn(variant)
            for (k in required) {
                assertTrue(
                    "$variant 缺少 @string/$k —— 该 API 级别的设备上开关将不渲染",
                    k in keys
                )
            }
        }
    }

    @Test
    fun `隐身开关不得使用被注释掉的方式占位`() {
        // 注释里的 <SwitchPreferenceCompat> 会被上面按 key 的正则命中，但实际不渲染。
        // 这条守卫确认：在两个变体里，hide_desktop_icon_key 都出现在**未被注释**的行上。
        for (variant in listOf(
            "app/src/main/res/xml/service_more_options_preferences.xml",
            "app/src/main/res/xml-v30/service_more_options_preferences.xml",
        )) {
            val f = File(repoRoot, variant)
            // 逐行判定：只认"整行不是注释开头"的行。注释块内部的 `<SwitchPreferenceCompat>`
            // 示例会被误认为是有效节点，这里直接按行首过滤掉。
            val live = f.readLines()
                .filterNot { it.trimStart().startsWith("<!--") }
                .any { it.contains("""android:key="@string/hide_desktop_icon_key"""") }
            assertTrue("$variant 中 hide_desktop_icon_key 不是有效节点（可能被注释）", live)
        }
    }
}
