package me.gm.cleaner.core.storage.redirect.domain

/**
 * 「整盘重定向」下，应用进程内**仍然可达**的共享存储子树推导（纯函数，无 Android 依赖）。
 *
 * ## 要解决的问题（真机实证，2026-10-28 第 55 批）
 *
 * 给相册（`com.coloros.gallery3d`）配了整盘重定向后，从其它应用唤起系统图片选择器，
 * 网格里出现整片灰色空白格。真机规则（`redirect_policy.json` 快照）是：
 *
 * ```
 * 0: /storage/emulated/0/Android/data/com.coloros.gallery3d/sdcard -> /storage/emulated/0
 * 1: /storage/emulated/0/Android/data/com.coloros.gallery3d      -> （同一路径，恒等）
 * 2: /storage/emulated/0/DCIM                                    -> （同一路径，恒等）
 * 3: /storage/emulated/0/Pictures                                -> （同一路径，恒等）
 * ```
 *
 * 实际下发 3 条挂载（第 1 条被自包含判定剔除），`bindMount result=true`、各层 HEALTHY：
 *
 * ```
 * /mnt/user/0/emulated/0/Android/data/com.coloros.gallery3d/sdcard → /storage/emulated/0
 * /mnt/user/0/emulated/0/DCIM                                     → /storage/emulated/0/DCIM
 * /mnt/user/0/emulated/0/Pictures                                 → /storage/emulated/0/Pictures
 * ```
 *
 * 于是相册进程里的 `/storage/emulated/0` **已经不是共享存储根**，而是它自己的
 * 私有 `sdcard` 目录；只有 `DCIM`、`Pictures` 两个子树被恒等 carve-out 救回来。
 *
 * ## 为什么会出现空白格（而不是"看不见那些照片"）
 *
 * MediaProvider 的媒体库数据库**独立于挂载命名空间**：`query` 照旧返回全机媒体行，
 * `_data` 是真实共享存储路径。相册拿到 `_data = /storage/emulated/0/Documents/…/a.jpg`
 * 后按**自己的命名空间**打开 ⇒ 落到 `.../gallery3d/sdcard/Documents/…/a.jpg` ⇒ 不存在
 * ⇒ 缩略图加载失败 ⇒ 灰格。而 `DCIM`、`Pictures` 下的行因为 carve-out 存在，照常显示
 * —— 这正是实机现象："有的显示、有的空白，新拍的（DCIM/Camera）也显示"。
 *
 * ## 判据（可证明，不是启发式）
 *
 * 一条媒体行在该应用视图里「能否按它自己的路径读到」等价于：
 * **把该路径按该包的完整规则解释一遍，结果仍等于它自己**（恒等）。
 *
 * 理由是解释器语义与挂载语义一致：`interpret` 的 `derivedPath` 就是应用实际打开的
 * backing 路径。恒等 ⇒ 打开的就是它自己；非恒等 ⇒ 打开的是另一处（整盘重定向下
 * 通常是私有沙箱里不存在的同名路径）。
 *
 * 因此可达集合 = **整盘重定向目标之下、解释结果保持恒等的那些子树**。
 *
 * ## 边界
 *
 * - 只有存在「整盘重定向」（target 恰好是共享存储根、且 source 与 target 不同）时才启用；
 *   普通单目录重定向（如 `Download/Browser -> Download/QuarkCloudDrive`）返回空集合，
 *   调用方必须把它理解为「不施加限制」，**绝不能**当成「什么都不允许」。
 * - 候选子树用探针路径过一遍 [MountPlanDeriver.resolveMountedPath]，因此后置规则
 *   若把某个 carve-out 又改写掉，它会自动被排除 —— 不另写一套前缀逻辑。
 */
object MediaReachability {

    /** 共享存储根：`/storage/emulated/<n>` 或 `/sdcard`。 */
    private val SHARED_ROOT = Regex("""^/storage/emulated/\d+$""")
    private const val SDCARD_ROOT = "/sdcard"

    /** 探针子路径：只用于验证「该子树是否恒等」，不必真实存在。 */
    private const val PROBE_LEAF = "__mc_reachability_probe__"

    /**
     * 推导该包在「整盘重定向」下仍然可达的子树根集合。
     *
     * @return 可达子树根；**空集合表示「不存在整盘重定向，不应施加任何可达性限制」**。
     */
    fun deriveReachableRoots(rules: List<RedirectRule>): Set<String> {
        if (rules.isEmpty()) return emptySet()
        val root = findWholeRootTarget(rules) ?: return emptySet()
        val candidates = rules.asSequence()
            .filter { it.source == it.target }
            .map { it.target }
            .filter { it != root && isStrictDescendant(it, root) }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return emptySet()
        val probeSuffix = "/$PROBE_LEAF"
        return candidates.filterTo(LinkedHashSet()) { candidate ->
            MountPlanDeriver.resolveMountedPath(rules, candidate + probeSuffix) ==
                candidate + probeSuffix
        }
    }

    /**
     * 找出「整盘重定向」的目标：恰好是共享存储根、且被改写到了别处。
     *
     * 取**最后一条**命中的规则：解释器是后置优先，最后一条对根的改写才决定最终语义。
     */
    fun findWholeRootTarget(rules: List<RedirectRule>): String? =
        rules.lastOrNull { isSharedStorageRoot(it.target) && it.source != it.target }?.target

    /** 是否为共享存储根（`/storage/emulated/<n>` 或 `/sdcard`）。 */
    fun isSharedStorageRoot(path: String): Boolean =
        path == SDCARD_ROOT || SHARED_ROOT.matches(path)

    /** `path` 是否为 `root` 的严格后代（按段边界，`/a/bc` 不是 `/a/b` 的后代）。 */
    fun isStrictDescendant(path: String, root: String): Boolean =
        path.length > root.length && path.startsWith("$root/")
}
