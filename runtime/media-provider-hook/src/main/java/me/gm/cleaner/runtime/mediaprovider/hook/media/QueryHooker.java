package me.gm.cleaner.runtime.mediaprovider.hook.media;

import me.gm.cleaner.runtime.mediaprovider.hook.policy.AppSpecificMediaFilter;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.HookPolicyCache;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.InaccessibleCursor;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.InaccessibleQueryFilter;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.InaccessibleEvalStats;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.InaccessibleQueryFilter;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.MediaBucketCensus;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.RedirectShadowFilter;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore.Files.FileColumns;
import android.util.ArraySet;
import android.util.Log;
import me.gm.cleaner.core.storage.redirect.domain.MediaReachability;
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule;
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.AbstractGuardedHook;
import me.gm.cleaner.runtime.mediaprovider.hook.xp.HookParam;
import me.gm.cleaner.runtime.mediaprovider.hook.xp.XpCompat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;


/**
 * MediaProvider query Hook。
 *
 * <p>继承 {@link AbstractGuardedHook} 获得统一防护：任何 handler 异常不外抛、
 * 参数回滚、每方法熔断 —— 与 FuseJavaGate 同进程统一异常策略，
 * 保证宿主 MediaProvider 不因本 Hook 崩溃。
 * 本 Hook 无需回滚的 args 槽位（只读查询参数），guardedArgIndexes 为空。
 */
public class QueryHooker extends AbstractGuardedHook {
    private static final String INCLUDED_DEFAULT_DIRECTORIES = "android:included-default-directories";
    /**
     * MediaProvider `query` 的 SQL selection / 绑定参数 Bundle key。
     *
     * 用字面量而不用 AOSP 的常量：本模块的编译 classpath 是受限的 android stub，
     * 取不到 `android.provider.ContentResolver`。字面量与 AOSP 常量取值一致，
     * 也是 MediaProvider `DatabaseUtils.resolveQueryArgs` 实际读取的 key。
     */
    /**
     * queryArgs 里 SQL selection 的键（按优先级排序，写入时全部写）。
     *
     * 本 ROM 用 `android:query-arg-sql-selection`，AOSP 用 `android:query-selection`；
     * 二进制实证见 [InaccessibleQueryFilter.getSelectionKeys]。别再写死单一键名。
     */
    private static final String[] SELECTION_KEYS =
            InaccessibleQueryFilter.INSTANCE.getSelectionKeys();

    /** [SELECTION_KEYS] 对应的绑定参数键，顺序一一对应。 */
    private static final String[] SELECTION_ARGS_KEYS =
            InaccessibleQueryFilter.INSTANCE.getSelectionArgsKeys();
    private static final int TYPE_QUERY = 0;
    private final MediaProviderHook mHook;
    private final MediaProviderHooksService mService;
    private final ClassLoader mClassLoader;

    // DatabaseUtils Class/Method 缓存：热路径每 query 解析一次 findClass/findMethod
    // 开销显著，一次解析后复用。ROM 差异导致缺失时保持 null 并回退到 XposedHelpers。
    private final Object mResolveLock = new Object();
    private volatile boolean mDatabaseUtilsResolved;
    private volatile Class<?> mDatabaseUtilsClass;
    private volatile Method mResolveQueryArgs;
    private volatile Method mRecoverAbusiveSortOrder;
    private volatile Method mRecoverAbusiveLimit;
    private volatile Method mRecoverAbusiveSelection;
    private volatile Class<?> mProviderClass;
    private volatile Method mEnsureCustomCollator;
    private volatile Method mGetCallingPackageTargetSdkVersion;
    private volatile Method mIsCallingPackageAllowedHidden;
    private volatile Method mMatchUri;
    private volatile Method mGetDatabaseForUri;
    private volatile Method mGetQueryBuilder;
    /** 宿主 query(Uri, String[], Bundle, CancellationSignal)，供过滤探针复用。 */
    private volatile Method mProviderQuery;
    private volatile Class<?> mQbClass;
    private volatile Method mQbQuery;

    /**
     * 记录「过滤 SQL 在本 ROM 上不被接受」的包。
     *
     * 一旦某包的过滤探针查询抛异常，就永久放弃对该包的过滤并回退到原始查询 ——
     * 宁可过滤不生效，也绝不能让应用原本能成功的查询失败。
     */
    private static final Set<String> FILTER_UNSUPPORTED =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * 已通过探针验证、确认本 ROM 接受该过滤 SQL 的包。
     * 探针每包只跑一次，之后直接复用结论 —— 稳态零额外查询。
     */
    private static final Set<String> FILTER_VALIDATED =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * 探针查询期间的线程标记。探针直接调用宿主 query 会再次进入本 Hook，
     * 必须就地放行，否则无限递归。
     */
    private static final ThreadLocal<Boolean> PROBING = new ThreadLocal<>();

    /**
     * 已证实「SQL 下推在本 ROM 上对某包**不被采纳**」的包 ⇒ 必须启用结果集兜底。
     *
     * 第 21 批实机：条件确实写进了 queryArgs（filteredQueries=69），宿主也没抛异常，
     * 但仍有 12 次 openWithFuse 命中不可访问路径 —— 说明行照样返回给了应用。
     * 「写进去了」不等于「生效了」，这条必须靠实机探针判定，不能靠源码推断。
     */
    private static final Set<String> SQL_PUSHDOWN_INEFFECTIVE =
            Collections.synchronizedSet(new HashSet<String>());

    /** 本次调用已写入过滤条件，交给 handleAfter 判断是否还要兜底。{包名, 路径列表} */
    private static final ThreadLocal<Object[]> PENDING_FILTER = new ThreadLocal<>();

    /** 泄漏探针列名与行数上限：每包只跑一次，代价必须封顶。 */
    private static final String DATA_COLUMN = "_data";
    private static final String[] DATA_PROJECTION = new String[]{DATA_COLUMN};
    private static final int PROBE_ROW_CAP = 400;

