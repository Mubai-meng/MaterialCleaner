#include <dlfcn.h>
#include <elf.h>
#include <atomic>
#include <cstdint>
#include <set>
#include <libgen.h>
#include <link.h>
#include <sstream>
#include <shared_mutex>
#include <regex>
#include <sys/mman.h>
#include <unistd.h>

#include "bpf_hook.h"
#include "fuse_policy.h"
#include "xhook/xhook.h"
#include "fuse_i.h"
#include "fuse_lowlevel.h"
#include "logging.h"
#include "obfuscate.h"

#if defined(__aarch64__)
#define MC_ELF_R_SYM ELF64_R_SYM
#define MC_ELF_R_TYPE ELF64_R_TYPE
#define MC_ELF_R_JUMP_SLOT R_AARCH64_JUMP_SLOT
#elif defined(__arm__)
#define MC_ELF_R_SYM ELF32_R_SYM
#define MC_ELF_R_TYPE ELF32_R_TYPE
#define MC_ELF_R_JUMP_SLOT R_ARM_JUMP_SLOT
#elif defined(__x86_64__)
#define MC_ELF_R_SYM ELF64_R_SYM
#define MC_ELF_R_TYPE ELF64_R_TYPE
#define MC_ELF_R_JUMP_SLOT R_X86_64_JUMP_SLOT
#elif defined(__i386__)
#define MC_ELF_R_SYM ELF32_R_SYM
#define MC_ELF_R_TYPE ELF32_R_TYPE
#define MC_ELF_R_JUMP_SLOT R_386_JMP_SLOT
#else
#error Unsupported Android ABI
#endif

// Regex copied from FileUtils.java in MediaProvider, but without media directory.
const std::regex PATTERN_OWNED_PATH(
        "^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|obb)/([^/]+)(/?.*)?",
        std::regex_constants::icase);

static constexpr char PRIMARY_VOLUME_PREFIX[] = "/storage/emulated";

static bool isPackageOwnedPath(const std::string &path) {
    return std::regex_match(path, PATTERN_OWNED_PATH);
}

namespace bpf_hook {
    static constexpr char FUSE_HOOK_PATH_REGEX[] = ".*(libfuse_jni\\.so|MediaProvider\\.apk).*";
    static constexpr char FUSE_JNI_SONAME[] = "libfuse_jni.so";

    // Hook 线程与 JNI 写线程跨线程访问：relaxed 原子保证无撕裂且及时可见，
    // 单变量间无顺序依赖，无需更强的同步序。
    static std::atomic_bool isFuseBpfEnabled{false};
    static std::set<std::string> mountPoint = {};
    static std::shared_mutex mountPointMutex;
    static std::atomic_bool recordExternalAppSpecificStorage{false};

    // 决策 D1 开关：`fuse_bpf_fill_entries` 非移除语义的拦截范围。
    //   false（默认）：按 bpf_fd >= 0 的“安装”语义拦截；
    //   true         ：同样只拦非移除语义（移除语义恒放行，见 new_fuse_bpf_fill_entries）。
    // 移除语义（bpf_fd < 0，即 BpfFd::REMOVE）只做移除、永不安装短路，放行它可保留平台
    // “不把 bpf prog 残留在 Android/data/<pkg> inode 上”的不变量，故最高优先级绕过本开关。
    // 有意与 PR #12 原始 D1 分歧：PR 原语义 blockAll=true 时连 REMOVE 一起拦，
    // 会阻断系统生命周期清理造成残留；此处宣布保持稳定性契约，REMOVE 永远交由系统原实现，
    // 开关仅控制安装类 entry 的拦截范围。若未来看到 blockAll=true 仍放行 REMOVE，属设计而非遗漏。
    // 由 Java 侧策略快照驱动，经 commitPolicy 单次 JNI 下发。
    static std::atomic_bool fuseBpfBlockAll{false};

    bool (*old_StartsWith)(std::string_view s, std::string_view prefix);

    bool new_StartsWith(std::string_view s, std::string_view prefix) {
        if (!isFuseBpfEnabled.load(std::memory_order_relaxed) &&
            recordExternalAppSpecificStorage.load(std::memory_order_relaxed) &&
            prefix == PRIMARY_VOLUME_PREFIX) {
            auto path = std::string(s);
            if (isPackageOwnedPath(path)) {
                return false;
            }
        }
        return old_StartsWith(s, prefix);
    }

    bool isMountPoint(const std::string &path) {
        if (path.starts_with(PRIMARY_VOLUME_PREFIX)) {
            std::shared_lock<std::shared_mutex> lock(mountPointMutex);
            return mountPoint.find(path) != mountPoint.end();
        }
        return false;
    }

    // hook stubs
    bool (*old_containsMount_31)(const std::string &path);

    bool new_containsMount_31(const std::string &path) {
        if (isMountPoint(path)) {
            return true;
        }
        return old_containsMount_31(path);
    }

    bool (*old_containsMount_30)(const std::string &path, const std::string &userid);

    bool new_containsMount_30(const std::string &path, const std::string &userid) {
        if (isMountPoint(path)) {
            return true;
        }
        return old_containsMount_30(path, userid);
    }

    bool (*old_IsFuseBpfEnabled)();

    bool new_IsFuseBpfEnabled() {
        const bool enabled = old_IsFuseBpfEnabled();
        isFuseBpfEnabled.store(enabled, std::memory_order_relaxed);
        return enabled;
    }

    thread_local fuse_req_t fuse_req;

    void *(*old_fuse_req_userdata)(fuse_req_t req);

    void *new_fuse_req_userdata(fuse_req_t req) {
        fuse_req = req;
        return old_fuse_req_userdata(req);
    }

    void (*old_fuse_bpf_install)(struct fuse *fuse, struct fuse_entry_param *e,
                                 const std::string &child_path, int &backing_fd);

    void new_fuse_bpf_install(struct fuse *fuse, struct fuse_entry_param *e,
                              const std::string &child_path, int &backing_fd) {
        if (recordExternalAppSpecificStorage.load(std::memory_order_relaxed) ||
            (fuse_req != nullptr && fuse_req->ctx.uid == 0)) {
            return;
        }
        return old_fuse_bpf_install(fuse, e, child_path, backing_fd);
    }

