package me.gm.cleaner.runtime.server

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.server.orchestrator.MediaProviderHookLayerReporter
import me.gm.cleaner.runtime.server.orchestrator.NativeHookLayerReporter
import me.gm.cleaner.runtime.server.orchestrator.ServerErrorJournal
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensus
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包内「文件内容的取用与脱敏」。
 *
 * 职责边界：本文件只负责把文件/目录按预算放进 zip，以及把文本里的路径类标识
 * 脱敏成稳定别名。它不知道诊断包有哪些条目，也不做状态裁决。
 *
 * 与 [DiagnosticArchive] 分离的动因：包编排（摘要、状态、快照、取证命令）与
 * 文件取用是并列职责，后者的截断预算与首尾两段策略会独立演进。
 * 声明为顶层 internal，故既有调用点无需改动。
 */

internal const val MAX_TEXT_FILE_BYTES = 512 * 1024

/**
 * 启动窗口段（`cleaner-boot-*`）的归档预算：**1536 KiB**。
 *
 * 为什么比其它条目大 3 倍：这一段是 `-b main,system,crash` **无 tag 过滤**的全系统
 * 日志，实测 60 秒产出 7,249,920 B（**118 KB/s**）。512 KiB 只够 **4.3 秒**，
 * 起机关键窗口结构性不可见（第 42 批实测单文件覆盖 0.97 s / 2.89 s）。
 *
 * 数值依据（第 42 批实机样本外推）：`E`/`F` 行约占 12% 字节 ≈ 860 KB，
 * 分级裁剪给 CRITICAL 段 65% 预算 ⇒ 1536 × 0.65 ≈ 995 KB > 860 KB，
 * 因此 **E/F 能全留（step=1）**；剩余预算给白名单行与时间轴采样。
 * 降到 512 KiB 会让 E/F 自己就被采样，等于回到「证据随机丢弃」。
 *
 * 最多 [MAX_AUTO_LOG_FILES] 量的启动段（2 个分片）× 1536 KiB ≈ 3 MB，可接受。
 */
internal const val MAX_BOOT_LOG_BYTES = 1536 * 1024

/**
 * `logcat -t` 的行数上限：**按原始行计**，本机 ~700–1500 行/s ⇒ 30000 行 ≈ 20–40 s 覆盖窗。
 * 旧值 5000/3000 只覆盖几秒，等于让"聚焦运行日志"变成空文件（实测过滤后仅 7 行）。
 */
internal const val LOGCAT_TAIL_LINES = 30_000

/** 脱敏别名的固定盐。**不要改成随机值**，否则跨归档就不可比（详见 [alias]）。 */
internal const val ALIAS_SALT = "MaterialCleaner.DiagnosticArchive.v1"

/** 别名取 hex 用；不用 `Character.forDigit`（`java.lang.Character` 被 `kotlin.Char` 映射挡掉）。 */
internal const val HEX_DIGITS = "0123456789abcdef"

/**
 * 待脱敏的三类模式。
 *
 * 全部用 `replace(regex) { … }` 走 [alias]，即**保 identity** 替换；
 * 严禁退回成 `replace(regex, "<字面量>")` —— 那会让「只有取值不同」的字段
 * 在归档里变得无法区分（历史缺陷，见 [redact]）。
 */
internal val APP_DIR_REGEX = Regex("/data/app/(?:[^/\\s!]+/){2}[^/\\s!]*")

internal val EXPAND_VOLUME_REGEX = Regex("/mnt/expand/[0-9A-Fa-f-]+")

internal val HEX_ID_REGEX = Regex("(?i)\\b[0-9a-f]{24,}\\b")

