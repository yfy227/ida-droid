package dev.idadroid.agent.skills

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * V6 经验引擎 — 强化学习(RL-lite, Voyager/Reflexion 式)。
 *
 * 信号: 每次 ToolCallResult 的 (tool, success, errorKind, duration)
 * 正强化: 同一会话内连续成功序列(≥2) → 指纹匹配累计, hits≥3 晋升 learned 技能
 * 负强化: 失败签名(tool+errorKind)累计, 进下轮提示词避坑段
 * 衰减: 30 天未用 pattern 降权重排; 失败 7 天未被再次触发自动清零
 * 持久化: filesDir/skills/experience.json(宿主, 跨会话)
 */
class ExperienceEngine(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val storeFile: File get() = File(context.filesDir, "skills/experience.json")

    @Volatile
    private var store: ExperienceStore = ExperienceStore()

    /** 当前轮内的成功序列缓冲(轮开始清空) */
    private val turnSequence = mutableListOf<String>()

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureLoaded() {
        if (initialized) return
        store = runCatching {
            if (storeFile.isFile)
                json.decodeFromString(ExperienceStore.serializer(), storeFile.readText())
            else ExperienceStore()
        }.getOrDefault(ExperienceStore())
        initialized = true
    }

    /** 轮开始 — 清序列缓冲 */
    fun onTurnStart() {
        ensureLoaded()
        turnSequence.clear()
    }

    /** 工具结果 — 单点喂入(RL step) */
    @Synchronized
    fun onToolResult(toolName: String, success: Boolean, errorMessage: String?, durationMs: Long) {
        ensureLoaded()
        val errorKind = when {
            success -> ""
            errorMessage == null -> "error"
            errorMessage.contains("timed out", true) || errorMessage.contains("timeout", true) -> "timeout"
            errorMessage.contains("abort", true) || errorMessage.contains("取消", true) -> "aborted"
            else -> "error"
        }
        val entry = ExperienceEntry(toolName, success, errorKind, durationMs)
        store = store.copy(entries = (store.entries + entry).takeLast(500))

        if (success) {
            turnSequence.add(toolName)
        } else {
            // 失败打断序列 + 记签名
            turnSequence.clear()
            val sig = "$toolName|$errorKind"
            val existing = store.failures.find { "${it.toolName}|${it.errorKind}" == sig }
            store = store.copy(failures = if (existing == null) {
                store.failures + FailureSignature(
                    toolName, errorKind, 1, System.currentTimeMillis(),
                    (errorMessage ?: "").take(120)
                )
            } else {
                store.failures.map {
                    if (it === existing) it.copy(
                        occurrences = it.occurrences + 1,
                        lastSeenMs = System.currentTimeMillis(),
                        sampleMessage = (errorMessage ?: "").take(120)
                    ) else it
                }
            })
        }
        persist()
    }

    /** 轮结束 — 序列指纹化(正强化) */
    @Synchronized
    fun onTurnEnd() {
        ensureLoaded()
        // 取当前轮成功序列的所有长度≥2的后缀? 不 — 用完整序列+其 2-gram 骨架
        if (turnSequence.size >= 2) {
            val seq = turnSequence.toList()
            foldIntoPattern(seq)
            // 相邻二元组也计(更泛化的骨架)
            if (seq.size > 2) {
                seq.windowed(2).forEach { foldIntoPattern(it) }
            }
            turnSequence.clear()
        }
        decayIfNeeded()
        persist()
    }

    private fun foldIntoPattern(seq: List<String>) {
        val fp = seq.joinToString("→")
        val existing = store.patterns.find { it.toolSequence.joinToString("→") == fp }
        store = if (existing == null) {
            store.copy(patterns = store.patterns + UsagePattern(
                id = "p-${seq.joinToString("_").take(40)}",
                toolSequence = seq, hits = 1
            ))
        } else {
            store.copy(patterns = store.patterns.map {
                if (it === existing) it.copy(hits = it.hits + 1, lastUsedMs = System.currentTimeMillis()) else it
            })
        }
        // 晋升检查
        val updated = store.patterns.find { it.toolSequence.joinToString("→") == fp }
        if (updated != null && updated.hits >= 3 && !updated.promotedToSkill) {
            promote(updated)
        }
    }

    /** 模式晋升为 learned 技能 */
    private fun promote(p: UsagePattern) {
        val seq = p.toolSequence.joinToString(" → ")
        val skill = Skill(
            id = "learned-${p.id.take(24)}",
            name = "惯用链: ${p.toolSequence.first()}…${p.toolSequence.last()}",
            category = "learned",
            summary = "高频成功链 ×${p.hits}: $seq",
            origin = "learned",
            enabled = true
        )
        runCatching { SkillLibrary.upsertLearned(context, skill) }
        store = store.copy(patterns = store.patterns.map {
            if (it.id == p.id) it.copy(promotedToSkill = true) else it
        })
    }

    /** 衰减: 30 天未用的 pattern 砍半; 7 天未复现的失败签名清除 */
    private fun decayIfNeeded() {
        val now = System.currentTimeMillis()
        store = store.copy(
            patterns = store.patterns.map {
                if (now - it.lastUsedMs > 30L * 24 * 3600_000) it.copy(hits = (it.hits + 1) / 2) else it
            },
            failures = store.failures.filter { now - it.lastSeenMs < 7L * 24 * 3600_000 }
        )
    }

    private fun persist() = runCatching {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(json.encodeToString(ExperienceStore.serializer(), store))
    }

    /** 供提示词构建 */
    fun snapshot(): ExperienceStore { ensureLoaded(); return store }
}
