package me.gm.cleaner.runtime.server.hookbridge;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import api.SystemService;
import me.gm.cleaner.runtime.server.ktx.IContentProviderKt;

/**
 * Retrieves the hooks service Binder via the HooksBridgeProvider ContentProvider.
 *
 * <p>Replaces the previous Zygisk custom transaction mechanism. Both the root Server process
 * obtains the app-process bridge through the app's ContentProvider running in the
 * {@code me.gm.cleaner} process.</p>
 */
public class CleanerHooksBinderRetriever {
    private static final String TAG = "HooksBinderRetriever";
    private static final String AUTHORITY = "me.gm.cleaner.hooks_bridge";

    /**
     * Retrieve the {@link me.gm.cleaner.server.ICleanerHooksService} binder from the
     * ContentProvider bridge.
     *
     * @param context A valid context (Server or app process) for ContentResolver access.
     * @return The ICleanerHooksService binder, or {@code null} if the bridge is unavailable.
     */
    public static IBinder get(Context context) {
        // When called from the cleaner_server root process, the standard ContentResolver.call()
        // fails with "Unable to find app for caller" because the root process has no
        // ApplicationThread registered with the ActivityManager.
        // Use SystemService.getContentProviderExternal() to bypass this restriction,
        // same approach used by BinderSender.
        try {
            final var userId = 0;
            final var provider = SystemService.getContentProviderExternal(
                    AUTHORITY, userId, null, AUTHORITY);
            if (provider == null) {
                // 预期瞬态，不是故障：
                //   1) app 进程刚 fork、provider 还没 publish（开机 / 覆盖安装 / 被回收重启）；
                //   2) user 尚未解锁时，非 directBootAware 的 provider 在 AMS 里**不存在**
                //      （第 44 批：UnlockUser finished 之后 4 ms 才 Installing，server 早 1.8 s 发请求）。
                // 5.5.12 起 HooksBridgeProvider 已加 directBootAware，开机窗口内本应首次即成功；
                // 若仍走到这里，说明是"真晚"，由 CleanerHooksClient 的阶梯重试兜底。
                // 真故障（桥永久不存在）由 `startup retry exhausted`（E）单独暴露 —— 降级与
                // 升级成对，避免永久缺 provider 时全程静音。
                Log.w(TAG, "hooks bridge provider not published yet " +
                        "(app booting or user still locked), will retry");
                return null;
            }
            try {
                final var reply = IContentProviderKt.callCompat(
                        provider, null, AUTHORITY, "get_hooks_service", null, null);
                if (reply != null) {
                    return reply.getBinder("binder");
                }
            } finally {
                try {
                    SystemService.removeContentProviderExternal(AUTHORITY, null);
                } catch (final Throwable tr) {
                    Log.w(TAG, "Failed to remove content provider external", tr);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get hooks service", e);
        }
        return null;
    }

}

