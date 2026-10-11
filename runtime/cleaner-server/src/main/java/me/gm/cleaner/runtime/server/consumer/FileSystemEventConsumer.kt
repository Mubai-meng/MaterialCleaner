package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.server.recording.FileSystemObserver
import me.gm.cleaner.runtime.server.lifecycle.ObserverManager
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 文件系统事件消费者（游标持久化版）。
 *
 * 从 [DataBus] events/filesystem 读取单文件事件，转发给 [FileSystemObserver]。
 * 消费游标持久化到 DataBus cursors/，server 重启后可续消费。
 */
object FileSystemEventConsumer {
    private const val TAG = "FileSystemEventConsumer"

    /** consumed/ 目录归档保留时长：24 小时 */
    private const val CONSUMED_TTL_MS = 24 * 60 * 60 * 1000L

    /** consumed/ 目录最大文件数上限，超过时触发清理最旧文件 */
    private const val CONSUMED_MAX_FILES = 10000

    @Volatile
    private var cursor: String = ""

    /** 最近一次检查到的 signal 时间戳，用于熔断无事件轮询 */
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

    /** 从 DataBus 加载持久化游标 */
    fun loadCursor() {
        val (state, value) = DataBus.readCursorDetailed(DataBusProtocol.EVENT_FILESYSTEM)
        cursorRead = state
        cursor = value
        if (state == DataBusProtocol.CursorRead.UNREADABLE) {
            // 不可信游标不静默降级：留痕并禁止源事件清理，直到首次写游标成功。
            Log.e(TAG, "loadCursor: cursor unreadable for ${DataBusProtocol.EVENT_FILESYSTEM}, " +
                    "source event pruning disabled until a cursor write succeeds")
        } else {
            Log.d(TAG, "loadCursor: cursor='$cursor' state=$state")
        }
    }

