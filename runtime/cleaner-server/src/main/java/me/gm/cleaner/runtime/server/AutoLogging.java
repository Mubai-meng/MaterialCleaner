package me.gm.cleaner.runtime.server;

import android.os.Bundle;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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

    /**
     * 崩溃通道的**哨兵行**前缀（5.5.16，第 48 批 §4.1）。
     *
     * 缺陷现场：`cleaner-crash.log` 连续多次会话**全程 0 字节**，mtime 停在旧会话，
     * 而同一次会话里 crash buffer 确实有新条目（20:06:25 的 FORTIFY 告警晚于通道启动
     * 2.5 分钟）。同时刻的启动窗口段（`-b main,system,crash`）却把同一批 crash 行
     * 写了进去 —— 所以不是 SELinux、不是权限、不是 logd 不可读。
     *
     * 剩下的唯一解释是 `logcat -f` 的**块缓冲**：crash buffer 没有新条目时几乎不产出
     * 字节流，连 `--------- beginning of crash` 都不落盘。对排障者的后果是
     * 「0 字节」同时代表「没崩溃」和「通道根本没工作」，二者无法区分 ——
     * 正是规则⑨（"日志里没有"≠"没发生"）的另一个方向。
     *
     * 修法（最小、零功能风险）：logcat 启动**之前**先往目标文件写一行哨兵，
     * 再在 [CRASH_PROBE_SECONDS] 秒后校验文件是否增长：
     * - 增长 ⇒ 通道确实在落盘，只是此前没有可写的崩溃；
     * - 未增长 ⇒ 通道静默，写明在文件里，下一批可直接据此判定是否需要改成周期
     *   `logcat -d -b crash` 快照（那才是不依赖 `-f` 缓冲的方案）。
     *
     * 写入时机必须早于 logcat：`logcat -f` 以 `"a"`（append）打开文件，
     * 且写入前会 seek 到 EOF，所以先写的哨兵不会被覆盖，也不会与它争 offset。
     */
    static final String CRASH_SENTINEL_PREFIX = "# crash channel armed";

    /**
     * 重写哨兵前允许的既有字节数。
     *
     * 只在这个范围内才认为「文件里只有上一次的哨兵/诊断行」，可以安全重写；
     * 超过就说明确实写进过崩溃内容（或已轮转出 `.1`），此时**只追加**不覆盖 ——
     * 抹掉崩溃证据比留下多几行哨兵严重得多。
     */
    private static final long CRASH_SENTINEL_RESET_BYTES = 1024L;

    /**
     * 崩溃通道静默自检的延迟（秒）。
     *
     * 取 90 s 而不是更短：crash buffer 的内容取决于设备上**是否真的崩过**，
     * 空闲期本来就该是 0 条；太短的窗口会把「正常空闲」误报成「通道静默」。
     * 90 s 覆盖了 server 启动 + 首轮挂载（实测启动窗 4.2 s、boot 段 60 s），
     * 若这段时间通道健康，之后崩溃时自然会写进来。
     */
    private static final int CRASH_PROBE_SECONDS = 90;

    /**
     * 崩溃通道的兜底快照周期（秒）。第 51 批（6.0.2）。
     *
     * ## 为什么必须加这条兜底
     *
     * 第 49 批加的哨兵 + 90 s 自检**只负责诊断，不负责修复**：它把「通道静默」
     * 写成一行字，然后继续用同一个必然静默的 `logcat -f`。第 51 批真机证据
     * （PLK110 / 6.0.1）证明这个缺陷一直都在：
     * - `cleaner-crash.log` 末尾同时有
     *   `# crash channel armed: wall=1793061994631 ... pid=10194` 与
     *   `# channel silent: logcat wrote 0B within 90s since armed (1810799B)`；
     * - 而同一时段 `logcat -d -b crash -t 2000`（诊断包 commands 侧）**能读到**
     *   `10-27 08:46:27.619 F libc : Fatal signal 6 (SIGABRT) ... pid 8079 (init)`;
     * - `cleaner-crash.log` 里 **10-27 一行都没有**（最后一行停在 10-26 19:41）。
     *
     * 三重证据合起来只剩一个解释：`logcat -f` 对 crash buffer 走块缓冲，
     * buffer 无新条目时不刷盘；等真有崩溃时，那一次写也可能因缓冲/轮转时机
     * 全部丢失。而 `logcat -d`（dump 模式）直接读 buffer，不受块缓冲影响。
     *
     * ## 为什么不干脆停掉 `-f` 只留轮询
     *
     * `-f` 是**实时**落盘，崩溃进程被杀前后那几行只有它能抓到；轮询按周期取样，
     * 两次之间若 buffer 被冲掉就没了。两者互补：`-f` 抢实时，轮询保「一定读得到」。
     * 因此这里**不替换**，只增加一条独立快照通道。
     *
     * 60 s 的选择：crash buffer 默认容量在 256 KB 量级，实测单次崩溃 ~5 KB，
     * 60 s 内足以容纳多次崩溃而不被 logd 覆盖；同时每小时只有 60 次 dump，
     * 对 logd 的压力可忽略（实测 `-d -b crash` 耗时 < 20 ms）。
     */
    private static final int CRASH_SNAPSHOT_SECONDS = 60;

    /**
     * 崩溃快照的固定基名（**不带** `.log` 后缀的轮转形态，改由本类自己滚动）。
     *
     * 命名必须让 {@link #isCrashLogFileName(String)} 认得（它按
     * `cleaner-crash.log` 前缀匹配），这样诊断归档会与 `-f` 那份一同按首尾两段取用，
     * 排障者不必再去 `commands/` 里翻。
     */
    static final String CRASH_SNAPSHOT_NAME = CRASH_LOG_BASE_NAME + ".snapshot";

    /**
     * 快照保留轮数：`cleaner-crash.log.snapshot` + `.1` … `.3`，每份上限
     * [CRASH_SNAPSHOT_MAX_BYTES]。总量 ~1.2 MB，与 `-f` 那份的 4 MB 合计仍在
     * `MAX_LOG_DIR_BYTES`（64 MB）之内。
     */
    private static final int CRASH_SNAPSHOT_ROTATE_COUNT = 4;

    /** 单份快照上限 256 KB —— 与 crash buffer 自身容量同量级，够装下未轮转的全部条目。 */
    private static final long CRASH_SNAPSHOT_MAX_BYTES = 256L * 1024L;

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
     * 单线程守护调度器：负责「60 秒后停掉启动段」「90 秒后自检崩溃通道」
     * 「每 60 秒 dump 一次 crash buffer」这三件定时事务，不创建额外常驻线程池。
     *
     * 保持 static：它是纯粹的定时器，不持有任何实例状态，也不阻止进程退出
     * （daemon 线程）。
     *
     * 三件事务的耗时都是毫秒级（`-d -b crash` 实测 < 20 ms），单线程串行足够；
     * 反过来说，如果某次 dump 卡住，它会顺延后面的 tick 而不会并发堆积 ——
     * 这正是这里要的（宁可丢一拍，不要攒出 N 个并发 logcat）。
     */
    private static final ScheduledExecutorService BOOT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "cleaner-log-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    /** 崩溃快照的定时句柄；非 null 即「应运行」。停采集时必须 cancel。 */
    private ScheduledFuture<?> mCrashSnapshotFuture;

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
        // 崩溃快照：cleaner-crash.log.snapshot 及其 .1 ... .3。
        // 必须**先于**下面的 `.log` 判定：快照名不以 .log 结尾，也过不了
        // isRingShardOf（后缀不是纯数字），漏判会让快照既进不了诊断包、
        // 也不计入 pruneLocked 的字节预算 —— 正是「证据静默消失」的形态。
        if (isCrashSnapshotName(name)) {
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
     * `cleaner-crash.log.snapshot` 与 `cleaner-crash.log.snapshot.N` 的判定。
     *
     * 抽成独立方法是为了让归档、预算、回收三处共用同一口径（规则⑪的同源要求）。
     */
    static boolean isCrashSnapshotName(final String name) {
        if (name == null || !name.startsWith(CRASH_SNAPSHOT_NAME)) {
            return false;
        }
        final String rest = name.substring(CRASH_SNAPSHOT_NAME.length());
        if (rest.isEmpty()) {
            return true;
        }
        if (!rest.startsWith(".")) {
            return false;
        }
        final String digits = rest.substring(1);
        if (digits.isEmpty()) {
            return false;
        }
        for (int i = 0; i < digits.length(); i++) {
            final char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
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
                LOG_BASE_NAME, buildLogcatCommand(null), "auto logging", null);
        mCrashLogProcess = startRingChannel(
                CRASH_LOG_BASE_NAME, buildCrashLogcatCommand(null), "auto logging crash",
                CRASH_SENTINEL_PREFIX);
        startCrashSnapshotLocked();
        startBootLogLocked();
    }

    /**
     * 启动崩溃快照轮询（调用方必须已持有 [mLock]）。
     *
     * 与 `-f` 通道**并行**存在：`-f` 抢实时，轮询保「一定读得到」，二者互补。
     * 首拍延迟一个周期而不是立即执行 —— 启动瞬间 crash buffer 里通常只有
     * 上一次开机的旧条目，立即 dump 会把它们写进本次会话的快照里，反而
     * 干扰「本会话有没有崩」的判断。
     */
    private void startCrashSnapshotLocked() {
        if (mCrashSnapshotFuture != null) {
            return;
        }
        try {
            mCrashSnapshotFuture = BOOT_SCHEDULER.scheduleWithFixedDelay(
                    this::runCrashSnapshot,
                    CRASH_SNAPSHOT_SECONDS,
                    CRASH_SNAPSHOT_SECONDS,
                    TimeUnit.SECONDS);
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "crash snapshot scheduled: every " + CRASH_SNAPSHOT_SECONDS + "s -> "
                            + CRASH_SNAPSHOT_NAME);
        } catch (final RejectedExecutionException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "crash snapshot schedule failed", e);
        }
    }

    /**
     * 快照拍的执行体：捕获一切异常，保证定时任务不会因一次失败被永久取消
     * （`scheduleWithFixedDelay` 在任务抛出未捕获异常后会**静默停止**后续执行 ——
     * 这正是「日志通道悄悄失效」这类缺陷最喜欢的形态）。
     */
    private void runCrashSnapshot() {
        try {
            final File base = new File(sLogsDir, CRASH_SNAPSHOT_NAME);
            final long written = snapshotCrashBuffer(base);
            if (written > 0L) {
                Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                        "crash snapshot written: " + base.getName() + " " + written + "B");
            }
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "crash snapshot tick failed", t);
        }
    }

    /**
     * 预创建基名文件、把真实路径填进命令模板、启动 logcat。
     *
     * @param argvTemplate 命令模板；其中 {@link #PATH_PLACEHOLDER} 会被替换为真实路径
     * @param sentinel     ｜非 null 时在 logcat 启动**之前**向文件写入该前缀的哨兵行，
     *                     并调度一次静默自检（见 [CRASH_SENTINEL_PREFIX]）
     */
    private Process startRingChannel(final String baseName, final String[] argvTemplate,
                                     final String label, final String sentinel) {
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
        if (sentinel != null) {
            armChannel(logFile, sentinel, argv);
        }
        return startLogcat(argv, label + " -> " + baseName);
    }

    /**
     * 写哨兵行并调度静默自检。
     *
     * 顺序很关键：先写哨兵再 startLogcat —— logcat 以 append 模式打开，
     * 若先启动它，文件可能已被它持有且位置未知，追加会出现交错。
     */
    private static void armChannel(final File logFile, final String sentinel,
                                   final String[] argv) {
        final long armedBytes = writeSentinel(logFile, sentinel, argv);
        if (armedBytes <= 0L) {
            // 哨兵写失败（权限/磁盘满）不阻断采集：没有哨兵只是少一层判据，
            // 而 logcat 本身仍可能正常工作。
            return;
        }
        try {
            BOOT_SCHEDULER.schedule(
                    () -> verifyChannel(logFile, armedBytes),
                    CRASH_PROBE_SECONDS, TimeUnit.SECONDS);
        } catch (final RejectedExecutionException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "channel probe schedule failed", e);
        }
    }

    /**
     * 纯函数：构造哨兵行。
     *
     * 抽出来是为了让单测能钉住三个必须出现的字段：`boot=`（开机墙钟起点，
     * 与第 48 批分析侧 `audit_ts − uptime` 的口径一致）、`pid=`（区分 server 实例）、
     * 以及**实际下发的命令行**（否则"通道怎么起的"只能靠读源码猜）。
     *
     * @param prefix           哨兵前缀
     * @param wallMillis       当前墙钟
     * @param elapsedMillis    当前开机后毫秒（`SystemClock.elapsedRealtime()`）
     * @param pid              当前 pid
     * @param commandLine      下发的完整命令行（空格连接）
     */
    static String sentinelLine(final String prefix, final long wallMillis,
                               final long elapsedMillis, final int pid,
                               final String commandLine) {
        final long bootWall = wallMillis - elapsedMillis;
        return prefix + ": wall=" + wallMillis
                + " elapsed=" + elapsedMillis
                + " boot=" + bootWall
                + " pid=" + pid
                + " cmd=[" + commandLine + "]";
    }

    /**
     * 写哨兵行，返回写后文件长度（写入失败返回 -1）。
     *
     * 重置策略见 [CRASH_SENTINEL_RESET_BYTES]：只有「上一次留下的哨兵」这种小文件
     * 才会被整份重写，避免多轮 server 重启后哨兵无界累积；一旦文件里有真实内容
     * 就退化为追加。
     */
    static long writeSentinel(final File logFile, final String prefix, final String[] argv) {
        try {
            final long existing = logFile.length();
            final boolean reset = existing <= CRASH_SENTINEL_RESET_BYTES;
            final String line = sentinelLine(
                    prefix,
                    System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime(),
                    android.os.Process.myPid(),
                    String.join(" ", argv));
            try (FileOutputStream out = new FileOutputStream(logFile, !reset)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            final long armed = logFile.length();
            Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "channel armed: " + logFile.getName() + " reset=" + reset
                            + " bytes=" + armed);
            return armed;
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "channel sentinel write failed", t);
            return -1L;
        }
    }

    /**
     * 静默自检：文件在 [CRASH_PROBE_SECONDS] 秒内没有增长 ⇒ 通道没在落盘。
     *
     * 这里**只读长度 + 追加一行诊断**，不碰任何采集状态，因此可以安全地在定时线程
     * 上跑；server 已退出时最坏也只是往一个没人看的文件里多写一行。
     *
     * 结论写进文件本身（而不是只打 logcat）：`/data/local/tmp/cleaner_logs` 是
     * 磁盘目录，诊断包按首尾两段取用，写在这里的结论下一批一定读得到。
     */
    static void verifyChannel(final File logFile, final long armedBytes) {
        try {
            final long length = logFile.length();
            if (length > armedBytes) {
                Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                        "channel alive: " + logFile.getName()
                                + " +" + (length - armedBytes) + "B in "
                                + CRASH_PROBE_SECONDS + "s");
                return;
            }
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME,
                    "channel silent: " + logFile.getName() + " wrote 0B in "
                            + CRASH_PROBE_SECONDS + "s");
            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(("# channel silent: logcat wrote 0B within "
                        + CRASH_PROBE_SECONDS + "s since armed (" + armedBytes + "B)"
                        + " — if this repeats, -f block buffering is the cause;"
                        + " switch to periodic 'logcat -d -b crash' snapshots\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "channel probe failed", t);
        }
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
     * 纯函数：构造崩溃快照命令。
     *
     * {@code -d}（dump 后立即退出，不进入 follow 模式）是关键 —— 它直接读
     * crash buffer，**不经过** {@code -f} 的块缓冲，这正是绕开本次缺陷的机制。
     * `-t <n>` 限制单次条数，避免极端崩溃风暴下一次 dump 出几十 MB。
     *
     * 抽成独立方法是为了让单测钉住 `-d` 与 `-b crash` 两项：少了任何一个，
     * 快照就会退化成「又一次 follow 采集」或「读错 buffer」。
     */
    static String[] buildCrashSnapshotCommand() {
        return new String[]{
                "/system/bin/logcat",
                "-d",
                "-v", "threadtime",
                "-b", "crash",
                "-t", "2000",
        };
    }

    /**
     * 纯函数：把一次 dump 的结果写进快照文件，返回写入的字节数。
     *
     * 三个分支，顺序不能换：
     * 1. **内容为空** ⇒ 返回 0 且**不动**既有快照（空闲期空 dump 不能把上一次
     *    的真实崩溃抹掉）；
     * 2. **内容与既有快照逐字节相同** ⇒ 返回 0 且不轮转、不写盘。crash buffer
     *    在空闲期不变，dump 出来就是同一份字节；若照常写出，每 60 s 会把同一
     *    份 256 KB 重写一遍（15 MB/h），而 `pruneLocked` 按 mtime 淘汰 ——
     *    最先被挤掉的恰恰是最该保留的 `cleaner.log`。这条空转分支既是省 IO，
     *    也是保住主日志；
     * 3. **内容有变** ⇒ 先轮转（`.N → .N+1`，最旧一份丢弃），再把新内容
     *    **覆盖**写进基名文件。覆盖而非追加：一次 dump 就是 crash buffer 的
     *    全量视图，追加会随每 60 s 重复累积同一批条目（真机 1 小时能攒到 100 MB）。
     *
     * 超过 [CRASH_SNAPSHOT_MAX_BYTES] 时保留**尾部**（最新的条目在尾部，
     * crash buffer 是环形写入，头部最旧）。
     *
     * 覆盖语义的代价是「两次 dump 之间被 logd 冲掉的条目不可恢复」——
     * 那些条目由 `-f` 那条实时通道负责，两条通道互补，见
     * [CRASH_SNAPSHOT_SECONDS] 的注释。
     *
     * @return 写入字节数；空内容或内容未变时返回 0
     */
    static long writeCrashSnapshot(final File baseFile, final byte[] content) {
        if (content == null || content.length == 0) {
            return 0L;
        }
        try {
            final byte[] payload = content.length > CRASH_SNAPSHOT_MAX_BYTES
                    ? Arrays.copyOfRange(content, (int) (content.length - CRASH_SNAPSHOT_MAX_BYTES),
                    content.length)
                    : content;
            // 内容未变 ⇒ 真正的空转：不轮转、不写盘。见方法注释第 2 条。
            if (isSameContent(baseFile, payload)) {
                return 0L;
            }
            if (baseFile.exists() && baseFile.length() > 0L) {
                rotateSnapshots(baseFile);
            }
            try (FileOutputStream out = new FileOutputStream(baseFile, false)) {
                out.write(payload);
                out.flush();
            }
            setOwnerOnly(baseFile, false);
            return payload.length;
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "crash snapshot write failed", t);
            return -1L;
        }
    }

    /**
     * `baseFile` 的内容是否与 `payload` 逐字节相同。
     *
     * 先比长度（O(1) 挡掉绝大多数情形）再逐字节比：crash buffer 的多数字节
     * 在两次 dump 之间是不变的，逐字节比最多 256 KB，耗时远低于一次 logcat 启动。
     *
     * 读失败一律返回 false（当作「不同」）：宁可多写一次，也不要因为一次读
     * 异常就永久跳过写入、把快照冻在旧内容上。
     */
    private static boolean isSameContent(final File baseFile, final byte[] payload) {
        if (!baseFile.exists() || baseFile.length() != payload.length) {
            return false;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(baseFile)) {
            return Arrays.equals(readAllBytes(in), payload);
        } catch (final IOException e) {
            return false;
        }
    }

    /** 快照轮转：`base.3` 丢弃，`base.2 → base.3`，…，`base → base.1`。 */
    private static void rotateSnapshots(final File baseFile) {
        for (int i = CRASH_SNAPSHOT_ROTATE_COUNT - 1; i >= 1; i--) {
            final File older = new File(baseFile.getParentFile(), baseFile.getName() + "." + i);
            final File newer = i == 1
                    ? baseFile
                    : new File(baseFile.getParentFile(), baseFile.getName() + "." + (i - 1));
            if (!newer.exists()) {
                continue;
            }
            if (older.exists() && !older.delete()) {
                // 删不掉就放弃这一轮搬移：宁可少一份最旧快照，也不要把新内容
                // 覆盖到一份还有用的文件上。
                continue;
            }
            if (!newer.renameTo(older)) {
                Log.w(BuildConfig.LIBRARY_PACKAGE_NAME,
                        "crash snapshot rotate failed: " + newer.getName() + " -> "
                                + older.getName());
            }
        }
    }

    /**
     * 一次崩溃快照：dump crash buffer 并并入快照文件。
     *
     * **不抛异常、不改采集状态**，因此可安全地在定时线程上跑。
     * 失败只记一行 W 并返回，绝不影响 `-f` 通道。
     *
     * @return 写入字节数；空 dump 或失败返回 0 / 负数
     */
    static long snapshotCrashBuffer(final File baseFile) {
        Process process = null;
        try {
            process = new ProcessBuilder(buildCrashSnapshotCommand())
                    .redirectError(new File("/dev/null"))
                    .start();
            final byte[] content = readAllBytes(process.getInputStream());
            // dump 是同步完成的，这里等它退出以拿到完整 stdout；
            // 2 秒超时兜底，避免 logd 无响应时把调度线程挂死。
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "crash snapshot timed out");
                return -1L;
            }
            return writeCrashSnapshot(baseFile, content);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "crash snapshot failed", t);
            return -1L;
        } finally {
            if (process != null) {
                closeStreams(process);
                process.destroy();
            }
        }
    }

    /**
     * 读完一个流（Java 8 没有 `InputStream.readAllBytes`）。
     *
     * 上限 [CRASH_SNAPSHOT_MAX_BYTES] × 2：留一倍余量给「读完之后才知道要截断」
     * 的情况，同时防止某个异常的 logcat 产出无界内容把内存撑爆。
     */
    private static byte[] readAllBytes(final java.io.InputStream in) throws IOException {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > CRASH_SNAPSHOT_MAX_BYTES * 2) {
                break;
            }
        }
        return buffer.toByteArray();
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
        // buffer 选择与过滤串都取自 [LogTagFilter]：两处各自硬编码「-b main,system」
        // 在改动时会静默漂移 —— 单测断言的是 LogTagFilter，实际下发的是本方法，
        // 不同源就等于门禁只守住了一半（上游 344de9d 的原始动机）。
        return concat(
                new String[]{
                        "/system/bin/logcat",
                        "-f", path,
                        "-r", Integer.toString(ROTATE_KBYTES),
                        "-n", Integer.toString(ROTATE_COUNT),
                },
                LogTagFilter.mainChannelArgs());
    }

    /** 纯函数：构造崩溃通道命令（`-b crash` 全量，无 tag 过滤）。 */
    static String[] buildCrashLogcatCommand(final String logPath) {
        final String path = logPath == null ? PATH_PLACEHOLDER : logPath;
        return concat(
                new String[]{
                        "/system/bin/logcat",
                        "-f", path,
                        "-r", Integer.toString(CRASH_ROTATE_KBYTES),
                        "-n", Integer.toString(CRASH_ROTATE_COUNT),
                },
                LogTagFilter.crashChannelArgs());
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
        final ScheduledFuture<?> snapshot = mCrashSnapshotFuture;
        mLogProcess = null;
        mCrashLogProcess = null;
        mBootLogProcess = null;
        mBootStopFuture = null;
        mCrashSnapshotFuture = null;
        if (future != null) {
            future.cancel(false);
        }
        // 快照定时器必须在这里取消：它是独立于三个 logcat 进程的第四条采集路径，
        // 不取消的话「关闭保存日志」之后仍会每 60 秒 dump 一次 crash buffer 并写盘。
        if (snapshot != null) {
            snapshot.cancel(false);
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

    /**
     * 日志目录的只读统计（总字节 + 文件数），供设置页展示。
     *
     * <p><b>三种事实必须分开表达，绝不把「读不到」伪装成「零占用」</b>：
     * <ul>
     *   <li>目录不存在 ⇒ {@code available=true, 0 B, 0 个}：开关从未开过，是真的零占用；</li>
     *   <li>目录存在但读不了 / 不是目录 / 遍历中途出错 ⇒ {@code available=false}：
     *       设置页显示「统计暂不可用」，同时明确提示服务未运行或权限异常；</li>
     *   <li>正常 ⇒ {@code available=true} + 实际值。</li>
     * </ul>
     *
     * <p>统计口径与 {@link #pruneLocked()} 一致：只认 {@link #isLogFileName(String)}
     * 认定的本类日志文件（含 {@code cleaner.log.N} / {@code cleaner-crash.log.N}
     * 轮转分片与启动段），目录里若混入其它文件则不计入 —— 否则「显示 0 B 但目录非空」
     * 会让排障者误判成统计坏了。
     *
     * <p>本方法**不创建、不删除、不修改**任何文件，也不启动/停止任何子进程，
     * 因此可以安全地在 Binder 线程直接被调用（设置页那侧仍需放在 IO 线程，
     * 避免主线程同步 Binder 卡顿）。
     */
    public Bundle getLogDirStats() {
        final var result = new Bundle();
        result.putLong("totalBytes", 0L);
        result.putInt("fileCount", 0);
        result.putBoolean("available", false);

        final Path path = sLogsDir.toPath();
        final BasicFileAttributes dirAttrs;
        try {
            dirAttrs = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (final NoSuchFileException e) {
            // 目录明确不存在：真零占用，不是故障
            result.putBoolean("available", true);
            return result;
        } catch (final Exception e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "getLogDirStats: 目录状态不可读", e);
            return result;
        }
        if (!dirAttrs.isDirectory()) {
            // 路径被换成普通文件/符号链接（例如被其它工具占位），无法按目录统计
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "getLogDirStats: 日志路径不是目录");
            return result;
        }

        long totalBytes = 0L;
        int fileCount = 0;
        try (var stream = Files.list(path)) {
            final var iterator = stream.iterator();
            while (iterator.hasNext()) {
                final Path file = iterator.next();
                final Path fileName = file.getFileName();
                if (fileName == null || !isLogFileName(fileName.toString())) {
                    continue;
                }
                try {
                    final var attrs = Files.readAttributes(file, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isRegularFile()) {
                        continue;
                    }
                    totalBytes += attrs.size();
                    fileCount++;
                } catch (final NoSuchFileException e) {
                    // 遍历期间被 logcat 轮转或 pruneLocked 删掉：跳过，不算故障
                } catch (final Exception e) {
                    // 单个文件读不到 ⇒ 结果不完整，宁可报不可用也不给半份数字
                    result.putLong("totalBytes", totalBytes);
                    result.putInt("fileCount", fileCount);
                    return result;
                }
            }
        } catch (final Exception e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "getLogDirStats: 目录遍历失败", e);
            result.putLong("totalBytes", totalBytes);
            result.putInt("fileCount", fileCount);
            return result;
        }

        result.putLong("totalBytes", totalBytes);
        result.putInt("fileCount", fileCount);
        result.putBoolean("available", true);
        return result;
    }
}
