package me.gm.cleaner.runtime.server

import android.app.ActivityManager.RunningAppProcessInfo
import android.os.storage.VolumeInfo
import android.util.Log
import api.SystemService
import hidden.HiddenApiBridge.UserHandle_isIsolated
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.model.PackageStatus
import me.gm.cleaner.runtime.server.process.BaseProcessObserver
import me.gm.cleaner.runtime.server.process.PackageInfoMapper
import me.gm.cleaner.runtime.server.lifecycle.ObserverManager
import me.gm.cleaner.runtime.server.storage.StorageEventListenerDelegate
import me.gm.cleaner.runtime.server.orchestrator.LayerId
import me.gm.cleaner.runtime.server.orchestrator.LayerReport
import me.gm.cleaner.runtime.server.orchestrator.LayerState
import java.io.File
import java.util.TreeMap

/**
 * VFS bind mount 层的生命周期门面。
 *
 * CleanerServer 只转交系统存储事件；本类负责与现有 observer/mounter 体系交互，
 * 让 VFS 层细节集中在单一边界内。
 */
class VfsLayerController {

    private companion object {
        const val TAG = "VfsLayerController"
    }

    fun isFuseBpfEnabled(): Boolean {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        return observer != null && observer.isFuseBpfEnabled()
    }

    fun onStorageMounted(vol: VolumeInfo, isPrimary: Boolean, isJustMounted: Boolean) {
        if (isPrimary) {
            RuntimeFileUtils.setExternalStorageDir(File(vol.path, "0"))
        }
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        if (observer != null) {
            val mountUserId = StorageEventListenerDelegate.getMountUserId(vol)
            observer.mountedStorage.add(mountUserId)
            if (isJustMounted) {
                if (isPrimary) {
                    observer.remountAll()
                } else {
                    observer.remountAllWithCheck()
                }
            } else if (isPrimary) {
                observer.recordAll()
            }
        }
        if (isPrimary && observer != null && observer.isFuseBpfEnabled()) {
            switchAppDataDirOwnersAsync()
        }
    }

