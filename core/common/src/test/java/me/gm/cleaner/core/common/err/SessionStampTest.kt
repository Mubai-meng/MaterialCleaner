package me.gm.cleaner.core.common.err

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住会话标识的三条契约（5.5.16，第 48 批 §3.3.1）。
 *
 * 背景：持久层 `journal.jsonl` 60 行里有 13 处 `atElapsed` 递减 ⇒ 至少 14 次
 * 开机混写。`atElapsed` 每次重启归零，天然不能回答「这条属于哪次开机」，
 * 于是「52 次 watchdog」到底是单会话还是 14 会话累加，从归档里无法判定。
 */
class SessionStampTest {

    /** 第 48 批实测的锚点：最早 audit 行 `audit(1792843506.051:194)`、uptime 88.094927 s。 */
    private val batch48Wall = 1_792_843_506_051L
    private val batch48Elapsed = 88_095L
    private val batch48Boot = 1_792_843_417_956L

    @Test
    fun `boot 起点等于墙钟减开机后毫秒`() {
        // 与分析脚本同一口径（boot_ts = audit_ts − uptime），否则归档里的
        // sessionId 和分析侧算出的 boot_ts 无法对齐，等于白加一个字段。
        assertEquals(
            batch48Boot,
            SessionStamp.bootWallMillis(batch48Wall, batch48Elapsed),
        )
    }

    @Test
    fun `会话标识带 b 前缀且能反解回 boot 起点`() {
        val id = SessionStamp.sessionIdOf(batch48Boot)
        assertEquals("b1792843417956", id)
        assertEquals(batch48Boot, id.substring(1).toLong())
    }

    @Test
    fun `同一 boot 内任意两个时刻得到同一会话`() {
        // 这是本字段存在的全部意义：跨重启可分组，同一 boot 内稳定。
        val a = SessionStamp.bootWallMillis(batch48Boot + 1_000L, 1_000L)
        val b = SessionStamp.bootWallMillis(batch48Boot + 183_900L, 183_900L)
        assertEquals(a, b)
    }

    @Test
    fun `不同 boot 必然得到不同会话`() {
        // 第 48 批的相邻两次会话相差约 6 小时，必须区分开。
        val first = SessionStamp.bootWallMillis(batch48Wall, batch48Elapsed)
        val second = SessionStamp.bootWallMillis(batch48Wall + 21_500_000L, 95_000L)
        assertTrue(SessionStamp.sessionIdOf(first) != SessionStamp.sessionIdOf(second))
    }

    @Test
    fun `平台桩不可用时两个取值方法都不抛`() {
        // 关键护栏：ErrorJournal 在**构造时**就会调用这两个。若它们抛异常，
        // journal 初始化失败，整条错误留痕链断掉 —— 比丢两个字段严重得多。
        // JVM 单测里 android.os.* 是抛 "Stub!" 的桩，正好覆盖这条降级路径。
        SessionStamp.currentPid()
        SessionStamp.currentSessionId()
    }

    @Test
    fun `无法确定的会话必须是 null 而不是编号 0`() {
        // 用 0 / 空串兜底会让「标识缺失」和「会话编号真的是 0」不可区分，
        // 排障者会把未知读成一个合法会话。
        val id = SessionStamp.currentSessionId()
        assertTrue(
            "sessionId 只能是 null 或 b 前缀：$id",
            id == null || id.startsWith("b"),
        )
        if (id != null) {
            assertNotNull(id.substring(1).toLongOrNull())
        }
    }
}