internal fun addDirectoryFiles(
    zip: ZipOutputStream,
    dir: File,
    entryPrefix: String,
    maxFiles: Int,
) {
    val all = dir.listFiles()
        ?.filter { isRegularFileNoFollow(it) }
        ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        ?: emptyList()
    val files = all.take(maxFiles)
    addText(zip, "$entryPrefix/manifest.txt", buildString {
        appendLine("path=${dir.path}")
        appendLine("exists=${dir.exists()}")
        // 可审计：记录目录总数，排障者可判断证据是否完整
        appendLine("total=${all.size}")
        appendLine("included=${files.size}")
        for (file in files) {
            appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}")
        }
    })
    for (file in files) {
        addFileTail(zip, file, "$entryPrefix/${file.name}", MAX_TEXT_FILE_BYTES)
    }
}

internal fun addFileTail(zip: ZipOutputStream, file: File, entryName: String, maxBytes: Int) {
    runCatching {
        val content = buildString {
            appendLine("path=${file.path}")
            appendLine("size=${file.length()}")
            appendLine("modified=${file.lastModified()}")
            if (file.length() > maxBytes) {
                appendLine("truncated=head omitted, tailBytes=$maxBytes")
            }
            appendLine()
            append(readFileTail(file, maxBytes))
        }
        addText(zip, entryName, content)
    }.onFailure {
        addText(zip, "$entryName.error.txt", it.stackTraceToString())
    }
}

