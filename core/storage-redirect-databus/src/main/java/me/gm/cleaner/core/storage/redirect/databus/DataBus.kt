package me.gm.cleaner.core.storage.redirect.databus

import android.os.Process
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件系统数据总线。
 *
 * 承载跨进程、跨层共享的持久化事实。
 *
 * ## 目录结构
 * ```
 * /data/local/tmp/cleaner/bus/
 *   snapshots/
 *     redirect_policy.json
 *     read_only.json
 *   signals/
 *     redirect_policy_changed
 *     read_only_changed
 *     platform_capabilities_changed
 *     filesystem_events_changed
 *   events/
 *     filesystem/     ← MediaProvider 进程写入，server 进程读取
 *     redirect_notice/ ← MediaProvider 进程写入，server 进程读取
 *     filesystem.quarantine/ ← 毒丸隔离（server 写，原文保留、游标推进）
 *     redirect_notice.quarantine/ ← 毒丸隔离（server 写，原文保留、游标推进）
 *     consumed/       ← 已消费事件归档
 *   leases/
 *     query_sessions/ ← MediaProvider 写入，server 按 TTL 维护临时目录
 *   cursors/          ← 消费者游标持久化
 *     filesystem.attempts/ ← 按事件名的处理重试计数（server 写）
 *     redirect_notice.attempts/ ← 按事件名的处理重试计数（server 写）
 *   counters/         ← 事件队列持久化序号
 *   tmp/
 * ```
 *
 * ## 原子写协议（快照 & 事件共享）
 * 1. 写入 tmp/ 下的临时文件
 * 2. flush + fsync 确保落盘
 * 3. rename 到目标文件（POSIX 原子性）
 * 4. touch signal 通知消费者
 *
 * ## 事件格式
 * 每个事件为一个独立 JSON 文件，命名规则：
 *   `<seq-20位>-<timestamp>-<pid>-<rand-hex>.json`
 * 消费者按文件名排序读取，确保时序性。
 *
 * ## 权限
 * - snapshots/signals：server (root) 写，MediaProvider 读
 * - events：MediaProvider 写，server 读/消费
 * - 共享写目录使用 01777 sticky bit，避免跨 UID 删除/替换对方文件
 * - 数据文件默认 0644，仅 signal 文件保留 0666 用于无内容通知
 */
object DataBus {
    private const val TAG = "DataBus"

    private const val CLEANER_ROOT = "/data/local/tmp/cleaner"
    const val BUS_ROOT = "$CLEANER_ROOT/bus"

    private const val MODE_DIR_WORLD_READABLE = 493 // 0755
    private const val MODE_DIR_SHARED_STICKY = 1023 // 01777
    private const val MODE_FILE_WORLD_READABLE = 420 // 0644
    private const val MODE_FILE_WORLD_WRITABLE = 438 // 0666

    private const val DIR_SNAPSHOTS = "snapshots"
    private const val DIR_SIGNALS = "signals"
    // 以下原语与目录常量对模块内可见：DataBusEventQuarantine /
    // DataBusRecoveryLedger 复用同一套原子写实现，避免跨文件复制。
    internal const val DIR_EVENTS = "events"
    internal const val DIR_CURSORS = "cursors"
    internal const val DIR_LEASES = "leases"

    internal const val DIR_COUNTERS = "counters"
    private const val DIR_CONSUMED = "consumed"
    private const val DIR_TMP = "tmp"

    @Volatile
    private var initialized = false

