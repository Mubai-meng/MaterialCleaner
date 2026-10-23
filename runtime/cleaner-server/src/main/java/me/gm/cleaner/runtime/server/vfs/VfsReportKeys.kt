package me.gm.cleaner.runtime.server.vfs

/**
 * VFS 层指标键契约（产出方 = VfsLayerController.collectReport）。
 *
 * `preparedPackages` 原名 `mountedPackages`，语义却是 `Mounter.mkdirRecords.keySet()`
 * —— 只在 `auto_prepare_dirs()` **成功**时写入，含义是「目标目录已就绪的包数」，
 * **与挂载是否成功无关**。这个名字两次把排查带偏：
 * 第 38 批 `mountedPackages=1` 配 `vfsMountedPids=0` 曾被当成矛盾（其实完全自洽）；
 * 第 40 批重启后恒为 0 又被读成「挂载全灭」。挂载成功只看 `vfsMountedPids` 与明细 `srStatus`。
 */
object VfsReportKeys {
    /** 目标目录（mkdir）已就绪的包数。**不是**挂载成功数。 */
    const val PREPARED_PACKAGES = "preparedPackages"

    /** 已配置重定向规则的包数（分母口径）。 */
    const val CONFIGURED_PACKAGES = "configuredPackages"

    /** `startUpAwarePids` 的实时条数（`collectReport` 当场读 Mounter，**不经**普查节流）。 */
    const val RECORDED_PIDS = "recordedPids"

    /**
     * [RECORDED_PIDS] 的**采样时刻**（epoch ms），与 `vfsCensusAt` 同一时钟域。
     *
     * 存在的唯一理由：报告里并存两类指标 —— 实时值（`recordedPids` /
     * `preparedPackages` / `mountTotalAttempts`，`collectReport` 当场读 Mounter）
     * 与 30 秒节流缓存值（`vfsObservedPids` / `vfsMountedPids` / `srStatus`，
     * 由 `CENSUS_THROTTLE_MS` 复用上一轮普查）。
     *
     * 第 42 批（`recordedPids=0` 配 `vfsMountedPids=4`）与第 43 批（同样组合）
     * **连续两次**被读成「模块自身矛盾」，实际两次都是：普查采样时 4 个 pid 还活着，
     * 之后用户从最近任务清理掉，`collectReport` 实时读才变成 0。
     * 没有这个时间戳，读日志的人无法自证「两组数字不是同一时刻」。
     */
    const val RECORDED_PIDS_AT = "recordedPidsAt"
}
