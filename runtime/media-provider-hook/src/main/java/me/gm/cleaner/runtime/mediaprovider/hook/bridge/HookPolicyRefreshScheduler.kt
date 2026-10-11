package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import android.os.SystemClock
import android.util.Log
import me.gm.cleaner.runtime.mediaprovider.hook.policy.HookPolicyCache
import me.gm.cleaner.runtime.mediaprovider.hook.policy.HookRefreshIntervalPolicy
import me.gm.cleaner.runtime.mediaprovider.hook.fuse.FuseNativePolicyAdapter
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicLong

/**
 * HookPolicy 刷新调度器。
 *
 * 运行在 MediaProvider 进程中，使用 Timer 低频轮询
 * DataBus signal 文件，检测到变更时刷新 HookPolicyCache。
 *
 * 使用 java.util.Timer 避免 hidden-api 模块的 android.os.Handler stub 遮蔽问题。
 *
 * ## 设计
 * - Timer 守护线程每 [POLL_INTERVAL_MS] 检查一次
 * - 检查策略、只读、configured_mount_points signal
 * - 检测到变更后从 DataBus 拉取新快照并同步 native
 * - 不在 MediaProvider 中创建独立 native 线程或 inotify watcher
 */
object HookPolicyRefreshScheduler {
    private const val TAG = "HookPolicyRefreshScheduler"

    /** 稳态轮询间隔：与旧实现一致，长期开销不变。 */
    private const val POLL_INTERVAL_MS = HookRefreshIntervalPolicy.STEADY_INTERVAL_MS

    private val timer = Timer("HookPolicyRefresh", true)

    @Volatile
    private var running = false

    /** 收敛锚点：调度器启动时刻 / 最近一次 configured generation 前进时刻。 */
    private val startedAtElapsed = AtomicLong(0L)
    private val lastProgressAtElapsed = AtomicLong(0L)
    private val lastSeenMountPointsGeneration = AtomicLong(0L)

    fun start() {
        if (running) return
        running = true
        startedAtElapsed.set(SystemClock.elapsedRealtime())
        Log.i(
            TAG,
            "Started, pollInterval=${POLL_INTERVAL_MS}ms, " +
                    "convergeInterval=${HookRefreshIntervalPolicy.CONVERGE_INTERVAL_MS}ms " +
                    "within ${HookRefreshIntervalPolicy.CONVERGENCE_WINDOW_MS}ms of progress",
        )
        // ⚠️ 首轮立即执行。旧实现 initialDelay = POLL_INTERVAL_MS，使「hook 侧首次把
        // configured_mount_points 推给 native」固定晚 5s —— 而这正是状态页里
        // 「FUSE 兼容层 · 状态过期」每次都比其他层慢一截的主因
        // （第 15 批日志实测：19:16:58 server 发布 → 19:17:06 hook 才应用 gen 3）。
        scheduleNext(0L)
    }

    /**
     * 自调度链：每轮结束后按 [HookRefreshIntervalPolicy] 决定的间隔排下一轮。
     *
     * 之所以不用 `scheduleAtFixedRate`，是因为间隔要随「是否仍在收敛」变化：
     * 收敛期 2s、稳态 5s。Timer 单线程串行，从任务内调度下一任务是安全的。
     */
    private fun scheduleNext(delayMs: Long) {
        if (!running) return
        timer.schedule(object : TimerTask() {
            override fun run() {
                try {
                    pollAndRefresh()
                } catch (e: Exception) {
                    Log.e(TAG, "pollAndRefresh failed", e)
                }
                if (running) {
                    scheduleNext(nextIntervalMillis())
                }
            }
        }, delayMs)
    }

    private fun nextIntervalMillis(): Long = HookRefreshIntervalPolicy.nextInterval(
        nowElapsed = SystemClock.elapsedRealtime(),
        anchorElapsed = maxOf(startedAtElapsed.get(), lastProgressAtElapsed.get()),
    )

    /**
     * 配置真在前进了（configured generation 前进）就把收敛锚点前移，
     * 让快速轮询跟着实际的收敛活动走，而不是只看启动时刻。
     * 前进检测是一次内存读 + 单调比较，不在 DataBus 上新增任何 IO。
     */
    private fun noteProgressIfAny() {
        val gen = HookPolicyCache.nativeMountPointsGeneration
        while (true) {
            val seen = lastSeenMountPointsGeneration.get()
            if (gen <= seen) return
            if (lastSeenMountPointsGeneration.compareAndSet(seen, gen)) {
                lastProgressAtElapsed.set(SystemClock.elapsedRealtime())
                return
            }
        }
    }

    private fun pollAndRefresh() {
        if (HookPolicyCache.isStale()) {
            HookPolicyCache.refreshChangedSnapshotsFromDataBus()
        }
        FuseNativePolicyAdapter.retryInlineHookInitializationIfDue()
        // 走轮询专用入口：语义未变时按保活下限落盘，而非每轮无条件重写。
        // 详见 NativeHookStatus.HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS 的取值约束。
        NativeHookStatus.publishSnapshotFromPoll()
        noteProgressIfAny()
    }
}