    /**
     * 拉取并消费所有未处理事件。
     * @return 消费的事件数量
     */
    fun pollAndConsume(): Int {
        // 信号熔断：signal 未变化表示无新事件，跳过文件系统扫描（listFiles）以节省 tmpfs I/O
        val signalTime = DataBus.getSignalTimestamp(DataBusProtocol.SIGNAL_FILESYSTEM_EVENTS_CHANGED)
        if (signalTime <= lastSignalTimestamp && lastSignalTimestamp > 0) {
            // 清理独立于消费流程：静默队列（无新事件）仍需回收过期文件，
            // 否则低流量队列的保留期形同虚设。节流在 maybePruneQueue 内部。
            maybePruneQueue()
            return 0
        }
        lastSignalTimestamp = signalTime

        val events = DataBus.readEventFiles(DataBusProtocol.EVENT_FILESYSTEM, cursor)
        if (events.isEmpty()) {
            maybePruneQueue()
            return 0
        }

        val observer = ObserverManager.fastGetObserver(FileSystemObserver::class.java)
        if (observer == null) {
            Log.w(TAG, "FileSystemObserver not available, keeping cursor for ${events.size} events")
            lastSignalTimestamp = 0L
            // observer 缺失只阻塞消费，不阻塞清理：清理基于 DataBus 持久化游标，
            // 与内存 observer 无关。
            maybePruneQueue()
            return 0
        }

        var consumed = 0
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
                val timeMillis = event.optLong("timeMillis", System.currentTimeMillis())
                val packageName = event.optString("packageName", "")
                val path = event.optString("path", "")
                val flags = event.optInt("flags", 0)

                if (packageName.isEmpty() || path.isEmpty()) {
                    if (!quarantineAndAdvance(
                            eventFile, eventJson,
                            reason = "missing-required-field",
                            stage = "validate",
                        )
                    ) {
                        failed = true
                        break
                    }
                    continue
                }

                try {
                    observer.onEvent(timeMillis, packageName, path, flags)
                } catch (e: Exception) {
                    val infra = EventConsumePolicy.isInfrastructureFault(e)
                    infraStreak = if (infra) infraStreak + 1 else 0
                    val next = DataBus.readEventAttempt(
                        DataBusProtocol.EVENT_FILESYSTEM, eventFile.name,
                    ) + 1
                    if (!DataBus.writeEventAttempt(
                            DataBusProtocol.EVENT_FILESYSTEM, eventFile.name, next,
                        )
                    ) {
                        Log.e(TAG, "Failed to persist attempt for ${eventFile.name}, keeping cursor", e)
                        failed = true
                        break
                    }
                    when (EventConsumePolicy.decideTransient(next, infraStreak)) {
                        EventConsumePolicy.TransientDecision.QUARANTINE -> {
                            Log.w(TAG, "Quarantining event ${eventFile.name} after $next attempts", e)
                            if (!quarantineAndAdvance(
                                    eventFile, eventJson,
                                    reason = "onEvent-failed: ${e.message}",
                                    stage = "onEvent",
                                    attempts = next,
                                )
                            ) {
                                failed = true
                                break
                            }
                            continue
                        }
                        EventConsumePolicy.TransientDecision.RETRY -> {
                            Log.e(TAG, "Failed to consume event ${eventFile.name} " +
                                    "(attempt=$next), keeping cursor", e)
                            failed = true
                            break
                        }
                    }
                }
                consumed++

                // 归档到 consumed/ 目录，保留事件记录供审计。
                // 归档失败视为基础设施故障：不推进游标，下轮重放（原文仍在队列目录）。
                if (!archiveEvent(eventJson)) {
                    Log.e(TAG, "Failed to archive event ${eventFile.name}, keeping cursor")
                    failed = true
                    break
                }
                if (!advanceCursor(eventFile)) {
                    Log.e(TAG, "Failed to persist cursor for ${eventFile.name}, keeping cursor")
                    failed = true
                    break
                }
                // 成功终态：游标已提交，清除重试计数。
                // 清除失败只记限频日志：计数仅影响重试预算，绝不回退游标、不撤销归档。
                clearAttemptAfterAdvance(eventFile.name)
                infraStreak = 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume event ${eventFile.name}, keeping cursor", e)
                failed = true
                break
            }
        }
        if (failed) {
            lastSignalTimestamp = 0L
        }

        if (consumed > 0) {
            Log.d(TAG, "Consumed $consumed events, cursor='$cursor'")
        }

        // 定期清理过期归档事件
        cleanupConsumed()
        // 有界保留：清理本轮已确认消费的源事件与过期毒丸证据。
        // 执行前提是线程亲缘性——pollOnce 只在 EventConsumerScheduler 的
        // HandlerThread 上执行，prepare() 不触发清理；@Volatile 仅保证可见性。
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
                    DataBusProtocol.EVENT_FILESYSTEM,
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
                DataBusProtocol.EVENT_FILESYSTEM,
                EventQueueRetention.QUARANTINE_RETENTION_MS,
            )
        }.getOrElse {
            DataBusProtocol.PruneResult(0, 0, 1, null)
        }
        failed = failed or reportPrune("quarantine", quarantine)
        // 孤儿计数清理不依赖游标水位（只与事件存量有关），与隔离清理并列执行。
        val orphanAttempts = runCatching {
            DataBus.pruneOrphanAttempts(DataBusProtocol.EVENT_FILESYSTEM)
        }.getOrElse {
            DataBusProtocol.PruneResult(0, 0, 1, null)
        }
        failed = failed or reportPrune("orphanAttempts", orphanAttempts)
        pruneFailureStreak = if (failed) {
            val streak = pruneFailureStreak + 1
            if (streak >= EventQueueRetention.PRUNE_FAILURE_JOURNAL_THRESHOLD) {
                Log.e(TAG, "pruneQueue: $streak consecutive failures " +
                        "for ${DataBusProtocol.EVENT_FILESYSTEM}")
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
     * 清理 consumed/ 目录中超过 TTL 的归档事件文件，避免 tmpfs 空间占满。
     * 阈值双重控制：过期时间（CONSUMED_TTL_MS）+ 最大文件数（CONSUMED_MAX_FILES）。
     */
    private fun cleanupConsumed() {
        val consumedDir = File(DataBus.BUS_ROOT, "events/consumed")
        if (!Files.isDirectory(consumedDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return

        val files = consumedDir.listFiles()
            ?.filter {
                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        it.name.endsWith(".json")
            }
            ?.sortedBy { it.name }  // 按文件名排序（内含时间戳）
            ?: return

        if (files.isEmpty()) return

        val now = System.currentTimeMillis()
        val thresholdTime = now - CONSUMED_TTL_MS

        var deleted = 0
        // 第一遍：删除超过 TTL 的文件
        for (file in files) {
            if (file.lastModified() < thresholdTime && file.delete()) {
                deleted++
            }
        }
        // 第二遍：如果仍超过最大文件数，从最旧的开始删除
        val remaining = files.size - deleted
        if (remaining > CONSUMED_MAX_FILES) {
            val excess = remaining - CONSUMED_MAX_FILES
            val sorted = files.filter { it.exists() }.sortedBy { it.name }
            for (i in 0 until minOf(excess, sorted.size)) {
                if (sorted[i].delete()) deleted++
            }
        }

        if (deleted > 0) {
            Log.d(TAG, "cleanupConsumed: deleted $deleted files, remaining=${files.size - deleted}")
        }
    }

    /**
     * 隔离毒丸并推进游标：quarantine 落盘成功后才写游标；任一步失败返回 false，
     * 调用方不得宣称已消费。隔离文件名确定，重复隔离幂等覆盖。
     */
    private fun quarantineAndAdvance(
        event: DataBusProtocol.EventFile,
        content: String,
        reason: String,
        stage: String,
        attempts: Int = DataBus.readEventAttempt(DataBusProtocol.EVENT_FILESYSTEM, event.name),
    ): Boolean {
        if (!DataBus.quarantineEvent(
                DataBusProtocol.EVENT_FILESYSTEM,
                DataBusProtocol.EventFile(event.name, content),
                reason, stage, attempts,
            )
        ) {
            Log.e(TAG, "Failed to quarantine event ${event.name}, keeping cursor")
            return false
        }
        DataBus.clearEventAttempt(DataBusProtocol.EVENT_FILESYSTEM, event.name)
        return advanceCursor(event)
    }

    private fun advanceCursor(event: DataBusProtocol.EventFile): Boolean {
        // 先持久化、后更新内存：写失败时内存游标必须保持原位，否则本轮后续
        // readEventFiles 会跳过未确认事件（at-least-once 保障）。
        if (!DataBus.writeCursorToEvent(DataBusProtocol.EVENT_FILESYSTEM, event)) return false
        cursor = event.name
        // 本进程刚原子写入过即为可信：重确认后清理门重新打开。
        cursorRead = DataBusProtocol.CursorRead.OK
        return true
    }

    /**
     * 终态游标提交成功后清除重试计数（成功/跳过路径共用）。
     * 清除失败只记限频日志：计数仅影响重试预算，绝不回退游标、不撤销副作用。
     */
    private fun clearAttemptAfterAdvance(eventName: String) {
        if (!DataBus.clearEventAttempt(DataBusProtocol.EVENT_FILESYSTEM, eventName)) {
            ClearAttemptWarnThrottle.warn("Failed to clear attempt for $eventName, cursor already advanced")
        }
    }

    /** clearEventAttempt 失败日志限频（60s），风格与 DataBusPrune 侧一致。 */
    private object ClearAttemptWarnThrottle {
        private var lastAt = 0L
        private const val INTERVAL_MS = 60_000L

        fun warn(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastAt < INTERVAL_MS) return
            lastAt = now
            Log.w(TAG, message)
        }
    }

    /**
     * 归档已消费事件到 consumed/ 目录。
     * 使用时间戳+随机数命名文件（不含事件序列号），避免浪费 DataBus 全局序列号计数器。
     * @return true 归档成功；false 基础设施故障，调用方须保留游标重试。
     */
    private fun archiveEvent(content: String): Boolean {
        if (!DataBus.ensureInitialized()) return false
        val consumedDir = File(DataBus.BUS_ROOT, "events/consumed")
        if (!Files.isDirectory(consumedDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return false

        val now = System.currentTimeMillis()
        val rand = ((Math.random() * 0xFFFF).toInt() and 0xFFFF)
        val filename = "$now-$rand.json"
        val tmpFile = try {
            Files.createTempFile(consumedDir.toPath(), "$filename-", ".tmp").toFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create consumed archive temp file", e)
            return false
        }
        val targetFile = File(consumedDir, filename)

        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Failed to rename consumed archive: $filename")
                tmpFile.delete()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive consumed event", e)
            tmpFile.delete()
            return false
        }
        return true
    }
}
