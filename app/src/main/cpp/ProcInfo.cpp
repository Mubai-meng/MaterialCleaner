#include <jni.h>
#include <cstring>
#include <string>
#include <vector>

#include "external/abseil-cpp/absl/strings/escaping.h"
#include "external/abseil-cpp/absl/strings/str_split.h"
#include "android-base/stringprintf.h"
#include "linux_syscall_support.h"
#include "obfs-string.h"
#include "FileUtils.h"

using android::base::StringPrintf;

#ifdef DEBUG

#include "logging.h"

#endif

namespace ProcInfo {

    jstring read_cmdline(JNIEnv *env, jclass clazz, jint pid) {
        int fd = sys_open(StringPrintf("/proc/%d/cmdline"_iobfs.c_str(), pid).c_str(), O_RDONLY, 0);
        if (fd < 0) {
            return env->NewStringUTF("");
        }

        char nice_name[PATH_MAX];
        nice_name[sys_read(fd, nice_name, sizeof(nice_name) - 1)] = 0;
        sys_close(fd);
        return env->NewStringUTF(nice_name);
    }

    /**
     * 在任意一行 cgroup 文本里查找 "uid_<d>/pid_<d>" 子串。
     *
     * 刻意**不锚定位置**：cgroup 的层级形态随 Android 版本变化，
     * 见 [read_uid] 的注释。命中返回 true，并给出同段解析出的 pid，
     * 供调用方挑选「属于本进程」的那一行。
     */
    bool scan_uid_pid(const char *line, int *outUid, int *outPid) {
        const char *p = line;
        while ((p = strstr(p, "uid_")) != nullptr) {
            int uid = -1, pidv = -1;
            if (sscanf(p, "uid_%d/pid_%d", &uid, &pidv) == 2 && uid >= 0) {
                *outUid = uid;
                *outPid = pidv;
                return true;
            }
            p += 4;
        }
        return false;
    }

    jint read_uid(JNIEnv *env, jclass clazz, jint pid) {
        // ⚠️ 旧实现用 sscanf("%*[^/]/uid_%d/pid_%d") 把 "uid_" 锚定在
        // **第一个 '/' 之后**，只能匹配 "0::/uid_X/pid_Y" 这一种形态。
        // ColorOS 17 / Android 17 的实际形态是 "0::/apps/uid_X/pid_Y"
        // （libprocessgroup 建的是 /sys/fs/cgroup/apps/uid_X/pid_Y，
        //   多一级 "apps/"，真机日志可见），于是解析恒失败返回 -1。
        //
        // 后果（2026-10-10 第 13 批真机实证）—— 三处调用点全部静默失效：
        //   1. VfsLayerController 的隔离进程过滤：!isIsolated(-1) == true，
        //      WebView 沙箱进程（真 uid 99000、u0a369i0）被算成目标 app 的
        //      NOT_MOUNTED pid ⇒ UI 显示「挂载异常」；
        //   2. Mounter 挂载重试的 PID 复用防护恒触发 ⇒ 延迟挂载重试永不执行；
        //   3. resetAllMounts 恒跳过 ⇒ 挂载永不回滚。
        //
        // 现在改为在整行里查找子串，并优先取 pid 与请求 pid 相同的那一行。
        int cuid = -1;
        int fallbackUid = -1;
        int fd = sys_open(StringPrintf("/proc/%d/cgroup"_iobfs.c_str(), pid).c_str(), O_RDONLY, 0);
        if (fd >= 0) {
            FILE *fp = fdopen(fd, "r"_iobfs.c_str());
            if (fp == nullptr) {
                sys_close(fd);
            } else {
                char line[PATH_MAX];
                while (fgets(line, PATH_MAX - 1, fp) != nullptr) {
                    int uid = -1, cpid = -1;
                    if (scan_uid_pid(line, &uid, &cpid)) {
                        if (cpid == pid) {
                            cuid = uid;
                            break;
                        }
                        if (fallbackUid < 0) {
                            fallbackUid = uid;
                        }
                    }
                }
                fclose(fp);
            }
        }
        if (cuid < 0 && fallbackUid >= 0) {
            cuid = fallbackUid;
        }

        // ── 兜底：/proc/<pid>/status 的 "Uid:" 行是 uid 的权威来源 ──
        // cgroup 层级形态还会继续变，任何形态假设都可能再次失效。兜底把
        // 「解析失败」从「静默返回 -1 让调用方误判」降级为「仍拿到正确 uid」；
        // 只有进程确实已死（两者都读不到）才返回负值，而那正是调用方该排除的情形。
        if (cuid < 0) {
            int sfd = sys_open(StringPrintf("/proc/%d/status"_iobfs.c_str(), pid).c_str(), O_RDONLY,
                               0);
            if (sfd >= 0) {
                FILE *fp = fdopen(sfd, "r"_iobfs.c_str());
                if (fp == nullptr) {
                    sys_close(sfd);
                } else {
                    char line[PATH_MAX];
                    while (fgets(line, PATH_MAX - 1, fp) != nullptr) {
                        if (strncmp(line, "Uid:", 4) == 0) {
                            int uid = -1;
                            if (sscanf(line + 4, "%d", &uid) == 1 && uid >= 0) {
                                cuid = uid;
                            }
                            break;
                        }
                    }
                    fclose(fp);
                }
            }
        }
        return cuid;
    }

