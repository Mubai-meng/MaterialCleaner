package me.gm.cleaner.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoragePolicyViewsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun seededStore(): FileConfiguredPolicyStore {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        store.updateRedirect(store.readRedirect().revision) {
            it.replaceRedirectRules(
                listOf("/storage/emulated/0/DCIM" to "/storage/emulated/0/Pictures"),
                listOf("com.example"),
            )
        }
        store.updateReadOnly(store.readReadOnly().revision) {
            it.replaceReadOnlyRules(listOf("/protected"), listOf("com.example"))
        }
        return store
    }

    @Test
    fun `读取视图与旧门面一致`() {
        val store = seededStore()

        assertEquals(setOf("com.example"), store.srPackages)
        assertEquals(1, store.srRulesCount)
        assertEquals(1, store.getPackageSrCount("com.example"))
        assertEquals(0, store.getPackageSrCount("other"))
        assertEquals(
            listOf("/storage/emulated/0/DCIM" to "/storage/emulated/0/Pictures"),
            store.getPackageSrZipped("com.example"),
        )
        assertEquals(
            listOf("/storage/emulated/10/DCIM" to "/storage/emulated/10/Pictures"),
            store.getPackageSrZipped("com.example", 10),
        )
        assertEquals(
            listOf("/storage/emulated/0/DCIM") to listOf("/storage/emulated/0/Pictures"),
            store.getPackageSr("com.example", 0),
        )
        assertEquals(listOf("/protected"), store.getPackageReadOnly("com.example"))
        assertEquals(listOf("/protected"), store.getPackageReadOnly("com.example", 10))
        assertEquals(listOf("com.example"), store.getUninstalledSrPackages(setOf("other")))
        assertEquals(listOf("com.example"), store.getUninstalledReadOnlyPackages(setOf("other")))
        assertTrue(store.getUninstalledSrPackages(setOf("com.example")).isEmpty())
    }

    /**
     * 批量索引必须与逐包读取逐项一致。
     *
     * 动机：`getPackageSrCount()` / `getPackageReadOnly()` 每次调用都重新读盘 + 解析
     * 整个策略文件，应用列表对 531 个包各调一次就是 1062 次全量解析（实测 1450ms）。
     * 改成一次 [policyRuleCountIndex] 后，正确性完全依赖「索引 == 逐包结果」这个等式。
     */
    @Test
    fun `批量规则计数索引与逐包读取一致`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        store.updateRedirect(store.readRedirect().revision) {
            it.replaceRedirectRules(
                listOf(
                    "/storage/emulated/0/DCIM" to "/storage/emulated/0/Pictures",
                    "/storage/emulated/0/Download" to
                            "/storage/emulated/0/Android/data/com.a/files/Download",
                ),
                listOf("com.a"),
            )
        }
        store.updateRedirect(store.readRedirect().revision) {
            it.replaceRedirectRules(
                listOf(
                    "/storage/emulated/0/Movies" to
                            "/storage/emulated/0/Android/data/com.b/files/Movies",
                ),
                listOf("com.b"),
            )
        }
        store.updateReadOnly(store.readReadOnly().revision) {
            it.replaceReadOnlyRules(listOf("/protected", "/secrets"), listOf("com.b"))
        }

        val index = store.policyRuleCountIndex()

        // 逐包结果必须与索引完全一致（含未配置包返回 0）
        for (name in listOf("com.a", "com.b", "com.missing")) {
            assertEquals(store.getPackageSrCount(name), index.srCount(name))
            assertEquals(store.getPackageReadOnly(name).size, index.readOnlyCount(name))
        }
        // 钉住具体数值，避免两边同时错成 0 也能通过
        assertEquals(2, index.srCount("com.a"))
        assertEquals(1, index.srCount("com.b"))
        assertEquals(0, index.srCount("com.missing"))
        assertEquals(0, index.readOnlyCount("com.a"))
        assertEquals(2, index.readOnlyCount("com.b"))
        assertEquals(0, index.readOnlyCount("com.missing"))
        assertEquals(setOf("com.a", "com.b"), index.configuredPackages)
    }

    /** 空配置下索引必须是空表，不能因为读不到文件而抛异常。 */
    @Test
    fun `空策略的索引为零`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        val index = store.policyRuleCountIndex()

        assertEquals(0, index.srCount("com.example"))
        assertEquals(0, index.readOnlyCount("com.example"))
        assertTrue(index.configuredPackages.isEmpty())
    }
}
