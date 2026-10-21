package me.gm.cleaner.runtime.mediaprovider.hook.policy
import android.os.SystemClock
import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookDataBusBridge
import me.gm.cleaner.runtime.mediaprovider.hook.err.HookErrorJournal
import org.json.JSONArray
import org.json.JSONObject
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeStatusSnapshotParser.describe

object NativeHookStatus {
    private const val TAG = "NativeHookStatus"
    /**
     * 快照结构版本：5 = 新增 `readOnlyEval` 段（只读判定 / 拒绝计数）。
     * 6 = 新增 `inaccessibleEval` 段（「不可访问的路径」判定 / 拒绝计数）。
     * 7 = `inaccessibleEval` 新增 `appSpecificRows`（应用专属存储幽灵行观测计数）。
     *     ⚠️ 不可访问 ≠ 只读：只读允许读、拒绝写；不可访问连读都拒绝。
     * 4 = 新增 `fuseRedirect` 段（重定向命中/未命中计数）。
     * 3 = 新增 `hookErrors` 段（模块进程结构化错误流水）。
     * 2 = 无这两段。消费方一律用 `optJSONObject` 读取，缺段即视为模块未提供。
     */
    private const val SCHEMA_VERSION = 7

    /** 快照内受控文本（错误详情等）的最大长度，超出截断。 */
    internal const val MAX_TEXT_LENGTH = 200

    /**
     * `hookErrors.entries` 的导出条数上限。
     * 该快照每 8s 心跳重写一次，50 条 × ~150 B ≈ 7.5 KB 尚可接受，但不放开上限。
     */
    private const val HOOK_ERROR_SNAPSHOT_LIMIT = 20

    /**
     * 宿主预期异常（swallowedHostExceptions）计数的快照发布最小间隔。
     * 该路径可能被宿主高频触发（如 query 对特定参数抛 IllegalArgumentException），
     * 窗口内的增量由下一次任意 mark* 发布、或轮询路径的保活落盘
     * （[publishSnapshotFromPoll]，≤ [HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS]）顺带带出，
     * 避免快照通道风暴。
     */
    internal const val SWALLOWED_PUBLISH_INTERVAL_MILLIS = 30_000L

    /**
     * [publishSnapshot] 的前沿合并窗口。
     *
     * 本方法有 29 个调用点（各 mark* / reset* 各一处），其中多数无节流。桥重连时
     * `HookPolicyCache.refreshFromDataBus` 会逐段触发 mark*，实测在 **78ms 内连打 8 次**
     * 发布：每次都写约 8KB + fsync + rename + chmod，并发一次
     * native_hook_status_changed 信号唤醒全部消费者 —— 纯属抖动。
     *
     * 窗口内只放行第一次。**末态不会丢**：被合并掉的语义变更由
     * [NativeHookPublishGate] 的 `pendingSemanticChange` 保持为「待落盘」，
     * 下一轮 `HookPolicyRefreshScheduler` 轮询必然补落盘。取单调时钟而非墙上时钟，
     * 避免系统校时回拨导致节流被无限延长。
     */
    internal const val PUBLISH_COALESCE_WINDOW_MILLIS = 1_000L

