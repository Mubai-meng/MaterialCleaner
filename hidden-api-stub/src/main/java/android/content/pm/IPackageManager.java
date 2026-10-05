package android.content.pm;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import androidx.annotation.RequiresApi;

import java.util.List;

public interface IPackageManager extends IInterface {

    boolean isPackageAvailable(String packageName, int userId) throws RemoteException;

    boolean getApplicationHiddenSettingAsUser(String packageName, int userId) throws RemoteException;

    ApplicationInfo getApplicationInfo(String packageName, int flags, int userId)
            throws RemoteException;

    @RequiresApi(33)
    ApplicationInfo getApplicationInfo(String packageName, long flags, int userId)
            throws RemoteException;

    PackageInfo getPackageInfo(String packageName, int flags, int userId)
            throws RemoteException;

    @RequiresApi(33)
    PackageInfo getPackageInfo(String packageName, long flags, int userId)
            throws RemoteException;

    int getPackageUid(String packageName, int flags, int userId) throws RemoteException;

    @RequiresApi(33)
    int getPackageUid(String packageName, long flags, int userId) throws RemoteException;

    String[] getPackagesForUid(int uid)
            throws RemoteException;

    ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId)
            throws RemoteException;

    /**
     * @deprecated Android 17 (API 37) 把该重载的返回类型从
     * {@code ParceledListSlice} 改成了 {@code PackageInfoList}
     * （{@code PackageInfoList extends ParceledListSlice<PackageInfo>}），
     * 因此这里的声明在 API 37 上不再匹配，直连调用会抛
     * {@code NoSuchMethodError: No interface method
     * getInstalledPackages(JI)Landroid/content/pm/ParceledListSlice;}。
     * <p>
     * 桩保持旧签名以维持 API 33~36 的直连快路径；
     * API 37 由 {@code api.SystemService#getInstalledPackages} 反射兜底。
     */
    @RequiresApi(33)
    ParceledListSlice<PackageInfo> getInstalledPackages(long flags, int userId)
            throws RemoteException;

    ParceledListSlice<ApplicationInfo> getInstalledApplications(int flags, int userId)
            throws RemoteException;

    /**
     * Android 17 (API 37) 未变更该重载（仍返回 {@code ParceledListSlice}），
     * 与 {@link #getInstalledPackages(long, int)} 的变化形成对照。
     */
    @RequiresApi(33)
    ParceledListSlice<ApplicationInfo> getInstalledApplications(long flags, int userId)
            throws RemoteException;

    int getUidForSharedUser(String sharedUserName)
            throws RemoteException;

    void grantRuntimePermission(String packageName, String permissionName, int userId)
            throws RemoteException;

    void revokeRuntimePermission(String packageName, String permissionName, int userId)
            throws RemoteException;

    int getPermissionFlags(String permissionName, String packageName, int userId)
            throws RemoteException;

    void updatePermissionFlags(String permissionName, String packageName, int flagMask, int flagValues, int userId)
            throws RemoteException;

    int checkPermission(String permName, String pkgName, int userId)
            throws RemoteException;

    int checkUidPermission(String permName, int uid)
            throws RemoteException;

    IPackageInstaller getPackageInstaller() throws RemoteException;

    int installExistingPackageAsUser(String packageName, int userId, int installFlags,
                                     int installReason) throws RemoteException;

    @RequiresApi(29)
    int installExistingPackageAsUser(String packageName, int userId, int installFlags,
                                     int installReason, List<String> whiteListedPermissions) throws RemoteException;

    ParceledListSlice<ResolveInfo> queryIntentActivities(Intent intent,
                                                         String resolvedType, int flags, int userId) throws RemoteException;

    @RequiresApi(33)
    ParceledListSlice<ResolveInfo> queryIntentActivities(Intent intent,
                                                         String resolvedType, long flags, int userId) throws RemoteException;

    ProviderInfo resolveContentProvider(String name, int flags, int userId);

    @RequiresApi(33)
    ProviderInfo resolveContentProvider(String name, long flags, int userId);

    boolean performDexOptMode(String packageName, boolean checkProfiles,
                              String targetCompilerFilter, boolean force, boolean bootComplete, String splitName)
            throws RemoteException;

    abstract class Stub extends Binder implements IPackageManager {

        public static IPackageManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
