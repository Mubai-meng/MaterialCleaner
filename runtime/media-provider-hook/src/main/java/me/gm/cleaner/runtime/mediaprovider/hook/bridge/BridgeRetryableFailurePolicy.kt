package me.gm.cleaner.runtime.mediaprovider.hook.bridge

/**
 * 桥接注册失败是否属于「宿主还没就绪」的**可重试判定**（纯策略，无 android 依赖）。
 *
 * 背景：MediaProvider 侧的注册失败只有两类成因，而旧实现两者都记 E 并打完整堆栈：
 *
 * | 成因 | 典型异常 | 是否故障 | 处理 |
 * |---|---|---|---|
 * | App 进程的 `hooks_bridge` Provider 尚未注册 | `IllegalArgumentException: Unknown authority …` | **否**，下一轮 `requestReRegister` 即恢复 | W，不打堆栈 |
 * | 桥就绪后仍失败（ack=false / linkToDeath 抛错 / 重试预算耗尽） | 其余一切 | **是** | E，打堆栈 |
 *
 * 第 43 批实测：一次开机窗口内第一类产出了 **17 行 E**（其中 12 行是堆栈），
 * 同窗口真实故障数为 0 —— 与 5.5.9 处理的 `HOOK.JAVA.BINDER.DEAD` 完全同型。
 *
 * 只做字符串判定、不接收异常实例：调用点在 Java 侧，且需要能被 JVM 单测直接覆盖。
 *
 * **保守优先**：只有明确认得出的「Provider 还没注册」才降级，认不出的一律留给 E，
 * 宁可多一条红色日志，也不让真故障静音。
 */
object BridgeRetryableFailurePolicy {

    /**
     * 「查不到 Provider」的固定话术，全部来自 `ContentResolver.acquireProvider`
     * 与 `ActivityManagerService`：Provider 未注册、包未安装完成、或进程刚起
     * 还没 publish 时只会是这几种。
     */
    private val PROVIDER_NOT_READY_MARKERS = arrayOf(
        "Unknown authority",
        "Failed to find provider",
        "Unable to find provider",
        "Could not find provider",
        "Failed to find provider info",
    )

    /** Provider 查找失败在框架里固定抛 `IllegalArgumentException`；其余类型不认。 */
    private const val ILLEGAL_ARGUMENT = "java.lang.IllegalArgumentException"

    /**
     * @param throwableClassName `e.getClass().getName()`
     * @param message `e.getMessage()`，可为 null
     * @return true 表示「宿主未就绪，可重试」，调用方应记 W 且不输出堆栈
     */
    @JvmStatic
    fun isRetryable(throwableClassName: String?, message: String?): Boolean {
        if (throwableClassName.isNullOrEmpty() || message.isNullOrEmpty()) {
            return false
        }
        if (throwableClassName != ILLEGAL_ARGUMENT) {
            return false
        }
        return PROVIDER_NOT_READY_MARKERS.any { message.contains(it, ignoreCase = true) }
    }
}
