package me.gm.cleaner.runtime.server.vfs.mount

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `errno=2` 归因的四条分支 + 非 ENOENT 静默 + 缺 path 回报时 unknown。
 *
 * 真机来源（6.0.0 / PLK110）：`com.coloros.gallery3d` 的规则
 * `.../gallery3d/cache → /storage/emulated/0/oua_classifier` 每次挂载都记
 * `stage=mount_rule phase=applying_rules errno=2 index=0`，而旧证据里只有
 * `os=No such file or directory`，无法判断缺的是 source 还是 target。
 */
class MissingPathAttributionTest {

    private val allPresent: (String) -> Boolean = { true }
    private val allMissing: (String) -> Boolean = { false }

    @Test
    fun `两侧都在时归因为 none 指向挂载事务本身`() {
        assertEquals(
            "missing=none",
            MissingPathAttribution.describe(2, "/mnt/user/0/src", "/storage/emulated/0/t", allPresent),
        )
    }

    @Test
    fun `仅 source 缺失`() {
        assertEquals(
            "missing=source",
            MissingPathAttribution.describe(
                errno = 2,
                source = "/mnt/user/0/emulated/0/Android/data/com.coloros.gallery3d/cache",
                target = "/storage/emulated/0/oua_classifier",
                exists = { it != "/mnt/user/0/emulated/0/Android/data/com.coloros.gallery3d/cache" },
            ),
        )
    }

    @Test
    fun `仅 target 缺失`() {
        assertEquals(
            "missing=target",
            MissingPathAttribution.describe(
                errno = 2,
                source = "/mnt/user/0/emulated/0/DCIM",
                target = "/storage/emulated/0/oua_classifier",
                exists = { it != "/storage/emulated/0/oua_classifier" },
            ),
        )
    }

    @Test
    fun `两侧都缺失`() {
        assertEquals(
            "missing=source+target",
            MissingPathAttribution.describe(2, "/a", "/b", allMissing),
        )
    }

    @Test
    fun `非 ENOENT 不产出归因`() {
        assertEquals("", MissingPathAttribution.describe(13, "/a", "/b", allMissing))
        assertEquals("", MissingPathAttribution.describe(22, "/a", "/b", allMissing))
        assertEquals("", MissingPathAttribution.describe(0, "/a", "/b", allMissing))
    }

    @Test
    fun `native 没回报路径时为 unknown 而不是谎报 none`() {
        assertEquals("missing=unknown", MissingPathAttribution.describe(2, "", "/b", allPresent))
        assertEquals("missing=unknown", MissingPathAttribution.describe(2, "/a", "", allPresent))
    }
}