    // ---------------------------------------------------------------------
    // Android 16/17 兼容：BPF 短路安装的真实拦截点已下移一层
    // ---------------------------------------------------------------------
    //
    // AOSP 中 fuse_bpf_install() 只是 do_lookup 的中间层：
    //
    //   void fuse_bpf_install(struct fuse* fuse, struct fuse_entry_param* e,
    //                         const std::string& child_path, int& backing_fd) {
    //     if (android::base::StartsWith(child_path, PRIMARY_VOLUME_PREFIX)) {
    //       if (is_bpf_backing_path(child_path)) {                  // .../Android/(data|obb)
    //         fuse_bpf_fill_entries(child_path, fuse->bpf_fd.get(), e, backing_fd);
    //       } else if (is_package_owned_path(child_path, fuse->path)) {  // .../Android/(data|obb)/<pkg>/...
    //         fuse_bpf_fill_entries(child_path, static_cast<int>(BpfFd::REMOVE), e, backing_fd);
    //       }
    //     }
    //   }
    //
    // 一加 15 / Android 17 实机 ELF 验证结论：
    //   * fuse_bpf_install 已被内联进 do_lookup，独立导出符号零引用 = 死代码；
    //   * do_lookup 只保留一处对 fuse_bpf_fill_entries 的调用（PLT/GOT 可拦截）；
    //   * 上面两个分支被编译器合并为同一次调用，改用 bpf_fd 取值区分语义：
    //         bpf_fd >= 0              → 安装 backing（内核短路，必须拦截才能让请求回到 FUSE daemon）
    //         bpf_fd < 0（REMOVE）     → 移除 inode 继承的 bpf prog（必须放行）
    //
    // 因此这里以 fuse_bpf_fill_entries 为主拦截点，移除语义恒放行（最高优先级绕过 blockAll），
    // 非移除语义按策略拦截；是否扩大拦截范围由开关 fuseBpfBlockAll 决定（决策 D1）。
    void (*old_fuse_bpf_fill_entries)(const std::string &path, int bpf_fd,
                                      struct fuse_entry_param *e, int &backing_fd);

    void new_fuse_bpf_fill_entries(const std::string &path, int bpf_fd,
                                   struct fuse_entry_param *e, int &backing_fd) {
        if (old_fuse_bpf_fill_entries == nullptr) {
            // 未捕获到原函数：宁可不装 BPF，也绝不能空指针跳转（会直接崩掉 MediaProvider）。
            return;
        }
        // 移除语义（bpf_fd < 0，即 BpfFd::REMOVE）直接透传，最高优先级绕过 blockAll：
        // 保留平台“不把 bpf prog 残留在 Android/data/<pkg> inode 上”的不变量。
        if (bpf_fd < 0) {
            return old_fuse_bpf_fill_entries(path, bpf_fd, e, backing_fd);
        }
        // 非移除语义：blockAll 或 bpf_fd >= 0 时按策略拦截（此处 bpf_fd >= 0 恒真，
        // 保留 blockAll 显式表达策略语义，兼容旧平台与未来负值扩展）。
        const bool interceptThisCall =
                fuseBpfBlockAll.load(std::memory_order_relaxed) || bpf_fd >= 0;
        if (interceptThisCall &&
            (recordExternalAppSpecificStorage.load(std::memory_order_relaxed) ||
             (fuse_req != nullptr && fuse_req->ctx.uid == 0))) {
            return;
        }
        return old_fuse_bpf_fill_entries(path, bpf_fd, e, backing_fd);
    }

    static void AppendJsonBool(std::ostringstream &out, const char *name, bool value) {
        out << '"' << name << "\":" << (value ? "true" : "false");
    }

    static void AppendJsonString(std::ostringstream &out, const char *name, const char *value) {
        out << '"' << name << "\":\"" << value << '"';
    }

    static bool RegisterHook(const char *symbol, void *newFunc, void **oldFunc) {
        return xhook_register(FUSE_HOOK_PATH_REGEX, symbol, newFunc, oldFunc) == 0;
    }

    // Match method for diagnostic output
    enum MatchMethod {
        MATCH_NONE  = 0,
        MATCH_EXACT = 1,
        MATCH_FUZZY = 2,
    };

    static const char *MatchMethodName(MatchMethod method) {
        return method == MATCH_EXACT ? "exact" : (method == MATCH_FUZZY ? "fuzzy" : "none");
    }

    struct EmbeddedFuseHookResult {
        bool foundFuseJni = false;
        bool startsWithHooked = false;
        bool containsMountHooked = false;
        bool isFuseBpfEnabledHooked = false;
        bool fuseReqUserdataHooked = false;
        // BPF 诊断拆三字段：fill_entries 主拦截点、install 旧平台兼容点、effective 有效位。
        // effective = fillEntriesHooked || installHooked，旧单字段不再复用。
        bool fillEntriesHooked = false;
        bool installHooked = false;
        // Diagnostic: which match method was used for each symbol
        MatchMethod startsWithMethod = MATCH_NONE;
        MatchMethod containsMountMethod = MATCH_NONE;
        MatchMethod isFuseBpfEnabledMethod = MATCH_NONE;
        MatchMethod fuseReqUserdataMethod = MATCH_NONE;
        MatchMethod fillEntriesMethod = MATCH_NONE;
        MatchMethod installMethod = MATCH_NONE;

        // 有效位：任一 BPF 拦截点命中即视为 BPF 链路有效。
        bool effective() const { return fillEntriesHooked || installHooked; }
    };

    struct EmbeddedHookTarget {
        const char *symbol;        // exact mangled name (for strcmp)
        const char *shortName;     // unqualified function name for fuzzy match (NULL = exact-only)
        const char *namespaceName; // expected C++ namespace for fuzzy match (NULL = global/C symbol)
        int paramCount;            // expected param count (-1 = don't check)
        void *replacement;
        void **original;
        bool *hooked;
        MatchMethod *method;       // [out] which method matched (diagnostics)
    };

