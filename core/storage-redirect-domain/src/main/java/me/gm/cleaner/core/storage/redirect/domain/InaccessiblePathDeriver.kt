package me.gm.cleaner.core.storage.redirect.domain

/**
 * 「不可访问的路径」识别器（纯函数，无 Android 依赖）。
 *
 * ## 背景：q4 不产生独立规则族
 *
 * 向导的问题 4（"Paths that can't access"，即用户所说的「不可访问的路径」）
 * 与「只读」是**两个完全不同的能力**：
 *
 * - 只读（read-only）：允许读、拒绝写，路径依然存在、内容依然可见；
 * - 不可访问（inaccessible）：连读都不允许，目标是让应用**完全识别不到**
 *   该文件夹或文件。
 *
 * 但 q4 **并不会**在配置里新增一条规则族，而是把每条不可访问目录展开成一条
 * **形态特殊的重定向规则**（见 app 侧 `MountWizard.createRules` 的 q4 分支）：
 *
 * ```
 * source = <cacheDir>/<相对路径>       target = <sdDir>/<相对路径>
 * ```
 *
 * 其中 `cacheDir = /storage/emulated/<user>/Android/data/<pkg>/cache`、
 * `sdDir = /storage/emulated/<user>`。该形态由 `inaccessibleMarker`
 * （恒为 `cacheDir to sdDir`）决定，`MountWizard.retrodictAnswers` 也用
 * **同一个形态**把规则反解回 q4 答案。
 *
 * 因此运行时**不需要改动配置持久化格式**，就能从已投影的规则里精确还原
 * 「哪些 target 是不可访问的」。把判据抽成本对象的纯函数，是为了让投影器与
 * 界面共用同一份真相，避免两处各写一份导致漂移。
 *
 * ## 为什么必须显式识别
 *
 * 只看挂载行为，q4 规则与「把某目录重定向到应用自己的 cache 子目录」在文件系统上
 * **完全等价**：目录仍然存在（只是一个空目录），`File.exists()` 为真。
 * 于是应用侧的 `File` 访问看似被隔离，但 MediaStore 查询结果仍会把
 * **真实文件整条吐给应用**（文件名 / `_data` / 时间 / id 全在），
 * 「完全识别不到」根本没做到。只有先识别出这些 target，才能在
 * MediaProvider 的 FUSE 与 query 两条链上真正拒绝。
 */
object InaccessiblePathDeriver {

    /**
     * 从同一 (package, userId) 下的有序重定向规则里识别「不可访问」的 target。
     *
     * @param rules 该 (package, userId) 下的全部重定向规则（顺序无关）
     * @param sdDir 该用户的共享存储根，例如 `/storage/emulated/0`
     * @param cacheDir 该用户下该包的应用专属 cache 目录，
     *                 例如 `/storage/emulated/0/Android/data/pkg/cache`
     * @return 被视为「不可访问」的 target 集合（应用应当完全识别不到的可见路径）
     */
    fun derive(
        rules: Iterable<RedirectRule>,
        sdDir: String,
        cacheDir: String,
    ): Set<String> {
        val sd = sdDir.trimEnd('/')
        val cache = cacheDir.trimEnd('/')
        if (sd.isEmpty() || cache.isEmpty()) {
            return emptySet()
        }
        val targets = LinkedHashSet<String>()
        for (rule in rules) {
            val target = rule.target.trimEnd('/')
            if (!startsWithPath(target, sd)) {
                continue
            }
            // relative 为 ""（target == sdDir）时是 q1 根重定向：整块共享存储进沙箱，
            // 语义是「重定向」而不是「不可访问」，必须排除，否则会把整个存储判为不可访问。
            val relative = target.substring(sd.length)
            if (relative.isEmpty()) {
                continue
            }
            if (rule.source.trimEnd('/') == cache + relative) {
                targets += target
            }
        }
        return targets
    }

