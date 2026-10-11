package me.gm.cleaner.runtime.server.orchestrator

import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import me.gm.cleaner.model.LayerStatus as IpcLayerStatus
import me.gm.cleaner.model.OrchestratedStatus as IpcOrchestratedStatus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol

/**
 * 三层运行状态聚合器。
 */
class RuntimeStatusAggregator(
    private val server: CleanerServer,
    private val recoverySnapshotProvider: () -> RuntimeRecoverySnapshot = {
        RuntimeRecoverySnapshot()
    },
) {
    private val statusGeneration = AtomicLong(0)
    private val layerStartedAt = ConcurrentHashMap<LayerId, Long>()

    /** 上次落盘的语义指纹；null 表示尚未落盘过。 */
    private var lastPublishedSemanticKey: String? = null

    fun collectStatusJson(): String = renderStatusJson(collectStatus())

    private fun renderStatusJson(status: OrchestratedStatus): String {
        val root = JSONObject()
        root.put("vfs", status.vfs.toJson())
        root.put("mediaProviderJavaHook", status.mediaProviderJavaHook.toJson())
        root.put("fuseNativeHook", status.fuseNativeHook.toJson())
        root.put("dataBus", status.dataBus.toJson())
        root.put("controlPlane", status.controlPlane.toJson())
        root.put("health", status.health.name)
        // 最近错误事件流水：与 DiagnosticArchive 的 errors/journal.jsonl 同源。
        root.put(
            "recentErrors",
            org.json.JSONArray().apply {
                ServerErrorJournal.snapshot().forEach { event ->
                    put(JSONObject().apply {
                        put("code", event.code)
                        put("atElapsed", event.atElapsed)
                        if (event.errno != 0) put("errno", event.errno)
                        event.subject?.let { put("subject", it) }
                        event.pathDigest?.let { put("pathDigest", it) }
                        if (event.generation > 0L) put("generation", event.generation)
                        event.detail?.let { put("detail", it) }
                    })
                }
            }
        )
        return root.toString(2)
    }

    fun collectStatusForIpc(): IpcOrchestratedStatus {
        val status = collectStatus()
        return IpcOrchestratedStatus(
            status.health.name,
            status.vfs.toIpc(),
            status.mediaProviderJavaHook.toIpc(),
            status.fuseNativeHook.toIpc(),
            status.dataBus.toIpc(),
            status.controlPlane.toIpc(),
        )
    }

    /**
     * 落盘一份编排状态快照（供诊断与跨进程观察）。
     *
     * ## 为什么需要脏检查
     * 本方法挂在 EventConsumerScheduler 的 **2s 心跳**上，而 [DataBus.writeSnapshot]
     * 每次都要建 tmp 文件 → write → fsync → rename → chmod，且**自身无脏检查**。
     * 实测单窗口写了 **105 次 × 约 5.28KB ≈ 554KB**，全部是重复内容。
     *
     * ## 为什么不能直接用内容哈希
     * payload 里的 `generation` 来自 `statusGeneration.incrementAndGet()`（每次采集必变），
     * `lastHeartbeatAt` / `nativeStatusAgeMs` 等都是从「当次采集时刻」派生的 —— 逐字节
     * 哈希**永远不会命中**。所以判据必须建立在语义字段上，见 [semanticKey]。
     *
     * ## 落盘策略：严格「语义变化才落盘」
     * 语义变了 → 立即落盘；语义没变 → **完全不落盘**。
     *
     * 刻意**不保留**「距上次落盘超过 N 秒就重发一次」的时间兜底：该兜底的唯一作用是
     * 让文件里的心跳/代数不过分陈旧，而本快照文件在本仓库中**没有任何读取方** ——
     * - 状态卡走 `CleanerService.getOrchestratedStatus()` → [collectStatusForIpc]（实时 IPC，不读文件）
     * - 诊断包走 [collectStatusJson]（内存新值，自写 zip 与 DataBus）
     * - `DataBus.readSnapshot(SNAPSHOT_ORCHESTRATED_STATUS)` 在全仓库无调用点
     *
     * 因此时间兜底只会产生纯粹的重复 fsync 写盘（实测 21 次写 / 21 个不同秒）。若将来
     * 出现文件读取方，应连同**读者侧的陈旧性判据**一起加回来，而不是单方面恢复定时重写。
     *
     * 诊断包不受本变更影响：`DiagnosticArchive` 走内存新值 `collectStatusJson()`
     * 自行写入 zip 与 DataBus，取到的永远是采集时刻的实时状态。
     */
    fun publishStatusSnapshot() {
        if (!DataBus.ensureInitialized()) return

        val status = collectStatus()
        val key = semanticKey(status)
        if (key == lastPublishedSemanticKey) {
            return
        }

        // 仅在确实要写盘时才渲染 JSON，省掉每轮 5KB 的序列化开销。
        if (DataBus.writeSnapshot(
                DataBusProtocol.SNAPSHOT_ORCHESTRATED_STATUS,
                renderStatusJson(status),
            )
        ) {
            lastPublishedSemanticKey = key
        }
    }

    /**
     * 语义指纹：只取「决定架构是否有效」的字段，剔除全部易变字段。
     *
     * 取每层的 `state` / `lastErrorAt` / `lastError` 与顶层 `health`。
     * 刻意**不含** `generation`（每轮递增）、`lastHeartbeatAt` / `lastStartedAt`
     * （采集时刻）、以及 metrics 里的派生量（如 `nativeStatusAgeMs` 每轮 +2000）。
     *
     * 因此 metrics 的增量变化**不会**触发落盘 —— 这是刻意的：本快照文件无读取方
     * （见 [publishStatusSnapshot]），metrics 的实时值走 [collectStatusForIpc] 的 IPC
     * 与诊断包的 `collectStatusJson()`，都不依赖该文件。
     */
    private fun semanticKey(status: OrchestratedStatus): String = buildString {
        append(status.health.name)
        for (layer in listOf(
            status.vfs,
            status.mediaProviderJavaHook,
            status.fuseNativeHook,
            status.dataBus,
            status.controlPlane,
        )) {
            append('|').append(layer.id.name)
            append(':').append(layer.state.name)
            append(':').append(layer.lastErrorAt)
            append(':').append(layer.lastError)
        }
    }

    private fun collectStatus(): OrchestratedStatus {
        val now = System.currentTimeMillis()
        val gen = statusGeneration.incrementAndGet()
        val vfsReport = server.vfsLayerController.collectReport(gen, now)

        val hooksBridgeConnected = MediaProviderHookGateway.pingBinder()
        val mediaProviderHookConnected = if (hooksBridgeConnected) {
            MediaProviderHookGateway.isMediaProviderHookConnected()
        } else {
            false
        }
        val recoverySnapshot = recoverySnapshotProvider()
        val hookReport = MediaProviderHookLayerReporter.collect(
            generation = gen,
            now = now,
            hooksBridgeConnected = hooksBridgeConnected,
            mediaProviderHookConnected = mediaProviderHookConnected,
            recovery = recoverySnapshot,
        )

        val nativeReport = NativeHookLayerReporter.collect(
            generation = gen,
            now = now,
            mediaProviderHookConnected = mediaProviderHookConnected,
        )

        val busReport = DataBusLayerReporter.collect(gen, now)

        val controlReport = ControlPlaneLayerReporter.collect(
            server = server,
            generation = gen,
            now = now,
            hooksBridgeConnected = hooksBridgeConnected,
            mediaProviderHookConnected = mediaProviderHookConnected,
            recovery = recoverySnapshot,
        )

        return OrchestratedStatus.evaluate(
            vfs = recordLayerStarted(vfsReport),
            mediaProviderJavaHook = recordLayerStarted(hookReport),
            fuseNativeHook = recordLayerStarted(nativeReport),
            dataBus = recordLayerStarted(busReport),
            controlPlane = recordLayerStarted(controlReport),
        )
    }

    private fun recordLayerStarted(report: LayerReport): LayerReport {
        if (report.state != LayerState.HEALTHY) {
            return report.copy(
                lastStartedAt = layerStartedAt[report.id] ?: 0L
            )
        }
        val now = report.lastHeartbeatAt
        val started = layerStartedAt[report.id]
        if (started == null) {
            layerStartedAt[report.id] = now
            return report.copy(lastStartedAt = now)
        }
        return report.copy(lastStartedAt = started)
    }

    private fun LayerReport.toJson(): JSONObject = JSONObject().apply {
        put("id", id.name)
        put("state", state.name)
        put("generation", generation)
        put("lastStartedAt", lastStartedAt)
        put("lastHeartbeatAt", lastHeartbeatAt)
        put("lastErrorAt", lastErrorAt)
        put("lastError", lastError)
        for ((key, value) in metrics) {
            put(key, value)
        }
    }

    private fun LayerReport.toIpc(): IpcLayerStatus {
        return IpcLayerStatus(
            id.name,
            state.name,
            generation,
            lastStartedAt,
            lastHeartbeatAt,
            lastErrorAt,
            lastError,
            metrics.keys.toTypedArray(),
            metrics.values.toTypedArray(),
        )
    }

}
