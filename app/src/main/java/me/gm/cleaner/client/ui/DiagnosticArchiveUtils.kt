package me.gm.cleaner.client.ui

/**
 * 诊断包抓取和分享工具。
 */
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ClientErrorJournal
import me.gm.cleaner.client.OrchestratedLayerStatus
import me.gm.cleaner.client.OrchestratedRuntimeStatus
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** App 进程侧错误事件流水在诊断包中的条目名。 */
private const val CLIENT_JOURNAL_ENTRY = "client/errors/journal.jsonl"

fun Fragment.exportDiagnosticsArchiveAndShare(context: Context) {
    AlertDialog.Builder(context)
        .setTitle(R.string.diagnostics_archive_privacy_title)
        .setMessage(R.string.diagnostics_archive_privacy_message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.diagnostics_archive_privacy_confirm) { _, _ ->
            createDiagnosticsArchiveAndShare(context)
        }
        .show()
}

private fun Fragment.createDiagnosticsArchiveAndShare(context: Context) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            cleanupOldDiagnosticArchives(context)
            val logFile = File(
                context.cacheDir,
                "material-cleaner-diagnostics-${System.currentTimeMillis()}.zip"
            )
            val pfd = runCatching { CleanerClient.service?.openDiagnosticsArchive() }
                .onFailure {
                    if (BuildConfig.DEBUG) Log.w("CleanerTest", "server diagnostics unavailable", it)
                }
                .getOrNull()
            if (pfd != null) {
                copyFromServerArchive(pfd, logFile)
                appendClientJournalEntry(logFile)
            } else {
                createFallbackArchive(logFile)
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", logFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.share_diagnostics_archive))
                )
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("CleanerTest", "diagnostics archive export failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "${context.getString(R.string.diagnostics_archive_failed)} ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

private fun copyFromServerArchive(pfd: ParcelFileDescriptor, target: File) {
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
        FileOutputStream(target).use { output ->
            input.copyTo(output)
        }
    }
    if (target.length() == 0L) {
        throw IllegalStateException("diagnostics archive is empty")
    }
}

/**
 * 向已生成的诊断包追加 App 进程侧错误事件流水
 * （client/errors/journal.jsonl，与 server 端 errors/journal.jsonl 同格式）。
 *
 * Java 标准库不支持向现有 zip 追加条目，采用全量重写：
 * 读出原包全部条目后连同新条目一起重新压缩。
 *
 * ⚠️ 本条目此前是**唯一绕过脱敏**的：server 路径下它由这里 raw 写入
 * （不经 `addTextEntry` → `redact`），而 fallback 路径下的同名条目**是脱敏的**
 * ⇒ 两条产出路径不一致，而 `manifest.txt` 却标着 `redacted=true`。
 * 现在统一走 [redact]。当前 journal 内容只有 code/atElapsed/subject/detail 无标识符，
 * 但一旦将来 detail 里带上路径或指纹，这个不一致就会变成真实泄露。
 *
 * 对**已脱敏**的 server 归档再跑一次 [redact] 是幂等的：
 * `<hex-id:xxxxxxxx>` 只有 8 位 hex（不匹配 ≥24 位规则），
 * `/data/app/<id:xxxxxxxx>` 只有一个路径段（不匹配两段规则）。
 */
private fun appendClientJournalEntry(zipFile: File) {
    val content = ClientErrorJournal.exportJsonL()
    if (content.isBlank()) return
    runCatching {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                entries += entry.name to zin.readBytes()
                entry = zin.nextEntry
            }
        }
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zout ->
            for ((name, bytes) in entries) {
                if (name == CLIENT_JOURNAL_ENTRY) continue
                zout.putNextEntry(java.util.zip.ZipEntry(name))
                zout.write(bytes)
                zout.closeEntry()
            }
            zout.putNextEntry(java.util.zip.ZipEntry(CLIENT_JOURNAL_ENTRY))
            zout.write(redact(content).toByteArray(Charsets.UTF_8))
            zout.closeEntry()
        }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("CleanerTest", "append client journal failed", it)
    }
}

