package me.gm.cleaner.runtime.mediaprovider.hook.fuse

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUSE hook 安装前的 uid 可解析性门（回归护栏）。
 *
 * 背景（真缺陷，第 6 批设备证据）：`exactRegistry` 把
 * `onfilecreatedforfuse → multiArgConsistency()`（**需要 uid**）登记成**精确项**，
 * 而 `onFileCreatedForFuse(String)` 的签名里**一个 int 参数都没有** ⇒
 * `resolveUid` 的两条路径（静态 uidIndex、运行时扫描 int ≥ 10000）**都必然失败** ⇒
 * 每次调用打一条 `W … cannot find uid` 后直接返回：纯死 hook + 日志洪泛。
 *
 * 旧门只对启发式匹配生效（`!match.exact && uidIndex < 0`），以"精确项已人工核对"为由豁免，
 * 而这一条恰恰核对错了。下面的用例把新规则钉死。
 */
class FuseUidGateTest {

    private val stringType = arrayOf<Class<*>>(String::class.java)
    private val stringIntType =
        arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!)
    private val stringIntegerType =
        arrayOf<Class<*>>(String::class.java, Integer::class.java)
    private val intStringType =
        arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, String::class.java)
    private val noParamType = arrayOf<Class<*>>()

    @Test
    fun `uidIndex 非负一律放行`() {
        assertNull(skipReasonForUnresolvableUid(true, 1, stringIntType))
        assertNull(skipReasonForUnresolvableUid(false, 1, stringIntType))
        assertNull(skipReasonForUnresolvableUid(false, 0, intStringType))
    }

    @Test
    fun `启发式且无 uid 下标时跳过`() {
        // (String, String) 没有 int：静态定位不到，也不信任按值猜的运行时回退。
        val types = arrayOf<Class<*>>(String::class.java, String::class.java)
        assertEqualsReason("heuristic", skipReasonForUnresolvableUid(false, -1, types))
    }

    @Test
    fun `精确项且签名无 int 参数时跳过`() {
        // ★ 本次修复：onFileCreatedForFuse(String) 走的就是这条。
        val reason = skipReasonForUnresolvableUid(true, -1, stringType)
        assertNotNull("精确项没有 int 参数时必须跳过（可证明的死 hook）", reason)
        assertTrue("原因需点明是精确项", reason!!.contains("exact entry"))
    }

    @Test
    fun `精确项且存在 int 参数时放行`() {
        // 不误伤：int 在别的下标（如 openWithFuse 的 uid 未静态定位）时仍交给运行时回退。
        assertNull(skipReasonForUnresolvableUid(true, -1, stringIntType))
        assertNull(skipReasonForUnresolvableUid(true, -1, intStringType))
    }

    @Test
    fun `Integer 包装类型也算 int 参数`() {
        assertNull(skipReasonForUnresolvableUid(true, -1, stringIntegerType))
    }

    @Test
    fun `空参数表在精确项下跳过`() {
        assertNotNull(skipReasonForUnresolvableUid(true, -1, noParamType))
    }

    @Test
    fun `hasIntParameter 只认 int 与 Integer`() {
        assertTrue(hasIntParameter(stringIntType))
        assertTrue(hasIntParameter(stringIntegerType))
        assertTrue(!hasIntParameter(stringType))
        val longType = arrayOf<Class<*>>(String::class.java, Long::class.javaPrimitiveType!!)
        assertTrue("long 不参与 uid 运行时回退", !hasIntParameter(longType))
    }

    private fun assertEqualsReason(prefix: String, actual: String?) {
        assertNotNull("应给出跳过原因", actual)
        assertTrue("原因应以 $prefix 开头，实际=$actual", actual!!.startsWith(prefix))
    }
}
