package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [PendingEventBuffer] 的三条契约：
 * 1. FIFO 顺序（补写时不能乱序）；
 * 2. 容量满时丢**最旧**、保留最近（诊断价值更高）；
 * 3. writer 失败即停，剩余事件必须留在缓冲里等下次补写。
 */
class PendingEventBufferTest {

    @Test
    fun `drain preserves fifo order`() {
        val buffer = PendingEventBuffer(8)
        buffer.offer("filesystem", "a")
        buffer.offer("filesystem", "b")
        buffer.offer("redirect_notice", "c")

        val out = mutableListOf<String>()
        val n = buffer.drainTo { queue, content ->
            out += "$queue:$content"
            true
        }

        assertEquals(3, n)
        assertEquals(listOf("filesystem:a", "filesystem:b", "redirect_notice:c"), out)
        assertEquals(0, buffer.size())
    }

    @Test
    fun `overflow drops the oldest and keeps the newest`() {
        val buffer = PendingEventBuffer(3)
        buffer.offer("filesystem", "1")
        buffer.offer("filesystem", "2")
        buffer.offer("filesystem", "3")
        assertTrue(buffer.offer("filesystem", "4"))

        val out = mutableListOf<String>()
        buffer.drainTo { _, content ->
            out += content
            true
        }

        assertEquals(listOf("2", "3", "4"), out)
    }

    @Test
    fun `failed writer stops drain and keeps remaining events`() {
        val buffer = PendingEventBuffer(8)
        buffer.offer("filesystem", "a")
        buffer.offer("filesystem", "b")
        buffer.offer("filesystem", "c")

        val out = mutableListOf<String>()
        val n = buffer.drainTo { _, content ->
            if (content == "b") {
                false
            } else {
                out += content
                true
            }
        }

        assertEquals(1, n)
        assertEquals(listOf("a"), out)
        assertEquals(2, buffer.size())

        val rest = mutableListOf<String>()
        buffer.drainTo { _, content ->
            rest += content
            true
        }
        assertEquals(listOf("b", "c"), rest)
    }

    @Test
    fun `empty drain is a no-op`() {
        val buffer = PendingEventBuffer(8)
        assertEquals(0, buffer.drainTo { _, _ -> error("writer must not be called") })
    }
}
