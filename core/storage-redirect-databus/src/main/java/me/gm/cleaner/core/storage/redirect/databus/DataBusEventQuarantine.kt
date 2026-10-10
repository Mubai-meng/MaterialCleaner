package me.gm.cleaner.core.storage.redirect.databus

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * DataBus 事件毒丸隔离与处理重试计数（P0-B：Poison/Transient 分离）。
 *
 * 职责边界：本对象只拥有**物理隔离与计数持久化**，不拥有“是否毒丸”的判定；
 * 判定归消费侧 EventConsumePolicy，恢复语义归 MediaProviderRecoveryStrategy。
 *
 * 不变量：
 * - 隔离成功才允许调用方推进游标；隔离失败不得删除原文、不得推进；
 * - 隔离文件名由事件名确定，重复隔离幂等覆盖；
 * - 计数按事件名持久化，跨进程重启不归零。
 *
 * 自 [DataBus] 抽离以控制单文件粒度（G2 门禁）；低层原子写原语复用
 * [DataBus] 的模块内可见成员。
 */
internal object DataBusEventQuarantine {

    private const val TAG = "DataBusEventQuarantine"

    /**
     * 隔离毒丸事件：原文保留在队列目录，仅推进游标跳过。
     * 信封内含原文、原因、阶段、次数，便于排障；原子写 tmp→fsync→rename。
     */
    fun quarantine(
        queue: String,
        event: DataBusProtocol.EventFile,
        reason: String,
        stage: String,
        attempts: Int,
    ): Boolean {
        if (!DataBus.isValidEventQueue(queue)) return false
        if (!DataBus.ensureInitialized()) return false
        val quarantineDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_EVENTS}/$queue.quarantine")
        if (!DataBus.prepareDirectory(quarantineDir)) return false
        val envelope = try {
            JSONObject()
                .put("queue", queue)
                .put("originalName", event.name)
                .put("reason", reason.take(500))
                .put("stage", stage.take(120))
                .put("attempts", attempts)
                .put("quarantinedAt", System.currentTimeMillis())
                .put("content", event.content)
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build quarantine envelope: $queue/${event.name}", e)
            return false
        }
        val filename = "${DataBus.sanitizeFileName(event.name)}.quarantine.json"
        val tmpFile = try {
            DataBus.createTempFileIn(quarantineDir, "$filename-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create quarantine temp file: $queue/${event.name}", e)
            return false
        }
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(envelope.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val targetFile = File(quarantineDir, filename)
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Quarantine rename failed: $queue/$filename")
                tmpFile.delete()
                return false
            }
            DataBus.makeWorldAccessible(targetFile, executable = false, writable = false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quarantine event: $queue/${event.name}", e)
            tmpFile.delete()
            false
        }
    }

    /** 读取指定事件的处理重试计数，缺失/非法视为 0。 */
    fun readAttempt(queue: String, eventName: String): Int {
        if (!DataBus.isValidEventQueue(queue)) return 0
        val file = File(
            "${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$queue.attempts/" +
                DataBus.sanitizeFileName(eventName),
        )
        return try {
            DataBus.readRegularText(file, "attempts/$queue/$eventName")
                ?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read attempt: $queue/$eventName", e)
            0
        }
    }

    /** 持久化指定事件的处理重试计数。 */
    fun writeAttempt(queue: String, eventName: String, count: Int): Boolean {
        if (!DataBus.isValidEventQueue(queue)) return false
        if (!DataBus.ensureInitialized()) return false
        val attemptDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$queue.attempts")
        if (!DataBus.prepareDirectory(attemptDir)) return false
        val tmpFile = try {
            DataBus.createTempFileIn(attemptDir, "${DataBus.sanitizeFileName(eventName)}-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create attempt temp file: $queue/$eventName", e)
            return false
        }
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(count.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val targetFile = File(attemptDir, DataBus.sanitizeFileName(eventName))
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Attempt rename failed: $queue/$eventName")
                tmpFile.delete()
                return false
            }
            DataBus.makeWorldAccessible(targetFile, executable = false, writable = false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write attempt: $queue/$eventName", e)
            tmpFile.delete()
            false
        }
    }

    /** 清除指定事件的重试计数（消费成功或已隔离后调用）。缺失视为成功。 */
    fun clearAttempt(queue: String, eventName: String): Boolean {
        if (!DataBus.isValidEventQueue(queue)) return false
        val file = File(
            "${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$queue.attempts/" +
                DataBus.sanitizeFileName(eventName),
        )
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear attempt: $queue/$eventName", e)
            false
        }
    }

    /**
     * 孤儿计数判定（纯函数，安全契约的可测载体）。
     *
     * 计数文件名即 `sanitize(事件名)`（与写入侧同一映射）；调用方传入存量事件名
     * 经同一映射后的集合，本函数只做集合成员判定，不触碰文件系统与 Log，
     * 因此 JVM 单测可直接覆盖。
     *
     * 保守语义：任一存量事件映射命中即保留（sanitize 碰撞时宁可多保留，
     * 不误删存活事件的计数）；计数只影响重试预算，多保留一次无正确性风险。
     */
    internal fun isOrphanAttempt(attemptFileName: String, liveSanitizedNames: Set<String>): Boolean =
        attemptFileName !in liveSanitizedNames

    /**
     * 清理孤儿重试计数：计数文件所对应事件文件已不在 `events/<queue>/` 即删除，
     * 事件仍在则保留。
     *
     * 设计说明（不建通用 GC 框架）：
     * - 存量口径复用消费/清理共用的 [DataBus.isEventFile]（常规文件 + `.json`），
     *   不各自实现近似规则；
     * - 不依赖消费游标（结果中 cursorRead 恒为 null，与 pruneQuarantine 同），
     *   计数只影响重试预算，游标推进协议不受影响；
     * - 原子写临时文件（`*.tmp`）与非常规文件不是已提交的计数，不触及；
     * - 存量枚举失败按未知处理：删 0 并计失败，由调用方限频留痕，下轮重试；
     * - 单轮成功删除受 [maxDeletePerRun] 约束（默认 500，与 prune 系列同），
     *   节流由调用方清理轮次承担，本函数不自带时间状态。
     */
    fun pruneOrphanAttempts(
        queue: String,
        maxDeletePerRun: Int = 500,
    ): DataBusProtocol.PruneResult {
        if (!DataBus.isValidEventQueue(queue)) return DataBusProtocol.PruneResult.rejected()
        if (maxDeletePerRun <= 0) return DataBusProtocol.PruneResult.rejected()
        val attemptDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$queue.attempts")
        if (!attemptDir.isDirectory) {
            return DataBusProtocol.PruneResult(0, 0, 0, null)
        }
        val attemptEntries = attemptDir.listFiles()
            ?: return DataBusProtocol.PruneResult(0, 0, 1, null)
        val eventDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_EVENTS}/$queue")
        val eventEntries = eventDir.listFiles()
            ?: return DataBusProtocol.PruneResult(0, 0, 1, null)
        val liveSanitized = eventEntries
            .filter { DataBus.isEventFile(it) }
            .map { DataBus.sanitizeFileName(it.name) }
            .toSet()
        var scanned = 0
        var deleted = 0
        var failed = 0
        for (file in attemptEntries) {
            if (deleted >= maxDeletePerRun) break
            if (!DataBus.isRegularFileNoFollow(file) || file.name.endsWith(".tmp")) continue
            scanned++
            if (!isOrphanAttempt(file.name, liveSanitized)) continue
            try {
                if (file.delete()) deleted++ else failed++
            } catch (e: Exception) {
                failed++
                LogOrphanThrottle.warn("prune orphan attempt delete failed: $queue/${file.name}")
            }
        }
        return DataBusProtocol.PruneResult(scanned, deleted, failed, null)
    }

    /** 孤儿计数清理日志限频：与 DataBusPrune.LogPruneThrottle 同风格，避免反复失败刷屏。 */
    private object LogOrphanThrottle {
        private var lastAt = 0L
        private const val INTERVAL_MS = 60_000L

        fun warn(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastAt < INTERVAL_MS) return
            lastAt = now
            Log.w(TAG, message)
        }
    }
}
