package me.gm.cleaner.runtime.server;

import android.os.Binder;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import api.SystemService;
import me.gm.cleaner.core.common.RuntimeFileUtils;
import me.gm.cleaner.core.storage.redirect.databus.DataBus;
import me.gm.cleaner.server.ICleanerServerCallback;
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol;

/**
 * Server-side DataBus proxy exposed to the MediaProvider Hook process.
 *
 * The Hook layer reads policies and writes events through DataBus. The callback
 * only provides a privileged server-side proxy when direct filesystem access is
 * unavailable; it must not become a policy or UI side-effect endpoint again.
 */
public class CleanerServerCallback extends ICleanerServerCallback.Stub {
    private static final String TAG = "CleanerServerCallback";

    /**
     * **入站** AIDL 调用累计次数（hook 进程 → 本 server 进程），已通过调用方校验的那些。
     *
     * ## 为什么要有这个计数（`E Parcel` 归因思路的转变）
     *
     * `E Parcel: Native binder in markForBinder is null for non-null jobject`
     * （外加同 tid 前置的 `W JavaBinder: ibinderForJavaObject … is not a Binder object`）
     * 在连续三批会话里都是**条目最多的 E tag**，且与我们的调用点**对不上**：
     * 我方全部可能产生点合计只有 13，而它单批 1266~1371 条；字符串
     * `markForBinder` / `is not a Binder object` 在 framework 与本仓库三处均 0 命中
     * （产生者在 `libandroid_runtime`）。
     *
     * 过去两批都在**找调用点**，两次都被"执行次数 vs 日志条数"证伪。现在换成**量化对照**：
     * 把这个可数的 IPC 体积暴露到 orchestrator 状态里，下一批直接算
     * `E Parcel 条数 ÷ 本计数`，看该比值是否跨会话稳定：
     * - 稳定 ⇒ 它是"每次 binder 事务固定附带若干条"的框架开销，与业务无关；
     * - 不随本计数变化 ⇒ 与我们的 IPC 无关，彻底关闭这条线。
     *
     * 无论结论如何都能落地，不再依赖"猜调用点"。计数是一次 AtomicLong 自增，无 IO。
     */
    private static final java.util.concurrent.atomic.AtomicLong sInboundCallbackCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** 供 orchestrator 状态采集读取（见 `DataBusLayerReporter`）。 */
    public static long inboundCallbackCount() {
        return sInboundCallbackCount.get();
    }

    private static final String[] MEDIA_PROVIDER_PACKAGES = {
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module"
    };

    private static boolean isPrivilegedServerUid(int uid) {
        final var appId = RuntimeFileUtils.INSTANCE.toAppId(uid);
        return appId == Process.ROOT_UID ||
                appId == Process.SYSTEM_UID ||
                appId == Process.SHELL_UID;
    }

    private static boolean isKnownMediaProviderPackage(String packageName) {
        for (final var candidate : MEDIA_PROVIDER_PACKAGES) {
            if (candidate.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownMediaProviderUid(int uid) {
        final var packages = SystemService.getPackagesForUidNoThrow(uid);
        for (final var packageName : packages) {
            if (isKnownMediaProviderPackage(packageName)) {
                return true;
            }
        }

        final var userId = RuntimeFileUtils.INSTANCE.toUserId(uid);
        final var callerAppId = RuntimeFileUtils.INSTANCE.toAppId(uid);
        for (final var packageName : MEDIA_PROVIDER_PACKAGES) {
            final var pi = SystemService.getPackageInfoNoThrow(packageName, 0, userId);
            if (pi != null && RuntimeFileUtils.INSTANCE.toAppId(pi.applicationInfo.uid) == callerAppId) {
                return true;
            }
        }
        return false;
    }

    private static void enforceHookCaller(String method) {
        final var callingPid = Binder.getCallingPid();
        final var callingUid = Binder.getCallingUid();
        if (callingPid == Os.getpid() ||
                isPrivilegedServerUid(callingUid) ||
                isKnownMediaProviderUid(callingUid)) {
            // 通过校验 = 一次真正会被受理的入站 binder 事务。本方法在每个 AIDL 入口
            // 恰好调用一次，所以这里是唯一需要的计数点（见 sInboundCallbackCount 的说明）。
            sInboundCallbackCount.incrementAndGet();
            return;
        }
        Log.w(TAG, "Rejected callback caller: method=" + method +
                " uid=" + callingUid + " pid=" + callingPid);
        throw new SecurityException("Unauthorized CleanerServerCallback caller: " + method);
    }

    private static boolean isHookWritableSnapshot(String name) {
        return DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS.equals(name);
    }

    private static boolean isHookWritableSignal(String name) {
        return DataBusProtocol.SIGNAL_FILESYSTEM_EVENTS_CHANGED.equals(name) ||
                DataBusProtocol.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED.equals(name) ||
                DataBusProtocol.SIGNAL_QUERY_SESSION_LEASES_CHANGED.equals(name) ||
                DataBusProtocol.SIGNAL_NATIVE_HOOK_STATUS_CHANGED.equals(name);
    }

    @Override
    public String readDataBusSnapshot(String name) {
        enforceHookCaller("readDataBusSnapshot");
        final var snapshot = DataBus.INSTANCE.readSnapshot(name);
        return snapshot == null ? "" : snapshot;
    }

    @Override
    public long getDataBusSignalTimestamp(String name) {
        enforceHookCaller("getDataBusSignalTimestamp");
        return DataBus.INSTANCE.getSignalTimestamp(name);
    }

    @Override
    public long writeDataBusEvent(String queue, String content) {
        enforceHookCaller("writeDataBusEvent");
        return DataBus.INSTANCE.writeEvent(queue, content);
    }

    @Override
    public boolean writeDataBusLease(String category, String key, String content) {
        enforceHookCaller("writeDataBusLease");
        return DataBus.INSTANCE.writeLease(category, key, content);
    }

    @Override
    public boolean writeDataBusSnapshot(String name, String content) {
        enforceHookCaller("writeDataBusSnapshot");
        if (!isHookWritableSnapshot(name)) {
            return false;
        }
        return DataBus.INSTANCE.ensureInitialized() &&
                DataBus.INSTANCE.writeSnapshot(name, content);
    }

    @Override
    public boolean signalDataBus(String name) {
        enforceHookCaller("signalDataBus");
        if (!isHookWritableSignal(name)) {
            return false;
        }
        return DataBus.INSTANCE.signal(name);
    }
}
