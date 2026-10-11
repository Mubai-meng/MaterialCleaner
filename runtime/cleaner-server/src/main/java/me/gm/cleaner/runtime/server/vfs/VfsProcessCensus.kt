package me.gm.cleaner.runtime.server.vfs

import me.gm.cleaner.model.PackageStatus

/**
 * VFS 进程普查快照（P1-C，诊断投影，非业务事实源）。
 *
 * 口径（PID 去重，共享进程不重复计）：
 * - observed = 本轮实际扫描的非隔离 PID 数；
 * - attributed = 归属到重定向包的 PID（进入各包 PackageStatus 数组）；
 * - unattributed = observed - attributed；
 * - targetsEmpty = 已归属但该 (package,user) 无挂载目标（UNMANAGED 位）；
 * - unmanaged = unattributed + targetsEmpty；
 * - managed = mounted + partial + notMounted + unknown + drift；
 * - drift = 携带 DELETED/OVERRIDE 位（目标漂移，罕见，单列）；
 * - mountFailedEvidence = 携带 MOUNT_FAILED 位的 PID 数（正交历史证据，不进分母）。
 *
 * 恒成立：observed = managed + unmanaged。
 */
data class VfsProcessCensus(
    val sampledAt: Long,
    val observedPids: Int,
    val managedPids: Int,
    val unmanagedPids: Int,
    val mountedPids: Int,
    val partialPids: Int,
    val notMountedPids: Int,
    val unknownPids: Int,
    val driftPids: Int,
    val mountFailedEvidence: Int,
    /** 有界明细，先排序后截断，异常优先；格式 `package:pid/uid/flagsHex`，`;` 分隔。 */
    val detail: String,
    val detailShown: Int,
    val detailTotal: Int,
    val truncated: Boolean,
) {
    // ── 指标键契约（DiagnosticArchive 等消费方必须引用此处，不得另写字面量） ──
    companion object {
        const val KEY_OBSERVED = "vfsObservedPids"
        const val KEY_MANAGED = "vfsManagedPids"
        const val KEY_UNMANAGED = "vfsUnmanagedPids"
        const val KEY_SR_TOTAL = "srStatusTotal"
        const val KEY_SR_TRUNCATED = "srStatusTruncated"
    }

    fun toMetrics(): Map<String, String> = mapOf(
        KEY_OBSERVED to observedPids.toString(),
        KEY_MANAGED to managedPids.toString(),
        KEY_UNMANAGED to unmanagedPids.toString(),
        "vfsMountedPids" to mountedPids.toString(),
        "vfsPartialPids" to partialPids.toString(),
        "vfsNotMountedPids" to notMountedPids.toString(),
        "vfsUnknownPids" to unknownPids.toString(),
        "vfsDriftPids" to driftPids.toString(),
        "vfsMountFailedEvidence" to mountFailedEvidence.toString(),
        "vfsCensusAt" to sampledAt.toString(),
        "srStatus" to detail,
        "srStatusShown" to detailShown.toString(),
        KEY_SR_TOTAL to detailTotal.toString(),
        KEY_SR_TRUNCATED to truncated.toString(),
    )
}

data class CensusEntry(
    val packageName: String,
    val pid: Int,
    val uid: Int,
    val pidFlag: Int,
)

object VfsProcessCensusBuilder {
    /** 异常优先 rank，越小越优先保留。 */    private fun abnormalRank(flag: Int): Int = when {
        flag and PackageStatus.PID_FLAG_UNKNOWN != 0 -> 0
        flag and PackageStatus.PID_FLAG_PARTIALLY_MOUNTED != 0 -> 1
        flag and PackageStatus.PID_FLAG_MOUNT_FAILED != 0 -> 2
        flag and (PackageStatus.PID_FLAG_DELETED or PackageStatus.PID_FLAG_OVERRIDE) != 0 -> 3
        flag and PackageStatus.PID_FLAG_NOT_MOUNTED != 0 -> 4
        flag and PackageStatus.PID_FLAG_UNMANAGED != 0 -> 6
        else -> 5 // MOUNTED：健康，最先被截断
    }

    fun build(
        sampledAt: Long,
        entries: List<CensusEntry>,
        unattributedPids: Int,
        maxDetailEntries: Int = 64,
    ): VfsProcessCensus {
        var mounted = 0
        var partial = 0
        var notMounted = 0
        var unknown = 0
        var drift = 0
        var targetsEmpty = 0
        var mountFailed = 0
        for (e in entries) {
            val f = e.pidFlag
            if (f and PackageStatus.PID_FLAG_UNMANAGED != 0) {
                targetsEmpty++
            } else {
                when {
                    f and PackageStatus.PID_FLAG_MOUNTED != 0 -> mounted++
                    f and PackageStatus.PID_FLAG_PARTIALLY_MOUNTED != 0 -> partial++
                    f and PackageStatus.PID_FLAG_NOT_MOUNTED != 0 -> notMounted++
                    f and (PackageStatus.PID_FLAG_DELETED or PackageStatus.PID_FLAG_OVERRIDE) != 0 -> drift++
                    else -> unknown++
                }
            }
            if (f and PackageStatus.PID_FLAG_MOUNT_FAILED != 0) mountFailed++
        }
        val managed = mounted + partial + notMounted + unknown + drift
        val unmanaged = unattributedPids + targetsEmpty
        val sorted = entries.sortedWith(
            compareBy({ abnormalRank(it.pidFlag) }, { it.packageName }, { it.pid }),
        )
        val shown = sorted.take(maxDetailEntries)
        return VfsProcessCensus(
            sampledAt = sampledAt,
            observedPids = managed + unmanaged,
            managedPids = managed,
            unmanagedPids = unmanaged,
            mountedPids = mounted,
            partialPids = partial,
            notMountedPids = notMounted,
            unknownPids = unknown,
            driftPids = drift,
            mountFailedEvidence = mountFailed,
            detail = shown.joinToString(";") {
                "${it.packageName}:${it.pid}/${it.uid}/${Integer.toHexString(it.pidFlag)}"
            },
            detailShown = shown.size,
            detailTotal = entries.size,
            truncated = sorted.size > shown.size,
        )
    }
}
