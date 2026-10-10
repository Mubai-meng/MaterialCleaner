package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import me.gm.cleaner.runtime.mediaprovider.hook.bootstrap.MediaProviderRuntime;
import me.gm.cleaner.runtime.mediaprovider.hook.media.MediaProviderHooksService;

/**
 * MediaProvider 进程侧的 Xposed 入口。
 *
 * ## 为什么 hook 必须下在子类上（Android 17 实测结论）
 *
 * 直觉做法是 hook `android.content.ContentProvider.attachInfo(Context, ProviderInfo)`
 * —— 它确实是框架链路里被执行到的方法，但**钩不住**：
 *
 * ```
 * ActivityThread.installProvider:
 *   localProvider = packageInfo.getAppFactory().instantiateProvider(cl, info.name); // 反射创建
 *   localProvider.attachInfo(c, info);                                              // invoke-virtual
 *
 * MediaProvider.attachInfo(Context, ProviderInfo):            // 子类覆写，dex 实测存在
 *   Log.v("MediaProvider", "Attached " + info.authority + ...);
 *   mUriMatcher = new LocalUriMatcher(info.authority);
 *   super.attachInfo(context, info);                          // ← invoke-super
 *
 * ContentProvider.attachInfo(Context, ProviderInfo, boolean):  // boot image 内
 *   ...
 *   ContentProvider.this.onCreate();                           // ← 此处才调 onCreate
 * ```
 *
 * `MediaProvider.attachInfo` 里的 `invoke-super` 在 AOT 编译时被解析为对 boot image 中
 * `ContentProvider.attachInfo` **已编译代码的直接调用**，ArtMethod 入口点替换拦不到它，
 * 于是 frame 钩子在父类上装了却永不触发。现场表现极具误导性：
 * `V MediaProvider: Attached media from com.android.providers.media.module` 明明打出来了，
 * 但模块侧一条日志都没有 → bootstrap 不执行 → Hook Binder 永不注册 →
 * server 的 MediaProviderRecoveryStrategy 每 60s 强杀一次 MediaProvider。
 *
 * 反过来，框架对 MediaProvider 实例的调用是不可去虚拟化的（实例来自反射），
 * 所以钩子下在**子类** `attachInfo` / `onCreate` 上才会真正生效。
 *
 * 保留父类 hook 作为冗余通路：厂商 ROM 若不自覆写 `attachInfo`，它仍然有用。
 */
public class XposedInit implements IXposedHookLoadPackage {
    private static final String TAG = "MC_REDIRECT";
    private static final String MEDIA_PROVIDER_CLASS = "com.android.providers.media.MediaProvider";

    /**
     * bootstrap 未触发时的观测窗口。
     *
     * 存在理由：Android 17 现场实测中 {@code MediaProviderRuntime.bootstrap()} 一次都没跑到
     * （logcat 里既没有 "Detected MediaProvider loading"，也没有 "MediaProviderHook
     * created successfully"），于是 Hook Binder 永不注册，server 侧
     * MediaProviderRecoveryStrategy 每 60s 强杀一次 MediaProvider，形成死循环。
     * 这种"静默不注册"必须能自曝，否则只能靠反复抓 log 猜。
     */
    private static final long BOOTSTRAP_WATCHDOG_DELAY_MS = 15_000L;

    private final MediaProviderHooksService mediaProviderHooksService = new MediaProviderHooksService();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        // 只处理 MediaProvider 进程，忽略其他所有系统应用
        switch (lpparam.packageName) {
            case "com.android.providers.media":
            case "com.android.providers.media.module":
            case "com.google.android.providers.media.module":
                break;
            default:
                return;
        }
        Log.i(TAG, "[XposedInit] Installing MediaProvider attach hook for package: " + lpparam.packageName);

        // 只做"尽早 System.loadLibrary(\"inline\")"的预热。此刻 MediaProvider.apk 内嵌的
        // libfuse_jni.so 尚未被 dlopen（实测 bpf_hook init 在 11:46:59.252，
        // libfuse_jni.so 在 11:46:59.293 才加载），因此这一次大概率拿到
        // coreAvailable=false；决定性的那次初始化在 bootstrap() 内。
        MediaProviderRuntime.initializeInlineHook(lpparam.packageName);

        // 主触发：子类 attachInfo / onCreate（invoke-virtual，可拦截）
        hookMediaProviderClass(lpparam);
        // 冗余触发：父类 attachInfo（invoke-super 场景下拦不到，但覆盖面更广）
        hookBaseProviderAttachInfo(lpparam);

