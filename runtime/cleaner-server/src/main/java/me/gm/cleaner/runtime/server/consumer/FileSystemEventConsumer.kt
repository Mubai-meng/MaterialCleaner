package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.observer.FileSystemObserver
import me.gm.cleaner.runtime.server.observer.ObserverManager
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

    /**
     * events/filesystem 目录低水位上限。
     *
     * 目录里的文件只被游标越过、从不删除，会随运行时间无界增长
     * （实测 11 分钟已有 561 个）。超过该值时清理**游标及之前**的文件
     * （这些文件不可能再被读出，删除不造成重复或丢失）。
     *
     * 数值取 200 而非 2000：清理本身是 O(n) 的，而且任何在写入路径上
     * 扫描该目录的逻辑都会随文件数线性变慢。消费者每 2 s 轮询一次并消费
     * 全部积压，200 已远超单轮可能积压的量级（>100 事件/秒的持续速率才会触顶，
     * 且触顶只是触发一次清理，不会丢事件）。
     */
    private const val EVENT_QUEUE_MAX_FILES = 200

    /**
     * 单个事件**投递**失败的最大重试次数，超过即隔离。
     *
     * 投递（[FileSystemObserver.onEvent] → Room 写入 / Binder 派发）可能因瞬态原因失败
     * （磁盘满、`DeadObjectException`），值得重试；但**必须有限**，
     * 否则确定性失败会重新变成毒丸（见 [EventDeadLetter]）。
     * 3 次 × 2 s 轮询 ≈ 6 s 内收敛。
     */
    private const val MAX_DELIVERY_ATTEMPTS = 3

    @Volatile
    private var cursor: String = ""

    /** 最近一次检查到的 signal 时间戳，用于熔断无事件轮询 */
    @Volatile
    private var lastSignalTimestamp: Long = 0L

    /**
     * 投递失败次数，按事件文件名计。
     *
     * 只用于区分"可重试"与"确定性失败"。不承担跨进程语义：
     * server 重启后计数归零，但游标也会重读，坏事件仍会在
     * [MAX_DELIVERY_ATTEMPTS] 次后被隔离，不会重新形成毒丸。
     *
     * 消费循环运行在单一 HandlerThread 上（`EventConsumerScheduler`），
     * 用并发容器只是为了让"单线程"这个前提将来被改动时依然安全。
     */
    private val deliveryFailures =
        java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 从 DataBus 加载持久化游标 */
    fun loadCursor() {
        cursor = DataBus.readCursor(DataBus.EVENT_FILESYSTEM)
        Log.d(TAG, "loadCursor: cursor='$cursor'")
    }

    /**
     * 拉取并消费所有未处理事件。
     * @return 消费的事件数量
     */
    fun pollAndConsume(): Int {
        // 信号熔断：signal 未变化表示无新事件，跳过文件系统扫描（listFiles）以节省 tmpfs I/O
        val signalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_FILESYSTEM_EVENTS_CHANGED)
        if (signalTime <= lastSignalTimestamp && lastSignalTimestamp > 0) return 0
        lastSignalTimestamp = signalTime

        val events = DataBus.readEventFiles(DataBus.EVENT_FILESYSTEM, cursor)
        if (events.isEmpty()) return 0

        val observer = ObserverManager.fastGetObserver(FileSystemObserver::class.java)
        if (observer == null) {
            Log.w(TAG, "FileSystemObserver not available, keeping cursor for ${events.size} events")
            lastSignalTimestamp = 0L
            return 0
        }

        var consumed = 0
        var quarantined = 0
        var discarded = 0

        /** true = 因"可能瞬态"的投递失败提前退出且**游标未推进**，下一轮重试。 */
        var retryPending = false

        for (eventFile in events) {
            // ── 阶段 1：解析 ──
            // 事件文件是 tmp→fsync→rename 原子落盘的，磁盘上的 .json 只可能是完整内容，
            // 解析失败即**永久**失败。必须结清该事件并推进游标，否则整条队列被毒丸卡死。
            val event = try {
                parseEvent(eventFile)
            } catch (e: Exception) {
                if (settleEvent(eventFile, "json parse failed", e)) quarantined++ else discarded++
                continue
            }

            if (event == null) {
                // 结构无效（缺 packageName/path）：沿用原有"跳过"语义
                advanceCursor(eventFile)
                continue
            }

            // ── 阶段 2：投递 ──
            // 投递失败可能瞬态（Room 磁盘满 / Binder DeadObject），做**有限**重试；
            // 达到阈值说明是确定性失败，同样结清，避免再次形成毒丸。
            try {
                observer.onEvent(event.timeMillis, event.packageName, event.path, event.flags)
            } catch (e: Exception) {
                val attempts = recordDeliveryFailure(eventFile.name)
                if (attempts < MAX_DELIVERY_ATTEMPTS) {
                    Log.w(
                        TAG,
                        "delivery failed for ${eventFile.name} " +
                                "(attempt $attempts/$MAX_DELIVERY_ATTEMPTS), will retry",
                        e,
                    )
                    retryPending = true
                    break
                }
                val kept = settleEvent(eventFile, "delivery failed after $attempts attempts", e)
                if (kept) quarantined++ else discarded++
                continue
            }
            deliveryFailures.remove(eventFile.name)
            consumed++

            // 归档到 consumed/ 目录，保留事件记录供审计
            // 直接文件写入（不含序列号），避免浪费事件序列号计数器
            archiveEvent(eventFile.content)
            advanceCursor(eventFile)
        }

        // 只有"值得重试"的失败才解除熔断；事件结清后不再重置，
        // 让 signal 熔断重新生效，避免每 2 s 空扫目录。
        if (retryPending) {
            lastSignalTimestamp = 0L
        }

        if (consumed > 0 || quarantined > 0 || discarded > 0) {
            Log.d(
                TAG,
                "Consumed $consumed events, quarantined $quarantined, " +
                        "discarded $discarded, cursor='$cursor'",
            )
        }

        // 定期清理过期归档事件
        cleanupConsumed()
        cleanupEventQueue()
        return consumed
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

    private fun advanceCursor(event: DataBus.EventFile) {
        cursor = event.name
        DataBus.writeCursorToEvent(DataBus.EVENT_FILESYSTEM, event)
    }

    private class ParsedEvent(
        val timeMillis: Long,
        val packageName: String,
        val path: String,
        val flags: Int,
    )

    /**
     * 解析事件 JSON。
     *
     * 契约（很重要，调用方据此分流）：
     * - **抛异常** = 确定性毒丸（见 [EventDeadLetter] 的安全性论证）→ 隔离 + 推进游标
     * - **返回 null** = 结构无效（缺 `packageName`/`path`）→ 沿用原有"跳过"语义
     * - **返回对象** = 可投递
     */
    private fun parseEvent(eventFile: DataBus.EventFile): ParsedEvent? {
        val event = JSONObject(eventFile.content)
        val packageName = event.optString("packageName", "")
        val path = event.optString("path", "")
        if (packageName.isEmpty() || path.isEmpty()) return null
        return ParsedEvent(
            timeMillis = event.optLong("timeMillis", System.currentTimeMillis()),
            packageName = packageName,
            path = path,
            flags = event.optInt("flags", 0),
        )
    }

    /** 记录一次投递失败并返回累计次数（`merge` 保证读改写原子）。 */
    private fun recordDeliveryFailure(name: String): Int =
        deliveryFailures.merge(name, 1) { current, increment -> current + increment } ?: 1

    /**
     * 结清一个无法处理的事件：让它离开队列目录**并推进游标**。
     * 这是"解开毒丸"的关键动作 —— 只要游标越过它，该事件就永远不可能再被读到。
     *
     * [EventDeadLetter.quarantine] 保证**不会失败到需要重试**：它要么把文件移入隔离区
     * （证据保留），要么退化为删除 / 留在原地由 `pruneConsumedEvents` 回收。
     * 因此"任何单个事件都无法永久阻塞队列"这一不变式**由构造成立**，
     * 不依赖任何重试机制。
     *
     * @return true = 已隔离（证据保留）；false = 只能丢弃（隔离失败，证据丢失）
     */
    private fun settleEvent(
        eventFile: DataBus.EventFile,
        reason: String,
        error: Throwable?,
    ): Boolean {
        val kept = EventDeadLetter.quarantine(
            DataBus.EVENT_FILESYSTEM, eventFile.name, reason, error,
        ) == EventDeadLetter.Settlement.QUARANTINED
        deliveryFailures.remove(eventFile.name)
        advanceCursor(eventFile)
        return kept
    }

    /**
     * 收紧 events/filesystem 目录：超过低水位时删除**游标及之前**的已消费事件文件。
     *
     * 只删除不可能再被读出的条目（游标是唯一读取起点），因此不会造成重复消费或丢失；
     * `consumed/` 归档仍按自身的 TTL / 上限保留审计副本。
     */
    private fun cleanupEventQueue() {
        val pruned = DataBus.pruneConsumedEvents(
            DataBus.EVENT_FILESYSTEM, keepAtMost = EVENT_QUEUE_MAX_FILES,
        )
        if (pruned > 0) {
            Log.i(TAG, "cleanupEventQueue: pruned $pruned consumed event files " +
                    "(cursor='$cursor', keepAtMost=$EVENT_QUEUE_MAX_FILES)")
        }
    }

    /**
     * 归档已消费事件到 consumed/ 目录。
     * 使用时间戳+随机数命名文件（不含事件序列号），避免浪费 DataBus 全局序列号计数器。
     */
    private fun archiveEvent(content: String) {
        if (!DataBus.ensureInitialized()) return
        val consumedDir = File(DataBus.BUS_ROOT, "events/consumed")
        if (!Files.isDirectory(consumedDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return

        val now = System.currentTimeMillis()
        val rand = ((Math.random() * 0xFFFF).toInt() and 0xFFFF)
        val filename = "$now-$rand.json"
        val tmpFile = try {
            Files.createTempFile(consumedDir.toPath(), "$filename-", ".tmp").toFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create consumed archive temp file", e)
            return
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive consumed event", e)
            tmpFile.delete()
        }
    }
}
