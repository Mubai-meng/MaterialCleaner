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

object DiagnosticArchive {
    private const val TAG = "DiagnosticArchive"
    private const val OUTPUT_DIR = "/data/local/tmp/cleaner_diagnostics"
    /** 自动日志目录；[truncationSummary]（DiagnosticSummary.kt）也要读它。 */
    internal const val AUTO_LOG_DIR = "/data/local/tmp/cleaner_logs"
    private const val MAX_AUTO_LOG_FILES = 8
    /** 启动窗口段单独保留的份数（每份取头部，见 [addAutoLogs]）。 */
    private const val MAX_BOOT_LOG_FILES = 2
    private const val MAX_EVENT_FILES = 20

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
            addHookErrorJournal(zip)
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
     *
     * 取持久层全文而非内存快照：持久层跨服务重启保留，
     * 内存环形缓冲只有 50 条且进程重启即清零。挂载失败等根因事实主要产生
     * 在 server 侧，只导出内存快照会让诊断包在每次服务重启后丢失历史。
     * 单行序列化统一由 [me.gm.cleaner.core.common.err.ErrorEvent.toJson] 提供，
     * 与 app 侧 client_error_journal.jsonl 的字段集由代码保证一致。
     */
    private fun addErrorJournal(zip: ZipOutputStream) {
        runCatching {
            addText(zip, "errors/journal.jsonl", ServerErrorJournal.exportJsonL())
        }.onFailure {
            addText(zip, "errors/journal_error.txt", it.stackTraceToString())
        }
    }

