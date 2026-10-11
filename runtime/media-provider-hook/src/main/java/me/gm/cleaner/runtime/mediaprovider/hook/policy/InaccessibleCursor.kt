package me.gm.cleaner.runtime.mediaprovider.hook.policy

import android.database.Cursor
import android.database.CursorWrapper
import android.util.Log

/**
 * 「不可访问的路径」在 **结果集** 上的兜底过滤（[CursorWrapper] 子类）。
 *
 * ## 为什么还要包一层 Cursor
 *
 * [InaccessibleQueryFilter] 把排除条件下推到 `android:query-selection` 由 SQLite 执行，
 * 稳态开销 ≈ 0 —— 但**前提是宿主 MediaProvider 真的采纳这条 selection**。第 21 批实机证伪了
 * 这个前提：`filteredQueries=69`（条件确实写进 queryArgs 了）却仍有 12 次
 * `openWithFuse` 命中 `/storage/emulated/0/DCIM/…` 被拒，也就是**行照样返回给了应用**。
 * ROM 是否采纳 queryArgs 是我们无法从源码判断、只有实机能回答的事，因此必须有一条
 * **不依赖宿主行为**的兜底路径。
 *
 * ## 代价与收敛
 *
 * 包 Cursor 的代价是一次 `O(n)` 扫描 + 一个 `IntArray` 位置映射表。为守住"不额外增加
 * 系统开销"的底线，本类**只在 SQL 下推被证明无效的包上启用**（判定见
 * `QueryHooker#probeFilteredQuery` 的探针：用 `{"_data"}` 投影跑一次过滤查询，
 * 命中数 > 0 即说明下推没被采纳），每包只探一次，结论缓存复用。
 *
 * ## 追加列的隐藏
 *
 * 应用查询时往往不请求 `_data`（豆包只要 `_id`，再用 `content://media/.../media/<id>`
 * 取缩略图），此时结果集里没有这一列、兜底无从判断。故调用方会在 query 前把 `_data`
 * 追加到 projection 末尾（见 `QueryHooker`），本类负责**把它对外隐藏**：
 * 列数减一、`getColumnNames()` 去掉尾部、`getColumnIndex` 对尾列返回 -1。
 * 可见列的下标不受影响（追加在末尾），应用看到的与"没注入"完全一致。
 *
 * ## 位置语义
 *
 * 过滤会改变行数，因此 `getCount()` / `getPosition()` / `isLast()` 等必须整体重映射，
 * 否则 RecyclerView / CursorAdapter 会按原始行数取到空白项。映射表在构造时一次算出，
 * 之后所有 `move*` 都是 O(1) 查表。
 */
class InaccessibleCursor private constructor(
    cursor: Cursor,
    private val roots: List<String>,
    private val tailHiddenColumns: Int,
    private val appSpecificPackage: String? = null,
) : CursorWrapper(cursor) {

    /** 对外可见的列数（= 宿主列数 - 被隐藏的尾列数），换算见 [InaccessibleCursorPlan]。 */
    private val visibleColumns: Int =
        InaccessibleCursorPlan.visibleColumnCount(cursor.columnCount, tailHiddenColumns)

    /** 过滤后位置 → 原始位置的映射表。 */
    private val mapping: IntArray

    /** 当前（过滤后）位置，语义对齐 `AbstractCursor#mPos`。 */
    private var pos = -1

    /** 被剔除的行数，供诊断使用。 */
    val hiddenCount: Int

    init {
        val src = wrappedCursor
        val dataIndex = src.getColumnIndex(DATA_COLUMN)
        val total = src.count
        val tmp = IntArray(total)
        var kept = 0
        val saved = src.position
        for (i in 0 until total) {
            if (!src.moveToPosition(i)) break
            if (!isHidden(src.getString(dataIndex))) {
                tmp[kept++] = i
            }
        }
        // 宿主游标位置必须复原：调用方可能已经 move 过（如 CursorAdapter 的两次遍历）。
        src.moveToPosition(saved)
        mapping = tmp.copyOf(kept)
        hiddenCount = total - kept
    }

    private fun isHidden(data: String?): Boolean =
        InaccessibleQueryFilter.isHidden(roots, data, appSpecificPackage)

    override fun getCount(): Int = mapping.size

    override fun getPosition(): Int = pos

    override fun move(offset: Int): Boolean = moveToPosition(pos + offset)

    override fun moveToPosition(position: Int): Boolean {
        val size = mapping.size
        if (position >= size) {
            pos = size
            return false
        }
        if (position < 0) {
            pos = -1
            return false
        }
        pos = position
        return super.moveToPosition(mapping[position])
    }

    override fun moveToFirst(): Boolean = moveToPosition(0)

    override fun moveToLast(): Boolean = moveToPosition(mapping.size - 1)

    override fun moveToNext(): Boolean = moveToPosition(pos + 1)

    override fun moveToPrevious(): Boolean = moveToPosition(pos - 1)

    override fun isFirst(): Boolean = pos == 0 && mapping.size != 0

    override fun isLast(): Boolean = pos == mapping.size - 1 && mapping.size != 0

    override fun isBeforeFirst(): Boolean = mapping.size == 0 || pos == -1

    override fun isAfterLast(): Boolean = mapping.size == 0 || pos == mapping.size

    // ── 追加列的隐藏：可见列下标不变，只把尾部多出来的列挡住 ──

    override fun getColumnCount(): Int = visibleColumns

    override fun getColumnNames(): Array<String> =
        InaccessibleCursorPlan.visibleColumnNames(super.getColumnNames(), visibleColumns)

    override fun getColumnIndex(columnName: String): Int =
        InaccessibleCursorPlan.visibleColumnIndex(
            super.getColumnIndex(columnName), visibleColumns)

    override fun getColumnIndexOrThrow(columnName: String): Int {
        val i = getColumnIndex(columnName)
        if (i < 0) {
            throw IllegalArgumentException("column '$columnName' does not exist")
        }
        return i
    }

    companion object {
        private const val TAG = "MC_REDIRECT"
        private const val DATA_COLUMN = "_data"

        /**
         * 需要过滤时才包装；任何不满足条件的情况都**原样返回宿主 Cursor**。
         *
         * @return 包装后的 Cursor；无需/无法过滤时返回 [cursor] 本身
         */
        @JvmStatic
        @JvmOverloads
        fun wrapIfNeeded(
            cursor: Cursor?,
            roots: Collection<String>?,
            tailHiddenColumns: Int = 0,
            appSpecificPackage: String? = null,
        ): Cursor? {
            if (cursor == null || cursor.isClosed
                || (roots.isNullOrEmpty() && appSpecificPackage.isNullOrBlank())
            ) {
                return cursor
            }
            val dataIndex = cursor.getColumnIndex(DATA_COLUMN)
            val total = cursor.count
            // _data 列不存在（非 files 表查询，或调用方没注入该列）或行数未知 ⇒ 放弃过滤。
            if (dataIndex < 0 || total < 0) {
                return cursor
            }
            val hide = tailHiddenColumns.coerceIn(0, cursor.columnCount)
            return try {
                val wrapped = InaccessibleCursor(
                    cursor, roots.orEmpty().toList(), hide, appSpecificPackage)
                if (wrapped.hiddenCount == 0) cursor else wrapped
            } catch (t: Throwable) {
                // 兜底的兜底：包装失败绝不能让应用拿不到数据。
                Log.w(TAG, "[InaccessibleCursor] wrap failed, returning raw cursor", t)
                cursor
            }
        }
    }
}