    /**
     * 轮询路径（[publishSnapshotFromPoll]）的**保活下限**（单调时钟毫秒）。
     *
     * ## 为什么必须有保活，而不能只做「语义脏检查」
     * 本快照在 cleaner-server 侧的**存活判据是 payload 里的 `createdAt`**：
     * `NativeHookLayerReporter.readNativeHookStatusFromDataBus()` 读 `createdAt` 算出
     * `ageMs`，一旦 `ageMs > NATIVE_HOOK_STATUS_MAX_AGE_MS`（当前 **15_000L**）
     * 就丢弃该快照；此时若 binder 兜底也不可用，FUSE 层会被判成 `UNAVAILABLE`
     * 并显示在主界面状态卡上。所以**必须周期性刷新 `createdAt`**。
     * 又因 `createdAt` 每轮都变，逐字节/内容哈希脏检查永远不会命中 —— 这也是
     * 本站不能照搬 cleaner-server 侧 `RuntimeStatusAggregator` 那套「严格语义指纹」做法的原因
     * （那个快照没有读取方，本站有）。
     *
     * ## 取值约束（不可随意调大）
     * 最坏陈旧度 ≈ 本值 + 一个轮询周期
     * （`HookPolicyRefreshScheduler.POLL_INTERVAL_MS` = 5_000L），必须显著小于 15_000L。
     * 取 8_000L ⇒ 在 5s 轮询下的**实际落盘周期为 10s**（相邻两次轮询中只有后一次过线），
     * 最坏陈旧度约 10s，相对 15s 判活窗口留出余量。
     * ⚠️ 调大本值前必须同步评估（或放宽）读者的判活窗口。
     */
    internal const val HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS = 8_000L

    private const val STATE_NOT_LOADED = "NOT_LOADED"
    private const val STATE_INLINE_LOADED = "INLINE_LOADED"
    private const val STATE_FUSE_WAITING = "FUSE_WAITING"
    private const val STATE_HOOK_READY_FULL = "HOOK_READY_FULL"
    private const val STATE_HOOK_READY_CORE = "HOOK_READY_CORE"
    private const val STATE_HOOK_DEGRADED = "HOOK_DEGRADED"
    private const val STATE_HOOK_UNAVAILABLE = "HOOK_UNAVAILABLE"
    private const val STATE_DISABLED = "DISABLED"

    private const val BRIDGE_STATE_IDLE = "IDLE"
    private const val BRIDGE_STATE_REGISTERING = "REGISTERING"
    private const val BRIDGE_STATE_REGISTERED = "REGISTERED"
    private const val BRIDGE_STATE_RETRYING = "RETRYING"
    private const val BRIDGE_STATE_FAILED = "FAILED"
    private var mediaProviderHookLoaded = false
    @Volatile
    private var mediaProviderPackageName = ""
    @Volatile
    private var policyCacheInitialized = false
    @Volatile
    private var policyCacheInitializedAt = 0L

    // ── 子状态：各自负责一个观测域的字段与序列化，本对象只做聚合与发布 ──
    private val guarded = GuardedHookCircuitState()
    private val fuse = FuseRedirectCounters()
    private val policy = PolicyApplyState()

    /** [publishSnapshot] / [publishSnapshotFromPoll] 共用的放行闸门。 */
    private val publishGate = NativeHookPublishGate(
        coalesceWindowMillis = PUBLISH_COALESCE_WINDOW_MILLIS,
        heartbeatIntervalMillis = HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS,
    )

    @Volatile
    private var inlineState = STATE_NOT_LOADED
    @Volatile
    private var inlineLibraryLoaded = false
    @Volatile
    private var inlineHookInitialized = false
    @Volatile
    private var inlineRetryCount = 0
    @Volatile
    private var inlineNextRetryAt = 0L
    @Volatile
    private var inlineRetryExhausted = false
    @Volatile
    private var inlineDisabledByPlatform = false
    @Volatile
    private var lastInlineError = ""
    @Volatile
    private var inlineLastFailureCode = ""

    @Volatile
    private var nativeStatus = NativeStatusSnapshot()

    @Volatile
    private var fuseJavaGateStatus = FuseJavaGateStatus()

    /**
     * FUSE 文件事件**前置去重**累计抑制条数（见 `FuseEventDedup`）。
     *
     * 只做绝对值单调写（热路径上读一次计数器即可，不做任何发布动作）；
     * 下一个 10s 心跳的 [publishSnapshot] 会把它带进快照，因此诊断归档里的
     * `fuseJavaGate.dedupedEvents` 能直接证明该机制在生效。
     */
    @Volatile
    private var policyCacheLastFailureCode = ""
    @Volatile
    private var policyCacheLastFailureGeneration = 0L
    @Volatile
    private var policyCacheLastError = ""
    @Volatile
    private var policyCacheLastGoodGeneration = 0L

