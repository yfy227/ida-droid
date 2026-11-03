package dev.idadroid.v7

import kotlinx.coroutines.flow.Flow

/**
 * V7 L2: 无状态引擎契约 — run(messages, config) → Flow<EngineEvent>
 *
 * 架构保证(vs V6 补丁):
 *  - 零内部状态: 每次调用独立, 挂起即可弃(解 C1/C2)
 *  - 消息列表由调用方(L3)从 SessionStore 传入, 引擎不持有跨 turn 任何东西
 *  - 事件流单向: 事件只是"发生了什么", 状态改变只能由 L3 写回 store
 */
sealed interface EngineEvent {
    /** 模型开始产出 */
    data class Delta(val text: String) : EngineEvent
    /** 思考内容 */
    data class Thinking(val text: String) : EngineEvent
    /** 模型请求调用工具 */
    data class ToolRequested(
        val callId: String,
        val toolName: String,
        val argsJson: String
    ) : EngineEvent
    /** 本轮 LLM 调用的 usage(每轮 LLM 请求一次, 累加由 L3) */
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedTokens: Int,
        val totalTokens: Int
    ) : EngineEvent
    /** 一轮结束(含 finish_reason; followedBy 为空=对话完成) */
    data class RoundFinished(
        val finishReason: String?,
        val assistantText: String,
        /** 工具轮之后的下一动作提示(模型继续/待执行工具) */
        val pendingToolCalls: List<PendingToolCall>
    ) : EngineEvent
    /** 引擎级错误(网络/协议/服务端) — 流终止 */
    data class Failed(val code: Int?, val message: String, val retryable: Boolean) : EngineEvent
}

data class PendingToolCall(
    val callId: String,
    val toolName: String,
    val argsJson: String
)

/** 一次 run 的完整输入(纯数据, 无生命周期) */
data class RunRequest(
    val config: AgentEndpointConfig,
    /** 全量对话历史(含 tool 对, 由 store 提供) */
    val messages: List<ApiMessage>,
    /** 本轮新增用户内容 */
    val userText: String,
    val images: List<String> = emptyList(),
    val systemPrompt: String? = null,
    val tools: List<ToolDef> = emptyList(),
    val thinkingLevel: String? = null
)

/** 统一消息模型(store 持久化单位, 引擎输入输出同构 — 解 A4: tool 对完整往返) */
data class ApiMessage(
    val role: String,                    // system / user / assistant / tool
    val text: String = "",
    val images: List<String> = emptyList(),
    val toolCalls: List<ApiToolCall> = emptyList(),   // assistant 发起的调用
    val toolCallId: String? = null,      // tool 消息回指
    val toolName: String? = null,
    val toolResult: String? = null,
    val toolStatus: String? = null       // ok / error / timeout
)

data class ApiToolCall(
    val callId: String,
    val toolName: String,
    val argsJson: String
)

data class ToolDef(
    val name: String,
    val description: String,
    val parametersJson: String
)

/** 端点配置(协议显式, 解 V6.5 端点三重奏: baseUrl/protocol/headers 单处定义) */
data class AgentEndpointConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** openai-chat / openai-responses / anthropic / google */
    val protocol: String = "openai-chat",
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null
)

/**
 * L2 引擎门面 — 唯一实现契约。
 * L3 只调这一个方法; 传输(HTTP/SSE/重试/端点补全)在实现内部, 对外不可见。
 */
interface StatelessAgentRunner {
    /**
     * 执行**单轮** LLM 调用(含流式输出+可能的工具调用请求)。
     * 多轮工具循环由 L3 编排(每执行完工具再调一次 run)——引擎不再自旋(解 C2)。
     */
    fun runOnce(request: RunRequest): Flow<EngineEvent>
}