    fun onStorageUnmounted(vol: VolumeInfo) {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java) ?: return
        val mountUserId = StorageEventListenerDelegate.getMountUserId(vol)
        observer.mountedStorage.remove(mountUserId)
    }

    fun remount(packageNames: Array<String>) {
        ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?.remountForPackages(packageNames)
    }

    fun getMountedDirs(): List<String> {
        return ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?.getMountedDirs()
            ?: emptyList()
    }

    fun shutdown() {
        ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?.resetAllMounts()
    }

    fun getPackageStatus(packageName: String, flags: Int): PackageStatus {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?: return PackageStatus()
        val startUpAwarePids = observer.getStartUpAwarePids(packageName)
        val processes = selectProcesses(flags, startUpAwarePids)
        val status = PackageStatus()
        val pids = mutableListOf<Int>()
        val pidFlags = mutableListOf<Int>()
        val userIds = mutableListOf<Int>()
        val mountFailedPids = observer.getMountFailedPids()
        val mkdir = observer.getMountedPackages().contains(packageName)
        // 只有已配置重定向的包才走 uid 权威判定，非重定向包保持 pkgList 语义，
        // 避免把不在重定向配置中的查询误判成没有进程。
        val uidMappingReady = VfsRuntimePolicy.getStorageRedirectPackages()
            .contains(packageName) && PackageInfoMapper.isMappingReady()

        processes
            .asSequence()
            .filter { !UserHandle_isIsolated(RuntimeFileUtils.read_uid(it.pid)) }
            .sortedBy { it.pid }
            .forEach { procInfo ->
                if (!isProcessOfPackage(procInfo, packageName, uidMappingReady)) return@forEach
                val userId = procInfo.uid.toUserId()
                pids.add(procInfo.pid)
                pidFlags.add(buildPidFlag(
                    procInfo.pid,
                    packageName,
                    userId,
                    startUpAwarePids,
                    mountFailedPids,
                    mkdir,
                ))
                userIds.add(userId)
            }

        status.pids = pids.toIntArray()
        status.pidFlags = pidFlags.toIntArray()
        status.userIds = userIds.toIntArray()
        return status
    }

    fun getSrPackagesStatus(flags: Int): Map<String, PackageStatus> {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?: return emptyMap()
        val allStartUpAwarePids = observer.getAllStartUpAwarePids()
        val mountFailedPids = observer.getMountFailedPids()
        val mountedPackages = observer.getMountedPackages()
        val srPackages = VfsRuntimePolicy.getStorageRedirectPackages()
        val processes = selectProcesses(flags, allStartUpAwarePids)
        // 包级 pidRecords。getAllStartUpAwarePids() 只回答"这个 pid 被**任意**包接管过"，
        // 回答不了"是不是被**本包**接管"；那正是区分"未接管"和"挂了"的关键，
        // 所以按包各取一份（N = 已配置包数，通常个位数）。
        val recordedPidsByPackage = srPackages.associateWith { observer.getStartUpAwarePids(it) }
        val uidMappingReady = PackageInfoMapper.isMappingReady()
        val statuses = TreeMap<String, MutablePackageStatus>()

        processes
            .asSequence()
            .filter { !UserHandle_isIsolated(RuntimeFileUtils.read_uid(it.pid)) }
            .sortedBy { it.pid }
            .forEach { procInfo ->
                val packageName =
                    resolveSrPackageName(procInfo, srPackages, uidMappingReady) ?: return@forEach
                val userId = procInfo.uid.toUserId()
                val status = statuses.getOrPut(packageName) { MutablePackageStatus() }
                status.pids.add(procInfo.pid)
                status.pidFlags.add(buildPidFlag(
                    procInfo.pid,
                    packageName,
                    userId,
                    recordedPidsByPackage[packageName] ?: emptySet(),
                    mountFailedPids,
                    mountedPackages.contains(packageName),
                ))
                status.userIds.add(userId)
            }

        return statuses.mapValues { (_, value) -> value.toPackageStatus() }
    }

    /**
     * 把进程归属到**唯一**一个重定向包；null 表示它不属于任何重定向包。
     *
     * 为什么不能只用 `procInfo.pkgList`：pkgList 回答的是"这个进程里有哪些包"，
     * 而托管了他人组件的宿主进程（OEM 推送通道那类）会把别人的包名也列进来。
     * 那种进程永远不会是重定向对象，`check_mounts` 必然一个 target 都命不中，
     * 一旦并入本包就会被判成"异常"，且随宿主进程生灭而抖动。
     * 改用 uid（sharedUserId 场景下退化为进程名）→ 包名的权威映射即可天然剔除。
     */
    private fun resolveSrPackageName(
        procInfo: RunningAppProcessInfo,
        srPackages: Set<String>,
        uidMappingReady: Boolean,
    ): String? {
        if (!uidMappingReady) {
            // 映射表未就绪：保守回退到旧的 pkgList 语义。
            // 这里绝不能直接 return null —— 那会把**所有**进程判成非目标，
            // 让每个包都显示"未挂载"，是比误报更糟的静默降级。
            return procInfo.pkgList?.firstOrNull { srPackages.contains(it) }
        }
        val srPackageName = PackageInfoMapper.getSrPackageName(procInfo.uid, procInfo.processName)
        return if (srPackageName != null && srPackages.contains(srPackageName)) srPackageName else null
    }

    private fun isProcessOfPackage(
        procInfo: RunningAppProcessInfo,
        packageName: String,
        uidMappingReady: Boolean,
    ): Boolean {
        if (!uidMappingReady) {
            return procInfo.pkgList?.contains(packageName) == true
        }
        return PackageInfoMapper.getSrPackageName(procInfo.uid, procInfo.processName) == packageName
    }

    fun collectReport(generation: Long, now: Long): LayerReport {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        return if (observer != null) {
            val mountedPackages = observer.getMountedPackages().size
            val recordedPids = observer.getAllStartUpAwarePids().size
            val mountFailedPids = observer.getMountFailedPids().size
            val mountTotalAttempts = observer.getTotalMountAttempts()
            val mountFailureCount = observer.getMountFailureCount()
            val mountGateRefusals = observer.getGateRefusalCount()
            val lastFailure = observer.getLastMountFailure()
            val lastMountErrorCode = observer.getLastMountErrorCode()
            val state = if (mountFailedPids > 0) {
                LayerState.DEGRADED
            } else {
                LayerState.HEALTHY
            }
            LayerReport(
                id = LayerId.VFS,
                state = state,
                generation = generation,
                lastHeartbeatAt = if (state == LayerState.HEALTHY) now else 0L,
                lastErrorAt = if (state == LayerState.HEALTHY) 0L else now,
                lastError = if (state == LayerState.HEALTHY) {
                    null
                } else {
                    "VFS mount failures detected"
                },
                metrics = mapOf(
                    "started" to "true",
                    "configuredPackages" to VfsRuntimePolicy
                        .getStorageRedirectPackages()
                        .size
                        .toString(),
                    "mountedPackages" to mountedPackages.toString(),
                    // 逐 pid 的挂载分类现场（pkg=u<userId>:<pid>#<flag>;…）。
                    // 存在的意义：ColorOS 按进程限流 logcat（约 300 行），
                    // 关键证据会被吃掉；而快照文件完全免疫。
                    // 形如 "com.x=y" 的挂载状态争议，靠它可直接定位到 pid 与 flag，
                    // 不必再靠"读代码猜"。
                    "srStatus" to buildSrStatusSummary(),
                    "recordedPids" to recordedPids.toString(),
                    "mountFailedPids" to mountFailedPids.toString(),
                    "mountTotalAttempts" to mountTotalAttempts.toString(),
                    "mountFailureCount" to mountFailureCount.toString(),
                    "mountGateRefusals" to mountGateRefusals.toString(),
                    "lastMountFailureAt" to (lastFailure?.timeMillis ?: 0L).toString(),
                    "lastMountFailurePackage" to (lastFailure?.packageName ?: ""),
                    "lastMountFailurePid" to (lastFailure?.pid ?: 0).toString(),
                    "lastMountFailureUid" to (lastFailure?.uid ?: 0).toString(),
                    "lastMountFailureReason" to (lastFailure?.reason ?: ""),
                    "lastMountFailureStage" to (lastFailure?.stage ?: ""),
                    "lastMountFailureErrno" to (lastFailure?.errno ?: 0).toString(),
                    "lastMountFailureIndex" to (lastFailure?.failedIndex ?: -1).toString(),
                    "lastMountFailureSource" to (lastFailure?.source ?: ""),
                    "lastMountFailureTarget" to (lastFailure?.target ?: ""),
                    "topErrorCode" to lastMountErrorCode,
                    "lastMountRetryable" to (lastFailure?.retryable ?: false).toString(),
                    "lastMountNamespaceDirty" to (lastFailure?.namespaceDirty ?: false).toString(),
                    "lastMountForceStopAttempted" to
                            (lastFailure?.forceStopAttempted ?: false).toString(),
                    "lastMountForceStopSucceeded" to
                            (lastFailure?.forceStopSucceeded ?: false).toString(),
                ),
            )
        } else {
            LayerReport(
                id = LayerId.VFS,
                state = LayerState.UNAVAILABLE,
                generation = generation,
                lastErrorAt = now,
                lastError = "BaseProcessObserver unavailable",
                metrics = mapOf("started" to "false"),
            )
        }
    }

    /**
     * 逐 pid 的挂载分类现场，供快照指标使用。
     *
     * 格式：`pkg=u<userId>:<pid>#<flag-hex>,…;…`
     *
     * 成本与 [getSrPackagesStatus] 相同：每个 (包, pid) 一次 native `check_mounts`
     * （读一遍 `/proc/<pid>/mountinfo`）。N = 已配置包数、M = 每包进程数，通常个位数，
     * 相对 5 s 的快照周期可忽略。
     */
    private fun buildSrStatusSummary(): String {
        val statuses = runCatching { getSrPackagesStatus(PackageStatus.GET_FROM_ALL_PROCESS) }
            .getOrElse {
                Log.w(TAG, "srStatus summary unavailable", it)
                return "unavailable"
            }
        if (statuses.isEmpty()) return "none"
        return statuses.entries.joinToString(";") { (packageName, status) ->
            val pids = status.pids.indices.joinToString(",") { index ->
                "u${status.userIds.getOrElse(index) { 0 }}:${status.pids[index]}" +
                        "#${status.pidFlags[index].toString(16)}"
            }
            "$packageName=$pids"
        }
    }

    private fun switchAppDataDirOwnersAsync() {
        Thread {
            for (userId in SystemService.getUserIdsNoThrow()) {
                for (packageName in VfsRuntimePolicy.getStorageRedirectPackages()) {
                    val ai = SystemService.getApplicationInfoNoThrow(packageName, 0, userId)
                        ?: continue
                    RuntimeFileUtils.switch_owner(
                        RuntimeFileUtils.getPathAsUser(
                            RuntimeFileUtils.buildExternalStorageAppDataDirs(packageName).path,
                            userId,
                        ),
                        ai.uid,
                        true,
                    )
                }
            }
        }.start()
    }

    private fun selectProcesses(flags: Int, startUpAwarePids: Set<Int>): List<RunningAppProcessInfo> {
        val processes = SystemService.getRunningAppProcessesNoThrow()
        return when (flags) {
            PackageStatus.GET_FROM_ALL_PROCESS -> processes
            PackageStatus.GET_FROM_RECORDS -> processes.filter { startUpAwarePids.contains(it.pid) }
            else -> emptyList()
        }
    }

    /**
     * 单 pid 的挂载状态分类。**必须覆盖所有取值组合**。
     *
     * 旧实现缺 else 分支：`check_mounts` 成功、无负值、但命中数少于 target 数时
     * 得到 base flag = 0（既非 MOUNTED 也非 UNKNOWN），调用侧只能靠兜底分支猜，
     * 于是同一种状况会随该包进程数在"未挂载"与"挂载异常"之间漂移。
     */
    private fun buildPidFlag(
        pid: Int,
        packageName: String,
        userId: Int,
        startUpAwarePids: Set<Int>,
        mountFailedPids: Set<Int>,
        mkdir: Boolean,
    ): Int {
        val targets = VfsRuntimePolicy.getMountTargets(packageName, userId)
        val mountedIndices = RuntimeFileUtils.check_mounts(pid, targets.toTypedArray())
        var pidFlag = 0
        when {
            mountedIndices == null ->
                pidFlag = pidFlag or PackageStatus.PID_FLAG_UNKNOWN

            mountedIndices.any { it < 0 } -> {
                // 挂载点被 //deleted 或被子挂载前缀覆盖：都算"没挂上"，但要分开记原因。
                if (mountedIndices.contains(-1)) {
                    pidFlag = pidFlag or PackageStatus.PID_FLAG_DELETED
                }
                if (mountedIndices.contains(-2)) {
                    pidFlag = pidFlag or PackageStatus.PID_FLAG_OVERRIDE
                }
            }
            // target 为空 = 无规则可挂，视为完成（与旧行为一致）。
            targets.isEmpty() ->
                pidFlag = pidFlag or PackageStatus.PID_FLAG_MOUNTED

            mountedIndices.size == targets.size ->
                pidFlag = pidFlag or PackageStatus.PID_FLAG_MOUNTED

            mountedIndices.isEmpty() ->
                pidFlag = pidFlag or PackageStatus.PID_FLAG_NOT_MOUNTED

            else ->
                pidFlag = pidFlag or PackageStatus.PID_FLAG_PARTIALLY_MOUNTED
        }
        // STARTUP_AWARE = 被**本包**接管过；否则 UNMANAGED = 压根不是我们的对象。
        // 二者互斥，调用侧据此判断该 pid 是否应计入分母。
        if (startUpAwarePids.contains(pid)) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_STARTUP_AWARE
        } else {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_UNMANAGED
        }
        if (mountFailedPids.contains(pid)) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_MOUNT_FAILED
        }
        if (!mkdir) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_MKDIR_FAILED
        }
        return pidFlag
    }

    private class MutablePackageStatus {
        val pids = mutableListOf<Int>()
        val pidFlags = mutableListOf<Int>()
        val userIds = mutableListOf<Int>()

        fun toPackageStatus(): PackageStatus {
            val status = PackageStatus()
            status.pids = pids.toIntArray()
            status.pidFlags = pidFlags.toIntArray()
            status.userIds = userIds.toIntArray()
            return status
        }
    }
}
