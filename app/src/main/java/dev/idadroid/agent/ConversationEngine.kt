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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对话引擎 — Agent Loop 的核心实现。
 *
 * 设计参考：
 * - Vercel AI SDK ToolLoopAgent: streamText + tool loop + prepareStep lifecycle
 * - OpenAI Agents SDK Runner.run(): simple while loop with tool execution
 * - LangGraph: state machine for explicit state transitions
 *
 * 架构分层：
 * - [ChatHttpClient] Layer 1: HTTPS SSE 流式客户端
 * - [ToolExecutor] Layer 2: 工具并行执行
 * - [ContextWindow] Layer 2.5: 上下文管理（token 估算、压缩、截断）
 * - [ConversationEngine] Layer 3: 编排以上三层，驱动 agent loop
 *
 * 核心循环：
 * 1. 追加用户消息 → trim context → 构建 API 请求
 * 2. SSE 流 → 文本/思考增量实时推送 → Finish
 * 3. 如果有工具调用 → 并行执行 → 追加 tool 结果 → 回到 2
 * 4. 如果无工具调用 → 对话结束
 *
 * 线程安全：
 * - 状态通过 MutableStateFlow (CAS 安全)
 * - 事件通过 MutableSharedFlow (buffered)
 * - 消息通过 ContextWindow 的 Mutex 保护
 * - abort 通过 cancel Job 传播
 */
