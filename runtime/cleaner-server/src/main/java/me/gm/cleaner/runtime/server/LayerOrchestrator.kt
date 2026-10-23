package me.gm.cleaner.runtime.server

import android.util.Log
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import me.gm.cleaner.runtime.server.lifecycle.ObserverManager
import me.gm.cleaner.runtime.server.process.BaseProcessObserver
import me.gm.cleaner.runtime.server.storage.StorageMountObserver
import me.gm.cleaner.runtime.server.orchestrator.EventConsumerScheduler
import me.gm.cleaner.runtime.server.orchestrator.HookRecoveryCoordinator
import me.gm.cleaner.runtime.server.orchestrator.MediaProviderRecoveryStrategy
import me.gm.cleaner.runtime.server.orchestrator.RuntimeStatusAggregator
import me.gm.cleaner.runtime.server.orchestrator.RuntimeRecoverySnapshot
import me.gm.cleaner.runtime.server.orchestrator.StartupTiming

/**
 * 三层编排器。
 *
 * 负责三层拦截能力的启动顺序与跨组件委托：
 * - VFS 主机制独立启动，不受 Hook Binder 状态影响
 * - DataBus 发布策略快照与事件队列
 * - Hook 死亡隔离恢复，不重启 VFS 主机制
 */
class LayerOrchestrator(
    private val server: CleanerServer,
) {
    private companion object {
        private const val TAG = "LayerOrchestrator"
    }

    private val eventConsumerScheduler = EventConsumerScheduler(server)
    private val mediaProviderRecoveryStrategy = MediaProviderRecoveryStrategy(server)
    private val hookRecoveryCoordinator = HookRecoveryCoordinator(
        server = server,
        mediaProviderRecoveryStrategy = mediaProviderRecoveryStrategy,
        onReconnectReady = {
            eventConsumerScheduler.pollOnce()
            eventConsumerScheduler.start()
        },
    )
    private val statusAggregator = RuntimeStatusAggregator(server) {
        RuntimeRecoverySnapshot(
            hook = hookRecoveryCoordinator.snapshot(),
            mediaProvider = mediaProviderRecoveryStrategy.snapshot(),
        )
    }

    init {
        eventConsumerScheduler.onHeartbeat = {
            hookRecoveryCoordinator.nativeHookHealthCheck()
            // 失败挂载重收敛：复用既有 2s 心跳，不另起线程；
            // 冷却/永久分类由 Mounter 内聚，出错不影响后继发布。
            runCatching {
                ObserverManager.getObserver(BaseProcessObserver::class.java)
                    ?.requeueFailedMounts()
            }
            statusAggregator.publishStatusSnapshot()
        }
    }

    /**
     * 执行服务器启动编排。
     */
    fun initialize() {
        val startedAt = System.currentTimeMillis()
        var checkpoint = startedAt
        // 分步耗时：开机后「策略快照何时可见」直接决定兼容层多久从 RECOVERING 转 HEALTHY。
        // 2026-10-22 22:06 重启实测：initialize start(22:06:29.830) → 首份 redirect_policy
        // 快照(22:07:02.884) 相隔 33 秒，且这段时间 ColorOS LOG_FLOWCTRL 丢了 2700+ 行，
        // 无法定位卡在哪一步。这里把每一步的耗时汇总成一行，稳态只输出一次，不刷屏。
        fun lap(): Long {
            val now = System.currentTimeMillis()
            val cost = now - checkpoint
            checkpoint = now
            return cost
        }
        Log.i(TAG, "initialize start")

        MediaProviderHookGateway.start(server)
        val gatewayMs = lap()

        ObserverManager.startAllObservers(server)
        val observersMs = lap()

        server.mPackageReceiver.registerPackageReceiver()
        val packageReceiverMs = lap()

        // 日志采集按配置对账（不是"启动时读一次"）：应启用而未运行则启动，
        // 应停用则保持未运行。关闭开关的生效点分别在
        // StoragePolicyChangeCoordinator.onPreferencesChanged() 与本方法。
        server.mAutoLogging.reconcileWithPreferences()
        val autoLoggingMs = lap()

        val dataBusReady = SnapshotPublisher.publishAll()
        val publishMs = lap()
        if (!dataBusReady) {
            Log.w(TAG, "DataBus unavailable during initialize, snapshots will be published on reconnect")
        }

        // 启动即收敛：publish 只更新快照，不管存量进程命名空间；
        // 不在这里 remountAll 的话，重启前挂上的进程将永久失 cover，
        // 直到下一次规则变更（挂载 decay 的根因之一）。
        ObserverManager.getObserver(BaseProcessObserver::class.java)?.remountAll()
        val remountMs = lap()

        MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
        val hookRefreshMs = lap()

        val mountObserver = ObserverManager.getObserver(StorageMountObserver::class.java)
        if (mountObserver != null) {
            mountObserver.setCleanerServer(server)
            mountObserver.registerListener(mountObserver)
        }

        BinderSender.register(server.cleanerService)

        server.sendBinderToManger(server.cleanerService)
        val binderMs = lap()

        eventConsumerScheduler.prepare()
        eventConsumerScheduler.pollOnce()
        eventConsumerScheduler.start()
        val schedulerMs = lap()

        // 九段耗时先落**内存事实**再打日志：ColorOS LOG_FLOWCTRL 按进程限额丢日志，
        // 这行汇总连续两批（第41、42批）整行丢失，而 initialize 显然已跑完
        // ⇒ 只靠日志无法回答「启动花了多久、卡在哪一步」。StartupTiming 的快照由
        // RuntimeStatusAggregator 渲染进 orchestrated_status.json 与诊断包，
        // 走的是实时内存值，不受日志是否被丢弃影响。
        val segments = linkedMapOf(
            StartupTiming.KEY_GATEWAY to gatewayMs,
            StartupTiming.KEY_OBSERVERS to observersMs,
            StartupTiming.KEY_PACKAGE_RECEIVER to packageReceiverMs,
            StartupTiming.KEY_AUTO_LOGGING to autoLoggingMs,
            StartupTiming.KEY_PUBLISH_ALL to publishMs,
            StartupTiming.KEY_REMOUNT to remountMs,
            StartupTiming.KEY_HOOK_REFRESH to hookRefreshMs,
            StartupTiming.KEY_BINDER to binderMs,
            StartupTiming.KEY_SCHEDULER to schedulerMs,
        )
        val elapsedMs = checkpoint - startedAt
        StartupTiming.record(
            startedAtWallMillis = startedAt,
            elapsedMs = elapsedMs,
            segmentsMs = segments,
        )
        // 日志行格式与历史完全一致（同一批键、同一顺序、同样一次 Log.i 调用），
        // 现由 segments 单源生成，避免日志与快照两处各自硬编码而漂移。
        Log.i(
            TAG,
            "initialize done in ${elapsedMs}ms (" +
                segments.entries.joinToString(", ") { "${it.key}=${it.value}" } + ")",
        )
    }

    /**
     * 处理 Hooks Binder 死亡事件。
     */
    fun onHooksBinderDied() {
        hookRecoveryCoordinator.onHooksBinderDied()
    }

    fun collectStatusJson(): String =
        statusAggregator.collectStatusJson()

    fun collectStatusForIpc() =
        statusAggregator.collectStatusForIpc()

    fun publishStatusSnapshot() {
        statusAggregator.publishStatusSnapshot()
    }
}
