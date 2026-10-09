package me.gm.cleaner.runtime.server.orchestrator

/**
 * FUSE Native Hook 层状态映射（纯函数）。
 *
 * 职责：把“能力事实 + 平台终态 + 同步裁决”映射为对外层状态，
 * 不读取 DataBus/Binder，可 JVM 直接测试。
 *
 * 优先级固定为三层，自上而下短路：
 * 1. **能力可用性**： Hook 不可用或 native 核心符号缺失时，同步状态无从谈起。
 *    核心符号缺失必须报 UNAVAILABLE，不能被 `!policySynced` 抢先判成 STALE
 *    （历史上 `!policySynced` 在前，导致符号缺失被误报为“配置过期”）。
 * 2. **平台终态**： UNSUPPORTED 与 DISABLED 是能力结论，
 *    不应被 Inline 等待状态或同步细节覆盖。
 * 3. **同步状态**： 只有能力可用才谈得上同步裁决。
 */
internal object NativeHookStateMapper {

    fun map(
        available: Boolean,
        coreAvailable: Boolean,
        inlineState: String,
        syncVerdict: NativeSyncPolicy.Verdict,
        policySynced: Boolean,
        fuseLibraryLoaded: Boolean,
    ): LayerState {
        if (!available) return LayerState.UNAVAILABLE
        if (!coreAvailable) return LayerState.UNAVAILABLE
        if (syncVerdict == NativeSyncPolicy.Verdict.UNSUPPORTED) return LayerState.DEGRADED
        if (inlineState == "DISABLED") return LayerState.DISABLED
        if (inlineState == "FUSE_WAITING" || inlineState == "INLINE_LOADED") {
            return LayerState.RECOVERING
        }
        if (syncVerdict == NativeSyncPolicy.Verdict.CONVERGING) return LayerState.RECOVERING
        if (!policySynced) return LayerState.STALE
        if (inlineState == "HOOK_READY_FULL" || inlineState == "HOOK_READY_CORE") {
            return LayerState.HEALTHY
        }
        if (inlineState == "HOOK_DEGRADED") return LayerState.DEGRADED
        if (fuseLibraryLoaded) return LayerState.UNAVAILABLE
        return LayerState.UNAVAILABLE
    }
}
