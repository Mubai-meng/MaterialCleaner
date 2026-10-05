package me.gm.cleaner.runtime.server.orchestrator

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
    }

    private var hooksRetryCount = 0
    private var hooksReconnectScheduled = false
    private val hooksRetryDelays = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    private var lastNativeHookCheckGeneration: Long = 0L

    data class RecoverySnapshot(
        val hooksRetryCount: Int = 0,
        val hooksReconnectScheduled: Boolean = false,
        val maxHookRetries: Int = 0,
        val lastNativeHookCheckGeneration: Long = 0L,
    )

    fun snapshot(): RecoverySnapshot = RecoverySnapshot(
        hooksRetryCount = hooksRetryCount,
        hooksReconnectScheduled = hooksReconnectScheduled,
        maxHookRetries = hooksRetryDelays.size * 2,
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
            hooksRetryCount++
            Log.w(TAG, "performHooksReconnect: Phase 1 FAILED (attempt $hooksRetryCount)")
            if (hooksRetryCount < hooksRetryDelays.size * 2) {
                scheduleHooksReconnect()
            } else {
                Log.e(TAG, "performHooksReconnect: max retries (${hooksRetryDelays.size * 2}) exceeded")
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
            return
        }
        if (nativeGen == lastNativeHookCheckGeneration && nativeGen > 0) {
            return
        }
        lastNativeHookCheckGeneration = nativeGen

        Log.w(TAG, "nativeHookHealthCheck: nativeGen=$nativeGen < snapshotGen=$snapshotGen, triggering refresh")
        MediaProviderHookGateway.refreshPolicyFromDataBus()
    }
}
