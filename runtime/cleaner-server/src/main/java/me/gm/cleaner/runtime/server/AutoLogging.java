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

import me.gm.cleaner.runtime.server.BuildConfig;


public class AutoLogging {
    public static final int MODE_CONTINUOUSLY = 1;
    private static final File sLogsDir = new File("/data/local/tmp/cleaner_logs");

    /**
     * 环形日志的固定基名。
     *
     * 旧实现每次 server 启动都用 {@code createTempFile} 新建一个带时间戳的文件，
     * 而淘汰只在**启动瞬间**跑一次 ⇒ 单次会话内的活动文件没有任何上限。
     * 本机 logcat 实测 ~700–1500 行/s、~177 B/行（≈120–265 KB/s），
     * 一个会话就能写出 10–20 GB/天；而 `/data/local/tmp` 在本机是 f2fs `/data`
     * 上的普通目录（不是 tmpfs），这些日志会真实占用用户存储且重启不消失。
     *
     * 改为固定基名 + logcat 原生轮转（`-r`/`-n`）后，磁盘占用由 logcat 自己
     * 硬性封顶，不依赖任何 Java 侧定时器。
     */
    static final String LOG_BASE_NAME = "cleaner.log";

    /**
     * 「启动窗口」专用日志段的前缀。
     *
     * 主环形是**滚动**的：启动期那几秒会在几分钟内被新日志覆盖，而诊断包又只保留
     * 每个文件尾部 512 KB。实测挂载失败（`MOUNT.ZYGOTE.*`）恰好发生在 server 启动
     * 后 1 秒内，等用户生成诊断包时早已被挤掉。这里额外开一个只跑
     * [BOOT_LOG_SECONDS] 秒的独立段，专门保留「启动 + 首分钟内」的日志。
     */
    static final String BOOT_LOG_PREFIX = "cleaner-boot-";

    /** 单个文件轮转阈值（KB）。`logcat -r` 的单位就是 KB。 */
    private static final int ROTATE_KBYTES = 4096;      // 4 MB

    /** 环形文件数（含基名文件）。`-n 8` ⇒ cleaner.log + cleaner.log.1 … .7。 */
    private static final int ROTATE_COUNT = 8;

    /** 启动窗口段：跑 60 秒，最多 2 × 8 MB = 16 MB（峰值 ~265 KB/s 时仍覆盖 60 s）。 */
    private static final int BOOT_LOG_SECONDS = 60;
    private static final int BOOT_ROTATE_KBYTES = 8192;
    private static final int BOOT_ROTATE_COUNT = 2;

    /**
     * 磁盘侧兜底阈值：文件数 + 总字节。
     *
     * 正常运行时主环形封顶 8 × 4 MB = 32 MB，启动段封顶 2 × 8 MB = 16 MB；
     * 这里的 64 MB 只用于清理旧版本遗留的 `*.log`（时间戳命名）以及异常情况下
     * 跑飞的单个文件。注意：{@link #pruneLocked()} 只在 server 启动时调用，
     * **不是**会话内的实时看门狗；会话内的硬上限完全由 `logcat -r/-n` 保证。
     */
    private static final int MAX_LOG_FILES = ROTATE_COUNT + BOOT_ROTATE_COUNT;
    private static final long MAX_LOG_DIR_BYTES = 64L * 1024 * 1024;

    /**
     * 当前由本类持有的 logcat 子进程。
     *
     * 原来的写法丢弃了 {@code start()} 的返回值，句柄无处可 kill：server 每重启一次
     * 就多一条永不退出的 logcat（旧的还继续写旧文件）。持有它是为了让
     * {@link #destroy()} 能真正杀掉。
     */
    private static final Object sLock = new Object();
    private static volatile Process sLogcat;

    /** 启动窗口段的 logcat；与主环形相互独立。 */
    private static volatile Process sBootLogcat;
    private static volatile ScheduledFuture<?> sBootStopFuture;

