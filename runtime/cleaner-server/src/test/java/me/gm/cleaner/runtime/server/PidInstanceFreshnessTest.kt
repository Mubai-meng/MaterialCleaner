package me.gm.cleaner.runtime.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PidInstanceFreshness.isStale` 纯函数单测（5.5.7 P2）。
 *
 * 背景：第 38 批普查把 3 个已退出的夸克 pid 判成 NOT_MOUNTED，
 * 而同一时刻 UI 显示「已挂载」。根因是 `check_mounts` 对**陈旧 pid** 采样：
 * pid 被内核复用后，`/proc/<pid>/mountinfo` 读到的是新进程的内容（空数组）。
 *
 * 本函数负责在采样前识别这类 pid，全部保守处理为「跳过」而不是「记 UNKNOWN」，
 * 因为记 UNKNOWN 会让 `vfsUnknownPids` 虚高，同样误导排查方向。
 */
class PidInstanceFreshnessTest {

    private val alwaysAlive: (Int) -> Boolean = { true }
    private val neverAlive: (Int) -> Boolean = { false }

    @Test
    fun `进程已消失判陈旧`() {
        assertTrue(
            PidInstanceFreshness.isStale(
                pid = 17878,
                snapshot = mapOf(17878 to 100L),
                alive = neverAlive,
                startTimeOf = { 100L },
            ),
        )
    }

    @Test
    fun `starttime读不到判陈旧`() {
        // 竞态退出：/proc/<pid> 还在但 stat 打不开，无法证明身份未变 ⇒ 保守跳过。
        assertTrue(
            PidInstanceFreshness.isStale(
                pid = 17878,
                snapshot = mapOf(17878 to 100L),
                alive = alwaysAlive,
                startTimeOf = { null },
            ),
        )
    }

    @Test
    fun `starttime不一致判pid已复用`() {
        // 核心场景：进程退出后 pid 被新进程接管，starttime 必然变化。
        assertTrue(
            PidInstanceFreshness.isStale(
                pid = 17878,
                snapshot = mapOf(17878 to 100L),
                alive = alwaysAlive,
                startTimeOf = { 999L },
            ),
        )
    }

    @Test
    fun `starttime一致判仍为同一实例`() {
        assertFalse(
            PidInstanceFreshness.isStale(
                pid = 17878,
                snapshot = mapOf(17878 to 100L),
                alive = alwaysAlive,
                startTimeOf = { 100L },
            ),
        )
    }

    @Test
    fun `快照里没有该pid时按存活放行`() {
        // 快照是遍历前采的，若某 pid 采集时读不到、之后才可读，不应因此被永久跳过；
        // 此时只能退化为「存活 + stat 可读」即放行，与旧行为一致。
        assertFalse(
            PidInstanceFreshness.isStale(
                pid = 4242,
                snapshot = emptyMap(),
                alive = alwaysAlive,
                startTimeOf = { 777L },
            ),
        )
    }

    @Test
    fun `快照为空且进程已消失仍判陈旧`() {
        // 存活判定优先于快照，缺快照不能掩盖「进程已死」。
        assertTrue(
            PidInstanceFreshness.isStale(
                pid = 4242,
                snapshot = emptyMap(),
                alive = neverAlive,
                startTimeOf = { 777L },
            ),
        )
    }
}