class ConversationEngine(
    private val context: Context,
    private val paths: EnvironmentPaths,
    private val proot: IdaProotRuntime
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ══════════════════════════════════════════════════════════════
    // 状态 & 事件
    // ══════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Idle)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    // ══════════════════════════════════════════════════════════════
    // 组件
    // ══════════════════════════════════════════════════════════════

    private val toolContext = ToolContext(proot, paths, dev.idadroid.settings.IdaDroidSettings(appContext))
    private val toolRegistry = ToolRegistry().apply {
        register(ShellTool())
        register(ReadFileTool())
        register(WriteFileTool())
        register(ListDirTool())
        register(SearchFilesTool())
        register(DeleteFileTool())
        register(FileInfoTool())
        // V5: MCP 原生工具链 — 主动拉文件 / 直调 ida-mcp / 会话状态(function calling 通道,
        // 替代 run_shell→mcpc 文本链路; bridge 未接线时工具返回明确错误提示, 不影响无 MCP 场景)
        register(PullFileTool())
        register(McpCallTool())
        register(McpStatusTool())
    }

    /** V5: 注入 MCP 文件桥(UI 层连线) — 供 PullFileTool/McpCallTool/McpStatusTool 使用 */
    /** V7.1: v7 双轨通道复用旧工具链(L1-L3 换血期间桥接) */
    val v7ToolContext: ToolContext get() = toolContext
    val v7ToolRegistry: ToolRegistry get() = toolRegistry

    fun setFileBridge(bridge: McpFileBridge?) {
        toolContext.fileBridge = bridge
    }

    // V6: 经验强化学习引擎(工具成败信号→模式提炼/负记忆)
    private val experience = dev.idadroid.agent.skills.ExperienceEngine(appContext)

    /** V6: 暴露给 UI(技能面板显示学习状态) */
    val experienceEngine: dev.idadroid.agent.skills.ExperienceEngine get() = experience

    /** V5.1: 插件工具动态注册(插件市场启用插件后调用) */
    fun registerExtraTools(tools: List<AgentTool>) {
        tools.forEach { toolRegistry.register(it) }
    }

    /** V5.1: 按名称移除工具(插件禁用/卸载) */
    fun unregisterTool(name: String) {
        toolRegistry.unregister(name)
    }
    private val toolExecutor = ToolExecutor(toolRegistry, toolContext)

    // ══════════════════════════════════════════════════════════════
    // 会话状态
    // ══════════════════════════════════════════════════════════════

    private val sendMutex = Mutex()
    private var currentJob: Job? = null
    private var contextWindow: ContextWindow? = null
    private var currentConfig: ConversationConfig? = null

    /** 累计 token 用量 */
    @Volatile private var totalPromptTokens = 0
    @Volatile private var totalCompletionTokens = 0
    @Volatile private var totalTokens = 0
    @Volatile private var totalCachedPromptTokens = 0

    /** 设置 compactor — PiAgentManager 设置后启用 LLM 摘要压缩 */
    var compactor: (suspend (List<ChatHttpClient.ChatMessageDto>) -> String?)? = null

    // ══════════════════════════════════════════════════════════════
    // 公共 API
    // ══════════════════════════════════════════════════════════════

    /** 当前消息历史 */
    suspend fun getMessages(): List<ChatHttpClient.ChatMessageDto> =
        contextWindow?.snapshot() ?: emptyList()

    /** 获取 token 用量(prompt, completion, total, cachedPrompt) — V5 增加缓存命中统计 */
    fun getTokenUsage(): Triple<Int, Int, Int> =
        Triple(totalPromptTokens, totalCompletionTokens, totalTokens)

    fun getCachedPromptTokens(): Int = totalCachedPromptTokens

    /** 重置 token 计数器 */
    fun resetTokenUsage() {
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalTokens = 0
        totalCachedPromptTokens = 0
    }

    /** 重置对话（新会话） */
    suspend fun reset() = sendMutex.withLock {
        contextWindow = null
        currentConfig = null
        resetTokenUsage()
        _state.update { ConversationState.Idle }
    }

    /** 从已有消息历史恢复对话 */
    suspend fun restoreFromMessages(messages: List<ChatHttpClient.ChatMessageDto>, config: ConversationConfig) = sendMutex.withLock {
        val cw = ContextWindow(config.contextTokenLimit, compactor)
        cw.addAll(messages)
        contextWindow = cw
        currentConfig = config
        _state.update { ConversationState.Idle }
    }

    /**
     * 就地压缩消息列表 — 用于 autoCompact。
     * 不创建新 ContextWindow，直接替换消息列表。
     */
    suspend fun compactMessages(newMessages: List<ChatHttpClient.ChatMessageDto>): Boolean {
        val cw = contextWindow ?: return false
        cw.replaceAll(newMessages)
        return true
    }

    /**
     * 发送用户消息，启动 agent loop。
     *
     * 用 sendMutex 串行化 — 同一时间只允许一个 send()。
     * abort() 可从任意线程取消当前 send()。
     */

    /** 中止当前对话 */
    fun abort() {
        currentJob?.cancel()
    }

    // ══════════════════════════════════════════════════════════════
    // 核心：Agent Loop

    // ══════════════════════════════════════════════════════════════
    // LLM 流式调用
    // ══════════════════════════════════════════════════════════════

    private suspend fun streamLlmRound(
        client: ChatHttpClient,
        cw: ContextWindow,
        tools: List<ChatHttpClient.ToolDefinition>,
        config: ConversationConfig,
        onEvent: (ConversationEvent) -> Unit
    ): LlmRoundResult {
        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        var toolCalls = emptyList<ChatHttpClient.ToolCallDto>()
        var finishReason: String? = null
        var usage: ChatHttpClient.TokenUsage? = null
        var error: ConversationError? = null
        var firstDelta = true

        try {
            client.chat(
                messages = cw.llmMessages(),
                tools = tools,
                systemPrompt = config.systemPrompt,
                thinkingLevel = config.thinkingLevel,
                temperature = config.temperature,
                maxTokens = config.effectiveMaxTokens,
                topP = config.topP
            ).collect { event ->
                when (event) {
                    is ChatHttpClient.StreamEvent.TextDelta -> {
                        if (firstDelta) {
                            firstDelta = false
                            _state.update { ConversationState.Streaming }
                            emit(ConversationEvent.StateChanged(ConversationState.Streaming), onEvent)
                        }
                        textBuffer.append(event.text)
                        emit(ConversationEvent.TextDelta(event.text), onEvent)
                    }
                    is ChatHttpClient.StreamEvent.ThinkingDelta -> {
                        if (firstDelta) {
                            firstDelta = false
                            _state.update { ConversationState.Streaming }
                            emit(ConversationEvent.StateChanged(ConversationState.Streaming), onEvent)
                        }
                        thinkingBuffer.append(event.text)
                        emit(ConversationEvent.ThinkingDelta(event.text), onEvent)
                    }
                    is ChatHttpClient.StreamEvent.ToolCallDelta -> {
                        // 增量不实时推送，等 Finish 时统一处理
                    }
                    is ChatHttpClient.StreamEvent.Finish -> {
                        finishReason = event.reason
                        toolCalls = event.toolCalls
                        if (event.usage.totalTokens > 0) {
                            usage = event.usage
                            totalPromptTokens += event.usage.promptTokens
                            totalCompletionTokens += event.usage.completionTokens
                            totalTokens += event.usage.totalTokens
                            totalCachedPromptTokens += event.usage.cachedPromptTokens
                            emit(ConversationEvent.TokenUsage(
                                totalPromptTokens, totalCompletionTokens, totalTokens,
                                totalCachedPromptTokens
                            ), onEvent)
                        }
                    }
                    is ChatHttpClient.StreamEvent.Error -> {
                        error = ConversationError.LlmError(event.message, retriable = true)
                    }
                    is ChatHttpClient.StreamEvent.RoundReset -> {
                        // V5: 重试前本轮已 emit 的文本/思考全部作废 — 清 buffer 防内容翻倍
                        textBuffer.setLength(0)
                        thinkingBuffer.setLength(0)
                        toolCalls = emptyList()
                        emit(ConversationEvent.RoundReset, onEvent)
                    }
                    is ChatHttpClient.StreamEvent.Retrying -> {
                        _state.update { ConversationState.Retrying(event.attempt, event.reason) }
                        emit(ConversationEvent.Retrying(event.attempt, event.reason, event.delayMs), onEvent)
                        emit(ConversationEvent.StateChanged(
                            ConversationState.Retrying(event.attempt, event.reason)), onEvent)
                    }
                }
            }
        } catch (e: CancellationException) {
            // abort — 保存已收到的部分文本到上下文
            if (textBuffer.isNotEmpty()) {
                cw.add(ChatHttpClient.ChatMessageDto(
                    role = "assistant",
                    content = textBuffer.toString(),
                    toolCalls = toolCalls
                ))
            }
            throw e
        }

        return LlmRoundResult(
            text = textBuffer.toString(),
            thinking = thinkingBuffer.toString(),
            toolCalls = toolCalls,
            finishReason = finishReason,
            error = error,
            usage = usage
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════

    private fun getOrCreateContextWindow(config: ConversationConfig): ContextWindow {
        val existing = contextWindow
        return if (existing != null) {
            // 复用已有窗口
            existing
        } else {
            ContextWindow(config.contextTokenLimit, compactor)
        }
    }

    private fun emit(event: ConversationEvent, onEvent: (ConversationEvent) -> Unit) {
        // V6 经验强化学习: 轮结束钩子(全部完成出口统一收口)
        if (event is ConversationEvent.TurnComplete) {
            runCatching { experience.onTurnEnd() }
        }
        onEvent(event)
        _events.tryEmit(event)
    }
}
