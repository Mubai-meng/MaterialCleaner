package me.gm.cleaner.runtime.server.hookbridge

import android.os.RemoteException
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import org.json.JSONObject
import java.util.function.Consumer

/**
 * Server-side gateway to the MediaProvider Java Hook runtime.
 *
 * This is a logical boundary before the runtime modules are physically split.
 * Server components should depend on this gateway instead of depending on
 * CleanerHooksClient or xposed implementation classes directly.
 */
object MediaProviderHookGateway {

    /** configured_mount_points snapshot generation 缓存（避免每 2s 健康检查读 DataBus） */
    @Volatile
    private var cachedMountPointsGeneration: Long = 0L
    /** 上次读取 DataBus snapshot 时的 signal 时间戳（跳过未变更的信号） */
    @Volatile
    private var lastMountSignalTimestamp: Long = 0L

    /**
     * 桥（应用进程 HooksBridgeProvider）成功建立的单调代数，每成功重连一次 +1。
     *
     * 用途：桥一旦换代，新桥手上必然没有 MediaProvider 的 Binder，
     * `isMediaProviderHookConnected()` 会立刻返回 false。这是**过渡态**，
     * 必须与"MediaProvider 侧真的没注册"区分开，否则恢复策略会把每次
     * 应用进程回收都当成故障去 force-stop MediaProvider。
     */
    @Volatile
    private var bridgeConnectionEpoch: Long = 0L

    fun bridgeConnectionEpoch(): Long = bridgeConnectionEpoch

    /** 由 [CleanerHooksClient] 在每次成功建立桥连接后调用。 */
    fun onBridgeConnected() {
        bridgeConnectionEpoch++
    }

    fun start(server: CleanerServer) {
        CleanerHooksClient.onStart(server)
    }

    fun tryReconnect(server: CleanerServer): Boolean =
        // 协调器已有有界退避与冷却，不能再由懒调用节流消耗恢复次数。
        CleanerHooksClient.tryReconnect(server, respectThrottle = false)

    @JvmStatic
    fun whileAlive(action: Consumer<ICleanerHooksService>) {
        CleanerHooksClient.whileAlive(action)
    }

    @JvmStatic
    fun pingBinder(): Boolean =
        CleanerHooksClient.pingBinder()

    fun isMediaProviderHookConnected(): Boolean =
        runCatching {
            var connected = false
            whileAlive { service ->
                connected = service.isMediaProviderHookConnected
            }
            connected
        }.getOrDefault(false)

    fun registerCleanerServerBinder(server: CleanerServer) {
        whileAlive { service ->
            service.setCleanerServerBinder(server.mCleanerServerCallback)
        }
    }

    fun registerAndRefreshFromDataBus(server: CleanerServer) {
        whileAlive { service ->
            try {
                service.setCleanerServerBinder(server.mCleanerServerCallback)
                service.refreshPolicyFromDataBus()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    @JvmStatic
    fun refreshPolicyFromDataBus() {
        whileAlive { service ->
            try {
                service.refreshPolicyFromDataBus()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    @JvmStatic
    fun reconnectIfNeeded() {
        whileAlive {
            // Trigger CleanerHooksClient's lazy reconnect path.
        }
    }

    fun nativeMountPointsGeneration(): Long =
        runCatching {
            var generation = 0L
            whileAlive { service ->
                generation = service.nativeMountPointsGeneration
            }
            generation
        }.getOrDefault(0L)

    fun nativeHookStatusJson(): String =
        runCatching {
            var status = ""
            whileAlive { service ->
                status = service.nativeHookStatusJson
            }
            status
        }.getOrDefault("")

    /**
     * 获取 configured_mount_points snapshot generation。
     *
     * 使用两级缓存减少 DataBus 文件 I/O：
     * 1. 先检查 signal 是否变更（仅 stat 代价）
     * 2. 信号未变 → 返回缓存值
     * 3. 信号变更 → 重新读取 DataBus 并更新缓存
     *
     * 此方法每 2s 被健康检查调用一次，缓存将 DataBus 读从每轮减少到仅在发布时。
     */
    fun configuredMountPointsSnapshotGeneration(): Long {
        val signalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
        if (signalTime <= lastMountSignalTimestamp && lastMountSignalTimestamp > 0) {
            return cachedMountPointsGeneration
        }
        // 信号变更，重新读取 DataBus
        val gen = DataBus.readSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS)
            ?.let { json ->
                runCatching { JSONObject(json).optLong("generation", 0L) }.getOrDefault(0L)
            } ?: 0L
        cachedMountPointsGeneration = gen
        lastMountSignalTimestamp = signalTime
        return gen
    }

    /**
     * 丢弃 configured_mount_points snapshot generation 缓存，使其下次强制重读 DataBus。
     *
     * 注意：**不要**在桥 Binder 死亡时调用。桥的宿主是 me.gm.cleaner 应用进程，
     * 它死亡完全不影响 MediaProvider 进程内的 native 挂载点；当时清零只会让
     * `nativeHookHealthCheck` 出现一次虚假的 "nativeGen=0 < snapshotGen=N"。
     * 当前唯一调用点是恢复策略在**真的**决定 force-stop MediaProvider 之前，
     * 用于保证 MediaProvider 重启后重新计算 generation 时不会读到旧缓存值。
     */
    fun resetNativeStateForReconnect() {
        cachedMountPointsGeneration = 0L
        lastMountSignalTimestamp = 0L
    }

    @JvmStatic
    fun onDestroy() {
        CleanerHooksClient.onDestroy()
    }
}