    /**
     * 确保总线目录结构存在，设置跨进程可访问权限。
     * 幂等，可在 server 或 MediaProvider 进程中调用。
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized) return true

        try {
            for (dir in requiredDirectories()) {
                if (!prepareDirectory(File(dir))) {
                    return false
                }
            }
            initialized = true
            Log.i(TAG, "DataBus initialized at $BUS_ROOT")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DataBus", e)
            return false
        }
    }

    // ── 快照读写 ──

    fun writeSnapshot(name: String, content: String): Boolean {
        if (!isValidSnapshotName(name)) return false
        if (!ensureInitialized()) return false
        val targetFile = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        val tmpFile = try {
            createTempFileIn(File("$BUS_ROOT/$DIR_TMP"), "$name-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create snapshot temp file: $name", e)
            return false
        }

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                // tmpfs 上 rename 永远在同一文件系统内，失败概率极低；
                // 放弃 fallback copy 以避免非原子覆盖的数据丢失窗口。
                Log.e(TAG, "rename failed for $name on tmpfs, deleting tmp")
                tmpFile.delete()
                return false
            }
            // 确保 MediaProvider 进程可读取，但不能改写 server 发布的快照。
            makeWorldAccessible(targetFile, executable = false, writable = false)
            Log.d(TAG, "Snapshot written: $name (${content.length} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write snapshot: $name", e)
            tmpFile.delete()
            false
        }
    }

    fun readSnapshot(name: String): String? {
        if (!isValidSnapshotName(name)) return null
        val file = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        return try {
            readRegularText(file, "snapshot/$name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read snapshot: $name", e)
            null
        }
    }

    /**
     * 读取快照并验证 JSON 有效性。
     *
     * 如果快照损坏（不是有效 JSON），将文件隔离为 .corrupted 后缀
     * 以避免重复读取失败，并返回 null。
     * 隔离而非删除——保留损坏文件供人工审查。
     *
     * 此方法与 [readSnapshot] 的区别在于增加了 JSON 格式验证和损坏隔离。
     * 消费者按需选择使用：
     * - 健康检查/诊断路径 → [readSnapshotSafe]（本方法）
     * - 热路径且自身有 JSON 解析保护的 → [readSnapshot]
     */
    fun readSnapshotSafe(name: String): String? {
        if (!isValidSnapshotName(name)) return null
        val file = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        return try {
            val content = readRegularText(file, "snapshot/$name") ?: return null
            // 验证 JSON 格式有效性
            JSONObject(content)
            content
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Corrupted snapshot detected: $name, quarantining")
            try {
                val corruptedName = "${name}.corrupted.${System.currentTimeMillis()}"
                file.renameTo(File(file.parentFile, corruptedName))
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to quarantine corrupted snapshot $name", e2)
                file.delete()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read snapshot: $name", e)
            null
        }
    }

    // ── 信号 ──

    fun signal(name: String): Boolean {
        if (!isValidSignalName(name)) return false
        if (!ensureInitialized()) return false
        val signalFile = File("$BUS_ROOT/$DIR_SIGNALS/$name")
        return try {
            val path = signalFile.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                Files.delete(path)
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                signalFile.createNewFile()
            } else {
                signalFile.setLastModified(System.currentTimeMillis())
            }
            makeWorldAccessible(signalFile, executable = false)
            Log.d(TAG, "Signal sent: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send signal: $name", e)
            false
        }
    }

    fun getSignalTimestamp(name: String): Long {
        if (!isValidSignalName(name)) return 0L
        val signalFile = File("$BUS_ROOT/$DIR_SIGNALS/$name")
        return if (isRegularFileNoFollow(signalFile)) signalFile.lastModified() else 0L
    }

    // ── 事件队列（单文件原子写） ──

    /**
     * 原子写入一个事件。
     *
     * 每个事件为单独的 JSON 文件：
     *   `events/<queue>/<seq20>-<ts>-<pid>-<rand>.json`
     *
     * 写入流程：tmp → fsync → rename → 目标文件
     * 多进程安全：持久化队列序号 + 文件锁串行发布，确保文件名顺序与可见顺序一致。
     *
     * @param queue 事件队列子目录名
     * @param content JSON 字符串
     * @return 事件序列号（成功），-1（失败）
     */
    fun writeEvent(queue: String, content: String): Long {
        if (!isValidEventQueue(queue)) return -1L
        if (!ensureInitialized()) return -1L
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!prepareDirectory(eventDir)) {
            return -1L
        }

