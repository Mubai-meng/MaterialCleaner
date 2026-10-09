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
    private const val DIR_EVENTS = "events"
    private const val DIR_LEASES = "leases"
    private const val DIR_CURSORS = "cursors"
    private const val DIR_COUNTERS = "counters"
    private const val DIR_CONSUMED = "consumed"
    private const val DIR_TMP = "tmp"

    @Volatile
    private var initialized = false

    // 进程内序号下界；真实事件序号会通过 counters/ 持久化分配。
    private val eventSeqCounter = AtomicLong(0)

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

        return writeEventLocked(queue, eventDir, content)
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
                    isRegularFileNoFollow(it) && it.name.endsWith(".json") &&
                            it.name > afterCursor
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
     */
    fun getLastEventFilename(queue: String): String {
        if (!isValidEventQueue(queue)) return ""
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists()) return ""
        return eventDir.listFiles()
            ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".json") }
            ?.maxByOrNull { it.name }
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
    //
    // 职责：DataBus 只提供不透明内容的原子存取，不解释恢复语义；
    // 语义（何时写/清、熔断含义）归 MediaProviderRecoveryStrategy。
    // server 私有状态，不进 snapshots 白名单，不参与健康快照检查。

    private const val RECOVERY_LEDGER_FILE = "media_provider_recovery.json"

    /** 读取恢复总账 JSON，缺失/非法返回 null（调用方视为全新 episode）。 */
    fun readRecoveryLedger(): String? {
        val file = File("$BUS_ROOT/$DIR_CURSORS/$RECOVERY_LEDGER_FILE")
        return try {
            readRegularText(file, "cursors/$RECOVERY_LEDGER_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read recovery ledger", e)
            null
        }
    }

    /** 原子持久化恢复总账 JSON。 */
    fun writeRecoveryLedger(content: String): Boolean {
        if (!ensureInitialized()) return false
        val cursorDir = File("$BUS_ROOT/$DIR_CURSORS")
        if (!prepareDirectory(cursorDir)) return false
        val tmpFile = try {
            createTempFileIn(cursorDir, "$RECOVERY_LEDGER_FILE-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recovery ledger temp file", e)
            return false
        }
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val targetFile = File(cursorDir, RECOVERY_LEDGER_FILE)
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Recovery ledger rename failed, deleting tmp")
                tmpFile.delete()
                return false
            }
            makeWorldAccessible(targetFile, executable = false, writable = false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write recovery ledger", e)
            tmpFile.delete()
            false
        }
    }

    /** 清除恢复总账（Hook 确认恢复后调用）。缺失视为成功。 */
    fun clearRecoveryLedger(): Boolean {
        val file = File("$BUS_ROOT/$DIR_CURSORS/$RECOVERY_LEDGER_FILE")
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear recovery ledger", e)
            false
        }
    }

    // ── 毒丸隔离与重试计数（P0-B：Poison/Transient 分离） ──
    //
    // 职责：DataBus 只拥有物理隔离与计数持久化，不拥有“是否毒丸”的判定；
    // 判定归消费侧 EventConsumePolicy。隔离成功才允许推进游标；隔离失败
    // 不得删除原文、不得推进。隔离文件名由事件名确定，重复隔离幂等覆盖。

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
    ): Boolean {
        if (!isValidEventQueue(queue)) return false
        if (!ensureInitialized()) return false
        val quarantineDir = File("$BUS_ROOT/$DIR_EVENTS/$queue.quarantine")
        if (!prepareDirectory(quarantineDir)) return false
        val envelope = try {
            JSONObject()
                .put("queue", queue)
                .put("originalName", event.name)
                .put("reason", reason.take(500))
                .put("stage", stage.take(120))
                .put("attempts", attempts)
                .put("quarantinedAt", System.currentTimeMillis())
                .put("content", event.content)
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build quarantine envelope: $queue/${event.name}", e)
            return false
        }
        val filename = "${sanitizeFileName(event.name)}.quarantine.json"
        val tmpFile = try {
            createTempFileIn(quarantineDir, "$filename-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create quarantine temp file: $queue/${event.name}", e)
            return false
        }
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(envelope.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val targetFile = File(quarantineDir, filename)
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Quarantine rename failed: $queue/$filename")
                tmpFile.delete()
                return false
            }
            makeWorldAccessible(targetFile, executable = false, writable = false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quarantine event: $queue/${event.name}", e)
            tmpFile.delete()
            false
        }
    }

    /** 读取指定事件的处理重试计数，缺失/非法视为 0。 */
    fun readEventAttempt(queue: String, eventName: String): Int {
        if (!isValidEventQueue(queue)) return 0
        val file = File("$BUS_ROOT/$DIR_CURSORS/$queue.attempts/${sanitizeFileName(eventName)}")
        return try {
            readRegularText(file, "attempts/$queue/$eventName")
                ?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read attempt: $queue/$eventName", e)
            0
        }
    }

    /** 持久化指定事件的处理重试计数。 */
    fun writeEventAttempt(queue: String, eventName: String, count: Int): Boolean {
        if (!isValidEventQueue(queue)) return false
        if (!ensureInitialized()) return false
        val attemptDir = File("$BUS_ROOT/$DIR_CURSORS/$queue.attempts")
        if (!prepareDirectory(attemptDir)) return false
        val tmpFile = try {
            createTempFileIn(attemptDir, "${sanitizeFileName(eventName)}-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create attempt temp file: $queue/$eventName", e)
            return false
        }
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(count.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val targetFile = File(attemptDir, sanitizeFileName(eventName))
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Attempt rename failed: $queue/$eventName")
                tmpFile.delete()
                return false
            }
            makeWorldAccessible(targetFile, executable = false, writable = false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write attempt: $queue/$eventName", e)
            tmpFile.delete()
            false
        }
    }

    /** 清除指定事件的重试计数（消费成功或已隔离后调用）。缺失视为成功。 */
    fun clearEventAttempt(queue: String, eventName: String): Boolean {
        if (!isValidEventQueue(queue)) return false
        val file = File("$BUS_ROOT/$DIR_CURSORS/$queue.attempts/${sanitizeFileName(eventName)}")
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear attempt: $queue/$eventName", e)
            false
        }
    }

    // ── Lease（短期会话） ──

    /**
     * 原子写入一个短期 lease。
     *
     * Lease 表示短期有效状态，命名由调用方提供但会被规整为文件安全形式。
     * 内容仍由调用方使用 JSON 表达，并在 payload 中包含 expiresAt。
     */
    fun writeLease(category: String, key: String, content: String): Boolean {
        if (!isValidLeaseCategory(category)) return false
        if (!ensureInitialized()) return false
        val leaseDir = File("$BUS_ROOT/$DIR_LEASES/$category")
        if (!prepareDirectory(leaseDir)) {
            return false
        }

        val filename = "${sanitizeFileName(key)}.json"
        val tmpFile = try {
            createTempFileIn(leaseDir, "$filename-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create lease temp file: $category/$filename", e)
            return false
        }
        val targetFile = File(leaseDir, filename)

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Lease rename failed: $category/$filename")
                tmpFile.delete()
                return false
            }
            makeWorldAccessible(targetFile, executable = false, writable = false)
            Log.d(TAG, "Lease written: $category/$filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write lease: $category/$filename", e)
            tmpFile.delete()
            false
        }
    }

    private fun prepareDirectory(dir: File): Boolean = try {
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

    private fun createTempFileIn(dir: File, prefix: String, suffix: String): File {
        val safePrefix = prefix
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .padEnd(3, '_')
        return Files.createTempFile(dir.toPath(), safePrefix, suffix).toFile()
    }

    private fun isRegularFileNoFollow(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun readRegularText(file: File, label: String): String? {
        if (!isRegularFileNoFollow(file)) {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Log.w(TAG, "Rejected non-regular DataBus file: $label")
            }
            return null
        }
        return file.readText(Charsets.UTF_8)
    }

    fun readLeaseFiles(category: String): List<DataBusProtocol.EventFile> {
        if (!isValidLeaseCategory(category)) return emptyList()
        val leaseDir = File("$BUS_ROOT/$DIR_LEASES/$category")
        if (!leaseDir.exists()) return emptyList()

        return try {
            leaseDir.listFiles()
                ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    readRegularText(file, "leases/$category/${file.name}")?.let {
                        DataBusProtocol.EventFile(file.name, it)
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read leases from $category", e)
            emptyList()
        }
    }

    @Synchronized
    private fun writeEventLocked(queue: String, eventDir: File, content: String): Long {
        val counterDir = File("$BUS_ROOT/$DIR_COUNTERS")
        if (!prepareDirectory(counterDir)) return -1L

        val counterFile = File(counterDir, "$queue.seq")
        val counterPath = counterFile.toPath()
        return try {
            if (Files.exists(counterPath, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(counterPath) ||
                        !Files.isRegularFile(counterPath, LinkOption.NOFOLLOW_LINKS))
            ) {
                Files.delete(counterPath)
            }

            RandomAccessFile(counterFile, "rw").use { raf ->
                raf.channel.use { channel ->
                    channel.lock().use {
                        val next = nextEventSequence(queue, raf)
                        writeCounterValue(raf, next)
                        makeWorldAccessible(counterFile, executable = false, writable = true)
                        if (writeEventFile(queue, eventDir, content, next)) {
                            eventSeqCounter.updateAndGet { current -> maxOf(current, next) }
                            next
                        } else {
                            -1L
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event with locked sequence for $queue", e)
            -1L
        }
    }

    private fun nextEventSequence(queue: String, raf: RandomAccessFile): Long {
        val storedSeq = readCounterValue(raf)
        val cursorSeq = parseEventSequence(readCursor(queue)) ?: 0L
        val queuedSeq = maxEventSequence(queue)
        val processSeq = eventSeqCounter.incrementAndGet()
        return maxOf(storedSeq + 1, cursorSeq + 1, queuedSeq + 1, processSeq)
    }

    private fun readCounterValue(raf: RandomAccessFile): Long {
        raf.seek(0)
        val content = ByteArray(raf.length().coerceAtMost(64L).toInt())
        if (content.isEmpty()) return 0L
        raf.readFully(content)
        return content.toString(Charsets.UTF_8).trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    private fun writeCounterValue(raf: RandomAccessFile, value: Long) {
        raf.setLength(0)
        raf.seek(0)
        raf.write(value.toString().toByteArray(Charsets.UTF_8))
        raf.fd.sync()
    }

    private fun writeEventFile(queue: String, eventDir: File, content: String, seq: Long): Boolean {
        val now = System.currentTimeMillis()
        val pid = Process.myPid()
        val rand = ((Math.random() * 0xFFFF).toInt() and 0xFFFF)
        val filename = String.format("%020d-%d-%d-%04x.json", seq, now, pid, rand)

        val tmpFile = try {
            createTempFileIn(eventDir, "$filename-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create event temp file: $queue/$filename", e)
            return false
        }
        val targetFile = File(eventDir, filename)

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Event rename failed: $filename")
                tmpFile.delete()
                return false
            }
            makeWorldAccessible(targetFile, executable = false, writable = false)
            Log.d(TAG, "Event written: $queue/$filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event to $queue", e)
            tmpFile.delete()
            false
        }
    }

    private fun maxEventSequence(queue: String): Long {
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists()) return 0L
        return eventDir.listFiles()
            ?.asSequence()
            ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".json") }
            ?.mapNotNull { parseEventSequence(it.name) }
            ?.maxOrNull()
            ?: 0L
    }

    private fun parseEventSequence(name: String): Long? =
        DataBusProtocol.EVENT_FILE_NAME_PATTERN.matchEntire(name)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    fun deleteLeaseFile(category: String, name: String): Boolean {
        if (!isValidLeaseCategory(category)) return false
        val file = File("$BUS_ROOT/$DIR_LEASES/$category/${sanitizeFileName(name)}")
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete lease: $category/$name", e)
            false
        }
    }

    /**
     * 读取持久化消费游标。
     * @return 游标值（上次消费的最后文件名），"" 表示未消费过
     */
    fun readCursor(queue: String): String {
        if (!isValidEventQueue(queue)) return ""
        val cursorFile = File("$BUS_ROOT/$DIR_CURSORS/$queue.cursor")
        return try {
            readRegularText(cursorFile, "cursors/$queue.cursor")?.trim() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cursor: $queue", e)
            ""
        }
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "lease" }

    private fun isValidSnapshotName(name: String): Boolean =
        isValidName("snapshot", name, DataBusProtocol.validSnapshotNames)

    private fun isValidSignalName(name: String): Boolean =
        isValidName("signal", name, DataBusProtocol.validSignalNames)

    private fun isValidEventQueue(queue: String): Boolean =
        isValidName("event queue", queue, DataBusProtocol.validEventQueues)

    private fun isValidLeaseCategory(category: String): Boolean =
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
        )
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

    private fun makeWorldAccessible(file: File, executable: Boolean, writable: Boolean = true) {
        file.setReadable(true, false)
        if (writable) {
            file.setWritable(true, false)
        } else {
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        if (executable) {
            file.setExecutable(true, false)
        } else {
            file.setExecutable(false, false)
        }
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
