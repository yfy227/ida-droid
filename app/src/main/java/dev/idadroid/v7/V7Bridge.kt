package dev.idadroid.v7

import dev.idadroid.agent.ChatMessage
import dev.idadroid.agent.SessionStore
import dev.idadroid.agent.ToolRegistry
import dev.idadroid.agent.ToolContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * V7.1: L1/L2/L3 ↔ 旧世界(V6 SessionStore / ToolRegistry / UI 状态流)的桥。
 *
 * 桥只做"接线", 不做业务 — 业务已全部收进 v7 三层。
 * 旧世界的 DeltaFlusher / upsertTool / processingPhase 全部由 sink 回调注入(参数化)。
 */

// ══════════════════════════════════════════════════════════════
// TurnStore 实现: 直接落 SessionStore(L1), tool 对完整往返(解 A4)
// ══════════════════════════════════════════════════════════════
class V7TurnStoreBridge(
    private val store: SessionStore,
    private val newId: () -> String,
    /** V7.2: 消息落地即刷 UI(实时工具组卡/assistant 消息, 替代 turn 末快照) */
    private val onAppend: ((ChatMessage) -> Unit)? = null
) : TurnStore {
    private fun emit(m: ChatMessage) { onAppend?.invoke(m) }

    override fun apiMessages(sessionId: String): List<ApiMessage> =
        store.messages(sessionId).map { m -> m.toApiMessage() }

    override fun appendAssistant(sessionId: String, text: String, toolCalls: List<ApiToolCall>) {
        val m = ChatMessage(
            id = newId(), role = "assistant", text = text, timestamp = System.currentTimeMillis(),
            toolCallId = toolCalls.firstOrNull()?.callId, toolName = toolCalls.firstOrNull()?.toolName
        )
        store.appendMessage(sessionId, m); emit(m)
        toolCalls.forEach { tc ->
            // assistant 的工具请求作为独立 tool 消息(running 态), 结果到达时 upsert
            val t = ChatMessage(
                id = newId(), role = "tool", text = "", timestamp = System.currentTimeMillis(),
                toolCallId = tc.callId, toolName = tc.toolName,
                toolStatus = "running"
            )
            store.appendMessage(sessionId, t); emit(t)
        }
    }

    override fun appendToolResult(sessionId: String, callId: String, toolName: String, result: ToolExecResult) {
        // SessionStore 是 append-only; running→done 的更新走 replaceMessages upsert
        val msgs = store.messages(sessionId)
        val idx = msgs.indexOfLast { it.role == "tool" && it.toolCallId == callId && it.toolStatus == "running" }
        if (idx >= 0) {
            val updated = msgs.toMutableList()
            updated[idx] = updated[idx].copy(
                toolResult = result.output.take(4000), toolStatus = result.status
            )
            store.replaceMessages(sessionId, updated)
            emit(updated[idx])
        } else {
            val t = ChatMessage(
                id = newId(), role = "tool", text = "", timestamp = System.currentTimeMillis(),
                toolCallId = callId, toolName = toolName,
                toolResult = result.output.take(4000), toolStatus = result.status
            )
            store.appendMessage(sessionId, t); emit(t)
        }
    }

    override fun appendAssistantError(sessionId: String, message: String) {
        val m = ChatMessage(id = newId(), role = "assistant", text = message, timestamp = System.currentTimeMillis())
        store.appendMessage(sessionId, m); emit(m)
    }

    override fun appendSystemNote(sessionId: String, message: String) {
        val m = ChatMessage(id = newId(), role = "system", text = message, timestamp = System.currentTimeMillis())
        store.appendMessage(sessionId, m); emit(m)
    }

    override fun addTurnUsage(sessionId: String, usage: Quadruple) {
        store.addTurnUsage(sessionId, usage.a, usage.b, usage.c, usage.d)
    }
}

// ══════════════════════════════════════════════════════════════
// ToolExecutor 实现: 对接旧 ToolRegistry(超时语义统一在这 — 解 E3)
// ══════════════════════════════════════════════════════════════
class V7ToolExecutorBridge(
    private val registry: ToolRegistry,
    private val toolContext: ToolContext
) : ToolExecutor {
    override suspend fun execute(toolName: String, argsJson: String): ToolExecResult {
        val timeoutMs = adaptiveTimeoutMs(toolName)
        val outcome: ToolExecResult? = withTimeoutOrNull(timeoutMs) {
            val r = runCatching { registry.execute(toolName, argsJson, toolContext) }
            r.fold(
                onSuccess = { ToolExecResult.success(it.output ?: "") },
                onFailure = { ToolExecResult.error("执行异常: ${it.message?.take(120)}") }
            )
        }
        return outcome ?: ToolExecResult(
            ok = false,
            output = "工具 '$toolName' 超时(${timeoutMs / 1000}s)。建议拆小分析范围。",
            status = "timeout"
        )
    }

    private fun adaptiveTimeoutMs(name: String): Long = when {
        name.startsWith("decompile") || name.startsWith("get_") || name.startsWith("list_") -> 60_000L
        name.startsWith("search") || name.startsWith("scan") || name.startsWith("deep") -> 300_000L
        name.contains("batch") || name.contains("all") -> 600_000L
        else -> 120_000L
    }
}

// ══════════════════════════════════════════════════════════════
// TurnSink 实现: 参数化注入旧 UI 状态流(全部回调, 零状态)
// ══════════════════════════════════════════════════════════════
class V7TurnSinkBridge(
    private val onPhase: (TurnPhase) -> Unit,
    private val onDeltaText: (String) -> Unit,
    private val onDeltaThinking: (String) -> Unit,
    private val onToolUpsert: (callId: String, name: String, argsJson: String?, result: String?, status: String) -> Unit,
    private val onNotice: (String) -> Unit
) : TurnSink {
    override fun onPhase(sessionId: String, phase: TurnPhase) = onPhase(phase)
    override fun onDelta(sessionId: String, text: String) = onDeltaText(text)
    override fun onThinking(sessionId: String, text: String) = onDeltaThinking(text)
    override fun onToolStart(sessionId: String, toolName: String) { /* upsert 由 append 驱动 */ }
    override fun onToolEnd(sessionId: String, toolName: String, ok: Boolean) { /* 同上 */ }
    override fun onNotice(sessionId: String, message: String) = onNotice(message)
}

// ══════════════════════════════════════════════════════════════
// 映射: ChatMessage(L1) → ApiMessage(v7)
// ══════════════════════════════════════════════════════════════
fun ChatMessage.toApiMessage(): ApiMessage = ApiMessage(
    role = role,
    text = text,
    toolCallId = toolCallId,
    toolName = toolName,
    toolResult = toolResult,
    toolStatus = toolStatus
)
