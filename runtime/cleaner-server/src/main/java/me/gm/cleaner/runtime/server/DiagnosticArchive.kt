package me.gm.cleaner.runtime.server

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.server.orchestrator.ServerErrorJournal
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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticArchive {
    private const val TAG = "DiagnosticArchive"
    private const val OUTPUT_DIR = "/data/local/tmp/cleaner_diagnostics"
    private const val AUTO_LOG_DIR = "/data/local/tmp/cleaner_logs"
    private const val MAX_COMMAND_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_FILE_BYTES = 512 * 1024
    private const val MAX_AUTO_LOG_FILES = 8
    private const val MAX_EVENT_FILES = 20

    /**
     * `logcat -t` 的行数上限：**按原始行计**，本机 ~700–1500 行/s ⇒ 30000 行 ≈ 20–40 s 覆盖窗。
     * 旧值 5000/3000 只覆盖几秒，等于让"聚焦运行日志"变成空文件（实测过滤后仅 7 行）。
     */
    private const val LOGCAT_TAIL_LINES = 30_000

    /** 脱敏别名的固定盐。**不要改成随机值**，否则跨归档就不可比（详见 [alias]）。 */
    private const val ALIAS_SALT = "MaterialCleaner.DiagnosticArchive.v1"

    /** 别名取 hex 用；不用 `Character.forDigit`（`java.lang.Character` 被 `kotlin.Char` 映射挡掉）。 */
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * 待脱敏的三类模式。
     *
     * 全部用 `replace(regex) { … }` 走 [alias]，即**保 identity** 替换；
     * 严禁退回成 `replace(regex, "<字面量>")` —— 那会让「只有取值不同」的字段
     * 在归档里变得无法区分（历史缺陷，见 [redact]）。
     */
    private val APP_DIR_REGEX = Regex("/data/app/(?:[^/\\s!]+/){2}[^/\\s!]*")
    private val EXPAND_VOLUME_REGEX = Regex("/mnt/expand/[0-9A-Fa-f-]+")
    private val HEX_ID_REGEX = Regex("(?i)\\b[0-9a-f]{24,}\\b")

    fun open(server: CleanerServer): ParcelFileDescriptor {
        val file = create(server)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun create(server: CleanerServer): File {
        val outDir = prepareOutputDir()
        cleanupOldArchives(outDir)

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val archive = createArchiveFile(outDir, timestamp)
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            addText(zip, "privacy.txt", privacyNotice())
            addText(zip, "manifest.txt", buildManifest(server))
            addText(zip, "summary.txt", buildSummary(server))
            addText(zip, "summary_zh-CN.txt", buildSummaryZhCn(server))
            addStatus(zip, server)
            addFilesystem(zip)
            addErrorJournal(zip)
            addDataBus(zip)
            addLogcat(zip)
            addAutoLogs(zip)
            addCommandOutputs(zip)
        }
        setOwnerOnly(archive, executable = false)
        Log.i("MC_DIAG", JSONObject().apply {
            put("event", "diagnostics_archive_created")
            put("path", archive.path)
            put("sizeBytes", archive.length())
            put("redacted", true)
            put("summary", true)
        }.toString())
        return archive
    }

    private fun prepareOutputDir(): File {
        val outDir = File(OUTPUT_DIR)
        val path = outDir.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            Files.delete(path)
        }
        Files.createDirectories(path)
        setOwnerOnly(outDir, executable = true)
        return outDir
    }

    private fun createArchiveFile(outDir: File, timestamp: String): File {
        val archive = Files.createTempFile(
            outDir.toPath(),
            "material-cleaner-diagnostics-$timestamp-",
            ".zip"
        ).toFile()
        setOwnerOnly(archive, executable = false)
        return archive
    }

    private fun setOwnerOnly(file: File, executable: Boolean) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (executable) {
            file.setExecutable(true, true)
        }
    }

    private fun privacyNotice(): String = buildString {
        appendLine("This diagnostics package is intended for troubleshooting Material Cleaner.")
        appendLine("It may include device details, process and mount state, logcat output,")
        appendLine("DataBus snapshots, redirect rules, and file paths.")
        appendLine("Basic identifiers such as build fingerprint, APK source paths,")
        appendLine("and long hex-like tokens are redacted before export.")
        appendLine("File paths and package names are preserved because they are required")
        appendLine("to diagnose storage redirection issues.")
    }

    private fun buildManifest(server: CleanerServer): String = buildString {
        appendLine("createdAt=${System.currentTimeMillis()}")
        appendLine("redacted=true")
        appendLine("serverPid=${Os.getpid()}")
        appendLine("serverUid=${Os.getuid()}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("packageName=${server.packageInfo.packageName}")
        appendLine("sourceDir=${server.packageInfo.applicationInfo.sourceDir}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("release=${Build.VERSION.RELEASE}")
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("brand=${Build.BRAND}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        // 归档**不是**完整日志：logs/auto/*.log 只留尾部 MAX_TEXT_FILE_BYTES。
        // 把这件事提到入口文件，避免只看 manifest 的人把「归档里没有」读成「没发生」。
        appendLine("truncation=${truncationSummary()}")
    }

    /**
     * 归档会截断的条目预告。
     *
     * 动机：`logs/auto/<name>.log` 只保留**尾部** [MAX_TEXT_FILE_BYTES]（512 KiB）。
     * 实测一次 16,424,932 B 的会话日志进包后只剩 524,338 B（**2.9%**）。此前这一事实
     * 只写在被截断文件自己的第 4 行（`truncated=head omitted, tailBytes=…`），
     * 而 `manifest.txt` / `summary.txt` 里毫无提示 —— 只看入口文件的人会把
     * 「归档里没有」读成「没发生」；再叠加 ColorOS `LOG_FLOWCTRL` 的按进程丢日志
     * （实测单次丢弃 2,470 行），就是**双重不完整**。
     *
     * 在写 manifest **之前**先算一遍（只读 `length()`，不做 IO，不写任何文件）。
     */
    private fun truncationSummary(): String {
        val files = File(AUTO_LOG_DIR).listFiles()
            ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_AUTO_LOG_FILES)
            ?: emptyList()
        val truncated = files.filter { it.length() > MAX_TEXT_FILE_BYTES }
        if (truncated.isEmpty()) {
            return "none?autoLogsKept=tail(${MAX_TEXT_FILE_BYTES}B)"
        }
        return truncated.joinToString("; ") { file ->
            "${file.name} keptLast=${MAX_TEXT_FILE_BYTES}B dropped=${file.length() - MAX_TEXT_FILE_BYTES}B"
        }
    }

    private fun buildSummary(server: CleanerServer): String = buildString {
        appendLine("Material Cleaner diagnostics summary")
        appendLine("Generated at: ${System.currentTimeMillis()}")
        appendLine()
        appendLine("Read this first:")
        appendLine("- status/orchestrated_status.json has the full five-layer state.")
        appendLine("- status/native_hook_status_pretty.json has MediaProvider/FUSE hook details.")
        appendLine("- databus/health.json has queue, snapshot, and permission health.")
        appendLine("- logs/logcat_material_cleaner_filtered.txt has focused runtime logs.")
        appendLine("  ⚠️ Both logs/logcat_* captures are capped by '-t' counted in RAW lines (~700–1500/s),")
        appendLine("  so they cover roughly the last 20–40 s only even with LOGCAT_TAIL_LINES=$LOGCAT_TAIL_LINES.")
        appendLine("- commands/media_provider_maps.txt and commands/media_provider_mountinfo.txt show MediaProvider process state.")
        appendLine("- ⚠️ This archive is NOT the full log: logs/auto/*.log keeps only the TAIL of each file")
        appendLine("  (see the 'truncation=' line in manifest.txt), and logcat itself drops lines under")
        appendLine("  ColorOS LOG_FLOWCTRL per-process quota. Absence in this archive != did not happen.")
        appendLine("  In EMBEDDED hook mode (EMBEDDED_GOT_PATCH) native libs load from inside an APK/APEX, so")
        appendLine("  /proc/<pid>/maps shows no libinline/libfuse_jni entry; use the 'nativeloader: Load ...'")
        appendLine("  lines in the logs as the loading evidence.")
        appendLine("- To attribute E Parcel (the largest E tag every batch): do NOT go looking for a call")
        appendLine("  site. Compute  E Parcel count / inboundHookCallbacks  (status/orchestrated_status.json,")
        appendLine("  dataBus layer). A stable ratio across sessions = fixed framework overhead per binder")
        appendLine("  transaction; a flat numerator = unrelated to our IPC. Either way it settles the question.")
        appendLine()

        val status = runCatching { JSONObject(server.layerOrchestrator.collectStatusJson()) }
            .onFailure {
                appendLine("Runtime status: unavailable (${it.javaClass.name}: ${it.message})")
            }
            .getOrNull()
        if (status != null) {
            appendLine("Runtime health: ${status.optString("health", "UNKNOWN")}")
            appendLayerSummary(status, "vfs", "VFS")
            appendLayerSummary(status, "mediaProviderJavaHook", "MediaProvider Java Hook")
            appendLayerSummary(status, "fuseNativeHook", "FUSE Native Hook")
            appendLayerSummary(status, "dataBus", "DataBus")
            appendLayerSummary(status, "controlPlane", "Control Plane")
            appendLine()
        }

        val health = runCatching { DataBus.checkHealth(repair = true) }.getOrNull()
        if (health != null) {
            appendLine("DataBus:")
            appendLine("- initialized=${health.initialized}, healthy=${health.healthy}, criticalSnapshotsReady=${health.criticalSnapshotsReady}")
            appendLine("- eventQueueCounts(archived/cumulative)=${health.eventQueueCounts}")
            appendLine("- eventQueuePending(backlog)=${health.pendingEventQueueCounts}")
            appendLine("- leaseCounts=${health.leaseCounts}")
            appendLine("- missingDirectories=${health.missingDirectories.size}, permissionIssues=${health.permissionIssues.size}")
            appendLine()
        }

        val nativeStatus = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (nativeStatus != null) {
            appendNativeHookSummary(nativeStatus)
            appendLine()
        }

        val platformCaps = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (platformCaps != null) {
            appendLine("Platform capabilities:")
            appendLine("- sdk=${platformCaps.optInt("sdkVersionInt", 0)}, fuse=${platformCaps.optBoolean("fuseAvailable", false)}, fuseBpf=${platformCaps.optBoolean("isFuseBpfEnabled", false)}")
            appendLine("- mediaProvider=${platformCaps.optString("mediaProviderPackageName", "")}")
            appendLine("- fuseJniLoadMode=${platformCaps.optString("fuseJniLoadMode", "UNKNOWN")}, nativeHookMode=${platformCaps.optString("supportedNativeHookMode", "UNKNOWN")}")
            appendLine()
        }

        appendLine("If the package is hard to read, start from this file, then open the referenced files above.")
    }

    private fun buildSummaryZhCn(server: CleanerServer): String = buildString {
        appendLine("Material Cleaner 诊断包概览")
        appendLine("生成时间：${System.currentTimeMillis()}")
        appendLine()
        appendLine("优先阅读：")
        appendLine("- status/orchestrated_status.json：完整五层运行状态。")
        appendLine("- status/native_hook_status_pretty.json：MediaProvider/FUSE Hook 详情。")
        appendLine("- databus/health.json：队列、快照和权限健康状态。")
        appendLine("- logs/logcat_material_cleaner_filtered.txt：聚焦运行日志。")
        appendLine("  ⚠️ logs/logcat_* 两份捕获的 `-t` 计的是**原始行数**（本机 ~700–1500 行/s），")
        appendLine("  即便上限已提到 $LOGCAT_TAIL_LINES 行，实际也只覆盖最近 20–40 秒。")
        appendLine("- commands/media_provider_maps.txt 与 commands/media_provider_mountinfo.txt：MediaProvider 进程状态。")
        appendLine("- ⚠️ 本归档**不是**完整日志：logs/auto/*.log 每份只保留**尾部**（见 manifest.txt 的")
        appendLine("  'truncation=' 行），且 logcat 本身会被 ColorOS LOG_FLOWCTRL 按进程限额丢弃。")
        appendLine("  「归档里没有」≠「没发生」。另：EMBEDDED 模式（EMBEDDED_GOT_PATCH）下 native 库是从")
        appendLine("  APK/APEX 包内加载的，/proc/<pid>/maps 里不会有 libinline/libfuse_jni 条目 ——")
        appendLine("  加载证据要看日志里的 'nativeloader: Load ...' 行。")
        appendLine("- `E Parcel`（每批条目最多的 E tag）的归因方式：**不要再找调用点**，改算")
        appendLine("  `E Parcel 条数 ÷ inboundHookCallbacks`（status/orchestrated_status.json 的 dataBus 段）。")
        appendLine("  比值跨会话稳定 ⇒ 每次 binder 事务的固定框架开销，与业务无关；分子恒定不随它变 ⇒ 与我们无关。")
        appendLine()

        val status = runCatching { JSONObject(server.layerOrchestrator.collectStatusJson()) }
            .onFailure {
                appendLine("运行状态：不可用（${it.javaClass.name}: ${it.message}）")
            }
            .getOrNull()
        if (status != null) {
            appendLine("运行健康：${status.optString("health", "UNKNOWN")}")
            appendLayerSummaryZhCn(status, "vfs", "VFS")
            appendLayerSummaryZhCn(status, "mediaProviderJavaHook", "MediaProvider Java Hook")
            appendLayerSummaryZhCn(status, "fuseNativeHook", "FUSE Native Hook")
            appendLayerSummaryZhCn(status, "dataBus", "DataBus")
            appendLayerSummaryZhCn(status, "controlPlane", "控制面")
            appendLine()
        }

        val health = runCatching { DataBus.checkHealth(repair = true) }.getOrNull()
        if (health != null) {
            appendLine("DataBus：")
            appendLine("- initialized=${health.initialized}, healthy=${health.healthy}, criticalSnapshotsReady=${health.criticalSnapshotsReady}")
            appendLine("- eventQueueCounts(累计写入)=${health.eventQueueCounts}")
            appendLine("- eventQueuePending(真实积压)=${health.pendingEventQueueCounts}")
            appendLine("- leaseCounts=${health.leaseCounts}")
            appendLine("- missingDirectories=${health.missingDirectories.size}, permissionIssues=${health.permissionIssues.size}")
            appendLine()
        }

        val nativeStatus = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (nativeStatus != null) {
            appendNativeHookSummaryZhCn(nativeStatus)
            appendLine()
        }

        val platformCaps = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (platformCaps != null) {
            appendLine("平台能力：")
            appendLine("- sdk=${platformCaps.optInt("sdkVersionInt", 0)}, fuse=${platformCaps.optBoolean("fuseAvailable", false)}, fuseBpf=${platformCaps.optBoolean("isFuseBpfEnabled", false)}")
            appendLine("- mediaProvider=${platformCaps.optString("mediaProviderPackageName", "")}")
            appendLine("- fuseJniLoadMode=${platformCaps.optString("fuseJniLoadMode", "UNKNOWN")}, nativeHookMode=${platformCaps.optString("supportedNativeHookMode", "UNKNOWN")}")
            appendLine()
        }

        appendLine("如果诊断包难以阅读，请先从本文件开始，再打开上方引用的文件。")
    }

    private fun StringBuilder.appendLayerSummary(
        root: JSONObject,
        key: String,
        label: String,
    ) {
        val layer = root.optJSONObject(key)
        if (layer == null) {
            appendLine("- $label: missing")
            return
        }
        val state = layer.optString("state", "UNKNOWN")
        val error = layer.optString("lastError", "")
        val suffix = if (error.isBlank() || error == "null") "" else ", error=$error"
        appendLine("- $label: $state$suffix")
    }

    private fun StringBuilder.appendLayerSummaryZhCn(
        root: JSONObject,
        key: String,
        label: String,
    ) {
        val layer = root.optJSONObject(key)
        if (layer == null) {
            appendLine("- $label：缺失")
            return
        }
        val state = layer.optString("state", "UNKNOWN")
        val error = layer.optString("lastError", "")
        val suffix = if (error.isBlank() || error == "null") "" else "，错误=$error"
        appendLine("- $label：$state$suffix")
    }

    private fun StringBuilder.appendNativeHookSummary(root: JSONObject) {
        val mediaProvider = root.optJSONObject("mediaProvider")
        val inline = root.optJSONObject("inline")
        val native = root.optJSONObject("native")
        val symbols = native?.optJSONObject("symbols")
        val missingSymbols = native?.optJSONArray("missingSymbols")

        appendLine("Native hook:")
        appendLine("- mediaProvider.loaded=${mediaProvider?.optBoolean("loaded", false) ?: false}, package=${mediaProvider?.optString("packageName", "") ?: ""}")
        appendLine("- inline.state=${inline?.optString("state", "UNKNOWN") ?: "UNKNOWN"}, retryCount=${inline?.optInt("retryCount", 0) ?: 0}, disabledByPlatform=${inline?.optBoolean("disabledByPlatform", false) ?: false}")
        appendLine("- fuseLibraryLoaded=${native?.optBoolean("fuseLibraryLoaded", false) ?: false}, hookMode=${native?.optString("hookMode", "UNKNOWN") ?: "UNKNOWN"}, fuseJniLoadMode=${native?.optString("fuseJniLoadMode", "UNKNOWN") ?: "UNKNOWN"}")
        if (symbols != null) {
            appendLine("- symbols containsMount=${symbols.optBoolean("containsMount", false)}, startsWith=${symbols.optBoolean("startsWith", false)}, isFuseBpfEnabled=${symbols.optBoolean("isFuseBpfEnabled", false)}, fuseReqUserdata=${symbols.optBoolean("fuseReqUserdata", false)}, fillEntries=${symbols.optBoolean("fillEntries", false)}, install=${symbols.optBoolean("install", false)}, effective=${symbols.optBoolean("effective", false)}")
        }
        if (missingSymbols != null && missingSymbols.length() > 0) {
            appendLine("- missingSymbols=$missingSymbols")
        }
        val lastError = native?.optString("lastError", "") ?: ""
        if (lastError.isNotBlank()) {
            appendLine("- lastError=$lastError")
        }
    }

    private fun StringBuilder.appendNativeHookSummaryZhCn(root: JSONObject) {
        val mediaProvider = root.optJSONObject("mediaProvider")
        val inline = root.optJSONObject("inline")
        val native = root.optJSONObject("native")
        val symbols = native?.optJSONObject("symbols")
        val missingSymbols = native?.optJSONArray("missingSymbols")

        appendLine("Native Hook：")
        appendLine("- mediaProvider.loaded=${mediaProvider?.optBoolean("loaded", false) ?: false}, package=${mediaProvider?.optString("packageName", "") ?: ""}")
        appendLine("- inline.state=${inline?.optString("state", "UNKNOWN") ?: "UNKNOWN"}, retryCount=${inline?.optInt("retryCount", 0) ?: 0}, disabledByPlatform=${inline?.optBoolean("disabledByPlatform", false) ?: false}")
        appendLine("- fuseLibraryLoaded=${native?.optBoolean("fuseLibraryLoaded", false) ?: false}, hookMode=${native?.optString("hookMode", "UNKNOWN") ?: "UNKNOWN"}, fuseJniLoadMode=${native?.optString("fuseJniLoadMode", "UNKNOWN") ?: "UNKNOWN"}")
        if (symbols != null) {
            appendLine("- symbols containsMount=${symbols.optBoolean("containsMount", false)}, startsWith=${symbols.optBoolean("startsWith", false)}, isFuseBpfEnabled=${symbols.optBoolean("isFuseBpfEnabled", false)}, fuseReqUserdata=${symbols.optBoolean("fuseReqUserdata", false)}, fillEntries=${symbols.optBoolean("fillEntries", false)}, install=${symbols.optBoolean("install", false)}, effective=${symbols.optBoolean("effective", false)}")
        }
        if (missingSymbols != null && missingSymbols.length() > 0) {
            appendLine("- missingSymbols=$missingSymbols")
        }
        val lastError = native?.optString("lastError", "") ?: ""
        if (lastError.isNotBlank()) {
            appendLine("- lastError=$lastError")
        }
    }

    private fun addStatus(zip: ZipOutputStream, server: CleanerServer) {
        runCatching {
            val status = server.layerOrchestrator.collectStatusJson()
            addText(zip, "status/orchestrated_status.json", status)
            DataBus.writeSnapshot(DataBusProtocol.SNAPSHOT_ORCHESTRATED_STATUS, status)
        }.onFailure {
            addText(zip, "status/orchestrated_status_error.txt", it.stackTraceToString())
        }
        runCatching {
            addText(zip, "status/server_exception.txt", server.cleanerService.serverException.toString())
        }
        addSnapshotIfExists(zip, DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS,
            "status/native_hook_status_pretty.json")
        addNativeHookSectionIfExists(zip, "fuseJavaGate",
            "status/fuse_java_gate_status.json")
        addSnapshotIfExists(zip, DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES,
            "status/platform_capabilities.json")
        addSnapshotIfExists(zip, DataBusProtocol.SNAPSHOT_CONFIGURED_MOUNT_POINTS,
            "status/configured_mount_points.json")
    }

    /**
     * 采集「文件系统 / 挂载」事实。
     *
     * 动机见 [FilesystemProbe]：历史归档的挂载采集全部带 grep 过滤
     * （`mount | grep -E '/storage|/mnt/runtime|/Android/data|fuse'`），
     * `/data/local/tmp` 从未出现在任何一条输出里，导致「DataBus 根所在文件系统类型」
     * 只能靠 adb 才能确认。这里由模块自己把结论写进归档，并同步打一条 logcat，
     * 使该事实**离线可自证**。
     *
     * 必须排在 [addLogcat] 之前：这样本条 logcat 会落在**本次**归档的
     * `logs/` 分区内，归档自带证据闭环。
     */
    private fun addFilesystem(zip: ZipOutputStream) {
        val root = FilesystemProbe.DATA_BUS_ROOT
        val paths = listOf(
            "/", "/data", "/data/local", root, DataBus.BUS_ROOT,
            OUTPUT_DIR, AUTO_LOG_DIR, "/storage/emulated/0",
        )
        runCatching {
            addText(
                zip,
                "status/filesystem.txt",
                FilesystemProbe.report(paths, crossCheckPaths = listOf("/data", root)),
            )
        }.onFailure {
            addText(zip, "status/filesystem_error.txt", it.stackTraceToString())
        }
        runCatching {
            FilesystemProbe.logStartupSummary()
        }
    }

    /**
     * 导出结构化错误事件流水（JSON Lines，每行一个 ErrorEvent 的紧凑表示）。
     * 与 orchestrated_status.json 中的 recentErrors 数组同源，
     * 供离线工具按错误码归并统计。
     */
    private fun addErrorJournal(zip: ZipOutputStream) {
        runCatching {
            val events = ServerErrorJournal.snapshot()
            if (events.isEmpty()) {
                addText(zip, "errors/journal.jsonl", "")
                return
            }
            val lines = events.joinToString(separator = "\n") { event ->
                JSONObject().apply {
                    put("code", event.code)
                    put("atElapsed", event.atElapsed)
                    if (event.errno != 0) put("errno", event.errno)
                    event.subject?.let { put("subject", it) }
                    event.pathDigest?.let { put("pathDigest", it) }
                    if (event.generation > 0L) put("generation", event.generation)
                    event.detail?.let { put("detail", it) }
                }.toString()
            }
            addText(zip, "errors/journal.jsonl", lines)
        }.onFailure {
            addText(zip, "errors/journal_error.txt", it.stackTraceToString())
        }
    }

    private fun addDataBus(zip: ZipOutputStream) {
        val initialized = DataBus.ensureInitialized()
        addText(zip, "databus/initialized.txt", initialized.toString())
        runCatching {
            addText(zip, "databus/health.json", healthToJson(DataBus.checkHealth(repair = true)).toString(2))
        }.onFailure {
            addText(zip, "databus/health_error.txt", it.stackTraceToString())
        }

        val busRoot = File(DataBus.BUS_ROOT)
        addDirectoryFiles(zip, File(busRoot, "snapshots"), "databus/snapshots", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "signals"), "databus/signals", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "cursors"), "databus/cursors", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "events/${DataBusProtocol.EVENT_FILESYSTEM}"),
            "databus/events/${DataBusProtocol.EVENT_FILESYSTEM}", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}"),
            "databus/events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "events/consumed"),
            "databus/events/consumed", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "leases/${DataBusProtocol.LEASE_QUERY_SESSIONS}"),
            "databus/leases/${DataBusProtocol.LEASE_QUERY_SESSIONS}", MAX_EVENT_FILES)
    }

    private fun healthToJson(health: DataBusProtocol.HealthReport): JSONObject = JSONObject().apply {
        put("initialized", health.initialized)
        put("healthy", health.healthy)
        put("criticalSnapshotsReady", health.criticalSnapshotsReady)
        put("missingDirectories", JSONArray(health.missingDirectories))
        put("permissionIssues", JSONArray(health.permissionIssues))
        // 两个字段并列：eventQueueCounts 是**累计写入量**（目录文件数，只增不减），
        // eventQueuePendingCounts 才是**真实积压**（文件名 > 游标）。历史上把前者读成
        // 积压导致过假告警，这里显式并列以消除歧义。
        put("eventQueueCounts", JSONObject(health.eventQueueCounts))
        put("eventQueuePendingCounts", JSONObject(health.pendingEventQueueCounts))
        put("leaseCounts", JSONObject(health.leaseCounts))
        put("snapshots", JSONArray().apply {
            for (snapshot in health.snapshots) {
                put(JSONObject().apply {
                    put("name", snapshot.name)
                    put("exists", snapshot.exists)
                    put("validJson", snapshot.validJson)
                    put("error", snapshot.error)
                })
            }
        })
    }

    private fun addLogcat(zip: ZipOutputStream) {
        // ⚠️ `-t N` 限的是**原始行数**，不是过滤后的行数：本机 logd 输出 ~700–1500 行/s
        // （实测 `-t 5000` 只得 6.8 s、`-t 3000` + TAG 过滤只得 2 行），
        // 旧值 5000/3000 等价于"只覆盖最后几秒"，会让读者把"归档里没有"误读成"没发生"。
        // 归档是用户主动触发的一次性动作，放大到 30000 才与"聚焦运行日志"的承诺相称。
        addCommand(
            zip,
            "logs/logcat_threadtime_recent.txt",
            "/system/bin/logcat -d -v threadtime -b main,system,crash -t $LOGCAT_TAIL_LINES",
        )
        addCommand(
            zip,
            "logs/logcat_material_cleaner_filtered.txt",
            "/system/bin/logcat -d -v threadtime -b main,system,crash -t $LOGCAT_TAIL_LINES " +
                    "MC_BOOT:* MC_EVENT:* MC_STATE:* MC_DIAG:* MC_NATIVE:* " +
                    "MC_REDIRECT:* DataBus:* DiagnosticArchive:* CleanerService:* " +
                    "LayerOrchestrator:* SnapshotPublisher:* HookRecoveryCoordinator:* " +
                    "MediaProviderRecoveryStrategy:* NativeHookStatus:* HookPolicyCache:* " +
                    "FuseNativePolicyAdapter:* HookDataBusBridge:* EventConsumerScheduler:* " +
                    "FileSystemEventConsumer:* RedirectNoticeConsumer:* QuerySessionLeaseConsumer:* " +
                    "ActivityManagerLogsObserver:* AMLogs:* MC/Test:* MC/StateMachine:* " +
                    "CleanerTest:* xhook:* starter:* " +
                    "me.gm.cleaner:* *:S",
        )
    }

    private fun addAutoLogs(zip: ZipOutputStream) {
        val dir = File(AUTO_LOG_DIR)
        val files = dir.listFiles()
            ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_AUTO_LOG_FILES)
            ?: emptyList()
        addText(zip, "logs/auto_logging_manifest.txt", buildString {
            appendLine("dir=$AUTO_LOG_DIR")
            appendLine("included=${files.size}")
            for (file in files) {
                appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}")
            }
        })
        for (file in files) {
            addFileTail(zip, file, "logs/auto/${file.name}", MAX_TEXT_FILE_BYTES)
        }
    }

    private fun addCommandOutputs(zip: ZipOutputStream) {
        val rootDir = FilesystemProbe.DATA_BUS_ROOT
        val commands = linkedMapOf(
            "commands/id.txt" to "id; getenforce 2>/dev/null; getprop ro.build.version.sdk; getprop ro.product.model",
            "commands/processes_cleaner.txt" to "ps -A | grep -E 'cleaner|material|gm.cleaner' || true",
            "commands/processes_mediaprovider.txt" to "ps -A | grep -E 'media.provider|providers.media|MediaProvider' || true",
            // ⚠️ 本机 hookMode=EMBEDDED_GOT_PATCH：libinline.so 从**本模块自己的 base.apk** 内加载，
            // libfuse_jni.so 从 **APEX 的 MediaProvider.apk** 内加载。旧模式
            // 'MediaProvider|libfuse_jni|libinline|com.android.mediaprovider' 在这两种情形下
            // **必然 0 命中**（maps 里只有 <apk 路径>+偏移），于是这个证据文件只剩个空壳。
            // 这里补上 me.gm.cleaner（本模块 APK 路径）—— 它才是"我们真的把库注进去了"的直接证据。
            "commands/media_provider_maps.txt" to "for p in ${'$'}(pidof com.android.providers.media.module com.google.android.providers.media.module com.android.providers.media 2>/dev/null); do echo === pid=${'$'}p ===; grep -E 'MediaProvider|libfuse_jni|libinline|com\\.android\\.mediaprovider|me\\.gm\\.cleaner' /proc/${'$'}p/maps 2>&1; done",
            "commands/media_provider_mountinfo.txt" to "for p in ${'$'}(pidof com.android.providers.media.module com.google.android.providers.media.module com.android.providers.media 2>/dev/null); do echo === pid=${'$'}p ===; grep -E '/storage|/mnt/runtime|/Android/data|fuse' /proc/${'$'}p/mountinfo 2>&1 | head -200; done",
            "commands/mount_storage.txt" to "mount | grep -E '/storage|/mnt/runtime|/Android/data|fuse' || true",
            // 以下是**未过滤**的原始挂载证据：上面那条 grep 把 /data/local/tmp 过滤掉了，
            // 导致 DataBus 根所在文件系统类型无法离线判定。这里补齐原始数据。
            "commands/proc_mounts.txt" to "cat /proc/mounts 2>&1",
            "commands/proc_self_mountinfo.txt" to "cat /proc/self/mountinfo 2>&1",
            // ⚠️ 本机 df 的 "Mounted on" 列**不是真实挂载点**，不能当证据用：
            // toybox 的 find_mount_point() 按 major:minor 扫 /proc/mounts(mountinfo)，
            // 且**后者覆盖前者**（last match wins）。本机 254:41(dm-41) 共 13 条，
            // 最后一条是 /data/app/... 上的 bind mount ⇒ 所有 dm-41 路径（含 /data）
            // 都被标成它。故这里保留原始 df 输出（用于看容量），同时追加一行显式说明，
            // 避免读者把该列误读为挂载事实。权威结论在 status/filesystem.txt
            // （/proc/mounts 最长前缀匹配 + statvfs f_fsid 三态比对）。
            "commands/df_databus.txt" to "df -k /data /data/local/tmp $rootDir ${DataBus.BUS_ROOT} 2>&1; " +
                "echo; " +
                "echo 'NOTE: the \"Mounted on\" column of df is NOT a real mount point on this device.'; " +
                "echo 'toybox find_mount_point() scans /proc/mounts matching major:minor with LAST-match-wins,'; " +
                "echo 'and the last 254:41 entry here is a bind mount at /data/app/..., so every dm-41 path'; " +
                "echo '(including /data) is labelled with it. Use this file only for capacity numbers.'; " +
                "echo 'Authoritative per-path mount point/fstype: status/filesystem.txt (longest-prefix match'; " +
                "echo 'on /proc/mounts plus statvfs f_fsid), backed by the stat -f block in stat_f_databus.txt.'",
            "commands/stat_f_databus.txt" to "stat -f /data /data/local/tmp $rootDir 2>&1; echo '--- stat ---'; stat $rootDir ${DataBus.BUS_ROOT} 2>&1; echo '--- ls -d ---'; ls -ld /data /data/local /data/local/tmp $rootDir 2>&1",
            "commands/databus_tree.txt" to "ls -laR ${DataBus.BUS_ROOT} 2>&1 | head -400",
            "commands/auto_logs_tree.txt" to "ls -laR $AUTO_LOG_DIR 2>&1 | head -200",
        )
        for ((entry, command) in commands) {
            addCommand(zip, entry, command)
        }
    }

    private fun addDirectoryFiles(
        zip: ZipOutputStream,
        dir: File,
        entryPrefix: String,
        maxFiles: Int,
    ) {
        val files = dir.listFiles()
            ?.filter { isRegularFileNoFollow(it) }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            ?.take(maxFiles)
            ?: emptyList()
        addText(zip, "$entryPrefix/manifest.txt", buildString {
            appendLine("path=${dir.path}")
            appendLine("exists=${dir.exists()}")
            appendLine("included=${files.size}")
            for (file in files) {
                appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}")
            }
        })
        for (file in files) {
            addFileTail(zip, file, "$entryPrefix/${file.name}", MAX_TEXT_FILE_BYTES)
        }
    }

    private fun addCommand(zip: ZipOutputStream, entryName: String, command: String) {
        val result = runCommand(command)
        addText(zip, entryName, buildString {
            appendLine("$ $command")
            appendLine("exitCode=${result.exitCode}")
            appendLine("timedOut=${result.timedOut}")
            appendLine("truncated=${result.truncated}")
            appendLine()
            append(result.output)
        })
    }

    private fun addSnapshotIfExists(zip: ZipOutputStream, snapshotName: String, entryName: String) {
        val content = DataBus.readSnapshotSafe(snapshotName) ?: return
        val pretty = runCatching {
            JSONObject(content).toString(2)
        }.getOrDefault(content)
        addText(zip, entryName, pretty)
    }

    private fun addNativeHookSectionIfExists(
        zip: ZipOutputStream,
        sectionName: String,
        entryName: String,
    ) {
        val content = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS) ?: return
        val section = runCatching {
            JSONObject(content).optJSONObject(sectionName)
        }.getOrNull() ?: return
        addText(zip, entryName, section.toString(2))
    }

    private fun runCommand(command: String): CommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = ByteArrayOutputStream()
            var truncated = false
            val reader = Thread {
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val input = process.inputStream
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read <= MAX_COMMAND_BYTES) {
                            output.write(buffer, 0, read)
                        } else {
                            val allowed = MAX_COMMAND_BYTES - output.size()
                            if (allowed > 0) output.write(buffer, 0, allowed)
                            truncated = true
                            process.destroy()
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            reader.join(1000)
            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                timedOut = !finished,
                truncated = truncated,
                output = output.toString(StandardCharsets.UTF_8.name()),
            )
        } catch (e: Exception) {
            CommandResult(-1, timedOut = false, truncated = false, output = e.stackTraceToString())
        } finally {
            process?.destroy()
        }
    }

    private fun addFileTail(zip: ZipOutputStream, file: File, entryName: String, maxBytes: Int) {
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

    private fun readFileTail(file: File, maxBytes: Int): String {
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

    private fun addText(zip: ZipOutputStream, entryName: String, content: String) {
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
    private fun alias(token: String): String {
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

    private fun safeEntryName(name: String): String =
        name.replace('\\', '/').trimStart('/').replace("../", "_")

    private fun isRegularFileNoFollow(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun cleanupOldArchives(dir: File) {
        runCatching {
            dir.listFiles()
                ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }
        }.onFailure {
            Log.w(TAG, "cleanupOldArchives failed", it)
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
        val output: String,
    )
}
