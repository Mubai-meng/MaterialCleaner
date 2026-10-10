package me.gm.cleaner.core.storage.redirect.databus

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * DataBus 短期会话（Lease）读写。
 *
 * 职责边界：本对象拥有 leases/ 目录下短期有效状态的读写；
 * Lease 命名规整、目录准备等基础原语复用 [DataBus] 的模块内可见成员。
 *
 * 自 [DataBus] 抽离以控制单文件粒度（G2 门禁）。[DataBus] 保留同名
 * 公开委托（writeLease/readLeaseFiles/deleteLeaseFile），跨模块调用方无需改动。
 */
internal object DataBusLease {

    private const val TAG = "DataBusLease"

    /**
     * 原子写入一个短期 lease。
     *
     * Lease 表示短期有效状态，命名由调用方提供但会被规整为文件安全形式。
     * 内容仍由调用方使用 JSON 表达，并在 payload 中包含 expiresAt。
     */
    fun writeLease(category: String, key: String, content: String): Boolean {
        if (!DataBus.isValidLeaseCategory(category)) return false
        if (!DataBus.ensureInitialized()) return false
        val leaseDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_LEASES}/$category")
        if (!DataBus.prepareDirectory(leaseDir)) {
            return false
        }

        val filename = "${DataBus.sanitizeFileName(key)}.json"
        val tmpFile = try {
            DataBus.createTempFileIn(leaseDir, "$filename-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create lease temp file: $category/$filename", e)
            return false
        }
        val targetFile = File(leaseDir, filename)

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Lease rename failed: $category/$filename")
                tmpFile.delete()
                return false
            }
            DataBus.makeWorldAccessible(targetFile, executable = false, writable = false)
            Log.d(TAG, "Lease written: $category/$filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write lease: $category/$filename", e)
            tmpFile.delete()
            false
        }
    }

    fun readLeaseFiles(category: String): List<DataBusProtocol.EventFile> {
        if (!DataBus.isValidLeaseCategory(category)) return emptyList()
        val leaseDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_LEASES}/$category")
        if (!leaseDir.exists()) return emptyList()

        return try {
            leaseDir.listFiles()
                ?.filter { DataBus.isRegularFileNoFollow(it) && it.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    DataBus.readRegularText(file, "leases/$category/${file.name}")?.let {
                        DataBusProtocol.EventFile(file.name, it)
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read leases from $category", e)
            emptyList()
        }
    }

    fun deleteLeaseFile(category: String, name: String): Boolean {
        if (!DataBus.isValidLeaseCategory(category)) return false
        val file = File(
            "${DataBus.BUS_ROOT}/${DataBus.DIR_LEASES}/$category/" +
                DataBus.sanitizeFileName(name),
        )
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete lease: $category/$name", e)
            false
        }
    }
}
