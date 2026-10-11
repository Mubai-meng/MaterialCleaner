package me.gm.cleaner.client.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.LaunchReason
import me.gm.cleaner.client.ServerStateMachine

class AppListViewModel(application: Application) : AppListViewModelBase(application) {
    /** Expose queryText changes for logging (debug only). */
    val queryTextFlow: Flow<String> get() = _queryTextFlow

    /** 在飞的加载任务：`init` 与 `onResume` 都会调 [loadApps]，不必跑两遍全量。 */
    private var loadJob: Job? = null

    /**
     * 加载列表。
     *
     * **不再等待服务启动**：[ServerStateMachine.recoverIfTargetRunning] 在服务不存活时
     * 会走完整启动流程（root 校验 → kill → 1s → 写数据文件 → shell →
     * `waitForBinder` 20×500ms），慢路径最坏阻塞 10s 以上；而"已配置应用"只依赖
     * 本地配置 + PackageManager，**不需要服务在线**。把两者串起来就是主界面
     * 列表「慢半拍」的直接原因。改为：先出列表，再恢复服务，必要时补一次全量。
     */
    fun loadApps() {
        if (loadJob?.isActive == true) {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListViewModel.loadApps: already running, skipped")
            return
        }
        loadJob = viewModelScope.launch {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListViewModel.loadApps: start")
            _appsFlow.value = AppListState.Loading
            val isFullList = loadAppsCommon()
            val recovered =
                ServerStateMachine.recoverIfTargetRunning(getApplication(), LaunchReason.RECOVERY)
            if (AppListLoadPlan.shouldReloadAfterRecovery(isFullList, recovered)) {
                loadAppsCommon()
            }
        }
    }

    init {
        loadApps()
    }
}

sealed class AppListState {
    data object Loading : AppListState()
    data class Done(val list: List<AppListModel>) : AppListState()
    data class Error(val message: String) : AppListState()
}
