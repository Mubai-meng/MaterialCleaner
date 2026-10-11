package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * 路径匹配判据（自身 + 任意深度子孙）—— 纯函数，无 android 依赖，可 JVM 单测。
 *
 * ⚠️ 本对象是**共享判据**，被两处复用，不要与任一能力绑定：
 * - 只读：允许读、拒绝写，路径依然可见；
 * - 不可访问：连读都拒绝，目标是让应用**完全识别不到**（SQL 下推 + 结果集兜底
 *   两条路径共用 [InaccessibleQueryFilter.isHidden]）。
 *
 * 早期版本这里写的是「只读规则（UI 上叫『不可访问的路径』）」—— 那是错的，
 * 两者是完全不同的能力，见 RULES-DETAIL 与第 17 批。
 *
 * ## 为什么要单独抽出来
 *
 * 旧判据是「路径与规则**相等**，或路径的**直接父目录**与规则相等」：
 *
 * ```
 * rule = /storage/emulated/0/Pictures
 * /storage/emulated/0/Pictures            → 命中（相等）
 * /storage/emulated/0/Pictures/x.jpg      → 命中（父目录相等）
 * /storage/emulated/0/Pictures/taobao/x.jpg → 未命中 ← 应用真实写法
 * ```
 *
 * 而 Android 应用几乎不会把文件平铺在共享根目录上：相册类写 `Pictures/<app>/…`、
 * 下载类写 `Download/<app>/…`、相机类写 `DCIM/Camera/…`。也就是说**真实写入路径永远比
 * 规则深至少两层** ⇒ 旧判据在实践中恒不命中，只读规则形同虚设。这与 UI 文案
 * 「该位置**或其下一级**」（英文 `at this location or its lower level`）也不一致。
 *
 * ## 语义
 *
 * 规则路径**自身**，以及它**任意深度的子孙**，全部命中。
 *
 * ## 边界（每一条都有对应用例）
 * - 大小写不敏感：FUSE 传进来的路径大小写未必与用户手填的一致；
 * - 规则结尾的斜杠要吃掉，否则 `/storage/emulated/0/Pictures/` 永远匹配不到任何东西；
 * - **前缀邻接必须是 `/`**：`/storage/emulated/0/PicturesX` 不得被
 *   `/storage/emulated/0/Pictures` 命中（这是"取前缀"写法最容易漏的坑）；
 * - 路径结尾的斜杠（宿主给的是目录）不影响判定；
 * - 重复斜杠按单个斜杠处理。
 */
object ReadOnlyPathMatcher {

    /**
     * 归一化：合并重复斜杠、去掉结尾斜杠、保持前导 `/`。
     *
     * 不做大小写折叠 —— 比较时统一用 `equals(ignoreCase = true)` 与前缀比较，
     * 折叠后反而会丢信息（本对象也用于把规则路径原样送进诊断快照）。
     */
    fun normalize(path: String): String {
        if (path.isEmpty()) return ""
        val absolute = path.startsWith("/")
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return if (absolute) "/" else ""
        return (if (absolute) "/" else "") + parts.joinToString("/")
    }

    /**
     * 判定 [path] 是否落在 [rulePath] 之内（含 [rulePath] 自身）。
     *
     * @param rulePath 用户在 UI 里配置的只读路径（未归一化也可）
     * @param path FUSE 回调给出的路径（**应用可见坐标**，即重定向前的原始路径）
     */
    fun matches(rulePath: String, path: String): Boolean {
        val rule = normalize(rulePath)
        val target = normalize(path)
        if (rule.isEmpty() || target.isEmpty()) return false
        if (target.equals(rule, ignoreCase = true)) return true
        // 前缀邻接必须是分隔符，否则 Pictures 会吃掉 PicturesX。
        val prefix = if (rule.endsWith("/")) rule else "$rule/"
        return target.length > prefix.length &&
                target.startsWith(prefix, ignoreCase = true)
    }

    /** 规则集合中任意一条命中即命中。空集合恒为 false（未配置只读规则的包走快速返回）。 */
    fun matchesAny(rulePaths: Collection<String>, path: String): Boolean {
        if (rulePaths.isEmpty()) return false
        for (rule in rulePaths) {
            if (matches(rule, path)) return true
        }
        return false
    }
}