    /**
     * 导出 MediaProvider（LSPosed 模块）进程的结构化错误流水。
     *
     * 与 [addErrorJournal] 的区别只是**来源进程**：后者是 server 进程自产的
     * `ServerErrorJournal`，前者是模块进程自产的 `HookErrorJournal`，经
     * `native_hook_status.json` 的 `hookErrors` 段跨进程搬运到此处。
     *
     * 为什么单独成文件而不是并入 `errors/journal.jsonl`：两个进程的错误码域不同，
     * 混在一个文件里会让「这条错误出自哪个进程」需要二次推断。
     *
     * 空流水也写一个 0 字节条目：让「模块从未报错」与「模块根本没在跑」在包结构上
     * 可区分（后者 `native_hook_status.json` 本身就缺失）。
     */
    private fun addHookErrorJournal(zip: ZipOutputStream) {
        runCatching {
            val json = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS)
            if (json == null) {
                addText(zip, "errors/hook_errors.jsonl", "")
                return
            }
            val hookErrors = runCatching { JSONObject(json).optJSONObject("hookErrors") }
                .getOrNull()
            val entries = hookErrors?.optJSONArray("entries")
            if (entries == null || entries.length() == 0) {
                addText(zip, "errors/hook_errors.jsonl", "")
                return
            }
            val lines = (0 until entries.length()).joinToString(separator = "\n") { i ->
                entries.getJSONObject(i).toString()
            }
            addText(zip, "errors/hook_errors.jsonl", lines)
            addText(
                zip,
                "errors/hook_errors_summary.txt",
                buildString {
                    appendLine("source=MediaProvider(LSPosed module) via native_hook_status.json")
                    appendLine("totalRecorded=" + hookErrors.optLong("totalRecorded", 0L))
                    appendLine("slotCount=" + hookErrors.optInt("slotCount", 0))
                    appendLine("exportLimit=" + hookErrors.optInt("exportLimit", 0))
                    appendLine("exported=" + entries.length())
                    appendLine()
                    appendLine("severity: W=warn E=error (as originally recorded)")
                    appendLine("count: occurrences of this exact (code, severity, subject, detail)")
                    appendLine("firstAtElapsed/lastAtElapsed: bounds of the observed span")
                    appendLine("atElapsed: SystemClock.elapsedRealtime of the MediaProvider process")
                },
            )
        }.onFailure {
            addText(zip, "errors/hook_errors_error.txt", it.stackTraceToString())
        }
    }

    private fun addDataBus(zip: ZipOutputStream) {
        val initialized = DataBus.ensureInitialized()
        addText(zip, "databus/initialized.txt", initialized.toString())
        val busRoot = File(DataBus.BUS_ROOT)
        runCatching {
            // P1 诊断质量：归档侧补齐隔离/计数/总账，不改 HealthReport 协议
            val healthJson = healthToJson(DataBus.checkHealth(repair = true))
            val q1 = File(busRoot, "events/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine")
            val q2 = File(busRoot, "events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine")
            val a1 = File(busRoot, "cursors/${DataBusProtocol.EVENT_FILESYSTEM}.attempts")
            val a2 = File(busRoot, "cursors/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.attempts")
            val recovery = File(busRoot, "cursors/media_provider_recovery.json")
            healthJson.put("quarantineCounts", JSONObject().apply {
                put("filesystem", q1.listFiles()?.count { it.isFile() && it.name.endsWith(".json") } ?: 0)
                put("redirectNotice", q2.listFiles()?.count { it.isFile() && it.name.endsWith(".json") } ?: 0)
            })
            healthJson.put("attemptCounts", JSONObject().apply {
                put("filesystem", a1.listFiles()?.count { it.isFile() } ?: 0)
                put("redirectNotice", a2.listFiles()?.count { it.isFile() } ?: 0)
            })
            healthJson.put("recoveryStateExists", recovery.exists())
            addText(zip, "databus/health.json", healthJson.toString(2))
        }.onFailure {
            addText(zip, "databus/health_error.txt", it.stackTraceToString())
        }

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
        // P0-1：毒丸隔离与重试计数目录（Fix 1′/P0-B 产物，world-readable，只读导出）
        addDirectoryFiles(zip,
            File(busRoot, "events/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine"),
            "databus/events/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine", MAX_EVENT_FILES)
        addDirectoryFiles(zip,
            File(busRoot, "events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine"),
            "databus/events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine", MAX_EVENT_FILES)
        addDirectoryFiles(zip,
            File(busRoot, "cursors/${DataBusProtocol.EVENT_FILESYSTEM}.attempts"),
            "databus/cursors/${DataBusProtocol.EVENT_FILESYSTEM}.attempts", MAX_EVENT_FILES)
        addDirectoryFiles(zip,
            File(busRoot, "cursors/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.attempts"),
            "databus/cursors/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.attempts", MAX_EVENT_FILES)
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
        put("pendingEventCounts", JSONObject(health.pendingEventCounts))
        put("quarantineCounts", JSONObject(health.quarantineCounts))
        put("cursorReadStates", JSONObject(
            health.cursorReadStates.mapValues { it.value.name },
        ))
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
            // 过滤串的唯一真相源在 LogTagFilter：持续采集与诊断导出共用同一份清单，
            // 避免两处各持一份而随代码演进漂移（此前清单已遗漏 cleaner_server、
            // me.gm.cleaner.runtime.server、MC/BootState、MC/XposedState 等实际在用的 tag）。
            "/system/bin/logcat -d -v threadtime -b main,system,crash -t $LOGCAT_TAIL_LINES " +
                    LogTagFilter.FILTER_SPEC,
        )
        // 崩溃 buffer 独立取证：crash 的 tag 是崩溃进程名（或 AndroidRuntime），
        // 无法用固定白名单穷举，故此处不加 tag 过滤；crash buffer 仅在崩溃时写入，体积可忽略。
        addCommand(
            zip,
            "logs/logcat_crash_recent.txt",
            "/system/bin/logcat -d -v threadtime -b crash -t 2000",
        )
        // 目标进程（MediaProvider）定向取证：Hook 住在该进程内，其自身 tag
        //（FuseDaemon 等）不在本项目白名单内，只能按 pid 定向抓取。
        addCommand(zip, "logs/logcat_media_provider_pid.txt", mediaProviderPidLogcatCommand())
    }

    /**
     * 纯函数：选出要进包的日志文件。
     *
     * 启动窗口段（`cleaner-boot-*`）**优先**：它们数量少、且承载 server 启动瞬间
     * 的挂载/策略失败证据；主环形按时间取最新的 [maxRing] 个。
     * 这样即使环形已经写了几分钟，启动段也不会被 `take(MAX_AUTO_LOG_FILES)` 挤掉。
     */
    internal fun selectAutoLogFiles(
        all: List<File>,
        maxBoot: Int = MAX_BOOT_LOG_FILES,
        maxRing: Int = MAX_AUTO_LOG_FILES,
    ): List<File> {
        val boot = all.asSequence()
            .filter { AutoLogging.isBootLogFileName(it.name) }
            .sortedByDescending { it.lastModified() }
            .take(maxBoot)
            .toList()
        val ring = all.asSequence()
            .filter { !AutoLogging.isBootLogFileName(it.name) }
            .sortedByDescending { it.lastModified() }
            .take(maxRing)
            .toList()
        return boot + ring
    }

    private fun addAutoLogs(zip: ZipOutputStream) {
        val dir = File(AUTO_LOG_DIR)
        val all = dir.listFiles()
            ?.filter { isRegularFileNoFollow(it) && AutoLogging.isLogFileName(it.name) }
            ?: emptyList()
        val files = selectAutoLogFiles(all)
        addText(zip, "logs/auto_logging_manifest.txt", buildString {
            appendLine("dir=$AUTO_LOG_DIR")
            appendLine("total=${all.size}")
            appendLine("included=${files.size}")
            appendLine("budget=bootTiered(${MAX_BOOT_LOG_BYTES}B)+ringHeadTail(${MAX_TEXT_FILE_BYTES}B)")
            for (file in files) {
                val kind = when {
                    AutoLogging.isBootLogFileName(file.name) -> "boot"
                    AutoLogging.isCrashLogFileName(file.name) -> "crash"
                    else -> "ring"
                }
                appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}\tkind=$kind")
            }
        })
        for (file in files) {
            if (AutoLogging.isBootLogFileName(file.name)) {
                // 启动段是 `-b main,system,crash` **无过滤**的全系统日志，实测 118 KB/s
                //（60 秒 7,249,920 B）。只取头部 512 KiB 仅覆盖 4.3 秒，起机关键窗口
                // 结构性不可见（第 42 批实测单文件 0.97 s）。改走分级裁剪：
                // E/F 全留 + 项目 tag 全留 + 其余按步长均匀采样 ⇒ 完整时间轴 + 证据零丢失。
                DiagnosticBootLogTrimmer.addBootLogTrimmed(
                    zip,
                    file,
                    "logs/auto/${file.name}",
                    MAX_BOOT_LOG_BYTES,
                )
            } else {
                // 主环形与崩溃通道取首尾两段（上游 317e754）：一个文件对应一次采集
                // 周期时，启动失败 / Hook 未加载 / 挂载首轮失败的证据位于**周期开头**，
                // 只取尾部等于把这些证据直接丢弃 —— 与"排查启动或崩溃问题"的用途相悖。
                addFileHeadAndTail(zip, file, "logs/auto/${file.name}", MAX_TEXT_FILE_BYTES)
            }
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
            // 内核侧拒绝证据：SELinux avc denied、FUSE 内核错误、mount/namespace 失败
            // 只在内核日志可见。logcat 的 kernel buffer 在多数真机不可用（实测恒为空），
            // 故必须走 dmesg 通道；tail 限制输出量，保留最近匹配。
            "commands/dmesg_evidence.txt" to
                    "dmesg 2>/dev/null | grep -iE 'avc|fuse|mount|namespace' | tail -2000",
        )
        for ((entry, command) in commands) {
            addCommand(zip, entry, command)
        }
    }

    /**
     * MediaProvider 定向 logcat 取证命令（`internal` 以支持 JVM 回归测试）。
     *
     * 这里的易错点只有一个，但代价很大：`$( )` **只能嵌套一层**。
     * 历史缺陷用的是 `MPID=$($(pidof ...))` —— 外层 `$( )` 会先执行内层，
     * 拿到 `9539` 这种字符串后再把它**当成命令**执行一次，于是：
     *
     * ```
     * /system/bin/sh: 9539: inaccessible or not found
     * media provider process is not running (no pid found)
     * ```
     *
     * MPID 恒空 ⇒ 该文件长期误报「MediaProvider 没在跑」，而同一批的
     * `media_provider_maps.txt` 用正确的 `for p in $(pidof ...)` 就能抓到 pid。
     * 该文件是唯一能定向取到 Hook 宿主日志的通道（MediaProvider 自身 tag
     * 不在 `LogTagFilter.TAGS` 白名单内），失效等于排查时丢失主要证据。
     */
    internal fun mediaProviderPidLogcatCommand(): String =
            "MPID=$(pidof com.android.providers.media.module " +
                    "com.android.providers.media " +
                    "com.google.android.providers.media.module 2>/dev/null | awk '{print $1}'); " +
                    "if [ -z \"${'$'}MPID\" ]; then " +
                    "echo 'media provider process is not running (no pid found)'; " +
                    "else /system/bin/logcat -d -v threadtime --pid=${'$'}MPID -t 2000; fi"

    private fun addCommand(zip: ZipOutputStream, entryName: String, command: String) {
        val result = DiagnosticCommandRunner.runCommand(command)
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

    // 命令执行实现见 DiagnosticCommandRunner（G2 粒度抽离）；
    // 历史 CommandResult 已迁移为 DiagnosticCommandRunner.CommandResult。
}
