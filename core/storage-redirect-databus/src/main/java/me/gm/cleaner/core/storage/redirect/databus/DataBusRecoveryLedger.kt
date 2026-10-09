package me.gm.cleaner.core.storage.redirect.databus

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * DataBus 恢复熔断总账（破坏轮次跨 server 重启延续）。
 *
 * 职责边界：本对象只提供**不透明内容的原子存取**，不解释恢复语义；
 * 语义（何时写/清、熔断含义）归 MediaProviderRecoveryStrategy。
 * server 私有状态，不进 snapshots 白名单，不参与健康快照检查。
 *
 * 自 [DataBus] 抽离以控制单文件粒度（G2 门禁）；低层原子写原语复用
 * [DataBus] 的模块内可见成员。
 */
internal object DataBusRecoveryLedger {

    private const val TAG = "DataBusRecoveryLedger"
    private const val FILE_NAME = "media_provider_recovery.json"

    /** 读取总账 JSON，缺失/非法返回 null（调用方视为全新 episode）。 */
    fun read(): String? {
        val file = File("${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$FILE_NAME")
        return try {
            DataBus.readRegularText(file, "cursors/$FILE_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read recovery ledger", e)
            null
        }
    }

    /** 原子持久化总账 JSON。 */
    fun write(content: String): Boolean {
        if (!DataBus.ensureInitialized()) return false
        val cursorDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}")
        if (!DataBus.prepareDirectory(cursorDir)) return false
        val tmpFile = try {
            DataBus.createTempFileIn(cursorDir, "$FILE_NAME-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recovery ledger temp file", e)
            return false
        }
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val targetFile = File(cursorDir, FILE_NAME)
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Recovery ledger rename failed, deleting tmp")
                tmpFile.delete()
                return false
            }
            DataBus.makeWorldAccessible(targetFile, executable = false, writable = false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write recovery ledger", e)
            tmpFile.delete()
            false
        }
    }

    /** 清除总账（Hook 确认恢复后调用）。缺失视为成功。 */
    fun clear(): Boolean {
        val file = File("${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$FILE_NAME")
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear recovery ledger", e)
            false
        }
    }
}
