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
 *     consumed/       ← 已消费事件归档
 *   leases/
 *     query_sessions/ ← MediaProvider 写入，server 按 TTL 维护临时目录
 *   cursors/          ← 消费者游标持久化
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

    /** 事件/归档文件后缀。计数只需比较文件名，见 [countQueue]。 */
    private const val JSON_SUFFIX = ".json"

    @Volatile
    private var initialized = false

    /**
     * 本进程内已经报告过目录准备失败的路径。
     *
     * MediaProvider 等非 root 进程对 /data/local/tmp 没有写权限（也不一定有权 stat），
     * `Files.createDirectories("/data/local/tmp/cleaner")` 必然抛 AccessDeniedException。
     * 这是**预期情形**（该进程的正路是 Binder bridge，见 HookDataBusBridge），
     * 但历史实现每次调用都 `Log.e(..., e)`，一个进程启动就打出两条 40+ 行的完整堆栈，
     * 反而把 native hook 的真实 lastError 淹没了。这里按路径去重，每个进程最多报一次。
     */
    private val reportedPrepareFailures =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** 最近一次目录准备失败的精简描述，供诊断归档读取；成功后清空。 */
    @Volatile
    private var lastInitFailure: String? = null

    /** 最近一次目录准备失败的描述（`路径: 异常类: 消息`），从未失败过则为 null。 */
    fun lastInitFailureOrNull(): String? = lastInitFailure

    /**
     * 文件名安全化用的正则。
     *
     * 提到文件级常量：原先写在 `createTempFileIn` 内部，每次调用都要
     * `Pattern.compile` 一次；该函数在每次事件写入路径上会被调用（写事件文件、写游标）。
     */
    private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")

    // 进程内序号下界；真实事件序号会通过 counters/ 持久化分配。
    private val eventSeqCounter = AtomicLong(0)

    /**
     * 每个队列已分配到的最大序号（含本进程分配）。
     *
     * 用于在 `counters/<queue>.seq` 被外部清除或出现回退时判定"需要重建下界"，
     * 从而让正常写入路径**不必**再读游标、更不必 `listFiles()` 扫描整个事件目录。
     */
    private val queueSeqFloor =
        java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    // 协议常量与数据载体（SnapshotHealth / HealthReport / EventFile / 文件名正则）
    // 统一由 DataBusProtocol 承载，本类不再重复声明，避免两处口径漂移。

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
            lastInitFailure = null
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
                // 真机证据：本机 /data/local/tmp 与 bus 根同处**同一文件系统**（f2fs，非 tmpfs，
                // 见 FilesystemProbe 结论），因此 rename 不会跨设备、失败概率极低；
                // 放弃 fallback copy 以避免非原子覆盖的数据丢失窗口。
                Log.e(TAG, "rename failed for $name, deleting tmp")
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
                // 同 [writeSnapshot]：同文件系统内 rename 原子，失败概率极低。
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
        lastInitFailure = "${dir.path}: ${e.javaClass.simpleName}: ${e.message}"
        if (reportedPrepareFailures.add(dir.path)) {
            // 每个进程、每个路径只报一次，且**不带堆栈**：这是设计内的降级（非 root 进程无权
            // 建 /data/local/tmp 目录 ⇒ 回退 Binder bridge），异常类名 + 消息已足够定位；
            // 37 帧 sun.nio.fs 内部调用对排障零增量（真机实测每个进程白占 38 行日志）。
            Log.w(
                TAG,
                "DataBus directory not writable from this process (uid=${Process.myUid()}), " +
                        "falling back to Binder bridge: ${dir.path}" +
                        " (${e.javaClass.simpleName}: ${e.message})",
            )
        }
        false
    }

    private fun createTempFileIn(dir: File, prefix: String, suffix: String): File {
        val safePrefix = prefix
            .replace(UNSAFE_FILENAME_CHARS, "_")
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

    /**
     * 分配下一个事件序号。
     *
     * ## 不变式（快路径的依据）
     * 调用方在持有 `counters/<queue>.seq` 文件锁的前提下，**先写计数器、再写事件文件**。
     * 因此计数器里的值永远 ≥ 目录中任何已存在事件文件的序号 —— 只要它是单调不减的，
     * 新序号就一定不会与既有文件冲突，也不会落在游标之前。
     *
     * ## 快路径（绝大多数情况）
     * 直接信任计数器文件。**不读游标、不 `listFiles()`**。原实现每写一个事件都要
     * 读一次游标文件、并对整个事件目录做 `listFiles()` + 逐文件 stat + 正则解析
     * （稳态上限 2000 个文件），是本项目最重的热路径开销。
     *
     * ## 慢路径（罕见）
     * 仅在计数器文件尚未建立（首次写入）、被外部清除，或出现回退时进入：
     * 用游标 + 一次目录扫描重建下界，保证不漏读也不错序。
     * 触发时会打一条 WARN，便于在日志中确认这是异常而非常态。
     */
    private fun nextEventSequence(queue: String, raf: RandomAccessFile): Long {
        val storedSeq = readCounterValue(raf)
        val processSeq = eventSeqCounter.incrementAndGet()
        val floor = queueSeqFloor.getOrPut(queue) { AtomicLong(0L) }

        if (storedSeq > 0L && storedSeq >= floor.get()) {
            floor.set(storedSeq)
            return maxOf(storedSeq + 1, processSeq)
        }

        val cursorSeq = parseEventSequence(readCursor(queue)) ?: 0L
        val queuedSeq = maxEventSequence(queue)
        val next = maxOf(storedSeq + 1, cursorSeq + 1, queuedSeq + 1, processSeq)
        floor.set(next)
        Log.w(TAG, "event sequence floor rebuilt for $queue: stored=$storedSeq " +
                "cursor=$cursorSeq queued=$queuedSeq -> next=$next")
        return next
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

    /**
     * 事件目录中已存在的最大序号 —— **O(n)**，仅供序号下界重建的慢路径使用。
     *
     * 不要把它放回常规写入路径：它要对目录里每个文件做一次 `stat` 并解析文件名，
     * 实测稳态目录规模上限为 `EVENT_QUEUE_MAX_FILES`，即每写一个事件就要扫描上千个文件。
     * 常规路径见 [nextEventSequence] 的快路径。
     */
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

    /**
     * 队列的**真实待消费深度**：文件名严格大于持久化游标的事件数。
     *
     * `events/<queue>/` 目录里的文件只被游标越过、**从不删除**，所以目录文件数是
     * “累计写入量”，不是积压量。直接拿它当积压量会产生永远为真的告警：
     * 实测 `filesystem=561 / consumed=561` 且 `cursors/filesystem.cursor` 正好等于
     * 目录里最新的 `...000561-*.json`，队列其实已经清空，却每 60s 报一次 backlog。
     *
     * 需要同时拿累计量与积压量时请改调 [queueCounts]，它只遍历一次目录。
     */
    fun pendingEventCount(queue: String): Int {
        if (!isValidEventQueue(queue)) return 0
        return countQueue("$BUS_ROOT/$DIR_EVENTS/$queue", readCursor(queue)).pending
    }

    /**
     * 队列目录的**累计文件数**与**真实积压**，一次遍历同时给出。
     *
     * 这是 `DataBusLayerReporter` 每轮（~2s）都要的两个数：旧实现是
     * `countJsonFiles()` + `pendingEventCount()` 各扫一遍目录（各自还对每个条目做一次
     * `stat`），实测稳态下 `events/filesystem` ~200 条、`events/consumed` ~460 条，
     * 合计每轮多付近千次 `stat`。
     */
    fun queueCounts(queue: String): DataBusProtocol.QueueCounts {
        if (!isValidEventQueue(queue)) return DataBusProtocol.QueueCounts(0, 0)
        return countQueue("$BUS_ROOT/$DIR_EVENTS/$queue", readCursor(queue))
    }

    /**
     * 队列目录累计文件数（含已消费，仅用于诊断展示，不等于积压）。
     */
    fun archivedEventCount(queue: String): Int {
        if (!isValidEventQueue(queue)) return 0
        return countQueue("$BUS_ROOT/$DIR_EVENTS/$queue", cursor = "").archived
    }

    /**
     * 清理队列目录中**游标及之前**的已消费事件文件。
     *
     * 为什么需要：游标机制让文件永不删除，`events/<queue>/` 会随运行时间无界增长
     * （实测 10:52~11:03 的 11 分钟内该目录已有 561 个文件）。每个消费者每轮都会
     * 对该目录做 `listFiles()`（`readEventFiles` / `maxEventSequence`），文件数膨胀
     * 会直接拖慢发布与消费路径。
     *
     * 安全性：只删除 `name <= cursor` 的文件 —— 游标是唯一读取起点，这些条目
     * 不可能再被读出，删除既不会造成重复也不会造成丢失。`consumed/` 归档仍按
     * 自身的 TTL / 上限保留审计副本。
     *
     * @param keepAtMost 低水位：文件数不超过该值时不做任何删除（避免高频 unlink）
     * @return 实际删除的文件数
     */
    fun pruneConsumedEvents(queue: String, keepAtMost: Int): Int {
        if (!isValidEventQueue(queue)) return 0
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!Files.isDirectory(eventDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0

        // 只用 list() 取文件名：listFiles() 会对每个条目做一次 stat，
        // 而上限判定与游标比较都只需要名字。旧实现是"先 listFiles + 逐文件 stat
        // + 全量排序，再判断是否超过低水位"，即在**无需清理**的绝大多数轮次里
        // 也付了 O(n log n) + n 次 stat。
        val names = eventDir.list() ?: return 0
        if (names.isEmpty() || names.size <= keepAtMost) return 0

        val cursor = readCursor(queue)
        if (cursor.isEmpty()) return 0

        // 待删集合 = 文件名 <= cursor（与旧的"排序后 break 到 name > cursor"等价）。
        val consumed = names.filter { it.endsWith(".json") && it <= cursor }
        if (consumed.isEmpty()) return 0

        // 只对待删候选做类型校验（防符号链接/目录），不扫全目录。
        var deleted = 0
        for (name in consumed.sorted()) {
            val file = File(eventDir, name)
            if (isRegularFileNoFollow(file) && file.delete()) deleted++
        }
        return deleted
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

        // 每个队列只遍历一次目录，同时拿到「累计」与「真实积压」。
        // 旧实现每 ~2s 一轮共 6 次目录遍历（本方法内 countJsonFiles ×4 +
        // DataBusLayerReporter 内 pendingEventCount ×2），且每次都逐条 stat；
        // 现在降到 4 次，其中 filesystem / redirect_notice 这 2 次改用 list()
        // （只取文件名）不再 stat。见 [countQueue]。
        val filesystemCounts = queueCounts(DataBusProtocol.EVENT_FILESYSTEM)
        val redirectNoticeCounts = queueCounts(DataBusProtocol.EVENT_REDIRECT_NOTICE)

        return DataBusProtocol.HealthReport(
            initialized = init && missingDirs.isEmpty(),
            missingDirectories = missingDirs,
            permissionIssues = permissionIssues,
            snapshots = DataBusProtocol.snapshotNames().map { inspectSnapshot(it) },
            eventQueueCounts = mapOf(
                DataBusProtocol.EVENT_FILESYSTEM to filesystemCounts.archived,
                DataBusProtocol.EVENT_REDIRECT_NOTICE to redirectNoticeCounts.archived,
                DIR_CONSUMED to countJsonFiles("$BUS_ROOT/$DIR_EVENTS/$DIR_CONSUMED"),
            ),
            pendingEventQueueCounts = mapOf(
                DataBusProtocol.EVENT_FILESYSTEM to filesystemCounts.pending,
                DataBusProtocol.EVENT_REDIRECT_NOTICE to redirectNoticeCounts.pending,
                // consumed/ 是归档、没有游标概念，积压恒为 0。
                DIR_CONSUMED to 0,
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
        "$BUS_ROOT/$DIR_EVENTS/$DIR_CONSUMED",
        "$BUS_ROOT/$DIR_LEASES/${DataBusProtocol.LEASE_QUERY_SESSIONS}",
        "$BUS_ROOT/$DIR_CURSORS",
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

    /**
     * 目录内 `*.json` 的**累计数**（无游标语义的归档目录用：`consumed/`、leases）。
     */
    private fun countJsonFiles(path: String): Int = countQueue(path, cursor = "").archived

    /**
     * 单次目录遍历同时算出「累计数」与「积压数」。
     *
     * **只用 `list()`（仅取文件名）而不是 `listFiles()`**：两个计数都只依赖文件名
     * （积压判据是 `name > cursor`），而 `listFiles()` 会对每个条目做一次 `stat`。
     * 调用方是每 ~2s 跑一轮的 `DataBusLayerReporter`，实测覆盖近千个条目。
     *
     * 取舍说明：旧实现用 `isRegularFileNoFollow` 过滤符号链接/同名目录，本实现不过滤。
     * 计数**仅用于诊断与告警**；事件的读写路径（`readEventFiles` / `pruneConsumedEvents`）
     * 仍各自做类型校验与文件名正则校验，因此 queue 目录里被人为塞入的 `.json`
     * 目录/链接最多让诊断数字偏大，不会影响消费、生产与清理的正确性。
     */
    private fun countQueue(path: String, cursor: String): DataBusProtocol.QueueCounts {
        val dir = File(path)
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return DataBusProtocol.QueueCounts(0, 0)
        }
        val names = dir.list() ?: return DataBusProtocol.QueueCounts(0, 0)
        var archived = 0
        var pending = 0
        for (name in names) {
            if (!name.endsWith(JSON_SUFFIX)) continue
            archived++
            // 游标缺失时 `cursor == ""`，`name > ""` 对所有文件名成立 ⇒ 全部计为积压。
            // 这正是旧实现的行为，也符合语义：没有游标 = 一条都没消费过 = 全部待消费。
            // **不要**为此加 `cursor.isNotEmpty()` 之类的守卫，那会在游标文件丢失时
            // 把积压静默判成 0，等于悄悄关掉 backlog 告警。
            if (name > cursor) pending++
        }
        return DataBusProtocol.QueueCounts(archived, pending)
    }

    /**
     * 设置文件/目录的跨进程可访问权限。
     *
     * **只发一次 `chmod`。** 末尾的 `Os.chmod(path, mode)` 用的是**绝对 mode**，
     * 会把之前任何 `File.setReadable/setWritable/setExecutable` 的结果完全覆盖；
     * 而那些调用每一个内部都要 `stat` + `chmod`（2 次系统调用），在绝对 chmod 之下
     * 属纯冗余。本函数在每次事件写入路径上被调用两次（计数器文件 + 事件文件），
     * 去掉冗余调用可让**每个事件少 6~8 次系统调用**。
     *
     * 唯一的语义差异：`Os.chmod` 失败时旧实现还残留 setXxx 的部分效果作为兜底。
     * 该路径仅在非 root 进程调用时出现（此时 setXxx 同样会失败），
     * 失败仍会打 WARN，行为可观测。
     */
    private fun makeWorldAccessible(file: File, executable: Boolean, writable: Boolean = true) {
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
