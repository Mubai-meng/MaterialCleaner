package me.gm.cleaner.client.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.PolicyRuleCountIndex
import me.gm.cleaner.core.config.policyRuleCountIndex
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
        val t0 = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: start loading packages")
        val installedPackages = CleanerClient.getInstalledPackagesOrNull(PackageManager.GET_PERMISSIONS)
        val t1 = SystemClock.elapsedRealtime()
        if (installedPackages == null) {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: service unavailable or RPC failed, fallback")
            val fallback = loadLocalFallback()
            val t2 = SystemClock.elapsedRealtime()
            logTiming(fallback.size, false, t1 - t0, 0L, 0L, t2 - t1, 0L)
            return@withContext AppListLoadResult(fallback, isFullList = false)
        }
        if (installedPackages.isNotEmpty()) {
            AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
        } else if (BuildConfig.DEBUG) {
            Log.w("CleanerTest", "AppListLoader.load: server returned empty installed list, keep previous label cache")
        }
        val t2 = SystemClock.elapsedRealtime()
        val srPackageStatus = CleanerClient.getSrPackagesStatusOrNull(PackageStatus.GET_FROM_ALL_PROCESS)
        val t3 = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: srPackageStatus size=${srPackageStatus?.size}")
        // 服务端映射就绪且查无记录等于真无，未就绪或链路失败一律按未知处理，不混用空表示两种含义。
        val statusUnknown = srPackageStatus == null
        // 一次读盘建「包名 → 规则数」索引。逐包调 getPackageSrCount()/getPackageReadOnly()
        // 会各自重新读文件 + 解析 JSON，531 个包 = 1062 次全量解析（实测 map=1450ms）。
        val counts = ConfiguredPolicyStoreProvider.instance.policyRuleCountIndex()
        val result = installedPackages.map { pi ->
            ensureActive()
            val packageName = pi.packageName
            AppListModel(
                pi,
                AppLabelCache.getPackageLabel(pi),
                counts.srCount(packageName),
                counts.readOnlyCount(packageName),
                parseMountState(if (statusUnknown) null else srPackageStatus?.get(packageName), statusUnknown)
            )
        }
        val t4 = SystemClock.elapsedRealtime()
        // 主界面只显示「已配置应用」，在发布列表之前把它们的图标预取进缓存，
        // 绑定阶段就不会在主线程逐条走 PackageManager.loadIcon()。
        preloadConfiguredIcons(installedPackages, counts)
        val t5 = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: result=${result.size} apps")
        logTiming(result.size, true, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4)
        AppListLoadResult(result, isFullList = true)
    }

    /**
     * 分阶段耗时（release 也打）。
     *
     * 为什么必须打：主界面列表"慢半拍"的候选原因有四五个（服务端安装列表 RPC、
     * 625 个包的应用名解析、挂载状态 RPC、模型映射），而在 release 构建里
     * `BuildConfig.DEBUG` 为 false、`CleanerTest` 一条都不输出，
     * 光看源码无法定位。每条加载一行，代价可忽略。
     */
    private fun logTiming(
        size: Int,
        full: Boolean,
        getInstalled: Long,
        labels: Long,
        status: Long,
        map: Long,
        icons: Long,
    ) {
        Log.i(
            TAG, "load: apps=" + size + " full=" + full +
                " getInstalled=" + getInstalled + "ms" +
                " labels=" + labels + "ms" +
                " status=" + status + "ms" +
                " map=" + map + "ms" +
                " icons=" + icons + "ms" +
                " total=" + (getInstalled + labels + status + map + icons) + "ms"
        )
    }

    private companion object {
        const val TAG = "MC/AppList"

        /** 图标预取时间预算：超出后放弃剩余项（退回懒加载），避免拖慢首屏。 */
        const val ICON_PRELOAD_BUDGET_MS = 400L
    }

    /**
     * 后台预取「已配置应用」的图标。
     *
     * 主界面只显示 `mountRulesCount > 0 || readOnlyCount > 0` 的包（本机实测 15 个），
     * 但绑定阶段拿不到"哪些会显示"，只能在 `onBindViewHolder` 主线程懒加载
     * `PackageManager.loadIcon()` —— 实测 15 个 item 合计约 1.6s（单条 30–400ms）。
     * 这里用一次有预算的 IO 预取，换取绑定阶段全命中。
     *
     * @param budgetMs 时间预算：超出即放弃剩余项（它们退回懒加载），不拖慢首屏。
     */
    private suspend fun preloadConfiguredIcons(
        packages: List<PackageInfo>,
        counts: PolicyRuleCountIndex,
        budgetMs: Long = ICON_PRELOAD_BUDGET_MS,
    ) {
        val configured = packages.filter {
            counts.srCount(it.packageName) > 0 || counts.readOnlyCount(it.packageName) > 0
        }
        if (configured.isEmpty()) {
            return
        }
        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            for (pi in configured) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    break
                }
                AppIconCache.preloadOne(context, pi)
            }
        }
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
        val counts = store.policyRuleCountIndex()
        return installedPackages.mapNotNull { pi ->
            val packageName = pi.packageName
            val label = AppLabelCache.getLabelIfCached(packageName)
                ?: pi.applicationInfo?.let { it.loadLabel(context.packageManager).toString() }
                ?: packageName
            AppListModel(
                pi,
                label,
                counts.srCount(packageName),
                counts.readOnlyCount(packageName),
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
            // 与 load() 同理：刷新规则计数必须用索引，否则每 200ms 防抖刷新就是又一轮 1062 次解析。
            val counts = ConfiguredPolicyStoreProvider.instance.policyRuleCountIndex()
            old.map {
                val packageName = it.packageInfo.packageName
                it.copy(
                    mountRulesCount = counts.srCount(packageName),
                    readOnlyCount = counts.readOnlyCount(packageName),
                    mountState = if (srPackageStatus == null) AppListModel.STATE_UNKNOWN else parseMountState(srPackageStatus[packageName], false),
                )
            }
        }
}
