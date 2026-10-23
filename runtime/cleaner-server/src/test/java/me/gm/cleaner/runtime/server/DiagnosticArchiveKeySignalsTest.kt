package me.gm.cleaner.runtime.server

import me.gm.cleaner.runtime.server.orchestrator.MediaProviderHookLayerReporter
import me.gm.cleaner.runtime.server.orchestrator.NativeHookLayerReporter
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensus
import me.gm.cleaner.runtime.server.vfs.VfsReportKeys
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断概览陈列逻辑测试：Archive 只陈列 Reporter 结论，不得自行裁决。
 *
 * 历史缺陷：Archive 手写字面量且自行推导 synced/pending，与运行状态分叉，
 * 且字段名与 Reporter 输出不一致导致信号永不显示。本测试用契约常量构造
 * 真实结构的状态 JSON，验证概览如实呈现结论与证据。
 */
class DiagnosticArchiveKeySignalsTest {

    private fun statusJson(
        fuseState: String,
        syncVerdict: String,
        epochConsistent: Boolean,
        policySynced: Boolean,
        mpState: String = "HEALTHY",
        wakeOnly: Boolean = false,
        destructiveRounds: Int = 0,
        managed: Int = 4,
        unmanaged: Int = 2,
        srTotal: Int = 12,
        srTruncated: Boolean = false,
        packagesWithProcesses: Int? = 2,
        configuredPackages: Int? = 7,
    ): JSONObject {
        val epoch = if (epochConsistent) "epoch-a" else "epoch-b"
        return JSONObject().apply {
            put("fuseNativeHook", JSONObject().apply {
                put("state", fuseState)
                put(NativeHookLayerReporter.KEY_SYNC_VERDICT, syncVerdict)
                put(NativeHookLayerReporter.KEY_APPLIED_EPOCH, epoch)
                put(NativeHookLayerReporter.KEY_SNAPSHOT_EPOCH, "epoch-a")
                put(NativeHookLayerReporter.KEY_POLICY_SYNCED, policySynced)
            })
            put("mediaProviderJavaHook", JSONObject().apply {
                put("state", mpState)
                put(MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE, wakeOnly)
                put(MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS, destructiveRounds)
            })
            put("vfs", JSONObject().apply {
                put(VfsProcessCensus.KEY_MANAGED, managed)
                put(VfsProcessCensus.KEY_UNMANAGED, unmanaged)
                put(VfsProcessCensus.KEY_SR_TOTAL, srTotal)
                put(VfsProcessCensus.KEY_SR_TRUNCATED, srTruncated)
                if (packagesWithProcesses != null) {
                    put(VfsProcessCensus.KEY_PACKAGES_WITH_PROCESSES, packagesWithProcesses)
                }
                if (configuredPackages != null) {
                    put(VfsReportKeys.CONFIGURED_PACKAGES, configuredPackages)
                }
            })
        }
    }

    private fun renderEn(status: JSONObject): String =
        with(DiagnosticArchive) { StringBuilder().apply { appendKeySignals(status) } }.toString()

    private fun renderZh(status: JSONObject): String =
        with(DiagnosticArchive) {
            StringBuilder().apply { appendKeySignalsZhCn(status) }
        }.toString()

    @Test
    fun `如实陈列Reporter的STALE结论`() {
        val out = renderEn(statusJson(
            fuseState = "STALE", syncVerdict = "STALE",
            epochConsistent = false, policySynced = false,
        ))
        assertTrue(out.contains("state=STALE"))
        assertTrue(out.contains("sync=STALE"))
        assertTrue(out.contains("epoch=MISMATCH"))
        assertTrue(out.contains("policySynced=false"))
    }

