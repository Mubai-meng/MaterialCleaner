package me.gm.cleaner.runtime.server.orchestrator

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.server.CleanerServerCallback
import org.json.JSONObject

object DataBusLayerReporter {
    private const val BACKLOG_WARN_INTERVAL_MS = 60_000L
    private const val FILESYSTEM_QUEUE_WARN_COUNT = 100
    private const val REDIRECT_NOTICE_QUEUE_WARN_COUNT = 100
    private const val CONSUMED_QUEUE_WARN_COUNT = 5_000
    private const val QUERY_SESSION_LEASE_WARN_COUNT = 100

    @Volatile
    private var lastBacklogWarningAt = 0L

    fun collect(generation: Long, now: Long): LayerReport {
        val health = DataBus.checkHealth(repair = true)
        warnIfBacklog(health, now)
        val platformCapsJson = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
        val platformCaps = platformCapsJson?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        val metrics = linkedMapOf(
            "busRootExists" to health.initialized.toString(),
            "missingDirectoryCount" to health.missingDirectories.size.toString(),
            "permissionIssueCount" to health.permissionIssues.size.toString(),
            // 物理存量（已消费未清理也计入），用途是存储残留，不是积压。
            "eventQueueFilesystem" to (health.eventQueueCounts[DataBusProtocol.EVENT_FILESYSTEM] ?: 0).toString(),
            "eventQueueRedirectNotice" to (health.eventQueueCounts[DataBusProtocol.EVENT_REDIRECT_NOTICE] ?: 0).toString(),
            "eventQueueConsumed" to (health.eventQueueCounts["consumed"] ?: 0).toString(),
            // 待处理量：唯一驱动积压告警的口径。
            "pendingQueueFilesystem" to (health.pendingEventCounts[DataBusProtocol.EVENT_FILESYSTEM] ?: 0).toString(),
            "pendingQueueRedirectNotice" to (health.pendingEventCounts[DataBusProtocol.EVENT_REDIRECT_NOTICE] ?: 0).toString(),
            // 隔离证据量与游标可信状态（UNREADABLE 队列的 pending 不可信）。
            "quarantineFilesystem" to (health.quarantineCounts[DataBusProtocol.EVENT_FILESYSTEM] ?: 0).toString(),
            "quarantineRedirectNotice" to (health.quarantineCounts[DataBusProtocol.EVENT_REDIRECT_NOTICE] ?: 0).toString(),
            "cursorReadFilesystem" to (health.cursorReadStates[DataBusProtocol.EVENT_FILESYSTEM]?.name ?: "UNKNOWN"),
            "cursorReadRedirectNotice" to (health.cursorReadStates[DataBusProtocol.EVENT_REDIRECT_NOTICE]?.name ?: "UNKNOWN"),
            "leaseQuerySessions" to (health.leaseCounts[DataBusProtocol.LEASE_QUERY_SESSIONS] ?: 0).toString(),
            // 入站 AIDL 体积（hook 进程 → server，见 CleanerServerCallback.sInboundCallbackCount）。
            // 存在的唯一目的是把 `E Parcel`（每批条目最多的 E tag）从"找调用点"改成
            // **可重复的定量对照**：下一批直接算 E Parcel ÷ 本值。详见该字段的 KDoc。
            // 该改动与上游本次的 databus 重构正交，合并时保留。
            "inboundHookCallbacks" to CleanerServerCallback.inboundCallbackCount().toString(),
        )

        for (snapshot in health.snapshots) {
            metrics[snapshotMetricName(snapshot.name)] = when {
                snapshot.exists && snapshot.validJson -> "exists"
                snapshot.exists -> "corrupted"
                else -> "missing"
            }
        }

        if (platformCaps != null) {
            metrics["platformMediaProviderPackage"] =
                platformCaps.optString("mediaProviderPackageName", "")
            metrics["platformFuseJniLoadMode"] =
                platformCaps.optString("fuseJniLoadMode", "UNKNOWN")
            metrics["platformSupportedNativeHookMode"] =
                platformCaps.optString("supportedNativeHookMode", "NONE")
            metrics["platformMediaProviderApiShape"] =
                platformCaps.optString("mediaProviderApiShape", "UNKNOWN")
            metrics["platformSystemFuseJniAvailable"] =
                platformCaps.optBoolean("systemFuseJniAvailable", false).toString()
        }

        val state = when {
            // 游标不可信是独立故障：即使总线基础设施健康，也不得报完全健康。
            // ABSENT（新队列）不是故障，只有 UNREADABLE 降级。
            health.cursorReadStates.any { it.value == DataBusProtocol.CursorRead.UNREADABLE } ->
                LayerState.DEGRADED
            health.healthy -> LayerState.HEALTHY
            health.initialized -> LayerState.DEGRADED
            else -> LayerState.UNAVAILABLE
        }
        return LayerReport(
            id = LayerId.DATA_BUS,
            state = state,
            generation = generation,
            lastHeartbeatAt = if (health.initialized) now else 0L,
            lastErrorAt = if (state == LayerState.HEALTHY) 0L else now,
            lastError = buildError(health),
            metrics = metrics,
        )
    }

