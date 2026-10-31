package dev.idadroid.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * 工具执行器 — 并行执行工具调用，带超时和 abort 保护。
 *
 * 设计参考：
 * - Vercel AI SDK: tool execution 作为独立 layer
 * - OpenAI Agents SDK: parallel tool calls
 *
 * 与旧 ConversationManager.executeToolCallsParallel() 的区别：
 * 1. 不关心 UI 事件 — 只负责执行，事件由 Engine 统一发射
 * 2. 结构化超时结果 — ToolTimeout 而非 error("超时")
 * 3. abort 检查通过 coroutineContext.isActive 自动传播，不需要手动检查 conv.aborted
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val context: ToolContext,
    private val defaultTimeoutMs: Long = 120_000L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 并行执行多个工具调用。
     *
     * 单工具直接执行避免 async 开销；多工具用 async + awaitAll。
     * 每个工具有独立的超时保护。
     *
     * @param toolCalls 工具调用列表
     * @param onProgress 进度回调（toolCallId, toolName, args, phase, outcome）
     * @return 执行结果列表，与输入一一对应
     */
    suspend fun execute(
        toolCalls: List<ChatHttpClient.ToolCallDto>,
        onProgress: ((String, String, String, String, ToolOutcome) -> Unit)? = null
    ): List<ToolExecution> {
        if (toolCalls.isEmpty()) return emptyList()

        // 单工具直接执行
        if (toolCalls.size == 1) {
            val tc = toolCalls.first()
            return listOf(executeSingle(tc, onProgress))
        }

        // 多工具并行
        return coroutineScope {
            val deferreds = toolCalls.map { tc ->
                async { executeSingle(tc, onProgress) }
            }
            deferreds.awaitAll()
        }
    }

    /** 执行单个工具调用 */
    private suspend fun executeSingle(
        tc: ChatHttpClient.ToolCallDto,
        onProgress: ((String, String, String, String, ToolOutcome) -> Unit)?
    ): ToolExecution {
        onProgress?.invoke(tc.id, tc.name, tc.arguments, "start", ToolOutcome.success(""))
        val start = System.currentTimeMillis()
        val outcome = try {
            withTimeoutOrNull(defaultTimeoutMs) {
                registry.execute(tc.name, tc.arguments, context)
            } ?: ToolOutcome.error("工具执行超时（${defaultTimeoutMs / 1000}s）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.error("工具执行错误: ${e.message ?: e::class.simpleName ?: "未知错误"}")
        }
        val duration = System.currentTimeMillis() - start
        onProgress?.invoke(tc.id, tc.name, tc.arguments, "result", outcome)
        return ToolExecution(tc, outcome, duration)
    }
}