    @Test
    fun `健康时陈列SYNCED`() {
        val out = renderEn(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
        ))
        assertTrue(out.contains("sync=SYNCED"))
        assertTrue(out.contains("epoch=consistent"))
        assertTrue(out.contains("policySynced=true"))
        // 不得出现自造判定词
        assertFalse(out.contains("SYNC PENDING"))
        assertFalse(out.contains("epoch consistent,"))
    }

    @Test
    fun `概览不得自行推导synced结论`() {
        // epoch 一致但 policySynced=false：旧实现会显示 "epoch consistent"，
        // 新实现只陈列 Reporter 结论，不得出现自造判定。
        val out = renderEn(statusJson(
            fuseState = "STALE", syncVerdict = "STALE",
            epochConsistent = true, policySynced = false,
        ))
        assertTrue(out.contains("sync=STALE"))
        assertTrue(out.contains("epoch=consistent"))
        assertTrue(out.contains("policySynced=false"))
        // 旧实现的自造判定词不得出现
        assertFalse(out.contains("SYNC PENDING"))
        assertFalse(out.contains("epoch consistent,"))
    }

    @Test
    fun `熔断状态如实呈现`() {
        val out = renderEn(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
            mpState = "RECOVERING", wakeOnly = true, destructiveRounds = 3,
        ))
        assertTrue(out.contains("wakeOnly=true"))
        assertTrue(out.contains("destructiveAttempts=3/3"))
    }

    @Test
    fun `未熔断时不展示恢复行`() {
        val out = renderEn(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
        ))
        assertFalse(out.contains("MediaProvider recovery"))
    }

    @Test
    fun `VFS分母与srStatus截断如实呈现`() {
        val out = renderEn(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
            managed = 5, unmanaged = 3, srTotal = 40, srTruncated = true,
        ))
        assertTrue(out.contains("managed=5"))
        assertTrue(out.contains("unmanaged=3"))
        assertTrue(out.contains("total=40"))
        assertTrue(out.contains("truncated=true"))
    }

    @Test
    fun `中文概览同样只陈列`() {
        val out = renderZh(statusJson(
            fuseState = "RECOVERING", syncVerdict = "CONVERGING",
            epochConsistent = false, policySynced = false,
            wakeOnly = false, destructiveRounds = 1,
        ))
        assertTrue(out.contains("状态=RECOVERING"))
        assertTrue(out.contains("同步=CONVERGING"))
        assertTrue(out.contains("代次=不一致"))
        assertTrue(out.contains("破坏性尝试=1/3"))
        assertFalse(out.contains("已同步"))
    }

    @Test
    fun `缺少层次时安全返回`() {
        val empty = JSONObject()
        assertEquals("", renderEn(empty))
        assertEquals("", renderZh(empty))
    }

    // ── 第 40 批 P3-2：managed=0 的可解释性 ──────────────────────────────────

    /**
     * 第 39/40 批真实形态：目标应用一个都没启动 ⇒ managed=0、observ 全是非目标进程。
     * 此时概览必须同时给出「有进程在跑的目标包数 0/7」并注明「无样本」，否则
     * `managed=0, unmanaged=123` 会被读成「123 个进程失去接管」。
     */
    @Test
    fun `无目标进程时明确标注非判据失灵`() {
        val out = renderEn(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
            managed = 0, unmanaged = 123,
            packagesWithProcesses = 0, configuredPackages = 7,
        ))
        assertTrue(out.contains("managed=0"))
        assertTrue(out.contains("targetsRunning=0/7"))
        assertTrue(out.contains("no target process running"))
        assertTrue(out.contains("NOT a verdict failure"))
    }

    @Test
    fun `中文概览同样标注无样本`() {
        val out = renderZh(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
            managed = 0, unmanaged = 125,
            packagesWithProcesses = 0, configuredPackages = 7,
        ))
        assertTrue(out.contains("有进程在跑=0/7"))
        assertTrue(out.contains("目标应用均未运行"))
        assertTrue(out.contains("不等于判据失灵"))
    }

    /** 有目标进程时必须真实透出，且不得出现「无样本」的免责话术。 */
    @Test
    fun `有目标进程时不出现无样本标注`() {
        val out = renderEn(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
            managed = 5, unmanaged = 120,
            packagesWithProcesses = 1, configuredPackages = 7,
        ))
        assertTrue(out.contains("managed=5"))
        assertTrue(out.contains("targetsRunning=1/7"))
        assertFalse(out.contains("no target process running"))
    }

    /** 缺新键时退回旧格式，不得崩、不得凭空断言「无样本」。 */
    @Test
    fun `缺少新键时退回旧格式`() {
        val out = renderEn(statusJson(
            fuseState = "HEALTHY", syncVerdict = "SYNCED",
            epochConsistent = true, policySynced = true,
            managed = 0, unmanaged = 10,
            packagesWithProcesses = null, configuredPackages = null,
        ))
        assertTrue(out.contains("managed=0, unmanaged=10"))
        assertFalse(out.contains("targetsRunning="))
        assertFalse(out.contains("no target process running"))
    }

    private fun assertEquals(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
