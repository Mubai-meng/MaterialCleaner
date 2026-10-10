package me.gm.cleaner.core.storage.redirect.domain

/**
 * VFS 执行所需的已投影挂载计划。
 *
 * [mountPoints] 由规范解释器推导；输入契约见 [MountPlanDeriver]。
 */
data class RuntimeMountPlan(
    val packageName: String,
    val userId: Int,
    val sources: List<String>,
    val targets: List<String>,
    val mountPoints: List<String>,
    /**
     * 被拒绝的「自包含」规则对的**派生挂载位置**（**只看不改**：仅用于诊断）。
     *
     * 见 [MountPlanDeriver.isSelfContainedMount] —— 派生挂载位置落在自己 source 子树内时，
     * 这条规则对**不可能**被无副作用地实现，故从 [sources]/[targets]/[mountPoints]
     * 与 [mkdirList] 中全部剔除。这里保留那些派生路径，让调用方能把"为什么某条规则没生效"
     * 说出来，而不是静默丢弃（静默丢弃是本模块最危险的失效形态）。
     */
    val selfContainedPoints: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = sources.isEmpty()

    /** Mounter 建目录所需：挂载点推导结果 + 承载源目录。 */
    val mkdirList: List<String>
        get() = mountPoints + sources
}

/**
 * 快照裸规则对到挂载计划的唯一投影桥。
 *
 * 输入契约：规则对由投影器与钩子解析期过滤为规范路径后进入；
 * 查询路径非规范时原样返回，永不抛异常。旧解释器已彻底退役。
 */
object MountPlanDeriver {

    fun derive(
        packageName: String,
        userId: Int,
        rules: List<RedirectRule>,
    ): RuntimeMountPlan? {
        if (rules.isEmpty()) return null
        val pairs = validPairs(rules.map { it.source to it.target })
        if (pairs.isEmpty()) return null

        // 自包含判定必须看**派生后**的挂载位置，不能看规则声明的 target：
        // 真机那条 `X → X` 恒等 carve-out 声明的 target 就是 X（看着完全无害），
        // 只有把它按前缀解析到 `X/cache/Android/data/<pkg>` 之后才暴露问题。
        val derived = OrderedRedirectInterpreter.deriveMountPoints(toOrderedRules(pairs))
            .map(RedirectMountPoint::derivedPath)
        val kept = mutableListOf<Pair<String, String>>()
        val selfContained = mutableListOf<String>()
        pairs.forEachIndexed { index, pair ->
            val derivedPath = derived[index]
            if (isSelfContainedMount(pair.first, derivedPath)) {
                selfContained += derivedPath
            } else {
                kept += pair
            }
        }

        // 挂载点按**剔除后**的实际序列重算，保证顺序语义与真正下发的挂载一致
        // （剔除一条规则会改变后续规则的前缀解析上下文）。
        return RuntimeMountPlan(
            packageName = packageName,
            userId = userId,
            sources = kept.map { it.first },
            targets = kept.map { it.second },
            mountPoints = deriveMountPoints(kept),
            selfContainedPoints = selfContained,
        )
    }

    fun resolveMountedPath(rules: List<RedirectRule>, path: String): String {
        if (rules.isEmpty()) return path
        if (!OrderedRedirectInterpreter.isCanonicalAbsolutePath(path)) return path
        val zipped = rules.map { it.source to it.target }
        return OrderedRedirectInterpreter.interpret(path, toOrderedRules(zipped)).derivedPath
    }

    private fun deriveMountPoints(zipped: List<Pair<String, String>>): List<String> =
        OrderedRedirectInterpreter.deriveMountPoints(toOrderedRules(zipped))
            .map(RedirectMountPoint::derivedPath)

    /**
     * 该规则对是否「自包含」——**派生后的挂载位置**落在自己 source 的子树内。
     *
     * 参数是 `(rule.source, 该规则的派生挂载点)`（`deriveMountPoints` 的 `derivedPath`），
     * **不是**规则声明的 target。恒等 carve-out（`X → X`）声明上完全无害，
     * 只有前缀解析后才会暴露成 `X/cache/Android/data/<pkg>`。
     *
     * ## 为什么必须拒绝（可证明，不是启发式）
     *
     * 执行语义是 `mount(source, target)`（native 侧 `bind_mount_result`），
     * 且同一份计划内的挂载点**按列表顺序**施加到目标进程的 mount namespace。
     * 于是当 `target` 位于 `source` 之内时只剩两种可能，**都不成立**：
     *
     * 1. **源解析塌到目标上**：若同一计划里有更早的规则把 `source` 的祖先覆盖过，
     *    后施加的 `source` 在这个 namespace 里已经解析成 `target` 本身 ⇒ 退化为
     *    `mount(A, A)` ⇒ `EINVAL`/`EBUSY`，一次注定失败的挂载（并把该进程标成"挂载异常"）。
     * 2. **形成挂载环**：若源按宿主视图解析成功，则把一个目录 bind 到自己的后代路径上
     *    ⇒ 任何递归遍历（`find`、文件管理器、缓存清理、媒体扫描）都会**无限下降**，
     *    在用户自己的数据目录里制造指数级路径膨胀。
     *
     * 真机实证（2026-10-10）：包 `lfyunf.uvpdv.dr8a5.mpbjcuh.jfy` 的规则是
     * `X/cache → /storage/emulated/0` 与 `X → X`（恒等 carve-out），
     * 后者按前缀解析出的挂载点是 `X/cache/Android/data/<pkg>` —— 正好落在 `X` 之内。
     * 该配置**本身自相矛盾**（"把 /storage/emulated/0 整个搬进 X/cache"却又"保持 X 不改"，
     * 而 X ⊂ /storage/emulated/0 且 X/cache ⊂ X），任何忠实实现都必然踩上面两条之一。
     *
     * ## 边界
     *
     * - `derivedPath == source`（恒等）**不**算自包含：`mount(A, A)` 是合法且无副作用的空转，
     *   保留它可以维持既有 oracle 断言与快照形状。
     * - 仅比较**规范化**路径（调用点已过滤），两侧都无尾部分隔符，故用 `"$source/"` 前缀判定，
     *   不会把 `/a/bc` 误判成 `/a/b` 的后代。
     */
    fun isSelfContainedMount(source: String, derivedPath: String): Boolean =
        derivedPath.startsWith("$source/")

    /** 有效规则对：三字段同源的前提，非规范与空白直接丢弃。 */
    private fun validPairs(zipped: List<Pair<String, String>>): List<Pair<String, String>> =
        zipped.filter { (source, target) ->
            source.isNotBlank() && target.isNotBlank() &&
                OrderedRedirectInterpreter.isCanonicalAbsolutePath(source) &&
                OrderedRedirectInterpreter.isCanonicalAbsolutePath(target)
        }

    private fun toOrderedRules(zipped: List<Pair<String, String>>): List<OrderedRedirectRule> =
        validPairs(zipped).mapIndexed { index, (source, target) ->
            OrderedRedirectRule(
                ruleId = RuleId("mount-plan-${(source + target).hashCode()}"),
                type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
                source = source,
                target = target,
                orderIndex = index,
            )
        }
}
