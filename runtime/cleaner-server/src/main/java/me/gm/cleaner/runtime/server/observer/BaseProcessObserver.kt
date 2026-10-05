package me.gm.cleaner.runtime.server.observer

import android.app.ActivityManager
import android.util.Log
import androidx.annotation.CallSuper
import api.SystemService
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.runtime.server.VfsRuntimeConfigStore
import java.util.concurrent.CopyOnWriteArraySet

abstract class BaseProcessObserver : BaseObserver() {
    protected val mounter: Mounter = Mounter()

    val mountedStorage: CopyOnWriteArraySet<Int> = CopyOnWriteArraySet()

    protected fun isMounterActiveForUser(userId: Int): Boolean =
        mountedStorage.contains(userId)

    protected fun isMounterActiveForUid(uid: Int): Boolean = isMounterActiveForUser(uid.toUserId())

    private fun getRunningAppProcesses(packageNames: Array<String>): List<ActivityManager.RunningAppProcessInfo> =
        SystemService.getRunningAppProcessesNoThrow().filter { procInfo ->
            isMounterActiveForUid(procInfo.uid) && procInfo.pkgList.any { packageNames.contains(it) }
        }

    fun remountForPackages(packageNames: Array<String>) {
        // 留痕：规则变更时若目标应用尚未运行，matchedProcs 会是 0——这是**正常**结果，
        // 此时必须依赖 logcat 观察器在进程启动时补挂载。把这条打出来，
        // 就能把"没挂载"区分为"当时没进程可挂"与"根本没匹配到进程"。
        val procs = getRunningAppProcesses(packageNames)
        Log.i("MC_REDIRECT", "[Mounter] remountForPackages packages=${packageNames.toList()} " +
                "matchedProcs=${procs.size}")
        mounter.forProcList(procs, false, true)
    }

    private fun getRunningAppProcesses(packageNames: Iterable<String>): List<ActivityManager.RunningAppProcessInfo> =
        SystemService.getRunningAppProcessesNoThrow().filter { procInfo ->
            isMounterActiveForUid(procInfo.uid) && procInfo.pkgList.any { packageNames.contains(it) }
        }

    fun remountAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimeConfigStore.getStorageRedirectPackages()),
            false,
            true,
        )
    }

    fun remountAllWithCheck() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimeConfigStore.getStorageRedirectPackages()),
            true,
            true,
        )
    }

    fun recordAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimeConfigStore.getStorageRedirectPackages()),
            true,
            false,
        )
    }

    fun isFuseBpfEnabled(): Boolean = mounter.isFuseBpfEnabled

    fun getStartUpAwarePids(packageName: String): Set<Int> = mounter.getRecordedPids(packageName)

    fun getAllStartUpAwarePids(): Set<Int> = mounter.getAllRecordedPids()

    fun getMountFailedPids(): Set<Int> = mounter.getMountFailedPids()

    fun getMountedPackages(): Set<String> = mounter.getMountedPackages()

    fun getTotalMountAttempts(): Int = mounter.getTotalAttempts()

    fun getMountFailureCount(): Int = mounter.getFailureCount()

    fun getMountedDirs(): List<String> = mounter.getMountedDirs()

    fun getLastMountFailure(): Mounter.MountFailure? = mounter.getLastMountFailure()

    /** 最近一次挂载失败对应的统一错误码（ErrorCodes 注册表），无失败时为空串。 */
    fun getLastMountErrorCode(): String = mounter.getLastMountErrorCode()

    fun resetAllMounts() {
        mounter.resetAllMounts()
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        mounter.onDestroy()
    }
}
