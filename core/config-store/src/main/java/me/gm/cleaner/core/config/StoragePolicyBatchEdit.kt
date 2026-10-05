package me.gm.cleaner.core.config

import android.util.Log
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope

/**
 * 存储策略批量编辑：暂存多包变更后提交。
 *
 * 非原子：redirect 与 read-only 独立提交，先后失败互不回滚；
 * 调用方不得将其当作跨域事务。暂存失败记标记，提交直接报告失败，
 * 不再用原值伪装成功。
 */
class StoragePolicyBatchEdit(
    private val store: ConfiguredPolicyStore = ConfiguredPolicyStoreProvider.instance,
) {
    private var pendingRedirect: StoragePolicyEnvelope? = null
    private var pendingReadOnly: StoragePolicyEnvelope? = null
    private var baseRedirectRevision: String? = null
    private var baseReadOnlyRevision: String? = null
    private var stageFailed = false
    private var committed = false

    fun putRedirect(rawRules: List<Pair<String, String>>, packageNames: List<String>) {
        check(!committed) { "批量编辑已提交，不可复用" }
        pendingRedirect = stageRedirect(pendingRedirect, rawRules, packageNames)
    }

    fun putReadOnly(rawRules: List<String>, packageNames: List<String>) {
        check(!committed) { "批量编辑已提交，不可复用" }
        pendingReadOnly = stageReadOnly(pendingReadOnly, rawRules, packageNames)
    }

    fun commitStructured(): BatchCommitResult {
        check(!committed) { "批量编辑已提交，不可复用" }
        committed = true
        if (stageFailed) {
            return BatchCommitResult(
                overall = BatchCommitResult.Overall.FAILURE,
                redirect = null,
                readOnly = null,
                stageFailed = true,
            )
        }
        var redirectResult: PolicyStoreResult? = null
        var readOnlyResult: PolicyStoreResult? = null
        pendingRedirect?.let { pending ->
            val result = store.updateRedirect(baseRedirectRevision!!) { pending }
            redirectResult = result
            if (!result.success) {
                Log.e(TAG, "Failed to commit redirect policy batch: ${result.error}")
            }
        }
        pendingReadOnly?.let { pending ->
            val result = store.updateReadOnly(baseReadOnlyRevision!!) { pending }
            readOnlyResult = result
            if (!result.success) {
                Log.e(TAG, "Failed to commit read-only policy batch: ${result.error}")
            }
        }
        val attempted = listOfNotNull(redirectResult, readOnlyResult)
        val overall = when {
            attempted.all { it.success } -> BatchCommitResult.Overall.SUCCESS
            attempted.any { it.success } -> BatchCommitResult.Overall.PARTIAL
            else -> BatchCommitResult.Overall.FAILURE
        }
        return BatchCommitResult(
            overall = overall,
            redirect = redirectResult,
            readOnly = readOnlyResult,
            stageFailed = false,
        )
    }

    @Deprecated(
        message = "请改用 commitStructured 以区分 SUCCESS / PARTIAL / FAILURE",
        replaceWith = ReplaceWith("commitStructured().overall == BatchCommitOverall.SUCCESS"),
    )
    fun commit(): Boolean {
        return commitStructured().overall == BatchCommitResult.Overall.SUCCESS
    }

    private fun stageRedirect(
        staged: StoragePolicyEnvelope?,
        rawRules: List<Pair<String, String>>,
        packageNames: List<String>,
    ): StoragePolicyEnvelope {
        val current = staged?.let { it to baseRedirectRevision }
            ?: store.readRedirect().let { it.envelope to it.revision }
        baseRedirectRevision = current.second
        return try {
            current.first.replaceRedirectRules(rawRules, packageNames)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage redirect policy batch", e)
            stageFailed = true
            current.first
        }
    }

    private fun stageReadOnly(
        staged: StoragePolicyEnvelope?,
        rawRules: List<String>,
        packageNames: List<String>,
    ): StoragePolicyEnvelope {
        val current = staged?.let { it to baseReadOnlyRevision }
            ?: store.readReadOnly().let { it.envelope to it.revision }
        baseReadOnlyRevision = current.second
        return try {
            current.first.replaceReadOnlyRules(rawRules, packageNames)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage read-only policy batch", e)
            stageFailed = true
            current.first
        }
    }

    private companion object {
        const val TAG = "StoragePolicyBatchEdit"
    }
}
