package me.gm.cleaner.runtime.mediaprovider.hook.media;

import me.gm.cleaner.runtime.mediaprovider.hook.bridge.EventSignalCoalescer;
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookDataBusBridge;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.HookPolicyCache;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.InaccessibleEvalStats;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.ReadOnlyEvalStats;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.FileUtils;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import me.gm.cleaner.runtime.mediaprovider.hook.xp.HookParam;
import me.gm.cleaner.runtime.mediaprovider.hook.xp.XpCompat;

import me.gm.cleaner.core.storage.redirect.databus.DataBus;
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.AbstractGuardedHook;
import org.json.JSONObject;
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol;

/**
 * MediaProvider insertFile Hook。
 *
 * <p>继承 {@link AbstractGuardedHook} 获得统一防护：任何 handler 异常不外抛、
 * 参数回滚、每方法熔断 —— 与 FuseJavaGate 同进程统一异常策略。
 * 本 Hook 对 args 槽位的修改只发生在 {@link MediaStore.MediaColumns#DATA}
 * 重定向一步且位于方法末尾，异常路径上由 {@link AbstractGuardedHook} 的
 * 槽位快照兜底即可，无需额外 guardedArgIndexes（传空数组）；
 * {@link #ensureUniqueFileColumns} 内部以「先算后写」隔离 ContentValues 写入，
 * 中途异常时宿主拿到未污染的 values。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class InsertHooker extends AbstractGuardedHook {
    private final static String DIRECTORY_THUMBNAILS = ".thumbnails";

    private final static int IMAGES_MEDIA = 1;
    private final static int IMAGES_MEDIA_ID = 2;
    private final static int IMAGES_MEDIA_ID_THUMBNAIL = 3;
    private final static int IMAGES_THUMBNAILS = 4;
    private final static int IMAGES_THUMBNAILS_ID = 5;

    private final static int AUDIO_MEDIA = 100;
    private final static int AUDIO_MEDIA_ID = 101;
    private final static int AUDIO_MEDIA_ID_GENRES = 102;
    private final static int AUDIO_MEDIA_ID_GENRES_ID = 103;
    private final static int AUDIO_GENRES = 106;
    private final static int AUDIO_GENRES_ID = 107;
    private final static int AUDIO_GENRES_ID_MEMBERS = 108;
    private final static int AUDIO_GENRES_ALL_MEMBERS = 109;
    private final static int AUDIO_PLAYLISTS = 110;
    private final static int AUDIO_PLAYLISTS_ID = 111;
    private final static int AUDIO_PLAYLISTS_ID_MEMBERS = 112;
    private final static int AUDIO_PLAYLISTS_ID_MEMBERS_ID = 113;
    private final static int AUDIO_ARTISTS = 114;
    private final static int AUDIO_ARTISTS_ID = 115;
    private final static int AUDIO_ALBUMS = 116;
    private final static int AUDIO_ALBUMS_ID = 117;
    private final static int AUDIO_ARTISTS_ID_ALBUMS = 118;
    private final static int AUDIO_ALBUMART = 119;
    private final static int AUDIO_ALBUMART_ID = 120;
    private final static int AUDIO_ALBUMART_FILE_ID = 121;

    private final static int VIDEO_MEDIA = 200;
    private final static int VIDEO_MEDIA_ID = 201;
    private final static int VIDEO_MEDIA_ID_THUMBNAIL = 202;
    private final static int VIDEO_THUMBNAILS = 203;
    private final static int VIDEO_THUMBNAILS_ID = 204;

    private final static int DOWNLOADS = 800;
    private final static int DOWNLOADS_ID = 801;

    private final MediaProviderHook mHook;
    private final ClassLoader mClassLoader;

    public InsertHooker(MediaProviderHook hook, ClassLoader classLoader) {
        super("InsertHooker", "insertFile", new int[0]);
        mHook = hook;
        mClassLoader = classLoader;
    }

    private final static Pattern KNOWN_APP_DIR_PATHS = Pattern.compile(
            "(?i)(^/storage/[^/]+/(?:([0-9]+)/)?Android/(?:data|media|obb|sandbox)/)([^/]+)(/.*)?");

    /**
     * "Skipping - on FUSE thread" 的日志节流窗口。
     *
     * FUSE 线程上的 insert 是常态（实测 43 条 / 150s），逐条打 D 级日志既占
     * logd 配额（ColorOS LOG_FLOWCTRL 每进程 300 行）也没有信息量 ——
     * 跳过这个事实本身不变，变的只有条数。改为每窗口放行一条并带抑制计数。
     */
    private final static long SKIP_LOG_INTERVAL_MS = 30_000L;
    private static volatile long sLastSkipLogElapsed = 0L;
    private static volatile long sSkipLogSuppressed = 0L;

    /**
     * 审计日志按包节流窗口。
     *
     * <p>审计要的是「哪些应用在通过 MediaStore 建条目、建到哪里」，不是逐条明细；
     * 而 ColorOS 的 LOG_FLOWCTRL 按进程丢日志（300 行 / 进程），逐条打会把本模块
     * 真正要紧的行挤掉（第49批已因同类原因吃过亏）。故每包每窗口只放行一条，
     * 并携带本窗口的抑制计数。
     */
    private final static long AUDIT_LOG_INTERVAL_MS = 10_000L;
    /**
     * packageName -> {上次放行时刻, 抑制计数}。
     *
     * 包数量有天然上界（设备已安装应用数），但仍设硬上限，避免异常输入撑爆内存。
     */
    private static final ConcurrentHashMap<String, long[]> sAuditThrottle =
            new ConcurrentHashMap<>();
    private final static int AUDIT_THROTTLE_MAX_ENTRIES = 512;

    private String extractPathOwnerPackageName(String path) {
        final var m = KNOWN_APP_DIR_PATHS.matcher(path);
        if (m.matches()) {
            return m.group(3);
        }
        return null;
    }

    @Override
    protected void handleBefore(HookParam param) throws Throwable {
        if (mHook.isFuseThread()) {
            final long now = SystemClock.elapsedRealtime();
            if (now - sLastSkipLogElapsed >= SKIP_LOG_INTERVAL_MS) {
                Log.d("MC_REDIRECT", "[InsertHooker] Skipping - on FUSE thread"
                        + " (suppressed=" + sSkipLogSuppressed + " since the last line)");
                sLastSkipLogElapsed = now;
                sSkipLogSuppressed = 0L;
            } else {
                sSkipLogSuppressed++;
            }
            return;
        }
        /** ARGUMENTS */
        int match;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            match = (int) param.args[2];
        } else {
            match = (int) param.args[1];
        }
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            uri = (Uri) param.args[3];
        } else {
            uri = (Uri) param.args[2];
        }
        Bundle extras;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            extras = (Bundle) param.args[4];
        } else {
            extras = Bundle.EMPTY;
        }
        ContentValues values;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            values = (ContentValues) param.args[5];
        } else {
            values = (ContentValues) param.args[3];
        }
        int mediaType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mediaType = (int) param.args[6];
        } else {
            mediaType = (int) param.args[4];
        }

        /** PARSE */
        final var mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
        final var wasPathEmpty = wasPathEmpty(values);
        if (wasPathEmpty) {
            // Generate path when undefined
            ensureUniqueFileColumns(param.thisObject, match, uri, values, mimeType);
        }
        final var data = values.getAsString(MediaStore.MediaColumns.DATA);
        if (wasPathEmpty) {
            // Restore to allow mkdir
            values.remove(MediaStore.MediaColumns.DATA);
        }

        /** REDIRECT */
        final var callingPkg = mHook.getCallingPackage(param.thisObject);
        final var originalData = data;

        // ══════════════════════════════════════════════════════════════
        //  一次求值，两处复用
        //
        //  「不可访问」判定既用于拒绝决策，也用于审计落款。若不集中求值，
        //  两条路径各调一次 isInaccessible 会让 InaccessibleEvalStats.evaluated
        //  凭空翻倍，把「规则到底被判了几次」这个关键口径污染掉 —— 而那个计数
        //  正是历批用来区分「没求值 / 求值没命中 / 命中没生效」的唯一证据。
        //
        //  两个开关都关时完全不算：isReadOnly / isInaccessible 每次都会打
        //  PMS Binder（已知技术债），热路径上不能白跑。
        // ══════════════════════════════════════════════════════════════
        final boolean denyEnabled = HookPolicyCache.INSTANCE.getDenyMediaInsertWrites();
        final boolean auditEnabled = HookPolicyCache.INSTANCE.getAuditMediaInsert();
        final String inaccessibleRule =
                (denyEnabled || auditEnabled)
                        ? resolveInaccessibleRule(callingPkg, data)
                        : null;

        /**
         * DENY（默认关闭）：命中「不可访问」或「只读」规则时拒绝本次写入。
         *
         * 必须在路径重定向**之前**、用应用可见的原始路径判定 —— 与
         * FuseJavaGate.rejectInaccessible 同口径：重定向后路径落在
         * /storage/emulated/0/Android/data/<pkg>/… 下，拿它比规则永远匹配不上。
         */
        if (denyEnabled && denyMediaInsert(param, callingPkg, data,
                mediaType, mimeType, inaccessibleRule)) {
            return;
        }

        final var localMountedPath = HookPolicyCache.INSTANCE.getMountedPath(callingPkg, data);
        if (localMountedPath != null && !data.equals(localMountedPath) &&
                TextUtils.isEmpty(extractPathOwnerPackageName(localMountedPath))) {
            values.put(MediaStore.MediaColumns.DATA, localMountedPath);
            Log.i("MC_REDIRECT", "[InsertHooker] PATH REDIRECTED (local cache): " + originalData + " -> " + localMountedPath);
            emitRedirectNotice(callingPkg, data, localMountedPath, "INSERT");
            auditMediaInsert(callingPkg, data, mediaType, mimeType, "REDIRECTED", inaccessibleRule);
            return;
        }

        // ══════════════════════════════════════════════════════════════
        //  审计（默认关闭）：未命中重定向规则时留一条痕迹
        //
        //  历史行为是这里直接静默返回 —— 于是「应用通过 MediaStore 建了条目」
        //  这件事在日志里完全不可见，无论发生与否都看不出来（第52批根因）。
        //  审计打开后补一条日志，并顺带标注该路径是否落在「不可访问」规则下，
        //  用来回答「应用能不能绕过挂载、直接用媒体库写入本该看不见的目录」。
        // ══════════════════════════════════════════════════════════════
        auditMediaInsert(callingPkg, data, mediaType, mimeType, "PASS_THROUGH", inaccessibleRule);
    }

    /**
     * 拒绝一次媒体库写入。
     *
     * <p><b>两种规则、两种语义：</b>
     * <ul>
     *   <li><b>不可访问</b> —— 连读都不允许，写入更不允许；目标是让应用完全
     *       识别不到该路径（FUSE 侧回 ENOENT，应用看到的是"不存在"）。</li>
     *   <li><b>只读</b> —— 允许读、拒绝写；本次是 insert（写操作），命中即拒绝。</li>
     * </ul>
     *
     * <p><b>为什么用 {@code setResult(null)} 而不是 {@code setThrowable}：</b>
     * 设备实证（一加15 / Android 17，dexsql 查 `insertFile` 两个重载）返回值一律是
     * {@code Landroid/net/Uri;}，而宿主自身在 `SQLiteConstraintException` 且
     * targetSdk&lt;30 时就是 {@code return null}（反编译 5150–5156 行）—— 也就是说
     * <b>null 是宿主认可的合法返回值</b>，ContentResolver 收到后按"插入失败"处理，
     * 不会崩。反之抛受检异常会经 Binder 包装成 RuntimeException 抛给应用，
     * 可能直接崩掉调用方，代价远大于"插入返回空"。
     *
     * <p>短路语义：{@code setResult} 一旦设置，{@link me.gm.cleaner.runtime.mediaprovider.hook.bridge.AbstractGuardedHook}
     * 就不再 {@code chain.proceed()}，宿主 insertInternal 完全不执行 ⇒ 既不建数据库行、
     * 也不建文件、也不触发扫描。
     *
     * @return 已拒绝返回 true，调用方应立即 return
     */
    private boolean denyMediaInsert(final HookParam param, final String callingPkg,
                                    final String data, final int mediaType,
                                    final String mimeType, final String inaccessibleRule) {
        // 与 QueryHooker / 审计同口径：被 deny 的包不参与任何策略判定。
        if (TextUtils.isEmpty(callingPkg) || HookPolicyCache.INSTANCE.isDenied(callingPkg)) {
            return false;
        }
        // data 为空表示应用没指定目标路径、由宿主生成默认路径
        // （wasPathEmpty 分支）—— 那不是应用想写的目标，判它没有意义，
        // 而且此时 values 里的 DATA 已被移除，拿它比规则只会误伤。
        if (TextUtils.isEmpty(data)) {
            return false;
        }
        if (inaccessibleRule != null) {
            InaccessibleEvalStats.INSTANCE.recordDenial(InaccessibleEvalStats.DenyPoint.INSERT);
            Log.i("MC_REDIRECT", "[InsertHooker] INSERT DENIED by inaccessible rule: " + data
                    + " (rule=" + inaccessibleRule + ", pkg=" + callingPkg + ")");
            auditMediaInsert(callingPkg, data, mediaType, mimeType,
                    "DENIED_INACCESSIBLE", inaccessibleRule);
            param.setResult(null);
            return true;
        }
        if (HookPolicyCache.INSTANCE.isReadOnly(callingPkg, data)) {
            ReadOnlyEvalStats.INSTANCE.recordDenial(ReadOnlyEvalStats.DenyPoint.INSERT);
            Log.i("MC_REDIRECT", "[InsertHooker] INSERT DENIED by read-only rule: " + data
                    + " (pkg=" + callingPkg + ")");
            auditMediaInsert(callingPkg, data, mediaType, mimeType,
                    "DENIED_READ_ONLY", inaccessibleRule);
            param.setResult(null);
            return true;
        }
        return false;
    }

    /**
     * 解析「不可访问」命中的规则路径。
     *
     * <p>{@code isInaccessible} 的 Kotlin 形参非空，传 null 会 NPE ⇒ 这里先挡住空值。
     * 返回 null 表示未命中或无需判定。
     */
    private static String resolveInaccessibleRule(final String callingPkg, final String data) {
        if (TextUtils.isEmpty(callingPkg) || TextUtils.isEmpty(data)) {
            return null;
        }
        return HookPolicyCache.INSTANCE.isInaccessible(callingPkg, data);
    }

    /**
     * 媒体库写入审计：记录一次 insertFile 的事实与策略命中情况。
     *
     * <p>受 {@link HookPolicyCache#getAuditMediaInsert()} 总开关控制；关闭时
     * 立即返回，热路径开销只有一次 volatile 读。
     *
     * <p><b>只观测，不拦截</b>：本方法绝不调用 {@code param.setResult} /
     * {@code setThrowable}，因此打开审计不会改变任何应用的写入结果。
     * 拒绝写入是另一条独立路径（{@link #denyMediaInsert}），由另一个开关控制。
     *
     * @param callingPkg       调用方包名
     * @param data             应用可见的原始路径（重定向**前**，规则匹配用的就是它）
     * @param mediaType        insertFile 的 mediaType 入参
     * @param mimeType         values 里的 mime_type
     * @param outcome          DENIED_INACCESSIBLE / DENIED_READ_ONLY /
     *                         REDIRECTED / PASS_THROUGH
     * @param inaccessibleRule 调用方已算好的「不可访问」命中规则；null 表示未命中。
     *                         <b>由调用方传入而非在此重算</b>：重复调用 isInaccessible
     *                         会让 InaccessibleEvalStats.evaluated 凭空翻倍。
     */
    /**
     * 「这条文件系统事件来自媒体库写入」的标志位（`flags` 最高位区）。
     *
     * <p>用途：让「文件系统记录」界面把「某某应用通过媒体存储创建了某文件」与
     * 普通 FUSE 文件事件区分开。位选择与约束见 {@link #emitFileSystemRecord}。
     *
     * <p>⚠️ 不能单独使用：{@code getPathWithEvent} 对未知 flags 会抛
     * {@code IllegalArgumentException}，因此写入时**强制叠加** {@code FileObserver.CREATE}。
     */
    private static final int FLAG_MEDIA_INSERT = 0x20000000;

    /**
     * 把一次媒体库写入投递到「文件系统记录」。
     *
     * <p><b>走既有 {@code EVENT_FILESYSTEM} 队列</b>，不新增队列/表/AIDL 方法：
     * 「文件系统记录」界面的数据源就是 {@code filesystem.db}，由
     * {@code FileSystemObserver} 从该队列消费后写入。新增旁路会导致两套写入逻辑、
     * 两处节流与两种去重，长期必然漂移。
     *
     * <p><b>flags 语义</b>：{@link #FLAG_MEDIA_INSERT} 标记来源，并强制叠加
     * {@code FileObserver.CREATE}（值 0x100）—— 既表达"创建了一个文件"，
     * 又保证 {@code getPathWithEvent} 至少命中一个既有分支而不会抛异常。
     *
     * <p><b>失败不影响主流程</b>：整段 try/catch 吞掉异常。审计是观测能力，
     * 绝不能因为它让宿主写入失败。
     *
     * @param callingPkg 调用方包名
     * @param data       应用可见的原始路径（重定向**前**）
     */
    private static void emitFileSystemRecord(final String callingPkg, final String data) {
        if (TextUtils.isEmpty(callingPkg) || TextUtils.isEmpty(data)) {
            return;
        }
        try {
            final var event = new JSONObject();
            event.put("schemaVersion", 1);
            event.put("timeMillis", System.currentTimeMillis());
            event.put("packageName", callingPkg);
            event.put("path", data);
            // CREATE(0x100) 必须叠加：getPathWithEvent 对未知 flags 会抛异常。
            event.put("flags", FLAG_MEDIA_INSERT | 0x100);
            event.put("sourceLayer", "MEDIA_INSERT");
            event.put("methodName", "insertFile");
            final var seq = HookDataBusBridge.INSTANCE.writeEvent(
                    DataBusProtocol.EVENT_FILESYSTEM, event.toString());
            if (seq >= 0) {
                // 与 FuseJavaGate 同口径：写事件后发 signal，使消费者本轮不跳过目录扫描。
                EventSignalCoalescer.INSTANCE.signal(
                        DataBusProtocol.SIGNAL_FILESYSTEM_EVENTS_CHANGED);
            }
        } catch (Exception e) {
            Log.e("MC_REDIRECT", "[InsertHooker] file system record write failed", e);
        }
    }

    private static void auditMediaInsert(final String callingPkg, final String data,
                                         final int mediaType, final String mimeType,
                                         final String outcome,
                                         final String inaccessibleRule) {
        if (!HookPolicyCache.INSTANCE.getAuditMediaInsert()) {
            return;
        }
        // 与 QueryHooker 同口径：被 deny 的包不参与任何策略判定。
        if (TextUtils.isEmpty(callingPkg) || HookPolicyCache.INSTANCE.isDenied(callingPkg)) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final long[] slot = sAuditThrottle.computeIfAbsent(
                callingPkg, k -> new long[]{0L, 0L});
        synchronized (slot) {
            if (slot[0] != 0L && now - slot[0] < AUDIT_LOG_INTERVAL_MS) {
                slot[1]++;
                return;
            }
            final long suppressed = slot[1];
            slot[0] = now;
            slot[1] = 0L;
            Log.i("MC_REDIRECT", "[InsertHooker] AUDIT pkg=" + callingPkg
                    + " outcome=" + outcome
                    + " mediaType=" + mediaType
                    + " mime=" + mimeType
                    + " data=" + data
                    + " inaccessible=" + (inaccessibleRule != null ? inaccessibleRule : "-")
                    + " suppressed=" + suppressed);
            // 同步落到「文件系统记录」界面（用户诉求：要能看出哪个应用通过媒体存储
            // 创建了什么文件）。与日志共用同一节流窗口 —— 日志是"本窗口的代表样本"，
            // 记录也是同一粒度，两者口径一致才不会出现"日志有、界面没有"的错位。
            emitFileSystemRecord(callingPkg, data);
            if (sAuditThrottle.size() > AUDIT_THROTTLE_MAX_ENTRIES) {
                // 硬上限保护：异常输入下直接清空重建，宁可丢节流状态也不涨内存。
                sAuditThrottle.clear();
            }
        }
    }

    private boolean wasPathEmpty(ContentValues values) {
        return !values.containsKey(MediaStore.MediaColumns.DATA)
                || values.getAsString(MediaStore.MediaColumns.DATA).isEmpty();
    }

    /**
     * 补全缺失的文件列并计算唯一 DATA 路径。
     *
     * <p><b>先算后写：</b>全部计算与写入作用于 {@code working} 副本，
     * 全部成功后才在方法末尾一次性回写 {@code values}；
     * 中途任何异常时 {@code values} 保持进入时原状，
     * 由 AbstractGuardedHook 兜底 return 后宿主拿到未污染的 values。
     */
    private void ensureUniqueFileColumns(Object mp, int match, Uri uri,
                                         ContentValues values, String mimeType) {
        // 先算后写：working 是 values 的副本，所有中间计算只作用于它。
        final ContentValues working = new ContentValues(values);
        var defaultPrimary = Environment.DIRECTORY_DOWNLOADS;
        String defaultSecondary = null;
        switch (match) {
            case AUDIO_MEDIA:
            case AUDIO_MEDIA_ID:
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                break;
            case VIDEO_MEDIA:
            case VIDEO_MEDIA_ID:
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                break;
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                break;
            case AUDIO_ALBUMART:
            case AUDIO_ALBUMART_ID:
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case VIDEO_THUMBNAILS:
            case VIDEO_THUMBNAILS_ID:
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case IMAGES_THUMBNAILS:
            case IMAGES_THUMBNAILS_ID:
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case AUDIO_PLAYLISTS:
            case AUDIO_PLAYLISTS_ID:
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                break;
            case DOWNLOADS:
            case DOWNLOADS_ID:
                defaultPrimary = Environment.DIRECTORY_DOWNLOADS;
                break;
        }
        // Give ourselves reasonable defaults when missing
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
            working.put(MediaStore.MediaColumns.DISPLAY_NAME, String.valueOf(System.currentTimeMillis()));
        }
        // Use default directories when missing
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))) {
            if (defaultSecondary != null) {
                working.put(MediaStore.MediaColumns.RELATIVE_PATH, defaultPrimary + "/" + defaultSecondary);
            } else {
                working.put(MediaStore.MediaColumns.RELATIVE_PATH, defaultPrimary + "/");
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final var resolvedVolumeName = (String) XpCompat.callMethod(
                    mp, "resolveVolumeName", uri);
            final var volumePath = (File) XpCompat.callMethod(
                    mp, "getVolumePath", resolvedVolumeName);

            final var fileUtilsClass = XpCompat.findClass(
                    "com.android.providers.media.util.FileUtils", mClassLoader);
            final var isFuseThread = (boolean) XpCompat.callMethod(mp, "isFuseThread");
            XpCompat.callStaticMethod(fileUtilsClass, "sanitizeValues", working, !isFuseThread);
            computeDataFromValuesCompat(fileUtilsClass, mp, working, volumePath, isFuseThread);

            var res = new File(working.getAsString(MediaStore.MediaColumns.DATA));
            res = (File) XpCompat.callStaticMethod(
                    fileUtilsClass, "buildUniqueFile",
                    res.getParentFile(), mimeType, res.getName());

            working.put(MediaStore.MediaColumns.DATA, res.getAbsolutePath());
        } else {
            final var resolvedVolumeName = (String) XpCompat.callMethod(
                    mp, "resolveVolumeName", uri);

            final var relativePath = XpCompat.callMethod(
                    mp, "sanitizePath",
                    working.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
            );
            final var displayName = XpCompat.callMethod(
                    mp, "sanitizeDisplayName",
                    working.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            );

            var res = (File) XpCompat.callMethod(
                    mp, "getVolumePath", resolvedVolumeName);
            res = (File) XpCompat.callStaticMethod(
                    Environment.class, "buildPath", res, relativePath);
            res = (File) XpCompat.callStaticMethod(
                    FileUtils.class, "buildUniqueFile", res, mimeType, displayName);

            working.put(MediaStore.MediaColumns.DATA, res.getAbsolutePath());
        }

        // 全部计算成功，一次性回写：values 最终状态与旧成功路径一致
        // （逐键覆盖 + 移除 working 中被删除的键）。
        values.putAll(working);
        for (final String key : new ArrayList<>(values.keySet())) {
            if (!working.containsKey(key)) {
                values.remove(key);
            }
        }
    }

    /**
     * 适配宿主 {@code FileUtils.computeDataFromValues} 的两个重载。
     *
     * <p><b>设备实证</b>（一加15 / PLK110 / ColorOS 17 / Android 17，MediaProvider 在 APEX）：
     * <ul>
     *   <li>{@code (ContentValues, File, boolean)} —— code_units=5，
     *       仅 {@code const/4 v0, 0x0} 后转发 4 参版；</li>
     *   <li>{@code (ContentValues, File, boolean, boolean)} —— code_units=273，真正实现。</li>
     * </ul>
     * 宿主自身两处 insert 调用点用的一律是 <b>4 参</b>版，第 4 参为
     * {@code isFileTrashRestoreEnabled()}（判据为 targetSdk&gt;36 且 compat change
     * 461429441 开启）。
     *
     * <p><b>为何不能只调 3 参</b>：3 参重载存在，所以不会抛异常，但它把第 4 参
     * 硬编码成 {@code false} ⇒ 对同一 {@code values} 的 pending 文件名与
     * {@code date_expires} 处理与宿主不一致。
     *
     * <p>回退只认 {@link NoSuchMethodError}：旧 ROM 无 4 参重载、或无
     * {@code isFileTrashRestoreEnabled} 时走 3 参版（其内部同样以 false 转发）。
     */
    private static void computeDataFromValuesCompat(final Class<?> fileUtilsClass, final Object mp,
                                                    final ContentValues working,
                                                    final File volumePath,
                                                    final boolean isFuseThread) {
        try {
            // 与宿主同源：第 4 参取宿主自己的 isFileTrashRestoreEnabled()，
            // 不在此处自行推测 targetSdk / compat change。
            final var trashRestoreEnabled =
                    (boolean) XpCompat.callMethod(mp, "isFileTrashRestoreEnabled");
            XpCompat.callStaticMethod(fileUtilsClass, "computeDataFromValues",
                    working, volumePath, isFuseThread, trashRestoreEnabled);
            return;
        } catch (final NoSuchMethodError ignored) {
            // 旧 ROM：回退 3 参重载。
        }
        XpCompat.callStaticMethod(fileUtilsClass, "computeDataFromValues",
                working, volumePath, isFuseThread);
    }

    /**
     * 向 DataBus 写入重定向提示事件。
     * 异步写入，不影响主流程。
     */
    private static void emitRedirectNotice(String packageName, String originalPath,
                                           String mountedPath, String type) {
        try {
            final var event = new JSONObject();
            event.put("schemaVersion", 1);
            event.put("timeMillis", System.currentTimeMillis());
            event.put("packageName", packageName);
            event.put("originalPath", originalPath);
            event.put("mountedPath", mountedPath);
            event.put("type", type);
            event.put("reason", "REDIRECTED_TO_INTERNAL");
            final var seq = HookDataBusBridge.INSTANCE.writeEvent(
                    DataBusProtocol.EVENT_REDIRECT_NOTICE, event.toString());
            if (seq >= 0) {
                // 合并发射：见 EventSignalCoalescer（尾沿必达，消费者 2s 固定轮询）。
                EventSignalCoalescer.INSTANCE.signal(
                        DataBusProtocol.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED);
            }
        } catch (Exception e) {
            Log.e("MC_REDIRECT", "[InsertHooker] DataBus write failed", e);
        }
    }
}
