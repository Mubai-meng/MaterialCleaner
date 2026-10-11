package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * DiagnosticCommandRunner host 单测：只覆盖无设备依赖的部分。
 *
 * 前置确认：
 * - Runner 本体不依赖 android.util.Log（仅 java.io/nio/concurrent），host 可直接加载。
 * - 但 runCommand 硬编码 /system/bin/sh，host（Windows/Linux）无此路径，
 *   真实进程路径（超时 kill、阻塞 read 的 close 解阻、超限排空截断）需设备验收，
 *   本文件不断言上述 IO 行为，仅锁定默认参数与 host 降级不抛异常。
 */
class DiagnosticCommandRunnerTest {

    @Test
    fun `默认上限与超时保持不变`() {
        assertEquals(4 * 1024 * 1024, DiagnosticCommandRunner.MAX_COMMAND_BYTES)
        assertEquals(15L, DiagnosticCommandRunner.COMMAND_TIMEOUT_SECONDS)
    }

    @Test
    fun `host无sh时降级返回而不抛异常`() {
        // host 上 /system/bin/sh 不存在 → catch 分支返回，不抛、不超时、不截断。
        // （若在设备上运行，echo 正常结束，同样 timedOut/truncated/readError 均为 false。）
        val result = DiagnosticCommandRunner.runCommand("echo hi")
        assertFalse(result.timedOut)
        assertFalse(result.truncated)
        assertFalse(result.readError)
    }

    @Test
    fun `小值重载在host同样不抛异常`() {
        val result = DiagnosticCommandRunner.runCommand("echo hi", timeoutSeconds = 2, maxBytes = 1024)
        assertFalse(result.timedOut)
        assertFalse(result.truncated)
        assertFalse(result.readError)
    }
}
