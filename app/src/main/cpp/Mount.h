#ifndef MOUNT_H
#define MOUNT_H

#define REMOUNT_STORAGE

#include <jni.h>

namespace Mount {
    jboolean bind_mount(JNIEnv *env, jclass clazz, jint pid, jint uid,
                        jboolean unmountDataRestriction,
                        jboolean fuseBypass, jobjectArray jsources, jobjectArray jtargets);

    jstring bind_mount_status(JNIEnv *env, jclass clazz, jint pid, jint uid,
                              jboolean unmountDataRestriction,
                              jboolean fuseBypass, jobjectArray jsources, jobjectArray jtargets);

    /// 冲突处置专用 reset：进入目标 namespace 重建 /storage baseline，全程零 JNI。
    /// 返回 JSON（与 bind_mount_status 同 schema）。
    ///
    /// 背景（tombstone_31，2026-11-13）：bind_mount_internal 的 JNI 调用（含
    /// "Mount as user wish" 循环头的 GetArrayLength）发生在 fork 之后 —— fork 出的
    /// 挂载子进程没有可达的 ART runtime，任何 JNI 调用都是解引用空句柄
    /// （SEGV_MAPERR @ 0xc = mirror::Array::length_ 的偏移）。子进程崩溃 ⇒
    /// RESULT 永不到达 ⇒ 父进程 child_result_timeout ⇒ dirty=true ⇒ 误杀目标应用。
    /// 冲突处置需要**真实**的 baseline 重建（清掉同 pid 上旧包的挂载），不能走
    /// 零规则快速路径，因此单列本入口：只传 pid/uid 两个标量，fork 后零 env->。
    jstring bind_mount_reset(JNIEnv *env, jclass clazz, jint pid, jint uid);
}

#endif // MOUNT_H