    // ── hooks callback binder 桥注册状态机 ──
    @Volatile
    private var bridgeState = BRIDGE_STATE_IDLE
    @Volatile
    private var bridgeLastError = ""
    @Volatile
    private var bridgeAttemptCount = 0
    @Volatile
    private var bridgeLastAttemptAt = 0L

    fun markMediaProviderHookLoaded(packageName: String) {
        mediaProviderHookLoaded = true
        mediaProviderPackageName = packageName
        publishSnapshot()
    }

    fun markPolicyCacheInitialized() {
        policyCacheInitialized = true
        policyCacheInitializedAt = System.currentTimeMillis()
        publishSnapshot()
    }

    fun markInlineLoadSucceeded(statusJson: String) {
        val parsed = NativeStatusSnapshotParser.parse(statusJson)
        nativeStatus = parsed
        inlineLibraryLoaded = true
        inlineHookInitialized = parsed.coreAvailable
        lastInlineError = parsed.lastError
        inlineState = deriveInlineState(parsed)
        inlineDisabledByPlatform = false
        if (inlineState == STATE_DISABLED) {
            inlineRetryExhausted = true
            inlineNextRetryAt = 0L
        }
        if (parsed.coreAvailable) {
            inlineRetryCount = 0
            inlineNextRetryAt = 0L
            inlineRetryExhausted = false
            // 核心符号全部就位：清除 FUSE 域失败码。
            inlineLastFailureCode = ""
        } else if (inlineLastFailureCode.isBlank()) {
            // 初始化完成但核心不可用且尚无失败码：由调用方经 markInlineNativeFailure 补充；
            // 此处兜底标记为平台能力问题，避免快照出现"未初始化却无原因"的盲区。
            inlineLastFailureCode = ErrorCodes.HOOK_FUSE_CAPABILITY_UNAVAILABLE
        }
        publishSnapshot()
    }

    /**
     * 记录 FUSE native 初始化的结构化失败原因。
     * 由 [FuseNativePolicyAdapter] 依据 init() 返回的 statusJson.lastError 映射
     * [ErrorCodes.HOOK_FUSE_*] 后调用；lastError 文本仍保留用于人读诊断。
     */
    fun markInlineNativeFailure(code: String, detail: String) {
        inlineLastFailureCode = code
        if (detail.isNotBlank()) {
            lastInlineError = detail.take(MAX_TEXT_LENGTH)
        }
        publishSnapshot()
    }