        // 序号分配与原子落盘见 DataBusEventWriter（实现），此处仅保留公开 API 面。
        return DataBusEventWriter.writeEventLocked(queue, eventDir, content)
    }

    /**
     * 读取游标之后的所有事件。
     *
     * @param queue 事件队列子目录名
     * @param afterCursor 游标值（上次消费的最后文件名），"" 表示从头开始
     * @return 事件 JSON 字符串列表（按文件名排序）
     */
    fun readEvents(queue: String, afterCursor: String): List<String> {
        return readEventFiles(queue, afterCursor).map { it.content }
    }

    /**
     * 读取游标之后的所有事件，并保留文件名供消费者精确推进游标。
     */
    fun readEventFiles(queue: String, afterCursor: String): List<DataBusProtocol.EventFile> {
        if (!isValidEventQueue(queue)) return emptyList()
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists()) return emptyList()

        return try {
            eventDir.listFiles()
                ?.filter {
                    isEventFile(it) && it.name > afterCursor
                }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    readRegularText(file, "events/$queue/${file.name}")?.let {
                        DataBusProtocol.EventFile(file.name, it)
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read events from $queue", e)
            emptyList()
        }
    }

    /**
     * 获取事件队列中最后一个事件的文件名（用作游标）。
     *
     * 返回契约：
     * - 非法队列 / 目录不存在 / 空队列 → ""（保持既有语义）；
     * - `listFiles() == null`（目录枚举失败）→ null（哨兵，**不得**当空队列）。
     * 调用方必须把 null 按失败处理，不得继续把游标当作可信水位。
     */
    fun getLastEventFilename(queue: String): String? {
        if (!isValidEventQueue(queue)) return ""
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists()) return ""
        // 枚举失败返回 null 哨兵：调用方按失败处理，不得当空队列。
        val entries = eventDir.listFiles() ?: return null
        return entries
            .filter { isRegularFileNoFollow(it) && it.name.endsWith(".json") }
            .maxByOrNull { it.name }
            ?.name ?: ""
    }

    // ── 消费游标持久化 ──

    /**
     * 持久化消费游标。
     * 原子写：tmp → fsync → rename。
     */
    fun writeCursor(queue: String, cursor: String): Boolean {
        if (!isValidEventQueue(queue)) return false
        if (!ensureInitialized()) return false
        val cursorDir = File("$BUS_ROOT/$DIR_CURSORS")
        if (!prepareDirectory(cursorDir)) return false

        val cursorFile = File(cursorDir, "$queue.cursor")
        val tmpFile = try {
            createTempFileIn(cursorDir, "$queue.cursor-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cursor temp file: $queue", e)
            return false
        }

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(cursor.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(cursorFile)) {
                // tmpfs 上 rename 永远在同一文件系统内，失败概率极低；
                // 放弃 fallback copy 以避免非原子覆盖的数据丢失窗口。
                Log.e(TAG, "rename failed for cursor: $queue, deleting tmp")
                tmpFile.delete()
                return false
            }
            makeWorldAccessible(cursorFile, executable = false, writable = false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cursor: $queue", e)
            tmpFile.delete()
            false
        }
    }

    fun writeCursorToEvent(queue: String, event: DataBusProtocol.EventFile): Boolean =
        writeCursor(queue, event.name)

    // ── 恢复熔断总账（Fix 2：破坏轮次跨 server 重启延续） ──
    // 实现见 [DataBusRecoveryLedger]；此处仅保留跨模块公开 API 面。

    /** 读取恢复总账 JSON，缺失/非法返回 null（调用方视为全新 episode）。 */
    fun readRecoveryLedger(): String? = DataBusRecoveryLedger.read()

    /**
     * 区分式读取恢复总账：Absent 可按全新处理，Corrupted 必须保守。
     * 返回公开契约类型，跨模块可见。
     */
    fun readRecoveryLedgerDetailed(): DataBusProtocol.RecoveryLedgerRead =
        DataBusRecoveryLedger.readDetailed()

    /** 原子持久化恢复总账 JSON。 */
    fun writeRecoveryLedger(content: String): Boolean = DataBusRecoveryLedger.write(content)

    /** 清除恢复总账（Hook 确认恢复后调用）。缺失视为成功。 */
    fun clearRecoveryLedger(): Boolean = DataBusRecoveryLedger.clear()

    // ── 毒丸隔离与重试计数（P0-B：Poison/Transient 分离） ──
    // 实现见 [DataBusEventQuarantine]；此处仅保留跨模块公开 API 面。

    /**
     * 隔离毒丸事件：原文保留在队列目录，仅推进游标跳过。
     * 信封内含原文、原因、阶段、次数，便于排障；原子写 tmp→fsync→rename。
     */
    fun quarantineEvent(
        queue: String,
        event: DataBusProtocol.EventFile,
        reason: String,
        stage: String,
        attempts: Int,
    ): Boolean = DataBusEventQuarantine.quarantine(queue, event, reason, stage, attempts)

    /** 读取指定事件的处理重试计数，缺失/非法视为 0。 */
    fun readEventAttempt(queue: String, eventName: String): Int =
        DataBusEventQuarantine.readAttempt(queue, eventName)

    /** 持久化指定事件的处理重试计数。 */
    fun writeEventAttempt(queue: String, eventName: String, count: Int): Boolean =
        DataBusEventQuarantine.writeAttempt(queue, eventName, count)

    /** 清除指定事件的重试计数（消费成功或已隔离后调用）。缺失视为成功。 */
    fun clearEventAttempt(queue: String, eventName: String): Boolean =
        DataBusEventQuarantine.clearAttempt(queue, eventName)

    // ── 事件保留与清理（有界生命周期） ──
    // 实现见 [DataBusPrune]；此处仅保留跨模块公开 API 面。

    /**
     * 清理队列中「已消费且超过保留期」的源事件文件。
     *
     * 安全前提见 [DataBusPrune.pruneQueueEvents]。
     *
     * @param retentionMs 保留期；0 表示仅受游标约束、不受时间约束（测试用）
     * @param maxDeletePerRun 限制单轮**成功删除**数量（删除失败不阻断后续）。
     *   注意：目录枚举本身是全量的（`java.io.File` 无流式 API），本参数约束的是
     *   删除动作数而非扫描量；千级文件枚举成本可忽略，不伪装成扫描上限。
     */
    fun pruneQueueEvents(
        queue: String,
        retentionMs: Long,
        maxDeletePerRun: Int = 500,
    ): DataBusProtocol.PruneResult =
        DataBusPrune.pruneQueueEvents(queue, retentionMs, maxDeletePerRun)

    /**
     * 清理隔离目录中超过保留期的毒丸证据文件。
     *
     * 与源事件清理不同：隔离证据不依赖消费游标，只受保留期与文件规则约束，
     * 因此结果中的 cursorRead 恒为 null。
     */
    fun pruneQuarantine(
        queue: String,
        retentionMs: Long,
        maxDeletePerRun: Int = 500,
    ): DataBusProtocol.PruneResult =
        DataBusPrune.pruneQuarantine(queue, retentionMs, maxDeletePerRun)

    /**
     * 清理孤儿重试计数：计数对应事件文件已不在源队列时删除。
     *
     * 不依赖消费游标（计数清理只与事件存量有关），结果 cursorRead 恒为 null。
     * 实现见 [DataBusEventQuarantine.pruneOrphanAttempts]。
     */
    fun pruneOrphanAttempts(
        queue: String,
        maxDeletePerRun: Int = 500,
    ): DataBusProtocol.PruneResult =
        DataBusEventQuarantine.pruneOrphanAttempts(queue, maxDeletePerRun)

    /**
     * 源事件删除判定（纯函数，安全契约的可测载体）。
     *
     * 规则：
     * 1. `name > cursor` 一律不删——未越过提交点，即使超过保留期也不行；
     * 2. `name <= cursor` 才可能删除；游标为空（""）时任何非空文件名都 `> ""`，
     *    因此空游标天然删 0 条；
     * 3. `retentionMs == 0` 走显式特殊分支：仅受游标约束，不做时间比较。
     */
    internal fun shouldPruneEvent(
        name: String,
        cursor: String,
        lastModified: Long,
        now: Long,
        retentionMs: Long,
    ): Boolean = DataBusPrune.shouldPruneEvent(name, cursor, lastModified, now, retentionMs)

    // ── Lease（短期会话） ──

    /**
     * 原子写入一个短期 lease。实现见 [DataBusLease]。
     *
     * Lease 表示短期有效状态，命名由调用方提供但会被规整为文件安全形式。
     * 内容仍由调用方使用 JSON 表达，并在 payload 中包含 expiresAt。
     */
    fun writeLease(category: String, key: String, content: String): Boolean =
        DataBusLease.writeLease(category, key, content)

    internal fun prepareDirectory(dir: File): Boolean = try {
        val path = dir.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            Files.delete(path)
        }
        Files.createDirectories(path)
        makeWorldAccessible(dir, executable = true)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to prepare directory: ${dir.path}", e)
        false
    }

    internal fun createTempFileIn(dir: File, prefix: String, suffix: String): File {
        val safePrefix = prefix
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .padEnd(3, '_')
        return Files.createTempFile(dir.toPath(), safePrefix, suffix).toFile()
    }

    /**
     * 事件文件判定：**消费与清理共用的唯一口径**。
     *
     * 实现见 [DataBusPrune.isEventFile]。规则为「常规文件（不跟随软链）+ `.json` 后缀」。
     * `readEventFiles`（消费）、清理与 pending 统计都必须通过本函数判定，
     * 不得各自实现近似规则——否则清理会漏掉消费端能读到的文件，
     * 导致无界增长换个形式复发。
     *
     * `.tmp` 临时文件、游标文件、子目录均不满足本判定，因此不会被清理触及。
     */
    internal fun isEventFile(file: File): Boolean =
        DataBusPrune.isEventFile(file)

    /**
     * 隔离证据文件判定：与源事件是两种东西，分开表达。
     *
     * 实现见 [DataBusPrune.isQuarantineEvidenceFile]。
     */
    internal fun isQuarantineEvidenceFile(file: File): Boolean =
        DataBusPrune.isQuarantineEvidenceFile(file)

    internal fun isRegularFileNoFollow(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    internal fun readRegularText(file: File, label: String): String? {
        if (!isRegularFileNoFollow(file)) {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Log.w(TAG, "Rejected non-regular DataBus file: $label")
            }
            return null
        }
        return file.readText(Charsets.UTF_8)
    }

    fun readLeaseFiles(category: String): List<DataBusProtocol.EventFile> =
        DataBusLease.readLeaseFiles(category)

    // 事件序号分配与原子落盘见 DataBusEventWriter（实现，含序号水位观测）。

    fun deleteLeaseFile(category: String, name: String): Boolean =
        DataBusLease.deleteLeaseFile(category, name)

    /**
     * 读取持久化消费游标的可信状态与值。
     *
     * 实现见 [DataBusPrune.readCursorDetailed]。不复用 `readRegularText`：
     * 该函数把「路径不存在」「路径存在但非常规文件」「读取抛异常」三种情况
     * 全部塌缩为 null，使 ABSENT 与 UNREADABLE 无法区分。
     *
     * 空白内容与格式非法一律判 UNREADABLE，见
     * [DataBusPrune.isCredibleCursorContent]。
     */
    fun readCursorDetailed(queue: String): Pair<DataBusProtocol.CursorRead, String> =
        DataBusPrune.readCursorDetailed(queue)

    /**
     * 游标内容可信判定（纯函数）。实现见 [DataBusPrune.isCredibleCursorContent]。
     *
     * 规则：非空 **且** 完整匹配事件文件名格式。抽取为纯函数的原因：
     * 文件存在性/常规性判定依赖真实文件系统，JVM 单测无法隔离；
     * 内容判定是纯字符串逻辑，必须可测。
     */
    internal fun isCredibleCursorContent(content: String): Boolean =
        DataBusPrune.isCredibleCursorContent(content)

    /**
     * 读取持久化消费游标。
     *
     * 既有签名与语义保持不变：ABSENT 与 UNREADABLE 都返回 ""。
     * 需要区分二者时使用 [readCursorDetailed]。
     */
    fun readCursor(queue: String): String =
        DataBusPrune.readCursor(queue)

    internal fun sanitizeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "lease" }

    private fun isValidSnapshotName(name: String): Boolean =
        isValidName("snapshot", name, DataBusProtocol.validSnapshotNames)

    private fun isValidSignalName(name: String): Boolean =
        isValidName("signal", name, DataBusProtocol.validSignalNames)

    internal fun isValidEventQueue(queue: String): Boolean =
        isValidName("event queue", queue, DataBusProtocol.validEventQueues)

    internal fun isValidLeaseCategory(category: String): Boolean =
        isValidName("lease category", category, DataBusProtocol.validLeaseCategories)

    private fun isValidName(kind: String, value: String, allowed: Set<String>): Boolean {
        if (value in allowed) {
            return true
        }
        Log.w(TAG, "Rejected invalid DataBus $kind: $value")
        return false
    }

    /**
     * 检查 DataBus 是否具备跨进程工作所需的目录、权限与关键快照。
     *
     * @param repair true 时会尝试创建缺失目录并修复权限。
     */
    fun checkHealth(repair: Boolean = false): DataBusProtocol.HealthReport {
        val init = if (repair) ensureInitialized() else initialized ||
            File(BUS_ROOT).exists()
        val missingDirs = mutableListOf<String>()
        val permissionIssues = mutableListOf<String>()

        for (dir in requiredDirectories()) {
            val file = File(dir)
            val path = file.toPath()
            var exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            var isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            if (!exists || !isDirectory) {
                if (repair) {
                    prepareDirectory(file)
                    exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                }
                if (!exists || !isDirectory) {
                    missingDirs += dir
                }
            }
            if (exists && isDirectory) {
                if (repair) {
                    makeWorldAccessible(file, executable = true)
                }
                if (!file.canRead()) permissionIssues += "$dir:read"
                if (!file.canWrite()) permissionIssues += "$dir:write"
                if (!file.canExecute()) permissionIssues += "$dir:execute"
            }
        }

        // 每个队列只计算一次：pending 数量与游标状态必须来自同一次观测。
        // 若分两次调用，并发消费或游标变化会把不可信数量与可信状态拼在一起。
        val filesystemPending = countPendingEvents(DataBusProtocol.EVENT_FILESYSTEM)
        val redirectPending = countPendingEvents(DataBusProtocol.EVENT_REDIRECT_NOTICE)
        return DataBusProtocol.HealthReport(
            initialized = init && missingDirs.isEmpty(),
            missingDirectories = missingDirs,
            permissionIssues = permissionIssues,
            snapshots = DataBusProtocol.snapshotNames().map { inspectSnapshot(it) },
            eventQueueCounts = mapOf(
                DataBusProtocol.EVENT_FILESYSTEM to countJsonFiles(
                    "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_FILESYSTEM}",
                ),
                DataBusProtocol.EVENT_REDIRECT_NOTICE to countJsonFiles(
                    "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_REDIRECT_NOTICE}",
                ),
                DIR_CONSUMED to countJsonFiles("$BUS_ROOT/$DIR_EVENTS/$DIR_CONSUMED"),
            ),
            leaseCounts = mapOf(
                DataBusProtocol.LEASE_QUERY_SESSIONS to countJsonFiles(
                    "$BUS_ROOT/$DIR_LEASES/${DataBusProtocol.LEASE_QUERY_SESSIONS}",
                ),
            ),
            // pending 与游标可信状态：与 readEventFiles 同筛选同边界，
            // 数据损坏时按队列退化，不吞并整体状态。
            pendingEventCounts = mapOf(
                DataBusProtocol.EVENT_FILESYSTEM to filesystemPending.first,
                DataBusProtocol.EVENT_REDIRECT_NOTICE to redirectPending.first,
            ),
            quarantineCounts = mapOf(
                DataBusProtocol.EVENT_FILESYSTEM to countJsonFiles(
                    "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine",
                ),
                DataBusProtocol.EVENT_REDIRECT_NOTICE to countJsonFiles(
                    "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine",
                ),
            ),
            cursorReadStates = mapOf(
                DataBusProtocol.EVENT_FILESYSTEM to filesystemPending.second,
                DataBusProtocol.EVENT_REDIRECT_NOTICE to redirectPending.second,
            ),
        )
    }

    /**
     * 按队列计算 (pending, cursorRead)。
     *
     * 筛选规则**必须**与 [readEventFiles] 一致：常规文件 + `.json` 后缀 +
     * 字典序 `name > afterCursor`。若修改一处筛选，必须同步另一处，
     * 否则 pending 会与消费行为分叉，产生另一种误报。
     *
     * - ABSENT（新队列）：全部有效事件计入 pending；
     * - OK：只计 `name > cursor`；
     * - UNREADABLE：pending 按剩余文件估算但标记不可信，
     *   调用方不得把它当作可信积压依据。
     */
    private fun countPendingEvents(queue: String): Pair<Int, DataBusProtocol.CursorRead> {
        val (read, cursor) = readCursorDetailed(queue)
        val dir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        val files = dir.listFiles()?.filter { isEventFile(it) } ?: emptyList()
        val pending = when (read) {
            DataBusProtocol.CursorRead.ABSENT -> files.size
            DataBusProtocol.CursorRead.OK -> files.count { it.name > cursor }
            DataBusProtocol.CursorRead.UNREADABLE -> files.size
        }
        return pending to read
    }

    private fun requiredDirectories(): List<String> = listOf(
        CLEANER_ROOT,
        BUS_ROOT,
        "$BUS_ROOT/$DIR_SNAPSHOTS",
        "$BUS_ROOT/$DIR_SIGNALS",
        "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_FILESYSTEM}",
        "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_REDIRECT_NOTICE}",
        "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine",
        "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine",
        "$BUS_ROOT/$DIR_EVENTS/$DIR_CONSUMED",
        "$BUS_ROOT/$DIR_LEASES/${DataBusProtocol.LEASE_QUERY_SESSIONS}",
        "$BUS_ROOT/$DIR_CURSORS",
        "$BUS_ROOT/$DIR_CURSORS/${DataBusProtocol.EVENT_FILESYSTEM}.attempts",
        "$BUS_ROOT/$DIR_CURSORS/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.attempts",
        "$BUS_ROOT/$DIR_COUNTERS",
        "$BUS_ROOT/$DIR_TMP",
    )

    private fun inspectSnapshot(name: String): DataBusProtocol.SnapshotHealth {
        val file = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return DataBusProtocol.SnapshotHealth(name, exists = false, validJson = false)
        }
        return try {
            val content = readRegularText(file, "snapshot/$name")
                ?: return DataBusProtocol.SnapshotHealth(name, exists = true, validJson = false)
            JSONObject(content)
            DataBusProtocol.SnapshotHealth(name, exists = true, validJson = true)
        } catch (e: Exception) {
            DataBusProtocol.SnapshotHealth(
                name = name,
                exists = true,
                validJson = false,
                error = e.message ?: e.javaClass.name,
            )
        }
    }

    private fun countJsonFiles(path: String): Int {
        val dir = File(path)
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0
        return dir.listFiles()
            ?.count { isRegularFileNoFollow(it) && it.name.endsWith(".json") }
            ?: 0
    }

    internal fun makeWorldAccessible(file: File, executable: Boolean, writable: Boolean = true) {
        // ⚠️ 只发一次 `chmod`：末尾的 `Os.chmod(path, mode)` 用的是**绝对 mode**，
        // 会把之前任何 `File.setReadable/setWritable/setExecutable` 的结果完全覆盖，
        // 而那些调用每一个内部都要 `stat` + `chmod`（2 次系统调用），在绝对 chmod 之下
        // 属纯冗余。本函数在每次事件写入路径上会被调用两次（计数器文件 + 事件文件），
        // 去掉冗余调用可让**每个事件少 6~8 次系统调用**（bus 根在 f2fs 上，非 tmpfs）。
        //
        // 唯一的语义差异：`Os.chmod` 失败时旧实现还残留 setXxx 的部分效果作为兜底。
        // 该路径仅在非 root 进程调用时出现（此时 setXxx 同样会失败），失败仍会打 WARN，行为可观测。
        // 上游此前的写法保留在历史中（见 ADR 之前的 DataBus.makeWorldAccessible）。
        val mode = if (executable) {
            directoryMode(file)
        } else if (writable) {
            MODE_FILE_WORLD_WRITABLE
        } else {
            MODE_FILE_WORLD_READABLE
        }
        try {
            Os.chmod(file.path, mode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to chmod ${file.path} to $mode", e)
        }
    }

    private fun directoryMode(dir: File): Int = when (dir.path) {
        "$BUS_ROOT/$DIR_SIGNALS",
        "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_FILESYSTEM}",
        "$BUS_ROOT/$DIR_EVENTS/${DataBusProtocol.EVENT_REDIRECT_NOTICE}",
        "$BUS_ROOT/$DIR_LEASES/${DataBusProtocol.LEASE_QUERY_SESSIONS}",
        "$BUS_ROOT/$DIR_COUNTERS" -> MODE_DIR_SHARED_STICKY
        else -> MODE_DIR_WORLD_READABLE
    }
}
