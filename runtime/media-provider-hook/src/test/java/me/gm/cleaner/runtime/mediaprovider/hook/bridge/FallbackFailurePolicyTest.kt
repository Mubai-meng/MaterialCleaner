package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 40 批 P3-1：启动竞态 vs 桥接真断的区分。
 *
 * 现场数据（同一次开机，elapsedRealtime 可直接对齐）：
 * - 写事件：`atElapsed=33631` → 11:36:27.324
 * - server 注入回调：`setCleanerServerBinder` @ 11:36:28.914
 * ⇒ MediaProvider 先于 server 就绪 1.6 s，`callback==null` 必然发生。
 *
 * MediaProvider 的 SELinux 域写不了 DataBus 目录，兜底是这个窗口里唯一路径且注定失败。
 * 这类失败每开机固定出现一次，进 hook_errors 会把真信号淹掉。
 */
class FallbackFailurePolicyTest {

    @Test
    fun `从未有回调即启动竞态不入库`() {
        // 第 40 批真实现场：MediaProvider 先起，第一次写事件时还没有回调。
        assertFalse(FallbackFailurePolicy.shouldReport(everHadCallback = false))
    }

    @Test
    fun `曾有回调后兜底失败必须入库`() {
        // 回调丢失 = 桥接真断（server 重启/被杀），兜底又写不进 ⇒ 事件会真丢，必须留痕。
        assertTrue(FallbackFailurePolicy.shouldReport(everHadCallback = true))
    }

    /**
     * 反向保护：判定只依赖「是否曾有回调」，不得因为参数名相似而变成恒 true/false。
     * 这条同时锁住「两个分支都可达」——只断言单个值时容易把恒等实现的 bug 放过去。
     */
    @Test
    fun `两个分支都可达`() {
        val never = FallbackFailurePolicy.shouldReport(false)
        val had = FallbackFailurePolicy.shouldReport(true)
        assertTrue("两个分支结果必须不同，否则策略退化为常量", never != had)
        assertFalse(never)
        assertTrue(had)
    }
}
