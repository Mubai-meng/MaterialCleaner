package me.gm.cleaner.runtime.server.orchestrator

import android.os.Binder
import android.util.Log
import api.SystemService
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.SnapshotPublisher
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import me.gm.cleaner.runtime.server.process.ProcessIdentity
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
    /**
     * 总账腐败态：总账存在但不可确认（非常规文件/读取异常/JSON 损坏）。
     * true 时 Policy 强制 PROBE_ONLY；只有总账文件被成功清除后才允许退出。
     * @Volatile 保证 watchdog/Handler 线程可见性。
     */
    @Volatile
    private var ledgerCorrupted: Boolean = false

    data class RecoverySnapshot(
        val consecutiveHookMissing: Int = 0,
        val lastRecoveryAt: Long = 0L,
        val recoveryCooldownRemainingMs: Long = 0L,
        val mediaProviderWakeScheduled: Boolean = false,
        val recoveryInFlight: Boolean = false,
        val episodeStartMs: Long = 0L,
        val destructiveRounds: Int = 0,
        val wakeOnlyMode: Boolean = false,
        val ledgerCorrupted: Boolean = false,
    )

    init {
        // 重启延续：不断电熔断总账；Hook 确认连上且清账成功后才清零（见 connected 分支）。
        // Corrupted 不归零，进保守探测态。
        runCatching { loadLedger() }.onFailure {
            Log.w(TAG, "Failed to load recovery ledger, entering conservative probe", it)
            ledgerCorrupted = true
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
            ledgerCorrupted = ledgerCorrupted,
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
            if (destructiveRounds > 0 || lastRound != null || ledgerCorrupted) {
                // 安全重置：只有总账文件真正清除成功，才允许清零内存与腐败态；
                // clear 失败必须保持原状态，否则内存已清而文件仍在，下次重启误判。
                if (DataBus.clearRecoveryLedger()) {
                    destructiveRounds = 0
                    lastRound = null
                    ledgerCorrupted = false
                } else {
                    Log.w(TAG, "Failed to clear recovery ledger, keeping rounds=$destructiveRounds " +
                            "corrupted=$ledgerCorrupted")
                }
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
            // 扫描语义贯穿策略层：Unavailable 与 Success(empty) 是两种语义，
            // 不得在中间层退化成普通 Map 让策略重新混淆。
            val currentScan = scanMediaProcessInstances()
            val thresholdReached =
                consecutiveMediaProviderHookMissing >= MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD
            val decision = MediaProviderRecoveryPolicy.decide(
                now,
                MediaProviderRecoveryPolicy.State(
                    hookConnected = false,
                    episodeStartMs = episodeStartMs,
                    thresholdReached = thresholdReached,
                    lastRound = lastRound,
                    destructiveRounds = destructiveRounds,
                    mediaScan = currentScan,
                    ledgerCorrupted = ledgerCorrupted,
                ),
            )
            when (decision) {
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
                    return forceStopRound(now, currentScan)
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
    private fun forceStopRound(now: Long, decisionScan: MediaProcessScan): Boolean {
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
        // 单次执行前扫描贯穿准入、记账与操作：同实例守卫检查的快照必须
        // 与最终操作依据同一份快照，否则两份独立扫描的竞态窗口会使守卫失效。
        // 决策时观测结果不等于执行时对象，不能直接当事实记录。
        // 无法确认身份或确认无活进程时必须中止本轮破坏操作——“不能确认身份则禁止盲杀”、
        // “无活进程只做唤醒+重探测”是硬约束。包级清理需独立准入，不搭本轮便车。
        val targets = scanObservedTargets()
        if (targets.isEmpty()) {
            Log.w(TAG, "Pre-stop scan unavailable or empty, aborting destructive round " +
                    "to keep the blind-kill guard (will keep probing)")
            return true
        }
        // 同一份快照派生 pid 集合，复用唯一转换点做守卫与记账。
        val preStopObserved =
            PreStopScanPolicy.resolve(MediaProcessScan.Success(targets.pidMap()))
                ?: run {
                    Log.w(TAG, "Pre-stop identity unresolvable, aborting destructive round")
                    return true
                }
        // 执行前复检同实例守卫：决策时扫描可能已过期（进程在 wake/等待期间更替），
        // 只有执行前实例才是真正的杀灭对象，必须用它而非决策快照做最终准入。
        if (!MediaProviderRecoveryPolicy.mayTargetInstances(lastRound, preStopObserved)) {
            Log.w(TAG, "Pre-stop instances overlap last round's targets, " +
                    "aborting destructive round to keep the per-instance limit")
            return true
        }
        // 防御性复检：decide 与执行之间内存状态理论上单线程不变，
        // 但腐败态/熔断是硬门禁，在破坏前最后一刻再确认一次。
        if (ledgerCorrupted ||
            destructiveRounds >= MediaProviderRecoveryPolicy.MAX_DESTRUCTIVE_ROUNDS
        ) {
            Log.w(TAG, "Destructive guard tripped before force-stop " +
                    "(corrupted=$ledgerCorrupted rounds=$destructiveRounds), aborting")
            return true
        }
        // 操作目标裁决：观测 (package,userId) ∩ 该用户下已安装。
        // 无观测依据的包/用户不杀——包级 API 杀伤面不得大于准入证据。
        val operations = MediaProviderRecoveryPolicy.resolveOperationTargets(
            targets,
        ) { packageName, userId ->
            SystemService.getPackageInfoNoThrow(packageName, 0, userId) != null
        }
        if (operations.isEmpty()) {
            Log.w(TAG, "No operable targets (observed but not installed), " +
                    "aborting destructive round without consuming a round")
            return true
        }
        // 预先记账：先把“已保留的尝试轮次”落盘，成功后才允许破坏。
        // 杀后崩溃会多记不少记（偏保守），绝不能少记（偏危险）。
        // destructiveRounds 语义为尝试次数：即使最终未找到已安装包同样计轮。
        val nextRounds = destructiveRounds + 1
        val nextRound = MediaProviderRecoveryPolicy.RoundRecord(
            timeMs = now,
            targetPids = preStopObserved.keys.toSet(),
            targetStarts = preStopObserved.toMap(),
        )
        if (!persistLedger(nextRounds, nextRound)) {
            Log.w(TAG, "Failed to persist recovery ledger, aborting force-stop to keep fail-closed")
            return true
        }
        destructiveRounds = nextRounds
        lastRound = nextRound
        // 语义固定为“破坏操作前观测到的目标实例”。平台不返回实际终止清单，
        // 且 forceStopPackage 是包级操作、与扫描瞬间的 PID 集合存在固有竞态窗口，
        // 因此这是准入证据而非身份保证；操作集合已收敛为观测交集（见上），
        // 残余窗口仅为扫描到执行之间出现的新实例。
        // 确认进入破坏路径后才重置需要重建的 Native 状态。
        // 内存准入态已在预先记账时更新，此处只执行破坏与结果记录，不再二次计轮。
        MediaProviderHookGateway.resetNativeStateForReconnect()
        val stoppedPackages = forceStopMediaProviderPackages(operations)
        if (stoppedPackages.isEmpty()) {
            Log.w(TAG, "MediaProvider hook recovery requested, but no MediaProvider package was found")
        } else {
            Log.w(TAG, "MediaProvider hook recovery: force-stopped ${stoppedPackages.joinToString()}")
        }
        consecutiveMediaProviderHookMissing = 0
        lastMediaProviderRecoveryAt = now
        // destructiveRounds 语义固定为“破坏性尝试次数”（见预先记账注释）：
        // 诊断侧必须按尝试次数解读，不得当作成功强杀次数。
        scheduleMediaProviderWake()
        return true
    }

    /**
     * 观测当前媒体进程实例（PID→starttime）；不可读的 PID 剔除（宁可不杀）。
     *
     * 返回可区分"成功扫描"与"无法确认"：AMS 查询失败时早先返回 emptyMap，
     * 与"确实没有进程"混淆，导致调用方拿陈旧快照冒充操作目标。
     */
    private fun scanMediaProcessInstances(): MediaProcessScan {
        return try {
            val instances = SystemService.getRunningAppProcessesNoThrow()
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
            MediaProcessScan.Success(instances)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan media process instances", e)
            MediaProcessScan.Unavailable
        }
    }

    /**
     * 观测目标明细（执行层证据）：扫描时刻确实发现的进程实例。
     *
     * uid→userId 推导经 PreStopScanPolicy.userIdOf（= uid/100000），
     * 与 UserHandle.getUserId 一致；不可解析的 PID 剔除（宁可不杀）。
     * 本表只证明“当时看到过”，最终 API 影响包+用户范围，
     * 两者差集在 forceStopMediaProviderPackages 中日志留痕。
     */
    private fun scanObservedTargets(): List<ObservedTarget> {
        return try {
            SystemService.getRunningAppProcessesNoThrow()
                .asSequence()
                .filter { proc ->
                    proc.pkgList?.any { MEDIA_PROVIDER_PACKAGE_CANDIDATES.contains(it) } == true
                }
                .mapNotNull { proc ->
                    val pkg = proc.pkgList?.firstOrNull {
                        MEDIA_PROVIDER_PACKAGE_CANDIDATES.contains(it)
                    } ?: return@mapNotNull null
                    val start = ProcessIdentity
                        .currentInstance(proc.pid)?.startTime ?: return@mapNotNull null
                    ObservedTarget(
                        packageName = pkg,
                        userId = PreStopScanPolicy.userIdOf(proc.uid),
                        pid = proc.pid,
                        startTime = start,
                    )
                }
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan observed targets", e)
            emptyList()
        }
    }

    /**
     * 组装总账 JSON（委托纯策略函数；保留本方法以兼容既有调用点）。
     *
     * 纯组装逻辑见 MediaProviderRecoveryPolicy.buildLedgerJson（可单测）；
     * 文件原子写与 fsync 语义依赖设备，由设备验收覆盖。
     */
    internal fun buildLedgerJson(
        rounds: Int,
        round: MediaProviderRecoveryPolicy.RoundRecord?,
    ): String? = MediaProviderRecoveryPolicy.buildLedgerJson(rounds, round)

    /**
     * 预先记账：把“已保留的尝试轮次”落盘，成功返回 true。
     *
     * 原子性说明：依赖 DataBusRecoveryLedger 的 tmp→fsync→rename；
     * 只保证 write==true 时文件已被替换，不承诺掉电级目录元数据持久。
     * 失败必须禁杀（调用方中止），不得先杀后补。
     */
    private fun persistLedger(
        rounds: Int,
        round: MediaProviderRecoveryPolicy.RoundRecord?,
    ): Boolean {
        val json = buildLedgerJson(rounds, round)
        if (json == null) {
            Log.w(TAG, "Failed to build recovery ledger")
            return false
        }
        if (!DataBus.writeRecoveryLedger(json)) {
            Log.w(TAG, "Failed to persist recovery ledger")
            return false
        }
        return true
    }

    private fun loadLedger() {
        when (val r = DataBus.readRecoveryLedgerDetailed()) {
            // 确认不存在：新机/已清除，按全新处理。
            is me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol.RecoveryLedgerRead.Absent -> return
            is me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol.RecoveryLedgerRead.Corrupted -> {
                Log.w(TAG, "Recovery ledger corrupted (${r.reason}), entering conservative probe")
                ledgerCorrupted = true
                return
            }
            is me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol.RecoveryLedgerRead.Ok -> {
                // 语法层由 JSONObject 抛异常兜底（init 转腐败），语义层由 parseLedger
                // 显式校验：opt* 默认值永不抛，语义错误到不了异常通道，必须显式判。
                when (val parsed = MediaProviderRecoveryPolicy.parseLedger(r.json)) {
                    is MediaProviderRecoveryPolicy.LedgerParsed.Valid -> {
                        destructiveRounds = parsed.rounds
                        lastRound = parsed.round
                        if (parsed.round != null) {
                            Log.i(TAG, "Restored recovery ledger: " +
                                    "destructiveRounds=$destructiveRounds")
                        }
                    }
                    is MediaProviderRecoveryPolicy.LedgerParsed.Corrupted -> {
                        Log.w(TAG, "Recovery ledger failed semantic validation, " +
                                "entering conservative probe")
                        ledgerCorrupted = true
                    }
                }
            }
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

    /**
     * 包级强杀：操作集合已由 resolveOperationTargets 收敛为观测交集，
     * 此处只执行、不再扩大范围。
     *
     * @param operations 最终操作目标（包+用户组合）。
     * @return 实际下发强杀的包集合。
     */
    private fun forceStopMediaProviderPackages(
        operations: Set<OperationTarget>,
    ): Set<String> {
        val packages = linkedSetOf<String>()
        for (op in operations) {
            packages += op.packageName
            runCatching {
                SystemService.forceStopPackageNoThrow(op.packageName, op.userId)
            }.onFailure {
                Log.w(TAG, "force-stop MediaProvider failed: " +
                        "package=${op.packageName} user=${op.userId}", it)
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
