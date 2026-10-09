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
}