    static std::string BuildHookStatusJson(bool fuseAvailable,
                                           bool fuseLibraryLoaded,
                                           const char *fuseLibraryName,
                                           const char *hookMode,
                                           const char *fuseJniLoadMode,
                                           bool embeddedFuseJniFound,
                                           bool startsWithHooked,
                                           bool containsMountHooked,
                                           bool isFuseBpfEnabledHooked,
                                           bool fuseReqUserdataHooked,
                                           bool fillEntriesHooked,
                                           bool installHooked,
                                           bool effective,
                                           const char *startsWithMethod,
                                           const char *containsMountMethod,
                                           const char *isFuseBpfEnabledMethod,
                                           const char *fuseReqUserdataMethod,
                                           const char *fillEntriesMethod,
                                           const char *installMethod,
                                           bool xhookRefreshCalled,
                                           const char *lastError) {
        std::ostringstream out;
        out << "{";
        AppendJsonBool(out, "fuseAvailable", fuseAvailable);
        out << ",";
        AppendJsonBool(out, "fuseLibraryLoaded", fuseLibraryLoaded);
        out << ",";
        AppendJsonString(out, "fuseLibraryName", fuseLibraryName);
        out << ",";
        AppendJsonString(out, "hookMode", hookMode);
        out << ",";
        AppendJsonString(out, "fuseJniLoadMode", fuseJniLoadMode);
        out << ",";
        AppendJsonBool(out, "embeddedFuseJniFound", embeddedFuseJniFound);
        out << ",";
        AppendJsonBool(out, "xhookRefreshCalled", xhookRefreshCalled);
        out << ",\"symbols\":{";
        AppendJsonBool(out, "containsMount", containsMountHooked);
        out << ",";
        AppendJsonBool(out, "startsWith", startsWithHooked);
        out << ",";
        AppendJsonBool(out, "isFuseBpfEnabled", isFuseBpfEnabledHooked);
        out << ",";
        AppendJsonBool(out, "fuseReqUserdata", fuseReqUserdataHooked);
        out << ",";
        // BPF 三字段分开上报：fillEntries 主拦截点、install 兼容点、effective 有效位。
        AppendJsonBool(out, "fillEntries", fillEntriesHooked);
        out << ",";
        AppendJsonBool(out, "install", installHooked);
        out << ",";
        AppendJsonBool(out, "effective", effective);
        out << "},\"symbolMethods\":{";
        AppendJsonString(out, "containsMount", containsMountMethod);
        out << ",";
        AppendJsonString(out, "startsWith", startsWithMethod);
        out << ",";
        AppendJsonString(out, "isFuseBpfEnabled", isFuseBpfEnabledMethod);
        out << ",";
        AppendJsonString(out, "fuseReqUserdata", fuseReqUserdataMethod);
        out << ",";
        AppendJsonString(out, "fillEntries", fillEntriesMethod);
        out << ",";
        AppendJsonString(out, "install", installMethod);
        out << "},";
        AppendJsonString(out, "lastError", lastError);
        out << "}";
        return out.str();
    }

