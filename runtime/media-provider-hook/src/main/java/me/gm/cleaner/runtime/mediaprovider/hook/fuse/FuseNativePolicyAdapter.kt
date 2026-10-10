package me.gm.cleaner.runtime.mediaprovider.hook.fuse

import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.runtime.mediaprovider.hook.InlineHookConfig
import me.gm.cleaner.runtime.mediaprovider.hook.policy.HookPolicyCache
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus

/**
 * FUSE Native Hook 的策略适配器。
 *
 * 负责将 DataBus 中的策略快照同步到 FUSE Native Hook (libinline.so)。
 *
 * ## 数据流
 * ```
 * DataBus configured_mount_points.json
 *   → HookPolicyCache.loadAndPushConfiguredMountPoints()
 *     → FuseNativePolicyAdapter.applyConfiguredMountPoints()
 *       → InlineHookConfig.setMountPoint() [JNI → native]
 * ```
 *
 * ## 刷新路径
 * [HookPolicyCache] 是 configured_mount_points 的唯一分发入口；
 * [HookPolicyRefreshScheduler] 只负责触发 cache 刷新和 native 初始化重试，
 * 不再保留第二条直接读取 DataBus 的分发路径。
 *
 * ## 降级行为
 * - DataBus 快照不可用 → keep 上次有效值（不主动清空 native mountPoint）
 * - 空数组 → 清空 native mountPoint（显式清空操作）
 */
object FuseNativePolicyAdapter {
    private const val TAG = "FuseNativePolicyAdapter"
    private const val MAX_INLINE_RETRY_COUNT = 10
    private val RETRY_DELAYS_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

    @Volatile
    private var inlineLibraryLoaded = false

    fun applyConfiguredMountPoints(
        points: Array<String>,
        generation: Long,
        redirectRevision: String,
    ) {
        NativeHookStatus.markMountPointsApplyStarted(redirectRevision)
        var unsupported = false
        try {
            if (disableInlineIfUnsupportedByPlatform()) {
                unsupported = true
                val unsupportedError = IllegalStateException(
                    "Native hook disabled by PlatformCapabilities, state=" +
                            NativeHookStatus.currentInlineState()
                )
                NativeHookStatus.markMountPointsApplyUnsupported(
                    redirectRevision,
                    points.size,
                    unsupportedError,
                )
                throw unsupportedError
            }
            if (!retryInlineHookInitialization()) {
                throw IllegalStateException(
                    "Native hook not ready, state=" + NativeHookStatus.currentInlineState()
                )
            }
            // 单次 JNI 调用下发三维度（挂载点集合 + 记录偏好 + BPF 拦截范围开关），
            // 消除分次调用间“新挂载点配旧偏好/旧开关”的不一致窗口。
            InlineHookConfig.commitPolicy(
                points,
                HookPolicyCache.recordExternalAppSpecificStorage,
                HookPolicyCache.fuseBpfBlockAll,
            )
            NativeHookStatus.markMountPointsApplySucceeded(
                generation,
                points.size,
                redirectRevision,
            )
            Log.i(TAG, "applyConfiguredMountPoints: count=${points.size}, generation=$generation")
        } catch (t: Throwable) {
            if (!unsupported) {
                NativeHookStatus.markMountPointsApplyFailed(
                    generation,
                    points.size,
                    redirectRevision,
                    t,
                )
            }
            Log.e(TAG, "applyConfiguredMountPoints failed: count=${points.size}, generation=$generation", t)
            throw t
        }
    }

    fun retryInlineHookInitializationIfDue() {
        if (disableInlineIfUnsupportedByPlatform()) {
            return
        }
        val now = System.currentTimeMillis()
        if (!NativeHookStatus.shouldRetryInlineInitialization(now)) {
            return
        }
        val retryCount = NativeHookStatus.currentInlineRetryCount()
        if (retryCount >= MAX_INLINE_RETRY_COUNT) {
            NativeHookStatus.markInlineRetryExhausted(
                "Inline hook initialization retry exhausted, state=" +
                        NativeHookStatus.currentInlineState()
            )
            return
        }
        try {
            val nativeStatus = initializeInlineHook()
            NativeHookStatus.markInlineLoadSucceeded(nativeStatus)
            if (NativeHookStatus.isInlinePolicyBridgeAvailable()) {
                // 记录偏好变化经全量刷新统一应用（commitPolicy 原子生效）。
                HookPolicyCache.tryRefreshNativeMountPoints(force = true)
            } else {
                scheduleNextInlineRetry(now, retryCount)
            }
        } catch (t: Throwable) {
            NativeHookStatus.markInlineLoadFailed(t)
            val delay = scheduleNextInlineRetry(now, retryCount)
            Log.w(TAG, "retryInlineHookInitializationIfDue failed, nextRetry=${delay}ms", t)
        }
    }

