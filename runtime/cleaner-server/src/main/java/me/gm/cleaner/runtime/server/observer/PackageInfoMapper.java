package me.gm.cleaner.runtime.server.observer;

import static me.gm.cleaner.core.common.AndroidFilesystemConfig.AID_APP_START;

import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import api.SystemService;
import me.gm.cleaner.core.common.AndroidFilesystemConfig;
import me.gm.cleaner.core.common.RuntimeFileUtils;
import me.gm.cleaner.runtime.server.VfsRuntimeConfigStore;

public class PackageInfoMapper {
    private static final String TAG = "PackageInfoMapper";

    private static volatile SparseArray<String> sUidToSrPackageNames;
    private static volatile SparseArray<String> sUidToPackageNames;
    private static volatile SparseArray<Map<String, String>> sSharedUserIdEnabledUidToProcessNamesToPackageName;
    private static volatile Map<String, String> sProcessNameToSystemSrPackageNames;
    private static volatile Map<String, String> sProcessNameToSystemPackageNames;
    private static volatile Map<String, Integer> sLogFormatAppPrincipalNamesToUid;

    /**
     * 所有映射表都已完整填充并发布的标志。
     *
     * 它必须**最后**被置位：旧实现以 {@code sUidToSrPackageNames != null} 当作门闩，
     * 而该字段在方法体开头就被赋值，于是并发调用者在"门闩已是非 null、但
     * sLogFormatAppPrincipalNamesToUid 还没赋值"的窗口里会跳过整段初始化，
     * 随后在 {@link #getUid} 里对 null map 调用 get() 抛 NPE。
     * 实测后果是 ActivityManagerLogsObserver 的 logcat 线程被未捕获异常杀死，
     * 进程启动事件永久丢失（文件系统记录为空，且新进程不再触发 bindMount）。
     */
    private static volatile boolean sInitialized;

    public synchronized static void invalidate() {
        // 先落标志再清字段：与 ensurePackageInfoMaps() 共用 PackageInfoMapper.class 监视器，
        // 两者互斥，不会出现"标志已复位但字段仍被判为已初始化"的组合。
        sInitialized = false;
        sUidToSrPackageNames = null;
        sUidToPackageNames = null;
        sSharedUserIdEnabledUidToProcessNamesToPackageName = null;
        sProcessNameToSystemSrPackageNames = null;
        sProcessNameToSystemPackageNames = null;
        sLogFormatAppPrincipalNamesToUid = null;
    }

