package me.gm.cleaner.client.ui

/**
 * 四版本号对账结果。
 *
 * 更新后未重启时，已安装版大于任一运行中组件版（App 进程、Root 服务、
 * Zygote 内 Hook 只有重启手机能统一）。0 表示读不到，按未知处理，
 * 未知永不判 stale，避免误报打扰用户。
 */
enum class VersionStaleness {
    OK,
    STALE_APP,
    STALE_SERVER,
    STALE_HOOK,
}

/**
 * 纯函数：版本号对账。已安装版由 App 经 PackageManager 读取后传入
 * （server 编译桩无版本字段，不经 Binder 传）。
 */
fun evaluateVersionStaleness(
    appRunning: Long,
    installed: Long,
    serverRunning: Long,
    hookRunning: Long,
    hookAvailable: Boolean,
): VersionStaleness {
    if (installed <= 0L) return VersionStaleness.OK
    if (appRunning > 0L && appRunning != installed) return VersionStaleness.STALE_APP
    if (serverRunning > 0L && serverRunning != installed) return VersionStaleness.STALE_SERVER
    if (!hookAvailable) return VersionStaleness.OK
    if (hookRunning > 0L && hookRunning != installed) return VersionStaleness.STALE_HOOK
    return VersionStaleness.OK
}
