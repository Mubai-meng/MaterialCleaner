package me.gm.cleaner.runtime.server.process

import android.app.ActivityManager
import android.util.Log
import androidx.annotation.CallSuper
import api.SystemService
import me.gm.cleaner.core.common.RuntimeFileUtils.isIsolatedUid
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.runtime.server.VfsRuntimePolicy
import me.gm.cleaner.runtime.server.lifecycle.BaseObserver
import me.gm.cleaner.runtime.server.vfs.mount.Mounter
import java.util.concurrent.CopyOnWriteArraySet

abstract class BaseProcessObserver : BaseObserver() {
    protected val mounter: Mounter = Mounter()

    val mountedStorage: CopyOnWriteArraySet<Int> = CopyOnWriteArraySet()

    protected fun isMounterActiveForUser(userId: Int): Boolean =
        mountedStorage.contains(userId)

    /**
     * 该 uid 是否落在 Mounter 已接管的用户域内。**负 uid 一律视为不活跃。**
     *
     * 必须先显式拒绝 `uid < 0`，不能只靠 [toUserId] 派生：`toUserId()` 实现是
     * `this / AID_USER_OFFSET`（100000），而 Java/Kotlin 整数除法向零截断，
     * `(-1) / 100000 == 0` —— 负 uid 会被静默当成 **user 0**，于是 user 0 已挂载时
     * 本判据对任意负 uid 都返回 true，守卫彻底失去「过滤非目标」的作用
     * （实测：日志观察器每条未解析进程事件都白跑一次 Mounter Binder 调用）。
     * 负 uid 只可能来自 `PackageInfoMapper.getUid()` 的「拒绝」返回值，
     * 它不是任何包的身份，不该进入任何按 uid 判定的链路。
     */
    protected fun isMounterActiveForUid(uid: Int): Boolean =
        uid >= 0 && isMounterActiveForUser(uid.toUserId())

    private fun getRunningAppProcesses(packageNames: Array<String>): List<ActivityManager.RunningAppProcessInfo> =
        SystemService.getRunningAppProcessesNoThrow().filter { procInfo ->
            !procInfo.uid.isIsolatedUid() &&
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
            !procInfo.uid.isIsolatedUid() &&
                isMounterActiveForUid(procInfo.uid) && procInfo.pkgList.any { packageNames.contains(it) }
        }

    fun remountAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimePolicy.getStorageRedirectPackages()),
            false,
            true,
        )
    }

    fun remountAllWithCheck() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimePolicy.getStorageRedirectPackages()),
            true,
            true,
        )
    }

    fun recordAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimePolicy.getStorageRedirectPackages()),
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

    fun getGateRefusalCount(): Int = mounter.getGateRefusalCount()

    /**
     * 心跳重收敛：取出到期失败包，经正常 remount 链（新鲜 procList +
     * 选择策略 + 隔离过滤 + 身份门）重投，不直调单 pid 挂载。
     */
    fun requeueFailedMounts() {
        val due = mounter.consumeDueFailedPackages()
        if (due.isEmpty()) return
        Log.i("MC_REDIRECT", "[Observer] heartbeat requeue packages=$due")
        mounter.forProcListAsync(
            getRunningAppProcesses(due),
            false,
            true,
        )
    }

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
