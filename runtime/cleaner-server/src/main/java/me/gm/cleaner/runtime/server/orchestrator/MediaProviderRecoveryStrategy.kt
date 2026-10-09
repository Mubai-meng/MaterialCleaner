package me.gm.cleaner.runtime.server.orchestrator

import android.os.Binder
import android.util.Log
import api.SystemService
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.SnapshotPublisher
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import me.gm.cleaner.runtime.server.process.ProcessIdentity
import org.json.JSONArray
import org.json.JSONObject
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
    // Fix 2：episode 以 Hook 可用性为中心。connected→missing 开启，确认连上才结束；
    // 桥重连、server 重启都不重置 destructiveRounds（重启延续由落盘总账保证）。
    private var episodeStartMs: Long = 0L
    private var destructiveRounds: Int = 0
    private var lastRound: MediaProviderRecoveryPolicy.RoundRecord? = null
    private var lastWakeOnlyWakeAt: Long = 0L

    data class RecoverySnapshot(
        val consecutiveHookMissing: Int = 0,
        val lastRecoveryAt: Long = 0L,
        val recoveryCooldownRemainingMs: Long = 0L,
        val mediaProviderWakeScheduled: Boolean = false,
        val recoveryInFlight: Boolean = false,
        val episodeStartMs: Long = 0L,
        val destructiveRounds: Int = 0,
        val wakeOnlyMode: Boolean = false,
    )

    init {
        // 重启延续：不断电熔断总账；Hook 确认连上后才清零（见 connected 分支）。
        runCatching { loadLedger() }.onFailure {
            Log.w(TAG, "Failed to load recovery ledger, starting fresh", it)
        }
    }

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
            episodeStartMs = episodeStartMs,
            destructiveRounds = destructiveRounds,
            wakeOnlyMode = destructiveRounds >= MediaProviderRecoveryPolicy.MAX_DESTRUCTIVE_ROUNDS,
        )
    }

    fun recoverIfHookRegistrationMissing(): Boolean {
        if (MediaProviderHookGateway.isMediaProviderHookConnected()) {
            if (consecutiveMediaProviderHookMissing > 0 || episodeStartMs > 0L) {
                Log.i(TAG, "MediaProvider hook reconnected after " +
                        "$consecutiveMediaProviderHookMissing missing checks")
            }
            consecutiveMediaProviderHookMissing = 0
            episodeStartMs = 0L
            if (destructiveRounds > 0) {
                destructiveRounds = 0
                lastRound = null
                DataBus.clearRecoveryLedger()
            }
            return false
        }

        val now = System.currentTimeMillis()
        if (episodeStartMs <= 0L) {
            episodeStartMs = now
        }
        consecutiveMediaProviderHookMissing++
        if (consecutiveMediaProviderHookMissing < MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD) {
            Log.w(TAG, "MediaProvider hook missing while bridge is alive: " +
                    "check=$consecutiveMediaProviderHookMissing/" +
                    MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD)
            return false
        }

        if (!recoveryInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "MediaProvider hook recovery already in flight, skipping duplicate round")
            return true
        }
        try {
            val currentPids = scanMediaProcessInstances()
            val thresholdReached =
                consecutiveMediaProviderHookMissing >= MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD
            when (MediaProviderRecoveryPolicy.decide(
                now,
                MediaProviderRecoveryPolicy.State(
                    hookConnected = false,
                    episodeStartMs = episodeStartMs,
                    thresholdReached = thresholdReached,
                    lastRound = lastRound,
                    destructiveRounds = destructiveRounds,
                    currentMediaPids = currentPids,
                ),
            )) {
                MediaProviderRecoveryPolicy.Decision.CONNECTED -> return false
                MediaProviderRecoveryPolicy.Decision.PROBE_ONLY -> {
                    probeOnlyRound()
                    return true
                }
                MediaProviderRecoveryPolicy.Decision.WAKE_ONLY -> {
                    wakeOnlyRound(now)
                    return true
                }
                MediaProviderRecoveryPolicy.Decision.MAY_FORCE_STOP -> {
                    return forceStopRound(now, currentPids)
                }
            }
        } finally {
            recoveryInFlight.set(false)
        }
    }

    /** 非破坏性 probe：wake + 有界等待 Hook Binder；成功则发布刷新并收敛。 */
    private fun probeOnlyRound(): Boolean {
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
        lastMediaProviderRecoveryAt = System.currentTimeMillis()
        return true
    }

    /** 熔断态低频 wake：复用 60s 冷却节拍，禁止强杀。 */
    private fun wakeOnlyRound(now: Long) {
        if (now - lastWakeOnlyWakeAt < MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS) return
        lastWakeOnlyWakeAt = now
        lastMediaProviderRecoveryAt = now
        Log.w(TAG, "MediaProvider hook still missing (wake-only, " +
                "destructiveRounds=$destructiveRounds), probing lightly")
        wakeMediaProvider()
    }

    /**
     * 有限 force-stop 轮：probe 失败后执行，记录进程实例身份并落盘总账。
     * 同进程实例硬约束最多 1 次（由 policy 保证，此处只记录）。
     */
    private fun forceStopRound(now: Long, currentPids: Map<Int, Long>): Boolean {
        // 下线前再 probe 一次：状态可能在决策后已变化。
        // 注意：resetNativeStateForReconnect 必须在本 probe 失败后才执行——
        // 提前重置会让“probe 成功跳过强杀”也付出 Native 重建代价，
        // 与“无额外影响”的设计目标矛盾。
        wakeMediaProvider()
        if (awaitHookBinder()) {
            Log.i(TAG, "MediaProvider hook recovered by pre-stop probe, skipping force-stop")
            SnapshotPublisher.publishAll()
            runCatching {
                MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
            }.onFailure {
                Log.w(TAG, "refresh MediaProvider hook after probe failed", it)
            }
            return false
        }
        // 确认进入破坏路径后才重置需要重建的 Native 状态。
        MediaProviderHookGateway.resetNativeStateForReconnect()
        val stoppedPackages = forceStopMediaProviderPackages()
        if (stoppedPackages.isEmpty()) {
            Log.w(TAG, "MediaProvider hook recovery requested, but no MediaProvider package was found")
        } else {
            Log.w(TAG, "MediaProvider hook recovery: force-stopped ${stoppedPackages.joinToString()}")
        }
        consecutiveMediaProviderHookMissing = 0
        lastMediaProviderRecoveryAt = now
        lastRound = MediaProviderRecoveryPolicy.RoundRecord(
            timeMs = now,
            targetPids = currentPids.keys.toSet(),
            targetStarts = currentPids.toMap(),
        )
        destructiveRounds++
        persistLedger()
        scheduleMediaProviderWake()
        return true
    }

    /** 观测当前媒体进程实例（PID→starttime）；不可读的 PID 剔除（宁可不杀）。 */
    private fun scanMediaProcessInstances(): Map<Int, Long> {
        return try {
            SystemService.getRunningAppProcessesNoThrow()
                .asSequence()
                .filter { proc ->
                    proc.pkgList?.any { MEDIA_PROVIDER_PACKAGE_CANDIDATES.contains(it) } == true
                }
                .mapNotNull { proc ->
                    val start = ProcessIdentity
                        .currentInstance(proc.pid)?.startTime ?: return@mapNotNull null
                    proc.pid to start
                }
                .toMap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan media process instances", e)
            emptyMap()
        }
    }

    private fun persistLedger() {
        val json = try {
            JSONObject()
                .put("destructiveRounds", destructiveRounds)
                .put("lastRoundAt", lastRound?.timeMs ?: 0L)
                .put("lastRoundPids", JSONArray(lastRound?.targetPids?.toList() ?: emptyList<Int>()))
                .put("lastRoundStarts", JSONObject(
                    lastRound?.targetStarts?.mapKeys { it.key.toString() }
                        ?: emptyMap<String, Long>(),
                ))
                .toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build recovery ledger", e)
            return
        }
        if (!DataBus.writeRecoveryLedger(json)) {
            Log.w(TAG, "Failed to persist recovery ledger")
        }
    }

    private fun loadLedger() {
        val json = DataBus.readRecoveryLedger()
            ?: return
        val root = JSONObject(json)
        destructiveRounds = root.optInt("destructiveRounds", 0)
        val lastAt = root.optLong("lastRoundAt", 0L)
        val pids = mutableSetOf<Int>()
        val starts = mutableMapOf<Int, Long>()
        val arr = root.optJSONArray("lastRoundPids")
        if (arr != null) {
            for (i in 0 until arr.length()) pids.add(arr.optInt(i))
        }
        val obj = root.optJSONObject("lastRoundStarts")
        obj?.keys()?.forEach { key ->
            key.toIntOrNull()?.let { pid -> starts[pid] = obj.optLong(key) }
        }
        if (destructiveRounds > 0 || lastAt > 0L) {
            lastRound = MediaProviderRecoveryPolicy.RoundRecord(
                timeMs = lastAt, targetPids = pids, targetStarts = starts,
            )
            Log.i(TAG, "Restored recovery ledger: destructiveRounds=$destructiveRounds")
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
