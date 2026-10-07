package me.gm.cleaner.client.ui

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AppListOrdering] 的口径锁。
 *
 * 核心断言是**全序**：同一组数据无论以什么顺序喂进来，输出都必须逐项一致。
 * 旧实现（三层独立的稳定排序）做不到这一点 —— 挂载状态只有 4 档、规则桶只有 3 档、
 * 更新时间大量并列，残余并列会一路漏到 `PackageManager` 的返回顺序上，
 * 而那正是主界面「已挂载应用顺序总是变」的根因。
 */
class AppListOrderingTest {

    /** 纯 Kotlin 假实现：不碰 Android 框架，测试可在 JVM 上直接跑。 */
    private data class App(
        override val sortLabel: String,
        override val sortPackageName: String,
        override val sortLastUpdateTime: Long = 0L,
        override val sortMountState: Int = 0,
        override val sortMountRulesCount: Int = 0,
        override val sortReadOnlyCount: Int = 0,
    ) : AppListSortable

    private fun comparator(
        mountStateFirst: Boolean = true,
        ruleCountFirst: Boolean = true,
        byUpdateTime: Boolean = false,
    ): Comparator<App> = AppListOrdering.comparator(
        mountStateFirst = mountStateFirst,
        ruleCountFirst = ruleCountFirst,
        byUpdateTime = byUpdateTime,
        compareLabel = { o1, o2 -> o1.compareTo(o2) },
    )

    private fun packages(list: List<App>) = list.map { it.sortPackageName }

    @Test
    fun `比较器构成全序_任意输入顺序结果一致`() {
        val comparator = comparator()
        val base = listOf(
            App("微信", "com.tencent.mm", 100L, 1, 3, 0),
            App("支付宝", "com.eg.android.AlipayGphone", 100L, 1, 3, 1),
            App("淘宝", "com.taobao.taobao", 90L, 1, 2, 0),
            App("知乎", "com.zhihu.android", 90L, 0, 2, 0),
            App("微博", "com.sina.weibo", 80L, 1, 0, 1),
            App("什么值得买", "com.smzdm.client.android", 80L, 0, 0, 1),
        )
        val expected = packages(base.sortedWith(comparator))

        assertEquals(expected, packages(base.reversed().sortedWith(comparator)))
        for (seed in 0 until 50) {
            val shuffled = base.shuffled(Random(seed))
            assertEquals("seed=$seed", expected, packages(shuffled.sortedWith(comparator)))
        }
    }

    @Test
    fun `应用名并列时由包名兜底`() {
        // 两个应用标签完全相同（Collator 判等，旧实现会漏到输入顺序上）
        val comparator = comparator(mountStateFirst = false, ruleCountFirst = false)
        val aaa = App("同名应用", "com.aaa.first")
        val zzz = App("同名应用", "com.zzz.second")

        assertEquals(
            listOf("com.aaa.first", "com.zzz.second"),
            packages(listOf(zzz, aaa).sortedWith(comparator))
        )
    }

    @Test
    fun `挂载状态优先于规则数与应用名`() {
        val comparator = comparator()
        // 未挂载但应用名靠前、规则最多
        val unmounted = App("A", "com.a", 999L, AppListModel.STATE_UNMOUNTED, 9, 9)
        // 已挂载但应用名靠后、没有任何规则
        val mounted = App("Z", "com.z", 0L, AppListModel.STATE_MOUNTED, 0, 0)

        assertEquals(
            listOf("com.z", "com.a"),
            packages(listOf(unmounted, mounted).sortedWith(comparator))
        )
    }

    @Test
    fun `规则桶降序_组内按应用名升序`() {
        val comparator = comparator(mountStateFirst = false)
        val both = App("B", "com.b", 0L, 0, 1, 1) // 桶 = 3
        val mountOnly = App("C", "com.c", 0L, 0, 1, 0) // 桶 = 2
        val readOnly = App("A", "com.a", 0L, 0, 0, 1) // 桶 = 1

        assertEquals(
            listOf("com.b", "com.c", "com.a"),
            packages(listOf(readOnly, mountOnly, both).sortedWith(comparator))
        )
    }

    @Test
    fun `mounted_保留只读规则应用并按统一口径排序`() {
        val comparator = comparator(mountStateFirst = false)
        val list = listOf(
            App("零规则", "com.none", 0L, 0, 0, 0), // 无任何规则 → 必须被过滤掉
            App("C应用", "com.c", 0L, 0, 2, 0), // 桶 = 2
            App("B应用", "com.b", 0L, 0, 0, 1), // 桶 = 1，只有只读规则
        )

        assertEquals(
            listOf("com.c", "com.b"),
            packages(AppListOrdering.mounted(comparator, list))
        )

        // 同一份数据换个输入顺序，结果必须一致
        assertEquals(
            listOf("com.c", "com.b"),
            packages(AppListOrdering.mounted(comparator, list.reversed()))
        )
    }

    @Test
    fun `按更新时间排序时应用名仍作最终兜底`() {
        val comparator = comparator(byUpdateTime = true)
        val older = App("Z", "com.z", 1L)
        val newer = App("A", "com.a", 2L)
        assertEquals(
            listOf("com.a", "com.z"),
            packages(listOf(older, newer).sortedWith(comparator))
        )

        // 时间戳并列（同一批安装/系统应用）→ 回落应用名，而不是包管理器返回序
        val sameA = App("A", "com.same.a", 5L)
        val sameZ = App("Z", "com.same.z", 5L)
        assertEquals(
            listOf("com.same.a", "com.same.z"),
            packages(listOf(sameZ, sameA).sortedWith(comparator))
        )
    }

    @Test
    fun `ruleBucket 取值`() {
        assertEquals(3, AppListOrdering.ruleBucket(1, 1))
        assertEquals(2, AppListOrdering.ruleBucket(3, 0))
        assertEquals(1, AppListOrdering.ruleBucket(0, 2))
        assertEquals(0, AppListOrdering.ruleBucket(0, 0))
    }
}
