package me.gm.cleaner.core.storage.redirect.databus

/**
 * DataBus 协议常量分区。
 *
 * 承载跨进程共享的持久化事实命名协议（快照 / 信号 / 事件队列 / Lease 分类），
 * 以及与协议强绑定的数据载体与健康口径。
 *
 * 运行期实现（目录准备、原子写、游标、计数器、校验实现）仍保留在 [DataBus]。
 * 现有调用方已迁移至本对象；新代码请直接引用本对象，不再经 [DataBus] 转发。
 */
object DataBusProtocol {
    // ── 快照文件名 ──
    const val SNAPSHOT_REDIRECT_POLICY = "redirect_policy.json"
    const val SNAPSHOT_READ_ONLY = "read_only.json"
    const val SNAPSHOT_CONFIGURED_MOUNT_POINTS = "configured_mount_points.json"
    const val SNAPSHOT_PLATFORM_CAPABILITIES = "platform_capabilities.json"
    const val SNAPSHOT_ORCHESTRATED_STATUS = "orchestrated_status.json"
    const val SNAPSHOT_NATIVE_HOOK_STATUS = "native_hook_status.json"

    // ── 信号文件名 ──
    const val SIGNAL_REDIRECT_POLICY_CHANGED = "redirect_policy_changed"
    const val SIGNAL_READ_ONLY_CHANGED = "read_only_changed"
    const val SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED = "configured_mount_points_changed"
    const val SIGNAL_PLATFORM_CAPABILITIES_CHANGED = "platform_capabilities_changed"
    const val SIGNAL_NATIVE_HOOK_STATUS_CHANGED = "native_hook_status_changed"
    const val SIGNAL_FILESYSTEM_EVENTS_CHANGED = "filesystem_events_changed"
    const val SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED = "redirect_notice_events_changed"
    const val SIGNAL_QUERY_SESSION_LEASES_CHANGED = "query_session_leases_changed"

    // ── 事件子目录 ──
    const val EVENT_FILESYSTEM = "filesystem"
    const val EVENT_REDIRECT_NOTICE = "redirect_notice"

    // ── Lease 子目录 ──
    const val LEASE_QUERY_SESSIONS = "query_sessions"

    // ── 协议白名单（仅模块内可见，不扩大为 public）──
    internal val validSnapshotNames = setOf(
        SNAPSHOT_REDIRECT_POLICY,
        SNAPSHOT_READ_ONLY,
        SNAPSHOT_CONFIGURED_MOUNT_POINTS,
        SNAPSHOT_PLATFORM_CAPABILITIES,
        SNAPSHOT_ORCHESTRATED_STATUS,
        SNAPSHOT_NATIVE_HOOK_STATUS,
    )
    internal val validSignalNames = setOf(
        SIGNAL_REDIRECT_POLICY_CHANGED,
        SIGNAL_READ_ONLY_CHANGED,
        SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED,
        SIGNAL_PLATFORM_CAPABILITIES_CHANGED,
        SIGNAL_NATIVE_HOOK_STATUS_CHANGED,
        SIGNAL_FILESYSTEM_EVENTS_CHANGED,
        SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED,
        SIGNAL_QUERY_SESSION_LEASES_CHANGED,
    )
    internal val validEventQueues = setOf(
        EVENT_FILESYSTEM,
        EVENT_REDIRECT_NOTICE,
    )
    internal val validLeaseCategories = setOf(
        LEASE_QUERY_SESSIONS,
    )

    // ── 事件文件名正则（模块内可见，供 DataBus 解析序号）──
    internal val EVENT_FILE_NAME_PATTERN = Regex("^(\\d{20})-\\d+-\\d+-[0-9a-fA-F]{4}\\.json$")

    data class SnapshotHealth(
        val name: String,
        val exists: Boolean,
        val validJson: Boolean,
        val error: String? = null,
    )

    /**
     * 单个队列目录的**双计数**，由 **一次目录遍历**同时算出。
     *
     * 之所以把两者绑在一起：它们都只需要**文件名**（积压判据就是 `name > cursor`），
     * 分开算会让调用方对同一个目录扫两遍 —— `DataBusLayerReporter` 每轮（~2s）都要
     * 出这两个数，实测覆盖 `events/filesystem`(~200) + `events/consumed`(~460) 条。
     */
    data class QueueCounts(
        /** 目录内 `*.json` **累计写入量**，只增不减（游标越过不删除）。 */
        val archived: Int,
        /** 真实待消费深度：`文件名 > 游标` 的条数。无游标的归档目录恒为 0。 */
        val pending: Int,
    )

    data class HealthReport(
        val initialized: Boolean,
        val missingDirectories: List<String>,
        val permissionIssues: List<String>,
        val snapshots: List<SnapshotHealth>,
        /**
         * 队列目录的**累计写入量**（`countJsonFiles` 语义），**不是积压量**。
         *
         * ⚠️ `events/<queue>/` 里的文件只被游标越过、从不主动删除，因此这个数只增不减。
         * 把累计读成积压会得到永远为真的告警：实测 `filesystem=561` 时游标已指向目录里
         * 最后一个事件、队列其实是空的，却每 60s 报一次假 backlog。
         * 真实积压见 [pendingEventQueueCounts]；键集合与本体一致。
         */
        val eventQueueCounts: Map<String, Int>,
        /**
         * 队列**真实积压**（`文件名 > 游标`），与 [eventQueueCounts] 出自**同一次**目录遍历。
         *
         * 有默认值以兼容既有构造点；`DataBus.checkHealth` 一定会填充。
         */
        val pendingEventQueueCounts: Map<String, Int> = emptyMap(),
        val leaseCounts: Map<String, Int>,
    ) {
        fun hasSnapshot(name: String): Boolean =
            snapshots.any { it.name == name && it.exists && it.validJson }

        val criticalSnapshotsReady: Boolean
            get() = hasSnapshot(SNAPSHOT_REDIRECT_POLICY) &&
                    hasSnapshot(SNAPSHOT_READ_ONLY) &&
                    hasSnapshot(SNAPSHOT_CONFIGURED_MOUNT_POINTS)

        val healthy: Boolean
            get() = initialized &&
                    missingDirectories.isEmpty() &&
                    permissionIssues.isEmpty() &&
                    criticalSnapshotsReady
    }

    data class EventFile(
        val name: String,
        val content: String,
    )

    // ── 快照清单（模块内可见，供健康检查遍历）──
    internal fun snapshotNames(): List<String> = listOf(
        SNAPSHOT_REDIRECT_POLICY,
        SNAPSHOT_READ_ONLY,
        SNAPSHOT_CONFIGURED_MOUNT_POINTS,
        SNAPSHOT_PLATFORM_CAPABILITIES,
        SNAPSHOT_ORCHESTRATED_STATUS,
        SNAPSHOT_NATIVE_HOOK_STATUS,
    )
}
