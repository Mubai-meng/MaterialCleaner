# MaterialCleaner — 质感清理

> 一款 Android 存储重定向工具，通过 bind mount + Xposed Hook 实现文件系统级路径重定向。
> 需要 Root（KernelSU / APatch / Magisk）和 LSPosed 框架。

## 适配说明（ColorOS 17 / Android 17 ）

本仓库为 MaterialCleaner 的适配分支，目标运行环境：

- **设备 / ROM**：OnePlus 15（PLK110）/ ColorOS 17
- **Android 版本**：Android 17（API 37）
- **Root 方案**：KernelSU（国行）
- **Xposed 框架**：libxposed API 102
- **构建产物**：`versionCode=500` / `versionName=5.0.0`，单 `arm64-v8a` ABI

相对上游的主要改动方向（非侵入式，优先新增文件/类）：

- 不可访问路径链路：反解派生、规则下发、FUSE Java Gate 先于重定向拒绝、MediaProvider query SQL 下推与结果集兜底
- 只读规则递归覆盖与判定修正
- 事件死信隔离、挂载状态判定、规则无意义判据后置、应用列表全序排序
- 模块侧错误可见化（诊断归档中的 `errors/` 与 `hookErrors` 段）
- FUSE 热路径性能：进程内有界 UID→包名缓存（LRU + TTL），减少 PackageManager Binder 查询
- 应用列表图标 `LruCache`，避免主线程反复 `loadIcon`
- 若干 native 健壮性修复与日志流关闭加固

> 说明：以上为适配目标的工程改动，实机稳定性请以设备在 ColorOS 17 / Android 17 上的长期观察与诊断归档为准。

## 功能

- **存储重定向**：拦截应用文件读写，将指定路径重定向到其他目录
- **bind mount 机制**：通过 `mount --bind` 在目标进程 mount namespace 内创建路径映射
- **InsertHooker**：Hook MediaProvider.insertFile()，在 ContentValues 层替换 `_data` 列路径
- **FUSE Native Hook**：通过 xhook 或 embedded GOT patch 拦截 libfuse_jni.so，实现 FUSE 路径兼容
- **DataBus 数据面**：通过文件系统快照和事件队列分发规则、状态、通知和短期会话
- **运行状态诊断**：主界面按 VFS、MediaProvider Hook、FUSE Native Hook、DataBus、控制面展示健康状态
- **文件系统记录**：记录应用的文件操作历史，辅助创建挂载规则
- **挂载规则模板**：预设规则模板，新应用安装时自动应用
- **向导式规则创建**：根据文件系统记录自动建议挂载规则
- **三语言支持**：简体中文、繁体中文、英文

## 架构

### 三进程 + DataBus 数据面

```
┌──────────────────────┐       Binder        ┌──────────────────────┐
│  cleaner_server      │◄───────────────────►│  App Process         │
│  (root, app_process) │ HooksBridgeProvider │  UI / Binder Bridge  │
│  VFS / observers     │                     └──────────┬───────────┘
│  LayerOrchestrator   │                                │ Binder
└──────────┬───────────┘                                ▼
           │ DataBus                          ┌──────────────────────┐
           ▼                                  │  MediaProvider        │
┌────────────────────────────────┐                      │  LSPosed Hook        │
│  /data/local/tmp/cleaner/bus   │◄────────────────────►│  Query/Insert/FUSE   │
│  snapshots/events    │                      │  native status       │
│  leases/status       │                      └──────────────────────┘
└──────────────────────┘
```

控制面 Binder 只承担注册、刷新、诊断兜底；规则快照、挂载点、native 状态、文件事件、重定向提示和 query session lease 通过 DataBus 传递。

### 三层重定向机制

| 机制 | 层 | 原理 | 依赖 |
|------|----|------|------|
| **bind mount** | VFS 层 | `fork() → setns() → mount(MS_BIND)` 在目标进程 namespace 中创建绑定挂载 | root 权限 |
| **MediaProvider Java Hook** | MediaStore 层 | Hook query / insert / scan 相关路径，在 Java 层读取 DataBus 策略快照 | LSPosed |
| **FUSE Native Hook** | FUSE 兼容层 | xhook 或 embedded GOT patch 拦截 libfuse_jni.so，消费 configured_mount_points 快照 | LSPosed + libinline |

### 模块结构

