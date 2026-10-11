package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * FUSE 路径重定向的**可观测性**纯函数（无 android 依赖，便于 JVM 单测）。
 *
 * 动机：连续多批实机材料里 `redirected:` 恒为 0，而「hook 没工作」与
 * 「目标 app 触碰的路径根本不在任何已配置 target 之下」这两种可能
 * **在产物里完全无法区分** —— 未命中的路径上既没有日志也没有计数。
 *
 * 于是只能靠猜。这类"沉默的正确"代价很高：它让每一次实机验证都退化成
 * 一次无法证伪的观察。本对象提供两件事：
 * - [prefixOf]：把路径压成一个**目录前缀**，既保留足以判定"落在哪个目录子树"
 *   的信息（`/storage/emulated/0/Download` vs `/storage/emulated/0`），
 *   又不含用户文件名 —— 快照与诊断包是会被导出分享的载体，明文路径不得入内；
 * - [outcome]：把一次重定向评估归一化为命中/未命中，供调用方只做一次 volatile 写。
 */
object FuseRedirectStats {

    /**
     * 默认保留的路径段数上限。
     *
     * 取 5 的理由：判定"是否落在已配置 target 之下"至少要能表达
     * `/storage/emulated/0/Download`（4 段），再留一段余量；超出即截断，
     * 越深的路径对"为何没命中"这个问题贡献越小。
     */
    const val DEFAULT_PREFIX_SEGMENTS: Int = 5

    /**
     * 取路径的**目录前缀**（去掉最后一段），并把段数压到 [maxSegments] 以内。
     *
     * 为什么先去掉最后一段：路径的最后一段通常是文件名，而文件名正是
     * 必须排除在快照之外的东西。只做"取前 N 段"做不到这一点 ——
     * `/storage/emulated/0/dsprobe_m.log` 恰好就是 4 段，
     * 取前 4 段等于把文件名原样留下（本项目已因此踩过一次，被单测挡下）。
     *
     * 同时做规范化（合并重复斜杠）：诊断投影里统一形态更利于跨批比对。
     *
     * - `/storage/emulated/0/Download/a.apk` → `/storage/emulated/0/Download`
     * - `/storage/emulated/0/dsprobe_m.log` → `/storage/emulated/0`
     * - `//storage//emulated///0/x` → `/storage/emulated/0`
     * - 只有一段（如 `/storage`）时保留原样 —— 再去掉就什么都不剩了。
     */
    fun prefixOf(path: String, maxSegments: Int = DEFAULT_PREFIX_SEGMENTS): String {
        if (path.isEmpty() || maxSegments <= 0) return ""
        var end = path.length
        while (end > 0 && path[end - 1] == '/') end--
        if (end == 0) return "/"
        // 结尾带斜杠 ⇒ 宿主给的就是目录本身，不能再去掉最后一段，
        // 否则 `/storage/emulated/0/Download/` 会被压成 `/storage/emulated/0`，
        // 恰好把"是否落在 target 子目录下"这个唯一要回答的问题抹掉。
        val isDirectory = end < path.length
        val trimmed = path.substring(0, end)
        if (isDirectory) return capSegments(trimmed, maxSegments)
        val cut = trimmed.lastIndexOf('/')
        val dir = if (cut <= 0) trimmed else trimmed.substring(0, cut)
        return capSegments(dir, maxSegments)
    }

    /** 段数上限：超出即截断。顺带把重复斜杠规范化掉。 */
    private fun capSegments(path: String, maxSegments: Int): String {
        val absolute = path.startsWith("/")
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "/"
        val kept = if (parts.size <= maxSegments) parts else parts.take(maxSegments)
        return (if (absolute) "/" else "") + kept.joinToString("/")
    }

    /** 一次重定向评估的归一化结果。 */
    enum class Outcome { REDIRECTED, MISS }

    /**
     * 归一化：只有"确实改写了路径"才算命中。
     *
     * `mountedPath == path`（派生结果与原文相同）不算命中 —— 那表示规则存在但
     * 对该路径无实际效果，从诊断角度看与"没匹配到"是同一类需要解释的情形。
     */
    fun outcome(mountedPath: String?, path: String): Outcome =
        if (mountedPath != null && mountedPath != path) Outcome.REDIRECTED else Outcome.MISS
}
