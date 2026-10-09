package me.gm.cleaner.runtime.server.process

import java.io.File

/**
 * 进程实例身份（P1-C/Fix 2）。
 *
 * PID 可被系统复用，`/proc/<pid>/stat` 第 22 字段 starttime 在复用后必然变化。
 * 与 native `read_process_start_time`（Mount.cpp）同源，Java 侧纯读实现，
 * 专供恢复策略识别“同一进程实例最多一次破坏性恢复”。
 */
object ProcessIdentity {
    data class Instance(val pid: Int, val startTime: Long)

    /** 读取指定 PID 当前实例身份；进程不存在/不可读返回 null。 */
    fun currentInstance(pid: Int): Instance? {
        if (pid <= 0) return null
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull()
            ?: return null
        val startTime = parseStartTime(stat) ?: return null
        return Instance(pid, startTime)
    }

    /**
     * 解析 stat 第 22 字段 starttime。comm 可含空格与括号，
     * 从最后一个 ')' 之后按空格切分，第 20 个 token（state=第3字段起算第20个）。
     */
    fun parseStartTime(stat: String): Long? {
        val cursor = stat.lastIndexOf(')')
        if (cursor < 0 || cursor + 1 >= stat.length || stat[cursor + 1] != ' ') return null
        // ')' 之后依次为：state(3) ppid(4) pgrp(5) session(6) tty_nr(7) tpgid(8)
        // flags(9) minflt(10) cminflt(11) majflt(12) cmajflt(13) utime(14)
        // stime(15) cutime(16) cstime(17) priority(18) nice(19) num_threads(20)
        // itrealvalue(21) starttime(22) → 第 20 个 token。
        val tokens = stat.substring(cursor + 2).split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 20) return null
        return tokens[19].toLongOrNull()?.takeIf { it > 0 }
    }
}