    private static void ensurePackageInfoMaps() {
        if (sInitialized) {
            return;
        }
        synchronized (PackageInfoMapper.class) {
            if (sInitialized) {
                return;
            }
            // 全部先建在局部变量上，填充完成后一次性发布，最后再置位 sInitialized。
            // 读取侧只认 sInitialized，因此不会观察到"半初始化"的字段组合。
            final var uidToSrPackageNames = new SparseArray<String>();
            final var uidToPackageNames = new SparseArray<String>();
            final var sharedUserIdEnabledUidToProcessNamesToPackageName =
                    new SparseArray<Map<String, String>>();
            final var processNameToSystemSrPackageNames = new ArrayMap<String, String>();
            final var processNameToSystemPackageNames = new ArrayMap<String, String>();
            final var logFormatAppPrincipalNamesToUid = new ArrayMap<String, Integer>();

            final var srPackages = VfsRuntimeConfigStore.INSTANCE.getStorageRedirectPackages();

            // 第一遍（best-effort）：全量枚举已安装包。
            // 这一遍只服务于"重定向全量包"（mountForAllPackages）与系统应用 processName 反查，
            // 对存储重定向本身并非必需——真正必需的是第二遍。
            try {
                for (final var userId : SystemService.getUserIdsNoThrow()) {
                    final var installed = SystemService.getInstalledPackagesNoThrow(0, userId);
                    if (installed == null) {
                        continue;
                    }
                    for (final var pi : installed) {
                        putMappings(pi, userId, srPackages, uidToSrPackageNames, uidToPackageNames,
                                sharedUserIdEnabledUidToProcessNamesToPackageName,
                                processNameToSystemSrPackageNames, processNameToSystemPackageNames,
                                logFormatAppPrincipalNamesToUid);
                    }
                }
            } catch (final Throwable t) {
                Log.w(TAG, "full package enumeration failed, relying on targeted pass", t);
            }

            // 第二遍（权威）：只针对已配置重定向的包，逐包 getPackageInfo 解析。
            //
            // 为什么必须单独有这一遍：`getInstalledPackagesNoThrow` 在 API 37 上走的
            // `IPackageManager.getInstalledPackages(long,int)` 返回类型已由
            // `ParceledListSlice` 改为 `PackageInfoList`，任何环节退化（反射兜底失败、
            // 系统服务尚未就绪等）都会让它整体变成**空列表**——注意是"静默变空"，
            // 不是抛异常。一旦如此，上面那一遍什么都建不出来，`getUid()` 对**所有**包
            // 都返回 -1，而 logcat 观察器在 uid 为 -1 时会走进"系统应用"分支拿到 null
            // 包名，于是**静默 continue**：不报错、不挂载、不留任何日志。
            //
            // 实测现象（本轮现场日志）：redirect_policy 已下发到 MediaProvider
            // （packages=1）、configured_mount_points 已推到 native（count=3）、
            // 但 `[Mounter]` 与 `[AMLogsObserver]` 在整个 6 分钟会话中计数均为 0，
            // 即 VFS bind mount 从未执行，重定向完全不生效。
            //
            // 逐包 getPackageInfo 不经过 getInstalledPackages，因此不受该变更影响；
            // 且 N = 已配置包数（通常个位数），比全量枚举便宜两个数量级。
            for (final var userId : SystemService.getUserIdsNoThrow()) {
                for (final var packageName : srPackages) {
                    final var pi = SystemService.getPackageInfoNoThrow(packageName, 0, userId);
                    putMappings(pi, userId, srPackages, uidToSrPackageNames, uidToPackageNames,
                            sharedUserIdEnabledUidToProcessNamesToPackageName,
                            processNameToSystemSrPackageNames, processNameToSystemPackageNames,
                            logFormatAppPrincipalNamesToUid);
                }
            }

            // 发布：先写全部字段，最后置位标志（volatile 写 → 读取方可见完整映射表）。
            sUidToSrPackageNames = uidToSrPackageNames;
            sUidToPackageNames = uidToPackageNames;
            sSharedUserIdEnabledUidToProcessNamesToPackageName =
                    sharedUserIdEnabledUidToProcessNamesToPackageName;
            sProcessNameToSystemSrPackageNames = processNameToSystemSrPackageNames;
            sProcessNameToSystemPackageNames = processNameToSystemPackageNames;
            sLogFormatAppPrincipalNamesToUid = logFormatAppPrincipalNamesToUid;
            sInitialized = true;
            Log.i(TAG, "package maps built: sr=" + srPackages.size()
                    + " srUids=" + uidToSrPackageNames.size()
                    + " principalNames=" + logFormatAppPrincipalNamesToUid.size());
        }
    }

