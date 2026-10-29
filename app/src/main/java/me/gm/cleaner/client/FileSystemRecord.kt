package me.gm.cleaner.client

import android.os.FileObserver
import androidx.fragment.app.Fragment
import me.gm.cleaner.R

/**
 * 「这条记录来自媒体库写入（MediaStore.insert）」的 flags 标志位。
 *
 * 与 `InsertHooker.FLAG_MEDIA_INSERT` **必须保持一致**（0x20000000）。
 * 写入侧会同时叠加 [FileObserver.CREATE]，因此即使这个位被清掉，
 * 记录仍会退化成普通的「创建文件」而不是渲染失败。
 */
private const val FLAG_MEDIA_INSERT = 0x20000000

fun getPathWithEvent(path: String, flags: Int, fragment: Fragment): CharSequence {
    val eventStrId = when {
        // 必须排在 CREATE 之前：媒体库写入同时带着 CREATE 位，
        // 放后面就永远显示成普通的「创建文件」，看不出写入通道。
        hasFlag(flags, FLAG_MEDIA_INSERT) -> R.string.filesystem_record_flags_media_insert
        hasFlag(flags, FileObserver.CREATE) -> R.string.filesystem_record_flags_create
        hasFlag(flags, FileObserver.OPEN) &&
                !hasFlag(flags, FileObserver.CREATE) -> R.string.filesystem_record_flags_open
        hasFlag(flags, FileObserver.DELETE) -> R.string.filesystem_record_flags_delete
        hasFlag(flags, FileObserver.MOVED_FROM) -> R.string.filesystem_record_flags_move_from
        hasFlag(flags, FileObserver.MOVED_TO) -> R.string.filesystem_record_flags_move_to
        else -> throw IllegalArgumentException()
    }
    val objectStrId = if (hasFlag(flags, 0x40000000)) R.string.filesystem_record_flags_dir
    else R.string.filesystem_record_flags_file
    return fragment.getString(eventStrId, fragment.getString(objectStrId), path)
}

private fun hasFlag(flags: Int, mask: Int): Boolean = flags and mask != 0
