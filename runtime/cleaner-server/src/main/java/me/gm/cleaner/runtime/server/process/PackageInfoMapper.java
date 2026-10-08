package me.gm.cleaner.runtime.server.process;

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
import me.gm.cleaner.runtime.server.VfsRuntimePolicy;

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
     * 必须最后被置位：旧实现以 sUidToSrPackageNames != null 当作门闩，
     * 而该字段在方法体开头就被赋值，于是并发调用者在门闩已非空、但
     * sLogFormatAppPrincipalNamesToUid 还没赋值的窗口里会跳过整段初始化，
     * 随后在 getUid 里对 null 表调用 get() 而崩溃。
     * 实测后果是 logcat 线程被未捕获异常杀死，进程启动事件永久丢失。
     */
    private static volatile boolean sInitialized;

    /**
     * 建表专用的启动锁：局部建表全部填完再一次性发布，最后置位 sInitialized。
     * 读取侧只看 sInitialized，因此不会观察到半初始化的字段组合。
     */
    private static final Object sBootstrapLock = new Object();

    public static void invalidate() {
        // 先落标志再清字段：与 ensurePackageInfoMaps() 共用同一把启动锁，
        // 两者互斥，不会出现标志已复位但字段仍被判为已初始化的组合。
        synchronized (sBootstrapLock) {
            sInitialized = false;
            sUidToSrPackageNames = null;
            sUidToPackageNames = null;
            sSharedUserIdEnabledUidToProcessNamesToPackageName = null;
            sProcessNameToSystemSrPackageNames = null;
            sProcessNameToSystemPackageNames = null;
            sLogFormatAppPrincipalNamesToUid = null;
        }
    }

    private static void ensurePackageInfoMaps() {
        if (sInitialized) {
            return;
        }
        synchronized (sBootstrapLock) {
            if (sInitialized) {
                return;
            }
            // 全部先建在局部变量上，填充完成后一次性发布，最后再置位 sInitialized。
            final var uidToSrPackageNames = new SparseArray<String>();
            final var uidToPackageNames = new SparseArray<String>();
            final var sharedUserIdEnabledUidToProcessNamesToPackageName =
                    new SparseArray<Map<String, String>>();
            final var processNameToSystemSrPackageNames = new ArrayMap<String, String>();
            final var processNameToSystemPackageNames = new ArrayMap<String, String>();
            final var logFormatAppPrincipalNamesToUid = new ArrayMap<String, Integer>();

            final var srPackages = VfsRuntimePolicy.INSTANCE.getStorageRedirectPackages();

            // 第一遍（尽力而为）：全量枚举已安装包。
            // 这一遍只服务于重定向全量包与系统应用 processName 反查，
            // 对存储重定向本身并非必需，真正必需的是第二遍。
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

            // 第二遍（权威）：只针对已配置重定向的包，逐包取包信息补齐。
            // 起因是全量枚举在部分系统上会静默退化为空列表（不是抛异常），
            // 一旦如此，上面那一遍什么都建不出来，getUid 会全部返回 -1，
            // 观察器在 uid 为 -1 时走进系统应用分支拿到空包名，于是静默丢弃：
            // 不报错、不挂载、不留任何日志。
            // 逐包取包信息不经过全量枚举接口，因此不受该退化影响；
            // 且数量等于已配置包数（通常个位数），比重建全量表便宜得多。
            for (final var userId : SystemService.getUserIdsNoThrow()) {
                for (final var packageName : srPackages) {
                    final var pi = SystemService.getPackageInfoNoThrow(packageName, 0, userId);
                    putMappings(pi, userId, srPackages, uidToSrPackageNames, uidToPackageNames,
                            sharedUserIdEnabledUidToProcessNamesToPackageName,
                            processNameToSystemSrPackageNames, processNameToSystemPackageNames,
                            logFormatAppPrincipalNamesToUid);
                }
            }

            // 发布：先写全部字段，最后置位标志，读取方可见完整映射表。
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
     * 单个包的映射登记。第一遍与第二遍共用（含 sharedUserId 分支与重定向判定优先级），
     * 只是把包信息换成入参传入。
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
        // 单个包信息异常不得中断整张映射表的构建，
        // 否则 sInitialized 永远为假，后续每次调用都会重跑并重复失败。
        if (pi == null || pi.applicationInfo == null) {
            return;
        }
        final var uid = pi.applicationInfo.uid;
        final var packageName = pi.packageName;
        final var sr = srPackages.contains(packageName);
        final var processNames = getProcessNames(packageName, userId);
        if (RuntimeFileUtils.INSTANCE.toAppId(uid) >= AID_APP_START) {
            // 用户应用用 uid 反查包名，启用 sharedUserId 的改用进程名区分。
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
            // 系统应用用进程名反查包名。
            if (sr) {
                processNames.forEach(processName ->
                        processNameToSystemSrPackageNames.put(processName, packageName));
            } else {
                processNames.forEach(processName ->
                        processNameToSystemPackageNames.put(processName, packageName));
            }
        }
        // 主体名格式如 u0a123，统一去掉下划线后再建索引，保证查表口径一致。
        if (sr) {
            final var logFormatAppPrincipalName = AndroidFilesystemConfig
                    .getAppPrincipalName(uid)
                    .replace("_", "");
            logFormatAppPrincipalNamesToUid.put(logFormatAppPrincipalName, uid);
        }
    }

    /**
     * 读取侧统一入口：即使 invalidate 恰好插在建表与取值之间把字段清空，
     * 也只返回不可用而不是抛空指针。调用点绝不允许因映射表瞬时不可用而中断。
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
     * 映射表是否已就绪。调用侧在做归属判定前必须先问这一句。
     * 必要性：映射不可用时 getSrPackageName 对所有 uid 都返回空，
     * 若直接拿它当判据，会把全部进程都判成非目标，
     * 表现为所有包显示未挂载，又一次静默降级。
     * 所以调用侧必须能区分映射说不属于与映射压根不可用。
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

    // 主体名（去掉下划线归一化后）到 uid 再到重定向包名的映射：失败返回 -1，绝不抛异常。
    public static int getUid(String logFormatAppPrincipalName) {
        if (logFormatAppPrincipalName != null && TextUtils.isDigitsOnly(logFormatAppPrincipalName)) {
            try {
                return Integer.parseInt(logFormatAppPrincipalName);
            } catch (final NumberFormatException e) {
                return -1;
            }
        }
        ensurePackageInfoMaps();
        final var logFormatAppPrincipalNamesToUid = sLogFormatAppPrincipalNamesToUid;
        if (logFormatAppPrincipalNamesToUid != null && logFormatAppPrincipalName != null) {
            final var uid = logFormatAppPrincipalNamesToUid.get(logFormatAppPrincipalName);
            if (uid != null) {
                return uid;
            }
        }
        // 映射表未收录时的兜底：建表时目标包尚未配置，或全量枚举退化为空。
        // 直接返回 -1 会让观察器静默丢弃该进程启动事件，所以尽力按算术还原，
        // 再用重定向包映射表复核身份，避免把非重定向包误判成挂载目标。
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
     * 从主体名反推 uid，只接受 u用户号a应用号这一种形态。
     * 解析后会回环重编一次，只有能还原出同一个主名才接受；
     * 回环校验天然排除隔离进程、共享组与各类派生主体名，
     * 因此不会因解析过宽而把普通进程误认成重定向目标。
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

    // getPackageStatus()、remountAll() 使用：进程号加读到的 uid 再到包名。
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
