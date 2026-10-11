package me.gm.cleaner.client.ui

/**
 * 应用列表加载顺序的纯策略（无 android 依赖，可 JVM 单测）。
 *
 * ## 背景：主界面「已配置应用」为什么慢半拍
 *
 * 旧实现把列表加载串在服务恢复**之后**：
 *
 * ```
 * loadApps() {
 *     ServerStateMachine.recoverIfTargetRunning()   // ← 阻塞
 *     loadAppsCommon()
 * }
 * ```
 *
 * 而 `recoverIfTargetRunning` 在服务不存活时会走完整启动流程：
 * 校验 root → kill 旧进程 → `delay(1000)` → 写数据文件 → `shell app_process` →
 * `waitForBinder(20 × 500ms)`。慢路径最坏 **10s 以上**，
 * 而"已配置应用"列表只依赖本地配置（`ConfiguredPolicyStoreProvider` 的规则计数）
 * 加 `PackageManager` 的应用名，**根本不需要服务在线**。把两件事串起来，
 * 等于让一个本可立即渲染的列表去等服务启动 —— 这就是"慢半拍"。
 *
 * ## 新顺序
 *
 * 1. 先 `loadAppsCommon()`：服务不可用就走本地降级，列表立刻出得来；
 * 2. 再 `recoverIfTargetRunning()`：不再阻塞列表；
 * 3. 只有「首轮是本地降级」**且**「恢复成功」时才补一次全量重载。
 */
internal object AppListLoadPlan {

    /**
     * 是否需要在服务恢复成功后补一次全量重载。
     *
     * 两个条件缺一不可：
     * - 首轮已是全量 ⇒ 再刷一次只会让列表闪一下（用户看得见）；
     * - 恢复失败 ⇒ 服务仍不可用，重载还会拿到同样的降级结果，白跑一遍全量扫描。
     */
    fun shouldReloadAfterRecovery(
        firstResultIsFullList: Boolean,
        recoverySucceeded: Boolean,
    ): Boolean = !firstResultIsFullList && recoverySucceeded
}
