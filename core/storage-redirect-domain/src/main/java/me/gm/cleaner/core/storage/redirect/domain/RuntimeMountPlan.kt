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
        val zipped = rules.map { it.source to it.target }
        return RuntimeMountPlan(
            packageName = packageName,
            userId = userId,
            sources = zipped.map { it.first },
            targets = zipped.map { it.second },
            mountPoints = deriveMountPoints(zipped),
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

    private fun toOrderedRules(zipped: List<Pair<String, String>>): List<OrderedRedirectRule> =
        zipped.filter { (source, target) ->
            source.isNotBlank() && target.isNotBlank() &&
                OrderedRedirectInterpreter.isCanonicalAbsolutePath(source) &&
                OrderedRedirectInterpreter.isCanonicalAbsolutePath(target)
        }.mapIndexed { index, (source, target) ->
            OrderedRedirectRule(
                ruleId = RuleId("mount-plan-${(source + target).hashCode()}"),
                type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
                source = source,
                target = target,
                orderIndex = index,
            )
        }
}