private fun createFallbackArchive(target: File) {
    ZipOutputStream(FileOutputStream(target)).use { zip ->
        val status = runCatching { CleanerClient.getOrchestratedStatus() }
            .getOrNull()
        addTextEntry(zip, "privacy.txt", fallbackPrivacyNotice())
        addTextEntry(zip, "manifest.txt", buildFallbackManifest())
        addTextEntry(zip, "summary_zh-CN.txt", buildFallbackSummaryZhCn(status))
        addTextEntry(
            zip,
            "client/errors/journal.jsonl",
            ClientErrorJournal.exportJsonL()
        )
        addCommandEntry(
            zip,
            "logs/app_logcat_threadtime_recent.txt",
            listOf("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash", "-t", "2000")
        )
        addTextEntry(zip, "status/app_visible_status.txt", status?.toString() ?: "server unavailable")
    }
}

private fun fallbackPrivacyNotice(): String = buildString {
    appendLine("This fallback diagnostics package was generated by the app process.")
    appendLine("It may include device details, app-visible logcat output,")
    appendLine("and the app-visible service status.")
    appendLine("Basic identifiers such as build fingerprint, APK source paths,")
    appendLine("and long hex-like tokens are redacted before export.")
}

private fun buildFallbackSummaryZhCn(status: OrchestratedRuntimeStatus?): String = buildString {
    appendLine("Material Cleaner 诊断包概览（App fallback）")
    appendLine("生成时间：${System.currentTimeMillis()}")
    appendLine()
    appendLine("说明：")
    appendLine("- 这是服务端诊断不可用时由 App 进程生成的 fallback 诊断包。")
    appendLine("- 数据范围比完整服务端诊断包小，主要包含 App 可见状态和近期 logcat。")
    appendLine("- logs/app_logcat_threadtime_recent.txt：App 进程可抓取的近期日志。")
    appendLine("- status/app_visible_status.txt：App 通过 Binder 读取到的服务状态。")
    appendLine()
    appendLine("连接状态：")
    appendLine("- serverConnected=${CleanerClient.service != null}")
    appendLine("- serverVersion=${CleanerClient.serverVersion}")
    if (status == null) {
        appendLine("- orchestratedStatus=unavailable")
    } else {
        appendLine("- health=${status.health}")
        appendFallbackLayerSummary("VFS", status.vfs)
        appendFallbackLayerSummary("MediaProvider Java Hook", status.mediaProviderJavaHook)
        appendFallbackLayerSummary("FUSE Native Hook", status.fuseNativeHook)
        appendFallbackLayerSummary("DataBus", status.dataBus)
        appendFallbackLayerSummary("控制面", status.controlPlane)
    }
    appendLine()
    appendLine("如果需要完整链路证据，请优先修复服务端连接后重新导出诊断包。")
}

private fun StringBuilder.appendFallbackLayerSummary(
    label: String,
    layer: OrchestratedLayerStatus,
) {
    val error = layer.lastError.orEmpty()
    val suffix = if (error.isBlank() || error == "null") "" else "，错误=$error"
    appendLine("- $label：${layer.state}$suffix")
}

private fun buildFallbackManifest(): String = buildString {
    appendLine("createdAt=${System.currentTimeMillis()}")
    appendLine("redacted=true")
    appendLine("source=app-fallback")
    appendLine("serverConnected=${CleanerClient.service != null}")
    appendLine("serverVersion=${CleanerClient.serverVersion}")
    appendLine("sdk=${Build.VERSION.SDK_INT}")
    appendLine("release=${Build.VERSION.RELEASE}")
    appendLine("manufacturer=${Build.MANUFACTURER}")
    appendLine("brand=${Build.BRAND}")
    appendLine("model=${Build.MODEL}")
    appendLine("device=${Build.DEVICE}")
    appendLine("fingerprint=${Build.FINGERPRINT}")
}

