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
}
