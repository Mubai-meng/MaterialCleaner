package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.mediaprovider.hook.err.HookErrorJournal
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus
import me.gm.cleaner.server.ICleanerServerCallback
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol

/**
 * MediaProvider 进程访问 DataBus 的统一入口。
 *
 * 设备实测表明，MediaProvider SELinux 域可能无法直接访问
 * /data/local/tmp/cleaner/bus，因此优先通过 root server 注入的 Binder
 * callback 读取快照和 signal 时间戳。直接文件读取只作为兜底路径保留。
 */
object HookDataBusBridge {
    private const val TAG = "HookDataBusBridge"
    private const val FALLBACK_COOLDOWN_MS = 30_000L
    private val hookWritableSnapshots = setOf(
        DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS,
    )
    private val hookWritableSignals = setOf(
        DataBusProtocol.SIGNAL_FILESYSTEM_EVENTS_CHANGED,
        DataBusProtocol.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED,
        DataBusProtocol.SIGNAL_QUERY_SESSION_LEASES_CHANGED,
        DataBusProtocol.SIGNAL_NATIVE_HOOK_STATUS_CHANGED,
    )

    @Volatile
    private var callback: ICleanerServerCallback? = null

    /**
     * DataBus **文件兜底**的冷却截止时刻（elapsedRealtime）。
     *
     * MediaProvider（uid 10326）的 SELinux 域写不了 /data/local/tmp/cleaner/bus，
     * 兜底路径**注定失败**。旧实现每条事件都要把 `ensureInitialized()` +
     * `prepareDirectory()` + 写文件走一遍，全是白付的 tmpfs IO。
     * 失败一次后冷却 [FALLBACK_COOLDOWN_MS]，期间直接放弃并记录流水。
     * 冷却到期会再试一次 —— 若权限环境变化（如 server 换了可写目录）仍能自适应。
     */
    @Volatile
    private var fallbackDisabledUntilElapsed = 0L

    fun setCallback(value: ICleanerServerCallback?) {
        callback = value
    }

    fun readSnapshot(name: String): String? {
        val remote = callback
        if (remote != null) {
            try {
                val value = remote.readDataBusSnapshot(name)
                if (!value.isNullOrEmpty()) return value
            } catch (e: RemoteException) {
                Log.w(TAG, "readSnapshot via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "readSnapshot via server failed: $name", e)
                reportBridgeFailure(
                    ErrorCodes.HOOK_JAVA_BUS_WRITE_FAILED,
                    "readSnapshot via server failed: $name (${e.javaClass.simpleName})",
                )
            }
        }
        return DataBus.readSnapshot(name)
    }

    fun getSignalTimestamp(name: String): Long {
        val remote = callback
        if (remote != null) {
            try {
                return remote.getDataBusSignalTimestamp(name)
            } catch (e: RemoteException) {
                Log.w(TAG, "getSignalTimestamp via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "getSignalTimestamp via server failed: $name", e)
                reportBridgeFailure(
                    ErrorCodes.HOOK_JAVA_BUS_WRITE_FAILED,
                    "getSignalTimestamp via server failed: $name (${e.javaClass.simpleName})",
                )
            }
        }
        return DataBus.getSignalTimestamp(name)
    }

    fun writeEvent(queue: String, content: String): Long {
        // 冷却期内不再尝试注定失败的文件兜底（理由见字段注释）。
        if (SystemClock.elapsedRealtime() < fallbackDisabledUntilElapsed) {
            return -1L
        }
        val remote = callback
        if (remote != null) {
            try {
                val seq = remote.writeDataBusEvent(queue, content)
                if (seq >= 0L) return seq
            } catch (e: RemoteException) {
                Log.w(TAG, "writeEvent via server failed: $queue", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "writeEvent via server failed: $queue", e)
                reportBridgeFailure(
                    ErrorCodes.HOOK_JAVA_BUS_WRITE_FAILED,
                    "writeEvent via server failed: $queue (${e.javaClass.simpleName})",
                )
            }
        }
        val seq = DataBus.writeEvent(queue, content)
        if (seq < 0L) {
            fallbackDisabledUntilElapsed = SystemClock.elapsedRealtime() + FALLBACK_COOLDOWN_MS
            reportBridgeFailure(
                ErrorCodes.HOOK_JAVA_BUS_WRITE_FAILED,
                "writeEvent fallback returned $seq: $queue, cooling down " +
                        "${FALLBACK_COOLDOWN_MS}ms",
            )
        }
        return seq
    }

    /**
     * 远程通道（或 DataBus 兜底）写入失败的统一留痕。
     *
     * 这些分支此前只 `Log.w`，而 ColorOS `LOG_FLOWCTRL` 会按进程配额丢日志，
     * 于是「写失败」这一事实可能永远进不了诊断包。这里补一条
     * [NativeHookStatus.markHookError]，随快照落进 `errors/hook_errors.jsonl`。
     */
    private fun reportBridgeFailure(code: String, detail: String) {
        NativeHookStatus.markHookError(
            code, detail, "HookDataBusBridge",
            HookErrorJournal.SEVERITY_WARN, 0, 0L,
        )
    }

    fun writeLease(category: String, key: String, content: String): Boolean {
        val remote = callback
        if (remote != null) {
            try {
                if (remote.writeDataBusLease(category, key, content)) return true
            } catch (e: RemoteException) {
                Log.w(TAG, "writeLease via server failed: $category/$key", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "writeLease via server failed: $category/$key", e)
                reportBridgeFailure(
                    ErrorCodes.HOOK_JAVA_BUS_WRITE_FAILED,
                    "writeLease via server failed: $category/$key (${e.javaClass.simpleName})",
                )
            }
        }
        val ok = DataBus.writeLease(category, key, content)
        if (!ok) {
            reportBridgeFailure(
                ErrorCodes.HOOK_JAVA_BUS_WRITE_FAILED,
                "writeLease fallback failed: $category/$key",
            )
        }
        return ok
    }

    fun writeSnapshot(name: String, content: String): Boolean {
        if (name !in hookWritableSnapshots) {
            Log.w(TAG, "Rejected hook snapshot write: $name")
            reportBridgeFailure(
                ErrorCodes.BUS_WRITE_REJECTED,
                "Rejected hook snapshot write: $name",
            )
            return false
        }
        val remote = callback
        if (remote != null) {
            try {
                if (remote.writeDataBusSnapshot(name, content)) return true
            } catch (e: RemoteException) {
                Log.w(TAG, "writeSnapshot via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "writeSnapshot via server failed: $name", e)
            }
        }
        return DataBus.ensureInitialized() && DataBus.writeSnapshot(name, content)
    }

    fun signal(name: String): Boolean {
        if (name !in hookWritableSignals) {
            Log.w(TAG, "Rejected hook signal write: $name")
            reportBridgeFailure(
                ErrorCodes.BUS_WRITE_REJECTED,
                "Rejected hook signal write: $name",
            )
            return false
        }
        val remote = callback
        if (remote != null) {
            try {
                if (remote.signalDataBus(name)) return true
            } catch (e: RemoteException) {
                Log.w(TAG, "signal via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "signal via server failed: $name", e)
            }
        }
        return DataBus.signal(name)
    }
}
