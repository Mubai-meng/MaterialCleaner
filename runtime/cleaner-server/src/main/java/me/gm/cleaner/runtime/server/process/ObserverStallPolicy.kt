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

    /**
     * 旁路证据的时间松弛：探针行与 lastRead 的换算之间留 1s 容差，
     * 避免日志时间戳（墙钟）与 elapsedRealtime 换算的边界抖动被误判成"有新行"。
     */
    const val PROBE_SLACK_MS: Long = 1_000L

    /**
     * 解析 `logcat -v epoch` 行的秒级时间戳（形如 `1792651708.588 9471 ...`）。
     *
     * @return 毫秒时间戳；-1 表示这行不可用。
     *
     * 必须容忍 `-d` 输出里夹带的 `--------- beginning of main` 这类分隔行 ——
     * 调用方逐行解析取最大值，不能把分隔行当成"没有行"而提前放弃。
     */
    fun parseEpochMillis(line: String?): Long {
        if (line.isNullOrBlank()) return -1L
        val token = line.trimStart().substringBefore(' ')
        val seconds = token.toDoubleOrNull() ?: return -1L
        if (seconds <= 0.0) return -1L
        // 必须四舍五入：`toLong()` 截断会让 1792651708.588 变成 ...587（浮点表示误差），
        // 与墙钟差 1ms 在松弛窗口边界上会翻转判定。
        return kotlin.math.round(seconds * 1000.0).toLong()
    }

    /**
     * 旁路结论：过滤流里是否真的存在比 [lastReadAtMs] 更新的行。
     *
     * 语义边界（本函数存在的全部理由）：
     * - 观察器的 tail 只订阅 `ActivityManager` 一个 tag，**空闲期本来就没有行**
     *   （夜间、无进程启动）。只凭心跳停滞判死，会在空闲时反复误杀 server
     *   —— 第32批现场：48 条 `SUP.WATCHDOG.RESTART/watchdog-logcat`，全部无 logcat 真死证据。
     * - [lineEpochMs] <= 0（探针没拿到任何可解析行）⇒ 过滤流本身就是空的 ⇒ 空闲，不判死。
     * - 有行但早于 lastRead ⇒ 那些行我们已经读过，停滞是假象。
     * - 有行且晚于 lastRead + 松弛 ⇒ 我们确实漏读了新行 ⇒ 真停滞。
     */
    fun isProbeLineFresh(
        lineEpochMs: Long,
        nowWallMs: Long,
        nowElapsedMs: Long,
        lastReadAtMs: Long,
        slackMs: Long = PROBE_SLACK_MS,
    ): Boolean {
        if (lineEpochMs <= 0L) return false
        if (lastReadAtMs <= 0L) return true
        // lastRead 是单调时钟；探针行是墙钟 —— 用本次采样的双时钟差换算。
        val lastReadWallMs = nowWallMs - (nowElapsedMs - lastReadAtMs)
        return lineEpochMs > lastReadWallMs + slackMs
    }

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
