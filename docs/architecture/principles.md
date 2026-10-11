# MaterialCleaner 设计理念

> 本文档回答“为什么”，而非“怎么做”。
> 不可变量与判断规则见 `spec.md`，当前实现快照见 `current.md`。

---

## 一、核心目标

本项目的设计不是追求更多模块、更抽象的对象或更热门的框架，而是：

```text
减少语义源 + 缩短热路径 + 强化失败隔离 + 让三个执行平面可验证可收敛
```

任何新增结构必须能回答“为什么非它不可”，而不是“看起来更清晰”。

---

## 二、为什么 Store 是唯一配置事实源

配置是唯一被用户意志决定的。
如果允许多处直接写持久化文件，就会产生“两个地方都以为自己是真相”。
CAS revision 是把并发写从混乱变成可重放的工具。

---

## 三、为什么 `OrderedRedirectInterpreter` 是唯一语义解释源

同一组 RedirectRule 在 VFS、Hook、FUSE 上若解释不同，系统就会在不同路径看到不同状态。
统一解释源才能让三面可互换、可对比、可回归测试。
UI 也只能消费 canonical 结果，不能自己造一套。

---

## 四、为什么 RuntimePolicyProjector 是唯一投影入口

配置是持久事实，运行时是当前视图。
如果 VFS/Hook/FUSE 各自读配置重新计算，它们会因为 timing 不同而永久分叉。
Projector 负责把配置在一个时刻投影成 Runtime Snapshot，再由三个平面独立消费并最终收敛。

---

## 五、为什么 VFS / Hook / FUSE 是并行的而不是 fallback

它们解决的是不同观察点：

```text
VFS        → 进程看到的路径系统
Hook       → MediaProvider/Framework 的行为语义
FUSE Native → 底层原生 I/O 路径
```

缺哪个都不能互相替代。
把它们写成串行 fallback 会让一层失败扩大成整体降级。

---

## 六、为什么 DataBus 只做传输

Hook 热路径不能依赖 Server 活性。
DataBus 的职责是让 Hook 在离线时仍能读到最后一份稳定 snapshot。
如果 DataBus 开始解释 Policy，它就和 Projector 抢了同一份语义来源，最终两者都不会被别人信任。

---

## 七、为什么 DataBusProtocol 不拥有物理传输布局

物理布局是实现细节，协议是跨进程契约。
把 `CLEANER_ROOT / BUS_ROOT` 塞进 protocol 会让“目录改名”污染所有协议消费者。
这类变化应该由 DataBus 自己消化，而不是强制上层重新编译。

---

## 八、为什么批量提交是结构化而非 Boolean

`StoragePolicyBatchEdit` 本质是两个独立 domain write。
两者之间没有事务性，局部成功是真实可能发生的。
`Boolean` 会把 PARTIAL 伪装成整体失败，让调用方无法区分“全坏”与“半坏”。
结构化结果让失败模式可诊断、可恢复。

---

## 九、为什么 PARTIAL 不可达时只做防御分支

当前生产入口都是单域提交，理论上不可能 PARTIAL。
但如果未来出现双域批量，API 已经表达了这个状态，调用方只需响应，不必再扩展 Result 类型。
因此 PARTIAL 被保留在模型中，但当前不做 UI 交互或重试。

---

## 十、为什么 CORRUPT 不能被当成 empty policy

empty policy 是正常业务状态，CORRUPT 是存储层故障。
把 CORRUPT 降级成 empty 会让系统静默清空挂载链，看起来像成功但实际全不生效。
当前两入口行为不一致是已知非对称，记录即可，不伪装成正常。

---

## 十一、为什么 Hook 的 redirect/policy decision 热路径禁止 Binder/IO/反射等逐调用开销

MediaProvider 是系统进程，Hook 在它内部运行。
一旦热路径请求 Server、读磁盘、JSON 解析或动态反射，就会扩散到所有依赖 MediaProvider 的进程。
热路径的职责是“用本地缓存做快速判断”，重活交给发布时一次性完成的 Projector。

---

## 十二、为什么不继续扩张模块/抽象层

架构边界已经足够让每个问题有明确 owner。
继续加 Manager / Engine / Module 只会让调用链更绕，但不会减少语义源。
只有当新职责证明自己拥有独立生命周期或独立依赖边界时，才允许拆出物理边界。
