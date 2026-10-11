package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读规则（「不可访问的路径」）匹配判据单测。
 *
 * 核心要钉死的是**任意深度子孙**这条语义 —— 旧判据只覆盖一层，
 * 而应用真实写入路径永远比规则深至少两层，正是用户报「设置完无效」的直接原因。
 */
class ReadOnlyPathMatcherTest {

    private val rule = "/storage/emulated/0/Pictures"

    @Test
    fun `规则自身命中`() {
        assertTrue(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/Pictures"))
    }

    @Test
    fun `直接子项命中`() {
        assertTrue(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/Pictures/x.jpg"))
    }

    @Test
    fun `任意深度子孙命中`() {
        assertTrue(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/Pictures/taobao/x.jpg"))
        assertTrue(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/Pictures/a/b/c/d.bin"))
    }

    @Test
    fun `相邻同名前缀不得命中`() {
        assertFalse(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/PicturesX/x.jpg"))
        assertFalse(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/PicturesX"))
    }

    @Test
    fun `规则外的兄弟目录不得命中`() {
        assertFalse(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/DCIM/Camera/x.jpg"))
        // 规则是 Pictures 的父目录的情形：/storage/emulated/0 不该被 Pictures 命中
        assertFalse(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/pictures/x.jpg"))
        assertTrue(ReadOnlyPathMatcher.matches("/storage/emulated/0/DCIM", "/storage/emulated/0/dcim/Camera/a.jpg"))
    }

    @Test
    fun `规则结尾斜杠被吃掉`() {
        assertTrue(ReadOnlyPathMatcher.matches("/storage/emulated/0/Pictures/", "/storage/emulated/0/Pictures/x.jpg"))
        assertTrue(ReadOnlyPathMatcher.matches("/storage/emulated/0/Pictures/", "/storage/emulated/0/Pictures"))
        assertFalse(ReadOnlyPathMatcher.matches("/storage/emulated/0/Pictures/", "/storage/emulated/0/PicturesX/a"))
    }

    @Test
    fun `路径结尾斜杠按目录处理`() {
        assertTrue(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/Pictures/"))
        assertTrue(ReadOnlyPathMatcher.matches(rule, "/storage/emulated/0/Pictures/taobao/"))
    }

    @Test
    fun `重复斜杠被规范化`() {
        assertTrue(ReadOnlyPathMatcher.matches("//storage//emulated///0/Pictures", "/storage/emulated/0/Pictures/x.jpg"))
        assertTrue(ReadOnlyPathMatcher.matches(rule, "//storage//emulated///0///Pictures///a.jpg"))
    }

    @Test
    fun `空输入不得命中`() {
        assertFalse(ReadOnlyPathMatcher.matches("", "/storage/emulated/0"))
        assertFalse(ReadOnlyPathMatcher.matches(rule, ""))
        assertFalse(ReadOnlyPathMatcher.matches("", ""))
    }

    @Test
    fun `集合版本取任意命中`() {
        val rules = listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Download")
        assertTrue(ReadOnlyPathMatcher.matchesAny(rules, "/storage/emulated/0/Download/app/a.apk"))
        assertTrue(ReadOnlyPathMatcher.matchesAny(rules, "/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertFalse(ReadOnlyPathMatcher.matchesAny(rules, "/storage/emulated/0/Pictures/a.jpg"))
    }

    @Test
    fun `空集合恒不命中`() {
        assertFalse(ReadOnlyPathMatcher.matchesAny(emptyList(), "/storage/emulated/0"))
        assertFalse(ReadOnlyPathMatcher.matchesAny(emptySet(), "/storage/emulated/0"))
    }

    @Test
    fun `normalize保持前导斜杠并合并重复斜杠`() {
        assertEquals("/storage/emulated/0", ReadOnlyPathMatcher.normalize("//storage//emulated///0///"))
        assertEquals("/", ReadOnlyPathMatcher.normalize("///"))
        assertEquals("", ReadOnlyPathMatcher.normalize(""))
        assertEquals("relative/path", ReadOnlyPathMatcher.normalize("relative//path/"))
    }
}
