package api

import android.content.pm.PackageInfo
import android.os.SystemClock
import android.util.Log

/**
 * `getInstalledPackages` 一级缓存（含 single-flight 合并）。
 *
 * ## 为什么需要
 * 每次 `getInstalledPackages` 都是一次**大 parcel** IPC：实测单包 parcel 17–180KB
 * （见 ColorOS `W PackageInfo: Large parcel: size=… pkg=… uid=…`），而 server 侧有 5 个
 * 调用点（`PackageInfoMapper` / `FileSystemObserver` / `DataAppDirObserver` /
 * `PackageReceiver` / `CleanerService`），其中多个位于**字段初始化与构造函数**中，
 * 启动期会在多个线程上并发做同一份全量枚举 —— 日志里同一个包名在 5ms 内被枚举 8 次。
 *
 * ## 语义保证
 * - **只加速，不改变结论**：命中与未命中返回同一份数据内容；失败路径仍由调用方
 *   （`*NoThrow`）负责返回空列表，本类不吞异常、不改变失败语义。
 * - **防御性拷贝**：每次返回 `ArrayList` 副本，调用方对结果做增删改都不会污染缓存。
 * - **single-flight**：装载在锁内完成，并发调用者要么命中缓存、要么等待同一份结果，
 *   不会各自发起一次 IPC。锁是可重入的，`FromAllUsers` → 逐 user 的嵌套调用安全。
 * - **TTL 短**（[DEFAULT_TTL_MILLIS]）：包列表是"准静态"事实，但安装/卸载会使其失效，
 *   因此 TTL 取得很短，并额外提供 [invalidate] 供包变更广播立即失效
 *   （已接到 `PackageReceiver` 的 ADDED / REPLACED / FULLY_REMOVED 三个动作上）。
 *
 * ## 观测（重要：为什么不用 `api.util.Logger`）
 * 真实装载与失效各打一条 `MC_PkgCache` D 级日志（含 loads/hits）。
 * **本类刻意使用 `android.util.Log` 而不是 `api.util.Logger`**：后者的每个方法都以
 * `Log.isLoggable(TAG, level)` 为前提，D 级需要 `log.tag.MC_PkgCache=DEBUG`（默认 INFO）
 * ⇒ **实机永远不输出**，实测在一次 74 秒的真机会话里 `MC_PkgCache` 为 0 条，
 * 而同期 `PackageInfo: Large parcel` 有 100 条（证明代码确实在跑）。
 * 那会让"启动期 N 次枚举被合并为 1 次"这条 KDoc 承诺**无法被任何真机证据检验**。
 */
internal object PackageListCache {

    /** 缓存有效期。包变更会立即失效，因此这里只承担"短时间内的重复调用"兜底。 */
    const val DEFAULT_TTL_MILLIS = 5_000L

    /**
     * 直接用 `android.util.Log`，绕过 `api.util.Logger` 的 `isLoggable` 门控（见类 KDoc）。
     */
    private const val TAG = "MC_PkgCache"

    private class Entry(
        val value: List<PackageInfo>,
        val expiresAtElapsedMs: Long,
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    /** 真实装载次数（未命中），供日志观测。 */
    private var loadCount = 0L

    /** 命中次数，供日志观测。 */
    private var hitCount = 0L

    /**
     * 取缓存或装载。
     *
     * @param key 缓存键，必须包含全部影响结果的入参（flags / userId）。
     * @param loader 未命中时的装载动作。**抛异常时不会写入缓存**，
     *   由调用方既有的 `*NoThrow` 兜底语义处理。
     */
    fun getOrLoad(key: String, loader: () -> List<PackageInfo>): List<PackageInfo> {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val cached = entries[key]
            if (cached != null && now < cached.expiresAtElapsedMs) {
                hitCount++
                return ArrayList(cached.value)
            }
            val startedAt = SystemClock.elapsedRealtime()
            val loaded = loader()
            loadCount++
            entries[key] = Entry(loaded, now + DEFAULT_TTL_MILLIS)
            // 带上耗时：这是"这次装载值不值得"的唯一可观测量。
            Log.d(
                TAG,
                "load key=$key size=${loaded.size} costMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "ttl=${DEFAULT_TTL_MILLIS}ms loads=$loadCount hits=$hitCount",
            )
            return ArrayList(loaded)
        }
    }

    /** 立即失效全部缓存条目（包安装/卸载/替换后调用）。 */
    fun invalidate() {
        synchronized(lock) {
            if (entries.isNotEmpty()) {
                Log.d(
                    TAG,
                    "invalidate: dropped ${entries.size} entries (loads=$loadCount hits=$hitCount)",
                )
                entries.clear()
            }
        }
    }
}