    fun initializeInlineHook(): String {
        ensureInlineLibraryLoaded()
        val statusJson = InlineHookConfig.initializeXHook()
        logNativeStatus(statusJson)
        reportNativeFailureIfAny(statusJson)
        return statusJson
    }

    /**
     * 把 native init() 返回的状态摘要打到 logcat。
     *
     * 存在的理由：`reportNativeFailureIfAny` 只把失败码写进 DataBus 快照，而
     * MediaProvider 进程的 DataBus 文件通道本身就不可用（见 [DataBus.lastInitFailureOrNull]），
     * 于是"FUSE native hook 为什么没起来"在现场日志里彻底不可见
     * （实测：整份 logcat 只有 DataBus 的 AccessDeniedException，没有任何 native lastError）。
     * 这里把关键字段直接落到 logcat，保证下次复现能一眼看到原因。
     */
    private fun logNativeStatus(statusJson: String) {
        val summary = summarizeNativeStatus(statusJson)
        when {
            !summary.connectFailed -> Log.i(TAG, "native init status: $summary")
            // 启动竞态：首轮探测常常**早于** MediaProvider 自己 dlopen(libfuse_jni.so)。
            // 实测 11:24:46.084 报 core unavailable / embeddedFuseJniFound=false，
            // 96 ms 后（46.180）即转为全绿。此时按 W 记录，会在现场日志里制造
            // "原生 hook 从未生效"的长期误判 —— 它是预期内的时序，不是故障。
            // 因此这一类降为 I 并在文案里点明；真正的失败（符号缺失 / GOT patch 失败）
            // 仍走下面的 W 分支。
            summary.notYetLoaded -> Log.i(
                TAG,
                "native init status (fuse JNI not loaded yet — expected at startup, retry pending): " +
                        "$summary",
            )
            else -> Log.w(TAG, "native init status (core unavailable): $summary")
        }
    }

    private fun summarizeNativeStatus(statusJson: String): NativeStatusSummary {
        val root = runCatching { org.json.JSONObject(statusJson) }.getOrNull()
            // 状态串本身就解析不出来 —— 这不是启动时序，属真异常，保持 W 语义。
            ?: return NativeStatusSummary(
                connectFailed = true,
                notYetLoaded = false,
                text = "unparsable: $statusJson",
            )
        // 原生 BuildHookStatusJson 只输出 fuseAvailable/fuseLibraryLoaded/hookMode/
        // embeddedFuseJniFound/xhookRefreshCalled/symbols/symbolMethods/lastError，
        // **从不输出顶层 coreAvailable / fullAvailable**。
        // 此前用 root.optBoolean("coreAvailable", false) 读取，默认值 false 必然生效，
        // 于是初始化成功（5 个符号全 true、methods 全 exact、lastError 为空）时依然打印
        // "coreAvailable=false, fullAvailable=false" 并被打上 "(core unavailable)" 标签，
        // 在现场日志里制造了"原生 hook 从未生效"的长期误判。
        // 这里改为与 NativeHookStatus.parseNativeStatus 完全一致的派生口径。
        val symbols = root.optJSONObject("symbols")
        val containsMountHooked = symbols?.optBoolean("containsMount", false) ?: false
        val coreAvailable = containsMountHooked
        val fullAvailable = containsMountHooked &&
                (symbols?.optBoolean("startsWith", false) ?: false) &&
                (symbols?.optBoolean("isFuseBpfEnabled", false) ?: false) &&
                (symbols?.optBoolean("fuseReqUserdata", false) ?: false) &&
                (symbols?.optBoolean("fuseBpfInstall", false) ?: false)
        // 区分「库还没被 MediaProvider 加载进来」（启动竞态，预期）与「库在但符号缺失」
        // （真故障）。前者由 [logNativeStatus] 降级为 I。
        val embeddedFuseJniFound = root.optBoolean("embeddedFuseJniFound", false)
        val text = buildString {
            append("coreAvailable=").append(coreAvailable)
            append(", fullAvailable=").append(fullAvailable)
            append(", hookMode=").append(root.optString("hookMode", "UNKNOWN"))
            append(", fuseAvailable=").append(root.optBoolean("fuseAvailable", true))
            append(", fuseLibraryLoaded=").append(root.optBoolean("fuseLibraryLoaded", false))
            append(", fuseLibraryName=").append(root.optString("fuseLibraryName", ""))
            append(", fuseJniLoadMode=").append(root.optString("fuseJniLoadMode", "UNKNOWN"))
            append(", embeddedFuseJniFound=").append(embeddedFuseJniFound)
            append(", symbols=").append(root.optJSONObject("symbols")?.toString() ?: "{}")
            append(", methods=").append(root.optJSONObject("symbolMethods")?.toString() ?: "{}")
            append(", missingSymbols=").append(root.optJSONArray("missingSymbols")?.toString() ?: "[]")
            append(", lastError=").append(root.optString("lastError", ""))
        }
        return NativeStatusSummary(
            connectFailed = !coreAvailable,
            notYetLoaded = !coreAvailable && !embeddedFuseJniFound,
            text = text,
        )
    }

