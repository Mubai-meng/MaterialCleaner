package me.gm.cleaner.core.storage.redirect.databus

import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicLong

/**
 * DataBus 事件发布：序号分配与原子写入。
 *
 * 职责边界：本对象拥有**事件序号计数器与文件落盘**；
 * 队列名校验、目录准备等基础原语复用 [DataBus] 的模块内可见成员。
 *
 * 序号单调性来源（取四者最大值）：
 * 持久化序号、游标序号、队列现有最大序号、进程内序号。
 * 删除已消费事件会降低其中“队列现有最大”一项，但游标序号托底，
 * 因此清理不破坏序号单调递增。
 *
 * 自 [DataBus] 抽离以控制单文件粒度（G2 门禁）。[DataBus.writeEvent]
 * 保留公开委托，跨模块调用方无需改动。
 */
internal object DataBusEventWriter {

    private const val TAG = "DataBusEventWriter"

    // 进程内序号下界；真实事件序号会通过 counters/ 持久化分配。
    private val eventSeqCounter = AtomicLong(0)

    @Synchronized
    fun writeEventLocked(queue: String, eventDir: File, content: String): Long {
        val counterDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_COUNTERS}")
        if (!DataBus.prepareDirectory(counterDir)) return -1L

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
                        DataBus.makeWorldAccessible(counterFile, executable = false, writable = true)
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
        val cursorSeq = parseEventSequence(DataBus.readCursor(queue)) ?: 0L
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
            DataBus.createTempFileIn(eventDir, "$filename-", ".tmp")
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
            DataBus.makeWorldAccessible(targetFile, executable = false, writable = false)
            Log.d(TAG, "Event written: $queue/$filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event to $queue", e)
            tmpFile.delete()
            false
        }
    }

    private fun maxEventSequence(queue: String): Long {
        val eventDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_EVENTS}/$queue")
        if (!eventDir.exists()) return 0L
        return eventDir.listFiles()
            ?.asSequence()
            ?.filter { DataBus.isRegularFileNoFollow(it) && it.name.endsWith(".json") }
            ?.mapNotNull { parseEventSequence(it.name) }
            ?.maxOrNull()
            ?: 0L
    }

    private fun parseEventSequence(name: String): Long? =
        DataBusProtocol.EVENT_FILE_NAME_PATTERN.matchEntire(name)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
}
