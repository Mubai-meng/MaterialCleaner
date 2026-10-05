package me.gm.cleaner.runtime.server.observer;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.gm.cleaner.runtime.server.BuildConfig;
import me.gm.cleaner.runtime.server.CleanerServer;
import me.gm.cleaner.runtime.server.ServerConstants;
import me.gm.cleaner.runtime.server.util.StringUtils;

public class ActivityManagerLogsObserver extends BaseProcessObserver {
    private static final int INDEX_OF_TAG = 33;

    private final CleanerServer mServer;
    private volatile boolean mHasAmStart = false;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /**
     * 已被"静默丢弃"过的 app principal name 集合（去重后再记录日志）。
     * 没有它时，一次丢包在日志上完全不可见——正是上一轮"规则建了但没重定向"
     * 无法定位的原因。
     */
    private final Set<String> mUnresolvedLogged = ConcurrentHashMap.newKeySet();

    /** logcat 子进程附着次数，仅用于日志留痕。 */
    private final java.util.concurrent.atomic.AtomicInteger mLogcatAttachCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public ActivityManagerLogsObserver(final CleanerServer server) {
        mServer = server;
    }

    @Override
    public void onStart() {
        super.onStart();
        // /system/bin/logcat -c
        final var clearLogcat = new char[0x15];

        clearLogcat[0x0] = '-';
        clearLogcat[0x1] = 'p';
        clearLogcat[0x2] = '}';
        clearLogcat[0x3] = 'v';
        clearLogcat[0x4] = 'r';
        clearLogcat[0x5] = 'b';
        clearLogcat[0x6] = 'e';
        clearLogcat[0x7] = '&';
        clearLogcat[0x8] = 'h';
        clearLogcat[0x9] = 'b';
        clearLogcat[0xa] = 'b';
        clearLogcat[0xb] = '\"';
        clearLogcat[0xc] = 'b';
        clearLogcat[0xd] = '`';
        clearLogcat[0xe] = 'w';
        clearLogcat[0xf] = 'r';
        clearLogcat[0x10] = 's';
        clearLogcat[0x11] = 't';
        clearLogcat[0x12] = '!';
        clearLogcat[0x13] = '/';
        clearLogcat[0x14] = '`';

        for (int i = 0; i < 0x15; ++i) {
            clearLogcat[i] ^= (i + 0x15) % 19;
        }
        // @see https://stackoverflow.com/questions/13931729/filtering-logcat-logs-on-commandline
        // /system/bin/logcat -s ActivityManager:V -v threadtime
        final var logcat = new char[0x35];

        logcat[0x0] = '%';
        logcat[0x1] = 'x';
        logcat[0x2] = 'u';
        logcat[0x3] = '~';
        logcat[0x4] = 'z';
        logcat[0x5] = 'j';
        logcat[0x6] = '}';
        logcat[0x7] = '>';
        logcat[0x8] = 'p';
        logcat[0x9] = 'z';
        logcat[0xa] = 'z';
        logcat[0xb] = ':';
        logcat[0xc] = 'z';
        logcat[0xd] = 'x';
        logcat[0xe] = '\u007F';
        logcat[0xf] = 'z';
        logcat[0x10] = '{';
        logcat[0x11] = 'o';
        logcat[0x12] = '<';
        logcat[0x13] = '0';
        logcat[0x14] = 'm';
        logcat[0x15] = '?';
        logcat[0x16] = 'a';
        logcat[0x17] = 'B';
        logcat[0x18] = 'V';
        logcat[0x19] = 'J';
        logcat[0x1a] = 'R';
        logcat[0x1b] = 'L';
        logcat[0x1c] = 'R';
        logcat[0x1d] = '^';
        logcat[0x1e] = 'e';
        logcat[0x1f] = 'H';
        logcat[0x20] = 'D';
        logcat[0x21] = 'a';
        logcat[0x22] = 'f';
        logcat[0x23] = 'g';
        logcat[0x24] = 'q';
        logcat[0x25] = '>';
        logcat[0x26] = 'S';
        logcat[0x27] = '&';
        logcat[0x28] = '*';
        logcat[0x29] = '~';
        logcat[0x2a] = ')';
        logcat[0x2b] = '~';
        logcat[0x2c] = 'c';
        logcat[0x2d] = '~';
        logcat[0x2e] = 'h';
        logcat[0x2f] = 'o';
        logcat[0x30] = 'k';
        logcat[0x31] = 'd';
        logcat[0x32] = 'x';
        logcat[0x33] = '\u007F';
        logcat[0x34] = 'v';

        for (int i = 0; i < 0x35; ++i) {
            logcat[i] ^= (i + 0x35) % 43;
        }
        // ActivityManager: Start proc
        final var amStartProc = new char[0x1b];

        amStartProc[0x0] = 'A';
        amStartProc[0x1] = 'b';
        amStartProc[0x2] = 'v';
        amStartProc[0x3] = 'j';
        amStartProc[0x4] = 'r';
        amStartProc[0x5] = 'l';
        amStartProc[0x6] = 'r';
        amStartProc[0x7] = '~';
        amStartProc[0x8] = 'E';
        amStartProc[0x9] = 'h';
        amStartProc[0xa] = 'd';
        amStartProc[0xb] = 'j';
        amStartProc[0xc] = 'k';
        amStartProc[0xd] = 'h';
        amStartProc[0xe] = '|';
        amStartProc[0xf] = '5';
        amStartProc[0x10] = '0';
        amStartProc[0x11] = 'B';
        amStartProc[0x12] = 'f';
        amStartProc[0x13] = 'r';
        amStartProc[0x14] = 'f';
        amStartProc[0x15] = 'a';
        amStartProc[0x16] = '6';
        amStartProc[0x17] = 'g';
        amStartProc[0x18] = 'j';
        amStartProc[0x19] = 'v';
        amStartProc[0x1a] = 'y';

        for (int i = 0; i < 0x1b; ++i) {
            amStartProc[i] ^= (i + 0x1b) % 27;
        }
        // ActivityManager: Killing
        final var amKilling = new char[0x18];

        amKilling[0x0] = 'B';
        amKilling[0x1] = 'g';
        amKilling[0x2] = 'q';
        amKilling[0x3] = 'o';
        amKilling[0x4] = 'q';
        amKilling[0x5] = 'a';
        amKilling[0x6] = '}';
        amKilling[0x7] = 's';
        amKilling[0x8] = 'F';
        amKilling[0x9] = 'm';
        amKilling[0xa] = 'c';
        amKilling[0xb] = 'o';
        amKilling[0xc] = 'h';
        amKilling[0xd] = 'u';
        amKilling[0xe] = 'c';
        amKilling[0xf] = '(';
        amKilling[0x10] = '3';
        amKilling[0x11] = '_';
        amKilling[0x12] = 'i';
        amKilling[0x13] = 'm';
        amKilling[0x14] = 'n';
        amKilling[0x15] = 'j';
        amKilling[0x16] = 'j';
        amKilling[0x17] = 'b';

        for (int i = 0; i < 0x18; ++i) {
            amKilling[i] ^= (i + 0x18) % 21;
        }
        // PhantomProcessRecord
        final var phantomProcessRecord = new char[0x14];

        phantomProcessRecord[0x0] = 'Q';
        phantomProcessRecord[0x1] = 'j';
        phantomProcessRecord[0x2] = 'b';
        phantomProcessRecord[0x3] = 'j';
        phantomProcessRecord[0x4] = 'q';
        phantomProcessRecord[0x5] = 'i';
        phantomProcessRecord[0x6] = 'j';
        phantomProcessRecord[0x7] = 'X';
        phantomProcessRecord[0x8] = '{';
        phantomProcessRecord[0x9] = 'e';
        phantomProcessRecord[0xa] = 'h';
        phantomProcessRecord[0xb] = 'i';
        phantomProcessRecord[0xc] = '~';
        phantomProcessRecord[0xd] = '}';
        phantomProcessRecord[0xe] = ']';
        phantomProcessRecord[0xf] = 'u';
        phantomProcessRecord[0x10] = 'r';
        phantomProcessRecord[0x11] = '}';
        phantomProcessRecord[0x12] = 'r';
        phantomProcessRecord[0x13] = 'e';

        for (int i = 0; i < 0x14; ++i) {
            phantomProcessRecord[i] ^= (i + 0x14) % 19;
        }
        mExecutor.execute(() -> {
            Process process = null;
            try {
                if (BuildConfig.DEBUG) {
                    try {
                        Runtime.getRuntime().exec(new String(clearLogcat));
                    } catch (final Throwable e) {
                        Log.e("AMLogs", "clear logcat failed", e);
                    }
                }

                while (true) {
                    process = Runtime.getRuntime().exec(new String(logcat));
                    // 启动留痕：本循环是进程启动事件的唯一来源，它一旦静默死掉
                    // （exec 失败、被 SELinux 拒绝等），整条 bind mount 链路就没了触发点。
                    Log.i("MC_REDIRECT", "[AMLogsObserver] logcat attached: attempt="
                            + mLogcatAttachCount.incrementAndGet()
                            + " uid=" + android.os.Process.myUid());
                    final var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                        try {
                            final var indexOfStartProc = line.indexOf(new String(amStartProc));
                            if (indexOfStartProc != -1) {
                                if (indexOfStartProc != INDEX_OF_TAG) {
                                    continue;
                                }
                                // $pid:$processName/$logFormatAppPrincipalName for pre-top-activity, content provider, service {$packageName/$className} caller=$packageName
                                final var indexOfBrace = line.indexOf('{');
                                if (indexOfBrace == -1) {
                                    continue;
                                }
                                if (!mHasAmStart) {
                                    mHasAmStart = true;
                                }
                                final var start = StringUtils.substring(line, indexOfStartProc + 28, indexOfBrace);
                                // 异形行（如厂商定制 kill-reason 行）可能缺分隔符，先验下标再切分，
                                // 否则 substring 抛异常，debug 包还会连带杀死观察线程。
                                final var startSlash = start.indexOf('/');
                                final var startSpace = start.indexOf(' ');
                                if (startSlash == -1 || startSpace == -1 || startSpace <= startSlash) {
                                    continue;
                                }
                                final var logFormatAppPrincipalName = StringUtils.substring(start, startSlash + 1, startSpace);
                                final var uid = PackageInfoMapper.getUid(logFormatAppPrincipalName);
                                if (!isMounterActiveForUid(uid)) {
                                    continue;
                                }
                                final var startColon = start.indexOf(':');
                                if (startColon == -1 || startColon > startSlash) {
                                    continue;
                                }
                                final var processName = StringUtils.substring(start, startColon + 1, startSlash);

                                final var mountAll = getMounter().mountForAllPackages();
                                final String packageName;
                                if (mountAll) {
                                    packageName = PackageInfoMapper.getPackageName(uid, processName);
                                } else {
                                    packageName = PackageInfoMapper.getSrPackageName(uid, processName);
                                }
                                if (!TextUtils.isEmpty(packageName)) {
                                    final var pidStr = StringUtils.substring(start, 0, startColon);
                                    if (!TextUtils.isDigitsOnly(pidStr)) {
                                        continue;
                                    }
                                    final var pid = Integer.parseInt(pidStr);
                                    Log.i("MC_REDIRECT", "[AMLogsObserver] Process start detected: pkg=" + packageName + " pid=" + pid + " uid=" + uid);
                                    Log.i("MC_REDIRECT", "[AMLogsObserver] Triggering bindMount for " + packageName);
                                    getMounter().bindMountAsync(packageName, pid, uid);
                                } else if (mUnresolvedLogged.add(logFormatAppPrincipalName)) {
                                    // 静默丢弃是本模块最危险的失效形态：不挂载、不报错、无日志，
                                    // 用户只看到"规则建了但没重定向"。这里按 principalName 去重后
                                    // 报一次，既能定位"为什么没挂载"，又不会在进程风暴时刷屏。
                                    Log.w("MC_REDIRECT",
                                            "[AMLogsObserver] start event dropped: principalName="
                                                    + logFormatAppPrincipalName
                                                    + " uid=" + uid
                                                    + " processName=" + processName
                                                    + " mountAll=" + mountAll
                                                    + (uid < 0
                                                    // 注意措辞：uid < 0 只表示 getUid() 拒绝返回，
                                                    // 不能断言是"解析不出来"——按算术兜底能解出 uid、
                                                    // 但该包没配重定向规则时同样返回 -1，
                                                    // 那属于预期行为而非故障。上一轮被这句误导过一次。
                                                    ? " (uid not usable: unresolvable principal name,"
                                                    + " or package has no storage-redirect rule"
                                                    + " — the latter is expected, not a fault)"
                                                    : " (uid ok but package name lookup missed"
                                                    + " — check PackageInfoMapper mapping)"));
                                }
                            } else {
                                // $pid:$processName/$logFormatAppPrincipalName (adj 0): stop $packageName due to from pid $pid
                                final var indexOfKilling = line.indexOf(new String(amKilling));
                                if (indexOfKilling != INDEX_OF_TAG || line.length() < indexOfKilling + 25) {
                                    continue;
                                }
                                final var killing = StringUtils.substring(line, indexOfKilling + 25);
                                if (killing.startsWith(new String(phantomProcessRecord))) {
                                    continue;
                                }
                                // 同 start 分支：异形行缺分隔符时跳过，避免 substring 抛异常。
                                final var killingSlash = killing.indexOf('/');
                                final var killingSpace = killing.indexOf(' ');
                                if (killingSlash == -1 || killingSpace == -1 || killingSpace <= killingSlash) {
                                    continue;
                                }
                                final var logFormatAppPrincipalName = StringUtils.substring(killing, killingSlash + 1, killingSpace);
                                final var uid = PackageInfoMapper.getUid(logFormatAppPrincipalName);
                                if (!isMounterActiveForUid(uid)) {
                                    continue;
                                }
                                final var killingColon = killing.indexOf(':');
                                if (killingColon == -1 || killingColon > killingSlash) {
                                    continue;
                                }
                                final var processName = StringUtils.substring(killing, killingColon + 1, killingSlash);

                                final var packageName = PackageInfoMapper.getPackageName(uid, processName);
                                if (!TextUtils.isEmpty(packageName)) {
                                    final var pidStr = StringUtils.substring(killing, 0, killingColon);
                                    if (!TextUtils.isDigitsOnly(pidStr)) {
                                        continue;
                                    }
                                    final var pid = Integer.parseInt(pidStr);
                                    getMounter().notifyProcessKilled(packageName, pid);
                                }
                            }
                        } catch (StringIndexOutOfBoundsException e) {
                            Log.e("ActivityManagerLogsObserver", line, e);
                            if (BuildConfig.DEBUG) {
                                throw e;
                            }
                        } catch (Throwable t) {
                            // 单行解析失败绝不允许终止本线程：本 runnable 一旦退出，finally 会广播
                            // ACTION_LOGCAT_SHUTDOWN 并 shutdown 执行器，观察器将**永久失效**——
                            // 后果不只是日志缺失，而是进程启动事件全部丢失（文件系统记录永远为空，
                            // 新启动的进程也不再触发 bindMount，存储重定向静默降级）。
                            // 实测触发案例：PackageInfoMapper 映射表瞬时不可用导致的 NPE。
                            Log.e("ActivityManagerLogsObserver",
                                    "Failed to handle logcat line, skipping: " + line, t);
                        }
                    }
                    reader.close();
                    Thread.sleep(5000);
                }
            } catch (final IOException | InterruptedException e) {
                Log.e("ActivityManagerLogsObserver", "Logcat reading loop exited unexpectedly", e);
            } finally {
                mServer.noticeDispatcher.broadcastIntent(broadcastIntent ->
                        broadcastIntent.setAction(ServerConstants.ACTION_LOGCAT_SHUTDOWN)
                );
                if (process != null) {
                    process.destroy();
                }
                mExecutor.shutdown();
            }
        });
    }

    public boolean hasAmStart() {
        return mHasAmStart;
    }

    public boolean isLogcatShutdown() {
        return mExecutor.isShutdown();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }
}
