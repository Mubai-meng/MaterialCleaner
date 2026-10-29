package me.gm.cleaner.runtime.mediaprovider.hook.policy

import me.gm.cleaner.core.storage.redirect.domain.MediaReachability
import me.gm.cleaner.core.storage.redirect.domain.MountPlanDeriver
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import java.util.Locale

/**
 * 「整盘重定向」导致媒体库行在应用内**不可达**的判定与 SQL 构造（纯函数，无 Android 依赖）。
 *
 * ## 要解决的现象（2026-10-28 第 55 批，真机实证）
 *
 * 给相册（`com.coloros.gallery3d`）配了整盘重定向后，从别的应用唤起系统图片选择器，
 * 网格里出现**大量灰色空格**；但另一部分是正常缩略图，**新拍的照片也正常显示**。
 *
 * 真机日志把这件事钉死为「部分可达」：
 *
 * ```
 * bindMount result=true pkg=com.coloros.gallery3d pid=10911
 *   sources=[.../gallery3d/sdcard, /storage/emulated/0/DCIM, /storage/emulated/0/Pictures]
 *   targets=[/storage/emulated/0,  /storage/emulated/0/DCIM, /storage/emulated/0/Pictures]
 * MediaProvider: initPhotoPickerData, set local authority:com.coloros.gallery3d.photopicker
 * ```
 *
 * ⇒ 相册进程里 `/storage/emulated/0` 已被换成它自己的私有 `sdcard` 目录，
 * 只有 `DCIM`、`Pictures`（以及它自己的 `Android/data/<pkg>`）被恒等 carve-out 救回。
 * 于是：
 *
 * - `_data` 落在 carve-out 里 ⇒ 按自己的路径就能打开 ⇒ **正常显示**（新拍的照片在
 *   `DCIM/Camera`，正好在此列）；
 * - `_data` 落在别处（`Documents`、`Download`、`Movies`、`Android/media/其它包`…）
 *   ⇒ MediaProvider 的数据库与挂载命名空间无关，照旧返回整行 ⇒ 相册按自己的命名空间
 *   打开 ⇒ 落到 `.../gallery3d/sdcard/Documents/...` ⇒ 不存在 ⇒ **灰格**。
 *
 * ## 为什么在查询侧解决
 *
 * 这与「不可访问的路径」的根因**同源**（见 [InaccessibleQueryFilter] 的类注释）：
 * 数据库独立于挂载视图。区别只在语义：
 *
 * - 不可访问 = 用户明确要求隐藏 ⇒ 剔除；
 * - 本类 = 该行在应用里根本读不到，留着只是一张坏图 ⇒ 也剔除。
 *
 * 两者都必须是**按行剔除**，不能靠再挂一条 mount —— 那份路径根本不该出现在结果里。
 *
 * ## 关键安全边界（必须遵守）
 *
 * 只有 [MediaReachability.deriveReachableRoots] 返回**非空**时才启用。空集合表示
 * 「没有整盘重定向」，此时**绝不能**理解为「什么都不允许」，必须完全不干预。
 * 该判定已由 [MediaReachability] 的单测钉死。
 *
 * ## 开销
 *
 * 判定是纯字符串前缀比较；`_data` 通常命中共享存储根的直接子目录，`roots` 只有个位数，
 * 因此是 O(1) 级。SQL 下推由 SQLite 执行，稳态开销 ≈ 0。
 */
object RedirectShadowFilter {

    /** SQLite LIKE 的转义符，与 [InaccessibleQueryFilter] 保持一致。 */
    private const val ESCAPE_CHAR = '\\'

    /**
     * 该包当前是否存在「整盘重定向」（决定本过滤是否启用）。
     *
     * 逐查询调用一次即可；内部只做规则列表的线性扫描。
     */
    fun isEnabled(rules: List<RedirectRule>): Boolean =
        MediaReachability.deriveReachableRoots(rules).isNotEmpty()