        armBootstrapWatchdog();
    }

    /**
     * 主触发。`MediaProvider.attachInfo` 是框架链路里第一个不可去虚拟化的调用点，
     * `onCreate` 紧随其后（由 `ContentProvider.attachInfo` 内部调用），两条都装上。
     */
    private void hookMediaProviderClass(LoadPackageParam lpparam) {
        final Class<?> mediaProviderClass = resolveMediaProviderClass(lpparam);
        if (mediaProviderClass == null) {
            Log.e(TAG, "[XposedInit] Cannot resolve " + MEDIA_PROVIDER_CLASS +
                    " in " + lpparam.packageName + "; only the base-class hook is active");
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(mediaProviderClass, "attachInfo",
                    Context.class, ProviderInfo.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            final Context context = (Context) param.args[0];
                            final ProviderInfo providerInfo = (ProviderInfo) param.args[1];
                            final String authority =
                                    providerInfo == null ? null : providerInfo.authority;
                            Log.i(TAG, "[XposedInit] Detected MediaProvider loading, " +
                                    "package=" + lpparam.packageName + ", authority=" + authority);
                            MediaProviderRuntime.bootstrap(
                                    lpparam, context, mediaProviderHooksService);
                        }
                    });
            Log.i(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".attachInfo hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".attachInfo hook not installed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(mediaProviderClass, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof ContentProvider)) {
                                return;
                            }
                            final Context context =
                                    ((ContentProvider) param.thisObject).getContext();
                            Log.i(TAG, "[XposedInit] MediaProvider.onCreate reached, " +
                                    "bootstrapping (fallback trigger)");
                            MediaProviderRuntime.bootstrap(
                                    lpparam, context, mediaProviderHooksService);
                        }
                    });
            Log.i(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".onCreate fallback hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".onCreate fallback hook not installed", t);
        }
    }

    /**
     * 解析 MediaProvider 类。
     *
     * 必须**同步**在 handleLoadPackage 内完成：handleLoadPackage 由 LSPosed 在
     * LoadedApk.createOrUpdateClassLoaderLocked 时回调，而 provider 的 attachInfo 就发生在
     * 同一次 handleBindApplication 消息里（实测 handleLoadPackage 11:46:59.246，
     * attachInfo 11:46:59.300，相隔 54ms、同一条消息）。因此任何 postDelayed 兜底都太晚，
     * 只能在此刻把类拿到手；这里依次尝试三种等价取法以提高成功率。
     */
    private Class<?> resolveMediaProviderClass(LoadPackageParam lpparam) {
        try {
            return XposedHelpers.findClass(MEDIA_PROVIDER_CLASS, lpparam.classLoader);
        } catch (Throwable ignored) {
            // 落到下一种等价取法。
        }
        try {
            return Class.forName(MEDIA_PROVIDER_CLASS, false, lpparam.classLoader);
        } catch (Throwable ignored) {
            // 落到下一种等价取法。
        }
        try {
            return lpparam.classLoader.loadClass(MEDIA_PROVIDER_CLASS);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 冗余触发：`ContentProvider.attachInfo(Context, ProviderInfo)`。
     *
     * 对每个被 attach 的 provider 打印 class/authority，使"钩子到底有没有跑到"
     * 在 logcat 里直接可见。注意：本项目实测在 ColorOS 17 上此钩子不触发
     * （原因见类注释），因此**不能**把 bootstrap 只挂在这一条上。
     */
    private void hookBaseProviderAttachInfo(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ContentProvider.class, "attachInfo",
                    Context.class, ProviderInfo.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            final Context context = (Context) param.args[0];
                            final ProviderInfo providerInfo = (ProviderInfo) param.args[1];
                            final String authority =
                                    providerInfo == null ? null : providerInfo.authority;
                            final Object self = param.thisObject;
                            Log.d(TAG, "[XposedInit] base attachInfo: class=" +
                                    (self == null ? "?" : self.getClass().getName()) +
                                    ", authority=" + authority);

                            if (MediaStore.AUTHORITY.equals(authority)) {
                                Log.i(TAG, "[XposedInit] Detected MediaProvider loading via base " +
                                        "hook, package=" + lpparam.packageName);
                                MediaProviderRuntime.bootstrap(
                                        lpparam, context, mediaProviderHooksService);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "[XposedInit] ContentProvider.attachInfo base hook not installed", t);
        }
    }

    private void armBootstrapWatchdog() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (MediaProviderRuntime.isBootstrapped()) {
                return;
            }
            Log.e(TAG, "[XposedInit] WATCHDOG: MediaProvider bootstrap never ran within " +
                    BOOTSTRAP_WATCHDOG_DELAY_MS + "ms — every trigger " +
                    "(MediaProvider.attachInfo, MediaProvider.onCreate, " +
                    "ContentProvider.attachInfo) was missed. The Hook Binder will never be " +
                    "registered, so MEDIA_PROVIDER_JAVA_HOOK stays UNAVAILABLE and the server " +
                    "keeps trying to recover.");
        }, BOOTSTRAP_WATCHDOG_DELAY_MS);
    }
}
