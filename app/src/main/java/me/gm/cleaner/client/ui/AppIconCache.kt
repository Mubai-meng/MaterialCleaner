package me.gm.cleaner.client.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 应用列表图标缓存。
 *
 * 原实现把缓存内嵌在 [AppListAdapter] 的 companion 里，只能在绑定阶段**懒加载**：
 * 首次进入主界面时每个 item 都要在主线程走一次 `PackageManager.loadIcon()`。
 * 实测 15 个「已配置应用」的 `onBindViewHolder` 合计约 1.6s（单条 30–400ms），
 * 表现为"列表出来了但图标一格一格慢慢填"。
 *
 * 抽成独立对象后支持 [preloadOne]：由 [AppListLoader] 在后台线程、带时间预算地
 * 预取「配置过规则」的包（通常十几个），绑定阶段直接命中，主线程不再触碰 PM。
 *
 * 缓存只存 `ConstantState`（图标字节的共享表示），实际 Drawable 仍在主线程按
 * `resources` 新建，与旧实现的复用方式等价。
 */
object AppIconCache {

    private class CachedIcon(
        val lastUpdateTime: Long,
        val constantState: Drawable.ConstantState,
    )

    /**
     * 96 项：足够覆盖「已配置应用」（本机实测 15 个）外加一屏回滚。
     * 单条 ConstantState 量级 ~50 KB ⇒ 最坏约 5 MB，与旧 48 项相比增加有限。
     */
    private val cache = object : LruCache<String, CachedIcon>(96) {}

    /** 命中且应用未更新时返回可复用的 ConstantState。 */
    fun get(packageName: String, lastUpdateTime: Long): Drawable.ConstantState? =
        cache.get(packageName)?.takeIf { it.lastUpdateTime == lastUpdateTime }?.constantState

    fun put(packageName: String, lastUpdateTime: Long, constantState: Drawable.ConstantState) {
        cache.put(packageName, CachedIcon(lastUpdateTime, constantState))
    }

    /**
     * 预取单个包的图标并写入缓存。
     *
     * 必须在**非主线程**调用（`loadIcon` 走 PackageManager）。
     * 任一步失败都静默跳过 —— 绑定阶段仍会回退到原来的懒加载路径，行为不退化。
     */
    fun preloadOne(context: Context, packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        if (get(packageName, packageInfo.lastUpdateTime) != null) {
            return
        }
        val state = runCatching {
            packageInfo.applicationInfo?.loadIcon(context.packageManager)?.constantState
        }.getOrNull() ?: return
        put(packageName, packageInfo.lastUpdateTime, state)
    }
}
