package me.gm.cleaner.runtime.mediaprovider.hook.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [XpCompat] 单测 —— 它是 legacy XposedHelpers 的替代品，语义必须逐条钉死。
 *
 * 覆盖：类查找失败抛 ClassNotFoundError、方法查找沿父类链且失败抛 NoSuchMethodError、
 * 私有方法调用与宿主异常包装（InvocationTargetError 继承 Error，非受检）、
 * 静态方法调用、私有字段访问与沿父类链回退。
 */
class XpCompatTest {

    open class Base {
        private val baseSecret: String = "base"
        private fun baseHidden(): String = baseSecret
        fun inheritedVisible(): String = baseHidden()
    }

    class Sample : Base() {
        private var field: String = "init"
        private fun echo(s: String): String = s
        private fun add(a: Int, b: Int): Int = a + b
        private fun boom(): String = throw IllegalArgumentException("from host")

        fun publicEcho(s: String): String = echo(s)

        companion object {
            @JvmStatic
            fun staticConcat(a: String, b: String): String = a + b
        }
    }

    @Test
    fun `findClass 成功与失败`() {
        assertNotNull(XpCompat.findClass("java.lang.String", javaClass.classLoader))
        try {
            XpCompat.findClass("com.example.NoSuchClass", javaClass.classLoader)
            fail("应抛 ClassNotFoundError")
        } catch (e: XpCompat.ClassNotFoundError) {
            assertTrue(e.cause is ClassNotFoundException)
        }
    }

    @Test
    fun `findMethodExact 可解析私有方法并沿父类链回退`() {
        val m = XpCompat.findMethodExact(Sample::class.java, "echo", String::class.java)
        assertEquals("echo", m.name)

        // 父类私有方法：沿 superclass 链必须能找到（Fuse 门依赖此行为）。
        assertNotNull(XpCompat.findMethodExact(Sample::class.java, "baseHidden"))
        assertNotNull(XpCompat.findMethodExact(Sample::class.java, "staticConcat",
            String::class.java, String::class.java))

        try {
            XpCompat.findMethodExact(Sample::class.java, "baseHidden", String::class.java)
            fail("签名不匹配应抛 NoSuchMethodError")
        } catch (e: NoSuchMethodError) {
            // expected
        }
    }

    @Test
    fun `callMethod 调用私有方法与装箱基本类型`() {
        val s = Sample()
        assertEquals("hi", XpCompat.callMethod(s, "echo", "hi"))
        assertEquals(3, XpCompat.callMethod(s, "add", 1, 2))
        assertEquals("base", XpCompat.callMethod(s, "baseHidden"))
    }

    @Test
    fun `callMethod 把宿主异常包装为 InvocationTargetError且继承 Error`() {
        val s = Sample()
        try {
            XpCompat.callMethod(s, "boom")
            fail("应抛 InvocationTargetError")
        } catch (e: XpCompat.InvocationTargetError) {
            assertTrue("InvocationTargetError 必须是 Error（非受检，对齐 legacy）", e is Error)
            assertTrue("必须保留原始宿主异常", e.cause is IllegalArgumentException)
            assertEquals("from host", e.cause!!.message)
        }
    }

    @Test
    fun `callStaticMethod 调用静态方法`() {
        assertEquals("ab", XpCompat.callStaticMethod(Sample::class.java, "staticConcat", "a", "b"))
    }

    @Test
    fun `getObjectField 读私有字段并沿父类链回退`() {
        val s = Sample()
        assertEquals("init", XpCompat.getObjectField(s, "field"))
        assertEquals("base", XpCompat.getObjectField(s, "baseSecret"))
        try {
            XpCompat.getObjectField(s, "noSuchField")
            fail("应抛 NoSuchFieldError")
        } catch (e: NoSuchFieldError) {
            // expected
        }
    }

    @Test
    fun `精确匹配优先于 assignable 回退`() {
        // 同一方法名两个重载：应命中形参类型完全一致的那一个。
        val o = Overloaded()
        assertEquals("string", XpCompat.callMethod(o, "pick", "x"))
        assertEquals("object", XpCompat.callMethod(o, "pick", Any()))
    }

    class Overloaded {
        fun pick(s: String): String = "string"
        fun pick(o: Any): String = "object"
    }
}
