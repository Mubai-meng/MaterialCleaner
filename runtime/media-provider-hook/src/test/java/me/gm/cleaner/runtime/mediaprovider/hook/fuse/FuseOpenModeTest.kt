package me.gm.cleaner.runtime.mediaprovider.hook.fuse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuseOpenModeTest {
    @Test
    fun `只读不判写`() {
        assertFalse(isWriteModeBits(PFD_MODE_READ_ONLY))
    }

    @Test
    fun `写独占判写`() {
        assertTrue(isWriteModeBits(PFD_MODE_WRITE_ONLY))
    }

    @Test
    fun `读写判写`() {
        assertTrue(isWriteModeBits(PFD_MODE_READ_WRITE))
    }

    @Test
    fun `创建截断追加判写`() {
        assertTrue(isWriteModeBits(PFD_MODE_READ_ONLY or PFD_MODE_CREATE))
        assertTrue(isWriteModeBits(PFD_MODE_READ_ONLY or PFD_MODE_TRUNCATE))
        assertTrue(isWriteModeBits(PFD_MODE_READ_ONLY or PFD_MODE_APPEND))
    }
}
