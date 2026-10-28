#include <cerrno>
#include <libgen.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "absl/strings/match.h"
#include "android-base/stringprintf.h"
#include "android_filesystem_config.h"
#include "fuse_policy.h"
#include "logging.h"
#include "obfs-string.h"
#include "FileUtils.h"

using android::base::StringPrintf;

namespace FileUtils {

    bool mkdirs(const char *path, mode_t mode) {
        struct stat stat{};
        if (lstat(path, &stat) == 0) {
            if (S_ISDIR(stat.st_mode)) {
                return true;
            }
            if (S_ISREG(stat.st_mode)) {
                return false;
            }
        }
        std::vector<std::string> stack = {path};
        for (;;) {
            std::string parent = dirname(stack.back().c_str());
            if (lstat(parent.c_str(), &stat) == 0) {
                if (S_ISREG(stat.st_mode)) {
                    return false;
                } else {
                    break;
                }
            }
            stack.emplace_back(parent);
        }
        std::reverse(stack.begin(), stack.end());
        for (auto &&item: stack) {
            if (!TEMP_FAILURE_RETRY(mkdir(item.c_str(), mode))) {
#ifdef DEBUG
                LOGI("mkdir: %s"_iobfs.c_str(), item.c_str());
#endif
            } else if (errno != EEXIST) {
                // EEXIST 是正常路径：父目录已被系统/其他进程建好（Android、Android/data
                // 恒存在），并发创建也会命中。把它当失败打 E 会让 release 包在每次挂载
                // 时刷出与故障无关的错误行，挤掉 ColorOS 按进程配额里的真信号。
                // 其余 errno（EACCES/EROFS/ENOTDIR…）仍必须留证。
                LOGE("Failed to mkdir(%s, %d): %s"_iobfs.c_str(), item.c_str(), mode,
                     strerror(errno));
            }
        }
        return true;
    }

    jint rm_dir(JNIEnv *env, jclass clazz, jstring jdir) {
        const char *dir = env->GetStringUTFChars(jdir, nullptr);
        const jint ret = rmdir(dir);
        env->ReleaseStringUTFChars(jdir, dir);
        return ret;
    }

    bool child_of(const std::string &parent, const std::string &child) {
        return absl::EndsWithIgnoreCase(parent, "/") && absl::StartsWithIgnoreCase(child, parent) ||
               !absl::EndsWithIgnoreCase(parent, "/") &&
               absl::StartsWithIgnoreCase(child, parent + '/');
    }

    bool starts_with(const std::string &parent, const std::string &child) {
        return absl::EqualsIgnoreCase(child, parent) || absl::EqualsIgnoreCase(parent, "/") ||
               absl::StartsWithIgnoreCase(child, parent + '/');
    }

    bool prepare_dir(const char *path, mode_t mode, uid_t uid, gid_t gid) {
        // 只对**本次新建**的目录设 owner，语义对齐 /system/vold/Utils.cpp PrepareAndroidDirs()。
        //
        // 为什么必须区分「已存在」：mkdirList 里既有目标应用自己的新建目录，也有
        // /storage/emulated/0、/storage/emulated/0/Android、.../Android/data 这些
        // 系统卷内既有目录。对后者 chmod/chown 在 FUSE 上会被 MediaProvider 拒绝
        // （setattr 只允许特定目标），失败后旧实现按 `result = result && prepare_dir(...)`
        // **短路**，导致同一批里排在后面的目录（多为规则的 source，例如
        // .../Android/data/<pkg>/cache）根本没被 mkdir —— 随后 bind mount 报 ENOENT，
        // 表现为「规则每次都失败、重试 2s/5s/15s 四轮全失败」。真机证据（6.0.0 /
        // PLK110）：com.coloros.gallery3d 的规则每次记录 stage=mount_rule、
        // phase=applying_rules、errno=2、index=0，source=.../gallery3d/cache。
        struct stat existing{};
        const bool existed = lstat(path, &existing) == 0 && S_ISDIR(existing.st_mode);
        if (!mkdirs(path, mode)) {
            return false;
        }
        if (existed) {
            return true;
        }
        if (TEMP_FAILURE_RETRY(chmod(path, mode)) == -1) {
            // 错误日志不受 #ifdef DEBUG 门控：release 包同样要留下证据。
            LOGE("Failed to chmod(%s, %d): %s"_iobfs.c_str(), path, mode, strerror(errno));
            return false;
        }
        if (TEMP_FAILURE_RETRY(chown(path, uid, gid)) == -1) {
            LOGE("Failed to chown(%s, %d, %d): %s"_iobfs.c_str(), path, uid, gid, strerror(errno));
            return false;
        }
        return true;
    }

