package me.gm.cleaner.runtime.mediaprovider.hook.xp;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * libxposed API 102 的 hook 安装门面（迁移配套）。
 *
 * <p>libxposed 模型的 {@code hook} 能力挂在 {@link XposedInterface} 实例上，而该
 * 实例由框架在模块入口构造时经 {@code attachFramework} 注入。本项目各组件
 * （MediaDatabaseAdapter / FuseJavaGate 等）在构造期安装 hook，不便层层传递
 * 接口实例，因此由模块入口（{@code MediaProviderHookModule.onModuleLoaded}）
 * 一次性 {@link #attach}，此后全进程经 {@link #hook(Method, XposedInterface.Hooker)}
 * 安装 hook。
 *
 * <p>统一使用 {@link XposedInterface.ExceptionMode#PASSTHROUGH}：
 * <ul>
 *   <li>默认 PROTECTIVE 会吞掉「proceed 之前抛出的异常」并继续执行原方法 ——
 *       本项目的 veto 语义（{@code setResult(EPERM)} 只读拒绝 / open 句柄拒绝
 *       抛 FileNotFoundException）必须传播到调用方，PROTECTIVE 会让拒绝静默失效；</li>
 *   <li>PASSTHROUGH 下包装层（AbstractGuardedHook / FuseJavaGate.installHook）
 *       自带 try-catch 兜底意外异常，行为与旧 Xposed「before 抛异常记日志后放行」
 *       等价；{@code chain.proceed()} 抛出的宿主原方法异常在两种模式下都会传播。</li>
 * </ul>
 */
public final class HookInstall {

    private static final String TAG = "MC_REDIRECT";

    private static volatile XposedInterface sFramework;

    private HookInstall() {
    }

    /** 由模块入口调用，每进程至多一次。 */
    public static void attach(final XposedInterface framework) {
        if (framework == null) {
            throw new IllegalArgumentException("framework must not be null");
        }
        if (sFramework == null) {
            sFramework = framework;
        } else {
            Log.w(TAG, "[HookInstall] framework attached more than once, keeping the first");
        }
    }

    public static boolean isAttached() {
        return sFramework != null;
    }

    static XposedInterface framework() {
        final XposedInterface xp = sFramework;
        if (xp == null) {
            throw new IllegalStateException(
                    "XposedInterface not attached; module entry must call attach() first");
        }
        return xp;
    }

    /**
     * 安装 hook（PASSTHROUGH，见类注释）。
     *
     * @return 框架返回的 {@link io.github.libxposed.api.XposedInterface.HookHandle}
     *         （调用方一般无需保存；需要 unhook/替换时持有）
     */
    public static XposedInterface.HookHandle hook(final Method method,
                                                  final XposedInterface.Hooker hooker) {
        return framework()
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(hooker);
    }
}
