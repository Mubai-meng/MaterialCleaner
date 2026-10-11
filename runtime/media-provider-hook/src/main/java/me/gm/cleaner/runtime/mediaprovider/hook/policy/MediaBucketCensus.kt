package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * 媒体库「相册分桶」普查（纯字符串/集合运算，无 Android 依赖，可单测）。
 *
 * ## 为什么需要它
 *
 * 第 25 批实机：第 24 批的探针只回答了 `rows=0`（"没有应用专属行"），
 * 却回答不了用户真正的问题 —— **assets(6) 到底在哪**。原因是它有三个盲区：
 *
 * 1. 只统计命中「应用专属存储」判据的行，其余行一律丢弃；
 * 2. 不输出总行数，无法判断探针这次到底扫到了多少行（rows=0 可能是"确实没有"，
 *    也可能是"这次查询根本不含那 6 行"）；
 * 3. 不输出目录分布，因此拿不到任何形如 `.../assets` 的线索。
 *
 * 本对象把一次查询的 `_data` 父目录汇总成**可直接与相册 UI 对照**的口径：
 *
 * * [topNames] —— 分桶名（父目录最后一段，即相册 UI 上的 bucket 显示名）与张数，
 *   可与用户截图里的 "assets (6)"、"相机胶卷 (87)" 逐项对齐；
 * * [smallBuckets] —— 张数不超过 [SMALL_BUCKET_MAX] 的**完整父目录**。
 *   相册里"多出来的"条目几乎都是小相册：既可能是幽灵行（文件已删、DB 行还在），
 *   也可能是真实但被忽略的目录 —— 第 26 批的 assets(6) 就是后者，
 *   位于 Documents 下的一套教程资料里。所以**定位相册异常一律先看小相册**，
 *   且数量少、输出可控；大相册只给名字不给路径，兼顾隐私与日志体积。
 *
 * ## 开销
 *
 * 纯字符串切分 + HashMap 计数，无 I/O、无反射；由调用方保证只在「已配置不可访问
 * 规则的包」上、且每包每进程只跑一次，行数封顶（见 QueryHooker#PROBE_ROW_CAP）。
 */
object MediaBucketCensus {

    /** 小相册阈值：张数不超过它的分桶才输出完整父目录。 */
    const val SMALL_BUCKET_MAX = 10

    /**
     * 取分桶名：父目录的最后一段，与相册 UI 上的 bucket 显示名同源。
     * 空串、纯空白一律返回空串（调用方据此跳过）。
     */
    fun bucketOf(dir: String?): String {
        if (dir.isNullOrBlank()) {
            return ""
        }
        var s = dir
        if (s.length > 1 && s.endsWith('/')) {
            s = s.substring(0, s.length - 1)
        }
        val i = s.lastIndexOf('/')
        return if (i < 0) s else s.substring(i + 1)
    }

    /**
     * 按张数降序取前 [limit] 个分桶名；**同张数按名字升序**，保证输出稳定、
     * 跨批次可直接比对（HashMap 迭代序不稳定，不能直接用）。
     */
    fun topNames(dirCounts: Map<String, Int>, limit: Int): List<Pair<String, Int>> {
        val agg = LinkedHashMap<String, Int>()
        for ((dir, n) in dirCounts) {
            if (dir.isBlank() || n <= 0) {
                continue
            }
            val name = bucketOf(dir)
            if (name.isEmpty()) {
                continue
            }
            agg[name] = (agg[name] ?: 0) + n
        }
        return agg.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key }
            )
            .take(if (limit < 0) 0 else limit)
            .map { it.key to it.value }
    }

    /**
     * 张数不超过 [maxCount] 的分桶的**完整父目录**，最多 [limit] 条，按张数升序、
     * 同张数按路径升序（小相册在前，幽灵行通常是少数派）。
     */
    fun smallBuckets(
        dirCounts: Map<String, Int>,
        limit: Int = 3,
        maxCount: Int = SMALL_BUCKET_MAX,
    ): List<String> {
        return dirCounts.entries
            .filter { it.key.isNotBlank() && it.value > 0 && it.value <= maxCount }
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(if (limit < 0) 0 else limit)
            .map { it.key }
    }

    /** 格式化成 `assets=6, Camera=87`，供日志直接打印。 */
    fun formatTop(dirCounts: Map<String, Int>, limit: Int): List<String> =
        topNames(dirCounts, limit).map { it.first + "=" + it.second }
}
