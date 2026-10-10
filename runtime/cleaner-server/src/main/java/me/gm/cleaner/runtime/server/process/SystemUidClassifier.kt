package me.gm.cleaner.runtime.server.process

import me.gm.cleaner.core.common.AndroidFilesystemConfig.AID_APP_END
import me.gm.cleaner.core.common.AndroidFilesystemConfig.AID_APP_START
import me.gm.cleaner.core.common.AndroidFilesystemConfig.AID_USER_OFFSET

/**
 * uid 分类：**该 uid 是否可能对应唯一包名**（可证明规则，不是启发式）。
 *
 * ## 为什么需要它
 *
 * `ActivityManagerLogsObserver` 的 start 事件在 `uid >= 0` 之后仍会调用
 * `PackageInfoMapper.getPackageName(uid, processName)`；查不到就按
 * 「uid 正常但包名映射没命中」记一条 **W**，并提示去 `check PackageInfoMapper mapping`。
 *
 * 真机实测（2026-10-10 第 7 批）这条 W 的**唯一**触发者是
 * `principalName=1000 uid=1000 processName=com.oplus.midas`。
 * `1000` 是 `system` 共享 uid（`sharedUserId=android.uid.system`），而应用 uid 的下界是
 * `AID_APP_START = 10000`（与 `Process.FIRST_APPLICATION_UID` 同值）。
 *
 * **`appId < AID_APP_START` 在结构上不可能有唯一包名映射**：它要么是 root/system/radio/shell
 * 这类框架 AID，要么是被多个包**共享**的 uid（`android.uid.system` 等），
 * `getPackageName(uid, processName)` 对它们必然返回空。
 * 因此这一支不是"映射缺失"，而是**预期行为**；记 W 会让现场日志看起来在报错，
 * 也会把真正需要排查的「应用 uid 映射缺失」淹没。
 *
 * ## 方向
 *
 * 只把 [mayHaveUniquePackageMapping] 为 `false` 的情形降级为 D —— 判定与去重逻辑**完全不变**，
 * 与 `AMLogsObserver` 第一分支（`uid < 0`，§31 已降 D）保持同一口径。
 * 反向：`true` 一档仍必须是 W，那才是真正的映射故障。
 */
object SystemUidClassifier {

    /**
     * `appId` 落在应用区间（并含共享/隔离/框架 uid 的排除）时才可能映射到唯一包名。
     *
     * - `uid <= 0`：`0` 是 root，负数不是合法 uid（`getUid()` 的「拒绝」返回值，
     *   调用方已在上游拒绝，这里兜底恒 `false`）。
     * - `appId < AID_APP_START`：root/system/radio/shell/bluetooth… 等框架 AID，**共享**。
     * - `appId > AID_APP_END`：`AID_CACHE_GID`/`AID_EXT_GID`/`AID_EXT_CACHE_GID`/
     *   `AID_SHARED_GID`/`AID_ISOLATED_*` 区间，全部是**共享或框架合成**的身份，
     *   进程名由框架生成（`u<i>_i<n>` / `all_a<n>` / `u<i>_a<n>_ext_cache`），无包名。
     * - 其余（`AID_APP_START ≤ appId ≤ AID_APP_END`，任意 user）：**可能**有唯一包名。
     *
     * 逐段显式枚举而不是写 `appId >= AID_APP_START`：上界同样重要，
     * 少了它隔离进程会被误判成"映射缺失"（虽然上游另有 `isIsolatedUid` 拦截，
     * 但本函数必须自身正确，不能依赖调用顺序）。
     */
    fun mayHaveUniquePackageMapping(uid: Int): Boolean {
        if (uid <= 0) return false
        val appId = uid % AID_USER_OFFSET
        return appId in AID_APP_START..AID_APP_END
    }
}
