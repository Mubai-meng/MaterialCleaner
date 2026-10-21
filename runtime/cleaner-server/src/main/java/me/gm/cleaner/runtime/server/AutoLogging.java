package me.gm.cleaner.runtime.server;

import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.runtime.server.BuildConfig;


/**
 * 日志采集：三个独立通道 + 按配置对账 + 可回收。
 *
 * <h3>通道划分（第30批融合上游 5b1a87d）</h3>
 * <ul>
 *   <li><b>主环形</b> {@code cleaner.log}：`-b main,system` + [LogTagFilter] 白名单，
 *       `-r 4096 -n 8`（32 MB 硬上限）。旧实现采 `main,system,crash` 全量，
 *       实测 ~875 MB/天；按本项目 tag 聚焦后压缩两个数量级。</li>
 *   <li><b>崩溃通道</b> {@code cleaner-crash.log}：`-b crash` **无过滤**。
 *       崩溃时的 tag 是崩溃进程名或 {@code AndroidRuntime}，白名单穷举不到，
 *       一旦施加过滤就是「崩溃证据直接消失」。crash buffer 只在崩溃时写入，
 *       体积可忽略。</li>
 *   <li><b>启动窗口段</b> {@code cleaner-boot-<ts>.log}：`-b main,system,crash`
 *       **无过滤**，只跑 60 秒。主环形是滚动的，启动期那几秒会在几分钟内被新日志
 *       覆盖，而实测挂载失败（`MOUNT.ZYGOTE.*`）恰好发生在启动后 1 秒内。
 *       这一段同时是「白名单万一漏登记某 tag」的兜底现场。</li>
 * </ul>
 *
 * <h3>为什么必须固定基名 + logcat 原生轮转</h3>
 * 旧实现每次 server 启动都用 {@code createTempFile} 新建带时间戳的文件，而淘汰只在
 * **启动瞬间**跑一次 ⇒ 单次会话内的活动文件没有任何上限。本机 logcat 实测
 * ~700–1500 行/s、~177 B/行（≈120–265 KB/s），一个会话就能写出 10–20 GB/天；
 * 而 {@code /data/local/tmp} 在本机是 f2fs {@code /data} 上的普通目录（不是 tmpfs），
 * 这些日志会真实占用用户存储且重启不消失。
 * 改为固定基名 + logcat 原生轮转（{@code -r}/{@code -n}）后，磁盘占用由 logcat
 * 自己硬性封顶，不依赖任何 Java 侧定时器。
 * <p>
 * ⚠️ 因此上游的 {@code Files.createTempFile} 取文件法在本项目**不可用**：
 * {@code logcat -f} 的 {@code -r/-n} 轮转是按<b>同一个路径</b>滚动的，
 * 每次换新文件名等于让轮转永远不生效。本类保留固定基名。
 *
 * <h3>生命周期（第30批融合上游 93bf07a / b295b8f）</h3>
 * 旧实现的配置只在服务初始化时读一次、进程句柄启动即丢弃，因此：
 * ① 关闭「保存日志到文件」开关不会停掉已在写的 logcat，它继续向磁盘写；
 * ② 每次服务重启都多一条永不退出的 logcat（旧的还继续写旧文件）。
 * 现在统一走 [reconcileWithPreferences]：「期望状态」与「实际状态」对账，
 * 并由 [shutdown] 在服务退出前无条件回收。
 */
public class AutoLogging {
    private static final File sLogsDir = new File("/data/local/tmp/cleaner_logs");

    /**
     * 主环形日志的固定基名。
     *
     * 见类注释「为什么必须固定基名」——这里**不能**改成时间戳命名。
     */
    static final String LOG_BASE_NAME = "cleaner.log";

    /**
     * 「启动窗口」专用日志段的前缀。
     *
     * 主环形是**滚动**的：启动期那几秒会在几分钟内被新日志覆盖，而诊断包又只保留
     * 每个文件固定预算的片段。实测挂载失败（`MOUNT.ZYGOTE.*`）恰好发生在 server
     * 启动后 1 秒内，等用户生成诊断包时早已被挤掉。这里额外开一个只跑
     * [BOOT_LOG_SECONDS] 秒的独立段，专门保留「启动 + 首分钟内」的日志。
     */
    static final String BOOT_LOG_PREFIX = "cleaner-boot-";

