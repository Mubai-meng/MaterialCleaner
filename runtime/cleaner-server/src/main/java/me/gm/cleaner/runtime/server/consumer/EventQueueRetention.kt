package me.gm.cleaner.runtime.server.consumer

/**
 * DataBus 事件队列保留策略（清理责任人：Consumer 决定保留多久，DataBus 执行删除）。
 *
 * 与既有 `CONSUMED_TTL_MS`（consumed/ 归档）同构：策略在消费侧，
 * 物理删除在 DataBus。两个 Consumer 共用本对象，避免各自定义导致口径漂移。
 */
internal object EventQueueRetention {

    /**
     * 源事件保留窗：30 分钟。
     *
     * 作用仅为「缩短游标故障后可恢复历史」这一已接受边界的量级控制，
     * 不用于证明游标损坏后仍能完整重放（见 AGENTS.md 第 5 条）。
     */
    const val SOURCE_RETENTION_MS = 30 * 60 * 1000L

    /** 毒丸证据保留期：24 小时，与 consumed/ 归档口径对齐。 */
    const val QUARANTINE_RETENTION_MS = 24 * 60 * 60 * 1000L

    /** 清理节流：最多 5 分钟一次，避免每 2s 轮询都全列目录。 */
    const val PRUNE_INTERVAL_MS = 5 * 60 * 1000L

    /** 失败后的短重试间隔：不让单次 I/O 故障导致 5 分钟完全没有重试。 */
    const val PRUNE_RETRY_INTERVAL_MS = 30 * 1000L

    /** 连续失败达到此次数后向错误流水留痕，使清理长期失败在诊断层可见。 */
    const val PRUNE_FAILURE_JOURNAL_THRESHOLD = 3
}
