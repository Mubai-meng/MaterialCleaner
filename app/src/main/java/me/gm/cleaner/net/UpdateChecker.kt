package me.gm.cleaner.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.NotificationService
import me.gm.cleaner.dao.RootPreferences
import org.json.JSONObject
import java.net.URL

object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Mubai-meng/MaterialCleaner/releases/latest"

    data class ReleaseInfo(
        /** 展示用名字：优先 release name，回落到 tag（可能是不含版本号的中文标签）。 */
        val displayName: String,
        val htmlUrl: String,
        val body: String? = null,
        /** 从 tag 或 name 里抽出的可比较版本号；抽不出则为 null。 */
        val version: String? = null,
    )

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST_RELEASE_URL).openConnection().apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }
            conn.getInputStream().bufferedReader().use { it.readText() }
        }.mapCatching { text ->
            val json = JSONObject(text)
            val tag = json.optString("tag_name", "")
            val name = json.optString("name", "")
            ReleaseInfo(
                displayName = name.takeIf { it.isNotBlank() } ?: tag,
                htmlUrl = json.optString("html_url", ""),
                body = json.optString("body").takeIf { it.isNotEmpty() },
                version = extractVersion(tag) ?: extractVersion(name),
            )
        }.getOrNull()
    }

    /**
     * 从任意字符串里抽出第一个「点分数字」版本号（如 `Cleaner_6.0.0` → `6.0.0`）。
     *
     * ## 为什么必须这么做（6.0.5 实证）
     *
     * 本仓库的 release 是用中文标题建的，`tag_name` 就是 **「正式版」**，
     * 真正的版本号在 `name`（`Cleaner_6.0.0`）里。旧实现直接拿 `tag_name`
     * 去比较，`"正式版".toLong()` 抛 NumberFormatException ⇒ 退化到字符串比较
     * ⇒ `"6".compareTo("正") < 0`（Unicode 码位：`6`=0x36，`正`=0x6B63）⇒
     * **永远判定"有新版本"**，哪怕本地 6.0.5 已远超 6.0.0。
     *
     * 抽取规则：允许前缀 `v`/`V` 与任意非数字前缀（`Cleaner_`、`release-` 等），
     * 取第一段 `数字(.数字)+`。只有单段数字（如 `6`）**不**算版本号——
     * 那更可能是编号或日期，认了反而会与 `6.0.5` 的前缀撞车。
     */
    internal fun extractVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val m = VERSION_RE.find(raw) ?: return null
        // 必须至少有一个点，单段数字不当作版本号（见上面的理由）。
        return m.value.takeIf { it.contains('.') }
    }

    /**
     * `latest` 是否比 `current` 新。
     *
     * **任一侧版本号不可解析 ⇒ 返回 false（判为"已是最新"）**。
     * 宁可漏报也不误报：误报会把用户反复推到一个并不存在的更新上，
     * 而漏报只会让用户手动去仓库看一眼 —— 后者代价小得多。
     */
    fun isNewer(current: String, latest: String): Boolean {
        val cur = extractVersion(current) ?: return false
        val lat = extractVersion(latest) ?: return false
        val a = cur.split('.')
        val b = lat.split('.')
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val ai = a.getOrElse(i) { "0" }.toLongOrNull() ?: 0L
            val bi = b.getOrElse(i) { "0" }.toLongOrNull() ?: 0L
            if (ai != bi) return ai < bi
        }
        return false
    }

    /**
     * 检查结果。
     *
     * 职责边界：**检查（查询）与呈现（投递）分离**。本对象只判定结果，
     * 由调用方按场景决定如何呈现——手动检查需要当场反馈，自动检查才发通知。
     * 二者混在一个函数里会导致自动路径的去重规则污染手动路径：
     * 手动检查命中"已通知过"后静默，用户得不到任何回答。
     */
    sealed interface UpdateCheckResult {
        /** 已是最新版本。 */
        data class UpToDate(val current: String) : UpdateCheckResult

        /** 存在新版本。 */
        data class Available(val release: ReleaseInfo) : UpdateCheckResult

        /** 检查失败（网络不可达或响应无法解析）。 */
        data class Failed(val reason: String? = null) : UpdateCheckResult
    }

    /**
     * 只检查不呈现：返回结果供调用方决定呈现方式。
     */
    suspend fun check(): UpdateCheckResult {
        val release = fetchLatestRelease() ?: return UpdateCheckResult.Failed()
        val current = BuildConfig.VERSION_NAME
        // 用抽出的版本号比较，绝不能用 tag（tag 可能是「正式版」这类中文标签）。
        // release.version 为 null ⇒ tag 与 name 里都抽不出点分版本号 ⇒ 无法判定，
        // 按「已是最新」处理：宁可漏报一次更新，也绝不再误报（第 55 批实测：
        // tag 是中文标签时旧实现恒判「有新版本」，6.0.5 会提示降到 6.0.0）。
        val latest = release.version
        if (latest == null || !isNewer(current, latest)) {
            return UpdateCheckResult.UpToDate(current)
        }
        return UpdateCheckResult.Available(release)
    }

    /**
     * 自动路径：发现新版本时发通知。
     *
     * 面向无人值守场景（App 启动、开机自启），因此失败与"已是最新"都不打扰用户；
     * 同一版本只通知一次。手动检查走 [check] 并当场反馈，不受此去重约束。
     */
    suspend fun checkAndNotify(context: Context) {
        val result = check()
        if (result !is UpdateCheckResult.Available) return
        val release = result.release
        // 去重键用版本号而非 tag：tag 可被改（本次就遇到过 tag 是中文、
        // 版本号在 name 里的情形），拿它做键会让同一版本反复通知。
        val key = release.version ?: release.displayName
        if (key == RootPreferences.lastNotifiedUpdateVersion) return
        NotificationService.notifyUpdateAvailable(context, release)
        RootPreferences.lastNotifiedUpdateVersion = key
    }

    /**
     * 匹配第一个「数字(.数字)+」形式的版本号。
     *
     * 前缀不限（`v1.2.3` / `Cleaner_6.0.0` / `release-2.0` 都能命中），
     * 但必须在数字段前有边界考量：这里直接找子串，故 `abc6.0.0def` 也会命中
     * —— 对本仓库的命名（`Cleaner_6.0.0`）是想要的行为，且不会出现
     * 一个字符串里混两个不相干版本号的情形。
     */
    private val VERSION_RE = Regex("""\d+(?:\.\d+)+""")
}
