package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * 「不可访问的路径」在 MediaStore 查询上的过滤表达式构造（纯字符串，无 Android 依赖）。
 *
 * ## 为什么必须过滤查询
 *
 * 「不可访问」只靠挂载是不够的：q4 规则把目录挂载到应用自己的 cache 子目录，
 * 目录**依然存在**（只是空目录），而 MediaProvider 的数据库是独立于挂载命名空间的，
 * `MediaStore.query()` 仍会把真实文件整条返回 —— 文件名、`_data`、时间、id 全在，
 * 应用只是打开时才撞到 ENOENT。用户看到的是「相册里还列着这些照片」，
 * 也就是「还是能访问」。要做到「完全识别不到」，必须把查询结果里的这些行去掉。
 *
 * ## 为什么用 SQL selection 而不是包一层 Cursor
 *
 * 包裹 Cursor 需要把整个结果集复制一份（相册滚动时是几百到几千行）；
 * 把过滤条件下推到 `android:query-selection` 由 SQLite 执行，稳态开销接近 0。
 * 代价是必须自己拼 SQL —— 因此本对象做成纯函数并单测，拼装规则集中在一处。
 *
 * ## 空值语义
 *
 * `_data` 可能为 NULL（pending / 部分云端条目）。`NOT (_data LIKE ...)` 对 NULL
 * 求值为 NULL（非真），会**误删**这些行，因此外层显式 `OR _data IS NULL` 放行。
 */
import java.util.Locale

object InaccessibleQueryFilter {

    /** SQLite LIKE 的元字符；必须以 ESCAPE 方式转义，否则用户路径里的 `%` `_` 会变成通配符。 */
    private const val ESCAPE_CHAR = '\\'

    /**
     * queryArgs 里承载 SQL selection 的键，**按优先级排序**（下标 0 为首选）。
     *
     * ## 为什么有两个
     *
     * AOSP 的 `ContentResolver.QUERY_ARG_SQL_SELECTION` 取值是 `android:query-selection`，
     * 但本 ROM（ColorOS17 / Android17）把它整条改成了 `android:query-arg-sql-selection`。
     * 二进制实证：`com.android.providers.media.module` 的 classes.dex 与 framework.jar 里
     * `android:query-selection` 均出现 **0 次**，而 `android:query-arg-sql-selection`
     * 在 MediaProvider 的 query/update/delete 路径上被大量读取。
     *
     * ⇒ 只写 AOSP 键在本机等于没写（第 18~21 批的 SQL 下推因此从未生效）；只写本 ROM 键
     * 则换个 ROM 就失效。故**写入时全部写**（多余键对方不读、无害），**读取时按顺序取第一个非空**。
     */
    val selectionKeys: Array<String> = arrayOf(
        "android:query-arg-sql-selection",
        "android:query-selection",
    )

    /** [selectionKeys] 对应的绑定参数键，顺序一一对应。 */
    val selectionArgsKeys: Array<String> = arrayOf(
        "android:query-arg-sql-selection-args",
        "android:query-selection-args",
    )

    /**
     * 构造排除表达式：命中任一不可访问根（自身或任意深度子孙）的行将被剔除。
     *
     * @param roots 不可访问的路径根（应用可见路径，已按 userId 展开）
     * @return (selection, selectionArgs)；roots 为空时返回 null，调用方应跳过过滤。
     */
    @JvmOverloads
    fun buildExclusion(
        roots: Collection<String>,
        appSpecificPackage: String? = null,
    ): Pair<String, List<String>>? {
        // isNotBlank：空白项同样不可作为路径根，否则会拼出 `  /%` 这种无意义条件。
        val normalized = roots
            .asSequence()
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (normalized.isEmpty()) {
            return null
        }
        val conditions = ArrayList<String>(normalized.size + 1)
        val args = ArrayList<String>((normalized.size + 2) * 2)
        for (root in normalized) {
            // 自身：精确相等；子孙：root + "/%"（root 里的元字符已转义）。
            conditions += "(_data = ? OR _data LIKE ? ESCAPE '$ESCAPE_CHAR')"
            args += root
            args += escapeLike(root) + "/%"
        }
        // 应用专属存储：只有本包可见、而 MediaProvider 又常常取不出内容的行，
        // 一律剔除。**防御性** —— 第 26 批实测这类行在本机数量为 0，
        // assets(6) 坏图相册并非它造成（详见 AppSpecificMediaFilter 的类注释）。
        val pkg = appSpecificPackage?.takeIf { it.isNotBlank() }
        if (pkg != null) {
            val appSpecific = AppSpecificMediaFilter.buildExclusion(pkg)
            conditions += appSpecific.first
            args += appSpecific.second
        }
        val anyHit = conditions.joinToString(" OR ")
        val selection = "(_data IS NULL OR NOT ($anyHit))"
        return selection to args
    }

    /**
     * 把排除条件与调用方原有的 selection 合并。
     *
     * @param original 调用方原来的 SQL selection，可能为 null/空白
     * @param originalArgs 调用方原来的绑定参数，可能为 null
     * @param exclusion [buildExclusion] 的结果
     * @return 合并后的 (selection, args)；参数顺序与 `?` 出现顺序一致。
     */
    fun combine(
        original: String?,
        originalArgs: List<String>?,
        exclusion: Pair<String, List<String>>,
    ): Pair<String, List<String>> {
        val before = original?.takeIf { it.isNotBlank() }
        val beforeArgs = originalArgs ?: emptyList()
        return if (before == null) {
            exclusion
        } else {
            "($before) AND ${exclusion.first}" to (beforeArgs + exclusion.second)
        }
    }

