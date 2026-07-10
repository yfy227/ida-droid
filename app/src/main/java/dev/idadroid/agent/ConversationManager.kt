package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        data class ToolCallStart(val toolName: String, val args: String) : ConvEvent
        data class ToolCallResult(val toolName: String, val result: String, val success: Boolean) : ConvEvent
        data class PhaseChange(val phase: String?) : ConvEvent  // null=idle, "connecting", "receiving", "executing_tool"
        data class Error(val message: String) : ConvEvent
        object TurnEnd : ConvEvent
    }

    data class ConvConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val systemPrompt: String,
        val thinkingLevel: String? = null,
        val maxToolRounds: Int = 50  // 最多工具调用轮次，防止无限循环
    )

    /** 一次对话的完整状态 */
    private data class Conversation(
        val config: ConvConfig,
        val messages: MutableList<ChatHttpClient.ChatMessageDto>,
        var toolRound: Int = 0,
        var activeJob: Job? = null,
        var aborted: Boolean = false
    )

    private var current: Conversation? = null
    val events = MutableSharedFlow<ConvEvent>(extraBufferCapacity = 256)

    /** 当前对话的消息历史（持久化用） */
    fun getMessages(): List<ChatHttpClient.ChatMessageDto> = current?.messages?.toList() ?: emptyList()

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
        val conv = current ?: Conversation(config, mutableListOf()).also { current = it }
        conv.aborted = false

        // 追加用户消息
        conv.messages.add(ChatHttpClient.ChatMessageDto(
            role = "user",
            content = userText,
            images = images
        ))

        val client = ChatHttpClient(config.baseUrl, config.apiKey, config.model)
        val tools = ToolEventBus(context, proot, paths).toolDefinitions()
        val toolBus = ToolEventBus(context, proot, paths)

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
                    thinkingLevel = config.thinkingLevel
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
                        }
                        is ChatHttpClient.StreamEvent.Error -> {
                            errorMessage = event.message
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
            if (finishToolCalls.isEmpty() || finishReason == "stop") {
                onEvent(ConvEvent.PhaseChange(null))
                onEvent(ConvEvent.TurnEnd)
                return
            }

            // 执行工具调用
            onEvent(ConvEvent.PhaseChange("executing_tool"))
            for (tc in finishToolCalls) {
                if (conv.aborted) break

                onEvent(ConvEvent.ToolCallStart(tc.name, tc.arguments))

                val result = try {
                    toolBus.execute(tc.name, tc.arguments)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "工具执行错误: ${e.message}"
                }

                val success = !result.startsWith("错误:")
                onEvent(ConvEvent.ToolCallResult(tc.name, result, success))

                // 追加 tool 结果消息
                conv.messages.add(ChatHttpClient.ChatMessageDto(
                    role = "tool",
                    content = result,
                    toolCallId = tc.id,
                    name = tc.name
                ))
            }

            conv.toolRound++
            onEvent(ConvEvent.PhaseChange("connecting"))
        }

        if (conv.toolRound >= config.maxToolRounds) {
            onEvent(ConvEvent.Error("工具调用轮次超过上限 (${config.maxToolRounds})，已自动停止"))
        }

        onEvent(ConvEvent.PhaseChange(null))
        onEvent(ConvEvent.TurnEnd)
    }

    /** 中止当前对话 */
    fun abort() {
        current?.let { conv ->
            conv.aborted = true
            conv.activeJob?.cancel()
        }
    }

    /** 重置对话（新会话） */
    fun reset() {
        current = null
    }

    /** 从已有消息历史恢复对话 */
    fun restoreFromMessages(messages: List<ChatHttpClient.ChatMessageDto>, config: ConvConfig) {
        current = Conversation(config, messages.toMutableList())
    }
}
