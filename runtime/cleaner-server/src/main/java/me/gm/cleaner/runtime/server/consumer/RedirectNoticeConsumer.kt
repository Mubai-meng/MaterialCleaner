package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.server.CleanerServer
import org.json.JSONException
import org.json.JSONObject

/**
 * 重定向提示事件消费者（边界修正 + 游标持久化版）。
 *
 * - 只负责解析、TTL 检查、denylist 过滤、诊断日志
 * - 实际的 UI 广播通过 [CleanerServer.noticeDispatcher] 触发
 * - 不直接操作 android.content.Intent（避免 Kotlin stub 遮蔽问题）
 * - 消费游标持久化，server 重启后可续消费
 */
object RedirectNoticeConsumer {
    private const val TAG = "RedirectNoticeConsumer"
    private const val EVENT_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var server: CleanerServer? = null

    @Volatile
    private var cursor: String = ""

    @Volatile
    private var lastSignalTimestamp: Long = 0L

    /**
     * 跨轮次基础设施故障计数：单次 pollAndConsume 只处理队首附近事件，
     * 系统性故障（Binder/DB/磁盘）会跨轮复现，必须跨轮累积才能触发熔断。
     * 事件成功消费或非基础设施失败时清零。
     */
    @Volatile
    private var infraStreak: Int = 0

    /**
     * 游标可信状态：与内存游标值 [cursor] 是两个独立概念。
     *
     * 冷启动时若游标不可读，按兼容性策略仍把 [cursor] 置为 "" 继续处理可见事件，
     * 但本状态保持 UNREADABLE，源事件清理因此被禁止，直到本进程首次
     * 成功写入游标后重新确认。
     */
    @Volatile
    private var cursorRead: DataBusProtocol.CursorRead = DataBusProtocol.CursorRead.OK

    /** 上次清理尝试时间（成功或失败都记录，失败用更短的重试间隔）。 */
    @Volatile
    private var lastQueuePruneAt: Long = 0L

    /** 连续清理失败次数，用于限频与故障留痕。 */
    @Volatile
    private var pruneFailureStreak: Int = 0

    fun bind(server: CleanerServer) {
        this.server = server
    }

    fun loadCursor() {
        val (state, value) = DataBus.readCursorDetailed(DataBusProtocol.EVENT_REDIRECT_NOTICE)
        cursorRead = state
        cursor = value
        if (state == DataBusProtocol.CursorRead.UNREADABLE) {
            // 不可信游标不静默降级：留痕并禁止源事件清理，直到首次写游标成功。
            Log.e(TAG, "loadCursor: cursor unreadable for ${DataBusProtocol.EVENT_REDIRECT_NOTICE}, " +
                    "source event pruning disabled until a cursor write succeeds")
        } else {
            Log.d(TAG, "loadCursor: cursor='$cursor' state=$state")
        }
    }

