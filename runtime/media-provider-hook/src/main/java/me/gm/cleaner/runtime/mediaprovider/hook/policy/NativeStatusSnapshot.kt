package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONArray
import org.json.JSONObject

/**
 * native hook 初始化状态快照。
 *
 * 按 C2 协议解析 BPF 三字段：fillEntries 主拦截点、install 兼容点、effective 有效位。
 */
internal data class NativeStatusSnapshot(
    val fuseAvailable: Boolean = true,
    val fuseLibraryLoaded: Boolean = false,
    val fuseLibraryName: String = "",
    val hookMode: String = "UNKNOWN",
    val fuseJniLoadMode: String = "UNKNOWN",
    val embeddedFuseJniFound: Boolean = false,
    val containsMountHooked: Boolean = false,
    val startsWithHooked: Boolean = false,
    val isFuseBpfEnabledHooked: Boolean = false,
    val fuseReqUserdataHooked: Boolean = false,
    val fillEntriesHooked: Boolean = false,
    val installHooked: Boolean = false,
    val effectiveHooked: Boolean = false,
    val containsMountMethod: String = "",
    val startsWithMethod: String = "",
    val isFuseBpfEnabledMethod: String = "",
    val fuseReqUserdataMethod: String = "",
    val fillEntriesMethod: String = "",
    val installMethod: String = "",
    val xhookRefreshCalled: Boolean = false,
    val lastError: String = "",
) {
    val coreAvailable: Boolean
        get() = containsMountHooked

    val fullAvailable: Boolean
        get() = containsMountHooked &&
                startsWithHooked &&
                isFuseBpfEnabledHooked &&
                fuseReqUserdataHooked &&
                effectiveHooked

    val missingSymbols: List<String>
        get() = buildList {
            if (!fuseLibraryLoaded) return@buildList
            if (!containsMountHooked) add("containsMount")
            if (!startsWithHooked) add("startsWith")
            if (!isFuseBpfEnabledHooked) add("isFuseBpfEnabled")
            if (!fuseReqUserdataHooked) add("fuseReqUserdata")
            if (!effectiveHooked) add("fillEntries/install")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("fuseAvailable", fuseAvailable)
        put("fuseLibraryLoaded", fuseLibraryLoaded)
        put("fuseLibraryName", fuseLibraryName)
        put("hookMode", hookMode)
        put("fuseJniLoadMode", fuseJniLoadMode)
        put("embeddedFuseJniFound", embeddedFuseJniFound)
        put("xhookRefreshCalled", xhookRefreshCalled)
        put("coreAvailable", coreAvailable)
        put("fullAvailable", fullAvailable)
        put("symbols", JSONObject().apply {
            put("containsMount", containsMountHooked)
            put("startsWith", startsWithHooked)
            put("isFuseBpfEnabled", isFuseBpfEnabledHooked)
            put("fuseReqUserdata", fuseReqUserdataHooked)
            put("fillEntries", fillEntriesHooked)
            put("install", installHooked)
            put("effective", effectiveHooked)
        })
        put("symbolMethods", JSONObject().apply {
            put("containsMount", containsMountMethod)
            put("startsWith", startsWithMethod)
            put("isFuseBpfEnabled", isFuseBpfEnabledMethod)
            put("fuseReqUserdata", fuseReqUserdataMethod)
            put("fillEntries", fillEntriesMethod)
            put("install", installMethod)
        })
        put("missingSymbols", JSONArray(missingSymbols))
        put("lastError", lastError)
    }
}

internal object NativeStatusSnapshotParser {
    internal fun parse(json: String): NativeStatusSnapshot {
        return try {
            val root = JSONObject(json)
            val symbols = root.optJSONObject("symbols")
            val symbolMethods = root.optJSONObject("symbolMethods")
            val fillEntriesHooked = symbols?.optBoolean("fillEntries", false) ?: false
            val installHooked = symbols?.optBoolean("install", false) ?: false
            val effectiveHooked = symbols?.optBoolean("effective", fillEntriesHooked || installHooked)
                ?: (fillEntriesHooked || installHooked)
            NativeStatusSnapshot(
                fuseAvailable = root.optBoolean("fuseAvailable", true),
                fuseLibraryLoaded = root.optBoolean("fuseLibraryLoaded", false),
                fuseLibraryName = root.optString("fuseLibraryName", ""),
                hookMode = root.optString("hookMode", "UNKNOWN"),
                fuseJniLoadMode = root.optString("fuseJniLoadMode", "UNKNOWN"),
                embeddedFuseJniFound = root.optBoolean("embeddedFuseJniFound", false),
                containsMountHooked = symbols?.optBoolean("containsMount", false) ?: false,
                startsWithHooked = symbols?.optBoolean("startsWith", false) ?: false,
                isFuseBpfEnabledHooked = symbols?.optBoolean("isFuseBpfEnabled", false) ?: false,
                fuseReqUserdataHooked = symbols?.optBoolean("fuseReqUserdata", false) ?: false,
                fillEntriesHooked = fillEntriesHooked,
                installHooked = installHooked,
                effectiveHooked = effectiveHooked,
                containsMountMethod = symbolMethods?.optString("containsMount", "") ?: "",
                startsWithMethod = symbolMethods?.optString("startsWith", "") ?: "",
                isFuseBpfEnabledMethod = symbolMethods?.optString("isFuseBpfEnabled", "") ?: "",
                fuseReqUserdataMethod = symbolMethods?.optString("fuseReqUserdata", "") ?: "",
                fillEntriesMethod = symbolMethods?.optString("fillEntries", "") ?: "",
                installMethod = symbolMethods?.optString("install", "") ?: "",
                xhookRefreshCalled = root.optBoolean("xhookRefreshCalled", false),
                lastError = root.optString("lastError", ""),
            )
        } catch (e: Exception) {
            NativeStatusSnapshot(lastError = "Invalid native status: ${describe(e)}")
        }
    }

    internal fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) error.javaClass.name else "${error.javaClass.name}: $message"
    }
}