    private fun snapshotMetricName(name: String): String = when (name) {
        DataBusProtocol.SNAPSHOT_REDIRECT_POLICY -> "snapshotRedirectPolicy"
        DataBusProtocol.SNAPSHOT_READ_ONLY -> "snapshotReadOnly"
        DataBusProtocol.SNAPSHOT_CONFIGURED_MOUNT_POINTS -> "snapshotConfiguredMountPoints"
        DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES -> "snapshotPlatformCapabilities"
        DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS -> "snapshotNativeHookStatus"
        DataBusProtocol.SNAPSHOT_ORCHESTRATED_STATUS -> "snapshotOrchestratedStatus"
        else -> "snapshot${name.replaceFirstChar { it.uppercaseChar() }}"
    }

    private fun buildError(health: DataBusProtocol.HealthReport): String? {
        val unreadable = health.cursorReadStates
            .filterValues { it == DataBusProtocol.CursorRead.UNREADABLE }
            .keys
        // 游标故障独立成错：即使总线其他部分健康，也必须显式暴露，
        // 否则 UNREADABLE 队列的 pending 不可信却无任何错误说明。
        if (unreadable.isNotEmpty()) {
            return "cursor unreadable: ${unreadable.joinToString(",")} " +
                    "(pending counts for these queues are not credible)"
        }
        if (health.healthy) return null
        val parts = mutableListOf<String>()
        if (!health.initialized) parts += "bus unavailable"
        if (health.missingDirectories.isNotEmpty()) {
            parts += "missing directories=${health.missingDirectories.size}"
        }
        if (health.permissionIssues.isNotEmpty()) {
            parts += "permission issues=${health.permissionIssues.size}"
        }
        val missingCritical = listOf(
            DataBusProtocol.SNAPSHOT_REDIRECT_POLICY to "redirect_policy",
            DataBusProtocol.SNAPSHOT_READ_ONLY to "read_only",
            DataBusProtocol.SNAPSHOT_CONFIGURED_MOUNT_POINTS to "configured_mount_points",
        ).filterNot { (name, _) -> health.hasSnapshot(name) }
            .joinToString(",") { (_, label) -> label }
        if (missingCritical.isNotBlank()) {
            parts += "missing critical snapshots=$missingCritical"
        }
        return parts.joinToString("; ").ifBlank { "DataBus degraded" }
    }

    private fun warnIfBacklog(health: DataBusProtocol.HealthReport, now: Long) {
        // 积压告警只看可信队列的 pending：UNREADABLE 队列的数字是估算值，
        // 不得作为积压依据（由独立的游标故障告警覆盖）。
        fun crediblePending(queue: String): Int {
            if (health.cursorReadStates[queue] == DataBusProtocol.CursorRead.UNREADABLE) return 0
            return health.pendingEventCounts[queue] ?: 0
        }
        val filesystem = crediblePending(DataBusProtocol.EVENT_FILESYSTEM)
        val redirectNotice = crediblePending(DataBusProtocol.EVENT_REDIRECT_NOTICE)
        val consumed = health.eventQueueCounts["consumed"] ?: 0
        val querySessionLease = health.leaseCounts[DataBusProtocol.LEASE_QUERY_SESSIONS] ?: 0

        val exceeded = mutableListOf<String>()
        if (filesystem > FILESYSTEM_QUEUE_WARN_COUNT) {
            exceeded += "${DataBusProtocol.EVENT_FILESYSTEM}=$filesystem"
        }
        if (redirectNotice > REDIRECT_NOTICE_QUEUE_WARN_COUNT) {
            exceeded += "${DataBusProtocol.EVENT_REDIRECT_NOTICE}=$redirectNotice"
        }
        if (consumed > CONSUMED_QUEUE_WARN_COUNT) {
            exceeded += "consumed=$consumed"
        }
        if (querySessionLease > QUERY_SESSION_LEASE_WARN_COUNT) {
            exceeded += "${DataBusProtocol.LEASE_QUERY_SESSIONS}=$querySessionLease"
        }
        if (exceeded.isEmpty() || now - lastBacklogWarningAt < BACKLOG_WARN_INTERVAL_MS) {
            warnIfCursorUnreadable(health, now)
            return
        }
        lastBacklogWarningAt = now
        Log.w("MC_STATE", JSONObject().apply {
            put("event", "databus_backlog")
            put("filesystem", filesystem)
            put("redirectNotice", redirectNotice)
            put("consumed", consumed)
            put("querySessionLease", querySessionLease)
            put("exceeded", exceeded.joinToString(","))
        }.toString())
        warnIfCursorUnreadable(health, now)
    }

    /**
     * 游标故障告警：与积压告警分离的第二类告警。
     *
     * UNREADABLE 队列的 pending 数字不可信，不得作为积压依据；
     * 此处独立暴露故障，使游标损坏在诊断层可见。
     */
    @Volatile
    private var lastCursorFailureAt = 0L

    private fun warnIfCursorUnreadable(health: DataBusProtocol.HealthReport, now: Long) {
        val unreadable = health.cursorReadStates
            .filterValues { it == DataBusProtocol.CursorRead.UNREADABLE }
            .keys
        if (unreadable.isEmpty() || now - lastCursorFailureAt < BACKLOG_WARN_INTERVAL_MS) return
        lastCursorFailureAt = now
        Log.w("MC_STATE", JSONObject().apply {
            put("event", "databus_cursor_unreadable")
            put("queues", unreadable.joinToString(","))
        }.toString())
    }
}
