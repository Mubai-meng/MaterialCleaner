package me.gm.cleaner.runtime.server

import me.gm.cleaner.runtime.server.vfs.VfsProcessCensus
import me.gm.cleaner.runtime.server.vfs.VfsReportKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.5.12：诊断概览里**两组易误读数字**的自证文案。
 *
 * 一、`preparedPackages` 的采样时点。
 * 第 38 / 40 / 44 批连续三次出现「`preparedPackages=0` 配 `vfsMountedPids=N`」被读成
 * 「模块自相矛盾」。两者根本不是同一维度：前者是 `Mounter.mkdirRecords` 的 key 数
 * （目标目录已就绪），后者是普查时刻活着的已挂载 pid 数（30 秒缓存）。目标应用
 * 全部退出后前者实时清零，后者仍是缓存快照。补上时点后不必再靠读者推理。
 *
 * 二、`budget=` 文案的 per-file 语义。
 * 第 44 批把 manifest 里的 `bootTiered(1572864B)` 当成整个 auto 目录的总预算，
 * 实测 `logs/auto/` 3,564,059 B = 声明值 1.70 倍，白查一轮。
 */
class DiagnosticSummaryVfsSamplingTest {

    private fun vfs(
        prepared: Int? = null,
        preparedAt: Long? = null,
        recorded: Int? = null,
        recordedAt: Long? = null,
        mounted: Int? = null,
        censusAt: Long? = null,
    ): JSONObject = JSONObject().apply {
        prepared?.let { put(VfsReportKeys.PREPARED_PACKAGES, it) }
        preparedAt?.let { put(VfsReportKeys.PREPARED_PACKAGES_AT, it) }
        recorded?.let { put(VfsReportKeys.RECORDED_PIDS, it) }
        recordedAt?.let { put(VfsReportKeys.RECORDED_PIDS_AT, it) }
        mounted?.let { put(VfsProcessCensus.KEY_MOUNTED, it) }
        censusAt?.let { put(VfsProcessCensus.KEY_CENSUS_AT, it) }
    }

    private fun render(obj: JSONObject, zh: Boolean): String =
        StringBuilder().also { it.appendVfsSamplingSkew(obj, zh) }.toString()

    @Test
    fun `preparedPackages 与 vfsMountedPids 不同维度时两行都带时点`() {
        val text = render(
            vfs(
                prepared = 0, preparedAt = 1_000L,
                recorded = 0, recordedAt = 1_000L,
                mounted = 6, censusAt = 970_424L,
            ),
            zh = true,
        )
        assertTrue("prepared 行缺失：$text", text.contains("preparedPackages=0 @1000"))
        assertTrue("必须点明不是矛盾：$text", text.contains("不是矛盾"))
        assertTrue("skew 行缺失：$text", text.contains("recordedPids=0 @1000"))
        assertTrue("skew 行必须给差值：$text", text.contains("vfsMountedPids=6 @970424"))
    }

    @Test
    fun `缺 preparedPackagesAt 时不打印该行 也不猜差值`() {
        val text = render(
            vfs(
                prepared = 0,
                recorded = 0, recordedAt = 1_000L,
                mounted = 6, censusAt = 970_424L,
            ),
            zh = true,
        )
        assertFalse("缺时点不得输出 prepared 行：$text", text.contains("preparedPackages=0"))
        // skew 行只依赖 recordedAt / censusAt，仍应正常输出
        assertTrue("skew 行不应被牵连：$text", text.contains("recordedPids=0 @1000"))
    }

    @Test
    fun `普查值缺失时 prepared 行仍要输出`() {
        val text = render(
            vfs(prepared = 3, preparedAt = 2_000L, recorded = 1, recordedAt = 2_000L),
            zh = true,
        )
        assertTrue("prepared 行不应依赖 census：$text", text.contains("preparedPackages=3 @2000"))
        // 没有 vfsMountedPids ⇒ 不出 skew 行，但也不能报错
        assertFalse("无普查值时不该编造 skew：$text", text.contains("相差="))
    }

    @Test
    fun `英文概览用不同措辞`() {
        val text = render(
            vfs(prepared = 0, preparedAt = 5_000L, recorded = 0, recordedAt = 5_000L),
            zh = false,
        )
        assertTrue("英文 prepared 行缺失：$text", text.contains("VFS prepared: preparedPackages=0 @5000"))
        assertTrue("英文必须说明维度不同：$text", text.contains("different dimension"))
    }

    @Test
    fun `budget 文案必须标明 per-file 并算出总数上限`() {
        val spec = autoLogBudgetSpec()
        assertTrue("必须含 perFile：$spec", spec.contains("perFile(bootTiered="))
        assertTrue("必须含 boot 单项预算：$spec", spec.contains("bootTiered=1572864B"))
        assertTrue("必须含份数：$spec", spec.contains("maxBootFiles=2"))
        assertTrue(
            "必须直接给出乘积上限（1572864 × 2 = 3145728）：$spec",
            spec.contains("bootTotalMax=3145728B"),
        )
        assertEquals(
            "预算文案是常量拼接，不该含空白外的动态部分",
            spec,
            spec.trim(),
        )
    }
}
