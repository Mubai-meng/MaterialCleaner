#pragma once

#include <jni.h>
#include <string>

namespace bpf_hook {

    std::string Hook(void *handle, bool fuseLibraryMapped);

    void setMountPoint(JNIEnv *env, jclass clazz, jobjectArray value);

    void setRecordExternalAppSpecificStorage(JNIEnv *env, jclass clazz, jboolean value);

    /** 决策 D1 开关：是否连 `fuse_bpf_fill_entries` 的非移除语义一并按策略拦截（移除语义恒放行）。 */
    void setFuseBpfBlockAll(JNIEnv *env, jclass clazz, jboolean value);

    /** 单次调用下发策略三维度（挂载点集合 + 记录偏好 + BPF 拦截范围开关）。 */
    void commitPolicy(JNIEnv *env, jclass clazz, jobjectArray value, jboolean record,
                      jboolean block_all);
}  // namespace bpf_hook
