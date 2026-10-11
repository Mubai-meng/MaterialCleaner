package me.gm.cleaner.runtime.server.vfs

import me.gm.cleaner.model.PackageStatus

/**
 * PID 挂载终态分类（P1-A，纯函数）。
 *
 * 职责：只根据目标数与采样结果判定维度 A 终态，不触碰进程、文件系统与
 * 失败历史（维度 B：MOUNT_FAILED/STARTUP_AWARE/MKDIR_FAILED 由调用方正交叠加）。
 *
 * 不变量：
 * - targets 为空 → UNMANAGED（已观测但无管理目标，不进分母）；
 * - 采样 null → UNKNOWN（证据不足，不得推断未挂载）；
 * - 含负值 → DELETED/OVERRIDE（历史语义，允许共存）；
 * - 全命中 → MOUNTED；零命中 → NOT_MOUNTED；其余 → PARTIALLY_MOUNTED。
 */
object PidMountClassifier {
    /**
     * @param targetCount 该 (package,user) 的预期挂载目标数
     * @param mountedIndices check_mounts 采样结果，null 表示采样失败
     * @return 终态位（单个，DELETED/OVERRIDE 允许共存）
     */
    fun classify(targetCount: Int, mountedIndices: IntArray?): Int {
        if (targetCount <= 0) return PackageStatus.PID_FLAG_UNMANAGED
        if (mountedIndices == null) return PackageStatus.PID_FLAG_UNKNOWN
        var flag = 0
        if (mountedIndices.any { it < 0 }) {
            if (mountedIndices.contains(-1)) flag = flag or PackageStatus.PID_FLAG_DELETED
            if (mountedIndices.contains(-2)) flag = flag or PackageStatus.PID_FLAG_OVERRIDE
            return flag
        }
        return when {
            mountedIndices.size == targetCount -> PackageStatus.PID_FLAG_MOUNTED
            mountedIndices.isEmpty() -> PackageStatus.PID_FLAG_NOT_MOUNTED
            else -> PackageStatus.PID_FLAG_PARTIALLY_MOUNTED
        }
    }

    /**
     * 终态维度互斥校验：MOUNTED/PARTIALLY/NOT_MOUNTED/UNMANAGED/UNKNOWN
     * 彼此互斥；DELETED/OVERRIDE 允许共存；UNMANAGED 不得与任何终态共存。
     */
    fun isLegalCombination(pidFlag: Int): Boolean {
        val stateBits = pidFlag and (
            PackageStatus.PID_FLAG_MOUNTED or
                PackageStatus.PID_FLAG_PARTIALLY_MOUNTED or
                PackageStatus.PID_FLAG_NOT_MOUNTED or
                PackageStatus.PID_FLAG_UNMANAGED or
                PackageStatus.PID_FLAG_UNKNOWN or
                PackageStatus.PID_FLAG_DELETED or
                PackageStatus.PID_FLAG_OVERRIDE
            )
        val withoutDeletedOverride = stateBits and (
            PackageStatus.PID_FLAG_DELETED or PackageStatus.PID_FLAG_OVERRIDE
            ).inv()
        val singleOrDeletedOverride = withoutDeletedOverride == 0 ||
            withoutDeletedOverride.let { it and (it - 1) == 0 }
        if (!singleOrDeletedOverride) return false
        return !(pidFlag and PackageStatus.PID_FLAG_UNMANAGED != 0 &&
            stateBits != PackageStatus.PID_FLAG_UNMANAGED)
    }
}
