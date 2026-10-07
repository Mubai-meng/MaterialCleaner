package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 [MountRules.meaninglessRulesIndices] 的判据方向。
 *
 * 背景：解释器是**后置优先**（先 indexOfLast 命中，再自该索引向尾部链式改写），
 * 因此"通用重定向 + carve-out（例外）"这种规则集里，通用规则是真正起作用的那条。
 * 曾出现方向写反的实现，把通用规则误判为无意义（UI 置灰 / getAccessiblePlaces 错算），
 * 同时又漏掉真正的死规则。用例把两个方向都钉住。
 */
class MountRulesTest {

    private val sd = "/storage/emulated/0"
    private val pkg = "com.example.app"
    private val dataDir = "$sd/Android/data/$pkg"
    private val obbDir = "$sd/Android/obb/$pkg"
    private val cacheDir = "$dataDir/cache"

    /** 向导 q1（含 q12）输出：通用重定向 + 两条恒等 carve-out —— 三条都有意义。 */
    @Test
    fun `通用重定向加例外规则不应被判为无意义`() {
        val rules = MountRules(
            listOf(
                cacheDir to sd,
                dataDir to dataDir,
                obbDir to obbDir,
            )
        )
        assertEquals(emptyList<Int>(), rules.meaninglessRulesIndices)
    }

    /** 反向证据：被判"无意义"的那条一旦移除，映射就变 —— 它确实有意义。 */
    @Test
    fun `移除通用重定向会改变映射故其并非无意义`() {
        val all = MountRules(listOf(cacheDir to sd, dataDir to dataDir))
        val withoutGeneral = MountRules(listOf(dataDir to dataDir))
        assertEquals("$sd/Download", withoutGeneral.getMountedPath("$sd/Download"))
        assertEquals("$cacheDir/Download", all.getMountedPath("$sd/Download"))
        assertEquals(sd, withoutGeneral.getMountedPath(sd))
        assertEquals(cacheDir, all.getMountedPath(sd))
    }

    /** 真死规则：本规则 target 落在后续规则 target 之内 ⇒ 后置优先下永不命中。 */
    @Test
    fun `target 被后续规则覆盖的规则应判为无意义`() {
        val rules = MountRules(
            listOf(
                "$sd/Download/A" to "$sd/Download",
                "$sd/Download/B" to sd,
            )
        )
        assertEquals(listOf(0), rules.meaninglessRulesIndices)
    }

    /** 纯恒等且没有任何父重定向可被"挖洞" = 空操作。 */
    @Test
    fun `孤立恒等规则应判为无意义`() {
        val rules = MountRules(listOf(dataDir to dataDir))
        assertEquals(listOf(0), rules.meaninglessRulesIndices)
    }

    /** 重复 target：后置者胜出，前者无意义。 */
    @Test
    fun `相同 target 的重复规则前者无意义`() {
        val rules = MountRules(listOf("/a" to sd, "/b" to sd))
        assertEquals(listOf(0), rules.meaninglessRulesIndices)
    }

    /** carve-out 是恒等规则，但它在父重定向下挖了洞，不能算无意义。 */
    @Test
    fun `父重定向下的恒等例外规则有意义`() {
        val rules = MountRules(listOf(cacheDir to sd, dataDir to dataDir))
        assertEquals(emptyList<Int>(), rules.meaninglessRulesIndices)
    }
}
