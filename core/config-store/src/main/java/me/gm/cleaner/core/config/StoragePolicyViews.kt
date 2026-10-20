package me.gm.cleaner.core.config

import java.util.regex.Pattern

/**
 * 存储策略读取视图：Store 直读加用户展开。
 *
 * P9 收敛：调用方经此读配置，不再经过含旧 JSON 双轨的门面。
 * 旧格式分享导出等互操作仍走旧适配器，不在此列。
 */
fun ConfiguredPolicyStore.getPackageSrZipped(packageName: String, userId: Int = 0): List<Pair<String, String>> =
    readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
        .map { expandPathAsUser(it.source, userId) to expandPathAsUser(it.target, userId) }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageSr(packageName: String, userId: Int): Pair<List<String>, List<String>> {
    val rules = readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
    return rules.map { expandPathAsUser(it.source, userId) } to
        rules.map { expandPathAsUser(it.target, userId) }
}

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageSrCount(packageName: String): Int =
    readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules?.size ?: 0

/** @see getPackageSrZipped */
val ConfiguredPolicyStore.srPackages: Set<String>
    get() = readRedirect().envelope.redirectPolicies
        .map { it.scope.packageName }.toSet()

/** 配置了只读规则的包名集合。 */
val ConfiguredPolicyStore.readOnlyPackages: Set<String>
    get() = readReadOnly().envelope.readOnlyRules
        .map { it.scope.packageName }.toSet()

/** @see getPackageSrZipped */
val ConfiguredPolicyStore.srRulesCount: Int
    get() = readRedirect().envelope.redirectPolicies.sumOf { it.rules.size }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getUninstalledSrPackages(installedPackages: Set<String>): List<String> {
    val packages = readRedirect().envelope.redirectPolicies
        .map { it.scope.packageName }.toSet()
    return (packages - installedPackages).toList()
}

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageReadOnly(packageName: String, userId: Int = 0): List<String> =
    readReadOnly().envelope.readOnlyRules
        .filter { it.scope.packageName == packageName }
        .map { expandPathAsUser(it.visiblePath, userId) }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getUninstalledReadOnlyPackages(installedPackages: Set<String>): List<String> {
    val packages = readReadOnly().envelope.readOnlyRules
        .map { it.scope.packageName }.toSet()
    return (packages - installedPackages).toList()
}

private val VIEW_APP_DATA_DIR_PATHS: Pattern by lazy {
    Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/)?([^/]+)?(/.*)?")
}

private fun expandPathAsUser(path: String, userId: Int): String {
    if (userId == 0) {
        return path
    }
    val matcher = VIEW_APP_DATA_DIR_PATHS.matcher(path)
    if (!matcher.matches()) {
        return path
    }
    val builder = StringBuilder()
    for (i in 1..matcher.groupCount()) {
        val group = matcher.group(i) ?: continue
        if (group.all { it.isDigit() }) {
            builder.append(userId)
        } else {
            builder.append(group)
        }
    }
    return builder.toString()
}


/**
 * 一次读取算出的规则计数索引。
 *
 * 为什么需要：`getPackageSrCount()` / `getPackageReadOnly()` 每次调用都要走
 * `readRedirect()` / `readReadOnly()` —— 即**重新读盘 + 解析 JSON + 计算 revision**。
 * 应用列表对 531 个包各调一次就是 1062 次全量解析（本机实测 `map=1450ms`，
 * 加载总耗时 1567ms）。批量场景一律走本索引：一次 [readSnapshot]，之后 O(1) 查表。
 */
class PolicyRuleCountIndex internal constructor(
    private val srCounts: Map<String, Int>,
    private val readOnlyCounts: Map<String, Int>,
) {
    fun srCount(packageName: String): Int = srCounts[packageName] ?: 0

    fun readOnlyCount(packageName: String): Int = readOnlyCounts[packageName] ?: 0

    /** 配置过任意规则（重定向或只读）的包名集合。 */
    val configuredPackages: Set<String>
        get() = srCounts.keys + readOnlyCounts.keys
}

/**
 * 构建规则计数索引。
 *
 * 用 [ConfiguredPolicyStore.readSnapshot] 而不是分别调 `readRedirect` / `readReadOnly`：
 * 前者在同一锁区间内读完两个文件，保证 revision 属于同一时刻，与逐包读取的语义一致。
 */
fun ConfiguredPolicyStore.policyRuleCountIndex(): PolicyRuleCountIndex {
    val snapshot = readSnapshot()
    val srCounts = HashMap<String, Int>()
    for (policy in snapshot.redirect.envelope.redirectPolicies) {
        srCounts[policy.scope.packageName] = policy.rules.size
    }
    val readOnlyCounts = HashMap<String, Int>()
    for (rule in snapshot.readOnly.envelope.readOnlyRules) {
        val name = rule.scope.packageName
        readOnlyCounts[name] = (readOnlyCounts[name] ?: 0) + 1
    }
    return PolicyRuleCountIndex(srCounts, readOnlyCounts)
}