internal fun readFileTail(file: File, maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    if (file.length() <= maxBytes) {
        FileInputStream(file).use { input ->
            input.copyTo(output)
        }
    } else {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - maxBytes)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

/**
 * 只取文件头部 [maxBytes]。
 *
 * ⚠️ 当前**无调用点**：启动段（`cleaner-boot-*`）已改走
 * [DiagnosticBootLogTrimmer.addBootLogTrimmed] 的分级裁剪 —— 纯头部在实测
 * 118 KB/s 的洪峰下只覆盖 4.3 秒，等于把起机中段证据整段丢掉（第 42 批实测
 * 单文件覆盖 0.97 s）。保留本函数供「确实只关心开头 N 字节」的场景，
 * **不要**再用它处理启动段。
 */
internal fun addFileHead(zip: ZipOutputStream, file: File, entryName: String, maxBytes: Int) {
    runCatching {
        val content = buildString {
            appendLine("path=${file.path}")
            appendLine("size=${file.length()}")
            appendLine("modified=${file.lastModified()}")
            if (file.length() > maxBytes) {
                appendLine("truncated=tail omitted, headBytes=$maxBytes")
            }
            appendLine()
            append(readFileHead(file, maxBytes))
        }
        addText(zip, entryName, content)
    }.onFailure {
        addText(zip, "$entryName.error.txt", it.stackTraceToString())
    }
}

internal fun readFileHead(file: File, maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

/**
 * 取文件首尾两段并标注中段省略。
 *
 * 头段覆盖采集周期开头的现场（启动失败、Hook 未加载、挂载首轮失败都发生在这里），
 * 尾段覆盖最近现场。两者各占一半预算，中间显式写明省略了多少字节 ——
 * 读归档的人必须能一眼看出"这不是连续日志"，否则会把跳变读成事件。
 */
internal fun addFileHeadAndTail(
    zip: ZipOutputStream,
    file: File,
    entryName: String,
    maxBytes: Int,
) {
    runCatching {
        val headBytes = maxBytes / 2
        val tailBytes = maxBytes - headBytes
        val longFile = file.length() > maxBytes
        val content = buildString {
            appendLine("path=${file.path}")
            appendLine("size=${file.length()}")
            appendLine("modified=${file.lastModified()}")
            if (longFile) {
                appendLine("truncated=middle omitted, headBytes=$headBytes, tailBytes=$tailBytes")
            }
            appendLine()
            append(readFileHead(file, if (longFile) headBytes else maxBytes))
            if (longFile) {
                appendLine()
                appendLine("... [middle omitted: ${file.length() - maxBytes} bytes] ...")
                appendLine()
                append(readFileTail(file, tailBytes))
            }
        }
        addText(zip, entryName, content)
    }.onFailure {
        addText(zip, "$entryName.error.txt", it.stackTraceToString())
    }
}

internal fun addText(zip: ZipOutputStream, entryName: String, content: String) {
    zip.putNextEntry(ZipEntry(safeEntryName(entryName)))
    zip.write(redact(content).toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

/**
 * 保 identity 的脱敏。
 *
 * **旧实现的真缺陷**：把所有 ≥24 位 hex 串一律换成同一个字面量 `<hex-id>`，
 * 于是 `native_hook_status.json` 里
 * `redirectConfiguredRevision` / `redirectPublishedRevision` / `redirectAppliedRevision`
 * **三值全等** —— 归档再也无法回答「applied 是否 == configured」，而那正是该文件
 * 存在的意义（一次归档实测出现 37 处 `<hex-id>`）。
 *
 * 现在改成**别名**：同一 token 必得同一别名，不同 token 必得不同别名，因此
 * - 包内跨文件仍可比对（`redirect_policy.json` 的 revision ↔ native 段的 revision）；
 * - **跨归档仍可比对**（别名只由 token 决定，盐是常量，不含每次运行的随机量）；
 * - 仍不泄露原值（别名 = SHA-256(盐 + token) 的前 8 位 hex）；
 * - `grep '<hex-id'` 依旧命中（前缀刻意保留）。
 *
 * `/data/app/…` 同理不再整段塌成 `/data/app/<redacted>`：那会把
 * 「本模块自己的 base.apk 被加载」这类证据一起抹掉（实测因此无法从归档证明
 * `libinline.so` 的加载来源）。现在整段换成同一个别名，至少在包内可自证同源。
 *
 * ⚠️ `app/src/main/java/me/gm/cleaner/client/ui/DiagnosticArchiveUtils.kt` 里有一份
 * **复制品**（app 只以 `runtimeOnly` 依赖本模块，编译期看不到这里）。**两处必须同步改**：
 * 盐、模式、语义任一不同，server 归档与 app fallback 归档的别名就对不上。
 * 待办：把这段抽成共享类，消除复制。
 */
internal fun redact(content: String): String {
    var redacted = content
    val fingerprint = Build.FINGERPRINT
    // 用 isNullOrBlank：单元测试跑在 JVM 上，Build.FINGERPRINT 是 null（stub android.jar）。
    if (!fingerprint.isNullOrBlank()) {
        redacted = redacted.replace(fingerprint, "<build-fingerprint>")
    }
    redacted = redacted
        // ⚠️ 模式**包含**前缀路径，所以替换串必须把前缀写回去；
        // 只写 "<id:…>" 会把整个 "/data/app/" 也吃掉（已被单测抓到过一次）。
        .replace(APP_DIR_REGEX) { match -> "/data/app/<id:" + alias(match.value) + ">" }
        .replace(EXPAND_VOLUME_REGEX) { match -> "/mnt/expand/<id:" + alias(match.value) + ">" }
        .replace(HEX_ID_REGEX) { match -> "<hex-id:" + alias(match.value) + ">" }
    return redacted
}

/**
 * 脱敏别名：SHA-256(固定盐 + token) 的前 4 字节 hex。
 *
 * 盐是**编译期常量**而非每次运行的随机值 —— 这是「跨归档可比」的前提：
 * 若每次导出换个盐，两次归档里的同一 revision 就会得到不同别名，
 * 等于把「配置变了吗」这个问题也一起变成不可答。
 *
 * 取 32 bit 是因为单个归档里的 token 只有几十个，碰撞概率可忽略。
 */
internal fun alias(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((ALIAS_SALT + token).toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(8)
    for (i in 0 until 4) {
        val b = digest[i].toInt() and 0xFF
        sb.append(HEX_DIGITS[b ushr 4])
        sb.append(HEX_DIGITS[b and 0xF])
    }
    return sb.toString()
}

internal fun safeEntryName(name: String): String =
    name.replace('\\', '/').trimStart('/').replace("../", "_")

internal fun isRegularFileNoFollow(file: File): Boolean =
    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
