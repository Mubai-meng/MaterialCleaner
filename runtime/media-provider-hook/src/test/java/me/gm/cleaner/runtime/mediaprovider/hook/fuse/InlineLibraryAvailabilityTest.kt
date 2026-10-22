package me.gm.cleaner.runtime.mediaprovider.hook.fuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InlineLibraryAvailability] 的行为契约。
 *
 * 这些断言对应第33批现场：旧进程持有已被覆盖安装删除的 base.apk 路径，
 * `loadLibrary("inline")` 必然失败。判定的关键性质是**幂等且永久** ——
 * 一旦不可达，同进程内不再尝试、不再重复写日志。
 */
class InlineLibraryAvailabilityTest {

    @Test
    fun `初始状态为可用`() {
        val state = InlineLibraryAvailability()
        assertFalse(state.isUnavailable())
        assertNull(state.reason())
    }

    @Test
    fun `首次标记返回 true 并记录原因`() {
        val state = InlineLibraryAvailability()
        assertTrue(state.markUnavailable("couldn't find \"libinline.so\""))
        assertTrue(state.isUnavailable())
        assertEquals("couldn't find \"libinline.so\"", state.reason())
    }

    @Test
    fun `重复标记返回 false 且不覆盖首次原因`() {
        val state = InlineLibraryAvailability()
        assertTrue(state.markUnavailable("first"))
        assertFalse(state.markUnavailable("second"))
        assertEquals("first", state.reason())
    }

    @Test
    fun `空白原因归一为兜底文案`() {
        val state = InlineLibraryAvailability()
        assertTrue(state.markUnavailable("   "))
        assertEquals("UnsatisfiedLinkError", state.reason())

        val nullReason = InlineLibraryAvailability()
        assertTrue(nullReason.markUnavailable(null))
        assertEquals("UnsatisfiedLinkError", nullReason.reason())
    }

    @Test
    fun `超长原因被截断到上限`() {
        val long = "x".repeat(InlineLibraryAvailability.MAX_REASON_LENGTH + 50)
        assertEquals(
            InlineLibraryAvailability.MAX_REASON_LENGTH,
            InlineLibraryAvailability.normalizeReason(long).length,
        )
    }

    @Test
    fun `原因首尾空白被裁掉`() {
        assertEquals("boom", InlineLibraryAvailability.normalizeReason("  boom\n"))
    }

    @Test
    fun `日志闸门只放行一次`() {
        val state = InlineLibraryAvailability()
        assertTrue(state.consumeLogAllowance())
        assertFalse(state.consumeLogAllowance())
        assertFalse(state.consumeLogAllowance())
    }

    @Test
    fun `日志闸门与不可达状态相互独立`() {
        val state = InlineLibraryAvailability()
        // 先取日志额度（例如别处已经报过一行），不影响后续标记不可达。
        assertTrue(state.consumeLogAllowance())
        assertTrue(state.markUnavailable("boom"))
        assertTrue(state.isUnavailable())
        assertFalse(state.consumeLogAllowance())
    }
}
