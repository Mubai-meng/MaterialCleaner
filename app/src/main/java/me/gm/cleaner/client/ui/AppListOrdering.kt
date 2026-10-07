package me.gm.cleaner.client.ui

/**
 * 应用列表排序所需的键。
 *
 * 抽成接口有两个目的：
 * 1. 让排序口径 [AppListOrdering] 完全不依赖 Android 框架，可以直接跑 JVM 单测；
 * 2. 排序过程中**零额外分配** —— 若改成传 `RuleKeys` 数据类，每次比较都会新建对象。
 *
 * ⚠️ `sortPackageName` 必须是唯一键（包名天然唯一），它是全序的兜底项。
 */
interface AppListSortable {
    /** 展示名（生产环境用 Collator 比较，中文按拼音序）。 */
    val sortLabel: String

    /** 包名。**全序兜底键**，参与比较的每一项都必须不同。 */
    val sortPackageName: String

    val sortLastUpdateTime: Long

    /** @see AppListModel.STATE_UNMOUNTED 等三态常量。 */
    val sortMountState: Int

    val sortMountRulesCount: Int

    val sortReadOnlyCount: Int
}

/**
 * 应用列表排序的**唯一**口径。
 *
 * 主键沿用首选项：挂载状态 → 规则数 → 用户选择的排序方式（应用名 / 更新时间）；
 * 末尾**无条件**追加「应用名 → 包名」兜底，使整个比较构成**全序**。
 *
 * 为什么必须有兜底：三层主键的取值都很稀疏 ——
 * 挂载状态只有 4 档、规则桶只有 3 档、`lastUpdateTime` 大量并列（同一批安装、
 * 系统应用常常时间戳相同），而 Collator 还会把同名、全半角、被当成忽略字符的
 * 名字判为相等。任何残余并列都会回落到 `PackageManager` 的返回顺序，
 * 而那个顺序**没有任何稳定性保证** —— 这正是主界面「已挂载应用顺序总是变」的来源。
 *
 * 加上兜底后本比较器满足全序：同一份数据无论以什么顺序喂进来，输出都逐项一致。
 */
object AppListOrdering {

    /** 规则桶：两类规则都有 = 2，只有一种 = 1，都没有 = 0。 */
    fun ruleBucket(mountRulesCount: Int, readOnlyCount: Int): Int =
        (if (mountRulesCount > 0) 2 else 0) + (if (readOnlyCount > 0) 1 else 0)

    /**
     * @param mountStateFirst 是否把挂载状态作为最高优先级
     * @param ruleCountFirst  是否把规则桶作为次高优先级
     * @param byUpdateTime    true = 按更新时间降序；false = 按应用名升序
     * @param compareLabel    应用名比较。生产环境传 Collator，单测可传 `String.compareTo`
     */
    fun <T : AppListSortable> comparator(
        mountStateFirst: Boolean,
        ruleCountFirst: Boolean,
        byUpdateTime: Boolean,
        compareLabel: (String, String) -> Int,
    ): Comparator<T> {
        val chain = ArrayList<Comparator<T>>(5)
        if (mountStateFirst) {
            chain += compareByDescending<T> { it.sortMountState }
        }
        if (ruleCountFirst) {
            chain += compareByDescending<T> {
                ruleBucket(it.sortMountRulesCount, it.sortReadOnlyCount)
            }
        }
        chain += if (byUpdateTime) {
            compareByDescending<T> { it.sortLastUpdateTime }
        } else {
            Comparator { o1, o2 -> compareLabel(o1.sortLabel, o2.sortLabel) }
        }
        // ↓ 以下两项与首选项无关：保证任意并列都不会漏到输入顺序上。
        chain += Comparator<T> { o1, o2 -> compareLabel(o1.sortLabel, o2.sortLabel) }
        chain += compareBy { it.sortPackageName }
        return chain.reduce { acc, comparator -> acc.thenComparing(comparator) }
    }

    /**
     * 主界面「已挂载应用」列表 = 设置过**任意**规则（重定向或只读）的应用。
     *
     * 只读规则同样是有效规则，只是不走 bind mount；若只按 `sortMountRulesCount`
     * 过滤，只配了只读规则的应用会整条消失。
     *
     * 过滤与排序放在一起，是为了让**所有**提交路径共用同一口径 ——
     * 两条路径各排各的会互相覆盖，列表看起来就是「总是变」。
     */
    fun <T : AppListSortable> mounted(comparator: Comparator<T>, list: List<T>): List<T> =
        list.filter { it.sortMountRulesCount > 0 || it.sortReadOnlyCount > 0 }
            .sortedWith(comparator)
}
