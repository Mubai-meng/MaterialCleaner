package me.gm.cleaner.runtime.mediaprovider.hook.fuse

/**
 * libinline.so 在本进程内的可用性与「一次性日志」判定。
 *
 * ## 为什么单独抽一个类
 *
 * 1. **可测**：`System.loadLibrary` 在**同一进程内结果确定** —— 一旦失败，
 *    重试多少次都是同一个 `UnsatisfiedLinkError`。这个"要不要再试"的判定
 *    是纯状态机，与 Android 无关，可以脱离设备单测。
 * 2. **收敛**：把「是否不可达」「原因」「日志是否已打过」三件事收在一处，
 *    避免 adapter 里散落多个 volatile 字段、各处自行判断。
 *
 * ## 现场依据（第33批日志）
 *
 * 用户覆盖安装 5.5.1 后，旧的 MediaProvider 进程仍持有已被删除的旧 base.apk 路径
 * （`/data/app/~~CLq98cFieEB5wU39f2s0Sg==/.../base.apk`），`loadLibrary("inline")`
 * 报 `couldn't find "libinline.so"`。旧实现每次都重新 `loadLibrary`，
 * 收敛期 2 秒一轮的刷新让它连续重试 26 次，每次 21 行 W 堆栈 + 11 行 E 堆栈
 * （两个 tag 各一份），LSPosed 再把同样的栈抄一遍 ⇒ 单次升级刷出 500+ 行噪声，
 * 把真正的错误埋掉。库不可达只有「重启本进程」一条出路。
 */
internal class InlineLibraryAvailability {

    @Volatile
    private var unavailableReason: String? = null

    private var logged = false

    /** true 表示本进程已确定不可达；此后不应再调用 `loadLibrary`。 */
    fun isUnavailable(): Boolean = unavailableReason != null

    /** 规范化后的不可达原因；未标记时为 null。 */
    fun reason(): String? = unavailableReason

    /**
     * 标记本进程内不可达。
     *
     * @return 是否为**首次**标记（true 供调用方决定要不要写状态/日志；
     *         重复标记返回 false，不做任何改动）。
     */
    @Synchronized
    fun markUnavailable(rawReason: String?): Boolean {
        if (unavailableReason != null) {
            return false
        }
        unavailableReason = normalizeReason(rawReason)
        return true
    }

    /**
     * 一次性日志闸门：首次调用返回 true，之后恒 false。
     *
     * 与 [markUnavailable] 分开，是因为「记录状态」和「写日志」的时机不同：
     * 状态每次失败都要更新（上层用它渲染），日志只需一次。
     */
    @Synchronized
    fun consumeLogAllowance(): Boolean {
        if (logged) {
            return false
        }
        logged = true
        return true
    }

    companion object {
        /** 原因文本上限：只为现场辨识，不需要完整异常消息。 */
        const val MAX_REASON_LENGTH = 200

        private const val FALLBACK_REASON = "UnsatisfiedLinkError"

        /**
         * 规范化失败原因：裁到 [MAX_REASON_LENGTH]、空白归一为兜底文案。
         *
         * 绝不返回空串 —— 空原因会让状态页显示"库不可达，原因："这种无信息量的行。
         */
        fun normalizeReason(rawReason: String?): String {
            val trimmed = rawReason?.trim()
            if (trimmed.isNullOrEmpty()) {
                return FALLBACK_REASON
            }
            return if (trimmed.length > MAX_REASON_LENGTH) {
                trimmed.substring(0, MAX_REASON_LENGTH)
            } else {
                trimmed
            }
        }
    }
}
