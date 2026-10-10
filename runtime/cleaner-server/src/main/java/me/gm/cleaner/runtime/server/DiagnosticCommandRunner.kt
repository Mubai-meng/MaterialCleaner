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
    private const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val READER_JOIN_MS = 1_000L

    internal data class CommandResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
        val output: String,
        val readError: Boolean = false,
    )

    fun runCommand(command: String): CommandResult {
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
            val reader = Thread {
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val input = started.inputStream
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() >= MAX_COMMAND_BYTES) {
                            // 已达上限：继续排空丢弃，只保留前 4MB，避免子进程因管道满阻塞。
                            flags.truncated = true
                            continue
                        }
                        if (output.size() + read <= MAX_COMMAND_BYTES) {
                            output.write(buffer, 0, read)
                        } else {
                            val allowed = MAX_COMMAND_BYTES - output.size()
                            if (allowed > 0) output.write(buffer, 0, allowed)
                            flags.truncated = true
                            // 不 break、不 destroy：继续循环排空丢弃剩余输出。
                        }
                    }
                } catch (e: Exception) {
                    flags.readError = true
                }
            }
            reader.start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            reader.join(READER_JOIN_MS)
            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                timedOut = !finished,
                truncated = flags.truncated,
                output = output.toString(StandardCharsets.UTF_8.name()),
                readError = flags.readError,
            )
        } catch (e: Exception) {
            CommandResult(-1, timedOut = false, truncated = false, output = e.stackTraceToString())
        } finally {
            process?.destroy()
        }
    }
}
