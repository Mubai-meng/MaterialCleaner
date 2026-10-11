package me.gm.cleaner.runtime.server.vfs.mount

import android.util.Log

/**
 * 把"因自包含而被拒绝的规则对"报出来（**每包每份计划只报一次**）。
 *
 * 这些规则对在 [me.gm.cleaner.core.storage.redirect.domain.MountPlanDeriver] 里被剔除，
 * 剔除本身是正确的（自包含 bind mount 要么注定失败、要么在自己目录里造出挂载环，
 * 详见该函数的 KDoc），但**绝不能静默** —— 静默丢弃是本模块最危险的失效形态：
 * 用户只会看到"我配了规则，它没生效"。
 *
 * 放在 Mounter 之外的理由：Mounter 已接近文件粒度门禁（G2）上限，
 * 而这是一段与挂载流程无耦合的纯日志逻辑。
 */
internal object SelfContainedMountPointReporter {

    private const val TAG = "MC_REDIRECT"

    /** `packageName + '#' + targets.hashCode()`，避免同一份计划每次 bindMount 都刷屏。 */
    private val reported = mutableSetOf<String>()

    fun report(packageName: String, targets: List<String>) {
        if (targets.isEmpty()) return
        val key = packageName + '#' + targets.hashCode()
        synchronized(reported) {
            if (!reported.add(key)) return
        }
        Log.w(
            TAG,
            "[Mounter] self-contained rule pair skipped pkg=$packageName targets=$targets " +
                    "(target lies inside its own source: realizing it would either fail as a " +
                    "self-mount or create a bind-mount loop inside the app's own directory; " +
                    "fix the rule order / widen the carve-out target instead)",
        )
    }
}
