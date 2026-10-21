package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONObject

// 策略状态只描述「配置到执行器」的进度，不代表底层行为已经被探针证明。
internal const val POLICY_STATE_NO_RULE = "NO_RULE"
internal const val POLICY_STATE_PENDING = "PENDING"
internal const val POLICY_STATE_APPLYING = "APPLYING"
internal const val POLICY_STATE_APPLIED = "APPLIED"
internal const val POLICY_STATE_STALE = "STALE"
internal const val POLICY_STATE_UNSUPPORTED = "UNSUPPORTED"

internal const val REDIRECT_EXECUTOR = "MEDIA_PROVIDER_JAVA_HOOK"
internal const val READ_ONLY_EXECUTOR = "MEDIA_PROVIDER_JAVA_HOOK"
internal const val MOUNT_POINTS_EXECUTOR = "FUSE_NATIVE_HOOK"

/**
 * 三条策略状态机：挂载点（FUSE native）、重定向与只读（Java Hook）。
 *
 * 职责边界：只维护「配置代次 → 执行器是否接受」的进度与失败事实，
 * **不发布快照**（发布由 [NativeHookStatus] 统一负责），也不做行为级裁决
 * （是否真正生效由文件系统探针与统计段证明，不是本类能断言的）。
 *
 * 三条链共用一个 [aggregateState]：主界面状态卡只显示一个"策略"结论，
 * 因此需要把三条链的状态收敛成一个可读值（优先级：APPLYING > STALE >
 * UNSUPPORTED > NO_RULE > PENDING > APPLIED）。
 */
internal class PolicyApplyState {

    @Volatile
    private var lastMountPointsApplySuccess = false

    @Volatile
    private var mountPointsGeneration = 0L

    @Volatile
    private var mountPointsAppliedEpoch = ""

    @Volatile
    private var lastMountPointsApplyAt = 0L

    @Volatile
    private var lastMountPointsApplyGeneration = 0L

    @Volatile
    private var lastMountPointsApplyCount = 0

    @Volatile
    private var lastMountPointsApplyError = ""

    @Volatile
    private var mountPointsAppliedRevision = ""

    @Volatile
    private var mountPointsLastAttemptRevision = ""

    @Volatile
    private var mountPointsState = POLICY_STATE_PENDING

    @Volatile
    private var mountPointsConfiguredRevision = ""

    @Volatile
    private var mountPointsPublishedRevision = ""

    @Volatile
    private var mountPointsObservedAt = 0L

    /** 最近一次挂载点应用**尝试开始**时间：与「尝试完成时间」分离。 */
    @Volatile
    private var lastMountPointsAttemptAt = 0L

    /** 最近一次 attempt 的目标身份（与 attemptAt 配套；仅时间无法区分服务的是哪个 epoch）。 */
    @Volatile
    private var lastMountPointsAttemptEpoch = ""

    @Volatile
    private var lastMountPointsAttemptGeneration = 0L

    @Volatile
    private var redirectAppliedRevision = ""

    @Volatile
    private var redirectAppliedGeneration = 0L

    @Volatile
    private var redirectPolicyState = POLICY_STATE_PENDING

    @Volatile
    private var redirectConfiguredRevision = ""

    @Volatile
    private var redirectPublishedRevision = ""

    @Volatile
    private var redirectLastError = ""

    @Volatile
    private var redirectObservedAt = 0L

    @Volatile
    private var readOnlyAppliedRevision = ""

    @Volatile
    private var readOnlyAppliedGeneration = 0L

    @Volatile
    private var readOnlyPolicyState = POLICY_STATE_PENDING

    @Volatile
    private var readOnlyConfiguredRevision = ""

    @Volatile
    private var readOnlyPublishedRevision = ""

    @Volatile
    private var readOnlyLastError = ""

    @Volatile
    private var readOnlyObservedAt = 0L

    fun markMountPointsApplySucceeded(
        generation: Long,
        count: Int,
        redirectRevision: String,
        publisherEpoch: String,
    ) {
        lastMountPointsApplySuccess = true
        mountPointsGeneration = generation
        // 已应用代次确认：仅成功路径更新；失败/不支持路径绝不碰它，
        // 否则会把尚未成功应用的配置误判为正常。
        mountPointsAppliedEpoch = publisherEpoch
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = ""
        mountPointsAppliedRevision = redirectRevision
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        mountPointsState = when {
            count == 0 -> POLICY_STATE_NO_RULE
            redirectRevision.isBlank() -> POLICY_STATE_PENDING
            else -> POLICY_STATE_APPLIED
        }
    }

