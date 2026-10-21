package me.gm.cleaner.runtime.mediaprovider.hook.bootstrap;

import me.gm.cleaner.core.common.err.ErrorCodes;
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.BridgeRegistrationRetryGate;
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.BridgeRegistrationRetryPolicy;
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookBridgeRegistrar;
import me.gm.cleaner.runtime.mediaprovider.hook.err.HookErrorJournal;
import me.gm.cleaner.runtime.mediaprovider.hook.fuse.FuseNativePolicyAdapter;
import me.gm.cleaner.runtime.mediaprovider.hook.media.MediaProviderHook;
import me.gm.cleaner.runtime.mediaprovider.hook.media.MediaProviderHooksService;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import me.gm.cleaner.runtime.mediaprovider.hook.xp.XpCompat;

public final class MediaProviderRuntime {
    private static final String TAG = "MC_REDIRECT";

    private static Context sContext;
    private static MediaProviderHooksService sService;
    private static boolean sInlineHookInitialized;

    /**
     * bootstrap 是否已经完整跑过一次。
     *
     * XposedInit 为 bootstrap 装了两条独立触发通路（ContentProvider.attachInfo 与
     * MediaProvider.onCreate）；两条都通时必须只执行一次，否则 MediaProviderHook
     * 会被构造两遍、对同一批方法重复下 hook。
     */
    private static volatile boolean sBootstrapped;

    private static final Object BOOTSTRAP_LOCK = new Object();

    /** 供 XposedInit 的 bootstrap 看门狗判定是否需要告警。 */
    public static boolean isBootstrapped() {
        return sBootstrapped;
    }

    private MediaProviderRuntime() {
    }

    public static void bootstrap(final ClassLoader classLoader, final String packageName,
                                 final Context context, final MediaProviderHooksService service) {
        synchronized (BOOTSTRAP_LOCK) {
            if (sBootstrapped) {
                Log.i(TAG, "[XposedInit] bootstrap already completed, ignoring duplicate trigger");
                return;
            }
            sContext = context;
            sService = service;
            try {
                final var mediaProviderClass = XpCompat.findClass(
                        "com.android.providers.media.MediaProvider", classLoader
                );
                Log.i(TAG, "[XposedInit] MediaProvider class found, registering hooks...");
                NativeHookStatus.INSTANCE.markMediaProviderHookLoaded(packageName);
                initializeInlineHook(packageName);
                setupReRegisterOnDeath();
                MediaProviderHooksService.requestReRegister("initial MediaProvider load");
                service.initPolicyCache();
                new MediaProviderHook(service, classLoader, mediaProviderClass);
                sBootstrapped = true;
                Log.i(TAG, "[XposedInit] MediaProviderHook created successfully");
            } catch (XpCompat.ClassNotFoundError e) {
                Log.e(TAG, "[XposedInit] MediaProvider hook setup FAILED", e);
                // logcat 会被 ColorOS LOG_FLOWCTRL 按进程配额丢弃；同步写一条结构化
                // 失败事实，否则 server 侧只能报一句笼统的 "MediaProvider Hook unavailable"。
                NativeHookStatus.INSTANCE.markHookError(
                        ErrorCodes.INSTANCE.HOOK_JAVA_LOAD_CLASS_NOT_FOUND,
                        "stage=bootstrap_media_provider_hook, package=" + packageName
                                + ", failureType=ClassNotFoundError",
                        "MediaProviderRuntime", HookErrorJournal.SEVERITY_ERROR, 0, 0L);
            } catch (Throwable t) {
                // 除 ClassNotFoundError 外的失败此前会一路冒泡出 handleLoadPackage 的调用方，
                // 现场表现为"什么都没有发生"；这里显式记录，保证失败可见且不留下半初始化状态。
                Log.e(TAG, "[XposedInit] MediaProvider hook setup FAILED", t);
                NativeHookStatus.INSTANCE.markHookError(
                        ErrorCodes.INSTANCE.HOOK_JAVA_LOAD_FAILED,
                        "stage=bootstrap_media_provider_hook, package=" + packageName
                                + ", failureType=" + t.getClass().getSimpleName(),
                        "MediaProviderRuntime", HookErrorJournal.SEVERITY_ERROR, 0, 0L);
            }
        }
    }

    private static boolean registerHooksCallback() {
        if (sContext != null && sService != null) {
            return HookBridgeRegistrar.registerHooksCallback(sContext, sService);
        }
        // 回调 binder 为空 ⇒ 注册被跳过：必须留痕，否则 server 侧只能看到一句
        // 模糊的 "MediaProvider Hook unavailable"，无法区分是没走到 bootstrap
        // 还是走到但拿不到 binder。
        NativeHookStatus.INSTANCE.markHookError(
                ErrorCodes.INSTANCE.HOOK_JAVA_REG_SERVICE_NULL,
                "stage=register_hooks_callback, binder=" + (sService == null ? "null" : "present")
                        + ", context=" + (sContext == null ? "null" : "present")
                        + ", registration=skipped",
                "MediaProviderRuntime", HookErrorJournal.SEVERITY_WARN, 0, 0L);
        return false;
    }

