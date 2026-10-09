package me.gm.cleaner.runtime.server.orchestrator

import android.os.Binder
import android.util.Log
import api.SystemService
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.SnapshotPublisher
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProvider Hook 注册缺失时的受控恢复策略。
 *
 * 处理“App Bridge 可达，但 MediaProvider 进程没有重新注册 Hook Binder”的半断链状态。
 */
class MediaProviderRecoveryStrategy(
    private val server: CleanerServer,
) {
    private companion object {
        private const val TAG = "MediaProviderRecoveryStrategy"
        private const val MEDIA_PROVIDER_AUTHORITY = "media"
        private const val MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD = 3
        private const val MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS = 60_000L
        private const val MEDIA_PROVIDER_WAKE_DELAY_MS = 1_000L
        // probe-first 有界探测：成功条件是 Hook Binder 真正可用，而非 Provider 非空。
        private const val MEDIA_PROVIDER_PROBE_TIMEOUT_MS = 2_000L
        private const val MEDIA_PROVIDER_PROBE_POLL_MS = 250L
        private val MEDIA_PROVIDER_PACKAGE_CANDIDATES = arrayOf(
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.providers.media",
        )
    }

    private var consecutiveMediaProviderHookMissing: Int = 0
    private var lastMediaProviderRecoveryAt: Long = 0L
    private var mediaProviderWakeScheduled: Boolean = false
    // 单轮恢复并发保护：同一时刻仅一轮有效恢复流程，多 watchdog 信号不并行强杀。
    private val recoveryInFlight = AtomicBoolean(false)

    data class RecoverySnapshot(
        val consecutiveHookMissing: Int = 0,
        val lastRecoveryAt: Long = 0L,
        val recoveryCooldownRemainingMs: Long = 0L,
        val mediaProviderWakeScheduled: Boolean = false,
        val recoveryInFlight: Boolean = false,
    )

    fun snapshot(now: Long = System.currentTimeMillis()): RecoverySnapshot {
        val cooldownRemaining = if (lastMediaProviderRecoveryAt <= 0L) {
            0L
        } else {
            (MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS - (now - lastMediaProviderRecoveryAt))
                .coerceAtLeast(0L)
        }
        return RecoverySnapshot(
            consecutiveHookMissing = consecutiveMediaProviderHookMissing,
            lastRecoveryAt = lastMediaProviderRecoveryAt,
            recoveryCooldownRemainingMs = cooldownRemaining,
            mediaProviderWakeScheduled = mediaProviderWakeScheduled,
            recoveryInFlight = recoveryInFlight.get(),
        )
    }

    fun recoverIfHookRegistrationMissing(): Boolean {
        if (MediaProviderHookGateway.isMediaProviderHookConnected()) {
            if (consecutiveMediaProviderHookMissing > 0) {
                Log.i(TAG, "MediaProvider hook reconnected after " +
                        "$consecutiveMediaProviderHookMissing missing checks")
            }
            consecutiveMediaProviderHookMissing = 0
            return false
        }

        consecutiveMediaProviderHookMissing++
        if (consecutiveMediaProviderHookMissing < MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD) {
            Log.w(TAG, "MediaProvider hook missing while bridge is alive: " +
                    "check=$consecutiveMediaProviderHookMissing/" +
                    MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD)
            return false
        }

        val now = System.currentTimeMillis()
        val sinceLastRecovery = now - lastMediaProviderRecoveryAt
        if (sinceLastRecovery < MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS) {
            Log.w(TAG, "MediaProvider hook still missing, recovery cooldown active: " +
                    "${MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS - sinceLastRecovery}ms remaining")
            return true
        }

        lastMediaProviderRecoveryAt = now
        consecutiveMediaProviderHookMissing = 0
        MediaProviderHookGateway.resetNativeStateForReconnect()

        if (!recoveryInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "MediaProvider hook recovery already in flight, skipping duplicate round")
            return true
        }
        try {
            // Phase 1（probe-first）：缺 Hook 不等价于进程已坏，先有界 wake/probe；
            // 成功条件必须是 Hook Binder 真正可用，Provider 非空/进程存活不算恢复。
            wakeMediaProvider()
            if (awaitHookBinder()) {
                Log.i(TAG, "MediaProvider hook recovered by probe, skipping force-stop")
                SnapshotPublisher.publishAll()
                runCatching {
                    MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
                }.onFailure {
                    Log.w(TAG, "refresh MediaProvider hook after probe failed", it)
                }
                return false
            }

            // Phase 2：probe 未恢复才执行有限 force-stop，再 wake 并验证。
            val stoppedPackages = forceStopMediaProviderPackages()
            if (stoppedPackages.isEmpty()) {
                Log.w(TAG, "MediaProvider hook recovery requested, but no MediaProvider package was found")
            } else {
                Log.w(TAG, "MediaProvider hook recovery: force-stopped ${stoppedPackages.joinToString()}")
            }
            scheduleMediaProviderWake()
            return true
        } finally {
            recoveryInFlight.set(false)
        }
    }

    /**
     * 有界等待 Hook Binder 真正恢复：轮询连接态 + ping，超时即判 probe 失败。
     * Provider 可用但 Binder 不可用不得判定恢复成功。
     */
    private fun awaitHookBinder(): Boolean {
        val deadline = System.currentTimeMillis() + MEDIA_PROVIDER_PROBE_TIMEOUT_MS
        while (true) {
            if (MediaProviderHookGateway.isMediaProviderHookConnected() &&
                MediaProviderHookGateway.pingBinder()
            ) {
                return true
            }
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(MEDIA_PROVIDER_PROBE_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun forceStopMediaProviderPackages(): Set<String> {
        val userIds = SystemService.getUserIdsNoThrow()
        val packages = linkedSetOf<String>()

        for (packageName in MEDIA_PROVIDER_PACKAGE_CANDIDATES) {
            if (userIds.any { userId ->
                    SystemService.getPackageInfoNoThrow(packageName, 0, userId) != null
                }) {
                packages += packageName
            }
        }

        for (userId in userIds) {
            for (packageName in packages) {
                runCatching {
                    SystemService.forceStopPackageNoThrow(packageName, userId)
                }.onFailure {
                    Log.w(TAG, "force-stop MediaProvider failed: package=$packageName user=$userId", it)
                }
            }
        }
        return packages
    }

    private fun scheduleMediaProviderWake() {
        if (mediaProviderWakeScheduled) return
        mediaProviderWakeScheduled = true
        server.handler.postDelayed({
            mediaProviderWakeScheduled = false
            wakeMediaProvider()
            if (MediaProviderHookGateway.pingBinder() &&
                    MediaProviderHookGateway.isMediaProviderHookConnected()) {
                SnapshotPublisher.publishAll()
                runCatching {
                    MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
                }.onFailure {
                    Log.w(TAG, "refresh MediaProvider hook after wake failed", it)
                }
            }
        }, MEDIA_PROVIDER_WAKE_DELAY_MS)
    }

    private fun wakeMediaProvider() {
        for (userId in SystemService.getUserIdsNoThrow()) {
            val token = Binder()
            var acquired = false
            try {
                val provider = SystemService.getContentProviderExternal(
                    MEDIA_PROVIDER_AUTHORITY,
                    userId,
                    token,
                    TAG,
                )
                acquired = provider != null
                Log.i(TAG, "wakeMediaProvider: user=$userId acquired=$acquired")
            } catch (tr: Throwable) {
                Log.w(TAG, "wakeMediaProvider failed for user=$userId", tr)
            } finally {
                if (acquired) {
                    runCatching {
                        SystemService.removeContentProviderExternal(MEDIA_PROVIDER_AUTHORITY, token)
                    }.onFailure {
                        Log.w(TAG, "removeContentProviderExternal failed for user=$userId", it)
                    }
                }
            }
        }
    }
}
