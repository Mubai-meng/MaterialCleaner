package me.gm.cleaner.runtime.mediaprovider.hook.fuse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuseOpenDecisionTest {
    @Test
    fun `读加只读放行`() {
        assertFalse(shouldDenyOpen(PFD_MODE_READ_ONLY, true))
    }

    @Test
    fun `写加只读拒绝`() {
        assertTrue(shouldDenyOpen(PFD_MODE_WRITE_ONLY, true))
        assertTrue(shouldDenyOpen(PFD_MODE_READ_WRITE, true))
    }

    @Test
    fun `创建修饰加只读拒绝`() {
        assertTrue(shouldDenyOpen(PFD_MODE_READ_ONLY or PFD_MODE_CREATE, true))
        assertTrue(shouldDenyOpen(PFD_MODE_READ_ONLY or PFD_MODE_TRUNCATE, true))
        assertTrue(shouldDenyOpen(PFD_MODE_READ_ONLY or PFD_MODE_APPEND, true))
    }

    @Test
    fun `非只读一律放行`() {
        assertFalse(shouldDenyOpen(PFD_MODE_WRITE_ONLY, false))
        assertFalse(shouldDenyOpen(PFD_MODE_READ_WRITE, false))
    }

    @Test
    fun `未知mode加只读放行`() {
        assertFalse(shouldDenyOpen(null, true))
    }
}
