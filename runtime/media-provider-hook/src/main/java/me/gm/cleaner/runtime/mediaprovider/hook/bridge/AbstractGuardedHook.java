package me.gm.cleaner.runtime.mediaprovider.hook.bridge;

import me.gm.cleaner.core.common.err.ErrorCodes;
import me.gm.cleaner.runtime.mediaprovider.hook.err.HookErrorJournal;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus;
import me.gm.cleaner.runtime.mediaprovider.hook.xp.HookParam;

import android.os.SystemClock;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 统一异常防护的 Java Hook 装饰器基类（libxposed API 102 拦截器链版）。
 *
 * <p>本类把 {@code FuseJavaGate} 的 {@code installHook} 防护段（经过生产验证的样板）
 * 提炼为 MediaProvider 宿主进程内所有 Java Hook 共享的不变量：
 * <ol>
 *   <li><b>任何 handler 异常不外抛</b> —— 除 {@link VirtualMachineError} 与
 *       {@link ThreadDeath} 外一律吞掉并记录，宿主 MediaProvider 永远不会因
 *       本模块的 Hook 逻辑而崩溃；</li>
 *   <li><b>参数回滚</b> —— handler 抛异常时，{@code guardedArgIndexes}
 *       指定的 args 槽位恢复为进入时的原值，避免半成品参数污染宿主流程；</li>
 *   <li><b>每方法熔断</b> —— 每个 Hook 实例独享一个
 *       {@link HookHandlerCircuitBreaker}，连续失败后冷却，冷却期只放行
 *       一个半开探针，防止异常风暴反复冲击宿主。</li>
 * </ol>
 *
 * <p><b>拦截器链映射：</b>旧 {@code XC_MethodHook.beforeHookedMethod} 的语义由
 * {@link #intercept(XposedInterface.Chain)} 承接：handler 跑在 {@code chain.proceed()}
 * 之前；handler 对 {@link HookParam#args} 的改写经 {@code chain.proceed(args)} 生效；
 * {@link HookParam#setResult} / {@link HookParam#setThrowable} 的 veto 短路原方法。
 * after 阶段沿袭旧版 final 空实现 —— 子类逻辑应全部放在 {@link #handleBefore}。</p>
 *
 * <p><b>同进程统一异常策略：</b>FuseJavaGate 的 FUSE 方法族 Hook 与本基类覆盖的
 * query / insertFile / scan_file Hook 采用同一套「可选增强」哲学 —— Hook 失败只
 * 降级本模块自身功能，绝不阻断、更不崩溃宿主。区别仅在于 FuseJavaGate 直接对接
 * NativeHookStatus 上报 fuseJavaGate 段，而本基类通过 {@link #onCircuitOpened} /
 * {@link #onCircuitRecovered} 回调上报 guardedHooks 段：默认实现直接写入
 * NativeHookStatus，使 Query / Insert / Scan 熔断与 FUSE 门同等可见；
 * 子类可覆写回调以追加行为（如额外日志），但无需为可见性而覆写。</p>
 */
public abstract class AbstractGuardedHook implements XposedInterface.Hooker {
    /**
     * Hook 行为契约：以对象形态描述一段受防护的业务逻辑，
     * 供不便继承的场景以 lambda / 匿名类组合复用。
     */
    public interface Handler {
        void handle(HookParam param) throws Throwable;
    }

    /** 快照与日志统一使用的项目 tag。 */
    private static final String LOG_TAG = "MC_REDIRECT";

    private final String tag;
    private final String methodName;
    private final int[] guardedArgIndexes;

    /** 每 Hook 实例独享的熔断器；包私有以便同包测试观测计数。 */
    final HookHandlerCircuitBreaker circuitBreaker = new HookHandlerCircuitBreaker();

    protected AbstractGuardedHook(final String tag, final String methodName,
                                  final int[] guardedArgIndexes) {
        this.tag = tag;
        this.methodName = methodName;
        this.guardedArgIndexes = guardedArgIndexes.clone();
    }

    /**
     * 子类业务逻辑：抛出的任何异常都由防护层兜底，
     * 不会传播到宿主 MediaProvider。
     */
    protected abstract void handleBefore(HookParam param) throws Throwable;

    /**
     * 结果后置处理（可选）：在宿主原方法返回后、结果交回调用方之前执行。
     *
     * 默认实现原样返回，因此对既有子类零影响。需要**改写返回值**的子类才覆写
     * （例如 query 的结果集过滤 —— 这类逻辑在 [handleBefore] 里做不到，
     * 因为那时宿主还没执行）。
     *
     * 抛出的任何异常都会被 [intercept] 吞掉并原样返回宿主结果：Hook 是可选增强，
     * 后置处理失败只允许丢掉增强，绝不允许改变宿主的可见行为。
     */
    protected Object handleAfter(final HookParam param, final Object result) throws Throwable {
        return result;
    }

    /**
     * 宿主原方法<b>抛出异常</b>时的清理钩子（P0-1）。
     *
     * 为什么必须存在： {@link #handleAfter} 需要宿主返回值做入参，宿主抛异常时压根没有
     * result，后置处理因此<b>一次都不会执行</b>。而 MediaProvider 用的是线程池，线程会被
     * 复用 —— 挂在 ThreadLocal 上的本次调用状态（QueryHooker 的 {@code PENDING_FILTER}）
     * 就会残留到下一次查询：把 A 包的隐藏规则套到 B 包的 Cursor 上，
     * 既泄露了"不可访问"的安全承诺，又会因尾列数目错位取出错行。
     *
     * 默认实现为空：对既有 Hook 零影响，只有确实用了 ThreadLocal / 累计状态的子类才覆写。
     * 本方法抛出的任何异常都会被吞掉并原样重抛宿主异常 —— 清理失败绝不允许扭转宿主语义。
     */
    protected void onHostFailed(final HookParam param, final Throwable cause) throws Throwable {
    }

    /**
     * 熔断打开（进入冷却）时触发，openUntilUptimeMillis 为冷却截止时刻。
     * 默认实现上报 NativeHookStatus.guardedHooks 段。
     */
    protected void onCircuitOpened(long openUntilUptimeMillis, Throwable cause) {
        NativeHookStatus.INSTANCE.markGuardedCircuitOpened(
                tag, methodName, failureTypeOf(cause));
    }

    /**
     * 半开探针成功、熔断恢复闭合时触发。
     * 默认实现上报 NativeHookStatus.guardedHooks 段。
     */
    protected void onCircuitRecovered() {
        NativeHookStatus.INSTANCE.markGuardedCircuitRecovered(tag);
    }

    /**
     * 时钟钩子：生产路径即 SystemClock.elapsedRealtime()；
     * 测试覆写以推进熔断冷却与半开探针时序。
     */
    protected long uptimeMillis() {
        return SystemClock.elapsedRealtime();
    }

    private String failureTypeOf(final Throwable cause) {
        return cause.getClass().getSimpleName();
    }

    /**
     * 精确移植 FuseJavaGate.installHook 防护段语义：
     * 熔断准入 → 参数快照 → try/catch 兜底 → 异常分级处理。
     */
    @Override
    public final Object intercept(final XposedInterface.Chain chain) throws Throwable {
        final long acquiredAt = uptimeMillis();
        final HookHandlerCircuitBreaker.Permit permit =
                circuitBreaker.tryAcquire(acquiredAt);
        if (permit == null) {
            // 熔断冷却期内：直接放行宿主原方法，handler 不执行。
            return chain.proceed();
        }
        final Object[] args = chain.getArgs().toArray();
        final HookParam param = new HookParam(chain.getThisObject(), args);
        // 快照受保护槽位原值，handler 失败时回滚，保证宿主拿到未污染参数。
        final Object[] originalArgs = new Object[guardedArgIndexes.length];
        for (int i = 0; i < guardedArgIndexes.length; i++) {
            originalArgs[i] = param.args[guardedArgIndexes[i]];
        }
        try {
            handleBefore(param);
            if (circuitBreaker.onSuccess(permit) ==
                    HookHandlerCircuitBreaker.SuccessOutcome.RECOVERED) {
                onCircuitRecovered();
                NativeHookStatus.INSTANCE.markHookError(
                        ErrorCodes.INSTANCE.HOOK_JAVA_GUARD_CIRCUIT_RECOVERED,
                        "hook=" + tag + ", method=" + methodName,
                        tag, HookErrorJournal.SEVERITY_WARN, 0, 0L);
                Log.i(LOG_TAG, "[" + tag + "] handler circuit recovered");
            }
        } catch (final Throwable t) {
            for (int i = 0; i < guardedArgIndexes.length; i++) {
                param.args[guardedArgIndexes[i]] = originalArgs[i];
            }
            if (t instanceof VirtualMachineError || t instanceof ThreadDeath) {
                circuitBreaker.onAbort(permit, uptimeMillis());
                throw (Error) t;
            }
            final long failedAt = uptimeMillis();
            final var outcome = circuitBreaker.onFailure(permit, failedAt);
            final String message = "[" + tag + "] handler failed, failures="
                    + circuitBreaker.consecutiveFailures();
            final String detail = message + ", type=" + failureTypeOf(t);
            if (outcome == HookHandlerCircuitBreaker.FailureOutcome.OPENED) {
                onCircuitOpened(failedAt + HookHandlerCircuitBreaker.COOLDOWN_MILLIS, t);
                NativeHookStatus.INSTANCE.markHookError(
                        ErrorCodes.INSTANCE.HOOK_JAVA_GUARD_CIRCUIT_OPENED,
                        "hook=" + tag + ", method=" + methodName
                                + ", failureType=" + failureTypeOf(t),
                        tag, HookErrorJournal.SEVERITY_ERROR, 0, 0L);
                Log.e(LOG_TAG, message + ", circuit cooldown="
                        + circuitBreaker.remainingCooldownMillis(failedAt) + "ms", t);
                // logcat 会被 ColorOS LOG_FLOWCTRL 按进程配额丢弃，
                // 这里再写一条结构化流水，落进 native_hook_status.json 的 hookErrors 段。
                NativeHookStatus.INSTANCE.markHookError(
                        ErrorCodes.INSTANCE.HOOK_JAVA_GUARD_HANDLER_FAILED,
                        detail + ", circuitOpened=true", tag,
                        HookErrorJournal.SEVERITY_ERROR, 0, 0L);
            } else if (outcome == HookHandlerCircuitBreaker.FailureOutcome.COUNTED &&
                    circuitBreaker.shouldLogCountedFailure(failedAt)) {
                Log.w(LOG_TAG, message, t);
                NativeHookStatus.INSTANCE.markHookError(
                        ErrorCodes.INSTANCE.HOOK_JAVA_GUARD_HANDLER_FAILED,
                        detail, tag, HookErrorJournal.SEVERITY_WARN, 0, 0L);
            }
            // 永不阻断宿主操作 —— Hook 是可选增强，不是系统依赖
            // （对齐 FuseJavaGate 同语义注释）。
        }
        // veto：短路返回（EPERM）或短路抛出（open 句柄拒绝），不执行原方法。
        if (param.isVetoResult()) {
            return param.getResult();
        }
        if (param.isVetoThrowable()) {
            throw param.getThrowable();
        }
        final Object result;
        try {
            result = chain.proceed(param.args);
        } catch (final Throwable hostFailure) {
            // P0-1：宿主方法抛出 ⇒ 没有 result ⇒ handleAfter 不执行 ⇒ 线程本地状态无人清理。
            // 这是唯一能清理的位置；异常本身照原语义向上抛给宿主（Hook 不改变宿主行为）。
            try {
                onHostFailed(param, hostFailure);
            } catch (final VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (final Throwable t) {
                // 清理失败只留痕，不得掩盖/替换宿主原异常。
                Log.w(LOG_TAG, "[" + tag + "] onHostFailed cleanup threw", t);
            }
            throw hostFailure;
        }
        try {
            return handleAfter(param, result);
        } catch (final VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (final Throwable t) {
            Log.w(LOG_TAG, "[" + tag + "] handleAfter failed, returning host result", t);
            return result;
        }
    }
}