    /**
     * 崩溃通道的固定基名。
     *
     * 与主环形同样需要 `-r/-n` 轮转，所以同样是固定基名；单独一个文件是因为
     * 它的过滤条件完全不同（`-b crash` 且**无** tag 过滤）。它属于主通道的一部分，
     * 随 [reconcileWithPreferences] 的期望状态一起起停。
     */
    static final String CRASH_LOG_BASE_NAME = "cleaner-crash.log";

    /** 单个文件轮转阈值（KB）。`logcat -r` 的单位就是 KB。 */
    private static final int ROTATE_KBYTES = 4096;      // 4 MB

    /** 环形文件数（含基名文件）。`-n 8` ⇒ cleaner.log + cleaner.log.1 … .7。 */
    private static final int ROTATE_COUNT = 8;

    /**
     * 崩溃通道轮转：2 × 2 MB。
     *
     * crash buffer 本身很小（`-b crash` 的默认容量就 256 KB 级别，且只在崩溃时写入），
     * 这里给 4 MB 总量是纯保险，避免某次崩溃风暴把主环形的配额挤掉。
     */
    private static final int CRASH_ROTATE_KBYTES = 2048;
    private static final int CRASH_ROTATE_COUNT = 2;

    /** 启动窗口段：跑 60 秒，最多 2 × 8 MB = 16 MB（峰值 ~265 KB/s 时仍覆盖 60 s）。 */
    private static final int BOOT_LOG_SECONDS = 60;
    private static final int BOOT_ROTATE_KBYTES = 8192;
    private static final int BOOT_ROTATE_COUNT = 2;

    /**
     * 磁盘侧兜底阈值：文件数 + 总字节。
     *
     * 正常运行时三个通道合计封顶 8 × 4 + 2 × 2 + 2 × 8 = 52 MB；
     * 这里的 64 MB 只用于清理旧版本遗留的 `*.log`（时间戳命名）以及异常情况下
     * 跑飞的单个文件。注意：{@link #pruneLocked()} 只在采集启动时调用，
     * **不是**会话内的实时看门狗；会话内的硬上限完全由 `logcat -r/-n` 保证。
     */
    private static final int MAX_LOG_FILES =
            ROTATE_COUNT + CRASH_ROTATE_COUNT + BOOT_ROTATE_COUNT;
    private static final long MAX_LOG_DIR_BYTES = 64L * 1024 * 1024;

    /**
     * 采集状态锁。
     *
     * 第30批把进程句柄从 static 改为**实例字段**：句柄必须与「哪一次 server 实例
     * 启动了它」绑定，否则一个丢弃的实例无法回收自己那条 logcat。
     */
    private final Object mLock = new Object();

    /** 主环形通道的 logcat；非 null 即「按配置应当运行且正在运行」。 */
    private Process mLogProcess;

    /** 崩溃通道的 logcat。 */
    private Process mCrashLogProcess;

    /** 启动窗口段的 logcat；60 秒后由 [BOOT_SCHEDULER] 停掉并置 null。 */
    private Process mBootLogProcess;
    private ScheduledFuture<?> mBootStopFuture;

