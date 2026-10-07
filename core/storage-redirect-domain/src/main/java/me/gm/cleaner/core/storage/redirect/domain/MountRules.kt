package me.gm.cleaner.core.storage.redirect.domain

/**
 * Storage redirect mount rule calculator.
 *
 * This class is intentionally free of Android, Binder, DataBus and preference
 * dependencies. It contains only the path calculation that all redirect layers
 * must agree on.
 */
class MountRules {
    private lateinit var ruleZipped: List<Pair<String, String>>
    private lateinit var rule: Pair<List<String>, List<String>>

    constructor(ruleZipped: List<Pair<String, String>>) {
        this.ruleZipped = ruleZipped
    }

    constructor(rule: Pair<List<String>, List<String>>) {
        this.rule = rule
    }

    private fun ensureRuleZipped(): List<Pair<String, String>> {
        if (!::ruleZipped.isInitialized) {
            ruleZipped = rule.first.zip(rule.second)
        }
        return ruleZipped
    }

    private fun ensureRule(): Pair<List<String>, List<String>> {
        if (!::rule.isInitialized) {
            rule = ruleZipped.unzip()
        }
        return rule
    }

    val sources: List<String>
        get() = ensureRule().first

    val targets: List<String>
        get() = ensureRule().second

    fun isEmpty(): Boolean =
        ::ruleZipped.isInitialized && ruleZipped.isEmpty() ||
                ::rule.isInitialized && rule.first.isEmpty()

    val mountPoint: List<String>
        get() {
            val mkdirList = mutableListOf<String>()
            val mountedList = mutableListOf<Pair<String, String>>()
            ensureRuleZipped().forEach { (source, target) ->
                mkdirList.add(getMountedPath(mountedList, target))
                mountedList.add(Pair(source, target))
            }
            return mkdirList
        }

    /**
     * 对最终映射毫无贡献的规则下标。
     *
     * 解释顺序是**后置优先**（见 [getMountedPath] 与 OrderedRedirectInterpreter：
     * 先 `indexOfLast` 命中，再自该索引向尾部链式改写），因此：
     *
     * 1. 若本规则 target 被**后续**任一 target 覆盖（相等或更内层），则任何可能命中本规则的路径
     *    都必然先命中那条后续规则，本规则永远不会被执行 —— 真正无意义；
     * 2. 若把本规则加入后，其自身 target 的落点没有任何变化，说明它是不改变映射的空操作。
     *
     * ⚠️ 方向不可写反。反过来判"后续 target 落在本规则 target 之内"会把
     * "通用重定向 + carve-out（例外）"这类规则集的**通用规则**误判为无意义 ——
     * 而它恰恰是整组规则里真正起作用的那一条（向导 q1 的输出即为此形态）。
     */
    val meaninglessRulesIndices: List<Int>
        get() {
            val ruleZipped = ensureRuleZipped()
            val indices = mutableListOf<Int>()
            for (i in targets.indices) {
                val target = targets[i]
                if (targets.subList(i + 1, targets.size).any { startsWithPath(target, it) } ||
                    getMountedPath(ruleZipped.subList(0, i), target) ==
                    getMountedPath(ruleZipped.subList(0, i + 1), target)
                ) {
                    indices += i
                }
            }
            return indices
        }

    private fun getMountedPath(ruleZipped: List<Pair<String, String>>, path: String): String {
        val fileSystemLastMatch = ruleZipped.indexOfLast { (_, target) ->
            startsWithPath(path, target)
        }
        if (fileSystemLastMatch == -1) {
            return path
        }

        var mountedPath = path
        ruleZipped.subList(fileSystemLastMatch, ruleZipped.size).forEach { (source, target) ->
            if (startsWithPath(mountedPath, target)) {
                mountedPath = source + mountedPath.substring(target.length)
            }
        }
        return mountedPath
    }

    fun getMountedPath(path: String): String = getMountedPath(ensureRuleZipped(), path)

    fun getAccessiblePlaces(path: String): List<String> {
        val ruleZipped = ensureRuleZipped().toMutableList().apply {
            meaninglessRulesIndices.asReversed().forEach { index ->
                removeAt(index)
            }
        }
        val paths = mutableListOf<String>()
        if (ruleZipped.unzip().second.none { startsWithPath(it, path) }) {
            paths += path
        }
        for (i in ruleZipped.indices) {
            val (source, target) = ruleZipped[i]
            if (startsWithPath(source, path)) {
                val maybeAccessiblePath = target + path.substring(source.length)
                if (maybeAccessiblePath ==
                    getMountedPath(ruleZipped.subList(i + 1, ruleZipped.size), maybeAccessiblePath)
                ) {
                    if (maybeAccessiblePath !in paths) {
                        paths += maybeAccessiblePath
                    }
                }
            }
        }
        return paths
    }

    companion object {
        fun startsWithPath(path: String, prefix: String): Boolean =
            path == prefix || path.startsWith(ensureTrailingSeparator(prefix))

        private fun ensureTrailingSeparator(path: String): String =
            if (path.endsWith('/')) path else "$path/"
    }
}
