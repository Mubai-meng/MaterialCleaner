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
     * 服务端状态缺失分两种：映射就绪但查无记录等于真无，未就绪或链路失败等于未知。
     * 用 statusUnknown 显式区分，不混用空值的两种含义。
     */
    private fun parseMountState(packageStatus: PackageStatus?, statusUnknown: Boolean): Int {
        if (statusUnknown) return AppListModel.STATE_UNKNOWN
        packageStatus ?: return AppListModel.STATE_UNMOUNTED
        val mountedPids = mutableListOf<Int>()
        val unknownPids = mutableListOf<Int>()
        var managedCount = 0
        packageStatus.pidFlags.forEachIndexed { index, pidFlag ->
            // UNMANAGED 不进分母；其余终态互斥，MOUNT_FAILED 等为正交证据不影响分类。
            if (pidFlag and PackageStatus.PID_FLAG_UNMANAGED != 0) return@forEachIndexed
            managedCount++
            if (pidFlag and PackageStatus.PID_FLAG_MOUNTED != 0) {
                mountedPids += packageStatus.pids[index]
            } else if (pidFlag and PackageStatus.PID_FLAG_UNKNOWN != 0) {
                unknownPids += packageStatus.pids[index]
            } else if (pidFlag and (
                    PackageStatus.PID_FLAG_PARTIALLY_MOUNTED or
                        PackageStatus.PID_FLAG_NOT_MOUNTED or
                        PackageStatus.PID_FLAG_DELETED or
                        PackageStatus.PID_FLAG_OVERRIDE
                    ) == 0
            ) {
                // 无终态位（含旧版本 0 盲区）：证据不足，按 UNKNOWN 处理，不判未挂载。
                unknownPids += packageStatus.pids[index]
            }
        }
        if (managedCount == 0) {
            // 空列表保持旧语义 UNMOUNTED；存在进程但全部 UNMANAGED 则无法验证，判 UNKNOWN。
            return if (packageStatus.pids.isNotEmpty()) {
                AppListModel.STATE_UNKNOWN
            } else {
                AppListModel.STATE_UNMOUNTED
            }
        }
        return if (managedCount == mountedPids.size) {
            AppListModel.STATE_MOUNTED
        } else if (unknownPids.isNotEmpty()) {
            AppListModel.STATE_UNKNOWN
        } else if (mountedPids.isNotEmpty()) {
            AppListModel.STATE_MOUNT_EXCEPTION
        } else {
            AppListModel.STATE_UNMOUNTED
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
