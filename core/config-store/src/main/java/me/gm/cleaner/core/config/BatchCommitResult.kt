package me.gm.cleaner.core.config

/**
 * 存储策略批量提交的结构化结果。
 *
 * 两域独立提交、互不回滚：redirect 与 read-only 各自调用
 * [ConfiguredPolicyStore.updateRedirect] / [ConfiguredPolicyStore.updateReadOnly]，
 * 一域失败不回滚另一域已成功的写入。
 *
 * - [redirect] / [readOnly] 为对应域的原始 [PolicyStoreResult]，不新造失败类型；
 *   为 null 表示该域本次未尝试提交（无暂存内容，或因 [stageFailed] 在入口被阻断）。
 * - [stageFailed] 为 true 表示暂存阶段已失败，提交在入口被阻断，两域均为 null，
 *   [overall] 为 [Overall.FAILURE]，且不产生任何写入。
 * - [overall] 聚合规则：无失败为 SUCCESS；有成功也有失败为 PARTIAL；全部失败（含被阻断）为 FAILURE。
 */
data class BatchCommitResult(
    val overall: Overall,
    val redirect: PolicyStoreResult?,
    val readOnly: PolicyStoreResult?,
    val stageFailed: Boolean,
) {
    enum class Overall {
        SUCCESS,
        PARTIAL,
        FAILURE,
    }
}

/** 顶层别名，兼容 `BatchCommitOverall` 与 `BatchCommitResult.Overall` 两种引用写法。 */
typealias BatchCommitOverall = BatchCommitResult.Overall
