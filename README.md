# ReMap

基于 **bind mount + Xposed Hook** 的 Android 存储重定向工具。它把应用对共享存储的访问导向你指定的目录，同时对「不可访问」「只读」路径施加访问保护；规则按应用配置，可导出、导入与随包模板套用。

本仓库是面向 **OnePlus 15（PLK110）/ ColorOS 17 / Android 17** 的 MaterialCleaner 适配分支，应用名为 **ReMap**（旧名「质感清理」），应用内「检查更新」与「关于」均指向本仓库。上游项目见 [firesahc/MaterialCleaner](https://github.com/firesahc/MaterialCleaner)。当前版本 **6.2.4（versionCode 624）**。

## 运行环境

| 项目 | 要求 |
| --- | --- |
| Android | 8.0+（API 26）；适配目标为 ColorOS 17 / Android 17 |
| Root | KernelSU / APatch / Magisk（适配目标为 KernelSU） |
| Xposed | LSPosed，加载 libxposed API 102 模块 |
| 架构 | 发布包为 arm64-v8a；源码可构建全 ABI 通用包 |
| 语言 | 简体中文、繁体中文、英文（默认跟随系统） |

> 编译参数为 targetSdk 34、NDK 26.1.10909125、CMake 3.22.1。版本号的单一真源是根 `build.gradle` 中的 `ext.versionCode` / `ext.versionName`。

## 核心功能

- **存储重定向**：在目标进程的 mount namespace 内执行 `mount --bind`，把应用访问的目录映射到承载目录。
- **三层协同**：VFS bind mount、MediaProvider Java Hook（MediaStore query / insert / scan）、FUSE Native Hook（libfuse_jni.so）；规则快照与事件通过 DataBus 文件数据面分发。
- **访问保护**：「不可访问」路径拒绝受控新访问；「只读」路径递归拒绝内容或元数据变更。
- **规则辅助**：文件系统记录 + 向导式规则建议 + 规则模板，新应用安装时可自动套用模板。
- **媒体库写入控制**（Android 10+）：「监控媒体库写入」只记录、不拦截（按包 10 秒节流）；「拦截媒体库写入」在目标路径命中「不可访问」或「只读」规则时拒绝写入并返回空值。两者独立、均默认关闭。
- **运行状态诊断**：主界面状态卡展示 VFS、MediaProvider Hook、FUSE Native Hook、DataBus、控制面五层健康状态；列表菜单可分享诊断归档。
- **界面**：Material 3、深色/黑色主题，三语言。

## 工作原理

```
┌──────────────────────┐       Binder        ┌──────────────────────┐
│  cleaner_server      │◄───────────────────►│  App Process         │
│  (root, app_process) │ HooksBridgeProvider │  UI / Binder Bridge  │
│  VFS / observers     │                     └──────────┬───────────┘
│  LayerOrchestrator   │                                │ Binder
└──────────┬───────────┘                                ▼
           │ DataBus                          ┌──────────────────────┐
           ▼                                  │  MediaProvider        │
┌──────────────────────────────┐              │  LSPosed Hook        │
│ /data/local/tmp/cleaner/bus  │◄─────────────►│  Query / Insert /    │
│ snapshots events leases      │              │  FUSE native status  │
└──────────────────────────────┘              └──────────────────────┘
```

控制面 Binder 只承担注册、刷新与诊断兜底；规则快照、挂载点、native 状态、文件事件、重定向提示和 query session lease 经 DataBus 传递。

| 机制 | 层 | 原理 | 依赖 |
| --- | --- | --- | --- |
| bind mount | VFS | `fork() → setns() → mount(MS_BIND)`，在目标 namespace 内建立绑定挂载 | root |
| MediaProvider Java Hook | MediaStore | Hook query / insert / scan 路径，在 Java 层读取 DataBus 策略快照 | LSPosed |
| FUSE Native Hook | FUSE 兼容 | xhook 或 embedded GOT patch 拦截 libfuse_jni.so，消费挂载点快照 | LSPosed + native |

## 安装

1. 从 [Releases](https://github.com/Mubai-meng/MaterialCleaner/releases) 下载签名 APK，文件名形如 `Cleaner_<版本>-arm64-v8a-release-signed.apk`。
2. 直接覆盖安装旧版即可，规则与设置全部保留，无需卸载。
3. 打开 LSPosed Manager → 模块 → 启用 ReMap。
4. 作用域勾选本机 MediaProvider 包，常见为 `com.android.providers.media.module`、`com.google.android.providers.media.module` 或 `com.android.providers.media`。
5. 重启设备。

> 覆盖安装后**必须重启一次**：Hook 运行在系统进程里，LSPosed 在 Zygote 阶段读取模块字节码，当次不会加载新代码。

## 快速上手

1. 主界面「存储重定向」tab → 右上角添加 → 选择目标应用。
2. 为该应用配置映射 / 保留规则；路径不确定时用「跟随向导」从文件系统记录自动补全。
3. 保存后重启目标应用，使其进入新的 mount namespace；若是覆盖安装模块，先重启手机。
4. 回到状态卡确认五层状态。需要排查时，在应用列表菜单选择「诊断归档」分享。

隐藏桌面图标后可通过 adb 重新打开界面：

```bash
adb shell am start -n me.gm.cleaner/.client.ui.ServiceSettingsActivity
```

## 主要设置项

位置：主界面「更多选项」tab。下表为常用项及其源码默认值；key 供对照代码与诊断归档使用。

| 分类 | 配置项（内部 key） | 默认 | 说明 |
| --- | --- | --- | --- |
| 存储重定向 | `aggressively_prompt_for_reading_media_files` | 开 | 提示读取媒体文件 |
| 存储重定向 | `open_wizard_by_default` | 关 | 进入单应用规则页时打开向导 |
| 存储重定向 | `wizard_root_dir_name` | `files` | 向导建议的根目录名 |
| 存储重定向 | `apply_template_on_package_added` | 关 | 新应用安装时自动套用规则模板 |
| 存储重定向 | `auto_logging` | 开 | 写入运行日志 |
| 文件系统记录 | `audit_media_insert` | 关 | 监控媒体库写入，仅 Android 10+ 显示 |
| 文件系统记录 | `deny_media_insert_writes` | 关 | 拦截媒体库写入，仅 Android 10+ 显示 |
| 文件系统记录 | `record_shared_storage` | 关 | 记录共享存储访问 |
| 服务与通知 | `start_on_boot` / `post_notification` / `check_update` | — | 开机启动 / 通知 / 从本仓库检查更新 |
| 界面 | 语言 / 主题 | 跟随系统 | 语言可选 `SYSTEM`、`en-US`、`zh-CN`、`zh-TW` |
| 本模块 | `hide_desktop_icon` / `hide_recents` | 关 | 隐藏桌面图标 / 隐藏最近任务 |

> 「FUSE BPF 拦截范围」开关已在 6.0.5 移除（对结果无实际影响），仅保留字符串与偏好字段用于兼容旧配置，界面中不再出现。

## 从源码构建

```bash
# Debug
./gradlew :app:assembleDebug

# Release（仅 arm64-v8a）
./gradlew :app:assembleArm64Release
```

- 产物：`app/build/outputs/apk/release/Cleaner_6.2.4-release.apk`；文件名带版本号，不带 ABI 与 `signed` 后缀。ABI 由任务名收敛，普通 `assembleRelease` 产出全 ABI 通用包。
- 仓库不含签名配置，发布 APK 需自行使用密钥做 V2 + V3 签名。
- `buildXposedMainJar` 会把 Hook runtime 及随行模块经 d8 打成 `assets/main.jar`，并执行锚点类门禁。
- 改代码请同步递增 `ext.versionCode` / `ext.versionName` 并更新 `CHANGELOG.md`。

单测需六个模块一起跑：

```bash
./gradlew :core:config-store:testDebugUnitTest :core:common:testDebugUnitTest \
  :core:storage-redirect-domain:testDebugUnitTest :runtime:cleaner-server:testDebugUnitTest \
  :runtime:media-provider-hook:testDebugUnitTest :app:testDebugUnitTest
```

## 目录结构

```
.
├── app/                         # 主 App：UI、ViewModel、native 打包
│   ├── src/main/cpp/            # libcleaner（mount / VFS）、libinline（FUSE Hook + xhook）、starter
│   └── src/main/assets/         # main.jar（构建时生成）
├── core/
│   ├── ipc-contract/            # AIDL 跨进程契约
│   ├── common/                  # 通用工具与错误码
│   ├── config-store/            # 配置存储与安全初始化
│   ├── storage-redirect-domain/ # 重定向领域模型与策略推导
│   └── storage-redirect-databus/# DataBus 快照、事件、lease、游标
├── runtime/
│   ├── cleaner-server/          # root server、VFS、observer、consumer、orchestrator
│   └── media-provider-hook/     # LSPosed MediaProvider Hook runtime（打进 main.jar）
├── platform/hidden-api/         # 隐藏 API 桥接与 SystemService 封装
├── hidden-api-stub/             # 隐藏 API 编译桩，仅 compileOnly
├── docs/
│   ├── architecture/            # 当前架构、规范、设计理念、ADR 状态
│   ├── adr/                     # 架构决策记录（15 篇）
│   └── adapter/                 # 适配诊断与实机日志分析
├── scripts/
│   ├── gates/                   # 结构质量门禁（Windows PowerShell）
│   └── device/                  # 实机探针：备份 / 恢复 / 冒烟 / 日志收集等
├── .github/                     # issue 模板与 build-gate 工作流
├── build.gradle                 # 版本与依赖单一真源
├── CHANGELOG.md                 # 版本变更记录
├── CONTEXT.md                   # 存储策略术语表（也是门禁的词汇规范来源）
└── README.md
```

## 已知限制与常见问题

- **LSPosed 必需**：MediaProvider Java Hook 与 FUSE Native Hook 依赖 LSPosed 把模块加载到 MediaProvider。
- **定制 ROM 兼容性**：部分系统（如 MIUI / HyperOS）可能因 SELinux 策略导致 bind mount 失败。
- **FUSE Native Hook 依赖平台形态**：Android 版本、APEX MediaProvider 与 libfuse_jni.so 的加载方式会影响可用 Hook 模式。
- **系统图片选择器出现空白格**：这通常是系统侧 `picker.db` 残留了未挂载卷的条目，不是本模块造成，本版不改代码处理。需要 root / adb shell 时可重建索引：

  ```bash
  adb shell content read --uri 'content://media/cli?cmd=cloud-provider%20reset-library'
  adb shell content read --uri 'content://media/cli?cmd=cloud-provider%20sync-library'
  ```

  第一条清除索引并重置同步状态，第二条立即触发重建。注意重建是让空白格从列表消失，不会让照片回来；本机相册服务全量同步只回传图片，执行后原视频条目会被清掉且不会自动补回，建议先备份 `picker.db`。

## 参与贡献

- 提交问题请使用仓库的 bug report / feature request issue 模板（空白 issue 会被自动关闭）；一般提问走 Discussions。
- 术语请遵循 `CONTEXT.md`，结构质量门禁可用 `./scripts/gates/Run-Gates.ps1` 运行（Windows PowerShell）。
- 设备侧脚本在 `scripts/device/`，只读优先，写操作需二次确认后再执行。
- 文档入口：`docs/architecture/current.md`（当前架构）、`docs/adr/`（决策记录）、`CHANGELOG.md`（版本记录）。
- 仓库目前未提供 LICENSE 与 CONTRIBUTING 文件，贡献前请先与维护者沟通授权方式。

## 致谢与赞助

上游项目：[firesahc/MaterialCleaner](https://github.com/firesahc/MaterialCleaner)。

如果觉得软件好用可以赞助作者。
<img width="240" height="304" alt="IMG_20261011_112622" src="https://github.com/user-attachments/assets/98f9cd4b-68ff-4233-9dbb-e69c454839b9" />
<img width="240" height="302" alt="IMG_20261011_112539" src="https://github.com/user-attachments/assets/f1b353d6-537a-4833-a253-249fc5b15784" />