    jintArray check_mounts(JNIEnv *env, jclass clazz, jint pid, jobjectArray jtargets) {
        int length = env->GetArrayLength(jtargets);
        if (length == 0) {
            return env->NewIntArray(0);
        }
        std::vector<std::string> mountinfo = {};
        int fd = sys_open(StringPrintf("/proc/%d/mountinfo"_iobfs.c_str(), pid).c_str(), O_RDONLY,
                          0);
        if (fd < 0) {
            return nullptr;
        }
        FILE *fp = fdopen(fd, "r"_iobfs.c_str());
        if (fp == nullptr) {
            sys_close(fd);
            return nullptr;
        }
        struct IsTarget {
            std::string _target;

            explicit IsTarget(std::string target) : _target(std::move(target)) {}

            bool operator()(const std::string &mountinfoLine) const {
                std::vector<std::string> splitLine = absl::StrSplit(mountinfoLine, ' ');
                std::string targetFromMountinfo = splitLine.at(4);
                std::string unescaped;
                absl::CUnescape(targetFromMountinfo, &unescaped);
                return absl::EqualsIgnoreCase(unescaped, _target);
            }
        };
        auto jfirstTarget = (jstring) env->GetObjectArrayElement(jtargets, 0);
        if (jfirstTarget == nullptr) { fclose(fp); return nullptr; }
        const char *firstTarget = env->GetStringUTFChars(jfirstTarget, nullptr);
        if (firstTarget == nullptr) { env->DeleteLocalRef(jfirstTarget); fclose(fp); return nullptr; }
        auto isFirstTarget = IsTarget(firstTarget);
        bool isFirstTargetFound = false;
        char line[PATH_MAX];
        while (fgets(line, PATH_MAX - 1, fp) != nullptr) {
            if (!isFirstTargetFound && isFirstTarget(line)) {
                isFirstTargetFound = true;
            }
            if (isFirstTargetFound) {
                mountinfo.emplace_back(line);
            }
        }
        env->ReleaseStringUTFChars(jfirstTarget, firstTarget);
        env->DeleteLocalRef(jfirstTarget);
        fclose(fp);

        std::vector<int> mounted = {};
        if (!mountinfo.empty()) {
            struct IsDeleted {
                bool operator()(const std::string &mountinfoLine) const {
                    return mountinfoLine.find("//deleted"_iobfs.c_str()) != std::string::npos;
                }
            };
            struct IsOverride {
                std::string _target;

                explicit IsOverride(std::string target) : _target(std::move(target)) {}

                bool operator()(const std::string &mountinfoLine) const {
                    std::vector<std::string> splitLine = absl::StrSplit(mountinfoLine, ' ');
                    std::string targetFromMountinfo = splitLine.at(4);
                    std::string unescaped;
                    absl::CUnescape(targetFromMountinfo, &unescaped);
                    return FileUtils::starts_with(unescaped, _target);
                }
            };
            for (int i = 0; i < mountinfo.size(); i++) {
                std::string checkLine = mountinfo.at(i);
                if (mounted.size() < length && IsDeleted()(checkLine)) {
                    mounted.emplace_back(-1);
                } else if (i == 0) {
                    // skip first element because it's already validated
                    mounted.emplace_back(i);
                } else {
                    if (mounted.size() < length) {
                        auto jtarget = (jstring) env->GetObjectArrayElement(jtargets,
                                                                            (jsize) mounted.size());
                        if (jtarget == nullptr) continue;
                        const char *target = env->GetStringUTFChars(jtarget, nullptr);
                        if (target == nullptr) { env->DeleteLocalRef(jtarget); continue; }
                        if (IsTarget(target)(checkLine)) {
                            mounted.emplace_back(i);
                            env->ReleaseStringUTFChars(jtarget, target);
                            env->DeleteLocalRef(jtarget);
                            continue;
                        }
                        env->ReleaseStringUTFChars(jtarget, target);
                        env->DeleteLocalRef(jtarget);
                    }
                    // latter mount override former mount
                    for (int j = 0; j < mounted.size(); j++) {
                        auto jtarget = (jstring) env->GetObjectArrayElement(jtargets, j);
                        if (jtarget == nullptr) continue;
                        const char *target = env->GetStringUTFChars(jtarget, nullptr);
                        if (target == nullptr) { env->DeleteLocalRef(jtarget); continue; }
                        if (IsOverride(target)(checkLine)) {
                            mounted.back() = -2;
                            env->ReleaseStringUTFChars(jtarget, target);
                            env->DeleteLocalRef(jtarget);
                            break;
                        }
                        env->ReleaseStringUTFChars(jtarget, target);
                        env->DeleteLocalRef(jtarget);
                    }
                }
            }
        }
        jintArray jmounted = env->NewIntArray((jsize) mounted.size());
        if (jmounted == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
        env->SetIntArrayRegion(jmounted, 0, (jsize) mounted.size(), &mounted.front());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(jmounted);
            return nullptr;
        }
        return jmounted;
    }
}  // namespace ProcInfo
