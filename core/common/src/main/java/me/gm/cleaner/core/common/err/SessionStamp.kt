package me.gm.cleaner.core.common.err

/**
 * 会话标识：把「这条流水属于哪一次开机 / 哪个进程」变成可判读的字段。
 *
 * 为什么必须补这一层（第 48 批 §3.3.1）：`client_error_journal.jsonl` 60 行里有
 * **13 处 `atElapsed` 递减**，最小的一跳从 67,596,881 掉到 4,966,331 —— 说明至少
 * **14 次独立开机**混写在同一个文件里。`atElapsed` 取自
 * `SystemClock.elapsedRealtime()`，每次重启归零，所以它天然**不能**回答
 * 「这条属于哪次开机」；`atWallMillis` 能回答，但它只在**新写入**的行上存在，
 * 历史行永远缺失。两者叠加的后果是：这个文件既不能排序，也不能分组，
 * 「52 次 watchdog」到底是 1 次会话还是 14 次会话累加，无法回答。
 *
 * 本类只提供**确定性标识**，不做任何分段与滚动 —— 分段策略属于持久化层
 * （`ClientErrorJournal` / `ServerErrorJournal` 的 `rollIfNeededLocked`）。
 * 分开的理由是标识口径必须唯一：同一进程内恒定，跨进程可比较，且与
 * 分析脚本离线算出的 boot_ts 同源。
 *
 * 所有平台调用都裹在 `runCatching` 里：`core:common` 的单测跑在 JVM 上，
 * `android.os.*` 是抛 `RuntimeException("Stub!")` 的桩。失败时返回
 * `null` / `0`（"不知道"），**绝不用 0 或空串伪装成已知会话** ——
 * 那会让「标识缺失」和「会话编号真的是 0」变得不可区分。
 */
internal object SessionStamp {

    /**
     * 纯函数：开机墙钟起点（毫秒）。
     *
     * 与第 48 批分析侧「`boot_ts = 最早 audit 行 − uptime`」**同一口径**，
     * 因此归档里的 `sessionId` 可以直接和离线脚本算出的 boot_ts 对齐，
     * 不必再维护第二套换算。
     */
    fun bootWallMillis(nowWallMillis: Long, nowElapsedMillis: Long): Long =
        nowWallMillis - nowElapsedMillis

    /**
     * 纯函数：会话标识字符串，形如 `b1792843417956`。
     *
     * 前缀 `b`（boot）让它在 JSON 行里一眼区别于 `code` / `pid`，
     * 也避免被误读成时间戳再做一次换算。
     *
     * 注意：NTP 校正会让同一 boot 内算出的 boot_ts 漂移几十秒（第 43 批实测
     * 40.6 s、第 48 批 15.4 s），所以**同一开机的两次计算可能相差一小段**。
     * 用于「哪次开机」的判定时按小时/天聚类即可；本字段不参与任何超时判断。
     */
    fun sessionIdOf(bootWallMillis: Long): String = "b$bootWallMillis"

    /**
     * 当前进程所属会话。
     *
     * 同一进程内恒定，调用方应**只算一次**再复用（见 [ErrorJournal]）：
     * 每行都重算会把 NTP 漂移直接写进同一个会话的每一行，反而破坏可用性。
     */
    fun currentSessionId(): String? = runCatching {
        sessionIdOf(
            bootWallMillis(
                System.currentTimeMillis(),
                android.os.SystemClock.elapsedRealtime(),
            ),
        )
    }.getOrNull()

    /** 当前进程 pid；取不到时返回 0，对应 `ErrorEvent.pid` 的"未知"约定。 */
    fun currentPid(): Int = runCatching {
        android.os.Process.myPid()
    }.getOrDefault(0)
}
