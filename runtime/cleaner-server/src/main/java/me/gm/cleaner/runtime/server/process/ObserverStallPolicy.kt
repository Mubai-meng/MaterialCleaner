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

    /** 心跳停滞阈值：超过即疑似假活，需旁路探针确认 logd 本身正常再判死。 */
    const val STALL_THRESHOLD_MS: Long = 180_000L

    /** 旁路探针 `logcat -d -t 1` 超时：超过即判 logd wedged，本次不杀。 */
    const val LOGD_PROBE_TIMEOUT_MS: Long = 5_000L

    @JvmOverloads
    fun isStalled(
        nowMs: Long,
        startAtMs: Long,
        lastReadAtMs: Long,
        graceMs: Long = START_GRACE_MS,
        stallMs: Long = STALL_THRESHOLD_MS,
        logdResponsive: Boolean = true,
    ): Boolean {
        // logd 自身 wedged 时杀 server 无用（新 tail 照样读不到行），
        // 此时不判死；用户可见性由既有“日志猫已退出”通知分支承担。
        if (!logdResponsive) return false
        if (startAtMs <= 0L) return false
        if (nowMs - startAtMs < graceMs) return false
        if (lastReadAtMs <= 0L) return true
        return nowMs - lastReadAtMs >= stallMs
    }
}
