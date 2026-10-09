package me.gm.cleaner.runtime.server.consumer

/**
 * 事件消费失败策略（P0-B：Poison/Transient 分离）。
 *
 * 职责：只判定“确定性损坏直接隔离 / 处理失败按预算重试 / 基础设施故障熔断”，
 * 不拥有文件 IO 与游标推进。IO 归 [DataBus]（隔离/计数/游标），分发归各 Consumer。
 *
 * 语义：at-least-once。游标提交失败允许重放；重复投递靠消费端幂等消化。
 */
object EventConsumePolicy {
    /** 同一事件处理失败达此次数后升级为处理毒丸并隔离。 */
    const val MAX_TRANSIENT_ATTEMPTS = 3

    /** 单轮轮询内连续基础设施失败达此次数后暂停升级，避免批量毒化正常事件。 */
    const val INFRA_CONSECUTIVE_THRESHOLD = 3

    enum class TransientDecision {
        /** 保留事件，下轮重试（不隔离）。 */
        RETRY,
        /** 隔离并推进（放弃正常处理，保留证据）。 */
        QUARANTINE,
    }

    /**
     * 处理阶段抛异常后的处置：默认 Transient；达预算且非系统性故障时升级隔离。
     *
     * @param attemptsAfterIncrement 本次失败计入后的累计次数（已持久化+1）
     * @param infraStreak 本轮已连续基础设施失败次数
     */
    fun decideTransient(attemptsAfterIncrement: Int, infraStreak: Int): TransientDecision {
        if (infraStreak >= INFRA_CONSECUTIVE_THRESHOLD) return TransientDecision.RETRY
        return if (attemptsAfterIncrement >= MAX_TRANSIENT_ATTEMPTS) {
            TransientDecision.QUARANTINE
        } else {
            TransientDecision.RETRY
        }
    }

    /**
     * 启发式判断是否为基础设施类暂时故障（Binder/DB/磁盘），用于熔断批量升级。
     *
     * 判定顺序：先沿异常链查类型名（可靠，不依赖消息文本），再查规范化消息
     * （覆盖消息非空但类名无法表达具体原因的包装异常）。不依赖 Android
     * 运行时类型，保证纯 JVM 可测。
     */
    fun isInfrastructureFault(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            val type = cur.javaClass.name.lowercase()
            if (type.contains("deadobject") ||
                type.contains("remoteeexception") ||
                type.contains("transactionfailed") ||
                type.contains("sqlexception") ||
                type.contains("sqlite") ||
                type.contains("ioexception") ||
                type.contains("filenotfound") ||
                type.contains("nospace") ||
                type.contains("enospc") ||
                type.contains("eio") ||
                type.contains("eacces")
            ) {
                return true
            }
            val msg = (cur.message ?: "").lowercase()
            if (msg.contains("deadobject") ||
                msg.contains("binder") ||
                msg.contains("transaction failed") ||
                msg.contains("transactionfailed") ||
                msg.contains("database is locked") ||
                msg.contains("database locked") ||
                msg.contains("sqlite") ||
                msg.contains("disk i/o") ||
                msg.contains("no space") ||
                msg.contains("enospc") ||
                msg.contains("eio") ||
                msg.contains("eacces") ||
                msg.contains("permission denied") ||
                msg.contains("broken pipe")
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }
}
