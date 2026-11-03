package dev.idadroid.v7

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * V7 L3: TurnOrchestrator — 单个用户 turn 的状态机编排器。
 *
 * 架构保证(vs V6 的 sendPromptInternal 300 行巨函数, 解 B 类全部):
 *  - 状态机显式: Idle→LLM→Tools→LLM→…→Done, **每个状态都有出口**(错误/超时/abort 路由到 sink)
 *  - 所有状态变更通过 [TurnSink] 写回(L3 不持有 UI 状态, 解 D1 大杂烩)
 *  - 工具执行由 [ToolExecutor] 注入(与引擎解耦, 解 E3 超时语义统一)
 *  - usage 累加在 orchestrator(每轮 LLM 的 Usage 事件相加), turn 末一次性报总
 */
class TurnOrchestrator(
    private val runner: StatelessAgentRunner,
    private val toolExecutor: ToolExecutor,
    private val store: TurnStore,
    private val sink: TurnSink
) {
    /** 最大工具轮(防失控; 引擎不再自旋) */
    private val maxToolRounds = 50

    /**
     * 执行一个完整 turn: 用户输入 → (LLM ↔ 工具)* → 结束。
     * 挂起协程取消 = abort(协作式, 每个 await 点检查)。
     */
    suspend fun runTurn(request: RunRequest, storeSessionId: String) = coroutineScope {
        var current = request
        var toolRound = 0
        var totalUsage = UsageAcc()
        val turnJob = coroutineContext[Job]

        sink.onPhase(storeSessionId, TurnPhase.LLM_RUNNING)
        while (true) {
            // ── 1. LLM 单轮 ──
            var roundFinish: EngineEvent.RoundFinished? = null
            var roundError: EngineEvent.Failed? = null
            runner.runOnce(current).collect { ev ->
                when (ev) {
                    is EngineEvent.Delta -> sink.onDelta(storeSessionId, ev.text)
                    is EngineEvent.Thinking -> sink.onThinking(storeSessionId, ev.text)
                    is EngineEvent.Usage -> totalUsage = totalUsage.plus(ev)
                    is EngineEvent.ToolRequested -> { /* 单轮内由 RoundFinished 统一结算 pending 列表 */ }
                    is EngineEvent.RoundFinished -> roundFinish = ev
                    is EngineEvent.Failed -> {
                        // 可重试的中间告知不终止; 终态失败落 store
                        if (!ev.retryable) roundError = ev
                        sink.onNotice(storeSessionId, ev.message)
                    }
                }
            }
            roundError?.let { fail ->
                store.appendAssistantError(storeSessionId, fail.message)
                sink.onPhase(storeSessionId, TurnPhase.FAILED)
                return@coroutineScope
            }
            val finish = roundFinish ?: run {
                store.appendAssistantError(storeSessionId, "引擎未返回轮终事件(流异常终止)")
                sink.onPhase(storeSessionId, TurnPhase.FAILED)
                return@coroutineScope
            }

            // ── 2. assistant 消息落 store(含 toolCalls — 解 A4: 完整往返) ──
            store.appendAssistant(storeSessionId, finish.assistantText, finish.pendingToolCalls.map {
                ApiToolCall(it.callId, it.toolName, it.argsJson)
            })

            // ── 3. 无工具请求 → turn 完成 ──
            if (finish.pendingToolCalls.isEmpty()) {
                store.addTurnUsage(storeSessionId, totalUsage.toQuadruple())
                sink.onPhase(storeSessionId, TurnPhase.DONE)
                return@coroutineScope
            }

            // ── 4. 工具轮 ──
            toolRound++
            if (toolRound > maxToolRounds) {
                store.appendSystemNote(storeSessionId, "工具轮次超上限($maxToolRounds), 本轮终止")
                sink.onPhase(storeSessionId, TurnPhase.DONE)
                return@coroutineScope
            }
            sink.onPhase(storeSessionId, TurnPhase.TOOLS_RUNNING)
            finish.pendingToolCalls.forEach { tc ->
                if (turnJob?.isCancelled == true) throw CancellationException("turn aborted")
                sink.onToolStart(storeSessionId, tc.toolName)
                val result = runCatching { toolExecutor.execute(tc.toolName, tc.argsJson) }
                    .getOrElse { ToolExecResult.error(it.message ?: "工具执行异常") }
                store.appendToolResult(storeSessionId, tc.callId, tc.toolName, result)
                sink.onToolEnd(storeSessionId, tc.toolName, result.ok)
            }

            // ── 5. 下一轮 LLM: 从 store 取全量消息(真相源, 解 C1) ──
            val nextMessages = store.apiMessages(storeSessionId)
            current = request.copy(messages = nextMessages.dropLast(1), userText = "")  // user 已在历史里
            sink.onPhase(storeSessionId, TurnPhase.LLM_RUNNING)
        }
    }
}

/** L3 对 store 的最小契约(实现由 L1 SessionStore 适配) */
interface TurnStore {
    fun apiMessages(sessionId: String): List<ApiMessage>
    fun appendAssistant(sessionId: String, text: String, toolCalls: List<ApiToolCall>)
    fun appendToolResult(sessionId: String, callId: String, toolName: String, result: ToolExecResult)
    fun appendAssistantError(sessionId: String, message: String)
    fun appendSystemNote(sessionId: String, message: String)
    fun addTurnUsage(sessionId: String, usage: Quadruple)
}

/** L3 对 UI 的最小契约(单向通知, 解 D1: UI 不再有 30 字段大杂烩) */
interface TurnSink {
    fun onPhase(sessionId: String, phase: TurnPhase)
    fun onDelta(sessionId: String, text: String)
    fun onThinking(sessionId: String, text: String)
    fun onToolStart(sessionId: String, toolName: String)
    fun onToolEnd(sessionId: String, toolName: String, ok: Boolean)
    fun onNotice(sessionId: String, message: String)
}

enum class TurnPhase { LLM_RUNNING, TOOLS_RUNNING, DONE, FAILED }

data class ToolExecResult(val ok: Boolean, val output: String, val status: String) {
    companion object {
        fun error(message: String) = ToolExecResult(false, message, "error")
        fun success(output: String) = ToolExecResult(true, output, "ok")
    }
}

/** L3 对工具执行的契约(超时/重试语义统一在这里 — 解 E3) */
interface ToolExecutor {
    suspend fun execute(toolName: String, argsJson: String): ToolExecResult
}

data class UsageAcc(val input: Int = 0, val output: Int = 0, val cached: Int = 0, val total: Int = 0) {
    fun plus(u: EngineEvent.Usage) = UsageAcc(
        input + u.inputTokens, output + u.outputTokens, cached + u.cachedTokens, total + u.totalTokens
    )
    fun toQuadruple() = Quadruple(input, output, cached, total)
}
data class Quadruple(val a: Int, val b: Int, val c: Int, val d: Int)