    /**
     * 取该包在整盘重定向下仍然可达的子树根（供调用方一次算出、多行复用）。
     *
     * @return 可达根集合；**空集合 ⇒ 不应施加任何限制**
     */
    fun reachableRoots(rules: List<RedirectRule>): Set<String> =
        MediaReachability.deriveReachableRoots(rules)

    /**
     * 判定某条 `_data` 是否会在该包的应用视图里落空。
     *
     * @param roots [reachableRoots] 的结果；为空时**恒返回 false**（不干预）
     * @param sharedRoot 该包的共享存储根（`/storage/emulated/0` 等），见
     *   [MediaReachability.findWholeRootTarget]
     * @param data 媒体行的 `_data`
     * @return true 表示这行在应用里打不开，应剔除
     */
    @JvmStatic
    fun isUnreachable(
        roots: Collection<String>,
        sharedRoot: String?,
        data: String?,
    ): Boolean {
        if (roots.isEmpty() || sharedRoot.isNullOrBlank()) return false
        if (data.isNullOrBlank()) return false
        // 只处理共享存储下的行：其它卷（SD 卡、/data 下的）不在整盘重定向的作用域内。
        if (!MediaReachability.isStrictDescendant(data, sharedRoot) && data != sharedRoot) {
            return false
        }
        return roots.none { inSubtree(data, it) }
    }

    /**
     * 构造排除表达式：`_data` 落在共享存储根之内、又不在任何可达子树内的行将被剔除。
     *
     * 只写 `_data LIKE '<sharedRoot>/%' AND NOT (<任一可达子树>)`，语义上等价于
     * 「看起来在共享存储里，但那条路径在这个应用里不存在」。
     *
     * @return (selection, args)；不可启用时返回 null，调用方应跳过过滤。
     */
    @JvmStatic
    fun buildExclusion(
        roots: Collection<String>,
        sharedRoot: String?,
    ): Pair<String, List<String>>? {
        if (roots.isEmpty() || sharedRoot.isNullOrBlank()) return null
        val normalized = roots.asSequence()
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() && inSubtree(it, sharedRoot) }
            .distinct()
            .toList()
        // 没有任何可达子树落在共享存储根内 ⇒ 该根下处处不可达，此时不能靠"排除白名单"
        // 表达（会让整盘媒体消失），按"不干预"处理，把决定权交回调用方。
        if (normalized.isEmpty()) return null
        val args = ArrayList<String>(1 + normalized.size * 2)
        val keptConditions = StringBuilder()
        normalized.forEachIndexed { index, root ->
            if (index > 0) keptConditions.append(" OR ")
            keptConditions.append("(_data = ? OR _data LIKE ? ESCAPE '$ESCAPE_CHAR')")
            args += root
            args += escapeLike(root) + "/%"
        }
        // 参数顺序必须与 `?` 在 selection 中出现的顺序完全一致：
        // 外层根前缀先出现，随后才是白名单里那批占位符。
        val selection =
            "(_data IS NULL OR NOT (_data LIKE ? ESCAPE '$ESCAPE_CHAR' AND NOT ($keptConditions)))"
        args.add(0, escapeLike(sharedRoot) + "/%")
        return selection to args
    }

    /** `path` 是否等于 `root` 或落在其内（段边界）。 */
    private fun inSubtree(path: String, root: String): Boolean =
        path == root || MediaReachability.isStrictDescendant(path, root)

    private fun escapeLike(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '%', '_', ESCAPE_CHAR -> sb.append(ESCAPE_CHAR).append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 供诊断日志使用的简短描述（不含具体文件名，避免隐私暴露）。 */
    fun describe(roots: Collection<String>, sharedRoot: String?): String {
        if (roots.isEmpty() || sharedRoot.isNullOrBlank()) return "disabled"
        val names = roots.map { it.substringAfterLast('/') }.sorted()
        return "root=" + sharedRoot.lowercase(Locale.ROOT) + " reachable=" + roots.size
            .toString() + " names=" + names
    }
}
