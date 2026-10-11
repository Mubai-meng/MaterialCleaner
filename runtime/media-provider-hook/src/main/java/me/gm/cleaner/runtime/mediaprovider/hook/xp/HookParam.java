package me.gm.cleaner.runtime.mediaprovider.hook.xp;

/**
 * legacy {@code XC_MethodHook.MethodHookParam} 的等价物（libxposed API 102 迁移配套）。
 *
 * <p>libxposed API 102 采用拦截器链模型（{@code Hooker.intercept(Chain)}），
 * 没有 before/after 回调与可变的 {@code MethodHookParam}。本类由包装层在每次
 * 拦截时构造：
 * <ul>
 *   <li>{@link #args} —— {@code chain.getArgs()} 的可变数组副本，业务代码沿用
 *       {@code param.args[i]} 读写（写改由包装层经 {@code chain.proceed(args)} 生效）；</li>
 *   <li>{@link #thisObject} —— 本次调用的 this 指针；</li>
 *   <li>{@link #setResult(Object)} / {@link #setThrowable(Throwable)} —— veto 语义：
 *       包装层据此短路（不 proceed / 抛出），对应旧 before 阶段短路原方法的行为。</li>
 * </ul>
 *
 * <p>veto 优先级：后调用者胜（与旧 XC_MethodHook 一致）；包装层先判
 * {@link #isVetoResult()} 再判 {@link #isVetoThrowable()}。
 *
 * <p>纯数据类，无 Android 依赖，可单测。
 */
public final class HookParam {

    /** 本次调用的 this 指针（静态方法为 null）。 */
    public final Object thisObject;

    /** 可变参数副本（对它的写经包装层 chain.proceed(args) 生效）。 */
    public final Object[] args;

    private boolean vetoResult;
    private Object result;
    private boolean vetoThrowable;
    private Throwable throwable;

    public HookParam(final Object thisObject, final Object[] args) {
        this.thisObject = thisObject;
        this.args = args;
    }

    /** 短路返回指定结果（取消 vetoThrowable）。 */
    public void setResult(final Object result) {
        this.vetoResult = true;
        this.vetoThrowable = false;
        this.result = result;
    }

    /** 短路抛出指定异常（取消 vetoResult）。 */
    public void setThrowable(final Throwable throwable) {
        this.vetoThrowable = true;
        this.vetoResult = false;
        this.throwable = throwable;
    }

    public boolean isVetoResult() {
        return vetoResult;
    }

    public Object getResult() {
        return result;
    }

    public boolean isVetoThrowable() {
        return vetoThrowable;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public boolean hasVeto() {
        return vetoResult || vetoThrowable;
    }
}
