package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * 「应用专属存储」里的媒体行判定（纯字符串，无 Android 依赖）。
 *
 * ## 它做什么
 *
 * 对**已配置不可访问规则**的包，把 `_data` 位于它自己
 * `/Android/data/<pkg>/` 或 `/Android/media/<pkg>/` 之下的行一并剔除。
 * 这类行的典型特征：Android 11+ 的 owner 机制让它们**只有本包可见**，
 * 而 MediaProvider 又常常取不出内容 —— 在相册里就表现为坏图、点开失败。
 *
 * ## ⚠️ 原始动机已被证伪 —— 现在是防御性代码
 *
 * 本类是为第 23/24 批的 "assets(6)" 坏图相册加的，当时的判断是：
 * "这些行位于该包自己的应用专属存储里"。**第 26 批实测推翻了这个判断**：
 *
 * 1. `media bucket census` 的 `rows` 字段（就是本判据的命中数）
 *    **连续两批恒为 0** ⇒ 本机根本不存在"应用专属存储里的媒体行"；
 * 2. 真正的 assets(6) 位于
 *    `/storage/emulated/0/Documents/文件/教程/脱壳教程/加固特征/OPPO加固/assets`
 *    —— 一个**共享存储下的真实目录**（既不在 Android/data 下，也不是幽灵行）；
 * 3. 坏图的原因是那批教程图片**文件本身不完整**，与应用专属存储无关；
 *    它之所以可见，只是因为 **Documents 不在用户配置的 Pictures/DCIM 规则范围内**。
 *
 * ⇒ **本类不是 assets(6) 的解法**。后续排查"相册里多出来的条目"时，
 * 不要往这里找，直接看 `media bucket census` 的 `small=`（小相册的完整父目录）。
 *
 * 保留它的理由：①防御性 —— 若将来某应用把自己专属目录里的图收进媒体库，
 * 仍会被挡掉；②零 I/O、零反射、仅对已配置规则的包生效，**不产生性能代价**。
 *
 * ## 开销
 *
 * 纯字符串包含判定，无 I/O、无反射；只在「该包确实配了不可访问规则」时才启用，
 * 未配置的包与共享存储路径完全不受影响。
 */
object AppSpecificMediaFilter {

    /** 应用专属外部存储的两个根段；两个都要覆盖（data 存放数据，media 存放媒体）。 */
    private const val DATA_SEGMENT = "/Android/data/"
    private const val MEDIA_SEGMENT = "/Android/media/"

    /**
     * 判定 `_data` 是否位于 [packageName] 自己的应用专属存储内。
     *
     * 用"段包含"而不是"路径前缀"：前缀判据要先把 sdcard 根展开
     * （`/sdcard`、`/storage/emulated/0`、`/mnt/user/0/emulated` …），
     * 而本模块在 MediaProvider 进程里拿不到可靠的应用视图根 —— 用段判据与根无关，
     * 且天然避免把 `/Android/data/其它包/` 误判成本包的（包名是段的一部分）。
     */
    fun isInside(data: String?, packageName: String): Boolean {
        if (data.isNullOrBlank() || packageName.isBlank()) {
            return false
        }
        return data.contains(DATA_SEGMENT + packageName + "/")
            || data.contains(MEDIA_SEGMENT + packageName + "/")
    }

    /**
     * 构造排除条件：命中本包专属存储（自身或任意深度子孙）的行将被剔除。
     *
     * 与 [InaccessibleQueryFilter.buildExclusion] 一样交给 SQLite 执行，
     * 参数用 `?` 绑定，路径里的 LIKE 元字符按 ESCAPE 转义。
     */
    fun buildExclusion(packageName: String): Pair<String, List<String>> {
        val escape = '\\'
        val condition = "(_data LIKE ? ESCAPE '$escape' OR _data LIKE ? ESCAPE '$escape')"
        val args = listOf(
            "%" + DATA_SEGMENT + packageName + "/%",
            "%" + MEDIA_SEGMENT + packageName + "/%",
        )
        return condition to args
    }

    /**
     * 取 `_data` 的父目录，供诊断采样使用（不落文件名，减少隐私暴露）。
     */
    fun parentDir(data: String?): String {
        if (data.isNullOrBlank()) {
            return ""
        }
        val i = data.lastIndexOf('/')
        return if (i <= 0) "" else data.substring(0, i)
    }
}
