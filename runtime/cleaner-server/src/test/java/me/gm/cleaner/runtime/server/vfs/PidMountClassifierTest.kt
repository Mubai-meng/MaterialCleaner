package me.gm.cleaner.runtime.server.vfs

import me.gm.cleaner.model.PackageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PidMountClassifier 纯函数单测：覆盖 P1-A 六场景与组合合法性。
 */
class PidMountClassifierTest {

    @Test
    fun `无目标判未接管`() {
        assertEquals(
            PackageStatus.PID_FLAG_UNMANAGED,
            PidMountClassifier.classify(0, intArrayOf()),
        )
    }

    @Test
    fun `采样失败判未知`() {
        assertEquals(
            PackageStatus.PID_FLAG_UNKNOWN,
            PidMountClassifier.classify(3, null),
        )
    }

    @Test
    fun `全命中判已挂载`() {
        assertEquals(
            PackageStatus.PID_FLAG_MOUNTED,
            PidMountClassifier.classify(2, intArrayOf(0, 1)),
        )
    }

    @Test
    fun `零命中判未挂载`() {
        assertEquals(
            PackageStatus.PID_FLAG_NOT_MOUNTED,
            PidMountClassifier.classify(2, intArrayOf()),
        )
    }

    @Test
    fun `部分命中判部分挂载`() {
        assertEquals(
            PackageStatus.PID_FLAG_PARTIALLY_MOUNTED,
            PidMountClassifier.classify(3, intArrayOf(0)),
        )
    }

    @Test
    fun `负值判删除或覆盖`() {
        assertEquals(
            PackageStatus.PID_FLAG_DELETED,
            PidMountClassifier.classify(2, intArrayOf(-1, 0)),
        )
        assertEquals(
            PackageStatus.PID_FLAG_OVERRIDE,
            PidMountClassifier.classify(2, intArrayOf(-2)),
        )
        assertEquals(
            PackageStatus.PID_FLAG_DELETED or PackageStatus.PID_FLAG_OVERRIDE,
            PidMountClassifier.classify(2, intArrayOf(-1, -2)),
        )
    }

    @Test
    fun `合法组合`() {
        assertTrue(PidMountClassifier.isLegalCombination(PackageStatus.PID_FLAG_MOUNTED))
        assertTrue(PidMountClassifier.isLegalCombination(PackageStatus.PID_FLAG_PARTIALLY_MOUNTED))
        assertTrue(PidMountClassifier.isLegalCombination(PackageStatus.PID_FLAG_NOT_MOUNTED))
        assertTrue(PidMountClassifier.isLegalCombination(PackageStatus.PID_FLAG_UNMANAGED))
        assertTrue(PidMountClassifier.isLegalCombination(PackageStatus.PID_FLAG_UNKNOWN))
        assertTrue(
            PidMountClassifier.isLegalCombination(
                PackageStatus.PID_FLAG_DELETED or PackageStatus.PID_FLAG_OVERRIDE,
            ),
        )
        // 正交证据位不影响合法性。
        assertTrue(
            PidMountClassifier.isLegalCombination(
                PackageStatus.PID_FLAG_MOUNTED or PackageStatus.PID_FLAG_MOUNT_FAILED,
            ),
        )
    }

    @Test
    fun `非法组合`() {
        assertFalse(
            PidMountClassifier.isLegalCombination(
                PackageStatus.PID_FLAG_MOUNTED or PackageStatus.PID_FLAG_PARTIALLY_MOUNTED,
            ),
        )
        assertFalse(
            PidMountClassifier.isLegalCombination(
                PackageStatus.PID_FLAG_UNMANAGED or PackageStatus.PID_FLAG_MOUNTED,
            ),
        )
        assertFalse(
            PidMountClassifier.isLegalCombination(
                PackageStatus.PID_FLAG_UNKNOWN or PackageStatus.PID_FLAG_NOT_MOUNTED,
            ),
        )
    }
}
