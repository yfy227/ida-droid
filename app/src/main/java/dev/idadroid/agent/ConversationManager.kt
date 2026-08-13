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
    ) {
        /** 线程安全的消息追加 */
        @Synchronized
        fun appendMessage(msg: ChatHttpClient.ChatMessageDto) { messages.add(msg) }
        /** 线程安全的消息快照 */
        @Synchronized
        fun snapshotMessages(): List<ChatHttpClient.ChatMessageDto> = messages.toList()
    }

    private val mutex = Mutex()
    private var current: Conversation? = null
    private val toolContext = ToolContext(proot, paths, dev.idadroid.settings.IdaDroidSettings(context.applicationContext))
    private val toolRegistry = ToolRegistry().apply {
        register(ShellTool())
        register(ReadFileTool())
        register(WriteFileTool())
        register(ListDirTool())
        register(SearchFilesTool())
        register(DeleteFileTool())
        register(FileInfoTool())
    }

    /**
     * 自动压缩回调 — 当 trimContextIfNeeded 检测到上下文溢出时调用。
     *
     * PiAgentManager 设置此回调，在自动截断前用 LLM 生成摘要，
     * 替代直接丢弃消息。如果回调返回 false 或未设置，回退到直接截断。
     */
    var autoCompactCallback: (suspend (List<ChatHttpClient.ChatMessageDto>) -> Boolean)? = null

    /** 累计 token 使用量 */
    @Volatile private var totalPromptTokens: Int = 0
    @Volatile private var totalCompletionTokens: Int = 0
    @Volatile private var totalTokens: Int = 0

    /** 当前对话的消息历史（持久化用） */
    suspend fun getMessages(): List<ChatHttpClient.ChatMessageDto> = mutex.withLock {
        current?.snapshotMessages() ?: emptyList()
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
     *   HTTPS chat → 收到  → 执行工具 → 追加 tool 结果 → 再次 HTTPS chat → ...
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
        conv.appendMessage(ChatHttpClient.ChatMessageDto(
            role = "user",
            content = userText,
            images = images
        ))

        // 上下文窗口管理：在发送前检查并截断
        trimContextIfNeeded(conv)

        val client = ChatHttpClient(config.baseUrl, config.apiKey, config.model, config.providerId)
        val tools = toolRegistry.definitions()

        onEvent(ConvEvent.PhaseChange("connecting"))

        while (conv.toolRound < config.maxToolRounds && !conv.aborted) {
            // 调用 LLM 并收集流式事件
            val llmResult = collectLlmStream(client, conv, tools, config, onEvent)

            // 处理错误
            if (llmResult.error != null) {
                if (llmResult.textBuffer.isNotEmpty()) {
                    conv.appendMessage(ChatHttpClient.ChatMessageDto(
                        role = "assistant",
                        content = llmResult.textBuffer,
                        toolCalls = llmResult.finishToolCalls
                    ))
                }
                onEvent(ConvEvent.Error(llmResult.error))
                onEvent(ConvEvent.PhaseChange(null))
                onEvent(ConvEvent.TurnEnd)
                return
            }

            // 追加 assistant 消息到历史
            conv.appendMessage(ChatHttpClient.ChatMessageDto(
                role = "assistant",
                content = llmResult.textBuffer.ifBlank { null },
                toolCalls = llmResult.finishToolCalls
            ))

            // 如果没有工具调用，对话结束
            // 注意: 部分API返回 finishReason="stop" 同时携带 tool_calls，
            // 此时应该执行工具，而不是结束对话。
            if (llmResult.finishToolCalls.isEmpty()) {
                onEvent(ConvEvent.PhaseChange(null))
                onEvent(ConvEvent.TurnEnd)
                return
            }

            // 执行工具调用 — 并行执行无依赖的工具
            onEvent(ConvEvent.PhaseChange("executing_tool"))
            val toolResults = executeToolCallsParallel(llmResult.finishToolCalls, conv, config, onEvent)

            // 追加所有 tool 结果消息
            toolResults.forEach { (toolCall, result) ->
                conv.appendMessage(ChatHttpClient.ChatMessageDto(
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

    /** 单轮 LLM 流式调用的收集结果 */
    private data class LlmRoundResult(
        val textBuffer: String,
        val thinkingBuffer: String,
        val finishToolCalls: List<ChatHttpClient.ToolCallDto>,
        val finishReason: String?,
        val error: String?
    )

    /** 收集单轮 LLM SSE 流的所有事件 */
    private suspend fun collectLlmStream(
        client: ChatHttpClient,
        conv: Conversation,
        tools: List<ChatHttpClient.ToolDefinition>,
        config: ConvConfig,
        onEvent: (ConvEvent) -> Unit
    ): LlmRoundResult {
        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        var finishToolCalls: List<ChatHttpClient.ToolCallDto> = emptyList()
        var finishReason: String? = null
        var errorMessage: String? = null

        try {
            client.chat(
                messages = conv.snapshotMessages(),
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
            // abort 触发的取消 — 不 emit Error/TurnEnd，避免：
            // 1. "对话已中止" 不被 suppressAbortError 匹配（只匹配 "abort" 关键字）
            // 2. TurnEnd 导致 setTurnActive(false) 重复调用
            // abort() 链路会自行处理 UI 状态清理（setTurnActive + finishStreamingFlush）
            onEvent(ConvEvent.PhaseChange(null))
            throw e
        }

        return LlmRoundResult(
            textBuffer = textBuffer.toString(),
            thinkingBuffer = thinkingBuffer.toString(),
            finishToolCalls = finishToolCalls,
            finishReason = finishReason,
            error = errorMessage
        )
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
    ): List<Pair<ChatHttpClient.ToolCallDto, ToolOutcome>> {
        if (toolCalls.size == 1) {
            // 单工具调用 — 直接执行，无需 async 开销
            val tc = toolCalls.first()
            if (conv.aborted) return listOf(tc to ToolOutcome.error("已中止"))
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
                    ToolOutcome.error("已中止")
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
    ): ToolOutcome {
        return try {
            withTimeoutOrNull(config.toolTimeoutMs) {
                toolRegistry.execute(tc.name, tc.arguments, toolContext)
            } ?: ToolOutcome.error("工具执行超时（${config.toolTimeoutMs / 1000}s）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "未知错误"
            ToolOutcome.error("工具执行错误: $msg")
        }
    }

    /**
     * 上下文窗口管理：当消息历史的估算 token 数超过限制时触发自动压缩。
     *
     * 优先调用 autoCompactCallback（LLM 摘要），回调不可用时回退到直接截断。
     * 截断时保留 system 消息和最近的对话，确保不破坏 assistant() → tool 结果消息的配对。
     */
    private suspend fun trimContextIfNeeded(conv: Conversation) {
        val config = conv.config
        // 检查是否需要压缩
        synchronized(conv) {
            val msgs = conv.messages
            val estimatedTokens = estimateTokens(msgs)
            if (estimatedTokens <= config.contextTokenLimit) return
            if (msgs.size <= 4) return
        }

        // 尝试 LLM 自动压缩 — 让 PiAgentManager 生成摘要并截断消息
        val callback = autoCompactCallback
        if (callback != null) {
            val snapshot = conv.snapshotMessages()
            val success = try { callback(snapshot) } catch (_: Exception) { false }
            if (success) return
        }

        // 回退：直接截断（LLM 不可用或回调返回 false）
        synchronized(conv) {
            val msgs = conv.messages
            if (msgs.size <= 4) return

            val keepRecent = msgs.size / 2
            var cutEnd = msgs.size - keepRecent
            if (cutEnd <= 1) return

            // 向前调整截断点，跳过 tool/assistant() 消息
            while (cutEnd > 1) {
                val msg = msgs[cutEnd]
                if (msg.role == "tool" || (msg.role == "assistant" && msg.toolCalls.isNotEmpty())) {
                    cutEnd--
                } else {
                    break
                }
            }

            if (cutEnd > 1) {
                msgs.subList(1, cutEnd).clear()
            }
        }
    }

    /**
     * 估算消息列表的 token 数。
     *
     * 改进：区分中文字符（~2 chars/token）和 ASCII 字符（~4 chars/token），
     * 比旧的统一 chars/3 估算更准确，减少上下文窗口浪费或溢出。
     */
    private fun estimateTokens(messages: List<ChatHttpClient.ChatMessageDto>): Int {
        return messages.sumOf { msg ->
            val content = msg.content.orEmpty()
            val toolCallsSize = msg.toolCalls.sumOf { it.arguments.length + it.name.length }
            estimateTokensForText(content) + estimateTokensForText(toolCallsSize.toString())
        }
    }

    /** 估算纯文本的 token 数 — 区分 CJK 和 ASCII */
    private fun estimateTokensForText(text: String): Int {
        if (text.isEmpty()) return 0
        var cjkChars = 0
        var asciiChars = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3000..0x30FF) {
                cjkChars++
            } else {
                asciiChars++
            }
        }
        // CJK: ~2 chars/token, ASCII: ~4 chars/token
        return (cjkChars / 2 + asciiChars / 4).coerceAtLeast(1)
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
        // 使用普通 MutableList，与 Conversation 构造器一致，统一同步策略。
        // 线程安全由 Conversation 的 @Synchronized 方法（appendMessage/snapshotMessages）
        // 和 trimContextIfNeeded 的 synchronized(conv) 统一保证，都锁在 Conversation 实例上。
        // Collections.synchronizedList 有自己的内部锁，与 @Synchronized(conv) 不一致：
        // trimContextIfNeeded 直接对 conv.messages 做 subList().clear()（compound 操作），
        // 此时 synchronizedList 的锁不起作用，只有 synchronized(conv) 能保证原子性。
        current = Conversation(config, messages.toMutableList())
    }
}
