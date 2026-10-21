package me.gm.cleaner.runtime.server

/**
 * 本项目关注的 logcat tag 集合。
 *
 * 职责：本文件是"本项目需要哪些 logcat tag"的**唯一真相源**。
 * 持续采集（[AutoLogging]）与诊断导出（[DiagnosticArchive]）都从这里取过滤串，
 * 避免两处各自维护清单而随代码演进漂移。
 *
 * logcat 过滤语义（实测确认，勿改）：
 * - filterSpec 只写 `<tag>:*` **不会**抑制其它 tag，必须以 `*:S` 结尾才真正过滤；
 * - 缺少 `*:S` 时会退化为全量输出，采集量将回到未过滤水平；
 * - 匹配是**精确字符串比较**，没有任何通配语义 —— `MC:*` 不会命中 `MC/Policy`，
 *   `me.gm.cleaner:*` 也不会命中 `me.gm.cleaner.runtime.server`。
 *
 * 维护约定（第30批起强制）：新增本项目日志 tag 时必须同步登记到此清单，
 * 否则持续采集与诊断聚焦日志都会**静默丢掉**该 tag。
 * 清单由 [LogTagFilterTest] 与源码实际 tag 集合双向校验：清单里多出来的 tag
 * 无害（只是多放行几条），漏掉的 tag 才是缺陷。改任何 `Log.x("...")` 字面量
 * 或 `TAG = "..."` 常量时，务必回来看一眼这里。
 */
object LogTagFilter {
    /**
     * 本项目全部日志 tag。
     *
     * 覆盖 server 侧（[me.gm.cleaner.runtime.server]、cleaner_server）、app 侧
     * （me.gm.cleaner、MC_ 与 MC/ 前缀）、Hook 侧（跑在 MediaProvider 进程内的
     * MC_REDIRECT 等）、native 侧（me.gm.cleaner、xhook）以及启动器（starter）。
     */
    private val TAGS = listOf(
        // ── 生命周期与状态机 ──────────────────────────────────────────
        "MC_BOOT", "MC_EVENT", "MC_STATE", "MC_DIAG",
        "MC/Test", "MC/StateMachine", "MC/BootState", "MC/XposedState",
        "MC/MoreOptions", "MC/Launcher", "MC/AppList", "MC/Policy", "MC_FsProbe",
        "MC_PkgCache",
        // ── 重定向与挂载 ─────────────────────────────────────────────
        "MC_REDIRECT", "StorageRedirect", "VfsLayerController", "MountWizard",
        "EditMountTemplate", "StoragePolicyChangeCoordinator", "StoragePolicyBatchEdit",
        // ── server 侧组件 ────────────────────────────────────────────
        "DataBus", "DataBusEventQuarantine", "DataBusEventWriter", "DataBusLease",
        "DataBusPrune", "DataBusRecoveryLedger",
        "DiagnosticArchive", "CleanerService", "CleanerServerCallback", "BinderSender",
        "LayerOrchestrator", "SnapshotPublisher", "HookRecoveryCoordinator",
        "MediaProviderRecoveryStrategy", "NativeHookStatus", "HookPolicyCache",
        "HookPolicyRefreshScheduler", "FuseNativePolicyAdapter", "HookDataBusBridge",
        "EventConsumerScheduler", "EventSignalCoalescer", "EventDeadLetter",
        "FileSystemEventConsumer", "FileSystemObserver",
        "RedirectNoticeConsumer", "QuerySessionLeaseConsumer", "QuerySessionCache",
        "ActivityManagerLogsObserver", "AMLogs", "PackageInfoMapper", "PackageReceiver",
        "BaseIntentObserver", "IntentReceiver",
        "PlatformCapabilitiesDetector", "SecurityHelper", "ServicePreferences",
        "PermissionUtils", "RootWorkerService",
        "ClientErrorJournal", "AppLabelCache",
        // ── Hook 侧（跑在 MediaProvider / 目标进程内）─────────────────
        "MediaProvider", "HookBridgeRegistrar", "HooksBridge", "HooksBinderRetriever",
        "BulkCursor", "ParceledListSlice", "IContentProviderKt",
        // ── app 侧 UI / 通知 ────────────────────────────────────────
        "NotificationService", "ServiceMoreOptions",
        "OverridableDefaultItemAnimator", "SimpleListDiffer", "ThemeColorPreference",
        // ── server / app 进程名当 tag 用的场合 ───────────────────────
        "me.gm.cleaner", "me.gm.cleaner.runtime.server", "cleaner_server",
        // ── native 与启动器 ─────────────────────────────────────────
        "xhook", "starter",
        // ── 仅 debug 构建产出 ───────────────────────────────────────
        "CleanerTest",
    )

    /**
     * 供 logcat 使用的过滤串，**必须**以 `*:S` 结尾。
     */
    val FILTER_SPEC: String = TAGS.joinToString(separator = " ") { "$it:*" } + " *:S"

    /**
     * 过滤串的数组形式，供 Java 侧拼装 logcat 命令使用。
     * 避免调用方各自拆分字符串导致口径分叉。
     */
    @JvmStatic
    fun filterSpecArgs(): Array<String> = FILTER_SPEC.split(" ").toTypedArray()

    /**
     * 主采集通道的完整命令参数：main/system buffer + 本项目 tag 过滤。
     *
     * 采集命令集中在此构造，使"过滤串必须以 `*:S` 结尾"这一不变式只有一处
     * 实现，并由单元测试守住。
     */
    @JvmStatic
    fun mainChannelArgs(): Array<String> =
        arrayOf("-b", "main,system") + filterSpecArgs()

    /**
     * 崩溃采集通道的命令参数：crash buffer 全量，不施加 tag 过滤。
     *
     * 崩溃的 logcat tag 是崩溃进程名或 AndroidRuntime，无法用固定白名单
     * 穷举，施加过滤会把崩溃证据直接丢弃。
     */
    @JvmStatic
    fun crashChannelArgs(): Array<String> = arrayOf("-b", "crash")
}