    /**
     * 单线程守护调度器：只负责 60 秒后停掉启动段，不创建额外常驻线程池。
     *
     * 保持 static：它是纯粹的定时器，不持有任何实例状态，也不阻止进程退出
     * （daemon 线程）。
     */
    private static final ScheduledExecutorService BOOT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "cleaner-boot-log-stop");
                thread.setDaemon(true);
                return thread;
            });

    public AutoLogging(final android.content.pm.PackageInfo packageInfo) {
    }

    /**
     * 目录内的日志文件名判定：主环形基名、其数字分片、崩溃通道、启动窗口段，
     * 以及旧版本的时间戳 `*.log`。
     *
     * 诊断归档也用它，避免两处口径漂移 —— 否则 `cleaner.log.1 … .7` 会因
     * `endsWith(".log")` 不成立而被漏掉，归档里只剩正在写的那一个文件。
     */
    public static boolean isLogFileName(final String name) {
        if (name == null) {
            return false;
        }
        // 启动段：cleaner-boot-<ts>.log 及其 logcat 轮转分片 .1 / .2
        if (name.startsWith(BOOT_LOG_PREFIX)) {
            return true;
        }
        if (name.endsWith(".log")) {
            // 主环形基名 / 崩溃通道基名 / 旧版 createTempFile 生成的 <ts>-<rand>.log
            return true;
        }
        // 两条固定基名通道的轮转分片
        return isRingShardOf(name, LOG_BASE_NAME)
                || isRingShardOf(name, CRASH_LOG_BASE_NAME);
    }

    /**
     * `"<base>.N"`（N 为纯数字、非空）形式的轮转分片判定。
     *
     * 单独抽出来是因为第30批新增了崩溃通道：只有 `cleaner.log.N` 一种分片时，
     * 靠一个 `startsWith(LOG_BASE_NAME + ".")` 分支就够了；两条通道共用后必须
     * 参数化，否则 `cleaner-crash.log.1` 会静默漏出归档。
     */
    private static boolean isRingShardOf(final String name, final String base) {
        final String prefix = base + ".";
        if (!name.startsWith(prefix)) {
            return false;
        }
        final String suffix = name.substring(prefix.length());
        if (suffix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            final char c = suffix.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 是否为「启动窗口」专用段。诊断归档对这类文件取**头部**而不是尾部。 */
    public static boolean isBootLogFileName(final String name) {
        return name != null && name.startsWith(BOOT_LOG_PREFIX);
    }

    /** 是否为崩溃通道文件（诊断归档把它与主环形一同按首尾两段取用）。 */
    public static boolean isCrashLogFileName(final String name) {
        return name != null && name.startsWith(CRASH_LOG_BASE_NAME);
    }

    private static File prepareLogsDir() throws IOException {
        final var path = sLogsDir.toPath();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(path) ||
                        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            Files.delete(path);
        }
        Files.createDirectories(path);
        setOwnerOnly(sLogsDir, true);
        return sLogsDir;
    }

    /**
     * 预创建固定基名文件并收紧权限。
     *
     * `logcat -f` 由 logcat 自己打开文件，所以这里必须先建好并 chmod 0600；
     * 目录本身是 0700，双重保护。轮转产生的 `<base>.N` 由 logcat 创建，
     * 权限受目录 0700 兜底。
     */
    private static File prepareRingFile(final String baseName) throws IOException {
        final var dir = prepareLogsDir();
        final var logFile = new File(dir, baseName);
        final Path path = logFile.toPath();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(path) ||
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            Files.delete(path);
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !logFile.createNewFile()) {
            throw new IOException("cannot create " + logFile);
        }
        setOwnerOnly(logFile, false);
        return logFile;
    }

    private static void setOwnerOnly(final File file, final boolean executable) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (executable) {
            file.setExecutable(true, true);
        }
    }

    /**
     * 按当前配置对账日志采集的**实际**状态：应启用而未运行则启动，应停用则停止。
     *
     * 幂等：状态已一致时为无操作；可在任意线程调用（含 Binder 线程）。
     * 这是「日志开关」唯一生效点 —— 旧实现只在 {@code initialize()} 读一次配置。
     *
     * 结算顺序很关键：先把「句柄在但进程已死」的情况结算掉，否则会把死引用
     * 误判成「仍在运行」而永不重启（自愈失效）。
     */
    public void reconcileWithPreferences() {
        final boolean desired = ServicePreferences.INSTANCE.getAutoLogging();
        synchronized (mLock) {
            mLogProcess = settleIfExited(mLogProcess, "logcat");
            mCrashLogProcess = settleIfExited(mCrashLogProcess, "logcat crash");
            mBootLogProcess = settleIfExited(mBootLogProcess, "logcat boot");
            if (!desired) {
                shutdownAllLocked("disabled by preference");
                return;
            }
            if (mLogProcess != null || mCrashLogProcess != null || mBootLogProcess != null) {
                // 任一路径活着 ⇒ 整体视为运行中。这里必须返回而不是"重新拉起一轮"：
                // 本方法会被每次偏好变更调用，重启会让启动窗口段反复生成新文件。
                // 之所以不只看主通道：主通道可能因磁盘满/权限被拒而启动失败，
                // 此时 crash 与启动段仍在正常工作 —— 只看主通道会导致每次偏好变更
                // 都把它们连带重启一遍。
                return;
            }
            // 主通道不在（首次启动，或上一轮意外死亡）：清掉可能残留的分支通道，
            // 再完整拉起一轮，避免两个 logcat 抢同一个 `-f` 文件。
            shutdownAllLocked("restart");
            startContinuousLoggingLocked();
        }
    }

    /**
     * 无条件停止日志采集，用于服务退出前回收子进程。
     *
     * 幂等：进程未运行时为无操作。**不等待**进程退出 —— 调用方紧接着
     * {@code System.exit(0)}，在这里阻塞只会拖长退出路径；logcat 对 SIGTERM
     * 是立即响应的，句柄在手就不会留下 PPID=1 的孤儿。
     */
    public void shutdown() {
        synchronized (mLock) {
            shutdownAllLocked("server exiting");
        }
    }

    /**
     * 结算死引用：进程已退出则返回 null（下次对账走启动分支自愈）。
     *
     * 只有「句柄非 null 且 isAlive() 为 false」才算异常退出。启动窗口段的
     * 正常到点停止走的是 [stopBootProcess]，会先把句柄置 null，不会走到这里。
     */
    private static Process settleIfExited(final Process process, final String label) {
        if (process == null || process.isAlive()) {
            return process;
        }
        Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, label + " process exited unexpectedly");
        return null;
    }

    /**
     * 启动三个采集通道（调用方必须已持有 [mLock]）。
     *
     * 任何一个通道启动失败都不影响其它通道：主通道失败时 crash 与启动段仍然
     * 有价值（它们恰好是"主通道为什么起不来"的证据来源）。
     */
    private void startContinuousLoggingLocked() {
        pruneLocked();
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                "auto logging: ring=" + ROTATE_COUNT + "x" + ROTATE_KBYTES + "KB"
                        + " crash=" + CRASH_ROTATE_COUNT + "x" + CRASH_ROTATE_KBYTES + "KB"
                        + " boot=" + BOOT_LOG_SECONDS + "s/"
                        + BOOT_ROTATE_COUNT + "x" + BOOT_ROTATE_KBYTES + "KB"
                        + " tags=" + LogTagFilter.INSTANCE.getFILTER_SPEC().split(" ").length);
        mLogProcess = startRingChannel(
                LOG_BASE_NAME, buildLogcatCommand(null), "auto logging");
        mCrashLogProcess = startRingChannel(
                CRASH_LOG_BASE_NAME, buildCrashLogcatCommand(null), "auto logging crash");
        startBootLogLocked();
    }

    /**
     * 预创建基名文件、把真实路径填进命令模板、启动 logcat。
     *
     * @param argvTemplate 命令模板；其中 {@link #PATH_PLACEHOLDER} 会被替换为真实路径
     */
    private Process startRingChannel(final String baseName, final String[] argvTemplate,
                                     final String label) {
        final File logFile;
        try {
            logFile = prepareRingFile(baseName);
        } catch (final IOException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, label + " prepare failed", e);
            return null;
        }
        final String[] argv = argvTemplate.clone();
        for (int i = 0; i < argv.length; i++) {
            if (PATH_PLACEHOLDER.equals(argv[i])) {
                argv[i] = logFile.getAbsolutePath();
            }
        }
        return startLogcat(argv, label + " -> " + baseName);
    }

    /**
     * 拉起一个 logcat 采集进程。
     *
     * stdout/stderr 一律丢到 `/dev/null`：`Redirect.DISCARD` 是 Java 9 API，
     * Android 上没有；而无人读取的管道会把子进程写阻塞。
     */
    private static Process startLogcat(final String[] argv, final String label) {
        try {
            final Process process = new ProcessBuilder(argv)
                    .redirectOutput(new File("/dev/null"))
                    .redirectError(new File("/dev/null"))
                    .start();
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, label + " started");
            return process;
        } catch (final IOException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, label + " start failed", e);
            return null;
        }
    }

    /**
     * 启动「启动窗口」段：独立文件、独立进程，跑满 [BOOT_LOG_SECONDS] 秒后自动停止。
     *
     * 这一段**不施加 tag 过滤**：它是"白名单漏登记"与"启动期崩溃"的兜底现场，
     * 只跑 60 秒、最多 16 MB，代价可控。
     */
    private void startBootLogLocked() {
        final File bootFile = new File(
                sLogsDir, BOOT_LOG_PREFIX + System.currentTimeMillis() + ".log");
        try {
            prepareLogsDir();
            if (!bootFile.createNewFile()) {
                Log.w(BuildConfig.LIBRARY_PACKAGE_NAME,
                        "boot log already exists: " + bootFile);
                return;
            }
            setOwnerOnly(bootFile, false);
            final Process process = new ProcessBuilder(
                    buildBootLogcatCommand(bootFile.getAbsolutePath()))
                    .redirectOutput(new File("/dev/null"))
                    .redirectError(new File("/dev/null"))
                    .start();
            mBootLogProcess = process;
            // 捕获 process 本体：即使 cancel 与任务触发并发，也只会停掉这一条，
            // 不会误杀下一轮 server 启动新建的 boot logcat。
            mBootStopFuture = BOOT_SCHEDULER.schedule(
                    () -> stopBootProcess(process), BOOT_LOG_SECONDS, TimeUnit.SECONDS);
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "boot log started: " + bootFile.getName()
                            + " for " + BOOT_LOG_SECONDS + "s");
        } catch (final IOException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "boot log start failed", e);
            // 启动失败不得残留空文件：boot 段按时间戳命名，每次失败都会攒一个 0 字节
            // 文件，而 pruneLocked 只在采集启动时按"最旧优先"淘汰，短时间内看不到效果
            //（上游 b295b8f 对主通道的同类修复，这里对应到 boot 段）。
            deleteIfEmpty(bootFile);
        } catch (final RejectedExecutionException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "boot log schedule failed", e);
            stopProcess(mBootLogProcess);
            mBootLogProcess = null;
            deleteIfEmpty(bootFile);
        }
    }

    /** 只在文件确实为空时删除，避免把已经写进内容的现场证据一起删掉。 */
    private static void deleteIfEmpty(final File file) {
        try {
            if (file.exists() && file.length() == 0L && !file.delete()) {
                Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "cannot remove empty " + file.getName());
            }
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "cleanup empty log failed", t);
        }
    }

    /**
     * 启动窗口段的到点停止：先摘除句柄再 kill，保证 [settleIfExited] 不会把
     * 「正常到点」误判成「异常退出」。
     */
    private void stopBootProcess(final Process process) {
        synchronized (mLock) {
            if (mBootLogProcess == process) {
                mBootLogProcess = null;
                mBootStopFuture = null;
            }
        }
        stopProcess(process);
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "boot log window closed");
    }

    /** 命令模板里代表"真实日志路径"的占位符，由 [startRingChannel] 替换。 */
    static final String PATH_PLACEHOLDER = "<log-path>";

    /**
     * 纯函数：构造主环形采集命令。
     *
     * 抽出来是为了让单测钉住四组不能丢的参数：
     * `-f`（否则 `-r/-n` 不生效）、`-r`（单文件上限）、`-n`（环形文件数）、
     * 以及末尾必须存在的 `*:S`（否则 tag 过滤不生效，采集量回到 875 MB/天）。
     *
     * @param logPath 真实日志路径；传 {@code null} 时返回含 [PATH_PLACEHOLDER] 的模板
     */
    static String[] buildLogcatCommand(final String logPath) {
        final String path = logPath == null ? PATH_PLACEHOLDER : logPath;
        return concat(
                new String[]{
                        "/system/bin/logcat", "-b", "main,system",
                        "-f", path,
                        "-r", Integer.toString(ROTATE_KBYTES),
                        "-n", Integer.toString(ROTATE_COUNT),
                },
                LogTagFilter.filterSpecArgs());
    }

    /** 纯函数：构造崩溃通道命令（`-b crash` 全量，无 tag 过滤）。 */
    static String[] buildCrashLogcatCommand(final String logPath) {
        final String path = logPath == null ? PATH_PLACEHOLDER : logPath;
        return new String[]{
                "/system/bin/logcat", "-b", "crash",
                "-f", path,
                "-r", Integer.toString(CRASH_ROTATE_KBYTES),
                "-n", Integer.toString(CRASH_ROTATE_COUNT),
        };
    }

    /** 纯函数：构造启动窗口段命令（同样必须带 `-f/-r/-n`，且**不加** tag 过滤）。 */
    static String[] buildBootLogcatCommand(final String logPath) {
        return new String[]{
                "/system/bin/logcat", "-b", "main,system,crash",
                "-f", logPath,
                "-r", Integer.toString(BOOT_ROTATE_KBYTES),
                "-n", Integer.toString(BOOT_ROTATE_COUNT),
        };
    }

    private static String[] concat(final String[] head, final String[] tail) {
        final String[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /**
     * 停止并回收全部采集进程（调用方必须已持有 [mLock]）。
     *
     * 先清引用再销毁：停止过程中的并发对账不会命中半完成状态。
     */
    private void shutdownAllLocked(final String reason) {
        final Process main = mLogProcess;
        final Process crash = mCrashLogProcess;
        final Process boot = mBootLogProcess;
        final ScheduledFuture<?> future = mBootStopFuture;
        mLogProcess = null;
        mCrashLogProcess = null;
        mBootLogProcess = null;
        mBootStopFuture = null;
        if (future != null) {
            future.cancel(false);
        }
        if (main == null && crash == null && boot == null) {
            return;
        }
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "auto logging stopped: " + reason);
        stopProcess(main);
        stopProcess(crash);
        stopProcess(boot);
    }

    private static void stopProcess(final Process process) {
        if (process == null) {
            return;
        }
        closeStreams(process);
        process.destroy();
    }

    /**
     * 关闭子进程的三条管道。
     *
     * 这三个流的读端/写端都是本进程的 FD：不关的话每采集轮次泄漏至多 3 个 FD，
     * 直到 ulimit 触顶（与 ActivityManagerLogsObserver 的同类缺陷叠加后尤其明显）。
     */
    private static void closeStreams(final Process process) {
        final Closeable[] streams = new Closeable[]{
                process.getInputStream(), process.getErrorStream(), process.getOutputStream()};
        for (final Closeable stream : streams) {
            try {
                stream.close();
            } catch (final Throwable ignored) {
                // 关闭失败无副作用：子进程退出后 libcore 的 ProcessManager 会兜底回收。
            }
        }
    }

    /**
     * 按「文件数 + 总字节」双阈值淘汰最旧的日志文件。
     *
     * 旧实现在 {@code files.length <= MAX_LOG_FILES} 时直接 return，字节阈值
     * 永远不会生效 —— 1 个 20 GB 的文件也不会被删。这里统一交给纯函数
     * {@link #pruneCount(long[], int, long)} 判定。
     */
    private static void pruneLocked() {
        final File[] files = sLogsDir.listFiles((dir, name) -> isLogFileName(name));
        if (files == null || files.length == 0) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        final long[] sizes = new long[files.length];
        for (int i = 0; i < files.length; i++) {
            sizes[i] = files[i].length();
        }
        final int toDelete = pruneCount(sizes, MAX_LOG_FILES, MAX_LOG_DIR_BYTES);
        int deleted = 0;
        for (int i = 0; i < toDelete; i++) {
            if (files[i].delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "pruned " + deleted + " stale log files");
        }
    }

    /**
     * 纯函数：按「文件数 + 总字节」双阈值，返回需要从最旧端删除的文件数。
     *
     * 抽出来是为了单测能钉住旧实现的致命缺陷：文件数不超限时，字节阈值也必须
     * 生效。两个阈值是**或**关系 —— 任一超限就继续删最旧的。
     *
     * @param sizesOldestFirst 各文件大小，按 lastModified 升序（最旧在前）
     * @param maxFiles         允许保留的最大文件数
     * @param maxBytes         允许保留的最大总字节
     * @return 需要从数组头部删除的文件数
     */
    static int pruneCount(final long[] sizesOldestFirst, final int maxFiles,
                          final long maxBytes) {
        final int n = sizesOldestFirst.length;
        long total = 0L;
        for (final long size : sizesOldestFirst) {
            total += size;
        }
        int deleted = 0;
        while (deleted < n) {
            if (n - deleted <= maxFiles && total <= maxBytes) {
                break;
            }
            total -= sizesOldestFirst[deleted];
            deleted++;
        }
        return deleted;
    }
}
