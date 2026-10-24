package me.gm.cleaner.client

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import me.gm.cleaner.BuildConfig

/**
 * 模块自身的「隐身」开关。
 *
 * 两个独立开关，语义都是**针对本模块自己**，不是针对别的应用：
 *
 * | 开关 | 效果 | 实现 |
 * |---|---|---|
 * | 隐藏桌面图标 | 启动器里看不到本模块图标 | 禁用 `activity-alias` |
 * | 隐藏后台任务 | 最近任务（多任务）里看不到本模块 | 启动时 `excludeFromRecents` |
 *
 * ## 为什么桌面图标走 `activity-alias`
 *
 * 直接把 `LAUNCHER` 挂在 `ServiceSettingsActivity` 上再禁用它，会连 LSPosed 的
 * 「打开模块」一起杀掉（那是 `am start -n <pkg>/.client.ui.ServiceSettingsActivity`，
 * 禁用的 Activity 起不来）。改成 alias 后：
 * - 禁用 alias ⇒ 启动器图标消失；
 * - 本体 `ServiceSettingsActivity` 始终 `enabled` ⇒ LSPosed / adb 仍能打开。
 *
 * 这是本功能**唯一安全**的实现方式：用户永远有办法把自己的设置页找回来。
 *
 * ## 为什么后台任务用 AppTask.setExcludeFromRecents
 *
 * `android:excludeFromRecents` 是静态 manifest 属性，写死就只能「永远不进」或
 * 「永远进」，做不成开关。改用官方 `ActivityManager.AppTask.setExcludeFromRecents()`：
 * 它对**本应用自己的 task** 生效，`REORDER_TASKS` 是 normal 权限（安装即授予），
 * 不碰系统状态、不需要 root。
 *
 * 因此需要在两个时机各做一次：
 * - 用户在设置页拨开关时 → [setHideRecents]，当次立即生效；
 * - Activity 每次创建时 → [applyRecentsOnCreate]，因为 task 是新建的，
 *   新 task 的 excludeFromRecents 默认回到 false。
 *
 * ⚠️ 本类**只**改本模块自己的包状态，绝不对其他应用调用任何 PackageManager 隐藏接口。
 */
object SelfHidingPreferences {
    private const val TAG = "MC_SelfHide"

    private const val PREFS_NAME = "self_hiding"
    private const val KEY_HIDE_DESKTOP_ICON = "hide_desktop_icon"
    private const val KEY_HIDE_RECENTS = "hide_recents"

    /**
     * 启动器别名的 ComponentName。
     *
     * 包名前缀必须用 `BuildConfig.APPLICATION_ID`（不是 `BuildConfig.LIBRARY_PACKAGE_NAME`）：
     * 后者是库模块名 `me.gm.cleaner`，与本模块 applicationId 在加后缀的构建里会分叉。
     */
    private val launcherAlias: ComponentName
        get() = ComponentName(
            BuildConfig.APPLICATION_ID,
            "${BuildConfig.APPLICATION_ID}.LauncherAlias",
        )

    /**
     * 偏好必须放在**设备加密保护存储**（DE），不能放 CE。
     *
     * ⚠️ 这是 5.5.14 修的 P0 崩溃（5.5.13 引入）：
     *
     * 5.5.12 给 `HooksBridgeProvider` 加了 `android:directBootAware="true"`（开机握手必需），
     * 于是**用户解锁之前**就会出现 `me.gm.cleaner` 进程并执行 `App.onCreate`。
     * 而 [reconcileOnStartup] 在 `App.onCreate` 里读偏好 —— 若偏好建在 CE 存储上，
     * 未解锁时 `getSharedPreferences` 直接抛：
     *
     * ```
     * IllegalStateException: SharedPreferences in credential encrypted storage are
     *   not available until after user (id 0) is unlocked
     * at me.gm.cleaner.app.App.onCreate(App.kt:47)
     * ```
     *
     * 后果不是"读不到偏好"，而是 `Unable to create application` ⇒ 整个进程创建失败
     * ⇒ UI 显示「存储重定向未运行」（实质是 App 侧拿不到 server binder）。
     *
     * 修法与 `RootPreferences` / `ServicePreferences` / `ServiceBootStateStore` 完全一致：
     * 统一用 `createDeviceProtectedStorageContext()`。DE 存储在解锁前后都可读写，
     * 而这两个开关（桌面图标可见性、是否进最近任务）本来也不含任何用户隐私。
     */
    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 读 ──

    /**
     * 读偏好一律不抛。
     *
     * 兜底的必要性：即使已迁到 DE 存储，`App.onCreate` 仍在 Direct Boot 窗口内执行，
     * 任何一次异常都会让进程创建失败（不是"开关失效"，而是整个 App 起不来）。
     * 读不到就按 `false`（不隐藏）处理 —— 失败方向应是"图标可见、可进入设置"，
     * 绝不是"图标消失且无处可点"。
     */
    private fun readBoolean(context: Context, key: String): Boolean = try {
        prefs(context).getBoolean(key, false)
    } catch (e: Exception) {
        Log.w(TAG, "read preference $key failed, falling back to false", e)
        false
    }

    fun isHideDesktopIcon(context: Context): Boolean =
        readBoolean(context, KEY_HIDE_DESKTOP_ICON)

    fun isHideRecents(context: Context): Boolean =
        readBoolean(context, KEY_HIDE_RECENTS)

