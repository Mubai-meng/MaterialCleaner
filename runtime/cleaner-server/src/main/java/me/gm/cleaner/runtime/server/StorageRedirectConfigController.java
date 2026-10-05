package me.gm.cleaner.runtime.server;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Set;

import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway;
import me.gm.cleaner.runtime.server.observer.PackageInfoMapper;
import me.gm.cleaner.runtime.server.VfsRuntimeConfigStore;

/**
 * Handles storage redirect configuration change commands.
 *
 * CleanerService remains the Binder facade; this controller owns the side
 * effects needed after config writes: preference reload, snapshot publishing,
 * Hook refresh and VFS remount.
 */
public class StorageRedirectConfigController {
    private static final String TAG = "StorageRedirectConfigController";

    private final CleanerServer mServer;

    public StorageRedirectConfigController(final CleanerServer server) {
        mServer = server;
    }

    public void onPreferencesChanged() {
        final var previousPackages = currentStorageRedirectPackages();
        reloadSharedPreferencesFromDisk();
        // 顺序治理：先构建并更新内存策略（Mounter 的数据源），
        // VFS remount 先切，发布快照与 Hook 刷新后置——上层切换不领先于底层挂载视图。
        final var snapshot = VfsRuntimeConfigStore.INSTANCE.refreshPolicy();
        remountAffectedStorageRedirectPackages(previousPackages);
        SnapshotPublisher.INSTANCE.publishRedirectPolicy(snapshot);
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    public void onStorageRedirectChanged() {
        final var previousPackages = currentStorageRedirectPackages();
        ServicePreferences.INSTANCE.invalidateSrCache();
        // 顺序治理（关键）：策略刷新必须**先于**映射表失效。
        // PackageInfoMapper 的映射表是惰性重建的——下一次 getUid() 调用才真正建表，
        // 而建表读的是当时的 VfsRuntimeConfigStore 策略。如果先 invalidate 再 refreshPolicy，
        // 两者之间任何一次 getUid()（logcat 观察线程在每个进程启动时都会调用）
        // 都会用**旧**包集合建表并把 sInitialized 置回 true，此后该包永远无法解析，
        // 进程启动事件被静默丢弃、bind mount 不再触发，且全程不产生任何日志。
        final me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot snapshot =
                VfsRuntimeConfigStore.INSTANCE.refreshPolicy();
        PackageInfoMapper.invalidate();
        // 同 M1：refreshPolicy 前置 → VFS 先切 → 发布同一份策略快照 → Hook 异步跟进。
        remountAffectedStorageRedirectPackages(previousPackages);
        SnapshotPublisher.INSTANCE.publishStorageRedirectPolicySet(snapshot);
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    public void onReadOnlyChanged() {
        ServicePreferences.INSTANCE.invalidateReadOnlyCache();
        final var snapshot = VfsRuntimeConfigStore.INSTANCE.refreshPolicy();
        SnapshotPublisher.INSTANCE.publishReadOnly(snapshot);
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    private LinkedHashSet<String> currentStorageRedirectPackages() {
        return new LinkedHashSet<>(
                VfsRuntimeConfigStore.INSTANCE.getStorageRedirectPackages()
        );
    }

    private void remountAffectedStorageRedirectPackages(Set<String> previousPackages) {
        final var affectedPackages = new LinkedHashSet<String>();
        if (previousPackages != null) {
            affectedPackages.addAll(previousPackages);
        }
        affectedPackages.addAll(VfsRuntimeConfigStore.INSTANCE.getStorageRedirectPackages());
        if (!affectedPackages.isEmpty()) {
            mServer.vfsLayerController.remount(affectedPackages.toArray(new String[0]));
        }
    }

    /**
     * SharedPreferences.Editor.apply() may notify before data reaches disk.
     * The server process explicitly reloads the backing preferences before it
     * rebuilds policy snapshots.
     */
    private void reloadSharedPreferencesFromDisk() {
        try {
            final var sps = new SharedPreferences[]{
                    ServicePreferences.INSTANCE.getPreferences()
            };
            final var spImplCls = Class.forName("android.app.SharedPreferencesImpl");
            final var method = spImplCls.getDeclaredMethod("startLoadFromDisk");
            method.setAccessible(true);
            for (final var sp : sps) {
                method.invoke(sp);
            }
        } catch (final ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                       InvocationTargetException e) {
            Log.w(TAG, "Failed to reload SharedPreferences", e);
        }
    }
}
