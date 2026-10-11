package me.gm.cleaner.runtime.mediaprovider.hook.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HookParam] 的 veto 语义单测。
 *
 * 为什么必须测：veto（{@code setResult/setThrowable}）是 libxposed 拦截器链模型下
 * 「短路原方法」的唯一载体 —— FuseJavaGate 的只读拒绝（EPERM）与 open 句柄拒绝
 * 完全依赖它。语义错（例如后调用者不能覆盖先调用者）会让拒绝静默失效，
 * 而产物级探针只能看到"字面量存在"，抓不到这个缺陷。
 */
class HookParamTest {

    @Test
    fun `args 与 thisObject 原样透传`() {
        val self = Any()
        val args = arrayOf<Any?>("p0", 1, null)
        val p = HookParam(self, args)
        assertSame(self, p.thisObject)
        assertSame(args, p.args)
        assertFalse(p.hasVeto())
        assertFalse(p.isVetoResult())
        assertFalse(p.isVetoThrowable())
    }

    @Test
    fun `setResult 置位 vetoResult 并返回该结果`() {
        val p = HookParam(null, arrayOfNulls(1))
        p.setResult(42)
        assertTrue(p.hasVeto())
        assertTrue(p.isVetoResult())
        assertFalse(p.isVetoThrowable())
        assertEquals(42, p.getResult())
    }

    @Test
    fun `setThrowable 置位 vetoThrowable 并返回该异常`() {
        val p = HookParam(null, arrayOfNulls(1))
        val t = java.io.FileNotFoundException("EPERM")
        p.setThrowable(t)
        assertTrue(p.hasVeto())
        assertFalse(p.isVetoResult())
        assertTrue(p.isVetoThrowable())
        assertSame(t, p.getThrowable())
    }

    @Test
    fun `后调用者胜 与旧 XC_MethodHook 一致`() {
        val p = HookParam(null, arrayOfNulls(1))
        p.setResult(1)
        p.setThrowable(RuntimeException("denied"))
        assertFalse("后调用的 setThrowable 必须取消 vetoResult", p.isVetoResult())
        assertTrue(p.isVetoThrowable())

        p.setResult(2)
        assertTrue("后调用的 setResult 必须取消 vetoThrowable", p.isVetoResult())
        assertFalse(p.isVetoThrowable())
    }

    @Test
    fun `args 槽位可写 供包装层经 chain_proceed 生效`() {
        val args = arrayOf<Any?>("/storage/emulated/0/a", "/storage/emulated/0/b")
        val p = HookParam(null, args)
        p.args[0] = "/mounted/a"
        assertEquals("/mounted/a", args[0])
        assertEquals("/storage/emulated/0/b", args[1])
        assertNull("未设置 veto 时 getResult 应为 null", p.getResult())
    }
}
