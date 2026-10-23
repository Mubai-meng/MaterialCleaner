package me.gm.cleaner.runtime.server

import android.app.ActivityManager.RunningAppProcessInfo
import android.os.storage.VolumeInfo
import api.SystemService
import hidden.HiddenApiBridge.UserHandle_isIsolated
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.model.PackageStatus
import me.gm.cleaner.runtime.server.process.BaseProcessObserver
import me.gm.cleaner.runtime.server.process.PackageInfoMapper
import me.gm.cleaner.runtime.server.process.ProcessIdentity
import me.gm.cleaner.runtime.server.vfs.CensusEntry
import me.gm.cleaner.runtime.server.vfs.CensusIsolationPolicy
import me.gm.cleaner.runtime.server.vfs.CensusVerdict
import me.gm.cleaner.runtime.server.vfs.PidMountClassifier
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensus
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensusBuilder
import me.gm.cleaner.runtime.server.vfs.VfsReportKeys
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
        /** 普查节流：collectReport 每 2s 心跳复用缓存，避免诊断成为负载源。 */
        private const val CENSUS_THROTTLE_MS = 30_000L
    }

    @Volatile
    private var lastCensusAt: Long = 0L

    @Volatile
    private var cachedCensus: VfsProcessCensus? = null

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
        val processes = selectProcesses(flags, observer.getStartUpAwarePids(packageName))
        val status = PackageStatus()
        val pids = mutableListOf<Int>()
        val pidFlags = mutableListOf<Int>()
        val userIds = mutableListOf<Int>()
        val startUpAwarePids = observer.getStartUpAwarePids(packageName)
        val mountFailedPids = observer.getMountFailedPids()
        val mkdir = observer.getPreparedPackages().contains(packageName)
        // 只有已配置重定向的包才走 uid 权威判定，非重定向包保持 pkgList 语义，
        // 避免把不在重定向配置中的查询误判成没有进程。
        val uidMappingReady = VfsRuntimePolicy.getStorageRedirectPackages()
            .contains(packageName) && PackageInfoMapper.isMappingReady()

        // 先固定本轮身份快照，遍历中途的 pid 复用才可被 PidInstanceFreshness 识别。
        val instanceSnapshot = captureInstanceSnapshot(processes.asSequence().map { it.pid })
        processes
            .asSequence()
            .sortedBy { it.pid }
            .forEach { procInfo ->
                if (!isProcessOfPackage(procInfo, packageName, uidMappingReady)) return@forEach
                // 隔离判定刻意放在归属判定**之后**：read_uid 要打开 /proc/<pid>/cgroup，
                // 对全部进程先做一次（实测 ~120 个 pid）纯属浪费；而归属不到重定向包的
                // 隔离进程本来就会被上面这行过滤掉，延后判定不改变任何计数。
                if (isExcludedForCensus(procInfo.pid)) return@forEach
                if (skipStaleInstance(procInfo.pid, packageName, instanceSnapshot)) return@forEach
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
        return enumerateSrProcesses(flags).statuses.mapValues { (_, value) -> value.toPackageStatus() }
    }

    /**
     * P1-C 普查（节流 30s）：与 [collectReport] 复用同一次采样，产出分母与
     * 有界 srStatus 明细。诊断投影，不驱动接管行为；失败只降诊断能力。
     */
    fun collectCensus(now: Long): VfsProcessCensus {
        cachedCensus?.let {
            if (now >= lastCensusAt && now - lastCensusAt < CENSUS_THROTTLE_MS) return it
        }
        val enumeration = enumerateSrProcesses(PackageStatus.GET_FROM_ALL_PROCESS)
        val census = VfsProcessCensusBuilder.build(
            sampledAt = now,
            entries = enumeration.entries,
            unattributedPids = enumeration.unattributedPids,
        )
        lastCensusAt = now
        cachedCensus = census
        return census
    }

    private data class SrEnumeration(
        val statuses: TreeMap<String, MutablePackageStatus>,
        val entries: List<CensusEntry>,
        val unattributedPids: Int,
    )

    private fun enumerateSrProcesses(flags: Int): SrEnumeration {
        val empty = SrEnumeration(TreeMap(), emptyList(), 0)
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?: return empty
        val startUpAwarePids = observer.getAllStartUpAwarePids()
        val mountFailedPids = observer.getMountFailedPids()
        val preparedPackages = observer.getPreparedPackages()
        val srPackages = VfsRuntimePolicy.getStorageRedirectPackages()
        val processes = selectProcesses(flags, startUpAwarePids)
        // 映射就绪用 uid 权威归属，未就绪保守回退 pkgList，绝不直接返回空。
        val uidMappingReady = PackageInfoMapper.isMappingReady()
        val statuses = TreeMap<String, MutablePackageStatus>()
        val entries = mutableListOf<CensusEntry>()
        var unattributed = 0

        // 与 getPackageStatus 同口径：先固定身份快照，避免陈旧 pid 混入分母。
        val instanceSnapshot = captureInstanceSnapshot(processes.asSequence().map { it.pid })
        processes
            .asSequence()
            .sortedBy { it.pid }
            .forEach { procInfo ->
                val packageName =
                    resolveSrPackageName(procInfo, srPackages, uidMappingReady)
                if (packageName == null) {
                    unattributed++
                    return@forEach
                }
                // 与 getPackageStatus 同口径：只对已归属到重定向包的 pid 付出
                // /proc/<pid>/cgroup 的读取成本（实测从 ~120 次降到个位数）。
                // 被排除的隔离进程既不进 entries 也不计入 unattributed，
                // 与「过滤前置」时的行为完全一致。
                if (isExcludedForCensus(procInfo.pid)) return@forEach
                if (skipStaleInstance(procInfo.pid, packageName, instanceSnapshot)) return@forEach
                val userId = procInfo.uid.toUserId()
                val flag = buildPidFlag(
                    procInfo.pid,
                    packageName,
                    userId,
                    startUpAwarePids,
                    mountFailedPids,
                    preparedPackages.contains(packageName),
                )
                val status = statuses.getOrPut(packageName) { MutablePackageStatus() }
                status.pids.add(procInfo.pid)
                status.pidFlags.add(flag)
                status.userIds.add(userId)
                entries.add(CensusEntry(packageName, procInfo.pid, procInfo.uid, flag))
            }

        return SrEnumeration(statuses, entries, unattributed)
    }

    fun collectReport(generation: Long, now: Long): LayerReport {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        return if (observer != null) {
            val preparedPackages = observer.getPreparedPackages().size
            val recordedPids = observer.getAllStartUpAwarePids().size
            val mountFailedPids = observer.getMountFailedPids().size
            val mountTotalAttempts = observer.getTotalMountAttempts()
            val mountFailureCount = observer.getMountFailureCount()
            val mountGateRefusals = observer.getGateRefusalCount()
            val lastFailure = observer.getLastMountFailure()
            val lastMountErrorCode = observer.getLastMountErrorCode()
            // 同一次普查采样复用：分母与 srStatus 明细同源，诊断失败不改行为。
            val census = runCatching { collectCensus(now) }.getOrNull()
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
                    VfsReportKeys.CONFIGURED_PACKAGES to VfsRuntimePolicy
                        .getStorageRedirectPackages()
                        .size
                        .toString(),
                    VfsReportKeys.PREPARED_PACKAGES to preparedPackages.toString(),
                    // 实时值必须带自己的采样时刻：它和下面的 census 缓存值
                    // （vfsObservedPids/vfsMountedPids/srStatus，30s 节流）不是同一
                    // 时刻的快照，缺时点就会被读成「模块自相矛盾」（第 42/43 批连续两次）。
                    VfsReportKeys.RECORDED_PIDS to recordedPids.toString(),
                    VfsReportKeys.RECORDED_PIDS_AT to now.toString(),
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
                ) + (census?.toMetrics() ?: emptyMap()),
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

    /**
     * 把进程归属到唯一一个重定向包，空表示它不属于任何重定向包。
     * 不能只用 pkgList：宿主进程会把他人包名也列进来，那种进程永远不是重定向对象，
     * 一旦并入就会被判成异常且随宿主生灭抖动。改用 uid（共享 uid 场景退化为进程名）权威映射天然剔除。
     * 映射未就绪时保守回退旧 pkgList 语义，绝不直接返回空，避免把全部进程判成非目标。
     */
    private fun resolveSrPackageName(
        procInfo: RunningAppProcessInfo,
        srPackages: Set<String>,
        uidMappingReady: Boolean,
    ): String? {
        if (!uidMappingReady) {
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

    private fun selectProcesses(flags: Int, startUpAwarePids: Set<Int>): List<RunningAppProcessInfo> {
        val processes = SystemService.getRunningAppProcessesNoThrow()
        return when (flags) {
            PackageStatus.GET_FROM_ALL_PROCESS -> processes
            PackageStatus.GET_FROM_RECORDS -> processes.filter { startUpAwarePids.contains(it.pid) }
            else -> emptyList()
        }
    }

    /**
     * 采集本轮普查的 pid → 进程实例身份（starttime）快照，作为**局部值**返回。
     *
     * 刻意不做成实例字段：`getPackageStatus` 是 Binder 接口，可被 app 侧并发调用；
     * 共享字段会出现「A 的调用覆盖 B 的快照」，导致 B 把正常进程误判成 pid 复用
     * 而跳过（或反过来放过陈旧 pid）。每轮一个局部快照即无此问题。
     *
     * 用途：识别 PID 复用。`/proc/<pid>` 存在并不代表还是原来那个进程 ——
     * 目标进程退出后内核可能把同一个 pid 分给新进程，此时 `check_mounts`
     * 读到的是**别人的** mountinfo，空数组会被误判成 NOT_MOUNTED。
     * 第 38 批实测即此形态（`vfsNotMountedPids=3`，UI 同时显示「已挂载」）。
     */
    private fun captureInstanceSnapshot(pids: Sequence<Int>): Map<Int, Long> {
        val snapshot = HashMap<Int, Long>()
        pids.forEach { pid ->
            val startTime = runCatching {
                ProcessIdentity.currentInstance(pid)?.startTime
            }.getOrNull() ?: return@forEach
            snapshot[pid] = startTime
        }
        return snapshot
    }

    /**
     * 该 pid 是否应从 VFS 普查中排除（隔离进程 / uid 不可读）。
     *
     * 注意默认值：**读不到 uid 时排除，而不是放行**。
     * 放行会让隔离进程过滤整体失效（read_uid 在 cgroup v2 下恒 -1 的真机实证）。
     */
    private fun isExcludedForCensus(pid: Int): Boolean {
        val uid = runCatching { RuntimeFileUtils.read_uid(pid) }.getOrDefault(-1)
        val verdict = CensusIsolationPolicy.verdict(
            observedUid = uid,
            frameworkSaysIsolated = uid >= 0 && UserHandle_isIsolated(uid),
        )
        if (verdict == CensusVerdict.EXCLUDE_UID_UNREADABLE) {
            UnreadableUidWarn.warn(
                "census: unreadable uid for pid=$pid, excluding it from VFS stats",
            )
        }
        return verdict != CensusVerdict.INCLUDE
    }

    /** 陈旧 pid 的告警限频：理由同上，且它们是正常进程更替的产物，不该看起来像故障。 */
    private object StaleInstanceWarn {
        private const val INTERVAL_MS = 60_000L

        @Volatile
        private var lastAt = 0L

        fun warn(message: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAt < INTERVAL_MS) return
            lastAt = now
            android.util.Log.i("VfsLayerController", message)
        }
    }

    /** uid 不可读的告警限频：普查每 30s 一轮，逐 pid 刷屏会淹没其他信号。 */
    private object UnreadableUidWarn {
        private const val INTERVAL_MS = 60_000L

        @Volatile
        private var lastAt = 0L

        fun warn(message: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAt < INTERVAL_MS) return
            lastAt = now
            android.util.Log.w("VfsLayerController", message)
        }
    }

    /**
     * 陈旧实例的统一出口：判定 + 限频留痕。
     *
     * 单独抽出来是为了两处调用点口径完全一致，且日志不会逐 pid 刷屏
     * （普查 30s 一轮 × 每轮每个陈旧 pid 一行 = 噪声）。限频但**不静默**：
     * 第 38 批的教训正是「跳过」若不留痕，下次排查会重新怀疑判据。
     */
    private fun skipStaleInstance(
        pid: Int,
        packageName: String,
        snapshot: Map<Int, Long>,
    ): Boolean {
        if (!PidInstanceFreshness.isStale(pid, snapshot)) return false
        StaleInstanceWarn.warn("census: skip stale pid=$pid pkg=$packageName " +
                "(process gone or pid reused, excluded from VFS stats)")
        return true
    }

    private fun buildPidFlag(
        pid: Int,
        packageName: String,
        userId: Int,
        startUpAwarePids: Set<Int>,
        mountFailedPids: Set<Int>,
        mkdir: Boolean,
    ): Int {
        val targets = VfsRuntimePolicy.getMountTargets(packageName, userId)
        var pidFlag = if (targets.isEmpty()) {
            // 该 (package,user) 无挂载目标：已观测但不在管理范围，不进 managed 分母。
            PackageStatus.PID_FLAG_UNMANAGED
        } else {
            PidMountClassifier.classify(
                targets.size,
                RuntimeFileUtils.check_mounts(pid, targets.toTypedArray()),
            )
        }
        if (startUpAwarePids.contains(pid)) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_STARTUP_AWARE
        }
        if (mountFailedPids.contains(pid)) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_MOUNT_FAILED
        }
        if (!mkdir) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_MKDIR_FAILED
        }
        return normalizePidFlag(pidFlag, pid)
    }

    /**
     * 终态维度互斥校验（P1-A）：非法组合运行时退化为 UNKNOWN + 诊断日志
     * （测试中强断言）。判定规则归 [PidMountClassifier]。
     */
    private fun normalizePidFlag(pidFlag: Int, pid: Int): Int {
        if (!PidMountClassifier.isLegalCombination(pidFlag)) {
            android.util.Log.w(
                "VfsLayerController",
                "Illegal pidFlag combination $pidFlag for pid=$pid, degrading to UNKNOWN",
            )
            val stateBits = pidFlag and (
                PackageStatus.PID_FLAG_MOUNTED or
                    PackageStatus.PID_FLAG_PARTIALLY_MOUNTED or
                    PackageStatus.PID_FLAG_NOT_MOUNTED or
                    PackageStatus.PID_FLAG_UNMANAGED or
                    PackageStatus.PID_FLAG_UNKNOWN or
                    PackageStatus.PID_FLAG_DELETED or
                    PackageStatus.PID_FLAG_OVERRIDE
                )
            return (pidFlag and stateBits.inv()) or PackageStatus.PID_FLAG_UNKNOWN
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

/**
 * PID 实例新鲜度判定（5.5.7 P2，纯函数）。
 *
 * 为什么单独成 object：`VfsLayerController` 的 companion 是 `private`，
 * 放在里面的函数测试不可见；而这段逻辑是全模块最容易出错、最需要边界单测的部分
 * （直接决定普查分母是否可信），所以独立出来。
 *
 * 与 [me.gm.cleaner.runtime.server.process.ProcessIdentity.isAlive] 的关系：
 * `isAlive` 只能识别「进程完全消失」，挡不住 PID 复用 —— 后者恰恰是第 38 批
 * 假告警的真凶（pid 被复用后读到新进程的 mountinfo ⇒ 空数组 ⇒ NOT_MOUNTED）。
 *
 * 与 `Mounter.bindMountLocked()` 的关系：挂载侧只需避开「已消失」（打错地方才是灾难）；
 * 普查侧还必须避开「已换成别人」（会被读成假告警）。所以这里更严。
 */
internal object PidInstanceFreshness {

    /**
     * 该 pid 是否已不是快照时刻的那个进程实例。
     *
     * 判为陈旧（返回 true）的三种情形，全部保守处理为「不采样」：
     * 1. 进程已消失（`/proc/<pid>` 不存在）；
     * 2. `/proc/<pid>/stat` 读不到（进程在竞态中退出，无法证明身份未变）；
     * 3. starttime 与快照不一致 ⇒ pid 已被内核复用。
     *
     * 注意「保守」的方向：这里宁可漏采一个活进程，也不把别人的挂载状态算到目标头上。
     * 漏采的代价是分母少一个（可自愈：下一轮普查重新采集）；错采的代价是假告警
     * 误导排查方向（第 38 批为此花了整整一轮）。
     *
     * @param snapshot 遍历开始前采集的 pid → starttime 快照
     * @param alive 注入式存活判定，默认真实 `/proc` 探测
     * @param startTimeOf 注入式 starttime 读取，默认真实 `/proc/<pid>/stat` 解析
     */
    fun isStale(
        pid: Int,
        snapshot: Map<Int, Long>,
        alive: (Int) -> Boolean = { ProcessIdentity.isAlive(it) },
        startTimeOf: (Int) -> Long? = { ProcessIdentity.currentInstance(it)?.startTime },
    ): Boolean {
        if (!alive(pid)) return true
        val current = startTimeOf(pid) ?: return true
        val known = snapshot[pid] ?: return false
        return current != known
    }
}