    fun markInlineLoadFailed(error: Throwable) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        nativeStatus = NativeStatusSnapshot(lastError = describe(error))
        lastInlineError = describe(error)
        inlineState = STATE_NOT_LOADED
        inlineDisabledByPlatform = false
        // 动态库加载/JNI 注册层失败：区别于符号缺失类失败。
        inlineLastFailureCode = ErrorCodes.HOOK_FUSE_LIB_LOAD_FAILED
        publishSnapshot()
    }

    fun markInlineRetryScheduled(retryCount: Int, nextRetryAt: Long) {
        inlineRetryCount = retryCount
        inlineNextRetryAt = nextRetryAt
        inlineRetryExhausted = false
        if (inlineState == STATE_NOT_LOADED || inlineState == STATE_INLINE_LOADED) {
            inlineState = STATE_FUSE_WAITING
        }
        publishSnapshot()
    }

    fun markInlineRetryExhausted(error: String) {
        inlineRetryExhausted = true
        inlineNextRetryAt = 0L
        lastInlineError = error
        inlineState = STATE_HOOK_UNAVAILABLE
        inlineDisabledByPlatform = false
        publishSnapshot()
    }

    fun markInlineDisabled(
        reason: String,
        fuseAvailable: Boolean,
        fuseJniLoadMode: String = "UNKNOWN",
    ) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineRetryExhausted = true
        inlineNextRetryAt = 0L
        lastInlineError = reason
        inlineState = STATE_DISABLED
        inlineDisabledByPlatform = true
        nativeStatus = NativeStatusSnapshot(
            fuseAvailable = fuseAvailable,
            hookMode = "NONE",
            fuseJniLoadMode = fuseJniLoadMode,
            lastError = reason,
        )
        publishSnapshot()
    }

    fun resetInlineRetryState() {
        inlineRetryCount = 0
        inlineNextRetryAt = 0L
        inlineRetryExhausted = false
        publishSnapshot()
    }

    fun markInlinePlatformSupported() {
        if (inlineState != STATE_DISABLED) {
            return
        }
        inlineState = STATE_NOT_LOADED
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineRetryCount = 0
        inlineNextRetryAt = 0L
        inlineRetryExhausted = false
        inlineDisabledByPlatform = false
        lastInlineError = ""
        nativeStatus = NativeStatusSnapshot()
        policy.resetToPending()
        publishSnapshot()
    }

    fun shouldRetryInlineInitialization(now: Long): Boolean {
        if (inlineRetryExhausted) return false
        if (inlineState == STATE_DISABLED) return false
        if (isInlinePolicyBridgeAvailable()) return false
        return inlineNextRetryAt <= 0L || now >= inlineNextRetryAt
    }

    fun currentInlineRetryCount(): Int = inlineRetryCount

    fun currentInlineState(): String = inlineState

    fun isInlineDisabled(): Boolean = inlineState == STATE_DISABLED

    fun isInlineDisabledByPlatform(): Boolean =
        inlineState == STATE_DISABLED && inlineDisabledByPlatform

    fun isInlinePolicyBridgeAvailable(): Boolean =
        inlineState == STATE_HOOK_READY_FULL ||
                inlineState == STATE_HOOK_READY_CORE ||
                inlineState == STATE_HOOK_DEGRADED

    fun markFuseJavaGateScanned(
        discoveredCount: Int,
        hookedMethods: List<String>,
        unknownMethods: List<String>,
        failedMethods: List<String>,
        skippedMethods: List<String>,
    ) {
        fuseJavaGateStatus = FuseJavaGateStatus(
            discoveredCount = discoveredCount,
            hookedMethods = hookedMethods,
            unknownMethods = unknownMethods,
            failedMethods = failedMethods,
            skippedMethods = skippedMethods,
        )
        publishSnapshot()
    }

    /**
     * 标记策略缓存一次加载失败。
     *
     * [code] 必须引用 [me.gm.cleaner.core.common.err.ErrorCodes.HOOK_JAVA_CACHE_*]
     * 常量；[generation] 为失败发生时已知的策略代际（与 ErrorEvent.generation
     * 语义一致），仅作诊断对照，不推进 [policyCacheLastGoodGeneration]。
     */
    fun markPolicyCacheFailed(code: String, detail: String?, generation: Long) {
        policyCacheLastFailureCode = code
        policyCacheLastFailureGeneration = generation
        policyCacheLastError = detail?.take(MAX_TEXT_LENGTH) ?: ""
        publishSnapshot()
    }

    /** 标记策略缓存加载成功：清空失败字段并单调推进最后成功代际。 */
    fun markPolicyCacheHealthy(generation: Long) {
        if (generation > policyCacheLastGoodGeneration) {
            policyCacheLastGoodGeneration = generation
        }
        policyCacheLastFailureCode = ""
        policyCacheLastFailureGeneration = 0L
        policyCacheLastError = ""
        publishSnapshot()
    }

    /** 挂载点（FUSE native）策略应用成功。 */
    fun markMountPointsApplySucceeded(
        generation: Long,
        count: Int,
        redirectRevision: String,
        publisherEpoch: String,
    ) {
        policy.markMountPointsApplySucceeded(generation, count, redirectRevision, publisherEpoch)
        publishSnapshot()
    }

    /** 挂载点策略应用开始（每次 attempt 必经入口）。 */
    fun markMountPointsApplyStarted(generation: Long, redirectRevision: String, publisherEpoch: String) {
        policy.markMountPointsApplyStarted(generation, redirectRevision, publisherEpoch)
        publishSnapshot()
    }

    /** 挂载点策略被平台判定为不支持。 */
    fun markMountPointsApplyUnsupported(redirectRevision: String, count: Int, error: Throwable) {
        policy.markMountPointsApplyUnsupported(redirectRevision, count, error)
        publishSnapshot()
    }

    /** 挂载点策略应用失败。 */
    fun markMountPointsApplyFailed(
        generation: Long,
        count: Int,
        redirectRevision: String,
        error: Throwable,
    ) {
        policy.markMountPointsApplyFailed(generation, count, redirectRevision, error)
        publishSnapshot()
    }

    /** Java Hook 已接受重定向正文；这不是行为层面的 EFFECTIVE 证明。 */
    fun markRedirectPolicyApplied(revision: String, generation: Long, hasRules: Boolean) {
        policy.markRedirectPolicyApplied(revision, generation, hasRules)
        publishSnapshot()
    }

    fun markRedirectPolicyFailed(revision: String, error: String) {
        policy.markRedirectPolicyFailed(revision, error)
        publishSnapshot()
    }

    /** Java Hook 已接受只读正文；这不是行为层面的 EFFECTIVE 证明。 */
    fun markReadOnlyPolicyApplied(revision: String, generation: Long, hasRules: Boolean) {
        policy.markReadOnlyPolicyApplied(revision, generation, hasRules)
        publishSnapshot()
    }

    fun markReadOnlyPolicyFailed(revision: String, error: String) {
        policy.markReadOnlyPolicyFailed(revision, error)
        publishSnapshot()
    }

    /**
     * 受防护 hook 熔断打开（进入冷却）。
     * [failureType] 为触发异常的类简名；打开计数与历史转换数同步递增。
     */
    fun markGuardedCircuitOpened(hookName: String, method: String, failureType: String) {
        guarded.markCircuitOpened(hookName, method, failureType)
        publishSnapshot()
    }

    /** 受防护 hook 半开探针成功、熔断恢复闭合。 */
    fun markGuardedCircuitRecovered(hookName: String) {
        guarded.markCircuitRecovered()
        publishSnapshot()
    }

    /**
     * 宿主抛出的预期异常被静默吞掉：仅递增计数并按最小间隔发布快照，
     * 不改变任何控制流。
     */
    fun markGuardedHostExceptionSwallowed() {
        if (guarded.markHostExceptionSwallowed()) {
            publishSnapshot()
        }
    }

    /** hooks callback binder 桥注册开始。 */
    fun markBridgeRegistering() {
        bridgeState = BRIDGE_STATE_REGISTERING
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /** hooks callback binder 桥注册成功：重置失败文本与重试进度。 */
    fun markBridgeRegistered() {
        bridgeState = BRIDGE_STATE_REGISTERED
        bridgeLastError = ""
        bridgeAttemptCount = 0
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /**
     * 重注册重试已调度（风暴抑制路径）。
     *
     * [attempt] 为当前已失败的尝试次数；下次实际尝试将由
     * [markBridgeRegistering] 走 REGISTERING 状态。
     */
    fun markBridgeRetryScheduled(attempt: Int) {
        bridgeState = BRIDGE_STATE_RETRYING
        bridgeAttemptCount = attempt
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /** hooks callback binder 桥注册失败（含重试路径）。 */
    fun markBridgeFailed(error: String) {
        bridgeState = BRIDGE_STATE_FAILED
        bridgeLastError = error.take(MAX_TEXT_LENGTH)
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /**
     * 记录一次模块进程（MediaProvider / LSPosed）运行期错误。
     *
     * 与 `Log.*` 的关系：**不是替代，而是补一条不依赖 logcat 的留痕**。
     * ColorOS `LOG_FLOWCTRL` 按进程配额丢日志（实测本进程单次会话丢 237 行），
     * 因此「日志里没有」不能证明「没发生」；本方法把错误写进
     * [HookErrorJournal]，随下一次 `publishSnapshot` 落进
     * `native_hook_status.json` 的 `hookErrors` 段，从而进入诊断包的
     * `status/native_hook_status_pretty.json` 与 `errors/hook_errors.jsonl`。
     *
     * 幂等性：同一 `(code, severity, subject, detail)` 在 30s 内合并计数，
     * 不会因高频告警把流水槽位刷满。
     *
     * @param code 必须引用 [me.gm.cleaner.core.common.err.ErrorCodes] 常量。
     * @param severity [HookErrorJournal.SEVERITY_WARN] / [SEVERITY_ERROR]。
     */
    fun markHookError(
        code: String,
        detail: String?,
        subject: String?,
        severity: String,
        errno: Int,
        generation: Long,
    ) {
        runCatching {
            HookErrorJournal.record(
                code = code,
                severity = severity,
                detail = detail,
                subject = subject,
                errno = errno,
                generation = generation,
            )
        }.onFailure {
            // 流水自身失败绝不阻断业务流程，也不得递归再记一次。
            Log.w(TAG, "markHookError failed for $code", it)
        }
        publishSnapshot()
    }

    /**
     * `mark*` 路径的发布入口：登记一次语义变更后按闸门判定是否落盘。
     *
     * 本方法在 29 个 `mark*` / `reset*` 调用点被触发，**不再**做无条件发布；
     * 被前沿合并窗口挡下的调用不会丢末态 —— 见 [NativeHookPublishGate]。
     */
    fun publishSnapshot() {
        if (!publishGate.onSemanticChange(SystemClock.elapsedRealtime())) {
            return
        }
        writeSnapshot()
    }

    /**
     * 轮询路径的发布入口：只做「补落盘」与「保活」，不登记新变更。
     *
     * 语义未变时，落盘周期由 [HEARTBEAT_REFRESH_MIN_INTERVAL_MILLIS] 决定；
     * 语义变更尚未成功落盘（如上一次写失败、或变更被合并窗口挡下）时会立即补一次。
     */
    fun publishSnapshotFromPoll() {
        if (!publishGate.onPoll(SystemClock.elapsedRealtime())) {
            return
        }
        writeSnapshot()
    }

    private fun writeSnapshot() {
        runCatching {
            val json = toJson()
            if (HookDataBusBridge.writeSnapshot(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS, json)) {
                HookDataBusBridge.signal(DataBusProtocol.SIGNAL_NATIVE_HOOK_STATUS_CHANGED)
                // 成功落盘才清「待落盘」标记；失败则保留，下一轮轮询会重试。
                publishGate.onPublishResult(succeeded = true)
            }
        }.onFailure {
            Log.w(TAG, "publishSnapshot failed", it)
        }
    }

    /** 记录 FUSE 事件前置去重的累计抑制条数（绝对值，非增量）。 */
    fun setFuseEventDedupedCount(total: Long) = fuse.setDedupedCount(total)

    /** 记录一次 FUSE 路径重定向评估的结果（热路径：只有原子自增与 volatile 写）。 */
    fun recordFuseRedirect(packageName: String, path: String, redirected: Boolean) =
        fuse.record(packageName, path, redirected)

    fun toJson(): String {
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("hookVersionCode", HookRuntimeConfig.VERSION_CODE)
            put("createdAt", System.currentTimeMillis())
            put("publisher", "NativeHookStatus")
            put("mediaProvider", JSONObject().apply {
                put("loaded", mediaProviderHookLoaded)
                put("packageName", mediaProviderPackageName)
            })
            put("policyCache", JSONObject().apply {
                put("initialized", policyCacheInitialized)
                put("initializedAt", policyCacheInitializedAt)
                put("lastFailureCode", policyCacheLastFailureCode)
                put("lastFailureGeneration", policyCacheLastFailureGeneration)
                put("lastError", policyCacheLastError)
                put("lastGoodGeneration", policyCacheLastGoodGeneration)
                policy.appendToPolicyCache(this)
            })
            put("bridgeRegistration", JSONObject().apply {
                put("state", bridgeState)
                put("lastError", bridgeLastError)
                put("attemptCount", bridgeAttemptCount)
                put("lastAttemptAt", bridgeLastAttemptAt)
            })
            put("guardedHooks", JSONObject().apply { guarded.appendTo(this) })
            put("inline", JSONObject().apply {
                put("state", inlineState)
                put("loaded", inlineLibraryLoaded)
                put("initialized", inlineHookInitialized)
                put("retryCount", inlineRetryCount)
                put("nextRetryAt", inlineNextRetryAt)
                put("retryExhausted", inlineRetryExhausted)
                put("disabledByPlatform", inlineDisabledByPlatform)
                put("lastFailureCode", inlineLastFailureCode)
                put("lastError", lastInlineError)
            })
            put("native", nativeStatus.toJson())
            put("policy", JSONObject().apply { policy.appendToPolicy(this) })
            put("fuseJavaGate", fuseJavaGateStatus.toJson())
            put("dedupedEvents", fuse.dedupedEvents)
            put("fuseRedirect", JSONObject().apply { fuse.appendRedirectTo(this) })
            put("readOnlyEval", JSONObject().apply {
                ReadOnlyEvalStats.snapshot().forEach { (k, v) -> put(k, v) }
            })
            put("inaccessibleEval", JSONObject().apply {
                InaccessibleEvalStats.snapshot().forEach { (k, v) -> put(k, v) }
            })
            put("hookErrors", JSONObject().apply {
                put("totalRecorded", HookErrorJournal.total())
                put("exportLimit", HOOK_ERROR_SNAPSHOT_LIMIT)
                put("slotCount", HookErrorJournal.size())
                put("entries", JSONArray().apply {
                    HookErrorJournal.snapshot().takeLast(HOOK_ERROR_SNAPSHOT_LIMIT).forEach { e ->
                        put(JSONObject().apply {
                            put("code", e.code)
                            put("severity", e.severity)
                            put("atElapsed", e.lastAtElapsed)
                            put("firstAtElapsed", e.firstAtElapsed)
                            put("count", e.count)
                            if (e.errno != 0) put("errno", e.errno)
                            e.subject?.let { put("subject", it) }
                            if (e.generation > 0L) put("generation", e.generation)
                            e.detail?.let { put("detail", it) }
                        })
                    }
                })
            })
        }.toString()
    }

    private fun deriveInlineState(status: NativeStatusSnapshot): String = when {
        !status.fuseAvailable -> STATE_DISABLED
        !status.fuseLibraryLoaded -> STATE_FUSE_WAITING
        status.fullAvailable -> STATE_HOOK_READY_FULL
        status.coreAvailable && status.startsWithHooked -> STATE_HOOK_READY_CORE
        status.coreAvailable -> STATE_HOOK_DEGRADED
        status.fuseLibraryLoaded -> STATE_HOOK_UNAVAILABLE
        inlineLibraryLoaded -> STATE_INLINE_LOADED
        else -> STATE_NOT_LOADED
    }

    private data class FuseJavaGateStatus(
        val discoveredCount: Int = 0,
        val hookedMethods: List<String> = emptyList(),
        val unknownMethods: List<String> = emptyList(),
        val failedMethods: List<String> = emptyList(),
        val skippedMethods: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("discoveredCount", discoveredCount)
            put("hookedCount", hookedMethods.size)
            put("unknownCount", unknownMethods.size)
            put("failedCount", failedMethods.size)
            put("skippedCount", skippedMethods.size)
            put("hookedMethods", JSONArray(hookedMethods))
            put("unknownMethods", JSONArray(unknownMethods))
            put("failedMethods", JSONArray(failedMethods))
            put("skippedMethods", JSONArray(skippedMethods))
        }
    }
}
