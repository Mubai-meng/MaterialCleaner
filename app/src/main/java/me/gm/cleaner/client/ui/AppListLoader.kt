package me.gm.cleaner.client.ui

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.model.PackageStatus

class AppListLoader(private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default) {

    suspend fun load(): List<AppListModel> = withContext(defaultDispatcher) {
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: start loading packages")
        val installedPackages = try {
            CleanerClient.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("CleanerTest", "AppListLoader.load: failed to load packages", e)
            emptyList()
        }
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: installedPackages=${installedPackages.size}")
        AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
        val srPackageStatus = try {
            CleanerClient.service?.getSrPackagesStatus(
                PackageStatus.GET_FROM_ALL_PROCESS
            ) ?: emptyMap()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("CleanerTest", "AppListLoader.load: failed to load srPackageStatus", e)
            emptyMap()
        }
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: srPackageStatus size=${srPackageStatus.size}")
        val result = installedPackages.map { pi ->
            ensureActive()
            AppListModel(
                pi,
                AppLabelCache.getPackageLabel(pi),
                ServicePreferences.getPackageSrCount(pi.packageName),
                ServicePreferences.getPackageReadOnly(pi.packageName).size,
                parseMountState(srPackageStatus[pi.packageName])
            )
        }
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: result=${result.size} apps")
        result
    }

    /**
     * 把服务端的逐 pid flag 归约成列表页的三态。
     *
     * 两条不变式：
     * 1. **分母只算 Mounter 接管过的 pid**（`STARTUP_AWARE` 或明确 `MOUNT_FAILED`）。
     *    未接管的 pid（`UNMANAGED`）与重定向无关，把它计入会让状态随无关进程的生灭抖动
     *    —— 这正是"第一次开显示挂载异常、第二次显示已挂载"的成因。
     * 2. **优先级固定**：真失败 > 部分挂载 > 全挂载 > 未知 > 未挂载。
     *    旧实现把"有 UNKNOWN"判在"部分已挂载"之前，会把真故障降级成"未知"；
     *    且单进程下 base flag = 0 会掉到 `STATE_UNMOUNTED`，与多进程下的口径互相矛盾。
     */
    private fun parseMountState(packageStatus: PackageStatus?): Int {
        packageStatus ?: return AppListModel.STATE_UNMOUNTED
        var managedCount = 0
        var mountedCount = 0
        var unknownCount = 0
        var failedCount = 0
        var partialCount = 0
        packageStatus.pidFlags.forEach { pidFlag ->
            val managed = pidFlag and PackageStatus.PID_FLAG_STARTUP_AWARE != 0 ||
                    pidFlag and PackageStatus.PID_FLAG_MOUNT_FAILED != 0
            if (!managed) return@forEach
            managedCount++
            when {
                pidFlag and (PackageStatus.PID_FLAG_MOUNT_FAILED
                        or PackageStatus.PID_FLAG_OVERRIDE
                        or PackageStatus.PID_FLAG_DELETED) != 0 -> failedCount++

                pidFlag and PackageStatus.PID_FLAG_PARTIALLY_MOUNTED != 0 -> partialCount++
                pidFlag and PackageStatus.PID_FLAG_MOUNTED != 0 -> mountedCount++
                pidFlag and PackageStatus.PID_FLAG_UNKNOWN != 0 -> unknownCount++
            }
        }
        return when {
            managedCount == 0 -> AppListModel.STATE_UNMOUNTED
            failedCount > 0 -> AppListModel.STATE_MOUNT_EXCEPTION
            // 挂上了一部分 target：这是"异常"而不是"已挂载"，
            // 但也绝不能退化成一个不带原因的布尔位。
            partialCount > 0 -> AppListModel.STATE_MOUNT_EXCEPTION
            mountedCount == managedCount -> AppListModel.STATE_MOUNTED
            mountedCount > 0 -> AppListModel.STATE_MOUNT_EXCEPTION
            unknownCount > 0 -> AppListModel.STATE_UNKNOWN
            else -> AppListModel.STATE_UNMOUNTED
        }
    }

    suspend fun updateRuleCount(old: List<AppListModel>): List<AppListModel> =
        withContext(defaultDispatcher) {
            old.map {
                it.copy(
                    mountRulesCount = ServicePreferences.getPackageSrCount(it.packageInfo.packageName),
                    readOnlyCount = ServicePreferences.getPackageReadOnly(it.packageInfo.packageName).size
                )
            }
        }
}