    /**
     * 当前**实际**的图标可见状态（读 PackageManager，不读偏好）。
     *
     * 必须与偏好分开读：用户可能在系统设置里手动恢复过图标，
     * 或覆盖安装后系统把 alias 重置为 manifest 默认的 enabled。
     * 只读偏好会让 UI 显示「已隐藏」而实际图标还在。
     */
    fun isDesktopIconActuallyVisible(context: Context): Boolean = try {
        val state = context.packageManager.getComponentEnabledSetting(launcherAlias)
        when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> true
        }
    } catch (e: Exception) {
        Log.w(TAG, "getComponentEnabledSetting failed for $launcherAlias", e)
        true
    }

    // ── 写 ──

    /**
     * 隐藏 / 显示桌面图标。
     *
     * 只动 alias，不动本体 Activity —— 见类文档「为什么桌面图标走 activity-alias」。
     *
     * @return true 表示已按请求生效（读回校验过），false 表示写失败或未生效
     */
    fun setHideDesktopIcon(context: Context, hide: Boolean): Boolean {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        return try {
            context.packageManager.setComponentEnabledSetting(
                launcherAlias, newState, PackageManager.DONT_KILL_APP,
            )
            // 偏好写入失败不影响本次开关是否生效（PackageManager 已经改成功了），
            // 只影响「下次冷启动要不要重放」。绝不能让它把设置页点崩。
            runCatching { prefs(context).edit { putBoolean(KEY_HIDE_DESKTOP_ICON, hide) } }
                .onFailure { Log.w(TAG, "write hide_desktop_icon preference failed", it) }
            // 读回校验：某些 ColorOS 版本上 setComponentEnabledSetting 会返回成功但不落盘。
            val visible = isDesktopIconActuallyVisible(context)
            val ok = visible != hide
            if (!ok) {
                Log.w(
                    TAG,
                    "setComponentEnabledSetting(hide=$hide) reported success but state is" +
                            " still visible=$visible — ColorOS may have blocked it",
                )
            }
            Log.i(TAG, "desktop icon hide=$hide -> visible=$visible ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "setComponentEnabledSetting(hide=$hide) failed", e)
            false
        }
    }

    /**
     * 隐藏 / 显示后台任务（最近任务界面）。
     *
     * 用官方 `ActivityManager.AppTask.setExcludeFromRecents()`：它对**自己的 task**
     * 直接生效，不需要 root，也不需要改系统状态。`REORDER_TASKS` 是 normal 权限，
     * 安装即授予。
     *
     * 之所以不用 manifest 的 `android:excludeFromRecents="true"`：那是静态属性，
     * 一旦写死就只能在「永远不进最近任务」和「永远进」之间二选一，做不成开关。
     *
     * ⚠️ 必须在 Activity 已 attach（onCreate 之后）调用；`appTasks` 为空时返回 false。
     *
     * @return true 表示已对本 task 设置成功
     */
    fun setHideRecents(context: Context, activity: Activity?, hide: Boolean): Boolean {
        val ok = try {
            val appTask = activity?.let {
                it.getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull()
            }
            if (appTask == null) {
                Log.w(TAG, "setHideRecents: no app task available (activity not attached?)")
                false
            } else {
                appTask.setExcludeFromRecents(hide)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "setHideRecents(hide=$hide) failed", e)
            false
        }
        // 偏好无论成败都写：下次冷启动会再试一次，写下来才知道"用户想要什么"。
        // 这里同样不能抛 —— 见 readBoolean 的注释。
        runCatching { prefs(context).edit { putBoolean(KEY_HIDE_RECENTS, hide) } }
            .onFailure { Log.w(TAG, "write hide_recents preference failed", it) }
        Log.i(TAG, "hide_recents=$hide applied=$ok")
        return ok
    }

    /**
     * Activity 创建时按偏好应用「隐藏后台任务」。
     *
     * 与 [reconcileOnStartup] 分开：那个在 `App.onCreate` 跑（没有 Activity），
     * 这个必须在 Activity attach 之后跑。
     */
    fun applyRecentsOnCreate(activity: Activity) {
        if (!isHideRecents(activity)) return
        try {
            activity.getSystemService(ActivityManager::class.java)
                ?.appTasks?.firstOrNull()
                ?.setExcludeFromRecents(true)
        } catch (e: Exception) {
            Log.w(TAG, "applyRecentsOnCreate failed", e)
        }
    }

    /**
     * 覆盖安装 / 升级后的自愈：把 alias 状态重新对齐到偏好。
     *
     * 必要性：`setComponentEnabledSetting` 的状态保存在 package settings 里，
     * 覆盖安装（尤其带 `-r` 且 versionCode 变化）后有概率被重置回 manifest 默认值
     * （`android:enabled="true"`）⇒ 用户勾了「隐藏桌面图标」，升级后图标又冒出来，
     * 而他因为「以为已经隐藏」不会去看桌面。
     *
     * 由 `App.onCreate` 调用，幂等、无 IO 放大（一次 getComponentEnabledSetting + 至多一次 set）。
     */
    fun reconcileOnStartup(context: Context) {
        if (!isHideDesktopIcon(context)) return
        if (!isDesktopIconActuallyVisible(context)) return   // 已经是对的，别白改
        Log.w(TAG, "desktop icon visible again after upgrade, re-hiding (preference said hidden)")
        setHideDesktopIcon(context, true)
    }
}
