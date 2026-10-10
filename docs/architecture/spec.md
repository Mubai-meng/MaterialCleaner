# MaterialCleaner 架构规范 

> Status: Active
> Scope: 全局架构、Storage Redirect、Runtime、DataBus、MediaProvider Hook、配置与演进约束
> 本规范约束新代码、重构与跨层依赖，不是任务清单。

---

## 一、核心不变量（任何变更不得破坏）

1. **配置唯一事实源**是 Store 接口及其持久化实现（`ConfiguredPolicyStore` / `FileConfiguredPolicyStore`），任何持久化后的 Storage Policy 必须经过它。
2. **重定向语义唯一解释源**是 `OrderedRedirectInterpreter`（经 `MountPlanDeriver` 投影桥），任何执行面不得重新实现规则解释。
3. **运行时投影唯一入口**是 `RuntimePolicyProjector`，VFS / Hook / FUSE 不得从配置文件各自重推运行时状态。
4. **三个执行面是并行的**：VFS / MediaProvider Java Hook / FUSE Native 解决不同访问观察点，不是三级 fallback；允许暂时不同步，但必须单调收敛。
5. **DataBus 仅做传输**：快照、信号、事件、cursor、lease、health。它不知道任何 `RedirectRule`、`MountRules`、`RuntimePolicyProjector` 类型。
6. **DataBusProtocol 不拥有物理传输布局**：`BUS_ROOT`、目录准备、原子写、事件序号计数等属 `DataBus` 实现侧。
7. **Hook 热路径的 redirect/policy decision path 依赖本地不可变缓存**，禁止 Binder/Room/JSON解析/动态反射/大量日志等逐调用开销。
8. **LSPosed 是进程注入机制**，不是 Policy Engine、Mount Manager 或 DataBus Owner。

---

## 二、关键区分（避免概念混淆）

| 概念 | 定义 | 不得混用 |
|---|---|---|
| `revision` | 配置语义版本（`ConfiguredPolicyStore` 返回） | `generation` / `epoch` |
| `generation` | 一次运行时投影/发布实例 | `revision` |
| `epoch` | 追踪某次发布上下文的标识 | `revision` / `generation` |
| `StoragePolicy` | 用户配置的事实 | 运行时执行状态 |
| `RedirectPolicySnapshot`（概念上的 Runtime Policy Snapshot） | 当前运行时应该看到的执行投影 | 原始配置 |
| `PlanHash` | 执行计划指纹 | 新 Policy Source |

---

## 三、行为边界（灰色地带必须说明理由）

### Domain 层纯度
禁止以下依赖进入 `core:storage-redirect-domain`：
`Android API`、`Binder`、`DataBus`、`SharedPreferences`、`Runtime 状态对象`。

### DataBus 边界
允许序列化 runtime representation，但禁止：
- `DataBus → OrderedRedirectInterpreter`
- `DataBus → RuntimePolicyProjector`
- `DataBus → RedirectRule / MountRules`

### App / Control Plane 权限
允许：修改配置、读状态、请求重投影/重挂载。
禁止：直接写 DataBus、直接操作 VfsRuntimeConfig、自己解释 RedirectRule。

### 批量提交语义
`StoragePolicyBatchEdit` 是非原子的，两域独立提交，先后失败互不回滚。
`commitStructured()` 的返回必须显式消费三态：
- `SUCCESS`：正常处理
- `PARTIAL`：防御性分支（当前生产入口理论上不可达），日志两域结果后返回，不做 UI 交互或重试
- `FAILURE`：记录 stageFailed 与域错误后返回

### CORRUPT 处理
配置源 CORRUPT 不可伪装为 empty policy。
当前已知非对称性（可暂留，但必须记录）：
- `StoragePolicyChangeCoordinator`：阻断副作用、保 last-known-good
- `SnapshotPublisher`：直接抛出（当前无兜底）
未出现三个真实调用点前不建立统一协议。

### 新代码准入
新增类/模块必须回答：
1. 属于哪一层？（Control / Config / Policy / Projection / Execution / Transport）
2. 是否已有职责承载？如有，不新建
3. 是否定义新 Policy Semantic？如有，必须复用 canonical interpreter
4. 是否引入跨层依赖？如有，默认拒绝
5. 是否进入热路径？如有，必须声明 allocation / IO / Binder / reflection / logging 成本
6. 是否满足独立生命周期 / 独立依赖边界 / 有实际复用价值三者之一？否则不得创建

---

## 四、已知但当前不启动（仅记录，未触发前不动）

以下项已经完成评估，触发信号出现前不启动：

| 问题 | 当前状态 | 触发重新评估的信号 | 预估成本 | 已验证替代方案 |
|---|---|---|---|---|
| `ServicePreferences` 深层迁移 | 兼容 façade + 普通 preferences | 新的强依赖或字段膨胀失控 | 中 | 保持 façade，新字段走 Store |
| `CORRUPT` fallback 统一 | 两个入口已知非对称 | 第三调用家族或真实 incident | 低 | Coordinator保last-known-good为统一入口 |
| `behaviorRevision` 引入 | `redirectRevision` / `readOnlyRevision` 存在 | behavior 进入 CAS 冲突协议 | 低 | 暂复用运行时 epoch |
| `PathPolicyEvaluator` 接入生产 | 只读 reduced，仅测试消费 | 需要路径级权限判定 | 中 | 调用 canonical interpreter 的 deriveMountedPath |
| `DataBusProtocol` 职责膨胀 | 当前承载命名/白名单/数据模型/健康口径 | runtime state / metrics / transport state 需求 | 低 | 新增独立对象，不扩大 DataBusProtocol |
| `Runtime Snapshot` 继续拆对象 | 单一 publication envelope | 独立生命周期需求 | 高 | 保持 envelope，新字段并入 RuntimeBehaviorPolicy |
| 历史 Legacy Model 全部清除 | domain 层已删，仅存 UI 模板与测试 oracle | 确认无 runtime consumer | 极低 | 物理删除 oracle 文件 |

---

## 五、变更规则

1. **语义优先于实现**：任何涉及 redirect semantics / mount path / readOnly semantics 的重构，必须先建立 differential harness 验证语义等价，再迁移实现。
2. **四真相不可替代**：`Policy Truth`、`Projection Truth`、`Execution Truth`、`Convergence Truth` 是四个不同维度，诊断时必须分别判断，不能用一个替代其他。
3. **不为形式主义新增抽象**：不创建独立生命周期不清晰的 Manager / Engine / Helper 层。
4. **不破坏冻结边界**：没有真实问题，不新增第四个执行面、不重做 DataBus、不碰 CAS 语义、不拆 FileConfiguredPolicyStore。
