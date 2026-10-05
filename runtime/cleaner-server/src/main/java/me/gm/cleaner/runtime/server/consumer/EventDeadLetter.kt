package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 事件死信隔离区（dead-letter）。
 *
 * ## 为什么必需（毒丸消息）
 * `FileSystemEventConsumer.pollAndConsume()` / `RedirectNoticeConsumer.pollAndConsume()`
 * 遇到无法处理的事件时会**不推进游标**。若该事件是**确定性**坏数据（解析必然失败），
 * 消费者会每 2 s 重试同一个文件，游标永久卡死：**整条队列停止工作**。
 * 对 filesystem 队列而言就是"新进程启动不再触发 bindMount"，即核心功能失效。
 * 这就是"毒丸消息"（poison pill）—— 一个坏事件让整条队列永久阻塞。
 *
 * ## 隔离为何是安全的（关键论证）
 * 事件文件由 `DataBus.writeEventFile()` 以 **tmp → fsync → rename** 原子落盘，
 * 且 `DataBus.readEventFiles()` 只认 `.json` 后缀（`.tmp` 一律不读）。
 * 因此磁盘上出现的一个 `.json` 事件文件**只可能是完整内容**，
 * 它的解析失败**不可能**是"读到半个文件"这类瞬时原因 —— 重试永远不会成功。
 * 隔离 + 推进游标是唯一正确的处理方式。
 *
 * 反过来，**投递**失败（Room 写入 / Binder 派发）可能是瞬态的，
 * 所以消费者那边做**有限重试**，超过阈值才轮到本类兜底隔离。
 *
 * ## 不变式：本函数一定能"结清"事件
 * [quarantine] **永不返回"失败到需要调用方重试"**，这是由下面三条退路保证的构造性性质，
 * 不是尽力而为。因此"任何单个事件都无法永久阻塞队列"无需依赖任何重试机制。
 *
 * ## 证据保留
 * 正常路径下原文件**原样移动**（不改内容、不删除），并写一份 `<name>.reason`
 * 记录隔离原因。之所以要 sidecar 文件而不是只打日志：ColorOS 的 `logd.flowctrl`
 * 会按进程限流约 300 行（本项目已知问题），关键原因行可能被吃掉。
 */
object EventDeadLetter {
    private const val TAG = "EventDeadLetter"

    /** 每个队列最多保留的隔离**载荷**文件数；`.reason` 附属文件随载荷一起回收。 */
    private const val MAX_PAYLOAD_FILES = 50

    /**
     * 事件结清结果。
     *
     * 两者都表示"该事件已经结清、调用方可以推进游标了"，区别只在证据是否保留。
     */
    enum class Settlement {
        /** 已移入隔离区：原始载荷 + 隔离原因都保留，可事后取证。 */
        QUARANTINED,

        /** 无法移入隔离区，只能丢弃（或留下孤儿文件由 `pruneConsumedEvents` 回收）——证据丢失。 */
        DISCARDED,
    }

    private fun dir(queue: String): File =
        File(DataBus.BUS_ROOT, "events/dead-letter/$queue")

    /**
     * 把一个无法处理的事件文件结清：优先移入隔离区，必要时退化为丢弃。
     *
     * **本函数保证不抛异常、且永不返回"失败到需要调用方重试"** —— 这是设计契约：
     * 调用方是在自己的 `catch` 处理器里调用本函数的；只要本函数抛出去，
     * 调用方就**来不及推进游标**，于是又退化成"同一个毒丸"（本类要根除的形态）。
     * 因此这里做兜底 try/catch，`Throwable` 一律降级为已结清。
     *
     * 三条退路（保证一定能结清）：
     * 1. 文件不存在 / 不是普通文件 → 读侧本就不会返回它，视为已结清
     * 2. 正常路径：`renameTo` 移入 `events/dead-letter/<queue>/`，证据保留
     * 3. rename 失败（建目录失败、权限、意外错误）→ 退化为 `delete()`
     * 4. 连 `delete()` 也失败 → 留在原地；但调用方推进游标后，
     *    `readEventFiles()` 的 `name > cursor` 过滤会让它**永不再被读出**，
     *    最终由 `DataBus.pruneConsumedEvents()` 回收
     *
     * @return 结清方式；调用方**无论返回值如何都应推进游标**
     */
    fun quarantine(
        queue: String,
        fileName: String,
        reason: String,
        error: Throwable?,
    ): Settlement = try {
        quarantineInternal(queue, fileName, reason, error)
    } catch (t: Throwable) {
        if (t is VirtualMachineError || t is ThreadDeath) throw t
        Log.e(
            TAG,
            "quarantine: unexpected failure for $queue/$fileName, treating as settled " +
                    "(cursor will advance; payload may remain but is unreachable)",
            t,
        )
        Settlement.DISCARDED
    }

    private fun quarantineInternal(
        queue: String,
        fileName: String,
        reason: String,
        error: Throwable?,
    ): Settlement {
        val source = File(File(DataBus.BUS_ROOT, "events/$queue"), fileName)
        if (!isRegular(source)) {
            Log.w(TAG, "quarantine: not a regular file, treated as settled: $queue/$fileName")
            return Settlement.DISCARDED
        }

        val targetDir = dir(queue)
        if (targetDir.isDirectory || targetDir.mkdirs()) {
            val target = File(targetDir, fileName)
            if (source.renameTo(target)) {
                writeReason(targetDir, fileName, reason, error)
                prune(targetDir)
                Log.w(TAG, "quarantined $queue/$fileName: $reason")
                return Settlement.QUARANTINED
            }
            Log.e(TAG, "quarantine: rename failed ${source.path} -> ${target.path}")
        } else {
            Log.e(TAG, "quarantine: cannot create ${targetDir.path}")
        }

        // 退路：放弃保留证据，保证队列能前进。
        val deleted = source.delete()
        val disposition = if (deleted) {
            "payload deleted, evidence lost"
        } else {
            "payload left in place; unreachable after cursor advance, " +
                    "will be reaped by pruneConsumedEvents"
        }
        if (error != null) {
            Log.e(TAG, "quarantine: DISCARDED $queue/$fileName ($reason) — $disposition", error)
        } else {
            Log.e(TAG, "quarantine: DISCARDED $queue/$fileName ($reason) — $disposition")
        }
        return Settlement.DISCARDED
    }

    /** 当前隔离的载荷数量（供诊断上报，让"队列卡死"变成可见的"N 条已隔离"）。 */
    fun count(queue: String): Int = try {
        dir(queue).list()?.count { it.endsWith(".json") } ?: 0
    } catch (e: Exception) {
        0
    }

    private fun writeReason(dir: File, fileName: String, reason: String, error: Throwable?) {
        val line = buildString {
            append(System.currentTimeMillis())
            append('\t').append(reason)
            if (error != null) {
                append('\t').append(error.javaClass.name)
                append(": ").append(error.message)
            }
            append('\n')
        }
        try {
            File(dir, "$fileName.reason").writeText(line, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "quarantine: failed to write reason for $fileName", e)
        }
    }

    /** 只保留最新的 [MAX_PAYLOAD_FILES] 个载荷（文件名内含时间戳，字典序即时间序）。 */
    private fun prune(dir: File) {
        val payloads = try {
            dir.list()?.filter { it.endsWith(".json") }?.sorted() ?: return
        } catch (e: Exception) {
            return
        }
        if (payloads.size <= MAX_PAYLOAD_FILES) return
        for (name in payloads.subList(0, payloads.size - MAX_PAYLOAD_FILES)) {
            File(dir, name).delete()
            File(dir, "$name.reason").delete()
        }
    }

    private fun isRegular(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
