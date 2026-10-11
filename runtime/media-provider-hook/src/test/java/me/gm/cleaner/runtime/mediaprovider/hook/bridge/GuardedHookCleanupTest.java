package me.gm.cleaner.runtime.mediaprovider.hook.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import me.gm.cleaner.runtime.mediaprovider.hook.xp.HookParam;

/**
 * [AbstractGuardedHook] 宿主失败清理钩子的单测（P0-1）。
 *
 * <p>为什么必须测：MediaProvider 用的是线程池，宿主 `query` 抛异常时根本没有返回值，
 * {@code handleAfter} 因此一次都不会执行，挂在 ThreadLocal 上的本次调用状态
 * （QueryHooker 的 {@code PENDING_FILTER}）就残留了下来 —— 线程被复用后，
 * 下一次查询（很可能是另一个应用）会读到上一次的 {包名, 隐藏路径, 是否注入尾列}。
 * 后果有两个：①本该隐藏的行泄露给别的应用，破坏了"不可访问"的安全承诺；
 * ②尾列数目错位导致取列错位。产物级探针只能证明"清理代码存在"，证明不了
 * "宿主抛异常这条路径真的执行了它"，所以必须把拦截链跑起来。
 *
 * <p>为什么用 Java 写：被测类与 {@code HookParam} 都是 Java，用 Java 写可以避免
 * Kotlin 在覆写带 {@code throws Throwable} 的 Java 方法时的平台类型摩擦。
 */
public class GuardedHookCleanupTest {

    /** 可控的拦截器链：宿主结果 / 宿主异常二选一，并记录 proceed 次数。 */
    private static final class StubChain implements XposedInterface.Chain {
        private final List<Object> args;
        private final Throwable hostFailure;
        private final Object hostResult;
        int proceedCount;

        StubChain(Object result, Throwable hostFailure) {
            this.args = java.util.Collections.singletonList("arg0");
            this.hostResult = result;
            this.hostFailure = hostFailure;
        }

        @Override
        public Executable getExecutable() {
            return null;
        }

        @Override
        public Object getThisObject() {
            return null;
        }

        @Override
        public List<Object> getArgs() {
            return args;
        }

        @Override
        public Object getArg(int index) {
            return args.get(index);
        }

        @Override
        public Object proceed() throws Throwable {
            proceedCount++;
            return deliver();
        }

        @Override
        public Object proceed(Object[] proceedArgs) throws Throwable {
            proceedCount++;
            return deliver();
        }

        @Override
        public Object proceedWith(Object capturedThis) throws Throwable {
            return proceed();
        }

        @Override
        public Object proceedWith(Object capturedThis, Object[] proceedArgs) throws Throwable {
            return proceed(proceedArgs);
        }

        private Object deliver() throws Throwable {
            if (hostFailure != null) {
                throw hostFailure;
            }
            return hostResult;
        }
    }

    /** 记录清理与后置处理调用次数的 Hook；时钟钩子重写以避开单测里的 SystemClock。 */
    private static final class RecordingHook extends AbstractGuardedHook {
        int hostFailedCount;
        int afterCount;
        Throwable lastCause;

        RecordingHook() {
            super("CleanupTest", "query", new int[0]);
        }

        @Override
        protected long uptimeMillis() {
            return 0L;
        }

        @Override
        protected void handleBefore(HookParam param) throws Throwable {
        }

        @Override
        protected Object handleAfter(HookParam param, Object result) throws Throwable {
            afterCount++;
            return result;
        }

        @Override
        protected void onHostFailed(HookParam param, Throwable cause) throws Throwable {
            hostFailedCount++;
            lastCause = cause;
        }
    }

    /** 不覆写清理钩子的 Hook：验证默认空实现对既有 Hook 是零影响。 */
    private static final class PlainHook extends AbstractGuardedHook {
        PlainHook() {
            super("PlainTest", "query", new int[0]);
        }

        @Override
        protected long uptimeMillis() {
            return 0L;
        }

        @Override
        protected void handleBefore(HookParam param) throws Throwable {
        }
    }

    @Test
    public void hostThrows_cleansUpAndRethrowsOriginal() throws Throwable {
        final RuntimeException failure = new RuntimeException("sqlite busy");
        final RecordingHook hook = new RecordingHook();
        final StubChain chain = new StubChain(null, failure);

        Throwable caught = null;
        try {
            hook.intercept(chain);
        } catch (final Throwable t) {
            caught = t;
        }

        // ①宿主异常必须原样传播 —— Hook 永远不改变宿主的可见行为。
        assertSame(failure, caught);
        // ②没有返回值 ⇒ 后置处理不该执行。
        assertEquals(0, hook.afterCount);
        // ③清理必须发生，且拿来的是宿主原异常。
        assertEquals(1, hook.hostFailedCount);
        assertSame(failure, hook.lastCause);
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void hostSucceeds_cleanupNotInvoked() throws Throwable {
        final RecordingHook hook = new RecordingHook();
        final StubChain chain = new StubChain("host-result", null);

        assertEquals("host-result", hook.intercept(chain));

        assertEquals(0, hook.hostFailedCount);
        assertNull(hook.lastCause);
        assertEquals(1, hook.afterCount);
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void defaultHook_doesNotDependOnCleanupOverride() throws Throwable {
        // 默认空实现：宿主异常照样原样抛回，不多做不少做。
        final IllegalStateException failure = new IllegalStateException("boom");
        final PlainHook hook = new PlainHook();

        Throwable caught = null;
        try {
            hook.intercept(new StubChain(null, failure));
        } catch (final Throwable t) {
            caught = t;
        }
        assertSame(failure, caught);
    }
}
