package me.gm.cleaner.runtime.server.process

/**
 * logcat 尾巴假活判定纯函数。
 *
 * 真死（executor 关闭）由 [ActivityManagerLogsObserver.isLogcatShutdown] 直接报告；
 * 本对象覆盖另一种死法：线程活着但停滞（readLine 被 logd wedged 或下游 Binder
 * 调用阻塞），此时 hasAmStart 闩锁早已置位、shutdown 标志永不落，看门狗只能靠
 * 心跳停滞发现。命中即等同真死（serverException=2），复用既有 kill+recover 闭环。
 *
 * @param nowMs 当前 elapsedRealtime
 * @param startAtMs 观察器 onStart 时刻（0 表示未启动，不判）
 * @param lastReadAtMs 最近消费到行时刻（0 表示从未读到行）
 */
internal object ObserverStallPolicy {
    /** 启动宽限：覆盖进程启动期的天然低流量，避免误杀。 */
    const val START_GRACE_MS: Long = 300_000L

    /** 心跳停滞阈值：超过即判假活。 */
    const val STALL_THRESHOLD_MS: Long = 180_000L

    fun isStalled(
        nowMs: Long,
        startAtMs: Long,
        lastReadAtMs: Long,
        graceMs: Long = START_GRACE_MS,
        stallMs: Long = STALL_THRESHOLD_MS,
    ): Boolean {
        if (startAtMs <= 0L) return false
        if (nowMs - startAtMs < graceMs) return false
        if (lastReadAtMs <= 0L) return true
        return nowMs - lastReadAtMs >= stallMs
    }
}