    /**
     * 单个包的映射登记。第一遍/第二遍共用，行为与原实现逐字节等价
     * （含 sharedUserId 分支与 sr 判定的优先级），只是把 {@code pi} 换成入参。
     */
    private static void putMappings(
            final PackageInfo pi,
            final int userId,
            final Set<String> srPackages,
            final SparseArray<String> uidToSrPackageNames,
            final SparseArray<String> uidToPackageNames,
            final SparseArray<Map<String, String>> sharedUserIdEnabledUidToProcessNamesToPackageName,
            final Map<String, String> processNameToSystemSrPackageNames,
            final Map<String, String> processNameToSystemPackageNames,
            final Map<String, Integer> logFormatAppPrincipalNamesToUid) {
        // 单个包信息异常（例如 applicationInfo 缺失）不得中断整张映射表的构建，
        // 否则 sInitialized 永远为 false，后续每次调用都会重跑并重复抛错。
        if (pi == null || pi.applicationInfo == null) {
            return;
        }
        final var uid = pi.applicationInfo.uid;
        final var packageName = pi.packageName;
        final var sr = srPackages.contains(packageName);
        final var processNames = getProcessNames(packageName, userId);
        if (RuntimeFileUtils.INSTANCE.toAppId(uid) >= AID_APP_START) {
            // As for user apps, we use uid to figure out its packageName.
            // But if the app enabled sharedUserId, we use processName.
            if (TextUtils.isEmpty(pi.sharedUserId)) {
                if (sr) {
                    uidToSrPackageNames.put(uid, packageName);
                } else {
                    uidToPackageNames.put(uid, packageName);
                }
            } else if (sr) {
                var processNameToPackageName =
                        sharedUserIdEnabledUidToProcessNamesToPackageName.get(uid);
                if (processNameToPackageName == null) {
                    processNameToPackageName = new ArrayMap<>(processNames.size());
                }
                for (final var processName : processNames) {
                    processNameToPackageName.put(processName, packageName);
                }
                sharedUserIdEnabledUidToProcessNamesToPackageName.put(
                        uid, processNameToPackageName);
            }
        } else {
            // As for system apps, we use processName to figure out its packageName.
            if (sr) {
                processNames.forEach(processName ->
                        processNameToSystemSrPackageNames.put(processName, packageName));
            } else {
                processNames.forEach(processName ->
                        processNameToSystemPackageNames.put(processName, packageName));
            }
        }
        // getAppPrincipalName format: "u0a123" or "u0a123_sr"; normalize by stripping underscores for consistent lookup.
        if (sr) {
            final var logFormatAppPrincipalName = AndroidFilesystemConfig
                    .getAppPrincipalName(uid)
                    .replace("_", "");
            logFormatAppPrincipalNamesToUid.put(logFormatAppPrincipalName, uid);
        }
    }

    /**
     * 读取侧统一入口：即使 {@link #invalidate()} 恰好插在 ensure 与取值之间把字段清空，
     * 也返回 null 而不是抛 NPE。调用点（logcat 解析线程）绝不允许因映射表瞬时不可用而中断。
     */
    private static boolean mapsUnavailable() {
        return sUidToSrPackageNames == null
                || sUidToPackageNames == null
                || sSharedUserIdEnabledUidToProcessNamesToPackageName == null
                || sProcessNameToSystemSrPackageNames == null
                || sProcessNameToSystemPackageNames == null
                || sLogFormatAppPrincipalNamesToUid == null;
    }

    /**
     * 映射表是否已就绪。调用侧在做 uid 归属判定前必须先问这一句。
     *
     * <p>必要性：映射不可用时 {@link #getSrPackageName} 对**所有** uid 都返回 null。
     * 若直接拿它当"该进程是否属于本包"的判据，会把全部进程都判成非目标，
     * 表现为所有包显示"未挂载"——又一次静默降级，与
     * {@code getInstalledPackagesNoThrow} 退化那一类故障同型。
     * 所以调用侧必须能区分"映射说不属于"和"映射压根不可用"。
     */
    public static boolean isMappingReady() {
        ensurePackageInfoMaps();
        return !mapsUnavailable();
    }

    public static String getSrPackageName(int uid, String processName) {
        ensurePackageInfoMaps();
        if (mapsUnavailable()) {
            return null;
        }
        if (RuntimeFileUtils.INSTANCE.toAppId(uid) >= AID_APP_START) {
            final var processNameToPackageName = sSharedUserIdEnabledUidToProcessNamesToPackageName.get(uid);
            if (processNameToPackageName == null) {
                // user app
                return sUidToSrPackageNames.get(uid);
            } else {
                // sharedUserId enabled user app
                return processNameToPackageName.get(processName);
            }
        } else {
            // system app
            return sProcessNameToSystemSrPackageNames.get(processName);
        }
    }