private fun addCommandEntry(zip: ZipOutputStream, entryName: String, command: List<String>) {
    val result = runCommand(command)
    addTextEntry(zip, entryName, buildString {
        appendLine("$ ${command.joinToString(" ")}")
        appendLine("exitCode=${result.exitCode}")
        appendLine("timedOut=${result.timedOut}")
        appendLine()
        append(result.output)
    })
}

private fun runCommand(command: List<String>): CommandResult {
    var process: Process? = null
    return try {
        process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
        }
        CommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            timedOut = !finished,
            output = output,
        )
    } catch (e: Exception) {
        CommandResult(-1, timedOut = false, output = e.stackTraceToString())
    } finally {
        process?.destroy()
    }
}

private fun addTextEntry(zip: ZipOutputStream, entryName: String, content: String) {
    zip.putNextEntry(ZipEntry(entryName))
    zip.write(redact(content).toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

/**
 * 保 identity 的脱敏。
 *
 * ⚠️ 这是 [me.gm.cleaner.runtime.server.DiagnosticArchive.redact] 的**复制品**（app 模块
 * 只以 `runtimeOnly` 依赖 cleaner-server，编译期看不到那个 object，所以暂时无法合并）。
 * **两处必须同步改**：盐、模式、语义任一不同，两条产出路径（server 归档 / app fallback 归档）
 * 的别名就会对不上，跨归档比对随之失效。
 *
 * 旧实现把所有 ≥24 位 hex 一律换成同一个 `<hex-id>`，于是
 * `redirect{Configured,Published,Applied}Revision` 三值全等，
 * 归档无法再回答「applied 是否 == configured」。现在改为别名：
 * 同值同别名、异值异别名，且盐是常量 ⇒ 跨文件、跨归档都可比。
 */
private const val ALIAS_SALT = "MaterialCleaner.DiagnosticArchive.v1"

/** 别名取 hex 用；不用 `Character.forDigit`（`java.lang.Character` 被 `kotlin.Char` 映射挡掉）。 */
private const val HEX_DIGITS = "0123456789abcdef"

private val APP_DIR_REGEX = Regex("/data/app/(?:[^/\\s!]+/){2}[^/\\s!]*")
private val EXPAND_VOLUME_REGEX = Regex("/mnt/expand/[0-9A-Fa-f-]+")
private val HEX_ID_REGEX = Regex("(?i)\\b[0-9a-f]{24,}\\b")

private fun redact(content: String): String {
    var redacted = content
    val fingerprint = Build.FINGERPRINT
    if (!fingerprint.isNullOrBlank()) {
        redacted = redacted.replace(fingerprint, "<build-fingerprint>")
    }
    redacted = redacted
        // ⚠️ 模式**包含**前缀路径，替换串必须把前缀写回去（否则会吃掉整个 "/data/app/"）。
        .replace(APP_DIR_REGEX) { match -> "/data/app/<id:" + redactAlias(match.value) + ">" }
        .replace(EXPAND_VOLUME_REGEX) { match -> "/mnt/expand/<id:" + redactAlias(match.value) + ">" }
        .replace(HEX_ID_REGEX) { match -> "<hex-id:" + redactAlias(match.value) + ">" }
    return redacted
}

/** 见 [redact]：SHA-256(固定盐 + token) 的前 4 字节 hex。 */
private fun redactAlias(token: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest((ALIAS_SALT + token).toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(8)
    for (i in 0 until 4) {
        val b = digest[i].toInt() and 0xFF
        sb.append(HEX_DIGITS[b ushr 4])
        sb.append(HEX_DIGITS[b and 0xF])
    }
    return sb.toString()
}

private fun cleanupOldDiagnosticArchives(context: Context) {
    context.cacheDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("material-cleaner-diagnostics-") }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(3)
        ?.forEach { it.delete() }
}

private data class CommandResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val output: String,
)
