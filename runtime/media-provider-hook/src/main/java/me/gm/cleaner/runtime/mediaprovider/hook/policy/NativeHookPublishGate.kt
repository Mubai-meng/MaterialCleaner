package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * [NativeHookStatus] 快照发布的放行闸门（纯逻辑，无 Android 依赖，可单测）。
 *
 * ## 为什么需要它
 * 修改前 `publishSnapshot()` 只有「前沿合并窗口」一层判据，且
 * `HookPolicyRefreshScheduler` 每 5s **无条件**调用一次 —— 由于窗口(1s) < 轮询周期(5s)，
 * 那次调用**必然**放行，于是即使语义毫无变化也每 5s 落一次盘（实测 16 次写 / 16 条 signal）。
 *
 * ## 两条路径共用一个水位
 * - [onSemanticChange]：`mark*` 路径，表示语义字段确实变了。
 * - [onPoll]：定时轮询路径，只负责「补落盘」与「保活」。
 *
 * ## 放行条件（[evaluate]）
 * 1. 距上次**放行**已过 [coalesceWindowMillis]（前沿合并，抑制 `mark*` 风暴）；
 * 2. 且满足下列任一：
 *    - 存在尚未成功落盘的语义变更（`pendingSemanticChange`）→ 补落盘；
 *    - 距上次放行已过 [heartbeatIntervalMillis] → 保活。
 *
 * ## 末态不丢（尾沿语义）
 * 被第 1 条合并掉的语义变更**不会丢**：`pendingSemanticChange` 保持为 true，
 * 下一轮轮询（≤ [heartbeatIntervalMillis]）必然放行一次。
 *
 * ## 为什么保活下限是刚需，而不是「可选的兜底」
 * 本快照的存活判据是 payload 里的 `createdAt`：cleaner-server 侧
 * `NativeHookLayerReporter.readNativeHookStatusFromDataBus()` 读 `createdAt` 算 `ageMs`，
 * 一旦 `ageMs > NATIVE_HOOK_STATUS_MAX_AGE_MS`（当前 15_000L）就**丢弃该快照**；
 * 若此时 binder 兜底也不可用，FUSE 层会被直接判成 `UNAVAILABLE` 并显示在主界面状态卡上。
 * 因此**必须周期性刷新 `createdAt`** —— 这也正是不能对本站做「严格内容脏检查」的原因：
 * payload 每轮都因 `createdAt` 而不同，逐字节哈希永远不会命中。
 *
 * ⚠️ 调大 [heartbeatIntervalMillis] 前必须同步评估（或放宽）读者的判活窗口，
 * 否则会引入「状态卡偶发显示不可用」的功能回归。
 */
internal class NativeHookPublishGate(
    private val coalesceWindowMillis: Long,
    private val heartbeatIntervalMillis: Long,
) {
    private val lock = Any()

    /**
     * 上次**放行**（即即将落盘）的单调时刻；[NEVER_ATTEMPTED] 表示尚未放行过。
     *
     * 刻意用负数哨兵而非 `0L`：单调时钟从 0 起算时 `0` 会与「刚放行过」撞车。
     */
    private var lastAttemptAtElapsedMs = NEVER_ATTEMPTED

    /** 是否存在「已变更但尚未成功落盘」的语义内容。 */
    private var pendingSemanticChange = false

    /** `mark*` 路径：先登记语义变更，再按窗口判定是否放行。 */
    fun onSemanticChange(nowElapsedMs: Long): Boolean = synchronized(lock) {
        pendingSemanticChange = true
        evaluate(nowElapsedMs)
    }

    /** 轮询路径：不登记新变更，只做「补落盘」与「保活」。 */
    fun onPoll(nowElapsedMs: Long): Boolean = synchronized(lock) {
        evaluate(nowElapsedMs)
    }

    /**
     * 落盘结果回报：**仅在成功时**清除待落盘标记。
     * 失败则保留标记，使下一轮轮询（≤ [heartbeatIntervalMillis]）重试，
     * 避免一次瞬时写失败把语义变更静默丢掉。
     */
    fun onPublishResult(succeeded: Boolean) = synchronized(lock) {
        if (succeeded) {
            pendingSemanticChange = false
        }
    }

    private fun evaluate(nowElapsedMs: Long): Boolean {
        val sinceLastAttempt = if (lastAttemptAtElapsedMs == NEVER_ATTEMPTED) {
            Long.MAX_VALUE
        } else {
            nowElapsedMs - lastAttemptAtElapsedMs
        }
        // 1) 前沿合并：窗口内只放行第一次，抑制 mark* 风暴。
        if (sinceLastAttempt < coalesceWindowMillis) {
            return false
        }
        // 2) 语义未变时，只有过了保活下限才放行。
        if (!pendingSemanticChange && sinceLastAttempt < heartbeatIntervalMillis) {
            return false
        }
        // 放行即推进水位（在锁内），并发调用不会同时放行。
        lastAttemptAtElapsedMs = nowElapsedMs
        return true
    }

    internal companion object {
        /** [lastAttemptAtElapsedMs] 的「从未放行」哨兵。 */
        internal const val NEVER_ATTEMPTED = -1L
    }
}