    /** 单线程守护调度器：只负责 60 秒后停掉启动段，不创建额外常驻线程池。 */
    private static final ScheduledExecutorService sBootScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "cleaner-boot-log-stop");
                thread.setDaemon(true);
                return thread;
            });

    public AutoLogging(final android.content.pm.PackageInfo packageInfo) {
    }

    /**
     * 目录内的日志文件名判定：环形基名、其数字分片、启动窗口段，以及旧版本的时间戳 `*.log`。
     *
     * 诊断归档也用它，避免两处口径漂移 —— 否则 `cleaner.log.1 … .7` 会被
     * `endsWith(".log")` 漏掉，归档里只剩正在写的那一个文件。
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
            // 环形基名 cleaner.log + 旧版 createTempFile 生成的 <ts>-<rand>.log
            return true;
        }
        if (!name.startsWith(LOG_BASE_NAME + ".")) {
            return false;
        }
        final String suffix = name.substring(LOG_BASE_NAME.length() + 1);
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
     * 预创建环形基名文件并收紧权限。
     *
     * `logcat -f` 由 logcat 自己打开文件，所以这里必须先建好并 chmod 0600；
     * 目录本身是 0700，双重保护。轮转产生的 `cleaner.log.N` 由 logcat 创建，
     * 权限受目录 0700 兜底。
     */
    private static File prepareRingFile() throws IOException {
        final var dir = prepareLogsDir();
        final var logFile = new File(dir, LOG_BASE_NAME);
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
     * 启动环形采集 + 启动窗口段。
     *
     * 关键点：`-f` 让 logcat 直接写文件，`-r 4096 -n 8` 让 logcat 自己轮转并
     * 覆盖最旧分片，因此**不需要**再 `redirectOutput` 到同一个文件（那会和
     * logcat 的写入 FD 打架）。stdout/stderr 一律 DISCARD，避免无人读取的
     * 管道把子进程写阻塞。
     */
    private void grabLogs() {
        synchronized (sLock) {
            // 幂等：先回收上一轮残留。grabLogs 只有一个调用点，真出现并发/异常重启时
            // 这一步保证同一时刻至多一条 logcat 被本类持有。
            stopLocked();
            pruneLocked();
            final File logFile;
            try {
                logFile = prepareRingFile();
            } catch (final IOException e) {
                Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "grabLogs failed", e);
                return;
            }
            try {
                sLogcat = new ProcessBuilder(buildLogcatCommand(logFile.getAbsolutePath()))
                        // `Redirect.DISCARD` 是 Java 9 API，Android 上没有；
                        // 用 /dev/null 等价地丢弃 stdout/stderr，避免无人读取的管道阻塞子进程。
                        .redirectOutput(new File("/dev/null"))
                        .redirectError(new File("/dev/null"))
                        .start();
            } catch (final IOException e) {
                Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "grabLogs failed", e);
            }
            startBootLogLocked();
        }
    }

    /**
     * 启动「启动窗口」段：独立文件、独立进程，跑满 [BOOT_LOG_SECONDS] 秒后自动停止。
     *
     * 与主环形的区别：
     * - 文件名带时间戳（每个 server 会话一份），归档按**头部**读取；
     * - 只跑 60 秒，16 MB 上限足够覆盖启动 + 首分钟（峰值 ~265 KB/s 也不会被滚掉）；
     * - 不参与主环形的固定基名轮转，避免把启动期日志挤掉。
     */
    private static void startBootLogLocked() {
        final File bootFile = new File(
                sLogsDir, BOOT_LOG_PREFIX + System.currentTimeMillis() + ".log");
        try {
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
            sBootLogcat = process;
            // 捕获 process 本体：即使 cancel 与任务触发并发，也只会停掉这一条，
            // 不会误杀下一轮 server 启动新建的 boot logcat。
            sBootStopFuture = sBootScheduler.schedule(
                    () -> stopProcess(process), BOOT_LOG_SECONDS, TimeUnit.SECONDS);
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "boot log started: " + bootFile + " for " + BOOT_LOG_SECONDS + "s");
        } catch (final IOException | RejectedExecutionException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "boot log start failed", e);
            stopBootLocked();
        }
    }

    /**
     * 纯函数：构造环形采集命令。
     *
     * 抽出来是为了让单测钉住三个不能丢的参数：
     * `-f`（否则 `-r/-n` 不生效）、`-r`（单文件上限）、`-n`（环形文件数）。
     * 少任何一个，日志都会退回无界增长。
     */
    static String[] buildLogcatCommand(final String logPath) {
        return new String[]{
                "/system/bin/logcat", "-b", "main,system,crash",
                "-f", logPath,
                "-r", Integer.toString(ROTATE_KBYTES),
                "-n", Integer.toString(ROTATE_COUNT),
        };
    }

    /** 纯函数：构造启动窗口段命令（同样必须带 `-f/-r/-n`）。 */
    static String[] buildBootLogcatCommand(final String logPath) {
        return new String[]{
                "/system/bin/logcat", "-b", "main,system,crash",
                "-f", logPath,
                "-r", Integer.toString(BOOT_ROTATE_KBYTES),
                "-n", Integer.toString(BOOT_ROTATE_COUNT),
        };
    }

    /**
     * 停止当前的 logcat 采集；由 {@code CleanerServer.onDestroy()} 调用，可重复执行。
     */
    public static void destroy() {
        synchronized (sLock) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        stopBootLocked();
        final Process process = sLogcat;
        sLogcat = null;
        stopProcess(process);
    }

    private static void stopBootLocked() {
        final ScheduledFuture<?> future = sBootStopFuture;
        sBootStopFuture = null;
        if (future != null) {
            future.cancel(false);
        }
        final Process process = sBootLogcat;
        sBootLogcat = null;
        stopProcess(process);
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

    public void registerBootShutdownReceiver(int mode) {
        if (mode == MODE_CONTINUOUSLY) {
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "auto logging ring: " + ROTATE_COUNT + " x " + ROTATE_KBYTES
                            + " KB -> " + new File(sLogsDir, LOG_BASE_NAME)
                            + "; boot segment: " + BOOT_LOG_SECONDS + "s, "
                            + BOOT_ROTATE_COUNT + " x " + BOOT_ROTATE_KBYTES + " KB");
            grabLogs();
        }
    }
}
