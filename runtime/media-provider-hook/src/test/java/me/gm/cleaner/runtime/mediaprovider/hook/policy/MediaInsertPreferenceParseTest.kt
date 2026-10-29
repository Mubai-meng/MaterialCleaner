package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏好透传解析守卫（第52批）。
 *
 * ## 被守的缺陷形状
 *
 * `auditMediaInsert` / `denyMediaInsertWrites` 这类开关的真相在偏好层，但 Hook 侧
 * 拿到的是 `redirect_policy` 快照 JSON，中间要过 6 层透传：
 *
 * ```
 * ServicePreferences → RuntimeBehaviorPolicy → RuntimePolicyProjector
 *    → SnapshotPublisher → PolicyPreferences → HookPolicyCache
 * ```
 *
 * 任何一层漏掉字段，**编译期全绿、单测全绿、装到手机上开关点了没反应**，
 * 且日志里一条线索都没有。第52批的审计开关就是靠人肉逐层核对才发现漏改风险。
 *
 * 本测试把"快照 JSON → PreferencesHolder"这一段钉死：
 * - 字段存在且为 true 时必须解析成 true（防"解析写错 key 名"）
 * - 字段缺席时必须为 false（防"老快照下行为突变"）
 *
 * ## 为什么默认必须是 false
 *
 * `denyMediaInsertWrites` 一旦默认 true，**所有**装了本模块的用户在升级瞬间就会
 * 开始拒绝媒体库写入 —— 这属于会改变应用行为的破坏性默认值。缺席容忍为 false
 * 是硬约束，不是随手选的。
 */
class MediaInsertPreferenceParseTest {

    private fun parse(json: String) = PolicyPreferencesParser.parse(json)

    @Test
    fun `审计开关打开时解析为true`() {
        val p = parse("""{"auditMediaInsert": true, "generation": 7}""")
        assertTrue("auditMediaInsert 应解析为 true", p.auditMediaInsert)
        assertEquals(7L, p.generation)
    }

    @Test
    fun `拒绝开关打开时解析为true`() {
        val p = parse("""{"denyMediaInsertWrites": true, "generation": 8}""")
        assertTrue("denyMediaInsertWrites 应解析为 true", p.denyMediaInsertWrites)
        assertEquals(8L, p.generation)
    }

    @Test
    fun `两个开关互相独立不得串味`() {
        // 只开审计：拒绝必须仍为 false，否则"只想看日志"会变成"开始拦写入"。
        val onlyAudit = parse("""{"auditMediaInsert": true}""")
        assertTrue(onlyAudit.auditMediaInsert)
        assertFalse("只开审计时拒绝必须为 false", onlyAudit.denyMediaInsertWrites)

        // 只开拒绝：审计必须仍为 false。
        val onlyDeny = parse("""{"denyMediaInsertWrites": true}""")
        assertTrue(onlyDeny.denyMediaInsertWrites)
        assertFalse("只开拒绝时审计必须为 false", onlyDeny.auditMediaInsert)
    }

    @Test
    fun `字段缺席时两个开关都必须是false`() {
        // 老快照（6.0.2 及更早）没有这两个字段：行为必须与历史完全一致。
        val p = parse("""{"generation": 3, "publisherEpoch": "old-rom"}""")
        assertFalse("缺席时审计必须为 false", p.auditMediaInsert)
        assertFalse("缺席时拒绝必须为 false（否则升级即破坏性变更）", p.denyMediaInsertWrites)
    }

    @Test
    fun `显式false不会被误判为true`() {
        val p = parse("""{"auditMediaInsert": false, "denyMediaInsertWrites": false}""")
        assertFalse(p.auditMediaInsert)
        assertFalse(p.denyMediaInsertWrites)
    }

    @Test
    fun `非布尔值按org_json既有语义处理而非本开关特有`() {
        // org.json 的 optBoolean 会把字符串 "true"/"false" 宽松转成布尔
        // （JSONObject.toBoolean 对 String 走 equalsIgnoreCase）。这是**既有语义**，
        // fuseBpfBlockAll 等所有布尔字段都一样，不是本开关引入的。
        // 这里把真实行为钉住，避免以后有人误以为能靠字符串形式表达开关。
        assertTrue(
            "org.json 会把字符串 \"true\" 转成 true —— 这是全字段一致的既有行为",
            parse("""{"denyMediaInsertWrites": "true"}""").denyMediaInsertWrites
        )
        assertFalse(
            "无法识别的字符串应回落到 fallback(false)",
            parse("""{"denyMediaInsertWrites": "yes"}""").denyMediaInsertWrites
        )
        assertFalse(
            "非布尔非字符串类型应回落到 fallback(false)",
            parse("""{"denyMediaInsertWrites": 1}""").denyMediaInsertWrites
        )
    }

    @Test
    fun `既有偏好字段不受新字段影响`() {
        // 回归保护：新字段是插在中间加的，不能打乱既有字段的解析。
        val p = parse(
            """{"fuseBpfBlockAll": true, "aggressivelyPromptForReadingMediaFiles": true,
               "recordExternalAppSpecificStorage": true, "auditMediaInsert": true,
               "denyMediaInsertWrites": true}"""
        )
        assertTrue(p.fuseBpfBlockAll)
        assertTrue(p.aggressivelyPromptForReadingMediaFiles)
        assertTrue(p.recordExternalAppSpecificStorage)
        assertTrue(p.auditMediaInsert)
        assertTrue(p.denyMediaInsertWrites)
    }
}
