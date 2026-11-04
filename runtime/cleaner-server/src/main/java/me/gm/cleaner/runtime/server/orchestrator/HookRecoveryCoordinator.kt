package me.gm.cleaner.runtime.server.orchestrator

import android.os.SystemClock
import android.util.Log
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.SnapshotPublisher
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway

/**
 * MediaProvider Hook 与 FUSE Native Hook 的恢复协调器。
 */
class HookRecoveryCoordinator(
    private val server: CleanerServer,
    private val mediaProviderRecoveryStrategy: MediaProviderRecoveryStrategy,
    private val onReconnectReady: () -> Unit,
) {
    private companion object {
        private const val TAG = "HookRecoveryCoordinator"

        /**
         * 同一失配状态下的重试间隔（健康检查每 2s 跑一轮）。
         *
         * native 挂载点推送若**永久**失败，会出现 nativeGen 恒 0 而 snapshotGen > 0 的
         * 稳定失配。原实现的去重条件是 `nativeGen == lastNativeHookCheckGeneration
         * && nativeGen > 0` —— 其中的 `> 0` 恰好把 0 排除，条件永不成立 ⇒ 每 2s 记一条
         * W 并重跑一次 refreshPolicyFromDataBus()，无上界。
         * 改为：状态跃迁立即重试，同一状态按本间隔退避重试。
         */
        private const val NATIVE_MISMATCH_RETRY_INTERVAL_MS = 30_000L
    }

    private var hooksRetryCount = 0
    private var hooksReconnectScheduled = false
    private val hooksRetryDelays = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

    /** 重试预算上限：`hooksRetryDelays` 走两轮。单一真源，避免快照与门控各算一遍。 */
    private val maxHookRetries: Int
        get() = hooksRetryDelays.size * 2

    private var lastNativeHookCheckGeneration: Long = 0L

    /** 上次触发刷新时观察到的 snapshot 侧代数；-1 表示尚未触发过。 */
    private var lastMismatchSnapshotGeneration: Long = -1L

    /** 上次触发刷新的单调时钟时刻；0 表示从未触发。 */
    private var lastNativeRefreshAtMs: Long = 0L

    data class RecoverySnapshot(
        val hooksRetryCount: Int = 0,
        val hooksReconnectScheduled: Boolean = false,
        val maxHookRetries: Int = 0,
        val lastNativeHookCheckGeneration: Long = 0L,
    )

    fun snapshot(): RecoverySnapshot = RecoverySnapshot(
        hooksRetryCount = hooksRetryCount,
        hooksReconnectScheduled = hooksReconnectScheduled,
        maxHookRetries = maxHookRetries,
        lastNativeHookCheckGeneration = lastNativeHookCheckGeneration,
    )

    fun onHooksBinderDied() {
        // 死掉的 Binder 是 **HooksBridgeProvider（me.gm.cleaner 应用进程）** 提供的桥，
        // 既不是 MediaProvider 侧的 Java Hook，也不是 FUSE native hook —— 应用进程
        // 被用户从最近任务划掉、或被 ColorOS 回收（常态）就会走到这里。
        //
        // 旧实现把它记成 "MEDIA_PROVIDER_JAVA_HOOK / FUSE_NATIVE_HOOK -> UNAVAILABLE
        // (binderDied = process death)" 并调用 resetNativeStateForReconnect()，
        // 直接引发误判链：
        //   桥换代 → 新桥手上没有 MediaProvider Binder → isMediaProviderHookConnected()=false
        //   → MediaProviderRecoveryStrategy 累计到阈值 → force-stop MediaProvider
        //   → 系统级进程死亡波 → 前台应用被连带杀掉（实测 10:54:50 / 11:02:13 两次）。
        //
        // 正确语义：这只是控制面桥断了，重连即可。native 挂载点状态由 MediaProvider
        // 进程持有，应用进程的死亡不可能使其失效，因此**不能**清零 generation 缓存。
        Log.w(TAG, "onHooksBinderDied: hooks bridge process died " +
                "(owner = me.gm.cleaner app process). MediaProvider Java hook and native " +
                "mount state are unaffected -> reconnecting bridge only")
        scheduleHooksReconnect()
    }

    private fun scheduleHooksReconnect() {
        if (hooksReconnectScheduled) return
        hooksReconnectScheduled = true

        val delay = hooksRetryDelays[hooksRetryCount.coerceAtMost(hooksRetryDelays.size - 1)]
        Log.i(TAG, "scheduleHooksReconnect: attempt #${hooksRetryCount + 1}, delay=${delay}ms")

        server.handler.postDelayed({
            hooksReconnectScheduled = false
            performHooksReconnect()
        }, delay)
    }

    private fun performHooksReconnect() {
        Log.i(TAG, "performHooksReconnect: attempting...")

        val hooksReconnected = MediaProviderHookGateway.tryReconnect(server)
        if (hooksReconnected) {
            hooksRetryCount = 0
            Log.i(TAG, "performHooksReconnect: Phase 1 OK - Binder reconnected")
        } else {
            // 预算必须**钳制**在上限。onHooksBinderDied() 不受预算门控 ——
            // 预算耗尽后再来一次死亡事件仍会再排一轮重连；旧实现于是把计数推到
            // 11、12… 无上界，对外报告出 `hooksRetryCount=11 > maxHookRetries=10`
            // 这种自相矛盾的状态（2026-11-02 诊断包 A 实测），
            // 让"预算耗尽"与"还有余量"再也无法区分。
            hooksRetryCount = HookRetryBudget.next(hooksRetryCount, maxHookRetries)
            Log.w(
                TAG,
                "performHooksReconnect: Phase 1 FAILED (attempt $hooksRetryCount/$maxHookRetries)",
            )
            if (HookRetryBudget.shouldContinue(hooksRetryCount, maxHookRetries)) {
                scheduleHooksReconnect()
            } else {
                Log.e(TAG, "performHooksReconnect: max retries ($maxHookRetries) exceeded")
            }
            return
        }

        val dataBusReady = SnapshotPublisher.publishAll()
        if (!dataBusReady) {
            Log.w(TAG, "performHooksReconnect: Phase 2 - DataBus snapshots not published")
        }

        try {
            MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
        } catch (e: RuntimeException) {
            Log.e(TAG, "performHooksReconnect: Phase 3 - Hook refresh failed", e)
        }

        onReconnectReady()
        Log.i(TAG, "performHooksReconnect: done")
    }

    fun nativeHookHealthCheck() {
        if (!MediaProviderHookGateway.pingBinder()) return

        if (mediaProviderRecoveryStrategy.recoverIfHookRegistrationMissing()) return

        val nativeGen = MediaProviderHookGateway.nativeMountPointsGeneration()
        val snapshotGen = MediaProviderHookGateway.configuredMountPointsSnapshotGeneration()

        if (nativeGen >= snapshotGen && snapshotGen > 0) {
            lastNativeHookCheckGeneration = nativeGen
            lastMismatchSnapshotGeneration = snapshotGen
            return
        }

        // 去重口径是「(nativeGen, snapshotGen) 组合 + 最小间隔」，不能只看 nativeGen：
        // nativeGen 恒为 0 时 `nativeGen > 0` 永不成立，永久失配会退化成每 2s 一刷。
        val now = SystemClock.elapsedRealtime()
        val stateChanged = nativeGen != lastNativeHookCheckGeneration ||
                snapshotGen != lastMismatchSnapshotGeneration
        if (!stateChanged && now - lastNativeRefreshAtMs < NATIVE_MISMATCH_RETRY_INTERVAL_MS) {
            return
        }
        lastNativeHookCheckGeneration = nativeGen
        lastMismatchSnapshotGeneration = snapshotGen
        lastNativeRefreshAtMs = now

        Log.w(TAG, "nativeHookHealthCheck: nativeGen=$nativeGen < snapshotGen=$snapshotGen, " +
                "triggering refresh" +
                if (stateChanged) "" else " (same mismatch state, retry after backoff)")
        MediaProviderHookGateway.refreshPolicyFromDataBus()
    }
}

/**
 * Hook 重连重试预算的纯函数（无 android 依赖，便于 JVM 单测）。
 *
 * 与 [HookRecoveryCoordinator] 的私有成员分开是刻意的：`private companion object`
 * 内的成员在单测里不可见（本项目已踩过），而这里的两条规则恰好是最需要钉死的部分。
 */
internal object HookRetryBudget {

    /**
     * 推进一次失败计数，**永不越过** [max]。
     *
     * 为什么必须钳制：[HookRecoveryCoordinator.onHooksBinderDied] 不受预算门控，
     * 预算耗尽后再来一次死亡事件仍会再排一轮重连；若不钳制，计数会无上界地增长，
     * 快照里就会出现 `hooksRetryCount > maxHookRetries` 这种自相矛盾的状态。
     */
    fun next(current: Int, max: Int): Int = if (current >= max) max else current + 1

    /** 是否还允许再排一轮（钳制后口径：`count == max` 即预算耗尽）。 */
    fun shouldContinue(count: Int, max: Int): Boolean = count < max
}