```
MaterialCleaner/
├── app/                         # Android App 主模块（UI / ViewModel / native 打包）
├── core/
│   ├── ipc-contract/            # AIDL 与跨进程模型
│   ├── common/                  # 通用运行时工具
│   ├── config-store/            # 服务配置与安全初始化
│   ├── storage-redirect-domain/ # 存储重定向领域模型与策略推导
│   └── storage-redirect-databus/# DataBus 快照、事件、lease、游标
├── runtime/
│   ├── cleaner-server/          # root server、VFS、observer、consumer、orchestrator
│   └── media-provider-hook/     # LSPosed MediaProvider Hook runtime
├── platform/
│   └── hidden-api/              # Android hidden API 桥接与 SystemService 封装
├── app/src/main/cpp/            # native：libcleaner（VFS/mount）、libinline（FUSE Hook + xhook）、starter
├── app/src/main/assets/         # main.jar（buildXposedMainJar 生成；模块元数据在 resources/META-INF/xposed）
├── docs/adr/                    # 架构决策记录（14 篇，状态见 docs/architecture/adr-status.md）
├── scripts/gates/               # 门禁检查脚本
└── MaterialCleaner.wiki/        # GitHub Wiki（中文文档主页）
```

## 环境要求

- **Android 8.0+**（API 26）
- **Root 权限**：KernelSU / APatch / Magisk
- **LSPosed 框架**（用于加载 Xposed 模块到 MediaProvider 进程）
- **NDK**：CMake 3.22.1 + Android NDK 26.1.10909125（版本已 pin，见根 `build.gradle`，不可随意升级）

## 构建指南

### 环境准备

```bash
# Android SDK 路径（Windows）
set ANDROID_HOME=D:\Android\Sdk

# 使用项目自带的 Gradle Wrapper
./gradlew --version
```

### 编译 APK

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease
```

构建过程会自动执行以下步骤：

1. 编译 `core/*`、`platform/*`、`runtime/*` 模块
2. 运行 `buildXposedMainJar` 任务，将 `runtime:media-provider-hook` 及必要依赖通过 d8 转为 DEX 并打包为 `assets/main.jar`
3. 编译 C++ 本地代码（libinline.so、libcleaner.so、starter）
4. 打包为 APK

### 安装

```bash
# 安装 Debug APK
adb install -r app/build/outputs/apk/debug/Cleaner_*-debug.apk

# 强制重启服务器（安装后需要）
adb shell am force-stop me.gm.cleaner
adb shell am start -n me.gm.cleaner/.client.ui.ServiceSettingsActivity
```

### LSPosed 配置

1. 安装 APK 后打开 LSPosed Manager
2. 启用 MaterialCleaner 模块
3. 作用域勾选当前设备的 MediaProvider 包，常见为 `com.android.providers.media.module`、`com.google.android.providers.media.module` 或 `com.android.providers.media`
4. 重启设备

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Kotlin + Material Design 3 + DataBinding + Navigation |
| 架构 | ViewModel + LiveData + Flow + Coroutines |
| 进程通信 | Binder AIDL + ContentProvider + DataBus 文件系统数据面 |
| Xposed | LSPosed + libxposed API 102（本地 jar 依赖） |
| 本地代码 | C++（xhook / embedded GOT patch、mount namespace 操作） |
| 数据库 | Room + SQLite |
| Root | libsu（topjohnwu） |
| 构建 | Gradle + CMake + d8 |

## 关键技术实现

### main.jar 自动构建

Xposed 模块以 `assets/main.jar`（DEX 格式）打包在 APK 中。Gradle task `buildXposedMainJar` 自动把 Hook runtime 及必要依赖转换为 DEX：

```bash
# 手动方式（如不通过 Gradle）：
d8 --min-api 26 --output /tmp/dex runtime/media-provider-hook/build/intermediates/aar_main_jar/debug/classes.jar
cd /tmp/dex && zip main.jar classes.dex
```

### 服务器进程启动

```
App → Shell.cmd(Starter.command) → start.sh → starter (ELF 二进制)
  → execvp app_process → CleanerServerLoader.main()
    → System.loadLibrary("android|compiler_rt|jnigraphics")
    → CleanerServer() → onStorageManagerServiceReady()
      → LayerOrchestrator.initialize()
      → ObserverManager.startAllObservers()
      → SnapshotPublisher.publishAll()
      → DataBus consumers / status scheduler
      → BinderSender.register() → sendBinderToManger()
```

## 已知限制

- **LSPosed 必需**：MediaProvider Java Hook 和 FUSE Native Hook 需要 LSPosed 加载 Xposed 模块到 MediaProvider
- **定制 ROM 兼容性**：部分定制系统（MIUI/HyperOS）可能因 SELinux 策略导致 bind mount 失败
- **FUSE Native Hook 依赖平台形态**：Android 版本、APEX MediaProvider 与 libfuse_jni.so 加载方式会影响可用 Hook 模式
- **main.jar 需随代码更新**：修改 `runtime/media-provider-hook` 后需重新构建，Gradle task 会自动处理



## 赞助

如果觉得软件好用可以赞助作者。

<img width="1260" height="1456" alt="1791686415378" src="https://github.com/user-attachments/assets/2ffd957d-5c6e-47f0-b9de-46ae7de0709b" />
<img width="1549" height="1965" alt="mm_facetoface_collect_qrcode_1791686353720" src="https://github.com/user-attachments/assets/26c17986-7a3f-4ccd-b369-00b428eb976d" />



