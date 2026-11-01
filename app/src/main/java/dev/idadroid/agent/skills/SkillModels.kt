package dev.idadroid.agent.skills

import kotlinx.serialization.Serializable

/**
 * V6 技能系统模型 — 参考 Claude Code SKILL.md / Voyager skill library。
 *
 * 技能 = 声明式文档(触发词 + 流程 + 工具组合), 索引进系统提示词(省 token),
 * 正文模型按需 run_shell cat 读取。动态技能由 ExperienceEngine 从成功模式晋升。
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val category: String = "general",          // 逆向 / 动态分析 / 协议 / 报告 / 实用
    val triggers: List<String> = emptyList(),  // 触发词(用户提到即建议)
    val summary: String = "",                  // 一句话摘要(进提示词索引)
    val file: String = "",                     // 正文相对路径(空=纯索引型)
    val origin: String = "builtin",            // builtin / learned(经验晋升)
    val enabled: Boolean = true
)

@Serializable
data class SkillIndex(val skills: List<Skill> = emptyList())

/** 经验条目 — 每次工具调用的原始记录(RL 的 reward 信号源) */
@Serializable
data class ExperienceEntry(
    val toolName: String,
    val success: Boolean,
    val errorKind: String = "",                // error/timeout/empty/aborted
    val durationMs: Long = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

/** 使用模式 — 相邻成功工具序列的指纹(正强化候选) */
@Serializable
data class UsagePattern(
    val id: String,
    val toolSequence: List<String>,            // 成功序列 如 [pull_file, mcpc, mcpc]
    val argTemplate: String = "",              // 参数共性摘要(可空)
    val hits: Int = 1,                         // 累计出现次数
    val lastUsedMs: Long = System.currentTimeMillis(),
    val promotedToSkill: Boolean = false
)

/** 失败签名 — 负强化记忆(下轮提示词避坑) */
@Serializable
data class FailureSignature(
    val toolName: String,
    val errorKind: String,
    val occurrences: Int = 1,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val sampleMessage: String = ""             // 最近一次错误摘要(≤120字)
)

@Serializable
data class ExperienceStore(
    val entries: List<ExperienceEntry> = emptyList(),      // 环形: 只留最近 500
    val patterns: List<UsagePattern> = emptyList(),
    val failures: List<FailureSignature> = emptyList()
)