    fun markMountPointsApplyStarted(
        generation: Long,
        redirectRevision: String,
        publisherEpoch: String,
    ) {
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        // 每次 attempt 必经入口：记录开始时间与目标身份，
        // 作为"当前配置正在同步"的可验证证据（无身份的时间戳不可作为证据）。
        lastMountPointsAttemptAt = mountPointsObservedAt
        lastMountPointsAttemptEpoch = publisherEpoch
        lastMountPointsAttemptGeneration = generation
        mountPointsState = POLICY_STATE_APPLYING
    }

    fun markMountPointsApplyUnsupported(
        redirectRevision: String,
        count: Int,
        error: Throwable,
    ) {
        lastMountPointsApplySuccess = false
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = 0L
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = NativeStatusSnapshotParser.describe(error)
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        mountPointsState = POLICY_STATE_UNSUPPORTED
    }

    fun markMountPointsApplyFailed(
        generation: Long,
        count: Int,
        redirectRevision: String,
        error: Throwable,
    ) {
        if (mountPointsState == POLICY_STATE_UNSUPPORTED) {
            // 平台明确不支持时，适配器和上层消费器可能各自记录一次失败；
            // 保留 UNSUPPORTED，不能被通用异常路径降级成普通 PENDING。
            return
        }
        lastMountPointsApplySuccess = false
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = NativeStatusSnapshotParser.describe(error)
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        mountPointsState = if (mountPointsAppliedRevision.isNotBlank()) {
            POLICY_STATE_STALE
        } else {
            POLICY_STATE_PENDING
        }
    }

    /** Java Hook 已接受重定向正文；这不是行为层面的 EFFECTIVE 证明。 */
    fun markRedirectPolicyApplied(revision: String, generation: Long, hasRules: Boolean) {
        val observedAt = System.currentTimeMillis()
        redirectConfiguredRevision = revision
        redirectPublishedRevision = revision
        redirectAppliedRevision = revision
        redirectAppliedGeneration = generation
        redirectLastError = ""
        redirectObservedAt = observedAt
        redirectPolicyState = when {
            !hasRules -> POLICY_STATE_NO_RULE
            revision.isBlank() -> POLICY_STATE_PENDING
            else -> POLICY_STATE_APPLIED
        }
    }

    fun markRedirectPolicyFailed(revision: String, error: String) {
        redirectConfiguredRevision = revision
        redirectPublishedRevision = revision
        redirectObservedAt = System.currentTimeMillis()
        redirectLastError = error.take(NativeHookStatus.MAX_TEXT_LENGTH)
        redirectPolicyState = if (redirectAppliedRevision.isNotBlank()) {
            POLICY_STATE_STALE
        } else {
            POLICY_STATE_PENDING
        }
    }

    /** Java Hook 已接受只读正文；这不是行为层面的 EFFECTIVE 证明。 */
    fun markReadOnlyPolicyApplied(revision: String, generation: Long, hasRules: Boolean) {
        val observedAt = System.currentTimeMillis()
        readOnlyConfiguredRevision = revision
        readOnlyPublishedRevision = revision
        readOnlyAppliedRevision = revision
        readOnlyAppliedGeneration = generation
        readOnlyLastError = ""
        readOnlyObservedAt = observedAt
        readOnlyPolicyState = when {
            !hasRules -> POLICY_STATE_NO_RULE
            revision.isBlank() -> POLICY_STATE_PENDING
            else -> POLICY_STATE_APPLIED
        }
    }

    fun markReadOnlyPolicyFailed(revision: String, error: String) {
        readOnlyConfiguredRevision = revision
        readOnlyPublishedRevision = revision
        readOnlyObservedAt = System.currentTimeMillis()
        readOnlyLastError = error.take(NativeHookStatus.MAX_TEXT_LENGTH)
        readOnlyPolicyState = if (readOnlyAppliedRevision.isNotBlank()) {
            POLICY_STATE_STALE
        } else {
            POLICY_STATE_PENDING
        }
    }

    /** inline 平台能力重新可用时，挂载点链回到 PENDING 等待重新下发。 */
    fun resetToPending() {
        mountPointsState = POLICY_STATE_PENDING
    }

