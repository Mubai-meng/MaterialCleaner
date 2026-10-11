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
        "https://api.github.com/repos/firesahc/MaterialCleaner/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String,
        val body: String? = null,
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
            ReleaseInfo(
                tagName = json.optString("tag_name", ""),
                htmlUrl = json.optString("html_url", ""),
                body = json.optString("body", null)
            )
        }.getOrNull()
    }

    fun isNewer(current: String, latest: String): Boolean {
        fun normalize(v: String) = v.trim().removePrefix("v").removePrefix("V")
        val a = normalize(current).split('.')
        val b = normalize(latest).split('.')
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val ai = a.getOrElse(i) { "0" }
            val bi = b.getOrElse(i) { "0" }
            val cmp = try {
                ai.toLong().compareTo(bi.toLong())
            } catch (e: NumberFormatException) {
                ai.compareTo(bi)
            }
            if (cmp != 0) return cmp < 0
        }
        return false
    }

    suspend fun checkAndNotify(context: Context) {
        val release = fetchLatestRelease() ?: return
        val current = BuildConfig.VERSION_NAME
        if (!isNewer(current, release.tagName)) return
        if (release.tagName == RootPreferences.lastNotifiedUpdateVersion) return
        NotificationService.notifyUpdateAvailable(context, release)
        RootPreferences.lastNotifiedUpdateVersion = release.tagName
    }
}
