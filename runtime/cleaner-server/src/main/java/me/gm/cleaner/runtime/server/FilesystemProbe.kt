package me.gm.cleaner.runtime.server

import android.os.StatFs
import android.system.Os
import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import java.io.File
import java.nio.file.Files

/**
 * 只读文件系统探针。
 *
 * 动机：诊断归档此前的挂载类采集**全部带 grep 过滤**
 * （`mount | grep -E '/storage|/mnt/runtime|/Android/data|fuse'`），
 * 因此 `/data/local/tmp`（DataBus 根目录）从未出现在任何一条挂载输出里，
 * 「DataBus 根所在文件系统类型」这一前提既无法从既有归档证实、也无法证伪，
 * 而设备侧又不方便执行 adb。本探针把该事实**由模块自己**写进诊断包与 logcat。
 *
 * 采集发生在 **server 进程自己的挂载命名空间**内（即 DataBus 读写真正发生的命名空间），
 * 用四条互相独立的路径取证，任一成立即可定论：
 *
 * 1. 自解析 `/proc/mounts` 做**最长前缀匹配**（不依赖 libcore 的实现细节）；
 * 2. `java.nio.file.FileStore.name()` / `.type()`（libcore 走 /proc/mounts）；
 * 3. `Os.statvfs()` 的 `f_fsid` 跨路径比对 —— fsid 相同即同一文件系统实例；
 * 4. `StatFs` 容量比对 —— 与 `/data` 完全一致即说明未独立挂载。
 *
 * 决定性判据：若对 `/data/local/tmp` 的最长前缀匹配落在 `/data`，且
 * `f_fsid(/data/local/tmp) == f_fsid(/data)`，则该路径只是 f2fs `/data` 上的普通目录，
 * **不是**独立 tmpfs 挂载；反之若匹配落在 `/data/local/tmp` 自身，则它是独立挂载。
 *
 * 全部操作**只读**：不创建、不删除、不挂载、不改权限、不触碰 `persist.*`。
 */
object FilesystemProbe {

    private const val TAG = "MC_FsProbe"
    private const val PROC_MOUNTS = "/proc/mounts"
    private const val PROC_SELF_MOUNTS = "/proc/self/mounts"
    private const val PROC_SELF_MOUNTINFO = "/proc/self/mountinfo"

    /**
     * `FileStore` 在本进程内不可用的原因（一旦确定就短路后续探测）。
     *
     * 见 [appendFileStore]：`SecurityException` 是**进程级**能力问题，
     * 不是某条路径的问题，所以缓存它避免 8 条路径重复写 16 行同样的错误。
     */
    @Volatile
    private var fileStoreBlockedReason: String? = null

    /**
     * DataBus 共享根（`/data/local/tmp/cleaner`）。
     *
     * `DataBus.CLEANER_ROOT` 是 private，这里由公开的 [DataBus.BUS_ROOT] 反推，
     * 避免两处各写一份字面量造成漂移。
     */
    val DATA_BUS_ROOT: String = File(DataBus.BUS_ROOT).parent ?: "/data/local/tmp/cleaner"

    /**
     * 启动时打一条单行结论。
     *
     * 目的：让「DataBus 根所在文件系统类型」**不需要 adb、也不需要生成诊断包**，
     * 从任意一次 logcat 抓取里都能直接读到（tag=[TAG]）。
     */
    fun logStartupSummary() {
        runCatching {
            Log.i(TAG, "filesystem_probe busRoot=$DATA_BUS_ROOT ${verdict(DATA_BUS_ROOT, "/data")}")
        }.onFailure {
            Log.w(TAG, "filesystem_probe failed", it)
        }
    }

    /** 一条挂载记录。 */
    data class MountEntry(
        val source: String,
        val mountPoint: String,
        val fsType: String,
        val options: String,
        val origin: String,
    )

