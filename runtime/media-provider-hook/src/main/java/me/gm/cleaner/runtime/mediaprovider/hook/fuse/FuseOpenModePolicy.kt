package me.gm.cleaner.runtime.mediaprovider.hook.fuse

/**
 * PFD modeBits 写判定纯核心。
 *
 * 零 Android / Xposed 依赖，可跑在纯 JVM 单测中；
 * [FuseJavaGate.openOp] 只消费判定结果，不在此解释 open 参数位置。
 */
internal const val PFD_MODE_READ_ONLY = 0x10000000
internal const val PFD_MODE_WRITE_ONLY = 0x20000000
internal const val PFD_MODE_READ_WRITE = 0x30000000
internal const val PFD_MODE_CREATE = 0x08000000
internal const val PFD_MODE_TRUNCATE = 0x04000000
internal const val PFD_MODE_APPEND = 0x02000000

/**
 * PFD modeBits 写判定（ParcelFileDescriptor 语义，非 POSIX O_ACCMODE）。
 *
 * 写访问位或创建/截断/追加修饰位任一命中即判写；未知组合放行由调用方决定。
 */
internal fun isWriteModeBits(modeBits: Int): Boolean {
    val access = modeBits and PFD_MODE_READ_WRITE
    val hasWriteAccess = access == PFD_MODE_WRITE_ONLY || access == PFD_MODE_READ_WRITE
    val hasWriteModifier = (modeBits and (PFD_MODE_CREATE or PFD_MODE_TRUNCATE or PFD_MODE_APPEND)) != 0
    return hasWriteAccess || hasWriteModifier
}
