package me.gm.cleaner.runtime.mediaprovider.hook.fuse

/**
 * FUSE hook 安装前的 **uid 可解析性门**（纯逻辑，零 Android / Xposed 依赖）。
 *
 * ## 为什么需要这道门
 *
 * uid 是事件归属（packageName）与只读规则判定的**唯一**依据：
 * - 每个已注册的 handler（`fileOp` / `renameOp` / `openOp` / `multiArgConsistency` /
 *   `accessCheck` / `uidTracking`）都要用 uid；
 * - [`FuseJavaGate.resolveUid`] 只有两条取值路径：**静态** `roles.uidIndex` 与
 *   **运行时回退** `findCallingUid`（扫描 args 里 ≥ 10000 的 int）。
 *
 * 于是「签名里连一个 int 参数都没有」时，两条路径**都必然失败**——这不是启发式猜测，
 * 而是可证明的死 hook：它会吃掉 ColorOS 每进程约 300 行的日志配额，且不做任何事。
 *
 * ## 修复的真实缺陷（第 6 批设备证据）
 *
 * `exactRegistry` 里 `onfilecreatedforfuse → multiArgConsistency()`（需要 uid），
 * 而该方法的签名是 `onFileCreatedForFuse(String)`（**没有 int 参数**）。
 * 旧门只对**启发式**匹配生效（`if (!match.exact && roles.uidIndex < 0)`），
 * 并以"精确注册表条目已人工核对签名语义"为由豁免 —— 但这一条恰恰核对错了，于是：
 * `hookedMethods` 里出现它、每次调用打一条
 * `W … onFileCreatedForFuse cannot find uid — signature available in hook log` 后直接返回。
 *
 * 现在对**精确项**也做同一判据（仅在"可证明"时生效，不影响任何带 int 参数的条目）。
 *
 * @param exact `true` = 命中精确注册表，`false` = 启发式匹配
 * @param uidIndex 静态分析得到的 uid 下标，-1 表示需运行时推断
 * @param types 方法形参类型（`Method.getParameterTypes()`）
 * @return 非 null 即应跳过，字符串说明原因（进聚合日志用）
 */
internal fun skipReasonForUnresolvableUid(
    exact: Boolean,
    uidIndex: Int,
    types: Array<Class<*>>,
): String? {
    if (uidIndex >= 0) {
        return null
    }
    // 启发式匹配：静态定位不到 uid 时**不信任**运行时回退（回退是按值猜的），一律跳过。
    if (!exact) {
        return "heuristic match without uid parameter"
    }
    // 精确项：只有"可证明"时才跳过 —— 签名里没有任何 int，运行时回退无处可扫。
    // 若将来某 ROM 给该方法加了 int 参数，本判据自动失效、hook 恢复安装。
    if (!hasIntParameter(types)) {
        return "exact entry without any int parameter (uid provably unresolvable)"
    }
    return null
}

/** 形参里是否存在 `int` / `Integer`。运行时回退 `findCallingUid` 只扫这两种。 */
internal fun hasIntParameter(types: Array<Class<*>>): Boolean =
    types.any { it == Int::class.javaPrimitiveType || it == Integer::class.java }
