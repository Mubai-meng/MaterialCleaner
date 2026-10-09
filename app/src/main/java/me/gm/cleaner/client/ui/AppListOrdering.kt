package me.gm.cleaner.client.ui

import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.util.collatorComparator

/**
 * AppList 展示层排序全序（P1-B）。
 *
 * 职责归属：只负责展示排序，不改变业务优先级。优先级高→低：
 * mountState desc（如启用）→ ruleScore desc（如启用）
 * → base（sortBy == SORT_BY_UPDATE_TIME 时 updateTime desc，否则 label collator）
 * → label collator → packageName。
 *
 * 单比较器一次 sortedWith 替代此前多轮 stable sort：旧链路在三项全相等时
 * 保留原列表顺序（输入乱序则输出不一致），此处以 label collator → packageName
 * 兜底保证全序。
 */
private val appLabelComparator: Comparator<AppListModel> = collatorComparator { it.label }

/** 与旧排序一致的规则分：有挂载规则 +2，有只读规则 +1。 */
fun ruleScoreOf(model: AppListModel): Int =
    (if (model.mountRulesCount > 0) 2 else 0) + (if (model.readOnlyCount > 0) 1 else 0)

/**
 * @param sortBy 与 ServicePreferences.SORT_BY_* 同值；用 Int 而非直接读偏好，
 * 保持纯函数 JVM 可测。
 */
fun buildAppListComparator(
    sortBy: Int,
    ruleCountEnabled: Boolean,
    mountStateEnabled: Boolean
): Comparator<AppListModel> = Comparator { a, b ->
    if (mountStateEnabled) {
        val r = b.mountState.compareTo(a.mountState)
        if (r != 0) return@Comparator r
    }
    if (ruleCountEnabled) {
        val r = ruleScoreOf(b).compareTo(ruleScoreOf(a))
        if (r != 0) return@Comparator r
    }
    if (sortBy == ServicePreferences.SORT_BY_UPDATE_TIME) {
        val r = b.packageInfo.lastUpdateTime.compareTo(a.packageInfo.lastUpdateTime)
        if (r != 0) return@Comparator r
    } else if (sortBy != ServicePreferences.SORT_BY_NAME) {
        // 与旧多轮排序一致：未知 sortBy 显式抛错，不静默兜底。
        throw IllegalArgumentException("Unknown sortBy=$sortBy")
    }
    // 名称排序的 base 即 label collator，与全序兜底同一步，无需重复比较。
    val r = appLabelComparator.compare(a, b)
    if (r != 0) return@Comparator r
    a.packageInfo.packageName.compareTo(b.packageInfo.packageName)
}
