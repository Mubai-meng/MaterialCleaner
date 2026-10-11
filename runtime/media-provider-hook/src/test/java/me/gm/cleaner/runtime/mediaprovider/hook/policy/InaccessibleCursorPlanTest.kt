package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InaccessibleCursorPlanTest {

    @Test
    fun `未注入追加列时列数与宿主一致`() {
        assertEquals(4, InaccessibleCursorPlan.visibleColumnCount(4, 0))
    }

    @Test
    fun `注入一列就少一列`() {
        // 宿主返回 [_id, date_taken, mime, _data]，对外只应看到前 3 列。
        assertEquals(3, InaccessibleCursorPlan.visibleColumnCount(4, 1))
    }

    @Test
    fun `隐藏数超过宿主列数时钳制为0而不是负数`() {
        assertEquals(0, InaccessibleCursorPlan.visibleColumnCount(2, 5))
        assertEquals(0, InaccessibleCursorPlan.visibleColumnCount(0, 1))
    }

    @Test
    fun `负数入参不会算出负列数`() {
        assertEquals(0, InaccessibleCursorPlan.visibleColumnCount(-3, 1))
        assertEquals(3, InaccessibleCursorPlan.visibleColumnCount(3, -1))
    }

    @Test
    fun `可见列下标保持不变`() {
        // 追加在末尾，前面各列的下标必须原样透出，否则应用取错列。
        assertEquals(0, InaccessibleCursorPlan.visibleColumnIndex(0, 3))
        assertEquals(2, InaccessibleCursorPlan.visibleColumnIndex(2, 3))
    }

    @Test
    fun `被隐藏的尾列的列名查不到`() {
        // _data 在宿主里下标 3，可见列只有 0..2 ⇒ 应用 getColumnIndex("_data") 应为 -1，
        // 与"根本没注入"时的行为一致。
        assertEquals(-1, InaccessibleCursorPlan.visibleColumnIndex(3, 3))
        assertEquals(-1, InaccessibleCursorPlan.visibleColumnIndex(4, 3))
    }

    @Test
    fun `宿主就没有的列仍是-1`() {
        assertEquals(-1, InaccessibleCursorPlan.visibleColumnIndex(-1, 3))
    }

    @Test
    fun `列名数组裁掉尾部追加列`() {
        val all = arrayOf("_id", "date_taken", "_data")
        assertEquals(listOf("_id", "date_taken"),
            InaccessibleCursorPlan.visibleColumnNames(all, 2).toList())
    }

    @Test
    fun `无需裁剪时返回原数组本身`() {
        val all = arrayOf("_id", "_data")
        assertSame(all, InaccessibleCursorPlan.visibleColumnNames(all, 2))
        assertSame(all, InaccessibleCursorPlan.visibleColumnNames(all, 5))
    }

    @Test
    fun `裁剪到0列返回空数组`() {
        val all = arrayOf("_id", "_data")
        assertEquals(0, InaccessibleCursorPlan.visibleColumnNames(all, 0).size)
    }
}
