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
import me.gm.cleaner.runtime.server.orchestrator.StartupTiming
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensus
import me.gm.cleaner.runtime.server.vfs.VfsReportKeys
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
 * 诊断包摘要与「失败事实」的渲染。
 *
 * 职责边界：只把已存在于快照/状态中的事实渲染成可读文本（英文版与中文版成对），
 * 不做状态裁决（裁决归 Reporter），也不负责取数。
 *
 * 与 [DiagnosticArchive] 分离的动因：摘要随诊断面扩展持续增长，继续留在编排类里
 * 会让「包的整体编排」与「文本渲染」两件事互相拖累（文件粒度门禁 800 行）。
 * 声明为顶层 internal，故既有调用点无需改动。
 */

internal fun privacyNotice(): String = buildString {
    appendLine("This diagnostics package is intended for troubleshooting Material Cleaner.")
    appendLine("It may include device details, process and mount state, logcat output,")
    appendLine("DataBus snapshots, redirect rules, and file paths.")
    appendLine("Basic identifiers such as build fingerprint, APK source paths,")
    appendLine("and long hex-like tokens are redacted before export.")
    appendLine("File paths and package names are preserved because they are required")
    appendLine("to diagnose storage redirection issues.")
}

internal fun buildManifest(server: CleanerServer): String = buildString {
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
 * 动机：主环形/崩溃通道 `logs/auto/<name>.log` 保留**首尾各半**；启动段按
 * **分级裁剪**（E/F 与项目 tag 逐字全留 + 其余均匀采样，预算
 * [MAX_BOOT_LOG_BYTES]）。
 * 实测一次 16,424,932 B 的会话日志进包后只剩 524,338 B（**2.9%**）。此前这一事实
 * 只写在被截断文件自己的第 4 行（`truncated=head omitted, tailBytes=…`），
 * 而 `manifest.txt` / `summary.txt` 里毫无提示 —— 只看入口文件的人会把
 * 「归档里没有」读成「没发生」；再叠加 ColorOS `LOG_FLOWCTRL` 的按进程丢日志
 * （实测单次丢弃 2,470 行），就是**双重不完整**。
 *
 * 在写 manifest **之前**先算一遍（只读 `length()`，不做 IO，不写任何文件）。
 */
internal fun truncationSummary(): String {
    val files = DiagnosticArchive.selectAutoLogFiles(
        File(DiagnosticArchive.AUTO_LOG_DIR).listFiles()
            ?.filter { isRegularFileNoFollow(it) && AutoLogging.isLogFileName(it.name) }
            ?: emptyList(),
    )
    val truncated = files.filter { it.length() > archiveBudgetBytes(it) }
    if (truncated.isEmpty()) {
        return "none?autoLogs=bootTiered(${MAX_BOOT_LOG_BYTES}B)+ringHeadTail(${MAX_TEXT_FILE_BYTES}B)"
    }
    return truncated.joinToString("; ") { file ->
        val budget = archiveBudgetBytes(file)
        // boot 段不是「砍掉尾部」，而是分级裁剪（E/F 与项目 tag 逐字全留 +
        // 其余均匀采样）。因此这里不能用 dropped= 表述，否则会被读成「尾部丢了」。
        val kept = if (AutoLogging.isBootLogFileName(file.name)) "keptTiered" else "keptHeadTail"
        "${file.name} $kept=${budget}B overBudget=${file.length() - budget}B"
    }
}

/**
 * 单个归档条目的字节预算。
 *
 * 启动段**必须**用更大的预算：[MAX_BOOT_LOG_BYTES] 的依据见其 KDoc ——
 * 512 KiB 在 118 KB/s 的洪峰下只覆盖 4.3 秒，连 E/F 行本身都会被采样掉。
 */
internal fun archiveBudgetBytes(file: File): Int =
    if (AutoLogging.isBootLogFileName(file.name)) MAX_BOOT_LOG_BYTES else MAX_TEXT_FILE_BYTES

internal fun buildSummary(server: CleanerServer): String = buildString {
    appendLine("Material Cleaner diagnostics summary")
    appendLine("Generated at: ${System.currentTimeMillis()}")
    appendLine()
    appendLine("Read this first:")
    appendLine("- status/orchestrated_status.json has the full five-layer state.")
    appendLine("- status/native_hook_status_pretty.json has MediaProvider/FUSE hook details.")
    appendLine("- databus/health.json has queue, snapshot, and permission health.")
    appendLine("- errors/hook_errors.jsonl has MediaProvider (LSPosed module) runtime errors:")
    appendLine("  structured, so unlike the logcat captures it is NOT subject to LOG_FLOWCTRL drops.")
    appendLine("- logs/logcat_material_cleaner_filtered.txt has focused runtime logs.")
    appendLine("  ⚠️ Both logs/logcat_* captures are capped by '-t' counted in RAW lines (~700–1500/s),")
    appendLine("  so they cover roughly the last 20–40 s only even with LOGCAT_TAIL_LINES=$LOGCAT_TAIL_LINES.")
    appendLine("- commands/media_provider_maps.txt and commands/media_provider_mountinfo.txt show MediaProvider process state.")
    appendLine("- commands/dmesg_evidence.txt has kernel-side denials (SELinux avc, FUSE, mount, namespace).")
    appendLine("- logs/logcat_crash_recent.txt and logs/logcat_media_provider_pid.txt cover crash and hook-host evidence.")
    appendLine("- ⚠️ This archive is NOT the full log: ring/crash logs keep the HEAD and the TAIL")
    appendLine("  (half of the budget each, middle omitted); boot logs")
    appendLine("  (cleaner-boot-*, first 60 s after server start) are TIER-TRIMMED, not head-cut:")
    appendLine("  every E/F line and every project-tag line is kept VERBATIM, and the remaining")
    appendLine("  noise is uniformly sampled, so the WHOLE time axis stays visible. Read the")
    appendLine("  'retention=' and 'step=' lines at the top of each boot log to see what was dropped.")
    appendLine("  See the 'truncation=' line in manifest.txt, and note logcat itself drops lines under")
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
        appendLine("- eventQueueCounts=${health.eventQueueCounts} (physical, includes consumed-but-retained)")
        appendLine("- pendingEventCounts=${health.pendingEventCounts} (drives backlog alerts)")
        appendLine("- quarantineCounts=${health.quarantineCounts}")
        appendLine("- cursorReadStates=${health.cursorReadStates} (UNREADABLE queues excluded from backlog)")
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

    if (status != null) {
        appendLine("Key signals:")
        appendKeySignals(status)
        appendLine()
    }

    appendLine("If the package is hard to read, start from this file, then open the referenced files above.")
}

/**
 * 启动编排分段耗时（内存事实，源自 [StartupTiming]）。
 *
 * 为什么必须放进摘要：`initialize done in …` 日志行在 ColorOS `LOG_FLOWCTRL` 下
 * 会被**整行**丢弃（第41、42 批全包检索命中 0 次），而"启动花了多久、卡在哪一步"
 * 直接决定兼容层多久从 RECOVERING 转 HEALTHY。此前排障只能靠快照体积变化反推时点。
 *
 * 缺该段一律按"本次进程尚未完成 initialize"陈述，不臆断耗时。
 */
internal fun StringBuilder.appendStartupTiming(root: JSONObject, zh: Boolean) {
    val timing = root.optJSONObject("startupTiming") ?: return
    val elapsed = timing.optLong("elapsedMs", -1L)
    if (elapsed < 0L) return
    val segments = timing.optJSONObject("segmentsMs")
    val detail = segments?.let { obj ->
        StartupTiming.SEGMENT_ORDER.mapNotNull { name ->
            val value = obj.optLong(name, -1L)
            if (value < 0L) null else "$name=$value"
        }.joinToString(", ")
    } ?: ""
    val attempt = timing.optInt("attempt", 0)
    if (zh) {
        appendLine(
            "- 启动编排：总耗时=${elapsed}ms" +
                (if (detail.isEmpty()) "" else "（$detail）") +
                (if (attempt > 1) "，第 $attempt 次" else ""),
        )
    } else {
        appendLine(
            "- Startup: elapsed=${elapsed}ms" +
                (if (detail.isEmpty()) "" else " ($detail)") +
                (if (attempt > 1) ", attempt #$attempt" else ""),
        )
    }
}

/**
 * P1 诊断质量：陈列 status JSON 中的关键信号。
 *
 * 职责边界：本函数只**陈列事实**，不做状态裁决——裁决归 Reporter
 * （[NativeHookLayerReporter] / [MediaProviderHookLayerReporter]），
 * 此处直接展示其结论与证据字段。自造判定词会与运行状态分叉，
 * 正是历史上"摘要显示 synced 而实际 STALE"的成因。
 */
internal fun StringBuilder.appendKeySignals(status: JSONObject) {
    // 启动耗时不依赖任何一层是否可用，必须放在下方「三层缺任一层就 return」之前。
    appendStartupTiming(status, zh = false)
    val vfs = status.optJSONObject("vfs")
    val mp = status.optJSONObject("mediaProviderJavaHook")
    val fuse = status.optJSONObject("fuseNativeHook")
    if (vfs == null || mp == null || fuse == null) return
    // FUSE：展示 Reporter 的权威裁决 + 支撑证据，不再自行推导 synced/pending
    val appliedEpoch = fuse.optString(NativeHookLayerReporter.KEY_APPLIED_EPOCH, "")
    val snapshotEpoch = fuse.optString(NativeHookLayerReporter.KEY_SNAPSHOT_EPOCH, "")
    val policySynced = fuse.optBoolean(NativeHookLayerReporter.KEY_POLICY_SYNCED, false)
    val verdict = fuse.optString(NativeHookLayerReporter.KEY_SYNC_VERDICT, "UNKNOWN")
    val layerState = fuse.optString("state", "UNKNOWN")
    if (appliedEpoch.isNotBlank() && snapshotEpoch.isNotBlank()) {
        val epoch = if (appliedEpoch == snapshotEpoch) "consistent" else "MISMATCH"
        appendLine("- FUSE: state=$layerState, sync=$verdict, " +
                "epoch=$epoch, policySynced=$policySynced")
    }
    // 恢复熔断：展示 Reporter 结论与计数（attempts 语义）
    val wakeOnly = mp.optBoolean(MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE, false)
    val rounds = mp.optInt(MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS, 0)
    if (wakeOnly || rounds > 0) {
        appendLine("- MediaProvider recovery: state=${mp.optString("state", "UNKNOWN")}, " +
                "wakeOnly=$wakeOnly, destructiveAttempts=$rounds/3")
    }
    // VFS 分母。必须并列「有几个目标包真的有进程在跑」：
    // managed=0 的两种成因（目标应用没启动 / 判据失灵）单看计数无法区分，
    // 第 39/40 批都因此把「没样本」误读成「接管失效」。
    val unmanaged = vfs.optInt(VfsProcessCensus.KEY_UNMANAGED, -1)
    val managed = vfs.optInt(VfsProcessCensus.KEY_MANAGED, -1)
    if (unmanaged >= 0 && managed >= 0) {
        val withProcs = vfs.optInt(VfsProcessCensus.KEY_PACKAGES_WITH_PROCESSES, -1)
        val configured = vfs.optInt(VfsReportKeys.CONFIGURED_PACKAGES, -1)
        val ctx = if (withProcs >= 0 && configured >= 0) {
            ", targetsRunning=$withProcs/$configured"
        } else if (withProcs >= 0) {
            ", targetsRunning=$withProcs"
        } else {
            ""
        }
        appendLine("- VFS pids: managed=$managed, unmanaged=$unmanaged" +
                ctx + if (managed == 0 && withProcs == 0) {
            " (no target process running — managed=0 is expected, NOT a verdict failure)"
        } else {
            ""
        })
    }
    // srStatus 截断
    val truncated = vfs.optBoolean(VfsProcessCensus.KEY_SR_TRUNCATED, false)
    val total = vfs.optInt(VfsProcessCensus.KEY_SR_TOTAL, -1)
    if (total >= 0) {
        appendLine("- srStatus: total=$total, truncated=$truncated")
    }
    appendVfsSamplingSkew(vfs, zh = false)
}

/** 中文概览：同样只陈列 Reporter 结论，不自行裁决。 */
internal fun StringBuilder.appendKeySignalsZhCn(status: JSONObject) {
    // 同英文版：启动耗时不依赖任何一层是否可用，必须先于层 guard 输出。
    appendStartupTiming(status, zh = true)
    val vfs = status.optJSONObject("vfs")
    val mp = status.optJSONObject("mediaProviderJavaHook")
    val fuse = status.optJSONObject("fuseNativeHook")
    if (vfs == null || mp == null || fuse == null) return
    val appliedEpoch = fuse.optString(NativeHookLayerReporter.KEY_APPLIED_EPOCH, "")
    val snapshotEpoch = fuse.optString(NativeHookLayerReporter.KEY_SNAPSHOT_EPOCH, "")
    val policySynced = fuse.optBoolean(NativeHookLayerReporter.KEY_POLICY_SYNCED, false)
    val verdict = fuse.optString(NativeHookLayerReporter.KEY_SYNC_VERDICT, "UNKNOWN")
    val layerState = fuse.optString("state", "UNKNOWN")
    if (appliedEpoch.isNotBlank() && snapshotEpoch.isNotBlank()) {
        val epoch = if (appliedEpoch == snapshotEpoch) "一致" else "不一致"
        appendLine("- FUSE：状态=$layerState，同步=$verdict，代次=$epoch，策略同步=$policySynced")
    }
    // 恢复熔断（attempts 语义）
    val wakeOnly = mp.optBoolean(MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE, false)
    val rounds = mp.optInt(MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS, 0)
    if (wakeOnly || rounds > 0) {
        appendLine("- MediaProvider 恢复：状态=${mp.optString("state", "UNKNOWN")}，" +
                "仅唤醒=$wakeOnly，破坏性尝试=$rounds/3")
    }
    // VFS 分母（同英文版：必须并列目标包中"有进程在跑"的个数）
    val unmanaged = vfs.optInt(VfsProcessCensus.KEY_UNMANAGED, -1)
    val managed = vfs.optInt(VfsProcessCensus.KEY_MANAGED, -1)
    if (unmanaged >= 0 && managed >= 0) {
        val withProcs = vfs.optInt(VfsProcessCensus.KEY_PACKAGES_WITH_PROCESSES, -1)
        val configured = vfs.optInt(VfsReportKeys.CONFIGURED_PACKAGES, -1)
        val ctx = if (withProcs >= 0 && configured >= 0) {
            "，有进程在跑=$withProcs/$configured"
        } else if (withProcs >= 0) {
            "，有进程在跑=$withProcs"
        } else {
            ""
        }
        appendLine("- VFS 进程：已管理=$managed，未接管=$unmanaged" + ctx +
                if (managed == 0 && withProcs == 0) {
                    "（目标应用均未运行，无样本 —— 不等于判据失灵）"
                } else {
                    ""
                })
    }
    // srStatus 截断
    val truncated = vfs.optBoolean(VfsProcessCensus.KEY_SR_TRUNCATED, false)
    val total = vfs.optInt(VfsProcessCensus.KEY_SR_TOTAL, -1)
    if (total >= 0) {
        appendLine("- srStatus：总数=$total，已截断=$truncated")
    }
    appendVfsSamplingSkew(vfs, zh = true)
}

/**
 * 实时值 vs 普查缓存值的**采样时点**对照。
 *
 * 报告里并存两类指标：`recordedPids`（`collectReport` 当场读 Mounter）与
 * `vfsMountedPids`（[CENSUS_THROTTLE_MS] 内复用的上一轮普查）。第 42 批与第 43 批
 * **连续两次**出现 `recordedPids=0` 配 `vfsMountedPids=4`，两次都被读成
 * 「模块自相矛盾」；实际两次都是普查采样之后用户从最近任务清理了目标应用。
 *
 * 时点并列后，读日志的人不必再靠推理 —— 相差多少毫秒直接写在脸上。
 */
internal fun StringBuilder.appendVfsSamplingSkew(vfs: JSONObject, zh: Boolean) {
    val recorded = vfs.optInt(VfsReportKeys.RECORDED_PIDS, -1)
    val mounted = vfs.optInt(VfsProcessCensus.KEY_MOUNTED, -1)
    if (recorded < 0 || mounted < 0) return
    val realtimeAt = vfs.optLong(VfsReportKeys.RECORDED_PIDS_AT, 0L)
    val censusAt = vfs.optLong(VfsProcessCensus.KEY_CENSUS_AT, 0L)
    // 缺任一时点就不猜：宁可不打印，也不给出可能错的差值。
    if (realtimeAt <= 0L || censusAt <= 0L) return
    val skew = realtimeAt - censusAt
    val verdict = when {
        recorded == mounted -> ""
        skew >= 0 -> if (zh) {
            "（两组数字来自不同时点，不是矛盾）"
        } else {
            " (different instants — NOT a contradiction)"
        }
        else -> if (zh) {
            "（实时值早于普查值：以 vfsCensusAt 为准）"
        } else {
            " (realtime sample precedes census sample — trust vfsCensusAt)"
        }
    }
    if (zh) {
        appendLine(
            "- VFS 采样：实时 recordedPids=$recorded @$realtimeAt，" +
                    "普查 vfsMountedPids=$mounted @$censusAt，相差=${skew}ms" + verdict,
        )
    } else {
        appendLine(
            "- VFS sampling: realtime recordedPids=$recorded @$realtimeAt vs " +
                    "census vfsMountedPids=$mounted @$censusAt, skew=${skew}ms" + verdict,
        )
    }
}

internal fun buildSummaryZhCn(server: CleanerServer): String = buildString {
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
    appendLine("- commands/dmesg_evidence.txt：内核侧拒绝证据（SELinux avc、FUSE、mount、namespace）。")
    appendLine("- logs/logcat_crash_recent.txt 与 logs/logcat_media_provider_pid.txt：崩溃与 Hook 宿主进程证据。")
    appendLine("- ⚠️ 本归档**不是**完整日志：主环形每份保留首尾两段（各占一半预算，中段省略）；")
    appendLine("  启动段 cleaner-boot-*（server 启动后 60 秒）是**分级裁剪**而非砍头：")
    appendLine("  E/F 行与项目 tag 行**逐字全留**，其余按固定步长均匀采样，时间轴完整可见；")
    appendLine("  实际丢了什么，看每份文件顶部的 'retention=' 与 'step=' 行。见 manifest.txt 的")
    appendLine("  'truncation=' 行；")
    appendLine("  且 logcat 本身会被 ColorOS LOG_FLOWCTRL 按进程限额丢弃。")
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
        appendLine("- eventQueueCounts=${health.eventQueueCounts}（物理量，含已消费未清理）")
        appendLine("- pendingEventCounts=${health.pendingEventCounts}（待处理，唯一告警口径）")
        appendLine("- quarantineCounts=${health.quarantineCounts}")
        appendLine("- cursorReadStates=${health.cursorReadStates}（UNREADABLE 队列不计入告警）")
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

    if (status != null) {
        appendLine("关键信号：")
        appendKeySignalsZhCn(status)
        appendLine()
    }

    appendLine("如果诊断包难以阅读，请先从本文件开始，再打开上方引用的文件。")
}

internal fun StringBuilder.appendLayerSummary(
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

internal fun StringBuilder.appendLayerSummaryZhCn(
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

/**
 * 在主摘要里直接列出 Hook 进程本次会话记录的失败事实。
 *
 * 条目本身已全量导出到 `errors/hook_errors.jsonl`；此处只做"一眼可见"，
 * 使排障者不必先翻原始快照就能读到失败码与原因。缺该段说明快照生成于
 * 字段引入之前、或 Hook 进程从未记录过失败——两种情况都按"无记录"陈述，
 * 不臆断原因。
 */
internal fun StringBuilder.appendHookErrorsLines(root: JSONObject, zh: Boolean) {
    val hookErrors = root.optJSONObject("hookErrors") ?: return
    val total = hookErrors.optLong("totalRecorded", 0L)
    val slots = hookErrors.optInt("slotCount", 0)
    val entries = hookErrors.optJSONArray("entries") ?: return
    if (entries.length() == 0) {
        appendLine(if (zh) "- hookErrors：无记录" else "- hookErrors: none recorded")
        return
    }
    appendLine(
        if (zh) {
            "- hookErrors：累计=$total，槽位=$slots，本次导出=${entries.length()}"
        } else {
            "- hookErrors: total=$total, slots=$slots, exported=${entries.length()}"
        }
    )
    for (i in 0 until entries.length()) {
        val item = entries.optJSONObject(i) ?: continue
        val code = item.optString("code", "")
        val count = item.optLong("count", 1L)
        val subject = item.optString("subject", "")
        val detail = item.optString("detail", "")
        append("- [").append(item.optString("severity", "W")).append("] ").append(code)
        if (count > 1L) append(" x").append(count)
        if (subject.isNotBlank()) append(" subject=").append(subject)
        if (detail.isNotBlank()) append(" ").append(detail)
        appendLine()
    }
}

internal fun StringBuilder.appendNativeHookSummary(root: JSONObject) {
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
    appendHookErrorsLines(root, zh = false)
    val lastError = native?.optString("lastError", "") ?: ""
    if (lastError.isNotBlank()) {
        appendLine("- lastError=$lastError")
    }
}

internal fun StringBuilder.appendNativeHookSummaryZhCn(root: JSONObject) {
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
    appendHookErrorsLines(root, zh = true)
    val lastError = native?.optString("lastError", "") ?: ""
    if (lastError.isNotBlank()) {
        appendLine("- lastError=$lastError")
    }
}