    /**
     * 拉取并消费未处理的提示事件。
     * @return 消费的事件数量
     */
    fun pollAndConsume(): Int {
        val srv = server ?: return 0
        val signalTime = DataBus.getSignalTimestamp(DataBusProtocol.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED)
        if (signalTime <= lastSignalTimestamp && lastSignalTimestamp > 0) {
            // 清理独立于消费流程：静默队列仍需回收过期文件（节流在内部）。
            maybePruneQueue()
            return 0
        }
        lastSignalTimestamp = signalTime

        val events = DataBus.readEventFiles(DataBusProtocol.EVENT_REDIRECT_NOTICE, cursor)
        if (events.isEmpty()) {
            maybePruneQueue()
            return 0
        }

        var consumed = 0
        var skipped = 0
        var failed = false
        for (eventFile in events) {
            try {
                val eventJson = eventFile.content
                val event = try {
                    JSONObject(eventJson)
                } catch (e: JSONException) {
                    // 隔离或游标提交失败时必须中止本轮：游标仍在事件前，
                    // 若继续消费后续事件会把游标推过这个未确认的坏事件。
                    if (!quarantineAndAdvance(
                            eventFile, eventJson,
                            reason = "json-parse-failed: ${e.message}",
                            stage = "parse",
                        )
                    ) {
                        failed = true
                        break
                    }
                    continue
                }
                val timeMillis = event.optLong("timeMillis", 0L)

                // TTL 检查：业务性跳过，推进游标
                if (timeMillis > 0 && System.currentTimeMillis() - timeMillis > EVENT_TTL_MS) {
                    skipped++
                    if (!advanceCursor(eventFile)) {
                        failed = true
                        break
                    }
                    continue
                }

                val packageName = event.optString("packageName", "")
                val originalPath = event.optString("originalPath", "")
                val mountedPath = event.optString("mountedPath", "")
                val reason = event.optString("reason", "REDIRECTED_TO_INTERNAL")
                val type = event.optString("type", "")

                if (packageName.isEmpty()) {
                    skipped++
                    if (!quarantineAndAdvance(
                            eventFile, eventJson,
                            reason = "missing-required-field: packageName",
                            stage = "validate",
                        )
                    ) {
                        failed = true
                        break
                    }
                    continue
                }

                // denylist 检查：业务性跳过，推进游标
                if (ServicePreferences.denylist.contains(packageName)) {
                    skipped++
                    if (!advanceCursor(eventFile)) {
                        failed = true
                        break
                    }
                    continue
                }

                try {
                    // 通过控制面方法触发 UI 广播（Java 侧，避免 Kotlin stub Intent 问题）
                    when (reason) {
                        "MEDIA_NOT_FOUND", "MEDIA_NOT_FOUND_AGGRESSIVE" -> {
                            val path = originalPath.ifBlank { mountedPath }
                            if (path.isBlank()) {
                                skipped++
                                if (!advanceCursor(eventFile)) {
                                    failed = true
                                    break
                                }
                                continue
                            }
                            srv.noticeDispatcher.showMediaNotFoundNotice(
                                packageName,
                                path,
                                reason == "MEDIA_NOT_FOUND_AGGRESSIVE",
                            )
                        }
                        else -> {
                            // 如果 mountedPath 已作为目录存在，跳过保存提示（文件已可访问）
                            if (mountedPath.isNotEmpty() && java.io.File(mountedPath).isDirectory) {
                                skipped++
                                if (!advanceCursor(eventFile)) {
                                    failed = true
                                    break
                                }
                                continue
                            }
                            srv.noticeDispatcher.showRedirectNotice(packageName, originalPath, mountedPath, type)
                        }
                    }
                } catch (e: Exception) {
                    val infra = EventConsumePolicy.isInfrastructureFault(e)
                    infraStreak = if (infra) infraStreak + 1 else 0
                    val next = DataBus.readEventAttempt(
                        DataBusProtocol.EVENT_REDIRECT_NOTICE, eventFile.name,
                    ) + 1
                    if (!DataBus.writeEventAttempt(
                            DataBusProtocol.EVENT_REDIRECT_NOTICE, eventFile.name, next,
                        )
                    ) {
                        Log.e(TAG, "Failed to persist attempt for ${eventFile.name}, keeping cursor", e)
                        failed = true
                        break
                    }
                    when (EventConsumePolicy.decideTransient(next, infraStreak)) {
                        EventConsumePolicy.TransientDecision.QUARANTINE -> {
                            Log.w(TAG, "Quarantining notice ${eventFile.name} after $next attempts", e)
                            if (!quarantineAndAdvance(
                                    eventFile, eventJson,
                                    reason = "dispatch-failed: ${e.message}",
                                    stage = "dispatch",
                                    attempts = next,
                                )
                            ) {
                                failed = true
                                break
                            }
                            continue
                        }
                        EventConsumePolicy.TransientDecision.RETRY -> {
                            Log.e(TAG, "Failed to consume redirect notice ${eventFile.name} " +
                                    "(attempt=$next), keeping cursor", e)
                            failed = true
                            break
                        }
                    }
                }
                consumed++
                if (!advanceCursor(eventFile)) {
                    Log.e(TAG, "Failed to persist cursor for ${eventFile.name}, keeping cursor")
                    failed = true
                    break
                }
                infraStreak = 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume redirect notice ${eventFile.name}, keeping cursor", e)
                failed = true
                break
            }
        }
        if (failed) {
            lastSignalTimestamp = 0L
        }

        if (consumed > 0 || skipped > 0) {
            Log.d(TAG, "Consumed $consumed, skipped $skipped, cursor='$cursor'")
        }
        // 有界保留：清理本轮已确认消费的源事件与过期毒丸证据。
        // 执行前提是线程亲缘性——pollOnce 只在 EventConsumerScheduler 的
        // HandlerThread 上执行，bind/loadCursor 不触发清理；@Volatile 仅保证可见性。
        maybePruneQueue()
        return consumed
    }

