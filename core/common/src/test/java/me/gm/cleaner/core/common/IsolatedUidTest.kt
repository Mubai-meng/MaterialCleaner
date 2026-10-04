package me.gm.cleaner.core.common

import me.gm.cleaner.core.common.RuntimeFileUtils.isIsolatedUid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隔离 uid 判定：webview 沙盒等隔离进程朝生暮死，挂载选择必须排除，
 * 否则注定撞上 PID 复用门，制造失败噪声。
 */
class IsolatedUidTest {

    @Test
    fun `普通应用uid非隔离`() {
        with(RuntimeFileUtils) {
            assertFalse(10273.isIsolatedUid())
            assertFalse(1000.isIsolatedUid())
            assertFalse(100345.isIsolatedUid())
        }
    }

    @Test
    fun `隔离区间uid判定为隔离`() {
        with(RuntimeFileUtils) {
            assertTrue(99001.isIsolatedUid())
            assertTrue(99999.isIsolatedUid())
            assertTrue(199001.isIsolatedUid())
        }
    }
}
