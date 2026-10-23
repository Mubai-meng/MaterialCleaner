package me.gm.cleaner.runtime.mediaprovider.hook.bridge

/**
 * 「DataBus 文件兜底失败」是否应记入 hook_errors 的判定。
 *
 * 背景（第 40 批现场，同一次开机内时间可精确对齐）：
 * - MediaProvider 进程内的写事件发生在 `elapsedRealtime=33.6s`；
 * - server 通过 `setCleanerServerBinder` 注入回调发生在 `35.5s`（`11:36:28.914`）。
 *
 * 也就是说启动期 MediaProvider **必然先于** server 就绪，此时 `callback == null`，
 * 只能走文件兜底；而 MediaProvider（uid 10326）的 SELinux 域写不了
 * `/data/local/tmp/cleaner/bus`，兜底**注定失败**。这属于设计内的启动竞态，
 * 却每开机固定往 `errors/hook_errors.jsonl` 记一条 W，并在诊断概览里顶出一条
 * `[W] HOOK.JAVA.BUS.WRITE_FAILED`，把真信号淹掉。
 *
 * 区分口径：
 * - **从未**拿到过回调 ⇒ 启动竞态，不入库（冷却照做，省掉后续白付的 tmpfs IO）。
 * - **曾经**拿到过、后来退回兜底仍失败 ⇒ 桥接真的断了，必须入库。
 */
internal object FallbackFailurePolicy {

    /**
     * @param everHadCallback 是否曾经成功拿到过 server 注入的回调
     * @return true 表示应调用 `reportBridgeFailure` 入库留痕
     */
    fun shouldReport(everHadCallback: Boolean): Boolean = everHadCallback
}
