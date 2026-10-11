package me.gm.cleaner.runtime.server;

import android.os.Build;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.Comparator;

import me.gm.cleaner.runtime.server.BuildConfig;


public class AutoLogging {
    public static final int MODE_CONTINUOUSLY = 1;
    private static final File sLogsDir = new File("/data/local/tmp/cleaner_logs");

    /**
     * 磁盘侧双阈值：文件数 + 总字节（P0-6）。
     *
     * server 每次重启都会新建一个日志文件，而 logcat 输出是持续的、落在 tmpfs 上。
     * 没有淘汰的话，几重启服就能把分区写满，且 FD 与残留 logcat 进程一起累积。
     */
    private static final int MAX_LOG_FILES = 8;
    private static final long MAX_LOG_DIR_BYTES = 32L * 1024 * 1024;

    /**
     * 当前由本类持有的 logcat 子进程。
     *
     * 原来的写法丢弃了 {@code start()} 的返回值，句柄无处可 kill：server 每重启一次就多一条
     * 永不退出的 logcat（旧的还继续写旧文件）。持有它是为了让 {@link #destroy()} 能真正杀掉。
     */
    private static final Object sLock = new Object();
    private static volatile Process sLogcat;

    public AutoLogging(final android.content.pm.PackageInfo packageInfo) {
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

    private static File createLogFile(final Object logName) throws IOException {
        final var dir = prepareLogsDir();
        final var logFile = Files.createTempFile(
                dir.toPath(), logName.toString() + "-", ".log").toFile();
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

    private void grabLogs(final Object logName, final String cmd) {
        synchronized (sLock) {
            // 幂等：先回收上一轮残留。grabLogs 只有一个调用点，真出现并发/异常重启时
            // 这一步保证同一时刻至多一条 logcat 被本类持有。
            stopLocked();
            pruneLocked();
            final File logFile;
            try {
                logFile = createLogFile(logName);
            } catch (final IOException e) {
                Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "grabLogs failed", e);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    sLogcat = new ProcessBuilder("sh", "-c", cmd)
                            .redirectOutput(logFile)
                            .start();
                } catch (final IOException e) {
                    Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "grabLogs failed", e);
                }
            }
        }
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
        final Process process = sLogcat;
        sLogcat = null;
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

    /** 按「文件数 + 总字节」双阈值淘汰最旧的 {@code *.log}。 */
    private static void pruneLocked() {
        final File[] files = sLogsDir.listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null || files.length <= MAX_LOG_FILES) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0L;
        for (final File file : files) {
            total += file.length();
        }
        int deleted = 0;
        for (final File file : files) {
            if (files.length - deleted <= MAX_LOG_FILES && total <= MAX_LOG_DIR_BYTES) {
                break;
            }
            final long size = file.length();
            if (file.delete()) {
                deleted++;
                total -= size;
            }
        }
        if (deleted > 0) {
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "pruned " + deleted + " stale log files");
        }
    }

    public void registerBootShutdownReceiver(int mode) {
        if (mode == MODE_CONTINUOUSLY) {
            grabLogs(System.currentTimeMillis(),
                    "/system/bin/logcat -b main,system,crash");
        }
    }
}
