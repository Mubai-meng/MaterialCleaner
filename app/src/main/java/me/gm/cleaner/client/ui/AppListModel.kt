package me.gm.cleaner.client.ui

import android.content.pm.PackageInfo

data class AppListModel(
    val packageInfo: PackageInfo,
    val label: String,
    val mountRulesCount: Int,
    val readOnlyCount: Int,
    val mountState: Int,
) : AppListSortable {

    // 排序键：只是把字段换个名字暴露给 AppListOrdering，
    // 不参与 equals/hashCode（因此不改变本类的相等语义）。
    override val sortLabel: String get() = label
    override val sortPackageName: String get() = packageInfo.packageName ?: ""
    override val sortLastUpdateTime: Long get() = packageInfo.lastUpdateTime
    override val sortMountState: Int get() = mountState
    override val sortMountRulesCount: Int get() = mountRulesCount
    override val sortReadOnlyCount: Int get() = readOnlyCount

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + mountRulesCount
        result = 31 * result + readOnlyCount
        result = 31 * result + mountState
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppListModel

        if (label != other.label) return false
        if (mountRulesCount != other.mountRulesCount) return false
        if (readOnlyCount != other.readOnlyCount) return false
        if (mountState != other.mountState) return false

        return true
    }

    companion object {
        const val STATE_UNMOUNTED = 0
        const val STATE_MOUNTED = 1
        const val STATE_UNKNOWN = 2
        const val STATE_MOUNT_EXCEPTION = 3
    }
}
