package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NativeHookStateMapper 组合测试：固定三层优先级，防止分支顺序回退。
 *
 * 历史缺陷：
 * - `!policySynced` 曾排在核心能力检查之前，符号缺失被误报为 STALE；
 * - Inline 等待状态曾排在平台终态之前，UNSUPPORTED 被报成 RECOVERING。
 */
class NativeHookStateMapperTest {

    private fun map(
        available: Boolean = true,
        coreAvailable: Boolean = true,
        inlineState: String = "HOOK_READY_FULL",
        syncVerdict: NativeSyncPolicy.Verdict = NativeSyncPolicy.Verdict.SYNCED,
        policySynced: Boolean = true,
        fuseLibraryLoaded: Boolean = true,
    ) = NativeHookStateMapper.map(
        available, coreAvailable, inlineState, syncVerdict, policySynced, fuseLibraryLoaded,
    )

    // ── 第一层：能力可用性 ──

    @Test
    fun `Hook不可用报UNAVAILABLE`() {
        assertEquals(LayerState.UNAVAILABLE, map(available = false))
    }

    @Test
    fun `核心符号缺失不得报STALE`() {
        // 关键回归：符号缺失时 !policySynced 也为真，能力层必须先短路。
        assertEquals(
            LayerState.UNAVAILABLE,
            map(coreAvailable = false, policySynced = false, inlineState = "HOOK_DEGRADED"),
        )
    }

    // ── 第二层：平台终态 ──

    @Test
    fun `UNSUPPORTED优先于Inline等待`() {
        // 关键回归：平台明确不支持时，Inline 仍在 FUSE_WAITING 也不得报恢复中。
        assertEquals(
            LayerState.DEGRADED,
            map(
                inlineState = "FUSE_WAITING",
                syncVerdict = NativeSyncPolicy.Verdict.UNSUPPORTED,
                policySynced = false,
            ),
        )
    }

    @Test
    fun `UNSUPPORTED优先于InlineLoaded`() {
        assertEquals(
            LayerState.DEGRADED,
            map(
                inlineState = "INLINE_LOADED",
                syncVerdict = NativeSyncPolicy.Verdict.UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `DISABLED是能力终态`() {
        assertEquals(
            LayerState.DISABLED,
            map(inlineState = "DISABLED", syncVerdict = NativeSyncPolicy.Verdict.STALE),
        )
    }

    // ── 第三层：同步状态 ──

    @Test
    fun `Inline等待在无平台终态时报恢复中`() {
        assertEquals(
            LayerState.RECOVERING,
            map(inlineState = "FUSE_WAITING", policySynced = false),
        )
    }

    @Test
    fun `CONVERGING报恢复中`() {
        assertEquals(
            LayerState.RECOVERING,
            map(
                inlineState = "HOOK_READY_CORE",
                syncVerdict = NativeSyncPolicy.Verdict.CONVERGING,
                policySynced = false,
            ),
        )
    }

    @Test
    fun `STALE裁决且能力可用报过期`() {
        assertEquals(
            LayerState.STALE,
            map(
                inlineState = "HOOK_READY_CORE",
                syncVerdict = NativeSyncPolicy.Verdict.STALE,
                policySynced = false,
            ),
        )
    }

    @Test
    fun `符号缺失即使已同步也报UNAVAILABLE`() {
        // 能力层优先于同步层：不能因为 policySynced 就宣称可用。
        assertEquals(LayerState.UNAVAILABLE, map(coreAvailable = false))
    }

    // ── 正常健康路径 ──

    @Test
    fun `FULL就绪且已同步报健康`() {
        assertEquals(LayerState.HEALTHY, map())
    }

    @Test
    fun `CORE就绪且已同步报健康`() {
        assertEquals(LayerState.HEALTHY, map(inlineState = "HOOK_READY_CORE"))
    }

    @Test
    fun `DEGRADED能力已同步报降级`() {
        assertEquals(LayerState.DEGRADED, map(inlineState = "HOOK_DEGRADED"))
    }

    @Test
    fun `库未加载报UNAVAILABLE`() {
        assertEquals(LayerState.UNAVAILABLE, map(inlineState = "NOT_LOADED", fuseLibraryLoaded = false))
    }
}