    /**
     * 影子集合缓存：包名 → (规则代际, 可达根, 共享存储根)。
     *
     * 推导一次要遍历规则列表并逐候选跑一遍解释器，而 query 是热路径（相册一次滚动
     * 几十上百次查询），不能每次都重算。规则代际变化（用户改了规则）时自然失效。
     * 只在「该包存在整盘重定向」时才写入，未命中的包不占位。
     */
    private static final Map<String, ShadowRoots> SHADOW_CACHE =
            new ConcurrentHashMap<>();

    /** 影子判定的不可变快照；[roots] 为空表示"不启用过滤"。 */
    private static final class ShadowRoots {
        final long generation;
        final List<String> roots;
        final String sharedRoot;

        ShadowRoots(final long generation, final List<String> roots, final String sharedRoot) {
            this.generation = generation;
            this.roots = roots;
            this.sharedRoot = sharedRoot;
        }

        static final ShadowRoots DISABLED = new ShadowRoots(-1L, Collections.<String>emptyList(), null);
    }

    /** 记录路径在本 ROM 不可用（如缺少 getQueryBuilder）时只告警一次。 */
    private static final AtomicBoolean RECORD_UNAVAILABLE_LOGGED = new AtomicBoolean();

    public QueryHooker(MediaProviderHook hook, MediaProviderHooksService service, ClassLoader classLoader) {
        // 会改写的槽位：args[1]（projection，注入 _data）/ args[2]（queryArgs，过滤条件）。
        // handler 抛异常时 AbstractGuardedHook 会把它们恢复成原值，保证宿主拿到的是
        // 未污染的查询参数 —— 第 20 批就是因为过滤写进 args[2] 后被回滚才失效的。
        super("QueryHooker", "query", new int[]{1, 2});
        mHook = hook;
        mService = service;
        mClassLoader = classLoader;
    }