    public static void initializeInlineHook(String packageName) {
        if (sInlineHookInitialized) {
            return;
        }
        Log.i("MC_REDIRECT", "[XposedInit] Loading inline lib for package: " + packageName);
        try {
            final var nativeStatus = FuseNativePolicyAdapter.INSTANCE.initializeInlineHook();
            NativeHookStatus.INSTANCE.markInlineLoadSucceeded(nativeStatus);
            sInlineHookInitialized = isNativeHookReady(nativeStatus);
            Log.i("MC_REDIRECT", "[XposedInit] libinline loaded and xhook initialized");
        } catch (Throwable e) {
            NativeHookStatus.INSTANCE.markInlineLoadFailed(e);
            Log.e("MC_REDIRECT", "[XposedInit] Failed to load inline library, FUSE native hook disabled", e);
        }
    }

    private static boolean isNativeHookReady(String nativeStatus) {
        return nativeStatus != null && nativeStatus.contains("\"containsMount\":true");
    }

    // 在 onMediaProviderLoaded 中设置自动重连回调，失败时有界突发+冷却探针恢复。
    private static void setupReRegisterOnDeath() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final BridgeRegistrationRetryGate retryGate = new BridgeRegistrationRetryGate();
        final int[] attempts = {0};
        final boolean[] cooldownProbe = {false};
        final Runnable retryTask = new Runnable() {
            @Override
            public void run() {
                if (!retryGate.beginScheduledRun()) {
                    return;
                }
                final boolean isCooldownProbe = cooldownProbe[0];
                cooldownProbe[0] = false;
                if (!isCooldownProbe) {
                    // 冷却探针不计入突发预算：它本身就是预算耗尽后的低频试探。
                    attempts[0]++;
                    Log.i("MC_REDIRECT", "[XposedInit] Re-registering hooks callback...");
                }
                // 注册表监视与回调注册是两个独立事实：注册成功但监视失败时，
                // 注册表再次死亡将无触发器，因此不能仅凭注册成功就 markIdle。
                final boolean watched = HookBridgeRegistrar.watchRegistryBinder(sContext);
                final boolean registered = registerHooksCallback();
                if (registered && watched) {
                    Log.i("MC_REDIRECT", "[XposedInit] Re-registration call completed");
                    attempts[0] = 0;
                    retryGate.markIdle();
                    return;
                }
                if (registered) {
                    Log.w("MC_REDIRECT", "[XposedInit] Register OK but registry watch failed, " +
                            "retrying watch (attempt " + attempts[0] + ")");
                    NativeHookStatus.INSTANCE.markBridgeFailed(
                            "registry watch failed after register, attempt " + attempts[0]);
                } else {
                    Log.e("MC_REDIRECT", "[XposedInit] Re-registration failed (attempt " + attempts[0] + ")");
                    NativeHookStatus.INSTANCE.markBridgeFailed(
                            "re-register attempt " + attempts[0] + " failed");
                }
                if (isCooldownProbe || BridgeRegistrationRetryPolicy.isBurstExhausted(attempts[0])) {
                    if (!isCooldownProbe
                            && BridgeRegistrationRetryPolicy.isBurstExhausted(attempts[0])) {
                        // 只在「转入冷却的那一次」留痕，后续冷却探针不重复记录，
                        // 避免把 50 个流水槽位刷满。
                        NativeHookStatus.INSTANCE.markHookError(
                                ErrorCodes.INSTANCE.HOOK_JAVA_REG_EXHAUSTED,
                                "stage=bridge_register_retry, attempts=" + attempts[0]
                                        + ", cooldownMs="
                                        + BridgeRegistrationRetryPolicy.COOLDOWN_MILLIS,
                                "MediaProviderRuntime", HookErrorJournal.SEVERITY_WARN, 0, 0L);
                    }
                    cooldownProbe[0] = true;
                    NativeHookStatus.INSTANCE.markBridgeRetryScheduled(attempts[0]);
                    retryGate.markWaiting();
                    if (!handler.postDelayed(this, BridgeRegistrationRetryPolicy.COOLDOWN_MILLIS)) {
                        retryGate.markIdle();
                    }
                    return;
                }
                NativeHookStatus.INSTANCE.markBridgeRetryScheduled(attempts[0]);
                retryGate.markWaiting();
                if (!handler.postDelayed(
                        this,
                        BridgeRegistrationRetryPolicy.delayMillis(attempts[0]))) {
                    retryGate.markIdle();
                }
            }
        };
        MediaProviderHooksService.sReRegisterCallback = () -> {
            // 合并重注册请求：突发/冷却期间的外部事件不重置预算，
            // 防止 Binder 死亡风暴无限重启退避序列。
            if (!retryGate.requestSchedule()) {
                Log.i("MC_REDIRECT", "[XposedInit] Re-registration request coalesced");
                return;
            }
            if (!handler.post(retryTask)) {
                retryGate.markIdle();
            }
        };
    }
}
