package me.gm.cleaner.runtime.server.vfs.mount

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `errno=2` 归因的判定表：四类结论 + 非 ENOENT 静默 + 探测不可信时 unknown。
 *
 * 真机来源（6.0.0 / PLK110）：`com.coloros.gallery3d` 的规则
 * `.../gallery3d/cache → /storage/emulated/0/oua_classifier` 每次挂载都记
 * `stage=mount_rule phase=applying_rules errno=2 index=0`，而旧证据里只有
 * `os=No such file or directory`，无法判断缺的是 source 还是 target。
 *
 * 2026-11-02 补充（诊断包 A）：`File.exists()` 把 stat 失败吞成 false，FUSE 断连时
 * 两侧都"缺失" ⇒ 报出 `missing=source+target`；而同一窗口 21ms 后就出现
 * errno=107(ENOTCONN)。因此探测改为三态，读不到一律 `missing=unknown`。
 */
class MissingPathAttributionTest {

    private val allPresent: (String) -> PathProbe = { PathProbe.PRESENT }
    private val allAbsent: (String) -> PathProbe = { PathProbe.ABSENT }

    @Test
    fun `两侧都在时归因为 none 指向挂载事务本身`() {
        assertEquals(
            "missing=none",
            MissingPathAttribution.describe(
                2, "/mnt/user/0/src", "/storage/emulated/0/t", allPresent,
            ),
        )
    }

    @Test
    fun `仅 source 缺失`() {
        val src = "/mnt/user/0/emulated/0/Android/data/com.coloros.gallery3d/cache"
        assertEquals(
            "missing=source",
            MissingPathAttribution.describe(
                errno = 2,
                source = src,
                target = "/storage/emulated/0/oua_classifier",
                probe = { if (it == src) PathProbe.ABSENT else PathProbe.PRESENT },
            ),
        )
    }

    @Test
    fun `仅 target 缺失`() {
        val target = "/storage/emulated/0/oua_classifier"
        assertEquals(
            "missing=target",
            MissingPathAttribution.describe(
                errno = 2,
                source = "/mnt/user/0/emulated/0/DCIM",
                target = target,
                probe = { if (it == target) PathProbe.ABSENT else PathProbe.PRESENT },
            ),
        )
    }

    @Test
    fun `两侧都缺失`() {
        assertEquals(
            "missing=source+target",
            MissingPathAttribution.describe(2, "/a", "/b", allAbsent),
        )
    }

    @Test
    fun `探测读不到时归因 unknown 而不是谎报缺失`() {
        // FUSE 断连（ENOTCONN）时 stat 全部失败：两侧都"读不到"，不得报 source+target。
        val unreadable: (String) -> PathProbe = { PathProbe.UNKNOWN }
        assertEquals("missing=unknown", MissingPathAttribution.describe(2, "/a", "/b", unreadable))
        // 单侧读不到同样不可信 —— 另一侧"存在"的结论也不成立。
        assertEquals(
            "missing=unknown",
            MissingPathAttribution.describe(
                2, "/a", "/b",
                { if (it == "/a") PathProbe.PRESENT else PathProbe.UNKNOWN },
            ),
        )
    }

    @Test
    fun `非 ENOENT 不产出归因`() {
        assertEquals("", MissingPathAttribution.describe(13, "/a", "/b", allAbsent))
        assertEquals("", MissingPathAttribution.describe(22, "/a", "/b", allAbsent))
        assertEquals("", MissingPathAttribution.describe(0, "/a", "/b", allAbsent))
        // ENOTCONN 本身不是 ENOENT：不归因（它的处置方向完全不同）。
        assertEquals("", MissingPathAttribution.describe(107, "/a", "/b", allAbsent))
    }

    @Test
    fun `native 没回报路径时为 unknown 而不是谎报 none`() {
        assertEquals("missing=unknown", MissingPathAttribution.describe(2, "", "/b", allPresent))
        assertEquals("missing=unknown", MissingPathAttribution.describe(2, "/a", "", allPresent))
    }
}
