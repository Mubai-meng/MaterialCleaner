package me.gm.cleaner.client

import android.content.pm.PackageInfo

fun getSharedUserIdPackages(packageInfo: PackageInfo): List<PackageInfo> {
    val installedPackages = CleanerClient.getInstalledPackagesOrNull(0) ?: emptyList()
    val sharedUserId = packageInfo.sharedUserId ?: return listOf(packageInfo)
    val uid = packageInfo.applicationInfo.uid
    return installedPackages.filter {
        uid == it.applicationInfo.uid && sharedUserId == it.sharedUserId
    }
}

fun getSharedProcessPackages(packageInfo: PackageInfo): List<PackageInfo> {
    val installedPackages = CleanerClient.getInstalledPackagesOrNull(0) ?: emptyList()
    val processName = packageInfo.applicationInfo.processName ?: return listOf(packageInfo)
    val uid = packageInfo.applicationInfo.uid
    return installedPackages.filter {
        uid == it.applicationInfo.uid && processName == it.applicationInfo.processName
    }
}