    /** 三条链收敛成一个可读结论（优先级见类注释）。 */
    fun aggregateState(): String = when {
        mountPointsState == POLICY_STATE_APPLYING ||
                redirectPolicyState == POLICY_STATE_APPLYING ||
                readOnlyPolicyState == POLICY_STATE_APPLYING -> POLICY_STATE_APPLYING
        mountPointsState == POLICY_STATE_STALE -> POLICY_STATE_STALE
        redirectPolicyState == POLICY_STATE_STALE || readOnlyPolicyState == POLICY_STATE_STALE ->
            POLICY_STATE_STALE
        mountPointsState == POLICY_STATE_UNSUPPORTED ||
                redirectPolicyState == POLICY_STATE_UNSUPPORTED ||
                readOnlyPolicyState == POLICY_STATE_UNSUPPORTED -> POLICY_STATE_UNSUPPORTED
        mountPointsState == POLICY_STATE_NO_RULE &&
                redirectPolicyState == POLICY_STATE_NO_RULE &&
                readOnlyPolicyState == POLICY_STATE_NO_RULE -> POLICY_STATE_NO_RULE
        mountPointsState == POLICY_STATE_PENDING ||
                redirectPolicyState == POLICY_STATE_PENDING ||
                readOnlyPolicyState == POLICY_STATE_PENDING -> POLICY_STATE_PENDING
        mountPointsState == POLICY_STATE_APPLIED ||
                redirectPolicyState == POLICY_STATE_APPLIED ||
                readOnlyPolicyState == POLICY_STATE_APPLIED -> POLICY_STATE_APPLIED
        else -> POLICY_STATE_PENDING
    }

    /** 快照序列化：`policyCache` 段里的策略部分（紧跟在缓存自身字段之后）。 */
    fun appendToPolicyCache(json: JSONObject) {
        json.put("applicationState", aggregateState())
        json.put("redirectState", redirectPolicyState)
        json.put("readOnlyState", readOnlyPolicyState)
        json.put("redirectConfiguredRevision", redirectConfiguredRevision)
        json.put("redirectPublishedRevision", redirectPublishedRevision)
        json.put("redirectAppliedRevision", redirectAppliedRevision)
        json.put("redirectAppliedGeneration", redirectAppliedGeneration)
        json.put("redirectExecutor", REDIRECT_EXECUTOR)
        json.put("redirectLastError", redirectLastError)
        json.put("redirectObservedAt", redirectObservedAt)
        json.put("readOnlyConfiguredRevision", readOnlyConfiguredRevision)
        json.put("readOnlyPublishedRevision", readOnlyPublishedRevision)
        json.put("readOnlyAppliedRevision", readOnlyAppliedRevision)
        json.put("readOnlyAppliedGeneration", readOnlyAppliedGeneration)
        json.put("readOnlyExecutor", READ_ONLY_EXECUTOR)
        json.put("readOnlyLastError", readOnlyLastError)
        json.put("readOnlyObservedAt", readOnlyObservedAt)
    }

    /** 快照序列化：`policy` 段（挂载点链）。 */
    fun appendToPolicy(json: JSONObject) {
        json.put("mountPointsGeneration", mountPointsGeneration)
        json.put("appliedPublisherEpoch", mountPointsAppliedEpoch)
        json.put("lastApplySuccess", lastMountPointsApplySuccess)
        json.put("appliedToExecutor", lastMountPointsApplySuccess)
        json.put("applicationState", mountPointsState)
        json.put("state", mountPointsState)
        json.put("configuredRevision", mountPointsConfiguredRevision)
        json.put("publishedRevision", mountPointsPublishedRevision)
        json.put("appliedRedirectRevision", mountPointsAppliedRevision)
        json.put("executor", MOUNT_POINTS_EXECUTOR)
        json.put("lastError", lastMountPointsApplyError)
        json.put("observedAt", mountPointsObservedAt)
        json.put("lastAttemptRedirectRevision", mountPointsLastAttemptRevision)
        json.put("lastApplyAt", lastMountPointsApplyAt)
        json.put("lastApplyGeneration", lastMountPointsApplyGeneration)
        json.put("lastAttemptAt", lastMountPointsAttemptAt)
        json.put("lastAttemptEpoch", lastMountPointsAttemptEpoch)
        json.put("lastAttemptGeneration", lastMountPointsAttemptGeneration)
        json.put("lastApplyCount", lastMountPointsApplyCount)
        json.put("lastApplyError", lastMountPointsApplyError)
    }
}
