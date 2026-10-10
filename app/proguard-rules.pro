-repackageclasses "me.gm.cleaner"
-allowaccessmodification
-overloadaggressively

# ══════════════════════════════════════════════════════════════════════
# 全应用保留原始类名 —— 不可收窄为「只保契约类」
#
# 来历：本条原本写在已被上游删除的 :shared 模块的 consumer-rules.pro 里，
# app 依赖 project(':shared') 时会自动并入本次 R8 运行。上游把 :shared 的
# 类搬进 core:common 并删除该模块（core:common 未声明 consumerProguardFiles）
# ⇒ 本条随之失效 ⇒ R8 首次开始改名+收缩应用自身代码（3894→2957 类，
# dex −904 KB）⇒ release 整机失效：
#     重定向一直「正在启动」、已配置应用「未知状态」，
#     app 侧日志仅 E MC/Launcher: launch(RECOVERY): binder timeout
#
# 机理：本项目有若干条**从 R8 视野之外按类名寻址**的入口。R8 只能看见
# Dex 内部的引用，看不见 native 字符串、exec 参数与 Xposed 元数据，
# 因此这些类会被判为「无用」而被改名甚至整类删除：
#   1) exec 入口（本次直接故障）：app/src/main/cpp/starter/starter.cpp 的
#        SERVER_CLASS_PATH = "me.gm.cleaner.runtime.server.CleanerServerLoader"
#      由 execvp("/system/bin/app_process -Djava.class.path=<apk> <class>")
#      拉起 cleaner_server 进程。该类的 main() 在 Dex 内 0 引用 ⇒ R8 整类
#      删除（mapping 无类行、usage.txt 有记录）⇒ 子进程启动即
#      ClassNotFoundException ⇒ binder 永不就绪 ⇒ 上述 binder timeout。
#   2) LSPosed 入口：assets/xposed_init
#        = me.gm.cleaner.runtime.mediaprovider.hook.XposedInit
#      同样被从 classes.dex 整类删除（main.jar 内另有副本，但 APK 侧入口
#      不缺失才是既有行为，不应依赖 main.jar 的加载时序）。
#   3) JNI 入口：libinline.so / libcleaner.so 内的 FindClass
#        "me/gm/cleaner/runtime/mediaprovider/hook/InlineHookConfig"
#        "me/gm/cleaner/core/common/RuntimeFileUtils"
#      本次侥幸未坏 —— 仅仅因为这两个类恰好声明了 native 方法，被下面
#      `-keepclasseswithmembernames … class * { native <methods>; }` 顺带
#      保住了类名。这是**偶然**而非保证：任何未声明 native 方法的
#      JNI 目标类都会以同样方式失效。
#
# 结论：隐式入口无法穷举，故此处保持全局不混淆。代价是 dex 不再收缩
# （约 +0.9 MB），换来设备端栈帧可读 —— 本项目排障完全依赖真机日志，
# 此项优先于体积。若将来要重新开启混淆，必须先为上面每一处（以及未来
# 新增的每一处）隐式入口补显式 -keep，并用
# .tmp-verify/check_entry_points.py 做二进制闭合验证后方可收窄。
# ══════════════════════════════════════════════════════════════════════
-keep class me.gm.cleaner.** { *; }

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
#
# 注：本条已被上面的 -keep class me.gm.cleaner.** 完全覆盖，单独保留是
# 为了把「跨进程契约」这一独立意图固化在规则文件里可检索；即使将来
# 全局规则被收窄，本条也必须留下。
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
