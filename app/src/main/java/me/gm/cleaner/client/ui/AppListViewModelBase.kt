package me.gm.cleaner.client.ui

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.config.getUninstalledReadOnlyPackages
import me.gm.cleaner.core.config.getUninstalledSrPackages
import me.gm.cleaner.core.config.readOnlyPackages
import me.gm.cleaner.core.config.srPackages
import me.gm.cleaner.util.PermissionUtils

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
        sequence = sequence.sortedWith(
            buildAppListComparator(
                ServicePreferences.sortBy,
                ServicePreferences.ruleCount,
                ServicePreferences.mountState
            )
        )
        AppListState.Done(sequence.toList())
    }

    protected suspend fun loadAppsCommon() {
        if (BuildConfig.DEBUG) Log.i(
            "CleanerTest",
            "AppListViewModelBase.loadAppsCommon: start"
        )
        _appsFlow.value = AppListState.Loading

        val result = try {
            AppListLoader(context = getApplication()).load()
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
            "AppListViewModelBase.loadAppsCommon: list.size=${result.list.size}, isFullList=${result.isFullList}"
        )
        val list = result.list

        // 仅在服务端全量列表可用时才计算 uninstalledPackages；
        // 本地降级结果里只包含配置了规则的包，直接用作 installed 集合会大面积误报。
        if (result.isFullList) {
            val installedPackages = list
                .asSequence()
                .map { it.packageInfo.packageName }
                .toSet()
            val store = ConfiguredPolicyStoreProvider.instance
            val uninstalledPackages =
                (store.getUninstalledSrPackages(installedPackages) +
                        store.getUninstalledReadOnlyPackages(installedPackages) +
                        ServicePreferences.denylist.toSet() - installedPackages).distinct()
            if (BuildConfig.DEBUG) Log.i(
                "CleanerTest",
                "AppListViewModelBase.loadAppsCommon: uninstalledPackages=${uninstalledPackages.size}"
            )
            if (uninstalledPackages.isNotEmpty()) {
                _uninstalledPackagesLiveData.postValue(uninstalledPackages.toMutableList())
            }
        }
        _appsFlow.value = AppListState.Done(list)
    }

    /** 策略快照或偏好变化后的统一刷新入口：先防抖，再决定增量/全量。 */
    fun onPolicyChanged() {
        schedulePolicyRefresh()
    }

    private fun schedulePolicyRefresh() {
        configChangeJob?.cancel()
        configChangeJob = viewModelScope.launch {
            delay(200)
            handlePolicyChanged()
        }
    }

    private suspend fun handlePolicyChanged() {
        val value = _appsFlow.value
        if (value !is AppListState.Done) return
        val store = ConfiguredPolicyStoreProvider.instance
        val configuredNames = (store.srPackages + store.readOnlyPackages).toSet()
        val currentNames = value.list.map { it.packageInfo.packageName }.toSet()
        val missingConfigured = configuredNames - currentNames
        if (missingConfigured.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "handlePolicyChanged: missing configured=$missingConfigured, full reload")
            loadAppsCommon()
            return
        }
        val updated = AppListLoader(context = getApplication()).updateRuleCount(value.list)
        _appsFlow.value = AppListState.Done(updated)
    }

    private val prefsObserver = Observer<SharedPreferences> { schedulePolicyRefresh() }
    private var configChangeJob: Job? = null

    init {
        ServicePreferences.preferencesChangeLiveData.observeForever(prefsObserver)
        viewModelScope.launch {
            ConfiguredPolicyStoreProvider.instance.snapshots.drop(1).collect { schedulePolicyRefresh() }
        }
    }

    override fun onCleared() {
        ServicePreferences.preferencesChangeLiveData.removeObserver(prefsObserver)
        configChangeJob?.cancel()
        super.onCleared()
    }
}
