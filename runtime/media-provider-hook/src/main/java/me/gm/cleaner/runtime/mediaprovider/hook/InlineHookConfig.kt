package me.gm.cleaner.runtime.mediaprovider.hook

object InlineHookConfig {

    /**
     * 单次 JNI 调用原子应用策略三维度（挂载点集合 + 记录偏好 + BPF 拦截范围开关），
     * 消除分次调用间"新挂载点配旧偏好/旧开关"的不一致窗口。
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
