package me.gm.cleaner.client.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.util.PermissionUtils
import me.gm.cleaner.util.collatorComparator

abstract class AppListViewModelBase(application: Application) :
    BaseServiceSettingsViewModel(application) {

    protected val _appsFlow: MutableStateFlow<AppListState> =
        MutableStateFlow(AppListState.Loading)
    val isDone: Boolean
        get() = _appsFlow.value is AppListState.Done

    protected val _uninstalledPackagesLiveData: MutableLiveData<List<String>> =
        MutableLiveData<List<String>>(emptyList())
    val uninstalledPackagesLiveData: LiveData<List<String>>
        get() = _uninstalledPackagesLiveData

    val appsFlow: Flow<AppListState> = combine(
        _appsFlow, _isSearchingFlow, _queryTextFlow
    ) { state, isSearching, queryText ->
        when (state) {
            is AppListState.Loading -> return@combine AppListState.Loading
            is AppListState.Error -> return@combine state
            else -> {}
        }
        val list = (state as AppListState.Done).list
        var sequence = list.asSequence()
        if (ServicePreferences.isHideSystemApp) {
            sequence = sequence.filter {
                it.packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
            }
        }
        if (ServicePreferences.isHideDisabledApp) {
            sequence = sequence.filter {
                it.packageInfo.applicationInfo.enabled
            }
        }
        if (ServicePreferences.isHideNoStoragePermissionApp) {
            sequence = sequence.filter {
                PermissionUtils.containsStoragePermissions(it.packageInfo)
            }
        }
        if (isSearching) {
            sequence = sequence.filter {
                it.label.contains(queryText, true) ||
                        it.packageInfo.packageName.contains(queryText, true) ||
                        (it.packageInfo.sharedUserId ?: "").contains(queryText, true)
            }
        }
        AppListState.Done(sequence.sortedWith(appListComparator()).toList())
    }

    /**
     * 应用列表排序的**唯一**入口。
     *
     * 首选项（排序方式 / 规则数 / 挂载状态）每次求值都重新读取 —— 排序菜单可以随时改，
     * 不能缓存。真正的比较链与全序兜底在 [AppListOrdering.comparator]。
     *
     * 旧实现是三层独立的稳定排序（`sortedWith` 之后再 `sortedByDescending` 两次）。
     * 稳定排序本身没问题，三层也确实是「后者为主键」，但三层主键的取值都很稀疏，
     * 残余并列会一路漏到 `PackageManager` 的返回顺序上 —— 那个顺序没有稳定性保证。
     */
    private fun appListComparator(): Comparator<AppListModel> {
        val byUpdateTime = when (ServicePreferences.sortBy) {
            ServicePreferences.SORT_BY_NAME -> false
            ServicePreferences.SORT_BY_UPDATE_TIME -> true
            else -> throw IllegalArgumentException()
        }
        val label = collatorComparator<String> { it }
        return AppListOrdering.comparator(
            mountStateFirst = ServicePreferences.mountState,
            ruleCountFirst = ServicePreferences.ruleCount,
            byUpdateTime = byUpdateTime,
            compareLabel = { o1, o2 -> label.compare(o1, o2) },
        )
    }

    /**
     * 主界面「已挂载应用」列表：过滤与排序的唯一入口。
     *
     * ⚠️ 主界面的**所有**提交路径都必须经由本方法。历史上
     * `AppListFragment.loadMountedApps()` 直接 `submitList(loaded.filter{...})`，
     * 完全没有排序，与 `appsFlow` 那条已排序的路径并发提交、互相覆盖，
     * 列表就在「排序后」与「PackageManager 原始序」之间来回跳。
     */
    fun mountedApps(list: List<AppListModel>): List<AppListModel> =
        AppListOrdering.mounted(appListComparator(), list)

    protected suspend fun loadAppsCommon() {
        if (BuildConfig.DEBUG) Log.i(
            "CleanerTest",
            "AppListViewModelBase.loadAppsCommon: start"
        )
        _appsFlow.value = AppListState.Loading

        val list = try {
            AppListLoader().load()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(
                "CleanerTest",
                "AppListViewModelBase.loadAppsCommon: failed to load apps", e
            )
            _appsFlow.value = AppListState.Error(e.message ?: "Unknown error")
            return
        }
        if (BuildConfig.DEBUG) Log.i(
            "CleanerTest",
            "AppListViewModelBase.loadAppsCommon: list.size=${list.size}"
        )

        val installedPackages = list
            .asSequence()
            .map { it.packageInfo.packageName }
            .toSet()
        val uninstalledPackages =
            (ServicePreferences.getUninstalledSrPackages(installedPackages) +
                    ServicePreferences.getUninstalledReadOnlyPackages(installedPackages) +
                    ServicePreferences.denylist.toSet() - installedPackages).distinct()
        if (BuildConfig.DEBUG) Log.i(
            "CleanerTest",
            "AppListViewModelBase.loadAppsCommon: uninstalledPackages=${uninstalledPackages.size}"
        )
        if (uninstalledPackages.isNotEmpty()) {
            _uninstalledPackagesLiveData.postValue(uninstalledPackages.toMutableList())
        }
        _appsFlow.value = AppListState.Done(list)
    }

    fun updateAppsRuleCount() {
        viewModelScope.launch {
            val value = _appsFlow.value
            if (value is AppListState.Done) {
                _appsFlow.value = AppListState.Loading
                // 重新加载完整列表（更新规则数、挂载状态等）
                val list = try {
                    AppListLoader().load()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("CleanerTest", "updateAppsRuleCount: reload failed", e)
                    value.list
                }
                _appsFlow.value = AppListState.Done(list)
            }
        }
    }
}