    // Mapping: normalized appPrincipalName (underscores stripped) -> uid -> srPackageName
    public static int getUid(String logFormatAppPrincipalName) {
        if (TextUtils.isDigitsOnly(logFormatAppPrincipalName)) {
            return Integer.parseInt(logFormatAppPrincipalName);
        }
        ensurePackageInfoMaps();
        final var logFormatAppPrincipalNamesToUid = sLogFormatAppPrincipalNamesToUid;
        if (logFormatAppPrincipalNamesToUid != null) {
            final var uid = logFormatAppPrincipalNamesToUid.get(logFormatAppPrincipalName);
            if (uid != null) {
                return uid;
            }
        }
        // 映射表未收录时的兜底。两种情况会走到这里：
        //   1) 建表时刻该包还未被配置重定向（表建好后才加规则，且 invalidate 尚未生效）；
        //   2) 建表时全量枚举退化成空列表（见 ensurePackageInfoMaps 第二遍的说明）。
        // 返回 -1 的后果是观察器静默丢弃该进程启动事件（不挂载、不报错），
        // 所以这里必须尽力还原。`u0a471` 这类主名是可逆的，直接按算术还原，
        // 再用 sr 包映射表复核身份，避免把非重定向包误判成需要挂载的目标。
        final var uid = parseUidFromAppPrincipalName(logFormatAppPrincipalName);
        if (uid < 0) {
            return -1;
        }
        final var uidToSrPackageNames = sUidToSrPackageNames;
        if (uidToSrPackageNames == null) {
            return -1;
        }
        return uidToSrPackageNames.get(uid) != null ? uid : -1;
    }

    /**
     * 从 logcat 的 app principal name（形如 {@code u0_a471} / {@code u0a471}）反解 uid。
     *
     * 只接受 {@code u<userId>_?a<appId>} 这一种形态：解析后**回环重编码**，
     * 只有 {@code getAppPrincipalName(uid)} 能还原出同一个主名才接受。
     * 回环校验天然排除 {@code u0_i5}（isolated）、{@code all_a1}（共享 GID）、
     * {@code u0_a10001_ext} / {@code _cache} 等非应用形态，以及越界的 userId 组合，
     * 因此不会因为解析过于宽松而把普通进程误认成重定向目标。
     */
    private static int parseUidFromAppPrincipalName(final String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return -1;
        }
        final var name = rawName.replace("_", "");
        if (name.length() < 3 || name.charAt(0) != 'u') {
            return -1;
        }
        final var indexOfA = name.indexOf('a', 1);
        if (indexOfA <= 1) {
            return -1;
        }
        final var userPart = name.substring(1, indexOfA);
        final var appPart = name.substring(indexOfA + 1);
        if (!TextUtils.isDigitsOnly(userPart) || !TextUtils.isDigitsOnly(appPart)) {
            return -1;
        }
        final long userId;
        final long appId;
        try {
            userId = Long.parseLong(userPart);
            appId = Long.parseLong(appPart);
        } catch (final NumberFormatException e) {
            return -1;
        }
        final long uid = userId * AndroidFilesystemConfig.AID_USER_OFFSET
                + appId + AndroidFilesystemConfig.AID_APP_START;
        if (uid <= 0 || uid > Integer.MAX_VALUE) {
            return -1;
        }
        final var resolved = (int) uid;
        if (!AndroidFilesystemConfig.getAppPrincipalName(resolved).replace("_", "").equals(name)) {
            return -1;
        }
        return resolved;
    }

    // getPackageStatus(), remountAll(): pid -> read_uid -> packageName
    public static String getPackageName(int uid, String processName) {
        ensurePackageInfoMaps();
        final var srPackageName = getSrPackageName(uid, processName);
        if (srPackageName != null) return srPackageName;
        if (mapsUnavailable()) {
            return null;
        }
        if (RuntimeFileUtils.INSTANCE.toAppId(uid) >= AID_APP_START) {
            final var processNameToPackageName = sSharedUserIdEnabledUidToProcessNamesToPackageName.get(uid);
            if (processNameToPackageName == null) {
                // user app
                return sUidToPackageNames.get(uid);
            } else {
                // sharedUserId enabled user app
                return processNameToPackageName.get(processName);
            }
        } else {
            // system app
            return sProcessNameToSystemPackageNames.get(processName);
        }
    }

    private static List<String> getProcessNames(String packageName, int userId) {
        final var processNames = new ArrayList<String>();
        final var pi = SystemService.getPackageInfoNoThrow(packageName,
                PackageManager.GET_ACTIVITIES |
                        PackageManager.GET_RECEIVERS |
                        PackageManager.GET_SERVICES |
                        PackageManager.GET_PROVIDERS, userId);
        if (pi != null) {
            for (final var components : new ComponentInfo[][]{
                    pi.activities, pi.receivers, pi.services, pi.providers}) {
                if (components != null) {
                    for (final var component : components) {
                        processNames.add(component.processName);
                    }
                }
            }
        }
        return processNames;
    }
}
