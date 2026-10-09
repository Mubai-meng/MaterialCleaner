package me.gm.cleaner.runtime.server.consumer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EventConsumePolicy 纯函数单测：不依赖 Android 运行时与 DataBus 文件。
 */
class EventConsumePolicyTest {

    @Test
    fun `未达预算重试`() {
        assertEquals(
            EventConsumePolicy.TransientDecision.RETRY,
            EventConsumePolicy.decideTransient(1, 0),
        )
        assertEquals(
            EventConsumePolicy.TransientDecision.RETRY,
            EventConsumePolicy.decideTransient(2, 0),
        )
    }

    @Test
    fun `达预算升级隔离`() {
        assertEquals(
            EventConsumePolicy.TransientDecision.QUARANTINE,
            EventConsumePolicy.decideTransient(3, 0),
        )
        assertEquals(
            EventConsumePolicy.TransientDecision.QUARANTINE,
            EventConsumePolicy.decideTransient(5, 0),
        )
    }

    @Test
    fun `基础设施连续故障熔断升级`() {
        assertEquals(
            EventConsumePolicy.TransientDecision.RETRY,
            EventConsumePolicy.decideTransient(10, 3),
        )
        assertEquals(
            EventConsumePolicy.TransientDecision.RETRY,
            EventConsumePolicy.decideTransient(10, 5),
        )
    }

    @Test
    fun `基础设施故障识别`() {
        assertTrue(EventConsumePolicy.isInfrastructureFault(RuntimeException("Binder transaction failed")))
        assertTrue(EventConsumePolicy.isInfrastructureFault(RuntimeException("database is locked")))
        assertTrue(EventConsumePolicy.isInfrastructureFault(RuntimeException("DeadObjectException")))
        assertTrue(EventConsumePolicy.isInfrastructureFault(RuntimeException("no space left on device")))
    }

    @Test
    fun `类型名优先于消息文本`() {
        // 消息非空但不含关键词，类名是 DeadObjectException → 仍应识别
        // （旧实现用 message ?: className，消息非空时跳过类名检查，会漏判）
        assertTrue(EventConsumePolicy.isInfrastructureFault(
            RuntimeException("some opaque wrapper text", DeadObjectExceptionStub())
        ))
        // "transaction failed" 带空格也能识别（旧实现只匹配无空格变体）
        assertTrue(EventConsumePolicy.isInfrastructureFault(
            RuntimeException("transaction failed remotely")
        ))
    }

    /** 模拟 android.os.DeadObjectException 的类名（纯 JVM 不可直接引用 Android 类型） */
    private class DeadObjectExceptionStub : RuntimeException("opaque")

    @Test
    fun `业务异常非基础设施故障`() {
        assertFalse(EventConsumePolicy.isInfrastructureFault(RuntimeException("JSONException: bad value")))
        assertFalse(EventConsumePolicy.isInfrastructureFault(IllegalArgumentException("missing field")))
    }
}
