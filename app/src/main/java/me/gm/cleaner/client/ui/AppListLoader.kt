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
        val serviceAvailable = try {
            CleanerClient.pingBinder() && CleanerClient.service != null
        } catch (e: Exception) {
            false
        }
        if (!serviceAvailable) {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: service unavailable, using local fallback")
            return@withContext AppListLoadResult(loadLocalFallback(), isFullList = false)
        }
        val installedPackages = try {
            CleanerClient.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("CleanerTest", "AppListLoader.load: failed to load packages", e)
            null
        }
        if (installedPackages == null) {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: binder call failed, using local fallback")
            return@withContext AppListLoadResult(loadLocalFallback(), isFullList = false)
        }
        if (installedPackages.isNotEmpty()) {
            AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
        } else if (BuildConfig.DEBUG) {
            Log.w("CleanerTest", "AppListLoader.load: server returned empty installed list, keep previous label cache")
        }
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
                ConfiguredPolicyStoreProvider.instance.getPackageSrCount(pi.packageName),
                ConfiguredPolicyStoreProvider.instance.getPackageReadOnly(pi.packageName).size,
                parseMountState(srPackageStatus[pi.packageName])
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

    private fun parseMountState(packageStatus: PackageStatus?): Int {
        packageStatus ?: return AppListModel.STATE_UNMOUNTED
        val mountedPids = mutableListOf<Int>()
        val unknownPids = mutableListOf<Int>()
        packageStatus.pidFlags.forEachIndexed { index, pidFlag ->
            if (pidFlag and PackageStatus.PID_FLAG_MOUNTED != 0) {
                mountedPids += packageStatus.pids[index]
            }
            if (pidFlag and PackageStatus.PID_FLAG_UNKNOWN != 0) {
                unknownPids += packageStatus.pids[index]
            }
        }
        return if (packageStatus.pids.isNotEmpty()
            && packageStatus.pids.size == mountedPids.size) {
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
            val stateQueryFailed = try {
                CleanerClient.service == null || !CleanerClient.pingBinder()
            } catch (e: Exception) {
                true
            }
            val srPackageStatus = if (stateQueryFailed) {
                emptyMap()
            } else {
                try {
                    CleanerClient.service?.getSrPackagesStatus(
                        PackageStatus.GET_FROM_ALL_PROCESS
                    ) ?: emptyMap()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("CleanerTest", "AppListLoader.updateRuleCount: getSrPackagesStatus failed", e)
                    emptyMap()
                }
            }
            old.map {
                val packageName = it.packageInfo.packageName
                it.copy(
                    mountRulesCount = ConfiguredPolicyStoreProvider.instance.getPackageSrCount(packageName),
                    readOnlyCount = ConfiguredPolicyStoreProvider.instance.getPackageReadOnly(packageName).size,
                    mountState = if (stateQueryFailed) AppListModel.STATE_UNKNOWN else parseMountState(srPackageStatus[packageName]),
                )
            }
        }
}
