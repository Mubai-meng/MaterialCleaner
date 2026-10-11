package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * [InaccessibleCursor] 的**列视图换算**（纯函数，无 Android 依赖，可直接单测）。
 *
 * ## 为什么单独抽出来
 *
 * `Cursor` / `CursorWrapper` 是 Android 类，本模块只有 junit（没有 Robolectric），
 * unit test 里实例化会抛 Stub!，所以包装类本身无法在单测里跑。把可判定的换算
 * 部分抽到这里，`InaccessibleCursor` 只做转发，新逻辑才有可执行验证。
 *
 * ## 语义
 *
 * 兜底需要 `_data` 判行，但应用往往不请求这一列，故调用方会把它追加到 projection
 * **末尾**。追加列对外必须消失，且**不能改变可见列的下标**（追加在末尾天然满足）。
 */
object InaccessibleCursorPlan {

    /**
     * 对外可见的列数 = 宿主列数 - 隐藏的尾列数。
     *
     * 两个入参都可能越界（宿主返回的列数少于预期、调用方传了过大的隐藏数），
     * 一律钳制到合法区间，绝不允许算出负数或超过宿主列数。
     */
    fun visibleColumnCount(hostColumnCount: Int, tailHiddenColumns: Int): Int {
        val host = hostColumnCount.coerceAtLeast(0)
        val hidden = tailHiddenColumns.coerceIn(0, host)
        return host - hidden
    }

    /**
     * 宿主列下标 → 对外可见下标；落在被隐藏的尾列上（或宿主本就没有该列）返回 -1。
     */
    fun visibleColumnIndex(hostIndex: Int, visibleColumns: Int): Int =
        if (hostIndex >= visibleColumns) -1 else hostIndex

    /**
     * 列名数组裁剪到 [visibleColumns]；无需裁剪时返回原数组（避免无谓拷贝）。
     */
    fun visibleColumnNames(all: Array<String>, visibleColumns: Int): Array<String> {
        val n = visibleColumns.coerceAtLeast(0)
        // copyOf(newSize) 的签名是 Array<T?>（尾部可能补 null），用 copyOfRange 保住非空类型。
        return if (n >= all.size) all else all.copyOfRange(0, n)
    }
}
