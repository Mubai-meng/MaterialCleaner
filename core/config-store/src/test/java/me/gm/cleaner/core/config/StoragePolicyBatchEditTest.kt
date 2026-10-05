package me.gm.cleaner.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@Suppress("DEPRECATION")
class StoragePolicyBatchEditTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `多包暂存单次提交`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyBatchEdit(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        tx.putRedirect(listOf("/c" to "/d"), listOf("p2"))
        tx.putReadOnly(listOf("/ro"), listOf("p1"))

        assertTrue(store.getPackageSrZipped("p1").isEmpty())
        assertTrue(tx.commitStructured().overall == BatchCommitResult.Overall.SUCCESS)

        assertEquals(listOf("/a" to "/b"), store.getPackageSrZipped("p1"))
        assertEquals(listOf("/c" to "/d"), store.getPackageSrZipped("p2"))
        assertEquals(listOf("/ro"), store.getPackageReadOnly("p1"))
    }

    @Test
    fun `暂存失败提交返回失败且不写入`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyBatchEdit(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf(""))
        assertFalse(tx.commitStructured().overall == BatchCommitResult.Overall.SUCCESS)
        assertTrue(store.getPackageSrZipped("").isEmpty())
    }

    @Test
    fun `任一域暂存失败整体失败且不写入`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyBatchEdit(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        tx.putReadOnly(listOf("/ro"), listOf(""))
        assertFalse(tx.commitStructured().overall == BatchCommitResult.Overall.SUCCESS)
        assertTrue(store.getPackageSrZipped("p1").isEmpty())
        assertTrue(store.getPackageReadOnly("p1").isEmpty())
    }
    @Test
    fun `提交后不可复用`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyBatchEdit(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        assertTrue(tx.commitStructured().overall == BatchCommitResult.Overall.SUCCESS)
        try {
            tx.putRedirect(listOf("/x" to "/y"), listOf("p2"))
            assertFalse("复用应抛", true)
        } catch (e: IllegalStateException) {
            assertTrue(true)
        }
    }

    @Test
    fun `提交时版本冲突返回失败且不覆盖`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyBatchEdit(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        store.updateRedirect(store.readRedirect().revision) {
            it.replaceRedirectRules(listOf("/other" to "/target"), listOf("p2"))
        }
        assertFalse(tx.commitStructured().overall == BatchCommitResult.Overall.SUCCESS)
        assertEquals(listOf("/other" to "/target"), store.getPackageSrZipped("p2"))
        assertTrue(store.getPackageSrZipped("p1").isEmpty())
    }

    @Test
    fun `redirect 成功与 readOnly 冲突时返回 PARTIAL 且不回滚`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyBatchEdit(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        tx.putReadOnly(listOf("/ro"), listOf("p1"))
        // 外部推进 read-only 版本，制造该域的 revision 冲突；redirect 版本不受影响。
        store.updateReadOnly(store.readReadOnly().revision) {
            it.replaceReadOnlyRules(listOf("/other"), listOf("p2"))
        }

        val result = tx.commitStructured()

        assertEquals(BatchCommitResult.Overall.PARTIAL, result.overall)
        assertFalse(result.stageFailed)
        assertNotNull(result.redirect)
        assertTrue(result.redirect!!.success)
        assertNotNull(result.readOnly)
        assertFalse(result.readOnly!!.success)
        assertEquals(PolicyStoreFailureKind.REVISION_CONFLICT, result.readOnly!!.failureKind)
        // 已成功的 redirect 不回滚，冲突的 read-only 未写入。
        assertEquals(listOf("/a" to "/b"), store.getPackageSrZipped("p1"))
        assertTrue(store.getPackageReadOnly("p1").isEmpty())
        assertEquals(listOf("/other"), store.getPackageReadOnly("p2"))
    }

    @Test
    fun `暂存失败时结构化结果两域为 null 且不写入`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyBatchEdit(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        tx.putReadOnly(listOf("/ro"), listOf(""))

        val result = tx.commitStructured()

        assertEquals(BatchCommitResult.Overall.FAILURE, result.overall)
        assertTrue(result.stageFailed)
        assertNull(result.redirect)
        assertNull(result.readOnly)
        assertTrue(store.getPackageSrZipped("p1").isEmpty())
        assertTrue(store.getPackageReadOnly("p1").isEmpty())
    }
}