    /**
     * 形态判据：只从规则自身的坐标形态反解，**不依赖运行时 `externalStorageDir`**。
     *
     * ## 为什么必须有这一条
     *
     * [derive] 需要调用方提供 `sdDir` / `cacheDir`，而 server 进程里的
     * `Environment.getExternalStorageDirectory()` 与配置中记录的存储坐标
     * **未必同源**（root app_process 下取到的卷路径可能与 `/storage/emulated/<user>`
     * 不一致）。第 19 批实机快照证明这一点：
     * `redirect_policy.json` 里 `com.unistrong.hnmpm.fj` 三条 q4 规则形态完全正确，
     * 但下发的 `inaccessibleRules` 为空 —— 环境值判据整体落空，
     * 于是整条「不可访问」链路从未被激活（hook 侧 `inaccessibleEval` 全 0）。
     *
     * 形态判据不查环境：q4 规则的 source 必然是
     * `<sdRoot>/Android/data/<pkg>/cache<rel>`，其中 `<pkg>` 就是规则所属包。
     * 于是 `<sdRoot>` 可以从 source 自身切出来，`target` 必须等于 `<sdRoot><rel>`。
     * 这样既不需要知道存储根的真实取值，也天然排除跨包误判。
     *
     * @param rules 该 (package, userId) 下的全部重定向规则
     * @param packageName 规则所属包名（用于定位 marker 段，兼作跨包防误判）
     */
    fun deriveByMarker(rules: Iterable<RedirectRule>, packageName: String): Set<String> {
        if (packageName.isEmpty()) {
            return emptySet()
        }
        val marker = "/Android/data/$packageName/cache"
        val targets = LinkedHashSet<String>()
        for (rule in rules) {
            val source = rule.source.trimEnd('/')
            val target = rule.target.trimEnd('/')
            val idx = source.indexOf(marker, ignoreCase = true)
            // idx <= 0：没找到，或 marker 顶到开头（sdRoot 为空，不是合法坐标）。
            if (idx <= 0) {
                continue
            }
            val rest = source.substring(idx + marker.length)
            // 段边界检查：rest 非空时必须以 '/' 开头，否则 marker 只是更长目录名
            // （例如 .../cacheExtra）的前缀，不能当作 q4 的 cache 段。
            if (rest.isNotEmpty() && !rest.startsWith("/")) {
                continue
            }
            // rest 为空 ⇒ source 正好是 cacheDir，形态与 q1 根重定向完全一致。
            // 误判会把整个共享存储判为不可访问，必须排除。
            if (rest.isEmpty()) {
                continue
            }
            val sdRoot = source.substring(0, idx)
            if (!sdRoot.startsWith("/")) {
                continue
            }
            if (target == sdRoot + rest) {
                targets += target
            }
        }
        return targets
    }

    /**
     * 两条判据取并集：形态判据为主，环境值判据作交叉校验。
     *
     * 正常情况下两者结果一致；环境值异常时由形态判据兜住，绝不因运行时卷路径
     * 取不到预期值就让「不可访问」静默失效。
     */
    fun deriveAll(
        rules: Iterable<RedirectRule>,
        packageName: String,
        sdDir: String,
        cacheDir: String,
    ): Set<String> = deriveByMarker(rules, packageName) + derive(rules, sdDir, cacheDir)

    /**
     * 判断 [path] 是否落在任一不可访问 target 之下（含 target 自身）。
     *
     * 与只读规则同一套匹配语义：**自身 + 任意深度子孙**。只判「相等」或
     * 「直接父目录」会漏掉所有真实文件路径（`Pictures/<app>/x.jpg` 至少深两层）。
     */
    fun matchesAny(inaccessibleTargets: Collection<String>, path: String): String? {
        if (inaccessibleTargets.isEmpty() || path.isEmpty()) {
            return null
        }
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        // 先长后短：命中更具体的规则，便于诊断时报告是哪条规则拦下的。
        return inaccessibleTargets
            .asSequence()
            .map { it.trimEnd('/') }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .firstOrNull { startsWithPath(normalized, it) }
    }

    private fun startsWithPath(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith(prefix + "/")
}
