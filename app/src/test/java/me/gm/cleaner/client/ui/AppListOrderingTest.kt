package me.gm.cleaner.client.ui

import android.content.pm.PackageInfo
import me.gm.cleaner.core.config.ServicePreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * AppListOrdering 纯 JVM 单测：只测比较器，不依赖 Android 运行时。
 *
 * ServicePreferences.SORT_BY_* 为 const，会内联为 Int 常量，
 * 单测运行时不会加载 ServicePreferences 类。
 */
class AppListOrderingTest {

    private fun modelOf(
        packageName: String,
        label: String = packageName,
        lastUpdateTime: Long = 0L,
        mountRulesCount: Int = 0,
        readOnlyCount: Int = 0,
        mountState: Int = AppListModel.STATE_UNMOUNTED
    ): AppListModel = AppListModel(
        packageInfo = packageInfoOf(packageName, lastUpdateTime),
        label = label,
        mountRulesCount = mountRulesCount,
        readOnlyCount = readOnlyCount,
        mountState = mountState
    )

    private fun packageInfoOf(packageName: String, lastUpdateTime: Long): PackageInfo {
        val info = try {
            PackageInfo()
        } catch (e: RuntimeException) {
            // 纯 JVM 单测下 android.jar 为 stub，直接构造抛 "Stub!"，用 Unsafe 绕过构造器。
            // 全反射写法：避免编译期引用 sun.misc，保证各 JDK 下编译通过。
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateInstance =
                unsafeClass.getMethod("allocateInstance", Class::class.java)
            allocateInstance.invoke(unsafe, PackageInfo::class.java) as PackageInfo
        }
        info.packageName = packageName
        info.lastUpdateTime = lastUpdateTime
        return info
    }

    private fun List<AppListModel>.names(): List<String> =
        map { it.packageInfo.packageName }

    @Test
    fun `同label时间规则状态下packageName决定顺序`() {
        val models = listOf("com.c", "com.a", "com.b")
            .map { modelOf(it, label = "Same", lastUpdateTime = 1000L) }
        val byName = buildAppListComparator(
            ServicePreferences.SORT_BY_NAME, ruleCountEnabled = true, mountStateEnabled = true
        )
        assertEquals(listOf("com.a", "com.b", "com.c"), models.sortedWith(byName).names())
        val byTime = buildAppListComparator(
            ServicePreferences.SORT_BY_UPDATE_TIME, ruleCountEnabled = true, mountStateEnabled = true
        )
        assertEquals(listOf("com.a", "com.b", "com.c"), models.sortedWith(byTime).names())
    }

    @Test
    fun `业务优先级不变_mountState与ruleScore高优且可开关`() {
        // mountState 高优：label 更靠后但状态更高 → 排前面。
        val mounted = modelOf("com.high", label = "zzz", mountState = AppListModel.STATE_MOUNTED)
        val unmounted = modelOf("com.low", label = "aaa", mountState = AppListModel.STATE_UNMOUNTED)
        val withState = buildAppListComparator(
            ServicePreferences.SORT_BY_NAME, ruleCountEnabled = true, mountStateEnabled = true
        )
        assertEquals(
            listOf("com.high", "com.low"),
            listOf(unmounted, mounted).sortedWith(withState).names()
        )
        // 关闭 mountState → 回到 label 顺序。
        val withoutState = buildAppListComparator(
            ServicePreferences.SORT_BY_NAME, ruleCountEnabled = false, mountStateEnabled = false
        )
        assertEquals(
            listOf("com.low", "com.high"),
            listOf(mounted, unmounted).sortedWith(withoutState).names()
        )

        // ruleScore 高优 + 权重：挂载规则(+2) > 只读规则(+1) > 无规则。
        val mountRule = modelOf("com.m", label = "zzz", mountRulesCount = 1)
        val readOnlyRule = modelOf("com.r", label = "mmm", readOnlyCount = 5)
        val noRule = modelOf("com.n", label = "aaa")
        val withRule = buildAppListComparator(
            ServicePreferences.SORT_BY_NAME, ruleCountEnabled = true, mountStateEnabled = false
        )
        assertEquals(
            listOf("com.m", "com.r", "com.n"),
            listOf(noRule, readOnlyRule, mountRule).sortedWith(withRule).names()
        )
        // 关闭 ruleCount → 回到 label 顺序。
        assertEquals(
            listOf("com.n", "com.r", "com.m"),
            listOf(mountRule, readOnlyRule, noRule).sortedWith(withoutState).names()
        )
    }

    @Test
    fun `updateTime倒序且tie走label兜底`() {
        val old = modelOf("com.old", label = "bbb", lastUpdateTime = 1000L)
        val new = modelOf("com.new", label = "aaa", lastUpdateTime = 2000L)
        val tieA = modelOf("com.tie.b", label = "bbb", lastUpdateTime = 3000L)
        val tieB = modelOf("com.tie.a", label = "aaa", lastUpdateTime = 3000L)
        val byTime = buildAppListComparator(
            ServicePreferences.SORT_BY_UPDATE_TIME, ruleCountEnabled = false, mountStateEnabled = false
        )
        assertEquals(
            listOf("com.tie.a", "com.tie.b", "com.new", "com.old"),
            listOf(old, tieA, new, tieB).sortedWith(byTime).names()
        )
    }

    @Test
    fun `输入乱序输出一致`() {
        val base = listOf(
            modelOf("com.p1", label = "Alpha", lastUpdateTime = 3000L),
            modelOf("com.p2", label = "", lastUpdateTime = 1000L),
            modelOf("com.p3", label = "Alpha", lastUpdateTime = 3000L, mountRulesCount = 2),
            modelOf("com.p4", label = "beta", lastUpdateTime = 2000L, readOnlyCount = 1),
            modelOf("com.p5", label = "Alpha", lastUpdateTime = 3000L, mountRulesCount = 1,
                mountState = AppListModel.STATE_MOUNTED),
            modelOf("com.p6", label = "Gamma", lastUpdateTime = 3000L,
                mountState = AppListModel.STATE_MOUNT_EXCEPTION),
            modelOf("com.p7", label = "", lastUpdateTime = 3000L, readOnlyCount = 3),
            modelOf("com.p8", label = "beta", lastUpdateTime = 2000L,
                mountState = AppListModel.STATE_UNKNOWN)
        )
        val comparators = listOf(
            buildAppListComparator(
                ServicePreferences.SORT_BY_NAME, ruleCountEnabled = true, mountStateEnabled = true
            ),
            buildAppListComparator(
                ServicePreferences.SORT_BY_UPDATE_TIME, ruleCountEnabled = true, mountStateEnabled = true
            ),
            buildAppListComparator(
                ServicePreferences.SORT_BY_NAME, ruleCountEnabled = false, mountStateEnabled = false
            )
        )
        val permutations = listOf(
            base.reversed(),
            base.drop(3) + base.take(3),
            base.shuffled(Random(0)),
            base.shuffled(Random(1)),
            base.shuffled(Random(42))
        )
        for (comparator in comparators) {
            val expected = base.sortedWith(comparator).names()
            for (input in permutations) {
                assertEquals(expected, input.sortedWith(comparator).names())
            }
        }
    }
}
