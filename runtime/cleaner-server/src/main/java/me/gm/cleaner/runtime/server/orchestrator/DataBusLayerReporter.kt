package me.gm.cleaner.runtime.server.orchestrator

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
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
        val filesystemPending = DataBus.pendingEventCount(DataBus.EVENT_FILESYSTEM)
        val redirectNoticePending = DataBus.pendingEventCount(DataBus.EVENT_REDIRECT_NOTICE)
        warnIfBacklog(health, now, filesystemPending, redirectNoticePending)
        val platformCapsJson = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_PLATFORM_CAPABILITIES)
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
                    (health.eventQueueCounts[DataBus.EVENT_FILESYSTEM] ?: 0).toString(),
            "eventQueueConsumed" to (health.eventQueueCounts["consumed"] ?: 0).toString(),
            // 隔离计数：把"队列卡死"变成可见的"N 条已隔离"。
            // 非 0 即说明有事件永久失败（毒丸），但**队列已经解开**、后续事件正常消费。
            "eventQueueFilesystemQuarantined" to
                    EventDeadLetter.count(DataBus.EVENT_FILESYSTEM).toString(),
            "leaseQuerySessions" to (health.leaseCounts[DataBus.LEASE_QUERY_SESSIONS] ?: 0).toString(),
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
        DataBus.SNAPSHOT_REDIRECT_POLICY -> "snapshotRedirectPolicy"
        DataBus.SNAPSHOT_READ_ONLY -> "snapshotReadOnly"
        DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS -> "snapshotConfiguredMountPoints"
        DataBus.SNAPSHOT_PLATFORM_CAPABILITIES -> "snapshotPlatformCapabilities"
        DataBus.SNAPSHOT_NATIVE_HOOK_STATUS -> "snapshotNativeHookStatus"
        DataBus.SNAPSHOT_ORCHESTRATED_STATUS -> "snapshotOrchestratedStatus"
        else -> "snapshot${name.replaceFirstChar { it.uppercaseChar() }}"
    }

    private fun buildError(health: DataBus.HealthReport): String? {
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
            DataBus.SNAPSHOT_REDIRECT_POLICY to "redirect_policy",
            DataBus.SNAPSHOT_READ_ONLY to "read_only",
            DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS to "configured_mount_points",
        ).filterNot { (name, _) -> health.hasSnapshot(name) }
            .joinToString(",") { (_, label) -> label }
        if (missingCritical.isNotBlank()) {
            parts += "missing critical snapshots=$missingCritical"
        }
        return parts.joinToString("; ").ifBlank { "DataBus degraded" }
    }

    private fun warnIfBacklog(
        health: DataBus.HealthReport,
        now: Long,
        filesystemPending: Int,
        redirectNoticePending: Int,
    ) {
        val consumed = health.eventQueueCounts["consumed"] ?: 0
        val querySessionLease = health.leaseCounts[DataBus.LEASE_QUERY_SESSIONS] ?: 0

        val exceeded = mutableListOf<String>()
        if (filesystemPending > FILESYSTEM_QUEUE_WARN_COUNT) {
            exceeded += "${DataBus.EVENT_FILESYSTEM}=$filesystemPending"
        }
        if (redirectNoticePending > REDIRECT_NOTICE_QUEUE_WARN_COUNT) {
            exceeded += "${DataBus.EVENT_REDIRECT_NOTICE}=$redirectNoticePending"
        }
        if (consumed > CONSUMED_QUEUE_WARN_COUNT) {
            exceeded += "consumed=$consumed"
        }
        if (querySessionLease > QUERY_SESSION_LEASE_WARN_COUNT) {
            exceeded += "${DataBus.LEASE_QUERY_SESSIONS}=$querySessionLease"
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