    private data class NativeStatusSummary(
        val connectFailed: Boolean,
        /** FUSE JNI 尚未被目标进程加载（启动竞态），区别于「加载了但符号缺失」。 */
        val notYetLoaded: Boolean,
        val text: String,
    ) {
        override fun toString(): String = text
    }

    /**
     * 将 native init() 返回的 lastError 自由文本映射为 [ErrorCodes.HOOK_FUSE_*] 统一错误码，
     * 写入 NativeHookStatus.inline 段；未匹配的文本不强行归类。
     */
    private fun reportNativeFailureIfAny(statusJson: String) {
        val failureCode = mapNativeLastErrorToCode(statusJson) ?: return
        NativeHookStatus.markInlineNativeFailure(failureCode, extractNativeLastError(statusJson))
    }

    internal fun mapNativeLastErrorToCode(statusJson: String): String? {
        val root = runCatching { org.json.JSONObject(statusJson) }.getOrNull() ?: return null
        // 原生只输出 symbols.containsMount，不输出顶层 coreAvailable（详见 summarizeNativeStatus）。
        // 旧判断 root.optBoolean("coreAvailable", false) 恒为 false，等于这道"核心已就绪则不算失败"
        // 的短路从未生效；改为按 symbols.containsMount 判定。
        if (root.optJSONObject("symbols")?.optBoolean("containsMount", false) == true) {
            return null
        }
        val message = root.optString("lastError")
        return when {
            message.contains("dlopen", ignoreCase = true) ||
                    message.contains("symbol handle unavailable", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_LIB_LOAD_FAILED
            message.contains("GOT hook failed", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_GOT_PATCH_FAILED
            message.contains("failed to find", ignoreCase = true) ||
                    message.contains("symbols missing", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_SYMBOL_MISSING
            message.contains("FUSE not available", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_CAPABILITY_UNAVAILABLE
            else -> null
        }
    }

    private fun extractNativeLastError(statusJson: String): String = runCatching {
        org.json.JSONObject(statusJson).optString("lastError")
    }.getOrDefault("")

    @Synchronized
    private fun ensureInlineLibraryLoaded() {
        if (inlineLibraryLoaded) {
            return
        }
        System.loadLibrary("inline")
        inlineLibraryLoaded = true
    }

    private fun retryInlineHookInitialization(): Boolean {
        if (NativeHookStatus.isInlinePolicyBridgeAvailable()) {
            return true
        }
        return try {
            val nativeStatus = initializeInlineHook()
            NativeHookStatus.markInlineLoadSucceeded(nativeStatus)
            if (!NativeHookStatus.isInlinePolicyBridgeAvailable()) {
                val now = System.currentTimeMillis()
                if (NativeHookStatus.shouldRetryInlineInitialization(now)) {
                    scheduleNextInlineRetry(now, NativeHookStatus.currentInlineRetryCount())
                }
            }
            NativeHookStatus.isInlinePolicyBridgeAvailable()
        } catch (t: Throwable) {
            NativeHookStatus.markInlineLoadFailed(t)
            scheduleNextInlineRetry(System.currentTimeMillis(), NativeHookStatus.currentInlineRetryCount())
            Log.w(TAG, "retryInlineHookInitialization failed", t)
            false
        }
    }

    private fun disableInlineIfUnsupportedByPlatform(): Boolean {
        if (!HookPolicyCache.platformCapabilitiesLoaded) {
            return false
        }
        val fuseAvailable = HookPolicyCache.fuseAvailableFromCache
        val reason = when {
            !fuseAvailable -> "PlatformCapabilities reports FUSE unavailable"
            HookPolicyCache.supportedNativeHookModeFromCache == "NONE" ->
                "PlatformCapabilities reports native hook unsupported, " +
                        "fuseJniLoadMode=${HookPolicyCache.fuseJniLoadModeFromCache}"
            else -> null
        }
        if (reason == null) {
            if (NativeHookStatus.isInlineDisabledByPlatform()) {
                NativeHookStatus.markInlinePlatformSupported()
            }
            return false
        }
        if (!NativeHookStatus.isInlineDisabled()) {
            NativeHookStatus.markInlineDisabled(
                reason = reason,
                fuseAvailable = fuseAvailable,
                fuseJniLoadMode = HookPolicyCache.fuseJniLoadModeFromCache,
            )
        }
        return true
    }

    private fun scheduleNextInlineRetry(now: Long, retryCount: Int): Long {
        val nextRetryCount = retryCount + 1
        val delay = RETRY_DELAYS_MS[
                retryCount.coerceAtMost(RETRY_DELAYS_MS.size - 1)
        ]
        NativeHookStatus.markInlineRetryScheduled(nextRetryCount, now + delay)
        return delay
    }
}
