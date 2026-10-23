package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BridgeRetryableFailurePolicy] 的边界与反向用例。
 *
 * 核心约束：**认不出的一律不降级**。降级错误会把真故障静音，比多一条 E 严重得多。
 */
class BridgeRetryableFailurePolicyTest {

    private val iae = "java.lang.IllegalArgumentException"

    @Test
    fun `Unknown authority 是可重试的启动竞态`() {
        assertTrue(
            BridgeRetryableFailurePolicy.isRetryable(
                iae, "Unknown authority me.gm.cleaner.hooks_bridge",
            ),
        )
    }

    @Test
    fun `各类 provider 未找到话术都认`() {
        listOf(
            "Failed to find provider info for me.gm.cleaner.hooks_bridge",
            "Unable to find provider me.gm.cleaner.hooks_bridge",
            "Could not find provider: me.gm.cleaner.hooks_bridge",
            "Failed to find provider me.gm.cleaner.hooks_bridge",
        ).forEach {
            assertTrue("应命中：$it", BridgeRetryableFailurePolicy.isRetryable(iae, it))
        }
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(
            BridgeRetryableFailurePolicy.isRetryable(
                iae, "unknown authority me.gm.cleaner.hooks_bridge",
            ),
        )
    }

    /** 反向：消息里提到 authority 但不是「找不到」⇒ 不算可重试。 */
    @Test
    fun `authority 已解析但权限被拒不算可重试`() {
        assertFalse(
            BridgeRetryableFailurePolicy.isRetryable(
                iae, "Permission Denial: opening provider me.gm.cleaner.hooks_bridge",
            ),
        )
    }

    /** 反向：只有在 Provider 查找这一种异常类型下才降级。 */
    @Test
    fun `非 IllegalArgumentException 即使话术相同也不降级`() {
        assertFalse(
            BridgeRetryableFailurePolicy.isRetryable(
                "java.lang.IllegalStateException", "Unknown authority me.gm.cleaner.hooks_bridge",
            ),
        )
        assertFalse(
            BridgeRetryableFailurePolicy.isRetryable(
                "android.os.DeadObjectException", "Unknown authority me.gm.cleaner.hooks_bridge",
            ),
        )
    }

    @Test
    fun `空值输入不降级`() {
        assertFalse(BridgeRetryableFailurePolicy.isRetryable(null, "Unknown authority x"))
        assertFalse(BridgeRetryableFailurePolicy.isRetryable("", "Unknown authority x"))
        assertFalse(BridgeRetryableFailurePolicy.isRetryable(iae, null))
        assertFalse(BridgeRetryableFailurePolicy.isRetryable(iae, ""))
    }

    /** 反向：真故障（超时 / 远端异常）必须留在 E。 */
    @Test
    fun `真故障不降级`() {
        listOf(
            iae to "Timeout talking to provider",
            iae to "Bundle has wrong type",
            "android.os.RemoteException" to "linkToDeath failed",
        ).forEach { (cls, msg) ->
            assertFalse("不应命中：$msg", BridgeRetryableFailurePolicy.isRetryable(cls, msg))
        }
    }
}
