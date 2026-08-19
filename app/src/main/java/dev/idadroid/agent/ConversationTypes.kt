package dev.idadroid.agent

// ══════════════════════════════════════════════════════════════
// 状态机 — 显式状态，替代字符串 phase
// ══════════════════════════════════════════════════════════════

sealed interface ConversationState {
    /** 空闲，等待用户输入 */
    data object Idle : ConversationState
    /** 正在连接 API */
    data object Connecting : ConversationState
    /** 正在接收流式文本 */
    data object Streaming : ConversationState
    /** 正在执行工具 */
    data class ExecutingTools(val toolNames: List<String>) : ConversationState
    /** 正在压缩上下文 */
    data object Compacting : ConversationState
    /** 正在重试 */
    data class Retrying(val attempt: Int, val reason: String) : ConversationState
    /** 对话完成 */
    data object Done : ConversationState
    /** 出错终止 */
    data class Failed(val error: ConversationError) : ConversationState
    /** 用户中止 */
    data object Aborted : ConversationState
}

// ══════════════════════════════════════════════════════════════
// 结构化事件 — 替代 ConvEvent 回调
// ══════════════════════════════════════════════════════════════

sealed interface ConversationEvent {
    /** 状态转换 */
    data class StateChanged(val to: ConversationState) : ConversationEvent
    /** 文本增量 */
    data class TextDelta(val text: String) : ConversationEvent
    /** 思考增量 */
    data class ThinkingDelta(val text: String) : ConversationEvent
    /** 工具调用开始 */
    data class ToolCallStart(val toolCallId: String, val toolName: String, val args: String) : ConversationEvent
    /** 工具调用结果 */
    data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String, val success: Boolean) : ConversationEvent
    /** Token 用量更新 */
    data class TokenUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : ConversationEvent
    /** 网络重试 */
    data class Retrying(val attempt: Int, val reason: String, val delayMs: Long) : ConversationEvent
    /** 轮次完成 */
    data object TurnComplete : ConversationEvent
    /** 错误 */
    data class Error(val error: ConversationError) : ConversationEvent
}

// ══════════════════════════════════════════════════════════════
// 结构化错误 — 替代 String error
// ══════════════════════════════════════════════════════════════

sealed interface ConversationError {
    /** LLM API 错误 */
    data class LlmError(val message: String, val retriable: Boolean = false) : ConversationError
    /** 工具执行错误 */
    data class ToolError(val toolName: String, val message: String) : ConversationError
    /** 工具执行超时 */
    data class ToolTimeout(val toolName: String, val timeoutMs: Long) : ConversationError
    /** 上下文溢出 */
    data class ContextOverflow(val estimatedTokens: Int, val limit: Int) : ConversationError
    /** 工具调用轮次超限 */
    data class MaxRoundsExceeded(val rounds: Int, val max: Int) : ConversationError
    /** 用户中止 */
    data object Aborted : ConversationError
}

// ══════════════════════════════════════════════════════════════
// 对话配置
// ══════════════════════════════════════════════════════════════

data class ConversationConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val providerId: String = "",
    val systemPrompt: String,
    val thinkingLevel: String? = null,
    val maxToolRounds: Int = 50,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val contextTokenLimit: Int = 32_000,
    val toolTimeoutMs: Long = 120_000L
) {
    /** Anthropic API 要求 max_tokens 必填 */
    val effectiveMaxTokens: Int? get() = maxTokens ?: if (providerId == "anthropic") 8192 else null
}

// ══════════════════════════════════════════════════════════════
// 上下文压缩结果
// ══════════════════════════════════════════════════════════════

sealed interface TrimResult {
    data object NotNeeded : TrimResult
    /** LLM 摘要压缩成功 */
    data class Compacted(val summary: String, val removedCount: Int, val savedTokens: Int) : TrimResult
    /** 回退截断 */
    data class Truncated(val removedCount: Int, val savedTokens: Int) : TrimResult
}

// ══════════════════════════════════════════════════════════════
// 单轮 LLM 调用结果
// ══════════════════════════════════════════════════════════════

data class LlmRoundResult(
    val text: String,
    val thinking: String,
    val toolCalls: List<ChatHttpClient.ToolCallDto>,
    val finishReason: String?,
    val error: ConversationError?,
    val usage: ChatHttpClient.TokenUsage?
)

// ══════════════════════════════════════════════════════════════
// 工具执行结果
// ══════════════════════════════════════════════════════════════

data class ToolExecution(
    val call: ChatHttpClient.ToolCallDto,
    val outcome: ToolOutcome,
    val durationMs: Long
)
