package me.gm.cleaner.runtime.server.orchestrator

import android.os.Binder
import android.util.Log
import api.SystemService
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.SnapshotPublisher
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway

/**
 * 单次服务生命周期内允许的 force-stop 恢复次数上限。
 *
 * force-stop 是"重启 MediaProvider 以让它重新注册 Hook Binder"的核选项。
 * 当 Hook Binder 没注册的原因**不在** MediaProvider 进程侧（例如模块 hook 点未生效），
 * 重启永远不会收敛：实测设备上 Cleaner server 每 60s 强杀一次 MediaProvider
 * （`ActivityManager: Force stopping com.android.providers.media.module appid=10326
 * ... from pid <server>`，4 分钟 4 次），同时持续打断相册/下载的 MediaStore 访问。
 * 超过上限后必须停止破坏性动作，只留明确告警。
 */
private const val MEDIA_PROVIDER_MAX_RECOVERIES = 3

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

        /**
         * 桥换代后的稳定期。
         *
         * 应用进程换新（用户把 me.gm.cleaner 从最近任务划掉、或 ColorOS 回收）后，
         * 新桥手上必然没有 MediaProvider 的 Binder，`isMediaProviderHookConnected()`
         * 会立刻返回 false —— 这是**过渡态**，MediaProvider 需要时间向新桥重新注册
         * （正常情况下由 MediaProviderHooksService 的桥存活探针自动触发）。
         * 稳定期内不采信"未连接"，避免把每次应用进程回收都当成故障。
         */
        private const val BRIDGE_RECONNECT_SETTLE_MS = 30_000L

        /**
         * 触发 force-stop 所需的"连续缺失"驻留时长。
         *
         * 真正的半断链会长期保持，过渡态不会。误判的代价是杀掉前台应用
         * （实测 10:54:50 与 11:02:13 两次：划掉 me.gm.cleaner → force-stop
         * media.module → 系统级进程死亡波 → 前台应用被连带杀死），
         * 因此把门槛抬到远高于过渡态的长度：force-stop 只作为最后的收容量手段。
         */
        private const val MEDIA_PROVIDER_MISSING_DWELL_MS = 5 * 60_000L
        private val MEDIA_PROVIDER_PACKAGE_CANDIDATES = arrayOf(
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.providers.media",
        )
    }

    private var consecutiveMediaProviderHookMissing: Int = 0
    private var lastMediaProviderRecoveryAt: Long = 0L
    private var mediaProviderWakeScheduled: Boolean = false

    /** 已观察到的桥代数；变化说明桥刚换代，需要重新起算观察窗口。 */
    private var observedBridgeEpoch: Long = 0L

    /** 当前桥代数的观察起点（墙钟），用于计算稳定期是否已过。 */
    private var bridgeObservedAt: Long = 0L

    /** 连续缺失的起始时间；0 表示当前没有缺失。 */
    private var hookMissingSince: Long = 0L

    /** 已执行的 force-stop 恢复次数；Hook 一旦连上就清零，允许后续真实的半断链再恢复。 */
    private var mediaProviderRecoveryCount: Int = 0
    private var recoveryExhaustedLogged: Boolean = false

    data class RecoverySnapshot(
        val consecutiveHookMissing: Int = 0,
        val lastRecoveryAt: Long = 0L,
        val recoveryCooldownRemainingMs: Long = 0L,
        val mediaProviderWakeScheduled: Boolean = false,
        val recoveryCount: Int = 0,
        val maxRecoveries: Int = MEDIA_PROVIDER_MAX_RECOVERIES,
        val recoveryExhausted: Boolean = false,
        val bridgeEpoch: Long = 0L,
        val hookMissingSince: Long = 0L,
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
            recoveryCount = mediaProviderRecoveryCount,
            recoveryExhausted = mediaProviderRecoveryCount >= MEDIA_PROVIDER_MAX_RECOVERIES,
            bridgeEpoch = observedBridgeEpoch,
            hookMissingSince = hookMissingSince,
        )
    }

    fun recoverIfHookRegistrationMissing(): Boolean {
        val now = System.currentTimeMillis()

        // ── 桥代数门控 ──
        // 桥 = me.gm.cleaner 应用进程里的 HooksBridgeProvider。它一旦换代
        // （用户划掉应用 / ColorOS 回收 / 服务端重连），新桥手上必然没有
        // MediaProvider 的 Binder，isMediaProviderHookConnected() 立刻变成 false。
        // 这是过渡态：MediaProvider 需要时间向新桥重新注册。此处重新起算观察窗口，
        // 而不是把它计成"hook 缺失"。
        val bridgeEpoch = MediaProviderHookGateway.bridgeConnectionEpoch()
        if (bridgeEpoch != observedBridgeEpoch) {
            if (observedBridgeEpoch != 0L) {
                Log.i(TAG, "Hooks bridge reconnected (epoch $observedBridgeEpoch -> " +
                        "$bridgeEpoch); restarting MediaProvider hook watch " +
                        "(settle ${BRIDGE_RECONNECT_SETTLE_MS}ms)")
            }
            observedBridgeEpoch = bridgeEpoch
            bridgeObservedAt = now
            consecutiveMediaProviderHookMissing = 0
            hookMissingSince = 0L
            return false
        }

        if (MediaProviderHookGateway.isMediaProviderHookConnected()) {
            if (consecutiveMediaProviderHookMissing > 0) {
                Log.i(TAG, "MediaProvider hook reconnected after " +
                        "$consecutiveMediaProviderHookMissing missing checks")
            }
            consecutiveMediaProviderHookMissing = 0
            hookMissingSince = 0L
            // Hook 重新连上说明恢复路径有效，重置预算，允许后续真实的半断链再恢复。
            mediaProviderRecoveryCount = 0
            recoveryExhaustedLogged = false
            return false
        }

        // 刚换代的桥在稳定期内不被采信：这段时间的"未连接"只说明重新注册还没完成。
        if (now - bridgeObservedAt < BRIDGE_RECONNECT_SETTLE_MS) {
            return false
        }

        if (hookMissingSince == 0L) {
            hookMissingSince = now
        }
        consecutiveMediaProviderHookMissing++
        if (consecutiveMediaProviderHookMissing < MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD) {
            Log.w(TAG, "MediaProvider hook missing while bridge is alive: " +
                    "check=$consecutiveMediaProviderHookMissing/" +
                    MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD)
            return false
        }

        if (mediaProviderRecoveryCount >= MEDIA_PROVIDER_MAX_RECOVERIES) {
            if (!recoveryExhaustedLogged) {
                recoveryExhaustedLogged = true
                Log.e(TAG, "MediaProvider hook recovery abandoned after " +
                        "$mediaProviderRecoveryCount force-stop(s): restarting MediaProvider " +
                        "does not make it register the Hook Binder, so this recovery loop can " +
                        "never converge. Root cause is inside the MediaProvider process " +
                        "(module hook target / FUSE native hook init), not process staleness. " +
                        "Stop force-stopping to avoid breaking MediaStore for gallery/downloads.")
            }
            return true
        }

        // ── 持续缺失窗口 ──
        // 桥已稳定（过了稳定期）但 hook 仍持续缺失，才可能是真正的半断链。
        // 过渡态不会持续这么久；healthCheck 每 2s 一次，因此只在首次越过阈值时记一条。
        val missingFor = now - hookMissingSince
        if (missingFor < MEDIA_PROVIDER_MISSING_DWELL_MS) {
            if (consecutiveMediaProviderHookMissing == MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD) {
                Log.w(TAG, "MediaProvider hook missing for ${missingFor}ms while bridge is " +
                        "stable (epoch $observedBridgeEpoch); holding force-stop until " +
                        "${MEDIA_PROVIDER_MISSING_DWELL_MS}ms of continuous missing")
            }
            return false
        }

        val sinceLastRecovery = now - lastMediaProviderRecoveryAt
        if (sinceLastRecovery < MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS) {
            Log.w(TAG, "MediaProvider hook still missing, recovery cooldown active: " +
                    "${MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS - sinceLastRecovery}ms remaining")
            return true
        }

        lastMediaProviderRecoveryAt = now
        consecutiveMediaProviderHookMissing = 0
        hookMissingSince = 0L
        MediaProviderHookGateway.resetNativeStateForReconnect()

        val stoppedPackages = forceStopMediaProviderPackages()
        if (stoppedPackages.isEmpty()) {
            Log.w(TAG, "MediaProvider hook recovery requested, but no MediaProvider package was found")
        } else {
            mediaProviderRecoveryCount++
            Log.w(TAG, "MediaProvider hook recovery: force-stopped ${stoppedPackages.joinToString()} " +
                    "(recovery $mediaProviderRecoveryCount/$MEDIA_PROVIDER_MAX_RECOVERIES)")
        }
        scheduleMediaProviderWake()
        return true
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
