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
    fun read(): String? = when (val r = readDetailed()) {
        is DataBusProtocol.RecoveryLedgerRead.Ok -> r.json
        else -> null
    }

    /**
     * 区分式读取：调用方必须区分“确认不存在”与“不可确认”。
     *
     * - Absent：路径确定不存在（新机/已清除），可按全新处理；
     * - Ok：常规文件且读取成功；
     * - Corrupted：存在但非常规文件、读取抛异常（调用方不得按全新处理，
     *   必须进保守恢复态）。
     *
     * 返回类型为公开契约 [DataBusProtocol.RecoveryLedgerRead]，跨模块可见。
     */
    fun readDetailed(): DataBusProtocol.RecoveryLedgerRead {
        val file = File("${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$FILE_NAME")
        return try {
            val path = file.toPath()
            if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return DataBusProtocol.RecoveryLedgerRead.Absent
            }
            val content = DataBus.readRegularText(file, "cursors/$FILE_NAME")
                ?: return DataBusProtocol.RecoveryLedgerRead.Corrupted("unreadable")
            DataBusProtocol.RecoveryLedgerRead.Ok(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read recovery ledger", e)
            DataBusProtocol.RecoveryLedgerRead.Corrupted(e.message ?: "exception")
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
            // 与 readDetailed 一致的不跟随语义：悬空符号链接本身存在，
            // File.exists() 跟随链接会误判不存在而谎报成功。成功标准是
            // 调用后路径（不跟随）确实不存在。
            val path = file.toPath()
            if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return true
            }
            java.nio.file.Files.deleteIfExists(path)
            !java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear recovery ledger", e)
            false
        }
    }
}
