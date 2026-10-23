package me.gm.cleaner.runtime.server.orchestrator

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * server 启动编排的分段耗时（进程内事实，供诊断快照读取）。
 *
 * ## 为什么必须有这份内存事实
 * [me.gm.cleaner.runtime.server.LayerOrchestrator.initialize] 结尾原本只有一行
 * `Log.i("initialize done in …ms (gateway=…, observers=…, …)")` 汇总九段耗时。
 * 但 ColorOS 17 的 `LOG_FLOWCTRL` 会**按进程限额丢弃日志**，实测第 41、42 两批
 * 全包检索该行命中 **0 次**（`cleaner.log` + 两份 boot log + `logcat_threadtime_recent.txt`
 * 均无），而函数确实已跑完 —— 后续步骤（`started=5/5`、`auto logging started`、
 * `DataBus initialized at`、事件游标推进）全都有独立证据。
 *
 * 于是"启动花了多久、卡在哪一步"这个问题在实机上**永远无法回答**，而它直接决定
 * 兼容层多久从 `RECOVERING` 转 `HEALTHY`（第 41 批实测 initialize start 到首份
 * redirect_policy 快照相隔 33 秒，且同期丢了 2700+ 行日志，完全无法定位）。
 *
 * ## 为什么不用文件而用内存
 * 写盘会再次依赖 DataBus 的原子写/fsync 路径，且在 `publishAll` **之前**执行会
 * 引入顺序依赖。这里只保留一个不可变快照对象，由 `RuntimeStatusAggregator`
 * 在渲染 `orchestrated_status.json` 与诊断包时读取 —— 诊断包走 `collectStatusJson()`
 * 的**实时内存值**，不受日志落盘是否成功影响。
 *
 * ## 并发语义
 * `initialize()` 可能被多次调用（server 重建 / Hook 重连路径），因此：
 * - [attempts] 累计调用次数，便于识别"这行数值属于第几次启动"；
 * - [snapshot] 只保留**最后一次**完成的记录，覆盖写入用 CAS 保证不会读到半更新状态。
 */
object StartupTiming {

    /** 编排分段名（与 `initialize done in …` 日志里的键**同名**，便于交叉核对）。 */
    const val KEY_GATEWAY = "gateway"
    const val KEY_OBSERVERS = "observers"
    const val KEY_PACKAGE_RECEIVER = "pkgReceiver"
    const val KEY_AUTO_LOGGING = "autoLogging"
    const val KEY_PUBLISH_ALL = "publishAll"
    const val KEY_REMOUNT = "remount"
    const val KEY_HOOK_REFRESH = "hookRefresh"
    const val KEY_BINDER = "binder"
    const val KEY_SCHEDULER = "scheduler"

    /**
     * 九段耗时的固定顺序。
     *
     * 刻意用 List 而非 Map：JSON 里键序稳定，跨批次可比；且缺段时能显式补 `-1`
     * 而不是静默少一个键（少键会被读成"该段不存在"，与"该段没耗时"混淆）。
     */
    val SEGMENT_ORDER: List<String> = listOf(
        KEY_GATEWAY,
        KEY_OBSERVERS,
        KEY_PACKAGE_RECEIVER,
        KEY_AUTO_LOGGING,
        KEY_PUBLISH_ALL,
        KEY_REMOUNT,
        KEY_HOOK_REFRESH,
        KEY_BINDER,
        KEY_SCHEDULER,
    )

    /** 一次 initialize 的完整耗时记录。 */
    data class Snapshot(
        val attempt: Int,
        val startedAtWallMillis: Long,
        val completedAtWallMillis: Long,
        val elapsedMs: Long,
        /** 九段耗时，毫秒；键集合见 [SEGMENT_ORDER]。 */
        val segmentsMs: Map<String, Long>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("attempt", attempt)
            put("startedAtWallMillis", startedAtWallMillis)
            put("completedAtWallMillis", completedAtWallMillis)
            put("elapsedMs", elapsedMs)
            put("segmentsMs", JSONObject().apply {
                for (name in SEGMENT_ORDER) {
                    put(name, segmentsMs[name] ?: -1L)
                }
            })
        }
    }

    private val attempts = AtomicInteger(0)
    private val current = AtomicReference<Snapshot?>(null)

    /**
     * 记录一次完成的 initialize。
     *
     * 调用方应保证 [segmentsMs] 覆盖 [SEGMENT_ORDER] 全部键；缺失键在序列化时写成
     * `-1`（明确表示"未采集"），不用 0 冒充"零耗时"。
     */
    fun record(
        startedAtWallMillis: Long,
        elapsedMs: Long,
        segmentsMs: Map<String, Long>,
    ) {
        val attempt = attempts.incrementAndGet()
        current.set(
            Snapshot(
                attempt = attempt,
                startedAtWallMillis = startedAtWallMillis,
                completedAtWallMillis = System.currentTimeMillis(),
                elapsedMs = elapsedMs,
                segmentsMs = LinkedHashMap(segmentsMs),
            ),
        )
    }

    /** 最近一次完成的记录；`null` 表示本次进程尚未完成过 initialize。 */
    fun snapshot(): Snapshot? = current.get()

    /**
     * 供状态快照渲染用：未记录时返回 `null`，调用方**不要**写入空对象 ——
     * 「没有 startupTiming 段」才能被读成"这次启动还没跑完"，写 `{}` 会被读成
     * "跑完了但没耗时"。
     */
    fun toJsonOrNull(): JSONObject? = current.get()?.toJson()

    /** 仅供单测复位；生产路径不调用。 */
    internal fun resetForTest() {
        attempts.set(0)
        current.set(null)
    }
}
