package me.gm.cleaner.runtime.mediaprovider.hook.xp;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * legacy {@code XposedHelpers} 的纯反射等价物（libxposed API 102 迁移配套）。
 *
 * <p>libxposed API 只提供 hook 能力（{@code XposedInterface.hook/deoptimize/Invoker}），
 * 不附带任何反射辅助函数；本项目 MediaProvider Hook 大量依赖按名调用宿主私有方法
 * （{@code resolveVolumeName} / {@code sanitizeValues} / {@code getCachedCallingIdentityForFuse}
 * 等），本类按 XposedHelpers 的既有语义逐一复刻，调用方只需把
 * {@code XposedHelpers.} 前缀替换为 {@code XpCompat.}：
 * <ul>
 *   <li>{@link #findClass}：失败抛 {@link ClassNotFoundError}（Error，与旧同名类一致）；</li>
 *   <li>{@link #findMethodExact}：沿父类链精确匹配，失败抛 {@link NoSuchMethodError}
 *       （旧 XposedHelpers 同为 Error，调用方 catch 语句无需改动）；</li>
 *   <li>{@link #callMethod} / {@link #callStaticMethod}：宿主异常统一包装为
 *       {@link InvocationTargetError}（与旧同名类一致：继承 {@link Error}、非受检，
 *       携带原始异常供调用方解包）；</li>
 *   <li>{@link #getObjectField}：沿父类链取字段，失败抛 {@link NoSuchFieldError}。</li>
 * </ul>
 *
 * <p>方法解析兼容基本类型装箱（{@code Integer -> int} 形参），先精确匹配再回退
 * assignable 匹配 —— 与 XposedHelpers.findMethodBestMatch 行为对齐。
 *
 * <p>注意：invoke 走 {@link Method#invoke}，与其他 hook 一样会穿过已安装的
 * hook 链（与旧 XposedHelpers 行为一致；需要跳过 hook 时由调用方自行缓存
 * {@code Method} 或使用框架 {@code Invoker}）。
 */
public final class XpCompat {

    /** 与 legacy {@code XposedHelpers.ClassNotFoundError} 同名同语义（Error）。 */
    public static class ClassNotFoundError extends Error {
        public ClassNotFoundError(String detailMessage, Throwable cause) {
            super(detailMessage, cause);
        }
    }

    /**
     * 与 legacy {@code XposedHelpers.InvocationTargetError} 同类同语义 —— 继承
     * {@link Error}（javap 实证：api-82.jar 中该类 extends java.lang.Error），
     * 属**非受检**异常，因此既有调用点（如 FuseJavaGate.getCallingPackageName）
     * 无需追加 throws 声明。
     */
    public static class InvocationTargetError extends Error {
        public InvocationTargetError(Throwable cause) {
            super(cause);
        }

        public InvocationTargetError(String detailMessage, Throwable cause) {
            super(detailMessage, cause);
        }
    }

    private XpCompat() {
    }

    // ── 类查找 ──────────────────────────────────────────────

    public static Class<?> findClass(final String className, final ClassLoader classLoader)
            throws ClassNotFoundError {
        try {
            return Class.forName(className, false, classLoader);
        } catch (final ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    // ── 方法查找 ────────────────────────────────────────────

    /**
     * 沿父类链按「名字 + 精确参数类型」查找方法；找不到抛 {@link NoSuchMethodError}。
     *
     * @param parameterTypes 形参类型，元素为 {@link Class} 或类名字符串
     */
    public static Method findMethodExact(final Class<?> clazz, final String methodName,
                                         final Object... parameterTypes) {
        final Class<?>[] resolved = resolveParameterTypes(parameterTypes);
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                final Method m = c.getDeclaredMethod(methodName, resolved);
                m.setAccessible(true);
                return m;
            } catch (final NoSuchMethodException ignored) {
                // 沿父类链继续。
            }
        }
        throw new NoSuchMethodError(methodName + " " + java.util.Arrays.toString(resolved)
                + " in " + clazz.getName());
    }

    private static Class<?>[] resolveParameterTypes(final Object[] parameterTypes) {
        if (parameterTypes == null) {
            return new Class<?>[0];
        }
        final Class<?>[] resolved = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            final Object t = parameterTypes[i];
            if (t instanceof Class) {
                resolved[i] = (Class<?>) t;
            } else if (t instanceof String) {
                // 类名字符串形式：仅支持 boot classpath 可见的类（本项目实际只传 Class）。
                try {
                    resolved[i] = Class.forName((String) t);
                } catch (final ClassNotFoundException e) {
                    throw new ClassNotFoundError((String) t, e);
                }
            } else if (t == null) {
                throw new IllegalArgumentException("parameterTypes[" + i + "] is null");
            } else {
                throw new IllegalArgumentException("parameterTypes[" + i
                        + "] must be a Class or String, got " + t.getClass().getName());
            }
        }
        return resolved;
    }

    // ── 方法调用 ────────────────────────────────────────────

    public static Object callMethod(final Object obj, final String methodName,
                                    final Object... args) throws InvocationTargetError {
        try {
            final Method m = findMethodBestMatch(obj.getClass(), methodName, args);
            return invoke(m, obj, args);
        } catch (final InvocationTargetError e) {
            throw e;
        } catch (final IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object callStaticMethod(final Class<?> clazz, final String methodName,
                                          final Object... args) throws InvocationTargetError {
        try {
            final Method m = findMethodBestMatch(clazz, methodName, args);
            if (!Modifier.isStatic(m.getModifiers())) {
                throw new NoSuchMethodError(methodName + " is not static in " + clazz.getName());
            }
            return invoke(m, null, args);
        } catch (final InvocationTargetError e) {
            throw e;
        } catch (final IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    private static Object invoke(final Method m, final Object target, final Object[] args)
            throws InvocationTargetError, IllegalAccessException {
        try {
            return m.invoke(target, args);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            throw new InvocationTargetError(cause != null ? cause : e);
        }
    }

    /**
     * 按名 + 实参解析方法：精确匹配（含装箱归一）优先，assignable 回退。
     * 找不到抛 {@link NoSuchMethodError}。
     */
    private static Method findMethodBestMatch(final Class<?> clazz, final String methodName,
                                              final Object... args) {
        Method bestMatch = null;
        // 遍历整条父类链（不按 clazz 自身的 static 修饰符截断 —— 那判断的是
        // 「类是否静态嵌套类」，与「调用静态方法」无关；单测已覆盖该回归）。
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (final Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)
                        || m.getParameterCount() != (args == null ? 0 : args.length)) {
                    continue;
                }
                final Class<?>[] types = m.getParameterTypes();
                boolean exact = true;
                boolean assignable = true;
                for (int i = 0; i < types.length; i++) {
                    final Class<?> actual = args[i] == null ? null
                            : box(args[i].getClass());
                    final Class<?> formal = box(types[i]);
                    if (actual == null) {
                        if (formal.isPrimitive()) {
                            exact = false;
                            assignable = false;
                        }
                        continue;
                    }
                    if (formal != actual) {
                        exact = false;
                        if (!formal.isAssignableFrom(actual)) {
                            assignable = false;
                            break;
                        }
                    }
                }
                if (exact) {
                    m.setAccessible(true);
                    return m;
                }
                if (assignable && bestMatch == null) {
                    bestMatch = m;
                }
            }
        }
        if (bestMatch != null) {
            bestMatch.setAccessible(true);
            return bestMatch;
        }
        throw new NoSuchMethodError(methodName
                + " " + java.util.Arrays.toString(args == null ? new Object[0] : args)
                + " in " + clazz.getName());
    }

    // ── 字段访问 ────────────────────────────────────────────

    public static Object getObjectField(final Object obj, final String fieldName)
            throws NoSuchFieldError {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (final NoSuchFieldException ignored) {
                // 沿父类链继续。
            } catch (final IllegalAccessException e) {
                throw new IllegalAccessError(e.getMessage());
            }
        }
        throw new NoSuchFieldError(fieldName + " in " + obj.getClass().getName());
    }

    private static Class<?> box(final Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == boolean.class) return Boolean.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == void.class) return Void.class;
        return c;
    }
}