    /**
     * 节流执行源事件与隔离目录清理。
     *
     * - 源事件清理的门是 cursorRead == OK：UNREADABLE 时即使 cursor 被兼容
     *   置为 "" 也不得删除（内存游标值与可信状态是两个独立概念）。
     * - 两个清理操作分别执行、分别记录，互不遮蔽。
     * - 失败用 30s 短间隔重试；成功恢复 5min 节流。
     * - 失败都不阻断消费主链路，只记限频日志与连续失败留痕。
     */
    private fun maybePruneQueue() {
        val now = System.currentTimeMillis()
        val interval = if (pruneFailureStreak > 0) {
            EventQueueRetention.PRUNE_RETRY_INTERVAL_MS
        } else {
            EventQueueRetention.PRUNE_INTERVAL_MS
        }
        if (now - lastQueuePruneAt < interval) return
        lastQueuePruneAt = now
        var failed = false
        if (cursorRead == DataBusProtocol.CursorRead.OK) {
            val result = runCatching {
                DataBus.pruneQueueEvents(
                    DataBusProtocol.EVENT_REDIRECT_NOTICE,
                    EventQueueRetention.SOURCE_RETENTION_MS,
                )
            }.getOrElse {
                // 异常即失败：不能压成零失败结果，否则重试节流被重置为长间隔。
                DataBusProtocol.PruneResult(0, 0, 1, cursorRead)
            }
            failed = failed or reportPrune("source", result)
        } else {
            Log.w(TAG, "pruneQueue: skipped source events, cursorRead=$cursorRead")
        }
        val quarantine = runCatching {
            DataBus.pruneQuarantine(
                DataBusProtocol.EVENT_REDIRECT_NOTICE,
                EventQueueRetention.QUARANTINE_RETENTION_MS,
            )
        }.getOrElse {
            DataBusProtocol.PruneResult(0, 0, 1, null)
        }
        failed = failed or reportPrune("quarantine", quarantine)
        pruneFailureStreak = if (failed) {
            val streak = pruneFailureStreak + 1
            if (streak >= EventQueueRetention.PRUNE_FAILURE_JOURNAL_THRESHOLD) {
                Log.e(TAG, "pruneQueue: $streak consecutive failures " +
                        "for ${DataBusProtocol.EVENT_REDIRECT_NOTICE}")
            }
            streak
        } else {
            0
        }
    }

    /** 记录单次清理结果；返回 true 表示本次存在失败。 */
    private fun reportPrune(kind: String, result: DataBusProtocol.PruneResult): Boolean {
        if (result.failed > 0) {
            Log.w(TAG, "pruneQueue: $kind scanned=${result.scanned} " +
                    "deleted=${result.deleted} failed=${result.failed} " +
                    "cursorRead=${result.cursorRead}")
            return true
        }
        if (result.deleted > 0) {
            Log.d(TAG, "pruneQueue: $kind scanned=${result.scanned} deleted=${result.deleted}")
        }
        return false
    }

    /**
     * 隔离毒丸并推进游标：quarantine 落盘成功后才写游标；任一步失败返回 false。
     */
    private fun quarantineAndAdvance(
        event: DataBusProtocol.EventFile,
        content: String,
        reason: String,
        stage: String,
        attempts: Int = DataBus.readEventAttempt(DataBusProtocol.EVENT_REDIRECT_NOTICE, event.name),
    ): Boolean {
        if (!DataBus.quarantineEvent(
                DataBusProtocol.EVENT_REDIRECT_NOTICE,
                DataBusProtocol.EventFile(event.name, content),
                reason, stage, attempts,
            )
        ) {
            Log.e(TAG, "Failed to quarantine notice ${event.name}, keeping cursor")
            return false
        }
        DataBus.clearEventAttempt(DataBusProtocol.EVENT_REDIRECT_NOTICE, event.name)
        return advanceCursor(event)
    }

    private fun advanceCursor(event: DataBusProtocol.EventFile): Boolean {
        // 先持久化、后更新内存：写失败时内存游标必须保持原位，否则本轮后续
        // readEventFiles 会跳过未确认事件（at-least-once 保障）。
        if (!DataBus.writeCursorToEvent(DataBusProtocol.EVENT_REDIRECT_NOTICE, event)) return false
        cursor = event.name
        // 本进程刚原子写入过即为可信：重确认后清理门重新打开。
        cursorRead = DataBusProtocol.CursorRead.OK
        return true
    }
}
