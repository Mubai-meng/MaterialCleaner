package me.gm.cleaner.runtime.mediaprovider.hook

object InlineHookConfig {

    /**
     * 单次 JNI 调用下发策略三维度（挂载点集合 + 记录偏好 + BPF 拦截范围开关），
     * 消除分次调用间“新挂载点配旧偏好/旧开关”的不一致窗口。
     *
     * 注意：此处“单次下发”指清单次 commit 调用，而非 CPU 原子事务；
     * native 侧按固定顺序依次写入三个原子量，中途无锁，崩溃可能留下半更新。
     * 方法名保持混淆短名 `a`，签名必须与 native 注册的
     * `([Ljava/lang/String;ZZ)V` 严格一致。
     */
    private external fun a(value: Array<String>, record: Boolean, blockAll: Boolean)

    fun commitPolicy(
        mountPoints: Array<String>,
        recordExternalAppSpecificStorage: Boolean,
        fuseBpfBlockAll: Boolean,
    ) = a(mountPoints, recordExternalAppSpecificStorage, fuseBpfBlockAll)

    private external fun init(): String

    fun initializeXHook(): String = init()
}