    /**
     * 生成多路径人类可读报告。
     *
     * @param paths 需要逐条解剖的路径
     * @param crossCheckPaths 额外做 `f_fsid` 交叉比对的路径（通常为 `/data`）
     */
    fun report(paths: List<String>, crossCheckPaths: List<String> = emptyList()): String {
        val mounts = runCatching { readMounts() }.getOrDefault(emptyList())
        val sb = StringBuilder()
        sb.appendLine("probe=FilesystemProbe")
        sb.appendLine("pid=${Os.getpid()} uid=${Os.getuid()}")
        sb.appendLine("mountTableOrigin=${mounts.map { it.origin }.distinct().joinToString(",")}")
        sb.appendLine("mountEntryCount=${mounts.size}")
        sb.appendLine()
        for (path in paths) {
            appendPath(sb, path, mounts)
        }
        if (crossCheckPaths.isNotEmpty()) {
            sb.appendLine("=== fsid 交叉比对（相同 fsid 即同一文件系统实例）===")
            val baseline = crossCheckPaths.firstOrNull()
            for (path in crossCheckPaths) {
                sb.appendLine("$path fsid=${fsidHex(path)}")
            }
            if (baseline != null) {
                for (path in paths) {
                    sb.appendLine("$path sameFsidAs($baseline)=${fsidComparison(path, baseline)}")
                }
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 单行结论。用于 logcat 直读，保证「日志里能直接看见」。
     *
     * @param target 待判定路径（DataBus 共享根）
     * @param baseline 对照路径（通常 `/data`）
     */
    fun verdict(target: String, baseline: String): String {
        val mounts = runCatching { readMounts() }.getOrDefault(emptyList())
        return "$target ${relationOf(target, mounts)} sameFsidAs($baseline)=" +
            fsidComparison(target, baseline)
    }

    /**
     * 结论措辞（纯函数）。
     *
     * 由单测钉住：措辞与 `ownMount` 判据必须一致，否则日志会给出**与数据相反**的
     * 可读结论 —— 那比没有结论更糟。
     */
    internal fun relationOf(target: String, mounts: List<MountEntry>): String {
        val entry = resolveMount(target, mounts) ?: return "mount=<unresolved>"
        val ownMount = normalizePath(entry.mountPoint) == normalizePath(target)
        return if (ownMount) {
            "IS a separate mount fstype=${entry.fsType} source=${entry.source}"
        } else {
            "is INSIDE mount ${entry.mountPoint} fstype=${entry.fsType} source=${entry.source}"
        }
    }

    // ── 内部实现 ──

    private fun appendPath(sb: StringBuilder, path: String, mounts: List<MountEntry>) {
        sb.appendLine("[path] $path")
        val file = File(path)
        sb.appendLine("  exists=${runCatching { file.exists() }.getOrDefault(false)}" +
            " isDirectory=${runCatching { file.isDirectory }.getOrDefault(false)}")
        val entry = resolveMount(path, mounts)
        if (entry == null) {
            sb.appendLine("  mount=<unresolved>")
        } else {
            sb.appendLine("  mountPoint=${entry.mountPoint} fstype=${entry.fsType}" +
                " source=${entry.source} origin=${entry.origin}")
            sb.appendLine("  ownMountPoint=${normalizePath(entry.mountPoint) == normalizePath(path)}")
            sb.appendLine("  mountOptions=${entry.options}")
        }
        appendFileStore(sb, path)
        sb.appendLine("  statvfs=${statvfsFields(path)}")
        sb.appendLine("  statFs=${statFsFields(path)}")
        sb.appendLine()
    }

    /**
     * `Files.getFileStore()` 探测，**进程内短路**。
     *
     * `FileStore` 只对持有存储权限的调用方开放，实测 ColorOS17 上对
     * `/`、`/data`、`/data/local/tmp/cleaner` 等**全部 8 条路径**都抛
     * `SecurityException: getFileStore` ⇒ 旧实现每路径写两行、共 16 行纯噪声，
     * 而结论早已由 `statvfs` 的 `f_fsid` 与 `StatFs` 给出。
     *
     * 所以：一旦因 `SecurityException` 失败，就认为**本进程内该 API 整体不可用**，
     * 后续路径只写一行 `fileStore=<skipped: …>`。其他异常（如路径不存在）不短路 ——
     * 那可能只是该路径的问题，不该把别的路径也一起跳过。
     */
    private fun appendFileStore(sb: StringBuilder, path: String) {
        val blockedReason = fileStoreBlockedReason
        if (blockedReason != null) {
            sb.appendLine("  fileStore=<skipped: $blockedReason>")
            return
        }
        try {
            val store = Files.getFileStore(File(path).toPath())
            sb.appendLine("  fileStore.name=${store.name()}")
            sb.appendLine("  fileStore.type=${store.type()}")
        } catch (e: Exception) {
            val message = "<error ${e.javaClass.simpleName}: ${e.message}>"
            sb.appendLine("  fileStore.name=$message")
            sb.appendLine("  fileStore.type=$message")
            if (e is SecurityException) {
                fileStoreBlockedReason = message
                sb.appendLine("  fileStoreNote=subsequent paths print one skipped line; see FilesystemProbe KDoc")
            }
        }
    }

    private fun statvfsFields(path: String): String = runCatching {
        val v = Os.statvfs(path)
        "bsize=${v.f_bsize} frsize=${v.f_frsize} blocks=${v.f_blocks} bfree=${v.f_bfree}" +
            " bavail=${v.f_bavail} files=${v.f_files} ffree=${v.f_ffree}" +
            " namemax=${v.f_namemax} flag=0x${java.lang.Long.toHexString(v.f_flag)}" +
            " fsid=${java.lang.Long.toHexString(v.f_fsid)}"
    }.getOrElse { "<error ${it.javaClass.simpleName}: ${it.message}>" }

    private fun statFsFields(path: String): String = runCatching {
        val s = StatFs(path)
        "totalBytes=${s.totalBytes} availableBytes=${s.availableBytes}" +
            " blockCount=${s.blockCountLong} blockSize=${s.blockSizeLong}"
    }.getOrElse { "<error ${it.javaClass.simpleName}: ${it.message}>" }

    private fun fsidHex(path: String): String = runCatching {
        java.lang.Long.toHexString(Os.statvfs(path).f_fsid)
    }.getOrElse { "<error ${it.javaClass.simpleName}>" }

    /**
     * 三态比对。
     *
     * 取不到 fsid 时必须回 `unknown`，**不能**回 `false`：`false` 会被读成
     * 「两者不是同一文件系统」，正好把结论推向 tmpfs 假说，属于静默的错误答案。
     */
    internal fun fsidComparison(a: String, b: String): String {
        val fa = fsidHex(a)
        val fb = fsidHex(b)
        if (fa.startsWith("<") || fb.startsWith("<")) return "unknown"
        return (fa == fb).toString()
    }

    /**
     * 最长前缀匹配。`/` 作为兜底候选，只在没有更长前缀时生效。
     *
     * 边界必须按「路径分量」判断（`target.startsWith("$mp/")`），
     * 否则 `/database` 会被误判为落在 `/data` 之内 —— 这正是本函数的决定性语义，
     * 因此抽成 `internal` 纯函数由单测钉住。
     */
    internal fun resolveMount(path: String, mounts: List<MountEntry>): MountEntry? {
        val target = normalizePath(path)
        var best: MountEntry? = null
        for (entry in mounts) {
            val mp = normalizePath(entry.mountPoint)
            val matches = target == mp || mp == "/" || target.startsWith("$mp/")
            if (!matches) continue
            if (best == null || normalizePath(best.mountPoint).length < mp.length) {
                best = entry
            }
        }
        return best
    }

    internal fun normalizePath(path: String): String {
        if (path.isEmpty()) return "/"
        val trimmed = if (path.length > 1 && path.endsWith("/")) path.trimEnd('/') else path
        return if (trimmed.isEmpty()) "/" else trimmed
    }

    /** 解析 `/proc/mounts` 格式：`source mountpoint fstype options dump pass`。 */
    internal fun parseMountTable(lines: List<String>, origin: String): List<MountEntry> {
        val entries = ArrayList<MountEntry>()
        for (line in lines) {
            val f = line.split(' ').filter { it.isNotEmpty() }
            if (f.size < 4) continue
            entries += MountEntry(
                source = unescapeMountField(f[0]),
                mountPoint = unescapeMountField(f[1]),
                fsType = f[2],
                options = f[3],
                origin = origin,
            )
        }
        return entries
    }

    /**
     * 解析 `/proc/self/mountinfo` 格式：
     * `id parent maj:min root mountpoint options 可选字段… - fstype source superopts`。
     *
     * 注意：Kotlin 的 KDoc 把半角方括号当链接语法，注释里不能出现未配对的左方括号。
     */
    internal fun parseMountInfo(lines: List<String>): List<MountEntry> {
        val entries = ArrayList<MountEntry>()
        for (line in lines) {
            val f = line.split(' ').filter { it.isNotEmpty() }
            val sep = f.indexOf("-")
            if (sep < 0 || f.size < 6 || f.size < sep + 3) continue
            entries += MountEntry(
                source = unescapeMountField(f[sep + 2]),
                mountPoint = unescapeMountField(f[4]),
                fsType = f[sep + 1],
                options = f[5],
                origin = "mountinfo",
            )
        }
        return entries
    }

    /** 优先 `/proc/mounts`；不可读/为空时回退解析 `/proc/self/mountinfo`。 */
    private fun readMounts(): List<MountEntry> {
        val mountsFile = File(PROC_MOUNTS).takeIf { it.exists() } ?: File(PROC_SELF_MOUNTS)
        val fromMounts = runCatching {
            parseMountTable(mountsFile.readLines(), mountsFile.name)
        }.getOrDefault(emptyList())
        if (fromMounts.isNotEmpty()) return fromMounts
        return runCatching {
            parseMountInfo(File(PROC_SELF_MOUNTINFO).readLines())
        }.getOrDefault(emptyList())
    }

    /** `/proc/mounts` 与 `mountinfo` 都按 `\NNN` 八进制转义空格、制表符等。 */
    private fun unescapeMountField(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 4 <= value.length) {
                val code = value.substring(i + 1, i + 4).toIntOrNull(8)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 4
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
