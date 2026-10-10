package me.gm.cleaner.runtime.mediaprovider.hook.media;

import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookDataBusBridge;
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookPolicyRefreshScheduler;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.HookPolicyCache;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.HookRuntimeConfig;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus;

import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import api.SystemService;
import me.gm.cleaner.server.ICleanerServerCallback;
import me.gm.cleaner.server.IMediaProviderHooksService;

public class MediaProviderHooksService extends IMediaProviderHooksService.Stub {
    private volatile ICleanerServerCallback mCleanerServerBinder = null;
    private final AtomicBoolean mPolicyCacheInitialized = new AtomicBoolean(false);
    private final IBinder.DeathRecipient mCleanerServerDeathRecipient = () -> {
        mCleanerServerBinder = null;
        HookDataBusBridge.INSTANCE.setCallback(null);
        requestReRegister("server callback died");
    };

    /** 当 Server 回调丢失时由 XposedInit 注入的重新注册回调 */
    public static volatile Runnable sReRegisterCallback;

    /**
     * 初始化本地策略缓存 + 启动定时刷新调度器。
     * 应在 Xposed 模块加载时调用一次。
     */
    public void initPolicyCache() {
        if (!mPolicyCacheInitialized.compareAndSet(false, true)) {
            return;
        }
        HookPolicyCache.INSTANCE.initFromDataBus();
        // 启动定时刷新调度器（每 5s 检查信号并刷新 native 挂载点）
        HookPolicyRefreshScheduler.INSTANCE.start();
        NativeHookStatus.INSTANCE.markPolicyCacheInitialized();
    }

    public void whileAlive(Consumer<ICleanerServerCallback> c) {
        if (mCleanerServerBinder != null) {
            c.accept(mCleanerServerBinder);
        } else {
            Log.w("MC_REDIRECT", "[MediaProviderHooksService] whileAlive: mCleanerServerBinder is NULL! Callback dropped.");
            requestReRegister("server callback missing while dispatching event");
        }
    }

    public static void requestReRegister(@NonNull String reason) {
        final var callback = sReRegisterCallback;
        if (callback == null) {
            Log.w("MC_REDIRECT", "[MediaProviderHooksService] Re-register requested before callback is ready: " + reason);
            return;
        }
        Log.i("MC_REDIRECT", "[MediaProviderHooksService] Re-registering hooks callback: " + reason);
        callback.run();
    }

    // ── 应用进程（HooksBridgeProvider）存活探针 ──

    /** 最近一次看护的应用进程存活探针 Binder；null 表示未看护或探针已失效。 */
    private static volatile IBinder sAppBridgeWatchBinder;
    private static volatile IBinder.DeathRecipient sAppBridgeWatchRecipient;
    private static final Object sAppBridgeWatchLock = new Object();

    /**
     * 看护应用进程（{@code me.gm.cleaner}）回传的存活探针 Binder。
     *
     * <p>为什么需要这条反向通道：hooks 桥（HooksBridgeProvider）跑在应用进程里，
     * 用户把应用从最近任务划掉、或 ColorOS 回收该进程时，桥对象整体被替换。
     * 而本进程手上唯一的“桥已换新”证据就是桥进程的 Binder 死亡——
     * 没有它，{@code sMediaProviderService} 会一直指向旧桥上已死的引用，
     * 服务端 {@code isMediaProviderHookConnected()} 恒为 false，最终触发
     * force-stop MediaProvider 这颗核弹（连带杀掉前台应用）。</p>
     *
     * <p>拿到死亡通知后调用 {@link #requestReRegister(String)} 重新走
     * {@code register_hooks_callback}，此时 ContentProvider 会拉起新的应用进程，
     * 新桥即可重新持有本进程的 Binder；整个过程无需重启 MediaProvider。</p>
     *
     * <p>兼容性：探针由新版 Provider 在注册回执里附带；旧版 Provider 不带该字段时
     * 本方法收到 null 直接返回，行为与改动前完全一致。</p>
     */
    public static void watchAppBridge(IBinder appBridge) {
        if (appBridge == null) {
            return;
        }
        final IBinder current = sAppBridgeWatchBinder;
        if (current != null && (current == appBridge || current.equals(appBridge))) {
            // 同一个桥进程，已在看护。
            return;
        }
        synchronized (sAppBridgeWatchLock) {
            final IBinder previous = sAppBridgeWatchBinder;
            final IBinder.DeathRecipient previousRecipient = sAppBridgeWatchRecipient;
            if (previous != null && previousRecipient != null) {
                try {
                    previous.unlinkToDeath(previousRecipient, 0);
                } catch (RuntimeException e) {
                    Log.w("MC_REDIRECT", "[MediaProviderHooksService] unlink app bridge death recipient failed", e);
                }
            }
            final IBinder.DeathRecipient recipient = () -> {
                sAppBridgeWatchBinder = null;
                sAppBridgeWatchRecipient = null;
                Log.w("MC_REDIRECT", "[MediaProviderHooksService] App bridge process died, "
                        + "re-registering hooks callback against the new bridge");
                requestReRegister("app bridge process died");
            };
            try {
                appBridge.linkToDeath(recipient, 0);
            } catch (RemoteException e) {
                // 桥进程在注册与 link 之间死掉了：立刻安排一次重注册，
                // 否则会丢掉这条唯一的“桥已换新”信号。重试由
                // BridgeRegistrationRetryPolicy 的突发预算 + 冷却探针兜底，不会失控。
                Log.w("MC_REDIRECT", "[MediaProviderHooksService] linkToDeath on app bridge failed, "
                        + "scheduling re-registration", e);
                requestReRegister("app bridge died before liveness probe was armed");
                return;
            }
            sAppBridgeWatchBinder = appBridge;
            sAppBridgeWatchRecipient = recipient;
            Log.i("MC_REDIRECT", "[MediaProviderHooksService] App bridge liveness probe armed");
        }
    }

