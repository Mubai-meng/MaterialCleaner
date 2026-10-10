package me.gm.cleaner.runtime.server.process

import me.gm.cleaner.core.common.AndroidFilesystemConfig.AID_APP_START
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SystemUidClassifier] 的护栏：把「什么 uid 不可能有唯一包名」钉死。
 *
 * 背景（真机第 7 批）：`AMLogsObserver` 第二条 W 的唯一触发者是 `uid=1000`
 * （`com.oplus.midas`，`sharedUserId=android.uid.system`）。该告警的文案却在叫读者
 * 去 `check PackageInfoMapper mapping` —— **假警报**。这里锁死判定，防止有人把规则退回成
 * 「一律记 W」或「一律降 D」。
 */
class SystemUidClassifierTest {

    @Test
    fun `框架与共享uid不可能有唯一包名`() {
        // root / system / radio / shell / bluetooth / nobody 等 AID_* 全部 < AID_APP_START。
        for (uid in intArrayOf(0, 1000, 1001, 1002, 2000, 1013, 9999)) {
            assertFalse("uid=$uid 应判为无唯一包名", SystemUidClassifier.mayHaveUniquePackageMapping(uid))
        }
    }

    @Test
    fun `负uid一律不可能`() {
        for (uid in intArrayOf(-1, -10000, Int.MIN_VALUE)) {
            assertFalse("uid=$uid 应判为无唯一包名", SystemUidClassifier.mayHaveUniquePackageMapping(uid))
        }
    }

    @Test
    fun `共享与隔离区间不可能`() {
        // AID_SHARED_GID / AID_EXT_GID / AID_EXT_CACHE_GID / AID_ISOLATED_* 全在应用区间之上。
        for (uid in intArrayOf(50000, 50001, 30000, 40000, 90000, 99999)) {
            assertFalse("uid=$uid 应判为无唯一包名", SystemUidClassifier.mayHaveUniquePackageMapping(uid))
        }
    }

    @Test
    fun `应用uid可能`() {
        // AID_APP_START .. AID_APP_END
        for (uid in intArrayOf(10000, 10001, 10123, 10326, 19999)) {
            assertTrue("uid=$uid 应判为可能有唯一包名", SystemUidClassifier.mayHaveUniquePackageMapping(uid))
        }
    }

    @Test
    fun `多用户的应用uid同样可能`() {
        // userId=10 的 appId=10123 ⇒ 10*100000+10123
        assertTrue(SystemUidClassifier.mayHaveUniquePackageMapping(10 * 100000 + 10123))
        // 多用户下 appId 仍是同一区间，边界要与 uid=10000/19999 一致
        assertTrue(SystemUidClassifier.mayHaveUniquePackageMapping(3 * 100000 + 10000))
        assertTrue(SystemUidClassifier.mayHaveUniquePackageMapping(3 * 100000 + 19999))
        // 多用户下 appId 低于/高于应用区间仍是不可能
        assertFalse(SystemUidClassifier.mayHaveUniquePackageMapping(3 * 100000 + 9999))
        assertFalse(SystemUidClassifier.mayHaveUniquePackageMapping(3 * 100000 + 20000))
    }

    @Test
    fun `应用区间边界紧贴`() {
        assertFalse(SystemUidClassifier.mayHaveUniquePackageMapping(AID_APP_START - 1))
        assertTrue(SystemUidClassifier.mayHaveUniquePackageMapping(AID_APP_START))
        assertTrue(SystemUidClassifier.mayHaveUniquePackageMapping(19999))
        assertFalse(SystemUidClassifier.mayHaveUniquePackageMapping(20000))
    }

    @Test
    fun `判定与调用次数无关`() {
        // 避免把纯函数写成带状态的（例如缓存/闩锁会随调用顺序漂移）。
        val probe = intArrayOf(1000, 10123, 20000, -1)
        val first = probe.map(SystemUidClassifier::mayHaveUniquePackageMapping)
        val second = probe.reversed().map(SystemUidClassifier::mayHaveUniquePackageMapping).reversed()
        assertTrue("同一批输入重复求值结果必须一致", first == second)
    }
}
