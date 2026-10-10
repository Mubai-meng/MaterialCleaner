package me.gm.cleaner.runtime.server.orchestrator

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.server.CleanerServerCallback
import me.gm.cleaner.runtime.server.consumer.EventDeadLetter
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
        // 积压深度必须按游标计算。events/<queue>/ 里的文件只被游标越过、从不删除，
        // 目录文件数是“累计写入量”；直接用它当积压量会每 60s 报一次假 backlog
        // （实测 filesystem=561 时游标已指向最后一个事件，队列其实是空的）。
        //
        // 该值由 checkHealth 的**同一次目录遍历**一并给出，无需再扫一遍目录；
        // 缺键时（例如外部自行构造的 HealthReport）回退到单点查询以保证行为不变。
        val filesystemPending = health.pendingEventQueueCounts[DataBusProtocol.EVENT_FILESYSTEM]
            ?: DataBus.pendingEventCount(DataBusProtocol.EVENT_FILESYSTEM)
        val redirectNoticePending =
            health.pendingEventQueueCounts[DataBusProtocol.EVENT_REDIRECT_NOTICE]
                ?: DataBus.pendingEventCount(DataBusProtocol.EVENT_REDIRECT_NOTICE)
        warnIfBacklog(health, now, filesystemPending, redirectNoticePending)
        val platformCapsJson = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
        val platformCaps = platformCapsJson?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        val metrics = linkedMapOf(
            "busRootExists" to health.initialized.toString(),
            "missingDirectoryCount" to health.missingDirectories.size.toString(),
            "permissionIssueCount" to health.permissionIssues.size.toString(),
            "eventQueueFilesystem" to filesystemPending.toString(),
            "eventQueueRedirectNotice" to redirectNoticePending.toString(),
            "eventQueueFilesystemArchived" to
                    (health.eventQueueCounts[DataBusProtocol.EVENT_FILESYSTEM] ?: 0).toString(),
            "eventQueueConsumed" to (health.eventQueueCounts["consumed"] ?: 0).toString(),
            // 隔离计数：把"队列卡死"变成可见的"N 条已隔离"。
            // 非 0 即说明有事件永久失败（毒丸），但**队列已经解开**、后续事件正常消费。
            "eventQueueFilesystemQuarantined" to
                    EventDeadLetter.count(DataBusProtocol.EVENT_FILESYSTEM).toString(),
            "leaseQuerySessions" to (health.leaseCounts[DataBusProtocol.LEASE_QUERY_SESSIONS] ?: 0).toString(),
            // 入站 AIDL 体积（hook 进程 → server，见 CleanerServerCallback.sInboundCallbackCount）。
            // 存在的唯一目的是把 `E Parcel`（每批条目最多的 E tag）从"找调用点"改成
            // **可重复的定量对照**：下一批直接算 E Parcel ÷ 本值。详见该字段的 KDoc。
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

    private fun warnIfBacklog(
        health: DataBusProtocol.HealthReport,
        now: Long,
        filesystemPending: Int,
        redirectNoticePending: Int,
    ) {
        val consumed = health.eventQueueCounts["consumed"] ?: 0
        val querySessionLease = health.leaseCounts[DataBusProtocol.LEASE_QUERY_SESSIONS] ?: 0

        val exceeded = mutableListOf<String>()
        if (filesystemPending > FILESYSTEM_QUEUE_WARN_COUNT) {
            exceeded += "${DataBusProtocol.EVENT_FILESYSTEM}=$filesystemPending"
        }
        if (redirectNoticePending > REDIRECT_NOTICE_QUEUE_WARN_COUNT) {
            exceeded += "${DataBusProtocol.EVENT_REDIRECT_NOTICE}=$redirectNoticePending"
        }
        if (consumed > CONSUMED_QUEUE_WARN_COUNT) {
            exceeded += "consumed=$consumed"
        }
        if (querySessionLease > QUERY_SESSION_LEASE_WARN_COUNT) {
            exceeded += "${DataBusProtocol.LEASE_QUERY_SESSIONS}=$querySessionLease"
        }
        if (exceeded.isEmpty() || now - lastBacklogWarningAt < BACKLOG_WARN_INTERVAL_MS) {
            return
        }
        lastBacklogWarningAt = now
        Log.w("MC_STATE", JSONObject().apply {
            put("event", "databus_backlog")
            put("filesystem", filesystemPending)
            put("redirectNotice", redirectNoticePending)
            put("consumed", consumed)
            put("querySessionLease", querySessionLease)
            put("exceeded", exceeded.joinToString(","))
        }.toString())
    }
}
