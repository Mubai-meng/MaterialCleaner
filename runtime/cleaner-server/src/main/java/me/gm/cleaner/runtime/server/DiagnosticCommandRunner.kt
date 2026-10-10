package me.gm.cleaner.runtime.server

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 诊断命令执行（进程拉起 + 管道排空 + 有界截断）。
 *
 * 职责边界：本对象只负责**单条 shell 命令的安全执行与输出采集**；
 * 归档组装（zip 落盘、摘要、脱敏）归 [DiagnosticArchive]。
 *
 * 自 [DiagnosticArchive] 抽离以控制单文件粒度（G2 门禁）。
 * 排空语义：达到上限后继续读取丢弃，只保留前 4MB，避免子进程因
 * 管道写满阻塞而被误报超时；truncated/readError/timedOut 三标志独立可并存。
 */
internal object DiagnosticCommandRunner {

    internal const val MAX_COMMAND_BYTES = 4 * 1024 * 1024
    internal const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val READER_JOIN_MS = 1_000L

    internal data class CommandResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
        val output: String,
        val readError: Boolean = false,
    )

    internal fun runCommand(
        command: String,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
        maxBytes: Int = MAX_COMMAND_BYTES,
    ): CommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val started = checkNotNull(process)
            val output = ByteArrayOutputStream()
            // 跨线程标志：reader 写入、主线程 join 后读取，用 @Volatile 保证可见性；
            // truncated/readError/timedOut 三标志独立可并存，不用互斥枚举。
            val flags = object {
                @Volatile var truncated = false
                @Volatile var readError = false
            }
            // 输入流外提：收尾时若 reader 仍阻塞在 read，主线程可 close 解阻。
            val input = started.inputStream
            val reader = Thread {
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        synchronized(output) {
                            if (output.size() >= maxBytes) {
                                // 已达上限：继续排空丢弃，只保留前 maxBytes，避免子进程因管道满阻塞。
                                flags.truncated = true
                            } else if (output.size() + read <= maxBytes) {
                                output.write(buffer, 0, read)
                            } else {
                                val allowed = maxBytes - output.size()
                                if (allowed > 0) output.write(buffer, 0, allowed)
                                flags.truncated = true
                                // 不 break、不 destroy：继续循环排空丢弃剩余输出。
                            }
                        }
                    }
                } catch (e: Exception) {
                    flags.readError = true
                }
            }
            reader.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            reader.join(READER_JOIN_MS)
            if (reader.isAlive) {
                // reader 仍阻塞在 read：close 流解除阻塞，reader 以 readError 退出；
                // 二次 join 后无论存活与否都按当前快照 toString，不再等待。
                try {
                    input.close()
                } catch (e: Exception) {
                    // 关闭失败只影响 reader 退出时延，收尾继续，不污染 CommandResult。
                }
                reader.join(READER_JOIN_MS)
            }
            val outputText = synchronized(output) {
                output.toString(StandardCharsets.UTF_8.name())
            }
            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                timedOut = !finished,
                truncated = flags.truncated,
                output = outputText,
                readError = flags.readError,
            )
        } catch (e: Exception) {
            CommandResult(-1, timedOut = false, truncated = false, output = e.stackTraceToString())
        } finally {
            process?.destroy()
        }
    }
}