    /// @see /system/vold/Utils.cpp PrepareAndroidDirs()
    jboolean auto_prepare_dirs(JNIEnv *env, jclass clazz, jobjectArray jdirs, jint juid) {
        const bool useSdcardFs = !storage_platform::is_fuse_available();
        const uid_t user_id = juid / AID_USER_OFFSET;
        const std::string androidDataDir = StringPrintf(
                "/storage/emulated/%d/Android/data"_iobfs.c_str(), user_id);
        const std::string androidObbDir = StringPrintf(
                "/storage/emulated/%d/Android/obb"_iobfs.c_str(), user_id);
        const std::string userSource = StringPrintf("/mnt/user/%d"_iobfs.c_str(), user_id);
        const std::string storage = "/storage"_iobfs.c_str();
        // mode 0771 + sticky bit for inheriting GIDs
        const mode_t mode = S_IRWXU | S_IRWXG | S_IXOTH | S_ISGID;
        const gid_t dataGid = useSdcardFs ? AID_MEDIA_RW : AID_EXT_DATA_RW;
        const gid_t obbGid = useSdcardFs ? AID_MEDIA_RW : AID_EXT_OBB_RW;
        jboolean result = JNI_TRUE;
        for (int i = 0, length = env->GetArrayLength(jdirs); i < length; i++) {
            auto jdir = (jstring) env->GetObjectArrayElement(jdirs, i);
            if (jdir == nullptr) continue;
            const char *dir = env->GetStringUTFChars(jdir, nullptr);
            if (dir == nullptr) { env->DeleteLocalRef(jdir); continue; }
            uid_t uid = AID_MEDIA_RW;
            gid_t gid = AID_MEDIA_RW;
            if (FileUtils::child_of(androidDataDir, dir)) {
                uid = juid;
                gid = dataGid;
            } else if (FileUtils::child_of(androidObbDir, dir)) {
                uid = juid;
                gid = obbGid;
            }
            // 逐目录独立求值，**不得**用 `result = result && prepare_dir(...)` 短路：
            // 一个目录失败（FUSE 拒绝 setattr、或该目录恰好被并发删除）会跳过其余
            // 全部目录的 mkdir。返回值仍表示「全部成功」，但每个目录都要被真正尝试。
            const bool prepared = prepare_dir(
                    useSdcardFs || user_id == 0 ? dir :
                    (userSource + std::string(dir).substr(storage.length(), strlen(dir))).c_str(),
                    mode, uid, gid
            );
            if (!prepared) {
                result = JNI_FALSE;
            }
            env->ReleaseStringUTFChars(jdir, dir);
            env->DeleteLocalRef(jdir);
        }
        return result;
    }

    void chownRecursively(const char *path, uid_t uid, gid_t gid) {
        DIR *pDir = opendir(path);
        if (pDir != nullptr) {
            struct dirent *file;
            while ((file = readdir(pDir)) != nullptr) {
                if (!strcmp(file->d_name, "."_iobfs.c_str()) ||
                    !strcmp(file->d_name, ".."_iobfs.c_str())) {
                    continue;
                }
                char child[PATH_MAX];
                snprintf(child, sizeof(child), "%s/%s"_iobfs.c_str(), path, file->d_name);

                chownRecursively(child, uid, gid);
            }
            closedir(pDir);
        }
        TEMP_FAILURE_RETRY(chown(path, uid, gid));
    }

    // Mirrors vold's app-dir owner repair for redirected Android/data and Android/obb paths.
    void switch_owner(JNIEnv *env, jclass clazz, jstring jdir, jint juid, jboolean isPrivate) {
        const char *dir = env->GetStringUTFChars(jdir, nullptr);
        const uid_t uid = isPrivate ? juid : AID_MEDIA_RW;
        const gid_t gid = isPrivate ? AID_EXT_DATA_RW : AID_EVERYBODY;
        chownRecursively(dir, uid, gid);
        env->ReleaseStringUTFChars(jdir, dir);
    }
}  // namespace FileUtils