    /**
     * 只在 MediaStore 的媒体 / 文件 URI 上尝试过滤。
     *
     * `_data` 是 `files` 表的列；其它 URI（音量、相册权限等非 files 表查询）没有它，
     * 拼上去必然报错。与其依赖异常回退，不如先按 URI 形态筛掉，避免一次查询就把
     * 该包永久标记为不支持过滤。抽成纯函数以便单测覆盖各种 URI 形态。
     */
    fun shouldAttempt(path: String?): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }
        val lower = path.lowercase(Locale.ROOT)
        return lower.contains("/images/") || lower.contains("/video/")
            || lower.contains("/audio/") || lower.contains("/file")
    }

    /**
     * 该查询路径是否为**单条目** URI（末段是纯数字主键）。
     *
     * ## 为什么需要这个判定（第56批，真机日志 1793193023226）
     *
     * `content://media/external/file/17830` 这类单条目 URI 是系统图片选择器
     * 逐条校验"这条还在不在、是什么类型"的入口：
     *
     * ```
     * W ContentResolver: No item at content://media/external/file/17830
     * E GalleryCloudMediaProvider: [fromMediaId] it's not image or video: 17830
     * E DatabaseUtils: Failed to onOpenPreview, mediaUri is null, mediaId:17830
     * W Glide: Load failed for [Image(mediaId=17830, ... totalCount=915)]  ⇒ 灰格
     * ```
     *
     * 列表侧（`content://media/picker/...`）不经过本过滤（[shouldAttempt] 为 false），
     * 而按 id 校验会进来 —— 影子段若作用于此，理论上会出现"列表有、按 id 查没有"
     * 的撕裂。本判定让影子段避开单条目查询，属**事前防御**。
     *
     * ## ⚠️ 归因订正（第60批，设备实证）
     *
     * 第56批曾据上面这条链判定「是影子过滤剔掉了这些行；6.0.5 该错误为 0、
     * 6.0.6 为 323 次，是本过滤新增后引入的回归」。**该结论已证伪**，真因与
     * 本过滤无关：
     *
     * - 第60批把 `picker.db` / `external.db` 只读拉回本地比对，全部 68 个失败 id
     *   的 `volume_name` 都是 `2cb4-cae8`；而 `sm list-volumes all` 只有
     *   private / emulated;0，`/storage` 下没有该卷 ⇒ 卡被拔掉。
     * - `getType` → `queryForSingleItem` 会排除未挂载卷 ⇒ 0 行 ⇒ 抛
     *   `No item at`。用 shell 直接查同一个 id 同样 "No result found"。
     * - 「6.0.5 为 0」是取样时机问题：6.0.5 日志窗口止于 10-28 19:26，而卡上
     *   最后一条 `date_added` 是 20:56 ⇒ 取样时卡还在。6.0.6 的 144 个失败 id
     *   又完整包含 6.0.7 的 68 个（交集 68、独有 0）⇒ 固定集合，与版本无关。
     * - `com.coloros.gallery3d` 的不可访问规则数为 0（4 条全是重定向），
     *   `QueryHooker.buildFilteredQueryArgs` 本就返回 null，本模块一条路径都没走。
     *
     * ⇒ 本判定**保留但降级为纯防御**：它没有修好任何已观测到的空白图。修法方向
     * 仍然合理（宁留灰格不误删），但不得再作为缺陷修复的证据引用。
     *
     * ## 修法取舍
     *
     * 让**影子段**不作用于单条目查询（[RedirectShadowFilter] 是"别列出读不到的行"，
     * 而单条目查询恰恰是列表之后才发生的校验，拦它只会把行变成灰格）。
     * **不**跳过 `inaccessible` 段 —— 用户明确配置的"不可访问"要求连存在性都看不见，
     * 必须照旧拦截。
     *
     * @param path `Uri#getPath()`，例如 `/external/file/17830`、`/picker/0/x/media/12`
     * @return true 表示末段是纯数字主键（单条目查询）
     */
    fun isSingleItemPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val last = path.trimEnd('/').substringAfterLast('/')
        if (last.isEmpty() || last.length > 18) return false
        return last.all { it in '0'..'9' }
    }

    /**
     * 判定某条记录的 `_data` 是否落在任一不可访问根内（自身或任意深度子孙）。
     *
     * SQL 下推与结果集兜底**必须共用这一个判据** —— 两条路径各写一份匹配逻辑
     * 必然漂移，届时会出现「SQL 说隐藏、Cursor 说保留」的撕裂。
     *
     * NULL / 空白恒为 false：`_data` 为 NULL 的行（pending / 云端条目）与文件
     * 路径无关，不能被路径规则误删。
     */
    @JvmOverloads
    fun isHidden(
        roots: Collection<String>,
        data: String?,
        appSpecificPackage: String? = null,
    ): Boolean {
        if (data.isNullOrBlank()) return false
        if (ReadOnlyPathMatcher.matchesAny(roots, data)) return true
        // 与 SQL 下推共用同一判据，避免"SQL 说隐藏、Cursor 说保留"的撕裂。
        val pkg = appSpecificPackage?.takeIf { it.isNotBlank() } ?: return false
        return AppSpecificMediaFilter.isInside(data, pkg)
    }

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
}
