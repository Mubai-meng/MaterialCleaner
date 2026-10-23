package me.gm.cleaner.runtime.server.hookbridge

/**
 * 启动期桥接重连的**短退避阶梯**（纯策略，无 android 依赖，便于 JVM 单测）。
 *
 * ## 问题（第 43 批实测，2026-10-23 17:03 开机）
 * ```
 * 17:03:35.853  server 9409  onStart: hooks binder not available yet, will retry
 * 17:03:35.912  app    9031  BinderProvider.call: method=sendBinder      <- 只差 59 ms
 *       ...... 中间 10.16 秒，没有任何主动重试 ......
 * 17:03:46.016  server 9409  [BinderSender] App process active, triggering server reconnection
 * 17:03:46.018  server 9409  tryReconnect: connected
 * ```
 * `onStart` 失败一次后，下一次尝试只能等 `whileAlive`（真正用到网关时）或
 * `BinderSender.onActive`（应用回到前台）。**开机时两者都不在窗口内发生**，
 * 于是 MediaProvider 侧 7.55 秒拿不到策略（`initFromDataBus` 四份快照全 null，
 * `HookPolicyCache.isStale()` 又把「读不到」当成「已消费」，轮询全程空转）。
 *
 * ## 覆盖范围
 * 只覆盖「`onStart` 刚失败」这一个窗口：有限次数 + 固定阶梯，总窗口 ~11.7 s，
 * 成功即停。**稳态一次都不多跑**，也不影响 `HookBridgeReconnectThrottlePolicy`
 * 对懒调用路径的节流（本阶梯走 `establishConnection`，不经过 `tryReconnect` 的节流）。
 *
 * 阶梯形状的依据：桥宿主是 app 进程，开机时它通常只差几十到几百毫秒就绪
 * （本批实测 59 ms）。因此前三次必须足够快，之后退到 2 s 上限以免在
 * 「app 被冻结 / 正在被回收」时做无意义的跨进程往返。
 */
object HookBridgeStartupRetryPolicy {

    /** 8 次尝试：200 + 500 + 1000 + 2000×5 = **11 700 ms**。 */
    const val MAX_ATTEMPTS: Int = 8

    private const val FIRST_DELAY_MILLIS = 200L
    private const val SECOND_DELAY_MILLIS = 500L
    private const val THIRD_DELAY_MILLIS = 1_000L
    private const val CAP_DELAY_MILLIS = 2_000L

    /**
     * @param attempt 第几次尝试，从 1 开始；≤0 也按首次处理（不抛异常）
     */
    @JvmStatic
    fun delayMillis(attempt: Int): Long = when {
        attempt <= 1 -> FIRST_DELAY_MILLIS
        attempt == 2 -> SECOND_DELAY_MILLIS
        attempt == 3 -> THIRD_DELAY_MILLIS
        else -> CAP_DELAY_MILLIS
    }

    /** 第 [nextAttempt] 次是否还应继续（含 1 与 [MAX_ATTEMPTS]）。 */
    @JvmStatic
    fun shouldContinue(nextAttempt: Int): Boolean = nextAttempt in 1..MAX_ATTEMPTS

    /** 整条阶梯的名义总时长，用于日志与文档自证。 */
    @JvmStatic
    fun totalWindowMillis(): Long = (1..MAX_ATTEMPTS).sumOf { delayMillis(it) }
}
