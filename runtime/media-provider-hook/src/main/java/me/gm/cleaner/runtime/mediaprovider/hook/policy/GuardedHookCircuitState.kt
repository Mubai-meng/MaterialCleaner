package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONObject

/**
 * 受防护 hook（`AbstractGuardedHook` 子类）的熔断观测状态。
 *
 * 职责边界：只维护熔断计数与最近失败事实，**不发布快照**（发布由
 * [NativeHookStatus] 统一负责），也不做熔断判定（判定在宿主侧
 * `AbstractGuardedHook` / `HookHandlerCircuitBreaker`）。
 *
 * 与 [NativeHookStatus] 分离的动因：后者聚合五个观测域并负责快照组装；
 * 熔断观测是其中一个独立子域，把它连同序列化就地集中，聚合类只保留编排。
 */
internal class GuardedHookCircuitState {
    @Volatile
    private var circuitOpenCount = 0

    @Volatile
    private var lastFailedHook = ""

    @Volatile
    private var lastFailedMethod = ""

    @Volatile
    private var lastFailureType = ""

    @Volatile
    private var totalOpenTransitions = 0L

    @Volatile
    private var recoveredCount = 0L

    @Volatile
    private var swallowedHostExceptions = 0L

    @Volatile
    private var swallowedLastPublishAt = 0L

    /** 熔断打开（进入冷却）：打开计数与历史转换数同步递增。 */
    fun markCircuitOpened(hookName: String, method: String, failureType: String) {
        circuitOpenCount += 1
        totalOpenTransitions += 1
        lastFailedHook = hookName
        lastFailedMethod = method
        lastFailureType = failureType.take(NativeHookStatus.MAX_TEXT_LENGTH)
    }

    /** 半开探针成功、熔断恢复闭合。 */
    fun markCircuitRecovered() {
        if (circuitOpenCount > 0) {
            circuitOpenCount -= 1
        }
        recoveredCount += 1
    }

    /**
     * 宿主抛出的预期异常被静默吞掉：仅递增计数。
     *
     * @return true 表示已过最小发布间隔，调用方应发布一次快照。
     *         该路径可能被宿主高频触发（如 query 对特定参数抛
     *         IllegalArgumentException），因此不能每次记账都发布。
     */
    fun markHostExceptionSwallowed(): Boolean {
        swallowedHostExceptions += 1
        val now = System.currentTimeMillis()
        if (now - swallowedLastPublishAt < NativeHookStatus.SWALLOWED_PUBLISH_INTERVAL_MILLIS) {
            return false
        }
        swallowedLastPublishAt = now
        return true
    }

    /** 快照序列化：`guardedHooks` 段。 */
    fun appendTo(json: JSONObject) {
        json.put("circuitOpenCount", circuitOpenCount)
        json.put("lastFailedHook", lastFailedHook)
        json.put("lastFailedMethod", lastFailedMethod)
        json.put("lastFailureType", lastFailureType)
        json.put("totalOpenTransitions", totalOpenTransitions)
        json.put("recoveredCount", recoveredCount)
        json.put("swallowedHostExceptions", swallowedHostExceptions)
    }
}