    private void unlinkCleanerServerDeathRecipient(ICleanerServerCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.asBinder().unlinkToDeath(mCleanerServerDeathRecipient, 0);
        } catch (RuntimeException e) {
            Log.w("MC_REDIRECT", "[MediaProviderHooksService] unlinkToDeath failed", e);
        }
    }

    @Override
    public int getVersion() {
        return HookRuntimeConfig.VERSION_CODE;
    }

    @Override
    public void setCleanerServerBinder(ICleanerServerCallback iinterface) {
        Log.i("MC_REDIRECT", "[MediaProviderHooksService] setCleanerServerBinder called, binder=" + (iinterface != null));
        // 先取消已有 DeathRecipient，防止多次 link 导致重复触发
        unlinkCleanerServerDeathRecipient(mCleanerServerBinder);
        mCleanerServerBinder = iinterface;
        HookDataBusBridge.INSTANCE.setCallback(iinterface);
        if (iinterface == null) {
            HookPolicyCache.INSTANCE.tryRefreshNativeMountPoints(false);
            return;
        }
        try {
            iinterface.asBinder().linkToDeath(mCleanerServerDeathRecipient, 0);
        } catch (final RemoteException e) {
            Log.e("MC_REDIRECT", "[MediaProviderHooksService] linkToDeath failed", e);
            mCleanerServerBinder = null;
            HookDataBusBridge.INSTANCE.setCallback(null);
            requestReRegister("server callback linkToDeath failed");
            return;
        } catch (final RuntimeException e) {
            Log.e("MC_REDIRECT", "[MediaProviderHooksService] linkToDeath failed", e);
            mCleanerServerBinder = null;
            HookDataBusBridge.INSTANCE.setCallback(null);
            requestReRegister("server callback linkToDeath failed");
            return;
        }
        // 尝试从 DataBus 刷新 native 挂载点（独立于 Binder 同步）
        HookPolicyCache.INSTANCE.refreshFromDataBus();
    }

    private static final Pattern PATHS_HAVE_USER_ID = Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/.*)?");

    private String getPathAsUser(@NonNull String path, int userId) {
        final var m = PATHS_HAVE_USER_ID.matcher(path);
        if (!m.matches()) {
            return path;
        }
        final var sb = new StringBuilder();
        for (int i = 1; i <= m.groupCount(); i++) {
            final var group = m.group(i);
            if (group == null) {
                continue;
            } else if (TextUtils.isDigitsOnly(group)) {
                sb.append(userId);
            } else {
                sb.append(group);
            }
        }
        return sb.toString();
    }

    public boolean isReadOnly(@NonNull String path, int uid) {
        final var packages = SystemService.getPackagesForUidNoThrow(uid);
        if (packages.isEmpty()) {
            return false;
        }
        final var packageName = packages.get(0);
        final var pathAsUser = getPathAsUser(path, 0);

        // 使用 HookPolicyCache（来自 DataBus 快照）
        // DataBus 是只读策略的唯一分发通道；Binder fallback 路径已移除。
        if (HookPolicyCache.INSTANCE.getReadOnlyGeneration() > 0) {
            return HookPolicyCache.INSTANCE.isReadOnly(packageName, pathAsUser);
        }
        return false;
    }

    @Override
    public void refreshPolicyFromDataBus() {
        HookPolicyCache.INSTANCE.refreshFromDataBus();
    }

    @Override
    public long getNativeMountPointsGeneration() {
        return HookPolicyCache.INSTANCE.getNativeMountPointsGeneration();
    }

    @Override
    public String getNativeHookStatusJson() {
        return HookPolicyCache.INSTANCE.getNativeHookStatusJson();
    }
}