    @Override
    protected void handleBefore(HookParam param) throws Throwable {
        if (mHook.isFuseThread() || Boolean.TRUE.equals(PROBING.get())) {
            // 后者：探针自身调用宿主 query 会重入本 Hook，必须就地放行，否则无限递归。
            return;
        }
        /** ARGUMENTS（轻量强转，不做 Bundle 拷贝/反射/DB 查询） */
        final var uri = (Uri) param.args[0];
        final var callingPackage = mHook.getCallingPackage(param.thisObject);
        if ("com.android.providers.media".equals(callingPackage) ||
                "com.android.providers.media.module".equals(callingPackage) ||
                "com.google.android.providers.media.module".equals(callingPackage)) {
            // Scanning files and internal queries.
            return;
        }
        if (HookPolicyCache.INSTANCE.isDenied(callingPackage)) {
            return;
        }

        // ══════════════════════════════════════════════════════════════
        //  「不可访问的路径」过滤（与「只读」无关，也不受记录开关影响）
        //
        //  只读 = 允许读、拒绝写；不可访问 = 连读都拒绝，目标是让应用完全识别不到
        //  该文件夹或文件。只靠挂载做不到这件事：MediaProvider 的数据库独立于挂载
        //  命名空间，MediaStore.query() 仍会把真实文件整行返回（文件名、_data、
        //  时间、id 全在），应用只是打开时撞 ENOENT —— 用户看到的就是"还是能访问"。
        //
        //  因此命中不可访问路径的行必须从查询结果里整行剔除。条件以 SQL selection
        //  下推给 SQLite 执行（稳态开销 ≈ 0），拼装规则集中在
        //  InaccessibleQueryFilter（纯函数，已单测）。任何异常都走"不过滤"回退。
        // ══════════════════════════════════════════════════════════════
        final List<String> hiddenPaths = HookPolicyCache.INSTANCE.getInaccessiblePaths(callingPackage);
        // ── 「影子路径」：整盘重定向把进程内的共享存储根换掉后，未被恒等 carve-out 救回的
        //    路径在该进程里根本不存在，而 MediaStore 的数据库独立于挂载命名空间、照旧返回
        //    整行 ⇒ 应用打开撞 ENOENT ⇒ 相册里就是一片灰格（第 55 批真机：有的显示、有的
        //    空白，新拍的在 DCIM/Camera 所以正常）。判据与规则的推导见
        //    RedirectShadowFilter / MediaReachability；无整盘重定向时两个集合恒为空，
        //    下面所有分支保持与改动前逐字节等价。
        // 一次取快照（内部按规则代际缓存）：roots 与 sharedRoot 必须来自同一份规则，
        // 分两次取会在规则热更新时出现"白名单与根不配套"的错配。
        final ShadowRoots shadow = shadowFor(callingPackage);
        final List<String> shadowRoots = shadow.roots;
        final String shadowRoot = shadow.sharedRoot;
        final Object originalArgs = param.args[2];
        Bundle filteredArgs = null;
        if ((!hiddenPaths.isEmpty() || !shadowRoots.isEmpty()) && shouldAttemptFilter(uri)
                && !FILTER_UNSUPPORTED.contains(callingPackage)) {
            final Bundle candidate = buildFilteredQueryArgs(
                    originalArgs, hiddenPaths, callingPackage, shadowRoots, shadowRoot,
                    InaccessibleQueryFilter.INSTANCE.isSingleItemPath(uri.getPath()));
            if (candidate != null) {
                if (FILTER_VALIDATED.contains(callingPackage)
                        || probeFilteredQuery(param, candidate, hiddenPaths, callingPackage,
                                shadowRoots, shadowRoot)) {
                    FILTER_VALIDATED.add(callingPackage);
                    filteredArgs = candidate;
                    param.args[2] = filteredArgs;
                    InaccessibleEvalStats.INSTANCE.recordDenial(
                            InaccessibleEvalStats.DenyPoint.QUERY);
                    // 下推被证伪 ⇒ 走结果集兜底。但兜底要靠 `_data` 判行，而应用常常
                    // 根本不请求这一列（豆包只取 _id，再用 content://media/.../media/<id>
                    // 取缩略图）—— 第 22 批实机：64 次过滤只有 6 次真的藏了行，其余全部
                    // 因为结果集里没有 _data 而静默放弃。故在此把该列补进 projection，
                    // 由 InaccessibleCursor 对外隐藏（列数减一），应用侧无感。
                    // args[1] == null 表示"全部列"，本就包含 _data，无需注入。
                    boolean injectedDataColumn = false;
                    if (SQL_PUSHDOWN_INEFFECTIVE.contains(callingPackage)
                            && param.args[1] instanceof String[]
                            && !containsColumn((String[]) param.args[1], DATA_COLUMN)) {
                        final String[] original = (String[]) param.args[1];
                        final String[] extended = Arrays.copyOf(original, original.length + 1);
                        extended[original.length] = DATA_COLUMN;
                        param.args[1] = extended;
                        injectedDataColumn = true;
                    }
                    PENDING_FILTER.set(new Object[]{
                            callingPackage, hiddenPaths, injectedDataColumn,
                            shadowRoots, shadowRoot,
                            InaccessibleQueryFilter.INSTANCE.isSingleItemPath(uri.getPath())});
                    Log.d("MC_REDIRECT", "[QueryHooker] inaccessible filter applied pkg="
                            + callingPackage + " paths=" + hiddenPaths.size()
                            + " cursorFallback="
                            + SQL_PUSHDOWN_INEFFECTIVE.contains(callingPackage)
                            + " injectedDataColumn=" + injectedDataColumn
                            + " shadow=" + RedirectShadowFilter.INSTANCE.describe(
                                    shadowRoots, shadowRoot));
                } else {
                    // 本 ROM 不接受这条 selection：永久放弃该包的过滤，绝不让应用
                    // 原本能成功的查询失败。此时 FUSE 层拒绝仍在（内容读不到），
                    // 只是查询结果仍会列出这些行。
                    FILTER_UNSUPPORTED.add(callingPackage);
                    Log.w("MC_REDIRECT", "[QueryHooker] inaccessible filter unsupported"
                            + " on this ROM, disabled for pkg=" + callingPackage);
                }
            }
        }

        // 策略门旁路：无重定向规则或记录开关全关时**不做记录**，避免 DatabaseUtils
        // 反射与后续 DB 查询。会话门已废弃见 ADR-0011，勿复活：
        // session 本身由本次 RECORD 创建，用它做门会导致首个 session 永不创建（冷启动死锁）。
        // ⚠️ 该门只管记录；上面的不可访问过滤不受它约束。
        final boolean needRecord =
                (HookPolicyCache.INSTANCE.getRecordExternalAppSpecificStorage()
                        || HookPolicyCache.INSTANCE.getAggressivelyPromptForReadingMediaFiles())
                        && HookPolicyCache.INSTANCE.hasRedirectRules(callingPackage);
        if (filteredArgs == null && !needRecord) {
            return;
        }

        // ══════════════════════════════════════════════════════════════
        //  记录（RECORD）路径可用性门
        //
        //  该路径依赖 getQueryBuilder 反射；部分 ROM（ColorOS17 / Android17）
        //  没有五参 getQueryBuilder，调用必然抛 NoSuchMethodError。而
        //  AbstractGuardedHook 一旦捕获 handler 异常就会回滚 args[2]，把上面
        //  刚写好的过滤条件一并丢掉（第 20 批实机：过滤已写入 queryArgs 却被回滚，
        //  filteredQueries=0，豆包相册仍列出不可访问图片）。
        //  记录只是可选增强，绝不能拖累过滤 —— 缺方法就直接跳过记录。
        // ══════════════════════════════════════════════════════════════
        ensureResolved(param.thisObject);
        if (mGetQueryBuilder == null) {
            if (RECORD_UNAVAILABLE_LOGGED.compareAndSet(false, true)) {
                Log.w("MC_REDIRECT", "[QueryHooker] record path unavailable: no 5-arg"
                        + " getQueryBuilder on " + param.thisObject.getClass().getName()
                        + "; recording disabled, inaccessible filter unaffected");
            }
            return;
        }

        try {
        final var callingUid = getCallingUid(param.thisObject);

        final var queryArgs = filteredArgs != null ? filteredArgs
                : (param.args[2] == null ? Bundle.EMPTY : (Bundle) param.args[2]);
        final var signal = (CancellationSignal) param.args[3];

        /** PARSE */
        final var query = new Bundle(queryArgs);
        query.remove(INCLUDED_DEFAULT_DIRECTORIES);
        final var honoredArgs = new ArraySet<String>();
        ensureResolved(param.thisObject);
        final Consumer<String> honoredConsumer = new Consumer<String>() {
            @Override
            public void accept(String s) {
                honoredArgs.add(s);
            }
        };
        final Function<String, String> collatorFunction = new Function<String, String>() {
            @Override
            public String apply(String s) {
                try {
                    final var m = mEnsureCustomCollator;
                    if (m != null) {
                        return (String) m.invoke(param.thisObject, s);
                    }
                } catch (IllegalAccessException | InvocationTargetException ignored) {
                }
                return (String) XpCompat.callMethod(param.thisObject, "ensureCustomCollator", s);
            }
        };
        if (mResolveQueryArgs != null) {
            try {
                mResolveQueryArgs.invoke(null, query, honoredConsumer, collatorFunction);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        } else {
            final var databaseUtilsClass = mDatabaseUtilsClass != null ? mDatabaseUtilsClass
                    : XpCompat.findClass(
                    "com.android.providers.media.util.DatabaseUtils", mClassLoader);
            XpCompat.callStaticMethod(
                    databaseUtilsClass, "resolveQueryArgs", query, honoredConsumer, collatorFunction);
        }

        final var targetSdkVersion = (int) invokeProviderMethod(
                param.thisObject, mGetCallingPackageTargetSdkVersion,
                "getCallingPackageTargetSdkVersion");
        final var allowHidden = (boolean) invokeProviderMethod(
                param.thisObject, mIsCallingPackageAllowedHidden,
                "isCallingPackageAllowedHidden");
        final var table = (int) invokeProviderMethod(
                param.thisObject, mMatchUri, "matchUri", uri, allowHidden);

        final var dataProjection = new String[]{FileColumns.DATA};
        final var helper = invokeProviderMethod(
                param.thisObject, mGetDatabaseForUri, "getDatabaseForUri", uri);
        final var qb = invokeProviderMethod(
                param.thisObject, mGetQueryBuilder, "getQueryBuilder",
                TYPE_QUERY, table, uri, query, honoredConsumer);

        if (targetSdkVersion < Build.VERSION_CODES.R) {
            // Some apps are abusing "ORDER BY" clauses to inject "LIMIT"
            // clauses; gracefully lift them out.
            invokeDatabaseUtilsStatic(mRecoverAbusiveSortOrder, "recoverAbusiveSortOrder", query);

            // Some apps are abusing the Uri query parameters to inject LIMIT
            // clauses; gracefully lift them out.
            invokeDatabaseUtilsStatic(mRecoverAbusiveLimit, "recoverAbusiveLimit", uri, query);
        }

        if (targetSdkVersion < Build.VERSION_CODES.Q) {
            // Some apps are abusing the "WHERE" clause by injecting "GROUP BY"
            // clauses; gracefully lift them out.
            invokeDatabaseUtilsStatic(mRecoverAbusiveSelection, "recoverAbusiveSelection", query);
        }

        /** QUERY */
        // try-with-resources 保证任何分支退出（含 dataColumn == -1 的提前返回与
        // 遍历中途异常）都关闭 cursor，修复旧代码在提前 return 路径上的泄漏。
        final ArrayList<String> data;
        try (Cursor c = (Cursor) invokeQbQuery(qb, helper, dataProjection, query, signal)) {
            if (c.getCount() == 0) {
                // querying nothing.
                return;
            }
            final var dataColumn = c.getColumnIndex(FileColumns.DATA);
            if (dataColumn == -1) {
                return;
            }
            data = new ArrayList<>();
            while (c.moveToNext()) {
                data.add(c.getString(dataColumn));
            }
        } catch (XpCompat.InvocationTargetError e) {
            // IllegalArgumentException that thrown from the media provider. Nothing I can do.
            // 预期宿主异常：计数进入 guardedHooks.swallowedHostExceptions 后静默放行，
            // 不计入熔断失败（避免常态化业务异常触发冷却）。
            NativeHookStatus.INSTANCE.markGuardedHostExceptionSwallowed();
            Log.d("MC_REDIRECT", "[QueryHooker] Swallowed host exception from query", e);
            rollbackInaccessibleFilter(param, originalArgs, filteredArgs, callingPackage);
            return;
        } catch (InvocationTargetException e) {
            NativeHookStatus.INSTANCE.markGuardedHostExceptionSwallowed();
            Log.d("MC_REDIRECT", "[QueryHooker] Swallowed host exception from query", e);
            rollbackInaccessibleFilter(param, originalArgs, filteredArgs, callingPackage);
            return;
        }

        if (!needRecord) {
            return;
        }

        /** RECORD */
        final var uid = callingUid != -1 ? callingUid : getCallingUid(param.thisObject);
        if (uid == -1) {
            return;
        }
            QuerySessionCache.recordQueriedPaths(callingPackage, uid, data);
        } catch (Throwable t) {
            // 记录是可选增强：它抛出的任何异常都会被 AbstractGuardedHook 捕获并
            // 回滚 args[2]，把上面已写好的过滤条件一并丢掉。这里自己兜住。
            Log.w("MC_REDIRECT", "[QueryHooker] record path failed, ignored", t);
        }
    }

    /**
     * 用「准备下发的同一份 queryArgs」把查询跑一遍，验证本 ROM 接受这条 SQL。
     *
     * <p>不用既有的 getQueryBuilder 反射链做探针：那条链属于记录路径，在部分 ROM
     * 上签名对不上会抛 NoSuchMethodError，而 handler 抛异常会导致 args[2] 被回滚，
     * 连带丢掉已写好的过滤条件。探针必须自洽、独立于记录。<br>
     * 直接调用宿主 query 是最忠实的验证：走的就是应用马上要走的那条代码路径；
     * 成本是每包首次命中多跑一次同样的查询，之后由 FILTER_VALIDATED 短路。
     *
     * @return true 表示本 ROM 接受；false 表示拒绝，调用方应放弃过滤
     */
    private boolean probeFilteredQuery(final HookParam param, final Bundle filtered,
                                       final List<String> hiddenPaths,
                                       final String callingPackage,
                                       final List<String> shadowRoots,
                                       final String shadowRoot) {
        final Object provider = param.thisObject;
        if (provider == null) {
            return false;
        }
        final Method queryMethod = ensureProviderQueryMethod(provider);
        if (queryMethod == null) {
            return false;
        }
        PROBING.set(Boolean.TRUE);
        Object cursor = null;
        try {
            cursor = queryMethod.invoke(provider,
                    param.args[0], param.args[1], filtered, param.args[3]);
            // ① 兼容性探针通过（没抛异常）只说明宿主「接受」这条 selection，
            //    不代表它采纳了。必须再验一次有效性，否则会重演第 21 批的假成功。
            final int leaked = countLeakedRows(
                    provider, queryMethod, param.args[0], filtered, hiddenPaths, callingPackage,
                    shadowRoots, shadowRoot);
            if (leaked > 0) {
                SQL_PUSHDOWN_INEFFECTIVE.add(callingPackage);
                Log.w("MC_REDIRECT", "[QueryHooker] inaccessible SQL pushdown NOT honored"
                        + " pkg=" + callingPackage + " leaked=" + leaked
                        + "; enabling cursor fallback");
            } else if (leaked == 0) {
                Log.d("MC_REDIRECT", "[QueryHooker] inaccessible SQL pushdown honored pkg="
                        + callingPackage + " leaked=0");
            }
            // 应用专属存储行采样：用**未过滤**的原始 queryArgs 跑同一条查询，
            // 统计落在 Android/data|media/<pkg>/ 下的行（见 AppSpecificMediaFilter）。
            // 注意：第 25/26 批实测该值恒为 0 —— 本机不存在这类行，相册里多出来的
            // assets(6) 是共享存储下的真实目录，与本判据无关（详见其类注释）。
            // 只在探针里跑（每包一次，行数封顶），稳态零额外查询。
            sampleAppSpecificRows(provider, queryMethod, param.args[0], param.args[2],
                    callingPackage);
            return true;
        } catch (Throwable t) {
            Log.d("MC_REDIRECT", "[QueryHooker] probe rejected inaccessible selection: " + t);
            return false;
        } finally {
            if (cursor instanceof Cursor) {
                try {
                    ((Cursor) cursor).close();
                } catch (Throwable ignored) {
                }
            }
            PROBING.remove();
        }
    }

    /**
     * 用 {@code {"_data"}} 投影跑一次**已过滤**的查询，统计仍泄漏出来的行数。
     *
     * 若下推真的被采纳，结果里不可能出现命中不可访问根的行 ⇒ leaked == 0；
     * leaked > 0 即证明宿主忽略了这条 selection。每包只跑一次（结果缓存），
     * 行数上限 [PROBE_ROW_CAP]，代价封顶。
     *
     * @return 泄漏行数；无法判定（如宿主不接受 {"_data"} 投影）返回 -1
     */
    private static int countLeakedRows(final Object provider, final Method queryMethod,
                                       final Object uri, final Bundle filtered,
                                       final List<String> hiddenPaths,
                                       final String callingPackage,
                                       final List<String> shadowRoots,
                                       final String shadowRoot) {
        try {
            final Object r = queryMethod.invoke(provider, uri, DATA_PROJECTION, filtered, null);
            if (!(r instanceof Cursor)) {
                return -1;
            }
            final Cursor c = (Cursor) r;
            try {
                final int idx = c.getColumnIndex(DATA_COLUMN);
                if (idx < 0 || !c.moveToFirst()) {
                    return 0;
                }
                int leaked = 0;
                int seen = 0;
                do {
                    if (++seen > PROBE_ROW_CAP) {
                        break;
                    }
                    final String data = c.getString(idx);
                    if (InaccessibleQueryFilter.INSTANCE.isHidden(
                            hiddenPaths, data, callingPackage)
                            || RedirectShadowFilter.INSTANCE.isUnreachable(
                                    shadowRoots, shadowRoot, data)) {
                        leaked++;
                    }
                } while (c.moveToNext());
                return leaked;
            } finally {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.d("MC_REDIRECT", "[QueryHooker] leak probe failed: " + t);
            return -1;
        }
    }

    /**
     * 宿主 {@code query} 抛异常时的兜底清理（P0-1）。
     *
     * 宿主抛异常 ⇒ 没有 result ⇒ {@link #handleAfter} 一次都不跑 ⇒ {@code PENDING_FILTER}
     * 残留。MediaProvider 线程池复用该线程时，下一次查询（很可能是<b>另一个应用</b>）会读到
     * 本次的 {包名, 隐藏路径, 是否注入尾列}：既可能把配置过的路径判成可访问，
     * 也会因尾列数目错位导致取列错位。这里把清理由"返回值驱动"改成"异常路径也覆盖"。
     */
    @Override
    protected void onHostFailed(final HookParam param, final Throwable cause) throws Throwable {
        PENDING_FILTER.remove();
    }

    /**
     * 结果兜底：SQL 下推被证伪的包，再把结果集过滤一遍。
     *
     * 这是「完全识别不到」的最后一道保险 —— 它不依赖宿主任何行为，只要返回的是
     * Cursor 且带 {@code _data} 列就一定生效。任何异常都由基类吞掉并退回原结果。
     */
    @Override
    protected Object handleAfter(final HookParam param, final Object result) throws Throwable {
        final Object[] pending = PENDING_FILTER.get();
        PENDING_FILTER.remove();
        if (pending == null || !(result instanceof Cursor)) {
            return result;
        }
        final String pkg = (String) pending[0];
        @SuppressWarnings("unchecked")
        final List<String> hidden = (List<String>) pending[1];
        if (!SQL_PUSHDOWN_INEFFECTIVE.contains(pkg)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        final List<String> shadowRoots = pending.length > 3
                ? (List<String>) pending[3] : Collections.<String>emptyList();
        final String shadowRoot = pending.length > 4 ? (String) pending[4] : null;
        // 单条目查询（`.../file/17830`）与 SQL 下推侧同口径：**不施加影子过滤**。
        // 否则走 cursor 兜底的包会在单条目上把行剔掉 ⇒ 灰格照旧（补丁 A 只覆盖了下推侧）。
        // 空集合在 RedirectShadowFilter.isUnreachable 里恒返回 false，等价于"不参与"。
        final boolean singleItem = pending.length > 5 && Boolean.TRUE.equals(pending[5]);
        final List<String> shadowRows = singleItem
                ? Collections.<String>emptyList() : shadowRoots;
        final String shadowRowRoot = singleItem ? null : shadowRoot;
        // 注入了 _data 就多一列要对外隐藏；否则列数与宿主一致。
        final int tailHidden = Boolean.TRUE.equals(pending[2]) ? 1 : 0;
        final Cursor wrapped = InaccessibleCursor.wrapIfNeeded(
                (Cursor) result, hidden, tailHidden, pkg, shadowRows, shadowRowRoot);
        if (wrapped != result && wrapped instanceof InaccessibleCursor) {
            final int hiddenRows = ((InaccessibleCursor) wrapped).getHiddenCount();
            InaccessibleEvalStats.INSTANCE.recordHiddenRows(hiddenRows);
            Log.d("MC_REDIRECT", "[QueryHooker] inaccessible rows hidden pkg=" + pkg
                    + " hiddenRows=" + hiddenRows);
        }
        return wrapped;
    }

    /**
     * 对**原始（未过滤）** queryArgs 跑同一条查询，产出一份「媒体分桶普查」：
     *
     * <ul>
     *   <li>{@code rows} —— 落在 {@code /Android/data|media/<pkg>/} 下的行数（第 24 批口径，
     *       第 25 批实机为 0，说明坏图行**不在**应用专属存储里）；
     *   <li>{@code total} —— 本次探针实际扫到的行数（回答"rows=0 是没扫到还是真没有"）；
     *   <li>{@code top} —— 分桶名与张数，可与相册 UI 上的 "assets (6)" 逐项对齐；
     *   <li>{@code small} —— 张数不超过 {@link MediaBucketCensus#SMALL_BUCKET_MAX} 的分桶的
     *       **完整父目录**，直接给出可疑相册的真实路径。
     * </ul>
     *
     * <p>第 24 批的探针有三个盲区（只统计命中行、不报总行数、不给目录分布），
     * 导致 rows=0 无法定位 assets(6)；本版一次输出全部四项，装机一次即可定性。
     * 只在探针里执行一次（每包每进程一次，行数封顶 [PROBE_ROW_CAP]），
     * 稳态不产生任何额外查询；任何异常一律吞掉，绝不影响应用原本的查询。
     */
    private static void sampleAppSpecificRows(final Object provider, final Method queryMethod,
                                              final Object uri, final Object originalArgs,
                                              final String callingPackage) {
        try {
            final Bundle source =
                    originalArgs instanceof Bundle ? (Bundle) originalArgs : Bundle.EMPTY;
            final Object r = queryMethod.invoke(provider, uri, DATA_PROJECTION, source, null);
            if (!(r instanceof Cursor)) {
                return;
            }
            final Cursor c = (Cursor) r;
            try {
                final int idx = c.getColumnIndex(DATA_COLUMN);
                if (idx < 0 || !c.moveToFirst()) {
                    return;
                }
                int seen = 0;
                int hit = 0;
                final Map<String, Integer> dirCounts = new HashMap<>();
                do {
                    if (++seen > PROBE_ROW_CAP) {
                        break;
                    }
                    final String data = c.getString(idx);
                    if (AppSpecificMediaFilter.INSTANCE.isInside(data, callingPackage)) {
                        hit++;
                    }
                    final String dir = AppSpecificMediaFilter.INSTANCE.parentDir(data);
                    if (!dir.isEmpty()) {
                        final Integer prev = dirCounts.get(dir);
                        dirCounts.put(dir, prev == null ? 1 : prev + 1);
                    }
                } while (c.moveToNext());
                InaccessibleEvalStats.INSTANCE.recordAppSpecificRows(hit);
                // 一次普查同时回答三件事：探针到底扫到多少行（total）、
                // 相册 UI 上的分桶名与张数（top，可与截图逐项对齐）、
                // 可疑小相册的完整父目录（small，assets(6) 就落在这一类）。
                final String census = "[QueryHooker] media bucket census pkg=" + callingPackage
                        + " rows=" + hit + " total=" + seen
                        + " top=" + MediaBucketCensus.INSTANCE.formatTop(dirCounts, 3)
                        + " small=" + MediaBucketCensus.INSTANCE.smallBuckets(
                        dirCounts, 3, MediaBucketCensus.SMALL_BUCKET_MAX);
                if (hit > 0) {
                    Log.i("MC_REDIRECT", census);
                } else {
                    Log.d("MC_REDIRECT", census);
                }
            } finally {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.d("MC_REDIRECT", "[QueryHooker] app-specific sample failed: " + t);
        }
    }

    /** 解析宿主 query(Uri, String[], Bundle, CancellationSignal)，按参数类型精确匹配。 */
    private Method ensureProviderQueryMethod(final Object provider) {
        Method m = mProviderQuery;
        if (m != null) {
            return m;
        }
        synchronized (mResolveLock) {
            m = mProviderQuery;
            if (m != null) {
                return m;
            }
            // providerClass 上还有 query(Uri,String[],String,String) 等四参重载，
            // 必须按参数类型精确匹配，只认 Bundle 变体（过滤条件只能走 queryArgs）。
            for (Class<?> c = provider.getClass(); c != null; c = c.getSuperclass()) {
                for (final Method d : c.getDeclaredMethods()) {
                    if (!"query".equals(d.getName()) || d.getParameterCount() != 4) {
                        continue;
                    }
                    final Class<?>[] ps = d.getParameterTypes();
                    if (ps[0] != Uri.class || ps[1] != String[].class
                            || ps[2] != Bundle.class || ps[3] != CancellationSignal.class) {
                        continue;
                    }
                    try {
                        d.setAccessible(true);
                    } catch (RuntimeException ignored) {
                    }
                    mProviderQuery = d;
                    return d;
                }
            }
            Log.w("MC_REDIRECT", "[QueryHooker] no query(Uri,String[],Bundle,"
                    + "CancellationSignal) on provider; inaccessible filter disabled");
            return null;
        }
    }

    private static int getCallingUid(Object provider) {
        try {
            final var threadLocal = (ThreadLocal<?>) XpCompat.getObjectField(
                    provider, "mCallingIdentity");
            final var identity = threadLocal != null ? threadLocal.get() : null;
            if (identity == null) {
                return -1;
            }
            return (int) XpCompat.getObjectField(identity, "uid");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 只在 MediaStore 的媒体 / 文件 URI 上尝试过滤。
     *
     * `_data` 是 `files` 表的列；其它 URI（音量、相册权限等非 files 表查询）没有它，
     * 拼上去必然报错。与其依赖异常回退，不如先按 URI 形态筛掉，避免一次查询就
     * 把该包永久标记为"过滤不支持"。
     */
    private static boolean shouldAttemptFilter(final Uri uri) {
        if (uri == null) {
            return false;
        }
        return InaccessibleQueryFilter.INSTANCE.shouldAttempt(uri.getPath());
    }

    /**
     * 把「不可访问」路径转成 SQLite 排除条件，合并进 queryArgs。
     *
     * @return 新的 Bundle（原 Bundle 不被修改）；无需过滤或无法构造时返回 null
     */
    private static Bundle buildFilteredQueryArgs(final Object originalArgs,
                                                 final List<String> hiddenPaths,
                                                 final String callingPackage,
                                                 final List<String> shadowRoots,
                                                 final String shadowRoot,
                                                 final boolean singleItem) {
        // 两段排除条件都是「按行剔除」，语义独立、用 AND 合并：
        //   ① 用户明确配置的「不可访问」；
        //   ② 整盘重定向下在该进程里根本不存在、只剩灰格的「影子路径」。
        // 任一段为空时退化为另一段，交集为空则整体不干预（与原行为一致）。
        final kotlin.Pair<String, List<String>> inaccessible =
                InaccessibleQueryFilter.INSTANCE.buildExclusion(hiddenPaths, callingPackage);
        // 单条目查询（`.../file/17830`）**不施加影子过滤**：它是图片选择器逐条校验
        // "这条还在不在"的入口，剔掉它只会让已经出现在列表里的行变成灰格
        // （第56批真机：915 条列表 vs 323 次 "No item"）。`inaccessible` 段照旧生效。
        final kotlin.Pair<String, List<String>> shadow = singleItem
                ? null
                : RedirectShadowFilter.INSTANCE.buildExclusion(shadowRoots, shadowRoot);
        final kotlin.Pair<String, List<String>> exclusion;
        if (inaccessible == null) {
            exclusion = shadow;
        } else if (shadow == null) {
            exclusion = inaccessible;
        } else {
            exclusion = new kotlin.Pair<>(
                    "(" + inaccessible.getFirst() + ") AND (" + shadow.getFirst() + ")",
                    concatArgs(inaccessible.getSecond(), shadow.getSecond()));
        }
        if (exclusion == null) {
            return null;
        }
        final Bundle source = originalArgs instanceof Bundle ? (Bundle) originalArgs : Bundle.EMPTY;
        final String originalSelection = firstNonBlank(source, SELECTION_KEYS);
        final String[] originalSelectionArgs = firstNonNull(source, SELECTION_ARGS_KEYS);
        final kotlin.Pair<String, List<String>> combined = InaccessibleQueryFilter.INSTANCE.combine(
                originalSelection,
                originalSelectionArgs == null ? null : Arrays.asList(originalSelectionArgs),
                exclusion);
        final Bundle filtered = new Bundle(source);
        final String[] args = combined.getSecond().toArray(new String[0]);
        // 全部候选键都写：本 ROM 读 `android:query-arg-sql-selection`，AOSP 读
        // `android:query-selection`，多写的那个对方不读，无副作用。
        for (final String key : SELECTION_KEYS) {
            filtered.putString(key, combined.getFirst());
        }
        for (final String key : SELECTION_ARGS_KEYS) {
            filtered.putStringArray(key, args);
        }
        return filtered;
    }

    /**
     * 该包在整盘重定向下仍然可达的子树根（供本次查询一次性取出、逐行复用）。
     *
     * 未配置整盘重定向时返回空表 ⇒ 调用方所有影子分支自动短路，行为与改动前一致。
     * 规则按 userId=0 取：本机（一加 15 / ColorOS17）只有用户 0，且规则也只在用户 0 下
     * 配置；若将来出现其它用户的查询，这里取不到规则 ⇒ 不启用过滤（fail-open），
     * 宁可留着灰格也绝不误删本可正常显示的行。
     *
     * 任何异常一律吞掉并返回空表：本功能是体验修复，绝不能因为它让宿主查询失败。
     */
    private static ShadowRoots shadowFor(final String callingPackage) {
        try {
            final long generation = HookPolicyCache.INSTANCE.getRedirectPolicyGeneration();
            final ShadowRoots cached = SHADOW_CACHE.get(callingPackage);
            if (cached != null && cached.generation == generation) {
                return cached;
            }
            final List<RedirectRule> rules =
                    HookPolicyCache.INSTANCE.getRedirectRules(callingPackage, 0);
            final String sharedRoot = rules.isEmpty() ? null
                    : MediaReachability.INSTANCE.findWholeRootTarget(rules);
            final Set<String> roots = sharedRoot == null ? Collections.<String>emptySet()
                    : RedirectShadowFilter.INSTANCE.reachableRoots(rules);
            final ShadowRoots computed = roots.isEmpty()
                    ? ShadowRoots.DISABLED
                    : new ShadowRoots(generation, new ArrayList<>(roots), sharedRoot);
            // 只在"确实启用"时缓存：未配置整盘重定向的包是绝大多数，不该占表。
            if (!computed.roots.isEmpty()) {
                SHADOW_CACHE.put(callingPackage, computed);
            }
            return computed;
        } catch (Throwable t) {
            Log.d("MC_REDIRECT", "[QueryHooker] shadow roots unavailable, skipped: " + t);
            return ShadowRoots.DISABLED;
        }
    }

    /** 该包可达子树根（空表 ⇒ 不启用影子过滤）。 */
    private static List<String> shadowReachableRoots(final String callingPackage) {
        return shadowFor(callingPackage).roots;
    }

    /** 该包整盘重定向的共享存储根；未启用时返回 null。 */
    private static String shadowSharedRoot(final String callingPackage) {
        return shadowFor(callingPackage).sharedRoot;
    }

    /** `?` 绑定参数的顺序拼接：两段 selection 以 AND 相连时参数也必须首尾相接。 */
    private static List<String> concatArgs(final List<String> first, final List<String> second) {
        final List<String> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return merged;
    }

    /** 按优先级取第一个非空字符串；全空返回 null。 */
    private static String firstNonBlank(final Bundle bundle, final String[] keys) {
        for (final String key : keys) {
            final String v = bundle.getString(key);
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /** 按优先级取第一个非 null 数组；全为 null 返回 null。 */
    private static String[] firstNonNull(final Bundle bundle, final String[] keys) {
        for (final String key : keys) {
            final String[] v = bundle.getStringArray(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** projection 是否已经包含指定列。 */
    private static boolean containsColumn(final String[] projection, final String column) {
        for (final String c : projection) {
            if (column.equals(c)) {
                return true;
            }
        }
        return false;
    }

    /** 过滤探针失败：回滚 args[2] 并永久放弃对该包的过滤。 */
    private static void rollbackInaccessibleFilter(final HookParam param, final Object originalArgs,
                                                   final Bundle filteredArgs,
                                                   final String callingPackage) {
        if (filteredArgs == null) {
            return;
        }
        param.args[2] = originalArgs;
        FILTER_UNSUPPORTED.add(callingPackage);
        Log.w("MC_REDIRECT", "[QueryHooker] inaccessible filter unsupported on this ROM, "
                + "disabled for pkg=" + callingPackage);
    }

    private void ensureResolved(Object provider) {
        if (mDatabaseUtilsResolved && mProviderClass == provider.getClass()) {
            return;
        }
        synchronized (mResolveLock) {
            if (!mDatabaseUtilsResolved) {
                try {
                    final var cls = XpCompat.findClass(
                            "com.android.providers.media.util.DatabaseUtils", mClassLoader);
                    mDatabaseUtilsClass = cls;
                    mResolveQueryArgs = findMethod(cls, "resolveQueryArgs", 3, true);
                    mRecoverAbusiveSortOrder = findMethod(cls, "recoverAbusiveSortOrder", 1, true);
                    mRecoverAbusiveLimit = findMethod(cls, "recoverAbusiveLimit", 2, true);
                    mRecoverAbusiveSelection = findMethod(cls, "recoverAbusiveSelection", 1, true);
                } catch (XpCompat.ClassNotFoundError ignored) {
                    mDatabaseUtilsClass = null;
                }
                mDatabaseUtilsResolved = true;
            }
            final var providerClass = provider.getClass();
            if (mProviderClass != providerClass) {
                mEnsureCustomCollator = findMethod(providerClass, "ensureCustomCollator", 1, false);
                mGetCallingPackageTargetSdkVersion =
                        findMethod(providerClass, "getCallingPackageTargetSdkVersion", 0, false);
                mIsCallingPackageAllowedHidden =
                        findMethod(providerClass, "isCallingPackageAllowedHidden", 0, false);
                mMatchUri = findMethod(providerClass, "matchUri", 2, false);
                mGetDatabaseForUri = findMethod(providerClass, "getDatabaseForUri", 1, false);
                mGetQueryBuilder = findMethod(providerClass, "getQueryBuilder", 5, false);
                if (mGetQueryBuilder == null) {
                    // 本 ROM 没有五参 getQueryBuilder（ColorOS17 实证）。把真实存在的
                    // 同名方法签名打出来，供后续按真签名适配；只在类首次解析时输出一次。
                    final StringBuilder cand = new StringBuilder();
                    for (Class<?> c = providerClass; c != null; c = c.getSuperclass()) {
                        for (final Method d : c.getDeclaredMethods()) {
                            if (d.getName().toLowerCase(Locale.ROOT).contains("querybuilder")) {
                                cand.append(c.getSimpleName()).append('#').append(d.getName())
                                        .append('/').append(d.getParameterCount()).append(' ');
                            }
                        }
                    }
                    Log.w("MC_REDIRECT", "[QueryHooker] getQueryBuilder candidates: "
                            + (cand.length() == 0 ? "<none>" : cand.toString()));
                }
                mProviderClass = providerClass;
            }
        }
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount, boolean staticOnly) {
        for (var c = cls; c != null; c = c.getSuperclass()) {
            for (final var m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != paramCount) {
                    continue;
                }
                if (staticOnly != java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                }
                return m;
            }
        }
        return null;
    }

    private static Object invokeProviderMethod(Object target, Method cached, String name,
            Object... args) throws Throwable {
        if (cached != null) {
            try {
                return cached.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
        return XpCompat.callMethod(target, name, args);
    }

    private void invokeDatabaseUtilsStatic(Method cached, String name, Object... args)
            throws Throwable {
        if (cached != null) {
            try {
                cached.invoke(null, args);
                return;
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
        final var cls = mDatabaseUtilsClass != null ? mDatabaseUtilsClass
                : XpCompat.findClass(
                "com.android.providers.media.util.DatabaseUtils", mClassLoader);
        XpCompat.callStaticMethod(cls, name, args);
    }

    private Object invokeQbQuery(Object qb, Object helper, String[] dataProjection,
            Bundle query, CancellationSignal signal) throws Throwable {
        final var qbClass = qb.getClass();
        var m = mQbQuery;
        if (m == null || mQbClass != qbClass) {
            synchronized (mResolveLock) {
                m = mQbQuery;
                if (m == null || mQbClass != qbClass) {
                    mQbQuery = findMethod(qbClass, "query", 4, false);
                    mQbClass = qbClass;
                    m = mQbQuery;
                }
            }
        }
        if (m != null) {
            return m.invoke(qb, helper, dataProjection, query, signal);
        }
        return XpCompat.callMethod(qb, "query", helper, dataProjection, query, signal);
    }
}
