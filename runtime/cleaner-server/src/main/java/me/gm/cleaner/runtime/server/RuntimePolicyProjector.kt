package me.gm.cleaner.runtime.server

import android.util.Log
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.config.ConfigSourceHealth
import me.gm.cleaner.core.config.ConfiguredPolicySnapshot
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.domain.InaccessiblePathDeriver
import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectInterpreter
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import me.gm.cleaner.core.storage.redirect.domain.RuntimeBehaviorPolicy
import me.gm.cleaner.core.storage.redirect.domain.RuntimeStoragePolicy
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/**
 * 运行时策略投影器：配置到运行时的唯一转换口。
 *
 * 从 [ConfiguredPolicySnapshot]（配置门面的一次性读取结果）生成
 * [RedirectPolicySnapshot]。
 * 使用单调递增的 [generationCounter] 确保代数独立性——不依赖系统时钟。
 * 只负责投影，不负责持久化、运行与发布。
 */
object RuntimePolicyProjector {
    private const val PUBLISHER_IDENTITY = "RuntimePolicyProjector"
    val publisherEpoch: String = UUID.randomUUID().toString()

    /** 单调递增的策略代数计数器 */
    private val generationCounter = AtomicLong(0)

    /**
     * 投影当前策略快照。
     * 每次调用生成一个新的 generation。
     */
    fun project(userIds: List<Int>): RedirectPolicySnapshot {
        check(ConfiguredPolicyStoreProvider.isInitialized()) {
            "ConfiguredPolicyStore 尚未初始化"
        }
        return project(ConfiguredPolicyStoreProvider.instance.readSnapshot(), userIds)
    }

    /** 使用一次读取的配置组合投影运行时快照，避免同一发布批次重复读取旧文件。 */
    fun project(
        configured: ConfiguredPolicySnapshot,
        userIds: List<Int>,
    ): RedirectPolicySnapshot {
        val now = System.currentTimeMillis()
        val configuredRedirect = configured.redirect
        val configuredReadOnly = configured.readOnly
        require(configuredRedirect.health != ConfigSourceHealth.CORRUPT) {
            "重定向配置损坏: ${configuredRedirect.diagnostics.joinToString()}"
        }
        require(configuredReadOnly.health != ConfigSourceHealth.CORRUPT) {
            "只读配置损坏: ${configuredReadOnly.diagnostics.joinToString()}"
        }
        val gen = generationCounter.incrementAndGet()
        val rules = LinkedHashMap<String, Map<Int, List<RedirectRule>>>()
        val preferencesReady = ServicePreferences.isInitialized()
        val readOnlyRules = buildReadOnlyRules(configuredReadOnly.envelope)

        for (policy in configuredRedirect.envelope.redirectPolicies) {
            val packageName = policy.scope.packageName
            val userRules = LinkedHashMap<Int, List<RedirectRule>>()
            for (userId in userIds) {
                val zipped = policy.rules.map { it.source to it.target }.map { (source, target) ->
                    RuntimeFileUtils.getPathAsUser(source, userId) to
                        RuntimeFileUtils.getPathAsUser(target, userId)
                }
                if (zipped.isNotEmpty()) {
                    val validRules = zipped.mapNotNull { (source, target) ->
                        buildRedirectRuleOrNull(packageName, userId, source, target)
                    }
                    if (validRules.isNotEmpty()) {
                        userRules[userId] = validRules
                    }
                }
            }
            if (userRules.isNotEmpty()) {
                rules[packageName] = userRules
            }
        }

        // 「不可访问的路径」不是独立配置：它由向导 q4 展开成的特殊形态重定向规则
        // 反解得到（判据见 InaccessiblePathDeriver）。放在投影期算一次，
        // 下游（MediaProvider Hook）直接消费，不在热路径重复推导。
        val inaccessibleRules = buildInaccessibleRules(rules)

        return RedirectPolicySnapshot(
            schemaVersion = 1,
            generation = gen,
            publisherEpoch = publisherEpoch,
            createdAt = now,
            publisher = PUBLISHER_IDENTITY,
            redirectRevision = configuredRedirect.revision,
            readOnlyRevision = configuredReadOnly.revision,
            storage = RuntimeStoragePolicy(
                redirectRules = rules,
                readOnlyRules = readOnlyRules,
                inaccessibleRules = inaccessibleRules,
            ),
            behavior = RuntimeBehaviorPolicy(
                deniedPackages = if (preferencesReady) ServicePreferences.denylist.toSet() else emptySet(),
                recordSharedStorage = if (preferencesReady) ServicePreferences.recordSharedStorage else false,
                recordExternalAppSpecificStorage = if (preferencesReady) {
                    ServicePreferences.recordExternalAppSpecificStorage
                } else {
                    false
                },
                // 真相归偏好层：此处只做透传，不按 ROM 做条件改写。
                fuseBpfBlockAll = if (preferencesReady) ServicePreferences.fuseBpfBlockAll else false,
                aggressivelyPromptForReadingMediaFiles = if (preferencesReady) {
                    ServicePreferences.aggressivelyPromptForReadingMediaFiles
                } else {
                    false
                },
                // 真相归偏好层：只做透传，不按 ROM 做条件改写。
                auditMediaInsert = if (preferencesReady) {
                    ServicePreferences.auditMediaInsert
                } else {
                    false
                },
                // 真相归偏好层：只做透传，不按 ROM 做条件改写。
                denyMediaInsertWrites = if (preferencesReady) {
                    ServicePreferences.denyMediaInsertWrites
                } else {
                    false
                },
                upsertRecords = if (preferencesReady) ServicePreferences.upsert else true,
            ),
        )
    }

