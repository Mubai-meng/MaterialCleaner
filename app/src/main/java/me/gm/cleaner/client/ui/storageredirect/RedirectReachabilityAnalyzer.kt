package me.gm.cleaner.client.ui.storageredirect

import me.gm.cleaner.core.storage.redirect.domain.MountPlanDeriver
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule

/**
 * 向导/界面可达性分析：无意义规则标灰与可达路径预览。
 *
 * P8 外迁：原 `MountRules.meaninglessRulesIndices/getAccessiblePlaces` 的搬运。
 * 运行时挂载/Hook/推导已改走规范解释器，不再经过这里；本对象仅服务界面展示，
 * 展示分析复用领域解释器。
 */
object RedirectReachabilityAnalyzer {

    fun mountedPath(rules: List<Pair<String, String>>, path: String): String {
        val domainRules = rules.map { (source, target) -> RedirectRule(source, target) }
        return MountPlanDeriver.resolveMountedPath(domainRules, path)
    }

    /**
     * 对最终映射毫无贡献的规则下标。
     *
     * 解释顺序是**后置优先**（见 [mountedPath] 与 OrderedRedirectInterpreter：
     * 先 `indexOfLast` 命中，再自该索引向尾部链式改写），因此：
     *
     * 1. 若本规则 target 被**后续**任一 target 覆盖（相等或更内层），则任何可能命中本规则的路径
     *    都必然先命中那条后续规则，本规则永远不会被执行 —— 真正无意义；
     * 2. 若把本规则加入后，其自身 target 的落点没有任何变化，说明它是不改变映射的空操作。
     *
     * ⚠️ 方向不可写反。反过来判"后续 target 落在本规则 target 之内"会把
     * "通用重定向 + carve-out（例外）"这类规则集的**通用规则**误判为无意义 ——
     * 而它恰恰是整组规则里真正起作用的那一条（向导 q1 的输出即为此形态）。
     * 该反向写法会同时造成**误报与漏报**，故此处必须保持 `startsWithPath(target, it)`。
     */
    fun redundantIndices(rules: List<Pair<String, String>>): List<Int> {
        val targets = rules.map { it.second }
        val indices = mutableListOf<Int>()
        for (i in targets.indices) {
            val target = targets[i]
            if (targets.subList(i + 1, targets.size).any { startsWithPath(target, it) } ||
                mountedPath(rules.subList(0, i), target) ==
                mountedPath(rules.subList(0, i + 1), target)
            ) {
                indices += i
            }
        }
        return indices
    }

    fun accessiblePlaces(rules: List<Pair<String, String>>, path: String): List<String> {
        val effective = rules.toMutableList().apply {
            redundantIndices(rules).asReversed().forEach { index ->
                removeAt(index)
            }
        }
        val paths = mutableListOf<String>()
        if (effective.unzip().second.none { startsWithPath(it, path) }) {
            paths += path
        }
        for (i in effective.indices) {
            val (source, target) = effective[i]
            if (startsWithPath(source, path)) {
                val maybeAccessiblePath = target + path.substring(source.length)
                if (maybeAccessiblePath ==
                    mountedPath(effective.subList(i + 1, effective.size), maybeAccessiblePath)
                ) {
                    if (maybeAccessiblePath !in paths) {
                        paths += maybeAccessiblePath
                    }
                }
            }
        }
        return paths
    }

    private fun startsWithPath(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith(ensureTrailingSeparator(prefix))

    private fun ensureTrailingSeparator(path: String): String =
        if (path.endsWith('/')) path else "$path/"
}
