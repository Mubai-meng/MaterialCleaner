package me.gm.cleaner.client.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.getPackageReadOnly
import me.gm.cleaner.core.config.getPackageSrCount
import me.gm.cleaner.core.config.readOnlyPackages
import me.gm.cleaner.core.config.srPackages
import me.gm.cleaner.model.PackageStatus

/** AppListLoader.load() 的结果：isFullList=false 表示服务端不可用时的本地降级结果。 */
data class AppListLoadResult(
    val list: List<AppListModel>,
    val isFullList: Boolean,
)

class AppListLoader(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val context: Context,
) {

    suspend fun load(): AppListLoadResult = withContext(defaultDispatcher) {
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: start loading packages")
        val installedPackages = CleanerClient.getInstalledPackagesOrNull(PackageManager.GET_PERMISSIONS)
        if (installedPackages == null) {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: service unavailable or RPC failed, fallback")
            return@withContext AppListLoadResult(loadLocalFallback(), isFullList = false)
        }
        if (installedPackages.isNotEmpty()) {
            AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
        } else if (BuildConfig.DEBUG) {
            Log.w("CleanerTest", "AppListLoader.load: server returned empty installed list, keep previous label cache")
        }
        val srPackageStatus = CleanerClient.getSrPackagesStatusOrNull(PackageStatus.GET_FROM_ALL_PROCESS)
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: srPackageStatus size=${srPackageStatus?.size}")
        // 服务端映射就绪且查无记录等于真无，未就绪或链路失败一律按未知处理，不混用空表示两种含义。
        val statusUnknown = srPackageStatus == null
        val result = installedPackages.map { pi ->
            ensureActive()
            AppListModel(
                pi,
                AppLabelCache.getPackageLabel(pi),
                ConfiguredPolicyStoreProvider.instance.getPackageSrCount(pi.packageName),
                ConfiguredPolicyStoreProvider.instance.getPackageReadOnly(pi.packageName).size,
                parseMountState(if (statusUnknown) null else srPackageStatus?.get(pi.packageName), statusUnknown)
            )
        }
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: result=${result.size} apps")
        AppListLoadResult(result, isFullList = true)
    }

    /** 服务不可用时：用 PackageManager 获取客户端可见的全量应用，供主界面过滤规则包和新建挂载选择。 */
    private fun loadLocalFallback(): List<AppListModel> {
        val store = ConfiguredPolicyStoreProvider.instance
        val installedPackages = try {
            context.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("CleanerTest", "AppListLoader.loadLocalFallback: failed to get local packages", e)
            emptyList()
        }
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.loadLocalFallback: packages=${installedPackages.size}")
        if (installedPackages.isNotEmpty()) {
            AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
        } else if (BuildConfig.DEBUG) {
            Log.w("CleanerTest", "AppListLoader.loadLocalFallback: keep previous label cache on empty/failed PM result")
        }
        return installedPackages.mapNotNull { pi ->
            val packageName = pi.packageName
            val label = AppLabelCache.getLabelIfCached(packageName)
                ?: pi.applicationInfo?.let { it.loadLabel(context.packageManager).toString() }
                ?: packageName
            AppListModel(
                pi,
                label,
                store.getPackageSrCount(packageName),
                store.getPackageReadOnly(packageName).size,
                AppListModel.STATE_UNKNOWN,
            )
        }
    }

    /**
     * 把服务端的逐 pid flag 归约成列表页的三态。
     *
     * 三条不变式：
     * 1. **状态缺失分两种**：映射就绪但查无记录 = 真无（`statusUnknown=false`）；
     *    未就绪或链路失败 = 未知（`statusUnknown=true`）。不混用空值的两种含义。
     * 2. **分母只算 Mounter 接管过的 pid**（`STARTUP_AWARE` 或明确 `MOUNT_FAILED`）。
     *    未接管的 pid（`UNMANAGED`）与重定向无关，把它计入会让状态随无关进程的生灭抖动
     *    —— 这正是"第一次开显示挂载异常、第二次显示已挂载"的成因。
     * 3. **优先级固定**：真失败 > 部分挂载 > 全挂载 > 未知 > 未挂载。
     *    旧实现把"有 UNKNOWN"判在"部分已挂载"之前，会把真故障降级成"未知"；
     *    且单进程下 base flag = 0 会掉到 `STATE_UNMOUNTED`，与多进程下的口径互相矛盾。
     */
    private fun parseMountState(packageStatus: PackageStatus?, statusUnknown: Boolean): Int {
        if (statusUnknown) return AppListModel.STATE_UNKNOWN
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
            val srPackageStatus = CleanerClient.getSrPackagesStatusOrNull(PackageStatus.GET_FROM_ALL_PROCESS)
            old.map {
                val packageName = it.packageInfo.packageName
                it.copy(
                    mountRulesCount = ConfiguredPolicyStoreProvider.instance.getPackageSrCount(packageName),
                    readOnlyCount = ConfiguredPolicyStoreProvider.instance.getPackageReadOnly(packageName).size,
                    mountState = if (srPackageStatus == null) AppListModel.STATE_UNKNOWN else parseMountState(srPackageStatus[packageName], false),
                )
            }
        }
}
