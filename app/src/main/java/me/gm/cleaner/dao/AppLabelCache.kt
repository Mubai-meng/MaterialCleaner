package me.gm.cleaner.dao

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import me.gm.cleaner.core.config.SecurityHelper
import me.gm.cleaner.core.config.SecurityHelper.read
import me.gm.cleaner.core.config.SecurityHelper.write
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

object AppLabelCache {
    private const val TAG = "AppLabelCache"
    private lateinit var pm: PackageManager
    private var appContext: Context? = null
    private var labelDir: File? = null
    private var lastLocale: Locale = Locale.getDefault()
    private var labelCache: JSONObject? = null

    /**
     * 只做零 IO 的初始化。
     *
     * ⚠️ **绝不能在这里触碰 `context.filesDir`**：本批（第 17 批）实机取证 ——
     * `BootCompleteReceiver` 拉起进程时 CE 存储尚未解锁，`getFilesDir()` 内部的
     * `ensurePrivateDirExists` 会 mkdir 失败（`errno 126 ENOKEY`），而这是跑在
     * **Application.onCreate 主线程**上的同步磁盘 IO。更糟的是把这条注定不可用的
     * 路径缓存下来，会让标签缓存整个会话都写不进去 ⇒ 每次启动都要为 600+ 个包
     * 重新解析应用名。统一延迟到首次读写再解析（那时多半已解锁）。
     */
    fun init(context: Context) {
        pm = context.packageManager
        appContext = context.applicationContext
    }

    /** 惰性解析标签目录；未初始化或拿不到 filesDir 时返回 null（调用方按无缓存处理）。 */
    private fun dir(): File? {
        labelDir?.let { return it }
        val dir = appContext?.filesDir?.resolve("label") ?: return null
        labelDir = dir
        return dir
    }

    fun getPackageLabelFromPackageManager(applicationInfo: ApplicationInfo): String =
        pm.getApplicationLabel(applicationInfo).toString()

    @Synchronized
    fun getPackageLabelFromCache(packageInfo: PackageInfo, updateCacheIfStale: Boolean): String? {
        val labelCache = readLabel()
        val updateTimeToLabel = labelCache.optJSONArray(packageInfo.packageName) ?: JSONArray()
        return when {
            updateTimeToLabel.length() == 2 &&
                    packageInfo.lastUpdateTime == updateTimeToLabel.getLong(0) ->
                updateTimeToLabel.getString(1)

            !updateCacheIfStale -> null
            else -> {
                val label = getPackageLabelFromPackageManager(packageInfo.applicationInfo)
                updateTimeToLabel.put(0, packageInfo.lastUpdateTime)
                updateTimeToLabel.put(1, label)
                labelCache.put(packageInfo.packageName, updateTimeToLabel)
                writeLabel(labelCache)
                label
            }
        }
    }

    fun getPackageLabel(packageInfo: PackageInfo): String =
        getPackageLabelFromCache(packageInfo, true)!!

    /** 无 [PackageInfo] 时按 packageName 直接取持久化标签，本地降级路径使用。 */
    @Synchronized
    fun getLabelIfCached(packageName: String): String? {
        val labelCache = readLabel()
        val updateTimeToLabel = labelCache.optJSONArray(packageName) ?: return null
        return if (updateTimeToLabel.length() == 2) updateTimeToLabel.getString(1) else null
    }

    @Synchronized
    fun updatePackageLabelCacheInBulk(
        installedPackages: List<PackageInfo>, removeUninstalled: Boolean
    ) {
        val labelCache = readLabel()
        var changed = false
        if (removeUninstalled) {
            val uninstalled = labelCache.keys().asSequence().toSet() -
                    installedPackages.asSequence().map { it.packageName }.toSet()
            uninstalled.forEach { packageName ->
                changed = true
                labelCache.remove(packageName)
            }
        }
        installedPackages.forEach { packageInfo ->
            if (getPackageLabelFromCache(packageInfo, false) == null) {
                changed = true

                val updateTimeToLabel = JSONArray()
                updateTimeToLabel.put(0, packageInfo.lastUpdateTime)
                updateTimeToLabel.put(
                    1, getPackageLabelFromPackageManager(packageInfo.applicationInfo)
                )
                labelCache.put(packageInfo.packageName, updateTimeToLabel)
            }
        }
        if (changed) {
            writeLabel(labelCache)
        }
    }

    private fun getLabelFile(): File? = dir()?.resolve(Locale.getDefault().toLanguageTag())

    @Synchronized
    private fun writeLabel(json: JSONObject) {
        labelCache = json
        val dir = dir()
        if (dir == null) {
            Log.w(TAG, "writeLabel: filesDir unavailable, label cache kept in memory only")
            return
        }
        try {
            dir.mkdirs()
            val labelFile = getLabelFile() ?: return
            if (labelFile.exists()) {
                labelFile.delete()
            }
            val encryptedFile = SecurityHelper.encryptedFile(labelFile)
            encryptedFile.write(json.toString().toByteArray())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write label cache", e)
        }
    }

    @Synchronized
    private fun readLabel(): JSONObject {
        if (lastLocale !== Locale.getDefault()) {
            lastLocale = Locale.getDefault()
            labelCache = null
        }
        if (labelCache == null) {
            val labelFile = getLabelFile()
            labelCache = if (labelFile == null) {
                JSONObject()
            } else {
                try {
                    val encryptedFile = SecurityHelper.encryptedFile(labelFile)
                    JSONObject(encryptedFile.read().toString())
                } catch (e: IOException) {
                    JSONObject()
                } catch (e: RuntimeException) {
                    JSONObject()
                }
            }
        }
        return labelCache!!
    }
}
