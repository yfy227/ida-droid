package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Layer 3: 对话管理器 — 编排 Layer 1 + Layer 2
 *
 * 核心流程：
 * 1. 用户消息 → 构建 OpenAI 请求 → Layer 1 HTTPS SSE 流
 * 2. SSE 事件 → 文本增量 / 思考增量 → 推送给 UI
 * 3. Finish(tool_calls) → Layer 2 执行工具 → 结果作为 tool 消息追加 → 回到步骤 1
 * 4. Finish(stop) → 对话结束
 *
 * 这就是"长对话模式"：AI 主动调用工具 → 执行 → 继续 → 直到完成。
 * 不再有 RPC 管道、进程管理、超时问题。
 *
 * 线程安全：所有对 current 对话状态的访问通过 mutex 保护。
 */
class ConversationManager(
    private val context: Context,
    private val paths: EnvironmentPaths,
    private val proot: IdaProotRuntime
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 对话事件流 — UI 订阅 */
    sealed interface ConvEvent {
        data class TextDelta(val text: String) : ConvEvent
        data class ThinkingDelta(val text: String) : ConvEvent
        data class ToolCallStart(val toolCallId: String, val toolName: String, val args: String) : ConvEvent
        data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String, val success: Boolean) : ConvEvent
        data class PhaseChange(val phase: String?) : ConvEvent  // null=idle, "connecting", "receiving", "executing_tool"
        data class Error(val message: String) : ConvEvent
        data class TokenUsageUpdate(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : ConvEvent
        data class Retrying(val attempt: Int, val reason: String, val delayMs: Long) : ConvEvent
        object TurnEnd : ConvEvent
    }

    data class ConvConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val providerId: String = "",
        val systemPrompt: String,
        val thinkingLevel: String? = null,
        val maxToolRounds: Int = 50,  // 最多工具调用轮次，防止无限循环
        val maxTokens: Int? = null,
        val temperature: Double? = null,
        val topP: Double? = null,
        /** 上下文窗口管理的估算 token 上限 */
        val contextTokenLimit: Int = 32_000,
        /** 单个工具调用超时（毫秒） */
        val toolTimeoutMs: Long = 120_000L
    )

    /** 一次对话的完整状态 */
    private data class Conversation(
        val config: ConvConfig,
        val messages: MutableList<ChatHttpClient.ChatMessageDto>,
        var toolRound: Int = 0,
        var activeJob: Job? = null,
        @Volatile var aborted: Boolean = false
    )

    private val mutex = Mutex()
    private var current: Conversation? = null
    private val toolBus = ToolEventBus(context, proot, paths)
    val events = MutableSharedFlow<ConvEvent>(extraBufferCapacity = 256)

    /** 累计 token 使用量 */
    @Volatile private var totalPromptTokens: Int = 0
    @Volatile private var totalCompletionTokens: Int = 0
    @Volatile private var totalTokens: Int = 0

    /** 当前对话的消息历史（持久化用） */
    suspend fun getMessages(): List<ChatHttpClient.ChatMessageDto> = mutex.withLock {
        current?.messages?.toList() ?: emptyList()
    }

    /** 获取累计 token 使用量 */
    fun getTokenUsage(): Triple<Int, Int, Int> = Triple(totalPromptTokens, totalCompletionTokens, totalTokens)

    /** 重置 token 计数器 */
    fun resetTokenUsage() {
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalTokens = 0
    }

    /**
     * 发送用户消息，启动对话流。
     *
     * 核心循环：
     *   HTTPS chat → 收到 tool_calls → 执行工具 → 追加 tool 结果 → 再次 HTTPS chat → ...
     *   直到收到 finish_reason=stop 或超过 maxToolRounds
     */
    suspend fun send(
        userText: String,
        images: List<String> = emptyList(),
        config: ConvConfig,
        onEvent: (ConvEvent) -> Unit
    ) {
        val conv = mutex.withLock {
            val existing = current
            if (existing != null && existing.config.model == config.model) {
                existing.aborted = false
                existing
            } else {
                val newConv = Conversation(config, mutableListOf())
                current = newConv
                newConv
            }
        }

        // 追加用户消息
        conv.messages.add(ChatHttpClient.ChatMessageDto(
            role = "user",
            content = userText,
            images = images
        ))

        // 上下文窗口管理：在发送前检查并截断
        trimContextIfNeeded(conv)

        val client = ChatHttpClient(config.baseUrl, config.apiKey, config.model, config.providerId)
        val tools = toolBus.toolDefinitions()

        onEvent(ConvEvent.PhaseChange("connecting"))

        while (conv.toolRound < config.maxToolRounds && !conv.aborted) {
            // 调用 LLM
            var textBuffer = StringBuilder()
            var thinkingBuffer = StringBuilder()
            var finishToolCalls: List<ChatHttpClient.ToolCallDto> = emptyList()
            var finishReason: String? = null
            var errorMessage: String? = null

            try {
                client.chat(
                    messages = conv.messages.toList(),
                    tools = tools,
                    systemPrompt = config.systemPrompt,
                    thinkingLevel = config.thinkingLevel,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    topP = config.topP
                ).collect { event ->
                    when (event) {
                        is ChatHttpClient.StreamEvent.TextDelta -> {
                            if (textBuffer.isEmpty()) onEvent(ConvEvent.PhaseChange("receiving"))
                            textBuffer.append(event.text)
                            onEvent(ConvEvent.TextDelta(event.text))
                        }
                        is ChatHttpClient.StreamEvent.ThinkingDelta -> {
                            thinkingBuffer.append(event.text)
                            onEvent(ConvEvent.ThinkingDelta(event.text))
                        }
                        is ChatHttpClient.StreamEvent.ToolCallDelta -> {
                            // 工具调用增量不实时推送，等 Finish 时统一处理
                        }
                        is ChatHttpClient.StreamEvent.Finish -> {
                            finishReason = event.reason
                            finishToolCalls = event.toolCalls
                            // 更新 token 统计
                            if (event.usage.totalTokens > 0) {
                                totalPromptTokens += event.usage.promptTokens
                                totalCompletionTokens += event.usage.completionTokens
                                totalTokens += event.usage.totalTokens
                                onEvent(ConvEvent.TokenUsageUpdate(
                                    totalPromptTokens, totalCompletionTokens, totalTokens
                                ))
                            }
                        }
                        is ChatHttpClient.StreamEvent.Error -> {
                            errorMessage = event.message
                        }
                        is ChatHttpClient.StreamEvent.Retrying -> {
                            onEvent(ConvEvent.Retrying(event.attempt, event.reason, event.delayMs))
                        }
                    }
                }
            } catch (e: CancellationException) {
                onEvent(ConvEvent.PhaseChange(null))
                onEvent(ConvEvent.Error("对话已中止"))
                onEvent(ConvEvent.TurnEnd)
                throw e
            }

            if (errorMessage != null) {
                // 先保存已收到的部分文本（如果有）
                if (textBuffer.isNotEmpty()) {
                    conv.messages.add(ChatHttpClient.ChatMessageDto(
                        role = "assistant",
                        content = textBuffer.toString(),
                        toolCalls = finishToolCalls
                    ))
                }
                onEvent(ConvEvent.Error(errorMessage!!))
                onEvent(ConvEvent.PhaseChange(null))
                onEvent(ConvEvent.TurnEnd)
                return
            }

            // 追加 assistant 消息到历史
            conv.messages.add(ChatHttpClient.ChatMessageDto(
                role = "assistant",
                content = textBuffer.toString().ifBlank { null },
                toolCalls = finishToolCalls
            ))

            // 如果没有工具调用，对话结束
            // 注意: 部分API返回 finishReason="stop" 同时携带 tool_calls，
            // 此时应该执行工具，而不是结束对话。
            if (finishToolCalls.isEmpty()) {
                onEvent(ConvEvent.PhaseChange(null))
                onEvent(ConvEvent.TurnEnd)
                return
            }

            // 执行工具调用 — 并行执行无依赖的工具
            onEvent(ConvEvent.PhaseChange("executing_tool"))
            val toolResults = executeToolCallsParallel(finishToolCalls, conv, config, onEvent)

            // 追加所有 tool 结果消息
            toolResults.forEach { (toolCall, result) ->
                conv.messages.add(ChatHttpClient.ChatMessageDto(
                    role = "tool",
                    content = result.output,
                    toolCallId = toolCall.id,
                    name = toolCall.name
                ))
            }

            conv.toolRound++

            // 上下文窗口管理：工具调用后也可能需要截断
            trimContextIfNeeded(conv)

            onEvent(ConvEvent.PhaseChange("connecting"))
        }

        if (conv.toolRound >= config.maxToolRounds) {
            onEvent(ConvEvent.Error("工具调用轮次超过上限 (${config.maxToolRounds})，已自动停止"))
        }

        onEvent(ConvEvent.PhaseChange(null))
        onEvent(ConvEvent.TurnEnd)
    }

    /**
     * 并行执行多个工具调用。
     * 每个工具有独立的超时保护。
     */
    private suspend fun executeToolCallsParallel(
        toolCalls: List<ChatHttpClient.ToolCallDto>,
        conv: Conversation,
        config: ConvConfig,
        onEvent: (ConvEvent) -> Unit
    ): List<Pair<ChatHttpClient.ToolCallDto, ToolResult>> {
        if (toolCalls.size == 1) {
            // 单工具调用 — 直接执行，无需 async 开销
            val tc = toolCalls.first()
            if (conv.aborted) return listOf(tc to ToolResult(false, "已中止", "Aborted"))
            onEvent(ConvEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
            val result = executeSingleTool(tc, config)
            onEvent(ConvEvent.ToolCallResult(tc.id, tc.name, result.output, result.success))
            return listOf(tc to result)
        }

        // 多工具并行
        onEvent(ConvEvent.PhaseChange("executing_tool"))
        val deferreds = toolCalls.map { tc ->
            scope.async {
                if (conv.aborted) {
                    ToolResult(false, "已中止", "Aborted")
                } else {
                    onEvent(ConvEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
                    val result = executeSingleTool(tc, config)
                    onEvent(ConvEvent.ToolCallResult(tc.id, tc.name, result.output, result.success))
                    result
                }
            }
        }
        return toolCalls.zip(deferreds.awaitAll())
    }

    /** 执行单个工具调用，带超时保护 */
    private suspend fun executeSingleTool(
        tc: ChatHttpClient.ToolCallDto,
        config: ConvConfig
    ): ToolResult {
        return try {
            withTimeoutOrNull(config.toolTimeoutMs) {
                val raw = toolBus.execute(tc.name, tc.arguments)
                // 统一成功/失败判断
                val success = toolBus.isToolResultSuccess(tc.name, raw)
                ToolResult(success, raw, null)
            } ?: ToolResult(false, "工具执行超时（${config.toolTimeoutMs / 1000}s）", "Timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "未知错误"
            ToolResult(false, "工具执行错误: $msg", msg)
        }
    }

    /**
     * 上下文窗口管理：当消息历史的估算 token 数超过限制时，
     * 从早期消息开始截断，保留 system 消息和最近的对话。
     * 确保不破坏 assistant(tool_calls) → tool 结果消息的配对。
     */
    private fun trimContextIfNeeded(conv: Conversation) {
        val config = conv.config
        val estimatedTokens = estimateTokens(conv.messages)
        if (estimatedTokens <= config.contextTokenLimit) return

        val msgs = conv.messages
        if (msgs.size <= 4) return

        // 目标保留条数（最近的一半）
        val keepRecent = msgs.size / 2
        // 候选截断点：从第 1 条之后开始（跳过第 0 条，通常是 system 或首条 user）
        var cutEnd = msgs.size - keepRecent
        if (cutEnd <= 1) return

        // 向前调整截断点：不能在 assistant(tool_calls) 和 tool 之间断开
        // 如果 cutEnd 处的消息是 tool 角色，向前回退到 tool_calls 的 assistant 消息之前
        while (cutEnd > 1 && msgs[cutEnd].role == "tool") {
            cutEnd--
        }
        // 如果 cutEnd 处的消息是 assistant 且带 tool_calls，也回退一条
        if (cutEnd > 1 && msgs[cutEnd].role == "assistant" && msgs[cutEnd].toolCalls.isNotEmpty()) {
            cutEnd--
        }

        if (cutEnd > 1) {
            // subList(1, cutEnd) 即可安全移除
            msgs.subList(1, cutEnd).clear()
        }
    }

    /** 粗略估算消息列表的 token 数 (1 token ≈ 4 chars for English, ≈ 2 chars for CJK) */
    private fun estimateTokens(messages: List<ChatHttpClient.ChatMessageDto>): Int {
        return messages.sumOf { msg ->
            val content = msg.content.orEmpty()
            val toolCallsSize = msg.toolCalls.sumOf { it.arguments.length + it.name.length }
            // 简化估算：英文约 4 chars/token，中文约 2 chars/token，取中间值 3
            (content.length + toolCallsSize) / 3
        }
    }

    /** 中止当前对话 */
    fun abort() {
        current?.let { conv ->
            conv.aborted = true
            conv.activeJob?.cancel()
        }
    }

    /** 重置对话（新会话） */
    suspend fun reset() = mutex.withLock {
        current = null
        resetTokenUsage()
    }

    /** 从已有消息历史恢复对话 */
    suspend fun restoreFromMessages(messages: List<ChatHttpClient.ChatMessageDto>, config: ConvConfig) = mutex.withLock {
        current = Conversation(config, messages.toMutableList())
    }
}

/** 结构化工具执行结果 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String?
)