    /**
     * 构建停止态策略快照。
     *
     * 停止服务是用户主动暂停所有运行能力，不应继续向 Hook/VFS 暴露旧规则。
     */
    fun projectStopped(): RedirectPolicySnapshot {
        val now = System.currentTimeMillis()
        val gen = generationCounter.incrementAndGet()
        return RedirectPolicySnapshot(
            schemaVersion = 1,
            generation = gen,
            publisherEpoch = publisherEpoch,
            createdAt = now,
            publisher = PUBLISHER_IDENTITY,
            behavior = RuntimeBehaviorPolicy(upsertRecords = false),
        )
    }

    private fun buildReadOnlyRules(
        envelope: StoragePolicyEnvelope,
    ): Map<String, List<String>> {
        val rules = LinkedHashMap<String, List<String>>()
        for ((packageName, paths) in envelope.readOnlyRules.groupBy {
            it.scope.packageName
        }.mapValues { (_, values) -> values.map { it.visiblePath } }) {
            val validPaths = paths.mapNotNull { path ->
                val normalized = validateExternalStoragePath(path)?.let {
                    RuntimeFileUtils.getPathAsUser(it, 0)
                }
                if (normalized == null) {
                    Log.w(
                        "MC_REDIRECT",
                        "[RuntimePolicyProjector] drop invalid read-only rule " +
                                "pkg=$packageName path=$path"
                    )
                }
                normalized
            }.distinct()
            if (validPaths.isNotEmpty()) {
                rules[packageName] = validPaths
            }
        }
        return rules
    }

    /**
     * 从已投影的重定向规则里反解「不可访问」target（向导 q4）。
     *
     * 只读在配置里是独立规则族，而 q4 不是 —— 它只表现为形态特殊的重定向规则。
     * 运行时若不做这一步反解，MediaProvider 就只能看到「一个指向应用自己 cache
     * 子目录的普通挂载」，于是 MediaStore 查询结果仍会把真实文件整条吐给应用，
     * 「完全识别不到」做不到。
     */
    private fun buildInaccessibleRules(
        rules: Map<String, Map<Int, List<RedirectRule>>>,
    ): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for ((packageName, userRules) in rules) {
            val targets = LinkedHashSet<String>()
            for ((userId, list) in userRules) {
                val sdDir = RuntimeFileUtils.getPathAsUser(
                    RuntimeFileUtils.externalStorageDir.path, userId)
                val cacheDir = RuntimeFileUtils.getPathAsUser(
                    File(File(RuntimeFileUtils.androidDataDir, packageName), "cache").path, userId)
                val byMarker = InaccessiblePathDeriver.deriveByMarker(list, packageName)
                val byEnv = InaccessiblePathDeriver.derive(list, sdDir, cacheDir)
                targets += byMarker + byEnv
                // 无条件诊断（放行期证据）：externalStorageDir 在 root app_process 下的
                // 取值与配置里的存储坐标未必同源，两条判据一旦不一致必须留下痕迹。
                // 只按配置变更触发，不在访问热路径上。
                Log.i(
                    "MC_REDIRECT",
                    "[RuntimePolicyProjector] inaccessible scan pkg=$packageName user=$userId " +
                        "rules=${list.size} sdDir=$sdDir cacheDir=$cacheDir " +
                        "byMarker=${byMarker.size} byEnv=${byEnv.size}"
                )
            }
            if (targets.isNotEmpty()) {
                out[packageName] = targets.toList()
                Log.i(
                    "MC_REDIRECT",
                    "[RuntimePolicyProjector] inaccessible pkg=$packageName targets=$targets"
                )
            }
        }
        return out
    }

    private fun buildRedirectRuleOrNull(
        packageName: String,
        userId: Int,
        source: String,
        target: String,
    ): RedirectRule? {
        val normalizedSource = validateExternalStoragePath(source)
        val normalizedTarget = validateExternalStoragePath(target)
        if (normalizedSource == null || normalizedTarget == null) {
            Log.w(
                "MC_REDIRECT",
                "[RuntimePolicyProjector] drop invalid rule pkg=$packageName " +
                        "user=$userId source=$source target=$target"
            )
            return null
        }
        // 快照规则必须规范：下游解释器永不抛异常，非规范直接丢弃并记录。
        if (!OrderedRedirectInterpreter.isCanonicalAbsolutePath(normalizedSource) ||
            !OrderedRedirectInterpreter.isCanonicalAbsolutePath(normalizedTarget)
        ) {
            Log.w(
                "MC_REDIRECT",
                "[RuntimePolicyProjector] drop non-canonical rule pkg=$packageName " +
                        "user=$userId source=$source target=$target"
            )
            return null
        }
        return RedirectRule(source = normalizedSource, target = normalizedTarget)
    }

    /** 配置门面已经完成词法规范化；这里仅验证运行时卷边界。 */
    private fun validateExternalStoragePath(path: String): String? {
        if (path.isEmpty() || '\u0000' in path || !path.startsWith("/")) {
            return null
        }
        return path.takeIf {
            RuntimeFileUtils.childOf(RuntimeFileUtils.externalStorageDirParent, it)
        }
    }

}
