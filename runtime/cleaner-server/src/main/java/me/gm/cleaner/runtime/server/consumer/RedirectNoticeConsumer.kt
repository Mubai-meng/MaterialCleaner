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

    fun bind(server: CleanerServer) {
        this.server = server
    }

    fun loadCursor() {
        cursor = DataBus.readCursor(DataBusProtocol.EVENT_REDIRECT_NOTICE)
        Log.d(TAG, "loadCursor: cursor='$cursor'")
    }

    /**
     * 拉取并消费未处理的提示事件。
     * @return 消费的事件数量
     */
    fun pollAndConsume(): Int {
        val srv = server ?: return 0
        val signalTime = DataBus.getSignalTimestamp(DataBusProtocol.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED)
        if (signalTime <= lastSignalTimestamp && lastSignalTimestamp > 0) return 0
        lastSignalTimestamp = signalTime

        val events = DataBus.readEventFiles(DataBusProtocol.EVENT_REDIRECT_NOTICE, cursor)
        if (events.isEmpty()) return 0

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
        return consumed
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
        return true
    }
}
