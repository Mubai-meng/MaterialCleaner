package me.gm.cleaner.runtime.server.vfs

/**
 * VFS 进程普查的「该 pid 是否纳入统计」纯策略。
 *
 * 抽出来的理由：**uid 读取失败**这条分支曾经被静默当成「非隔离」放行，
 * 是一个代价很高的默认值错误——
 *
 * `RuntimeFileUtils.read_uid()` 在 cgroup v2 形态（`0::/apps/uid_X/pid_Y`）
 * 下解析恒失败返回 -1（2026-10-10 第 13 批真机实证），于是
 * `!isIsolated(-1) == true`，**所有隔离进程都被放行**：
 * - WebView 沙箱进程（真 uid 99000、`u0a369i0`）被算成目标 app 的
 *   NOT_MOUNTED pid，污染分母并让 UI 显示「挂载异常」；
 * - 同一函数还被 Mounter 用于 PID 复用防护与 `resetAllMounts`，
 *   恒 -1 会让挂载重试永不执行、挂载永不回滚。
 *
 * 因此本策略把「读不到 uid」与「确认非隔离」当成两件不同的事：
 * 读不到 → [CensusVerdict.EXCLUDE_UID_UNREADABLE]（排除，且可单独观测），
 * 绝不退化成放行。
 *
 * 本对象不触碰 `/proc`，便于 JVM 单测。
 */
object CensusIsolationPolicy {

    /**
     * @param observedUid [RuntimeFileUtils.read_uid] 的返回值；负值表示读取失败。
     * @param frameworkSaysIsolated 框架 `UserHandle.isIsolated` 的判定（仅在
     *        [observedUid] 非负时才有意义）。
     */
    fun verdict(observedUid: Int, frameworkSaysIsolated: Boolean): CensusVerdict = when {
        // 硬规则：无法确认的 uid 一律不得放行，否则隔离进程过滤会整体失效。
        observedUid < 0 -> CensusVerdict.EXCLUDE_UID_UNREADABLE
        frameworkSaysIsolated -> CensusVerdict.EXCLUDE_ISOLATED
        else -> CensusVerdict.INCLUDE
    }

    fun shouldInclude(observedUid: Int, frameworkSaysIsolated: Boolean): Boolean =
        verdict(observedUid, frameworkSaysIsolated) == CensusVerdict.INCLUDE
}

/** 普查对单个 pid 的裁决；分开表示便于诊断「排除」到底是哪种原因。 */
enum class CensusVerdict {
    /** 纳入统计。 */
    INCLUDE,

    /** 确认为隔离进程（isolated / 沙箱渲染进程），排除。 */
    EXCLUDE_ISOLATED,

    /** uid 读取失败（进程已死或 /proc 形态未知），排除。 */
    EXCLUDE_UID_UNREADABLE,
}
