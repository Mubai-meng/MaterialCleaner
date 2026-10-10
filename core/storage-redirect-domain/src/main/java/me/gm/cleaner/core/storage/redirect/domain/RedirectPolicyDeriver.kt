package me.gm.cleaner.core.storage.redirect.domain

/**
 * Pure derivation helpers for storage redirect policy snapshots.
 *
 * 挂载点推导经唯一的 [MountPlanDeriver] 投影桥，不直接持有解释器。
 */
object RedirectPolicyDeriver {

    /**
     * Derives the FUSE-visible configured mount point set from redirect rules.
     *
     * The result is a rule-derived view, not a report of mounts that already
     * succeeded at VFS runtime.
     *
     * 多个 (package, userId) 计划可以产出**同一个挂载点**（例如两个包都把
     * `/Download` 重定向到同一目标），而下游把这四个点原样推给 native 去挂载：
     * 实测真机 `configured_mount_points.json` 里 `/storage/emulated/0/Download`
     * 出现了 **2 次**。这里按**首次出现顺序**去重（`distinct()` 对 List 保序），
     * 既让快照与 native 侧 `lastMountPointsApplyCount` 反映真实点数，
     * 也避免下发的重复挂载项。单包内部若已去重，本步对它是恒等变换。
     */
    fun buildConfiguredMountPoints(policy: RedirectPolicySnapshot): ConfiguredMountPointsSnapshot {
        val points = mutableListOf<String>()

        for ((packageName, userRules) in policy.storage.redirectRules) {
            for ((userId, rules) in userRules) {
                val plan = MountPlanDeriver.derive(packageName, userId, rules) ?: continue
                points.addAll(plan.mountPoints)
            }
        }

        return ConfiguredMountPointsSnapshot(
            schemaVersion = 1,
            generation = policy.generation,
            publisherEpoch = policy.publisherEpoch,
            createdAt = policy.createdAt,
            publisher = policy.publisher,
            redirectRevision = policy.redirectRevision,
            points = points.distinct(),
        )
    }

    fun getMountedPath(
        policy: RedirectPolicySnapshot,
        packageName: String,
        userId: Int,
        path: String,
    ): String {
        val rules = policy.storage.redirectRules[packageName]?.get(userId) ?: return path
        return MountPlanDeriver.resolveMountedPath(rules, path)
    }
}
