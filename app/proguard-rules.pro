-repackageclasses "me.gm.cleaner"
-allowaccessmodification
-overloadaggressively

# ══════════════════════════════════════════════════════════════════════
# AIDL 跨进程契约：Parcelable 的类名与 CREATOR 字段名不可混淆
#
# 故障现象（仅 release 复现，debug 不混淆故不复现）：
#   进入「服务设置 / 文件记录」页即闪退，日志为
#   android.os.BadParcelableException:
#   ClassNotFoundException when unmarshalling: me.gm.cleaner.model.FileSystemEvent
#
# 机理：
#   1) me.gm.cleaner.model 下的 Parcelable 会跨进程传递，例如
#      ICleanerService#queryAllRecords 返回 BulkCursor<FileSystemEvent>；
#   2) 反序列化端 BaseBulkCursor / BaseParceledListSlice 走的是
#      Class.forName(Parcel 中读到的类名) + getField("CREATOR")
#      的反射路径（见 core/ipc-contract/.../model/BulkCursor.java）；
#   3) Xposed 侧 main.jar 由 d8 直接转 DEX、不做混淆，其类名保持原始
#      FQN；而本 APK 经 R8 且顶部启用了 -repackageclasses "me.gm.cleaner"，
#      会把 me.gm.cleaner.model.FileSystemEvent 改名（如 me.gm.cleaner.hg）。
#      于是写入端写入原始类名、读取端按改名后的表查不到类 → 抛异常。
#
# 处置：契约模型整体保留原始类名与成员名（含反射读取的 CREATOR 字段）。
# 新增 model 包下的 Parcelable 无需再改此处，通配规则已覆盖。
# ══════════════════════════════════════════════════════════════════════
-keep class me.gm.cleaner.model.** { *; }

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers class hidden.ProcessObserverAdapter {
    <methods>;
}
-keepclassmembers class hidden.StorageEventListenerAdapter {
    <methods>;
}
-keepclassmembers class hidden.UidObserverAdapter {
    <methods>;
}

# This is generated automatically by the Android Gradle plugin.
-dontwarn android.app.ActivityManagerNative
-dontwarn android.app.ActivityThread$ApplicationThread
-dontwarn android.app.ActivityThread
-dontwarn android.app.AppOpsManager$OpEntry
-dontwarn android.app.AppOpsManager$PackageOps
-dontwarn android.app.ContentProviderHolder
-dontwarn android.app.ContextImpl
-dontwarn android.app.IActivityManager$Stub
-dontwarn android.app.IActivityManager
-dontwarn android.app.IApplicationThread
-dontwarn android.app.IProcessObserver$Stub
-dontwarn android.app.IProcessObserver
-dontwarn android.app.IUidObserver$Stub
-dontwarn android.app.IUidObserver
-dontwarn android.app.ProfilerInfo
-dontwarn android.content.IContentProvider
-dontwarn android.content.pm.ILauncherApps$Stub
-dontwarn android.content.pm.ILauncherApps
-dontwarn android.content.pm.IPackageManager$Stub
-dontwarn android.content.pm.IPackageManager
-dontwarn android.content.pm.ParceledListSlice
-dontwarn android.content.pm.UserInfo
-dontwarn android.ddm.DdmHandleAppName
-dontwarn android.os.IUserManager$Stub
-dontwarn android.os.IUserManager
-dontwarn android.os.ServiceManager
-dontwarn android.os.storage.IStorageManager$Stub
-dontwarn android.os.storage.IStorageManager
-dontwarn android.os.storage.IStorageEventListener$Stub
-dontwarn android.os.storage.IStorageEventListener
-dontwarn android.os.storage.VolumeInfo
-dontwarn android.os.storage.DiskInfo
-dontwarn android.os.storage.VolumeRecord
-dontwarn android.permission.IPermissionManager$Stub
-dontwarn android.permission.IPermissionManager
-dontwarn com.android.internal.app.IAppOpsService$Stub
-dontwarn com.android.internal.app.IAppOpsService
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
