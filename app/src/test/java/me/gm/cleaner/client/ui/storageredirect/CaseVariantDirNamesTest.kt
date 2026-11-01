package me.gm.cleaner.client.ui.storageredirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `caseVariantDirNames` 纯 JVM 单测。
 *
 * 被测函数只做字符串比较，不触碰 Android 运行时；`ANDROID_DIR_NAME` 是 private const，
 * 调用的 `caseVariantDirNames` 在 MountWizardKt 里，不会加载 MountWizard 类。
 *
 * ⚠️ 归因订正（2026-11-01）：本用例最初挂的「真机依据」是错的。10-31 相册「能看不能编辑」
 * 与「小写 `pictures` 是不是被保留」**无关** —— 用户未改任何规则、仅重启即恢复，重启前后
 * 策略快照逐字节相同（`redirectRevision=f4a54277`），`fuseRedirect.redirected` 却从 575 降为 0。
 * 真因是那次进程的 MediaStore `_data` 为小写、磁盘扫描视图为大写，MediaProvider 重启重建后自愈。
 * 本函数仍值得保留（厂商变体命名兜底），但**不得**再被当作那次故障的修复项引用。
 */
class CaseVariantDirNamesTest {

    private val imageKnown = listOf("Pictures", "DCIM", "Download")

    @Test
    fun `coloros 相册的小写 pictures 被识别为大小写变体`() {
        val actual = listOf("DCIM", "Pictures", "pictures", "Download", "Android")

        assertEquals(listOf("pictures"), caseVariantDirNames(imageKnown, actual))
    }

    @Test
    fun `应用私有顶层目录不得进入推荐`() {
        // 真机顶层实际存在这些；它们若被推荐，autoAnswer 会把它们一律判成「公共可访问」，
        // q1（根目录重定向）建议大面积失灵。
        val actual = listOf("alipay", "tencent", "rhea-trace", ".UTSystemConfig", "Pictures")

        assertEquals(emptyList<String>(), caseVariantDirNames(imageKnown, actual))
    }

    @Test
    fun `Android 即使大小写不同也不推荐`() {
        assertEquals(emptyList<String>(), caseVariantDirNames(listOf("Android"), listOf("android")))
    }

    @Test
    fun `已存在的标准目录不会重复出现`() {
        val actual = listOf("Pictures", "DCIM", "Download")

        assertEquals(emptyList<String>(), caseVariantDirNames(imageKnown, actual))
    }

    @Test
    fun `结果去重并按字典序`() {
        val actual = listOf("pictures", "PICTURES", "dcim", "download")

        assertEquals(listOf("dcim", "download", "pictures"), caseVariantDirNames(imageKnown, actual))
    }

    @Test
    fun `空输入返回空表且不抛`() {
        assertEquals(emptyList<String>(), caseVariantDirNames(emptyList(), emptyList()))
        assertEquals(emptyList<String>(), caseVariantDirNames(emptyList(), listOf("pictures")))
        assertEquals(emptyList<String>(), caseVariantDirNames(imageKnown, listOf("")))
    }

    @Test
    fun `前缀相同但名字不同的目录不算变体`() {
        // 只认「仅大小写不同」，不能把 Pictures2 / MyPictures 当成同一个目录。
        val actual = listOf("Pictures2", "MyPictures", "pictures_old")

        assertTrue(caseVariantDirNames(imageKnown, actual).isEmpty())
    }
}