    static bool PatchGotSlot(ElfW(Addr) slotAddress, void *replacement, void **original) {
        auto slot = reinterpret_cast<void **>(slotAddress);
        if (slot == nullptr || replacement == nullptr || original == nullptr) {
            return false;
        }
        if (*slot == replacement) {
            return true;
        }
        *original = *slot;
        const long pageSize = sysconf(_SC_PAGESIZE);
        if (pageSize <= 0) {
            return false;
        }
        auto page = reinterpret_cast<void *>(
                reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(pageSize) - 1));
        if (mprotect(page, static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE) != 0) {
            return false;
        }
        *slot = replacement;
        __builtin___clear_cache(reinterpret_cast<char *>(slot),
                                reinterpret_cast<char *>(slot) + sizeof(void *));
        mprotect(page, static_cast<size_t>(pageSize), PROT_READ);
        return true;
    }

    static bool IsRuntimeAddress(ElfW(Addr) value, ElfW(Addr) base) {
        return value >= base;
    }

    struct MangledNameComponent {
        const char *name = nullptr;
        size_t length = 0;
    };

    static bool IsDigit(char value) {
        return value >= '0' && value <= '9';
    }

    static bool ReadLengthPrefixedName(const char **cursor,
                                       MangledNameComponent *component = nullptr) {
        if (cursor == nullptr || *cursor == nullptr || !IsDigit(**cursor)) {
            return false;
        }
        const char *p = *cursor;
        size_t length = 0;
        while (IsDigit(*p)) {
            length = length * 10 + static_cast<size_t>(*p - '0');
            p++;
        }
        if (length == 0 || strlen(p) < length) {
            return false;
        }
        if (component != nullptr) {
            component->name = p;
            component->length = length;
        }
        *cursor = p + length;
        return true;
    }

    static bool ParseMangledNameComponents(const char *mangled,
                                           MangledNameComponent *components,
                                           size_t maxComponents,
                                           size_t *componentCount,
                                           const char **nameEnd) {
        if (mangled == nullptr || components == nullptr || componentCount == nullptr
                || maxComponents == 0) {
            return false;
        }
        *componentCount = 0;
        if (nameEnd != nullptr) {
            *nameEnd = nullptr;
        }

        if (mangled[0] != '_' || mangled[1] != 'Z') {
            components[0].name = mangled;
            components[0].length = strlen(mangled);
            *componentCount = 1;
            if (nameEnd != nullptr) {
                *nameEnd = mangled + components[0].length;
            }
            return components[0].length > 0;
        }

        const char *p = mangled + 2;
        const bool nested = *p == 'N';
        if (nested) {
            p++;
        }

        while (IsDigit(*p)) {
            if (*componentCount >= maxComponents) {
                return false;
            }
            if (!ReadLengthPrefixedName(&p, &components[*componentCount])) {
                return false;
            }
            (*componentCount)++;
        }

        if (*componentCount == 0) {
            return false;
        }
        if (nested) {
            if (*p != 'E') {
                return false;
            }
            p++;
        }
        if (nameEnd != nullptr) {
            *nameEnd = p;
        }
        return true;
    }

    static bool ComponentEquals(const MangledNameComponent &component, const char *expected) {
        return expected != nullptr
               && strlen(expected) == component.length
               && strncmp(component.name, expected, component.length) == 0;
    }

    static bool NamespaceMatches(const MangledNameComponent *components, size_t componentCount,
                                 const char *expectedNamespace) {
        if (expectedNamespace == nullptr || *expectedNamespace == '\0') {
            return componentCount == 1;
        }
        if (componentCount < 2) {
            return false;
        }

        size_t componentIndex = 0;
        const char *segment = expectedNamespace;
        while (*segment != '\0') {
            const char *segmentEnd = strstr(segment, "::");
            const size_t segmentLength = segmentEnd == nullptr
                                         ? strlen(segment)
                                         : static_cast<size_t>(segmentEnd - segment);
            if (componentIndex >= componentCount - 1
                    || components[componentIndex].length != segmentLength
                    || strncmp(components[componentIndex].name, segment, segmentLength) != 0) {
                return false;
            }
            componentIndex++;
            if (segmentEnd == nullptr) {
                break;
            }
            segment = segmentEnd + 2;
        }
        return componentIndex == componentCount - 1;
    }

    /**
     * Extract the unqualified function name from an Itanium C++ ABI mangled symbol.
     *
     * For `_ZN7android4base10StartsWithE...` → "StartsWith"
     * For `_ZN13mediaprovider4fuse13containsMountE...` → "containsMount"
     * For C symbols (like `fuse_req_userdata`) → returned as-is.
     *
     * Async-signal-safe, no heap allocation, no external dependencies.
     */
    static bool ExtractFunctionName(const char *mangled, char *out, size_t out_size) {
        if (!mangled || !out || out_size == 0) return false;

        MangledNameComponent components[8];
        size_t componentCount = 0;
        if (!ParseMangledNameComponents(mangled, components,
                                        sizeof(components) / sizeof(components[0]),
                                        &componentCount, nullptr)) {
            return false;
        }

        const auto &lastName = components[componentCount - 1];
        const size_t copyLen = lastName.length < out_size - 1 ? lastName.length : out_size - 1;
        memcpy(out, lastName.name, copyLen);
        out[copyLen] = '\0';
        return true;
    }

    static const char *SkipType(const char *sig);

    static const char *SkipSubstitution(const char *sig) {
        if (sig == nullptr || *sig != 'S') {
            return nullptr;
        }
        sig++;
        if (*sig == 't') {
            return sig + 1; // St = std::
        }
        while (IsDigit(*sig)) {
            sig++;
        }
        return *sig == '_' ? sig + 1 : nullptr;
    }

    static const char *SkipTemplateArgs(const char *sig) {
        if (sig == nullptr || *sig != 'I') {
            return nullptr;
        }
        sig++;
        while (*sig != '\0' && *sig != 'E') {
            sig = SkipType(sig);
            if (sig == nullptr) {
                return nullptr;
            }
        }
        return *sig == 'E' ? sig + 1 : nullptr;
    }

    static const char *SkipNestedName(const char *sig) {
        if (sig == nullptr || *sig != 'N') {
            return nullptr;
        }
        sig++;
        while (*sig != '\0' && *sig != 'E') {
            if (*sig == 'S') {
                sig = SkipSubstitution(sig);
            } else if (IsDigit(*sig)) {
                if (!ReadLengthPrefixedName(&sig)) {
                    return nullptr;
                }
                if (*sig == 'I') {
                    sig = SkipTemplateArgs(sig);
                }
            } else {
                return nullptr;
            }
            if (sig == nullptr) {
                return nullptr;
            }
        }
        return *sig == 'E' ? sig + 1 : nullptr;
    }

    static const char *SkipType(const char *sig) {
        if (sig == nullptr || *sig == '\0') {
            return nullptr;
        }
        while (*sig == 'R' || *sig == 'O' || *sig == 'P' || *sig == 'K'
               || *sig == 'V' || *sig == 'r') {
            sig++;
        }
        if (*sig == 'N') {
            return SkipNestedName(sig);
        }
        if (*sig == 'S') {
            return SkipSubstitution(sig);
        }
        if (IsDigit(*sig)) {
            if (!ReadLengthPrefixedName(&sig)) {
                return nullptr;
            }
            return *sig == 'I' ? SkipTemplateArgs(sig) : sig;
        }
        if (*sig == 'F') {
            sig++;
            while (*sig != '\0' && *sig != 'E') {
                sig = SkipType(sig);
                if (sig == nullptr) {
                    return nullptr;
                }
            }
            return *sig == 'E' ? sig + 1 : nullptr;
        }
        return sig + 1; // builtin or vendor extended one-letter type code
    }

    /**
     * Count top-level parameter types in an Itanium ABI signature suffix.
     *
     * Walks the signature string (the part after the function name's closing 'E')
     * tracking depth through nested names ('N'), template args ('I'), and
     * substitution back-references ('S{digits}_').  Each top-level type encoding
     * at depth 0 increments the count.
     *
     * Used for overload disambiguation — currently only `containsMount`
     * has two variants (1-arg for API 31+, 2-arg for API 30).
     */
    static int CountTopLevelParams(const char *sig) {
        if (!sig || !*sig) return 0;
        if (sig[0] == 'v' && sig[1] == '\0') return 0;
        int count = 0;
        while (*sig != '\0') {
            const char *next = SkipType(sig);
            if (next == nullptr || next <= sig) {
                return -1;
            }
            count++;
            sig = next;
        }
        return count;
    }

    /**
     * Given an Itanium ABI mangled function name, return a pointer to the
     * signature suffix (the part after the function name's closing 'E').
     *
     * For `_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk1...` → pointer to `RKNSt6__ndk1...`
     * For C symbols (non-mangled) → nullptr.
     */
    static const char* FindSignatureSuffix(const char *mangled) {
        if (!mangled || mangled[0] != '_' || mangled[1] != 'Z') return nullptr;
        MangledNameComponent components[8];
        size_t componentCount = 0;
        const char *nameEnd = nullptr;
        if (!ParseMangledNameComponents(mangled, components,
                                        sizeof(components) / sizeof(components[0]),
                                        &componentCount, &nameEnd)) {
            return nullptr;
        }
        return (nameEnd != nullptr && *nameEnd != '\0') ? nameEnd : nullptr;
    }

    static bool FuzzyMatchesTarget(const char *symbolName, const EmbeddedHookTarget &target) {
        MangledNameComponent components[8];
        size_t componentCount = 0;
        if (!ParseMangledNameComponents(symbolName, components,
                                        sizeof(components) / sizeof(components[0]),
                                        &componentCount, nullptr)) {
            return false;
        }
        if (!ComponentEquals(components[componentCount - 1], target.shortName)) {
            return false;
        }
        if (!NamespaceMatches(components, componentCount, target.namespaceName)) {
            return false;
        }
        if (target.paramCount < 0) {
            return true;
        }
        const char *sig = FindSignatureSuffix(symbolName);
        return sig != nullptr && CountTopLevelParams(sig) == target.paramCount;
    }

    static ElfW(Addr) ResolveDynamicAddress(ElfW(Addr) value, ElfW(Addr) base) {
        return IsRuntimeAddress(value, base) ? value : base + value;
    }

    static int PatchEmbeddedFuseJniCallback(struct dl_phdr_info *info, size_t, void *data) {
        auto result = static_cast<EmbeddedFuseHookResult *>(data);
        if (info == nullptr || result == nullptr) {
            return 0;
        }

        ElfW(Dyn) *dynamic = nullptr;
        for (int i = 0; i < info->dlpi_phnum; i++) {
            const auto &phdr = info->dlpi_phdr[i];
            if (phdr.p_type == PT_DYNAMIC) {
                dynamic = reinterpret_cast<ElfW(Dyn) *>(info->dlpi_addr + phdr.p_vaddr);
                break;
            }
        }
        if (dynamic == nullptr) {
            return 0;
        }

        const char *strtab = nullptr;
        ElfW(Sym) *symtab = nullptr;
        void *jmprel = nullptr;
        size_t pltrelsz = 0;
        ElfW(Addr) pltRelType = DT_NULL;
        const char *soname = nullptr;
        for (auto dyn = dynamic; dyn->d_tag != DT_NULL; dyn++) {
            switch (dyn->d_tag) {
                case DT_STRTAB:
                    strtab = reinterpret_cast<const char *>(
                            ResolveDynamicAddress(dyn->d_un.d_ptr, info->dlpi_addr));
                    break;
                case DT_SYMTAB:
                    symtab = reinterpret_cast<ElfW(Sym) *>(
                            ResolveDynamicAddress(dyn->d_un.d_ptr, info->dlpi_addr));
                    break;
                case DT_JMPREL:
                    jmprel = reinterpret_cast<void *>(
                            ResolveDynamicAddress(dyn->d_un.d_ptr, info->dlpi_addr));
                    break;
                case DT_PLTRELSZ:
                    pltrelsz = dyn->d_un.d_val;
                    break;
                case DT_PLTREL:
                    pltRelType = dyn->d_un.d_val;
                    break;
                case DT_SONAME:
                    if (strtab != nullptr) {
                        soname = strtab + dyn->d_un.d_val;
                    }
                    break;
                default:
                    break;
            }
        }
        if (strtab == nullptr || symtab == nullptr || jmprel == nullptr || pltrelsz == 0 ||
            (pltRelType != DT_REL && pltRelType != DT_RELA)) {
            return 0;
        }
        if (soname == nullptr) {
            for (auto dyn = dynamic; dyn->d_tag != DT_NULL; dyn++) {
                if (dyn->d_tag == DT_SONAME) {
                    soname = strtab + dyn->d_un.d_val;
                    break;
                }
            }
        }
        if (soname == nullptr || strcmp(soname, FUSE_JNI_SONAME) != 0) {
            return 0;
        }

        result->foundFuseJni = true;
        // 注意：libc++ 内联命名空间在不同 Android 版本间不同——Android 17 为 std::__1（NSt3__1），
        // 旧平台为 std::__ndk1（NSt6__ndk1）。此处统一按 NSt3__1 精确匹配，
        // 旧平台由 FuzzyMatchesTarget（短名 + 命名空间 + 参数个数）兜底。
        const char *startsWithSymbol = AY_OBFUSCATE(
                "_ZN7android4base10StartsWithENSt3__117basic_string_viewIcNS1_11char_traitsIcEEEES5_");
        const char *containsMount31Symbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse13containsMountERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE");
        // API30 的 2 参重载：本机（Android 17）已不存在，保留仅为兼容旧平台（模糊匹配 paramCount=2）。
        const char *containsMount30Symbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse13containsMountERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_");
        const char *isFuseBpfEnabledSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16IsFuseBpfEnabledEv");
        const char *fuseReqUserdataSymbol = AY_OBFUSCATE("fuse_req_userdata");
        // Android 16/17：真实生效的 BPF 安装点（有 PLT 槽，可 GOT/xhook 拦截）。
        const char *fuseBpfFillEntriesSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse21fuse_bpf_fill_entriesERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEiP16fuse_entry_paramRi");
        // 旧平台（fuse_bpf_install 尚未内联）仍走此符号；本机零引用，仅作兼容保留。
        const char *fuseBpfInstallSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16fuse_bpf_installEP4fuseP16fuse_entry_paramRKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERi");

        // Targets:
        //   symbol          shortName           namespace             paramCount  replacement              original                   hooked flag              method out
        EmbeddedHookTarget targets[] = {
                {startsWithSymbol,       AY_OBFUSCATE("StartsWith"),
                        AY_OBFUSCATE("android::base"), -1,
                        reinterpret_cast<void *>(new_StartsWith),
                        reinterpret_cast<void **>(&old_StartsWith), &result->startsWithHooked,
                        &result->startsWithMethod},
                {containsMount31Symbol,  AY_OBFUSCATE("containsMount"),
                        AY_OBFUSCATE("mediaprovider::fuse"), 1,
                        reinterpret_cast<void *>(new_containsMount_31),
                        reinterpret_cast<void **>(&old_containsMount_31), &result->containsMountHooked,
                        &result->containsMountMethod},
                {containsMount30Symbol,  AY_OBFUSCATE("containsMount"),
                        AY_OBFUSCATE("mediaprovider::fuse"), 2,
                        reinterpret_cast<void *>(new_containsMount_30),
                        reinterpret_cast<void **>(&old_containsMount_30), &result->containsMountHooked,
                        &result->containsMountMethod},
                {isFuseBpfEnabledSymbol, AY_OBFUSCATE("IsFuseBpfEnabled"),
                        AY_OBFUSCATE("mediaprovider::fuse"), -1,
                        reinterpret_cast<void *>(new_IsFuseBpfEnabled),
                        reinterpret_cast<void **>(&old_IsFuseBpfEnabled), &result->isFuseBpfEnabledHooked,
                        &result->isFuseBpfEnabledMethod},
                {fuseReqUserdataSymbol,  AY_OBFUSCATE("fuse_req_userdata"), nullptr, -1,
                        reinterpret_cast<void *>(new_fuse_req_userdata),
                        reinterpret_cast<void **>(&old_fuse_req_userdata), &result->fuseReqUserdataHooked,
                        &result->fuseReqUserdataMethod},
                // Android 16/17 主拦截点：放在 fuse_bpf_install 之前，命中后后者自动跳过。
                {fuseBpfFillEntriesSymbol, AY_OBFUSCATE("fuse_bpf_fill_entries"),
                        AY_OBFUSCATE("mediaprovider::fuse"), 4,
                        reinterpret_cast<void *>(new_fuse_bpf_fill_entries),
                        reinterpret_cast<void **>(&old_fuse_bpf_fill_entries), &result->fillEntriesHooked,
                        &result->fillEntriesMethod},
                {fuseBpfInstallSymbol,   AY_OBFUSCATE("fuse_bpf_install"),
                        AY_OBFUSCATE("mediaprovider::fuse"), -1,
                        reinterpret_cast<void *>(new_fuse_bpf_install),
                        reinterpret_cast<void **>(&old_fuse_bpf_install), &result->installHooked,
                        &result->installMethod},
        };

        const auto patchRelocations = [&](const auto *relocations, size_t count) {
            for (size_t i = 0; i < count; i++) {
                const auto &relocation = relocations[i];
                if (MC_ELF_R_TYPE(relocation.r_info) != MC_ELF_R_JUMP_SLOT) {
                    continue;
                }
                const auto symbolIndex = MC_ELF_R_SYM(relocation.r_info);
                const char *symbolName = strtab + symtab[symbolIndex].st_name;

                for (auto &target: targets) {
                    if (*target.hooked) continue;

                    // --- Phase 1: Exact match (strcmp) ---
                    bool matched = (strcmp(symbolName, target.symbol) == 0);
                    MatchMethod method = MATCH_EXACT;

                    // --- Phase 2: Fuzzy match via Itanium name extraction ---
                    if (!matched && target.shortName != nullptr &&
                        FuzzyMatchesTarget(symbolName, target)) {
                        matched = true;
                        method = MATCH_FUZZY;
                    }

                    // --- Apply GOT patch if matched ---
                    if (!matched) continue;

                    const auto slotAddress = info->dlpi_addr + relocation.r_offset;
                    *target.hooked = PatchGotSlot(
                            slotAddress, target.replacement, target.original);
                    *target.method = method;
                }
            }
        };

        if (pltRelType == DT_RELA) {
            if (pltrelsz % sizeof(ElfW(Rela)) != 0) {
                return 1;
            }
            patchRelocations(
                    static_cast<const ElfW(Rela) *>(jmprel),
                    pltrelsz / sizeof(ElfW(Rela)));
        } else {
            if (pltrelsz % sizeof(ElfW(Rel)) != 0) {
                return 1;
            }
            patchRelocations(
                    static_cast<const ElfW(Rel) *>(jmprel),
                    pltrelsz / sizeof(ElfW(Rel)));
        }
        return 1;
    }

    static EmbeddedFuseHookResult HookEmbeddedFuseJni() {
        EmbeddedFuseHookResult result;
        dl_iterate_phdr(PatchEmbeddedFuseJniCallback, &result);
        return result;
    }

    std::string Hook(void *handle, bool fuseLibraryMapped) {
        bool startsWithHooked = false;
        bool containsMountHooked = false;
        bool isFuseBpfEnabledHooked = false;
        bool fuseReqUserdataHooked = false;
        // BPF 诊断拆三字段：fillEntriesHooked 主拦截点、installHooked 兼容点、effective 有效位。
        // effective = fillEntriesHooked || installHooked，不复用旧单字段。
        bool fillEntriesHooked = false;
        bool installHooked = false;
        bool xhookRefreshCalled = false;
        std::string lastError;
        const bool startsWithRequired = storage_platform::device_api_level() >= 31;
        const char *startsWithMethod = "none";
        const char *containsMountMethod = "none";
        const char *isFuseBpfEnabledMethod = "none";
        const char *fuseReqUserdataMethod = "none";
        const char *fillEntriesMethod = "none";
        const char *installMethod = "none";

        if (!storage_platform::is_fuse_available()) {
            LOGE("%s", std::string(AY_OBFUSCATE("FUSE not available, skipping hook")).c_str()); // "FUSE not available, skipping hook"
            return BuildHookStatusJson(false, true, "libfuse_jni.so",
                                       "NONE", "UNKNOWN", false,
                                       false, false, false, false, false, false, false,
                                       "none", "none", "none", "none", "none", "none",
                                       false, "FUSE not available");
        }
        LOGI("%s", std::string(AY_OBFUSCATE("Initializing bpf_hook")).c_str()); // "Initializing bpf_hook"
        if (handle == nullptr) {
            lastError = "FUSE library mapped but symbol handle unavailable";
            auto embeddedResult = HookEmbeddedFuseJni();
            startsWithHooked = embeddedResult.startsWithHooked;
            containsMountHooked = embeddedResult.containsMountHooked;
            isFuseBpfEnabledHooked = embeddedResult.isFuseBpfEnabledHooked;
            fuseReqUserdataHooked = embeddedResult.fuseReqUserdataHooked;
            fillEntriesHooked = embeddedResult.fillEntriesHooked;
            installHooked = embeddedResult.installHooked;
            const bool effective = fillEntriesHooked || installHooked;
            if (embeddedResult.foundFuseJni) {
                lastError = containsMountHooked ? "" : "embedded libfuse_jni.so found but GOT hook failed";
            }
            return BuildHookStatusJson(true, fuseLibraryMapped,
                                       "MediaProvider.apk/libfuse_jni.so",
                                       "EMBEDDED_GOT_PATCH", "APEX_APK_EMBEDDED",
                                       embeddedResult.foundFuseJni,
                                       startsWithHooked, containsMountHooked,
                                       isFuseBpfEnabledHooked, fuseReqUserdataHooked,
                                       fillEntriesHooked, installHooked, effective,
                                       MatchMethodName(embeddedResult.startsWithMethod),
                                       MatchMethodName(embeddedResult.containsMountMethod),
                                       MatchMethodName(embeddedResult.isFuseBpfEnabledMethod),
                                       MatchMethodName(embeddedResult.fuseReqUserdataMethod),
                                       MatchMethodName(embeddedResult.fillEntriesMethod),
                                       MatchMethodName(embeddedResult.installMethod),
                                       false, lastError.c_str());
        }
        if (startsWithRequired) {
            // 先按 Android 17 的 libc++ 内联命名空间 std::__1（NSt3__1）匹配，失败再回退旧 NDK 的 std::__ndk1。
            const char *startsWithSymbol = AY_OBFUSCATE(
                    "_ZN7android4base10StartsWithENSt3__117basic_string_viewIcNS1_11char_traitsIcEEEES5_");
            auto startsWith = handle == nullptr ? nullptr : dlsym(handle, startsWithSymbol);
            auto startsWithRegistered = RegisterHook(startsWithSymbol, (void *) new_StartsWith,
                                                     (void **) &old_StartsWith);
            startsWithHooked = startsWith != nullptr && startsWithRegistered;
            if (!startsWithHooked) {
                const char *startsWithNdk1Symbol = AY_OBFUSCATE(
                        "_ZN7android4base10StartsWithENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEES5_");
                auto startsWithNdk1 = handle == nullptr ? nullptr : dlsym(handle, startsWithNdk1Symbol);
                auto startsWithNdk1Registered = RegisterHook(startsWithNdk1Symbol,
                                                             (void *) new_StartsWith,
                                                             (void **) &old_StartsWith);
                startsWithHooked = startsWithNdk1 != nullptr && startsWithNdk1Registered;
            }
            if (startsWithHooked) {
                startsWithMethod = "exact";
            }
            if (!startsWithHooked) {
                LOGE("%s", std::string(AY_OBFUSCATE("failed to find StartsWith")).c_str()); // "failed to find StartsWith"
                if (handle != nullptr) {
                    lastError = "failed to find StartsWith";
                }
            }
        }
        const char *containsMount31Symbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse13containsMountERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE");
        auto containsMount_31 = handle == nullptr ? nullptr : dlsym(handle, containsMount31Symbol);
        auto containsMount31Registered = RegisterHook(containsMount31Symbol, (void *) new_containsMount_31,
                                                      (void **) &old_containsMount_31);
        containsMountHooked = containsMount_31 != nullptr && containsMount31Registered;
        if (!containsMountHooked) {
            const char *containsMount31Ndk1Symbol = AY_OBFUSCATE(
                    "_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE");
            auto containsMount_31ndk1 =
                    handle == nullptr ? nullptr : dlsym(handle, containsMount31Ndk1Symbol);
            auto containsMount31Ndk1Registered = RegisterHook(containsMount31Ndk1Symbol,
                                                              (void *) new_containsMount_31,
                                                              (void **) &old_containsMount_31);
            containsMountHooked = containsMount_31ndk1 != nullptr && containsMount31Ndk1Registered;
        }
        if (!containsMountHooked) {
            const char *containsMount30Symbol = AY_OBFUSCATE(
                    "_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_");
            auto containsMount_30 = handle == nullptr ? nullptr : dlsym(handle, containsMount30Symbol);
            auto containsMount30Registered = RegisterHook(containsMount30Symbol,
                                                          (void *) new_containsMount_30,
                                                          (void **) &old_containsMount_30);
            containsMountHooked = containsMount_30 != nullptr && containsMount30Registered;
        }
        if (containsMountHooked) {
            containsMountMethod = "exact";
        }
        if (!containsMountHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find containsMount")).c_str()); // "failed to find containsMount"
            if (handle != nullptr) {
                lastError = "failed to find containsMount";
            }
        }
        const char *isFuseBpfEnabledSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16IsFuseBpfEnabledEv");
        auto IsFuseBpfEnabled = handle == nullptr ? nullptr : dlsym(handle, isFuseBpfEnabledSymbol);
        auto isFuseBpfEnabledRegistered = RegisterHook(isFuseBpfEnabledSymbol,
                                                       (void *) new_IsFuseBpfEnabled,
                                                       (void **) &old_IsFuseBpfEnabled);
        isFuseBpfEnabledHooked = IsFuseBpfEnabled != nullptr && isFuseBpfEnabledRegistered;
        if (isFuseBpfEnabledHooked) {
            isFuseBpfEnabledMethod = "exact";
        }
        if (!isFuseBpfEnabledHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find IsFuseBpfEnabled")).c_str()); // "failed to find IsFuseBpfEnabled"
            if (handle != nullptr) {
                lastError = "failed to find IsFuseBpfEnabled";
            }
        }

        const char *fuseReqUserdataSymbol = AY_OBFUSCATE("fuse_req_userdata");
        auto fuse_req_userdata = handle == nullptr ? nullptr : dlsym(handle, fuseReqUserdataSymbol); // "fuse_req_userdata"
        auto fuseReqUserdataRegistered = RegisterHook(fuseReqUserdataSymbol,
                                                      (void *) new_fuse_req_userdata,
                                                      (void **) &old_fuse_req_userdata);
        fuseReqUserdataHooked = fuse_req_userdata != nullptr && fuseReqUserdataRegistered;
        if (fuseReqUserdataHooked) {
            fuseReqUserdataMethod = "exact";
        }
        if (!fuseReqUserdataHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find fuse_req_userdata")).c_str()); // "failed to find fuse_req_userdata"
            if (handle != nullptr) {
                lastError = "failed to find fuse_req_userdata";
            }
        }

        // 主拦截点：fuse_bpf_fill_entries（Android 16/17，有 PLT 槽，xhook 可拦）。
        // 回退顺序：fill(__1) -> install(__1) -> install(__ndk1)，最后由 GOT 模糊匹配兜底。
        const char *fuseBpfFillEntriesSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse21fuse_bpf_fill_entriesERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEiP16fuse_entry_paramRi");
        auto fuse_bpf_fill_entries = handle == nullptr ? nullptr : dlsym(handle, fuseBpfFillEntriesSymbol);
        auto fuseBpfFillEntriesRegistered = RegisterHook(fuseBpfFillEntriesSymbol,
                                                         (void *) new_fuse_bpf_fill_entries,
                                                         (void **) &old_fuse_bpf_fill_entries);
        fillEntriesHooked = fuse_bpf_fill_entries != nullptr && fuseBpfFillEntriesRegistered;
        if (fillEntriesHooked) {
            fillEntriesMethod = "exact";
        }
        // 旧平台回退：fuse_bpf_install 尚未被内联时需直接拦它（两种 libc++ 命名空间都试）。
        if (!fillEntriesHooked) {
            const char *fuseBpfInstallSymbol = AY_OBFUSCATE(
                    "_ZN13mediaprovider4fuse16fuse_bpf_installEP4fuseP16fuse_entry_paramRKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERi");
            auto fuse_bpf_install = handle == nullptr ? nullptr : dlsym(handle, fuseBpfInstallSymbol);
            auto fuseBpfInstallRegistered = RegisterHook(fuseBpfInstallSymbol,
                                                         (void *) new_fuse_bpf_install,
                                                         (void **) &old_fuse_bpf_install);
            installHooked = fuse_bpf_install != nullptr && fuseBpfInstallRegistered;
            if (installHooked) {
                installMethod = "exact";
            }
        }
        if (!fillEntriesHooked && !installHooked) {
            const char *fuseBpfInstallNdk1Symbol = AY_OBFUSCATE(
                    "_ZN13mediaprovider4fuse16fuse_bpf_installEP4fuseP16fuse_entry_paramRKNSt6__ndk112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERi");
            auto fuse_bpf_install_ndk1 =
                    handle == nullptr ? nullptr : dlsym(handle, fuseBpfInstallNdk1Symbol);
            auto fuseBpfInstallNdk1Registered = RegisterHook(fuseBpfInstallNdk1Symbol,
                                                             (void *) new_fuse_bpf_install,
                                                             (void **) &old_fuse_bpf_install);
            installHooked = fuse_bpf_install_ndk1 != nullptr && fuseBpfInstallNdk1Registered;
            if (installHooked) {
                installMethod = "exact";
            }
        }
        if (!fillEntriesHooked && !installHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE(
                    "failed to find fuse_bpf_fill_entries/fuse_bpf_install")).c_str());
            if (handle != nullptr) {
                lastError = "failed to find fuse_bpf_fill_entries/fuse_bpf_install";
            }
        }

        xhook_refresh(0);
        xhookRefreshCalled = true;

        // If any symbol failed exact xhook match, try GOT Patch as fallback.
        // BPF 有效位 effective = fillEntriesHooked || installHooked，任一命中即视为 BPF 链路有效。
        bool effective = fillEntriesHooked || installHooked;
        const bool xhookAllSucceeded = (!startsWithRequired || startsWithHooked) && containsMountHooked
                               && isFuseBpfEnabledHooked && fuseReqUserdataHooked
                               && effective;

        bool fallbackResolvedAny = false;
        if (!xhookAllSucceeded) {
            auto embeddedResult = HookEmbeddedFuseJni();
            // Merge: GOT Patch succeeded where xhook failed
            if (!startsWithHooked && embeddedResult.startsWithHooked) {
                startsWithHooked = true;
                startsWithMethod = MatchMethodName(embeddedResult.startsWithMethod);
                fallbackResolvedAny = true;
            }
            if (!containsMountHooked && embeddedResult.containsMountHooked) {
                containsMountHooked = true;
                containsMountMethod = MatchMethodName(embeddedResult.containsMountMethod);
                fallbackResolvedAny = true;
            }
            if (!isFuseBpfEnabledHooked && embeddedResult.isFuseBpfEnabledHooked) {
                isFuseBpfEnabledHooked = true;
                isFuseBpfEnabledMethod = MatchMethodName(embeddedResult.isFuseBpfEnabledMethod);
                fallbackResolvedAny = true;
            }
            if (!fuseReqUserdataHooked && embeddedResult.fuseReqUserdataHooked) {
                fuseReqUserdataHooked = true;
                fuseReqUserdataMethod = MatchMethodName(embeddedResult.fuseReqUserdataMethod);
                fallbackResolvedAny = true;
            }
            if (!fillEntriesHooked && embeddedResult.fillEntriesHooked) {
                fillEntriesHooked = true;
                fillEntriesMethod = MatchMethodName(embeddedResult.fillEntriesMethod);
                fallbackResolvedAny = true;
            }
            if (!installHooked && embeddedResult.installHooked) {
                installHooked = true;
                installMethod = MatchMethodName(embeddedResult.installMethod);
                fallbackResolvedAny = true;
            }
            effective = fillEntriesHooked || installHooked;
            const bool hookAvailableAfterFallback =
                    (!startsWithRequired || startsWithHooked) && containsMountHooked
                    && isFuseBpfEnabledHooked && fuseReqUserdataHooked
                    && effective;
            if (hookAvailableAfterFallback && fallbackResolvedAny) {
                lastError = "some symbols resolved via GOT patch fallback";
            } else if (!hookAvailableAfterFallback) {
                lastError = "native symbols missing after xhook/GOT fallback";
            }
        }

        const char *hookMode = xhookAllSucceeded
                               ? "XHOOK"
                               : (fallbackResolvedAny
                                  ? "XHOOK_WITH_GOT_FALLBACK"
                                  : "XHOOK_PARTIAL");
        return BuildHookStatusJson(true, fuseLibraryMapped, "libfuse_jni.so",
                                   hookMode,
                                   "SYSTEM_LIB", false,
                                   startsWithHooked, containsMountHooked,
                                   isFuseBpfEnabledHooked, fuseReqUserdataHooked,
                                   fillEntriesHooked, installHooked, effective,
                                   startsWithMethod, containsMountMethod,
                                   isFuseBpfEnabledMethod, fuseReqUserdataMethod,
                                   fillEntriesMethod, installMethod,
                                   xhookRefreshCalled, lastError.c_str());
    }

    void setMountPoint(JNIEnv *env, jclass clazz, jobjectArray value) {
        // 先在锁外完成解析与集合构建，成功后在临界区内一次性交换：
        // 消除 clear 后逐条插入的长临界区，且任何异常路径都保留旧表不产生空窗。
        std::set<std::string> new_mount_points;
        const jsize length = env->GetArrayLength(value);
        if (env->ExceptionCheck()) return;

        for (jsize i = 0; i < length; i++) {
            auto jpath = (jstring) env->GetObjectArrayElement(value, i);
            if (env->ExceptionCheck()) return;
            if (jpath == nullptr) continue;
            const char *path = env->GetStringUTFChars(jpath, nullptr);
            if (path == nullptr) {
                env->DeleteLocalRef(jpath);
                return;
            }

            std::string parent = path;
            env->ReleaseStringUTFChars(jpath, path);
            env->DeleteLocalRef(jpath);
            if (env->ExceptionCheck()) return;

            while (true) {
                if (!new_mount_points.insert(parent).second) {
                    break;
                }
                // find_last_of 替代 strdup+dirname：无堆分配且语义等价。
                const auto separator = parent.find_last_of('/');
                if (separator == std::string::npos || separator == 0) {
                    parent = "/";
                } else {
                    parent.resize(separator);
                }
                if (parent == PRIMARY_VOLUME_PREFIX || parent == "/") {
                    break;
                }
            }
        }

        // 构建期间若有 JNI 异常挂起，清除并放弃本次更新，保留旧配置。
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("%s", std::string(AY_OBFUSCATE(
                    "setMountPoint aborted by pending JNI exception")).c_str());
            return;
        }

        std::unique_lock<std::shared_mutex> lock(mountPointMutex);
        mountPoint.swap(new_mount_points);
    }

    void setRecordExternalAppSpecificStorage(JNIEnv *env, jclass clazz, jboolean value) {
        recordExternalAppSpecificStorage.store(value == JNI_TRUE, std::memory_order_relaxed);
    }

    /** 决策 D1 开关：非移除语义的 BPF 拦截范围，变更时打 LOG 留痕便于排障。 */
    void setFuseBpfBlockAll(JNIEnv *env, jclass clazz, jboolean value) {
        const bool next = (value == JNI_TRUE);
        const bool previous = fuseBpfBlockAll.exchange(next, std::memory_order_relaxed);
        if (previous != next) {
            // 该开关直接决定 BPF 拦截范围，属可观测的行为切换，变更时留痕便于排障。
            LOGI("%s", (std::string(AY_OBFUSCATE("fuseBpfBlockAll=")) +
                        (next ? "true" : "false")).c_str());
        }
    }

    /**
     * 单次 JNI 调用下发策略的三个维度：挂载点集合、记录偏好、BPF 拦截范围开关。
     * 消除 Java 侧分次 JNI 调用时“新挂载点配旧偏好/旧开关”的不一致窗口。
     * 注意：此处“单次下发”并非 CPU 原子事务，而是三次独立写入按固定顺序依次生效
     *（setRecord -> setBlockAll -> setMountPoint）；任一解析异常保留上一份有效配置。
     */
    void commitPolicy(JNIEnv *env, jclass clazz, jobjectArray value, jboolean record,
                      jboolean block_all) {
        setRecordExternalAppSpecificStorage(env, clazz, record);
        setFuseBpfBlockAll(env, clazz, block_all);
        setMountPoint(env, clazz, value);
    }
}  // namespace bpf_hook
