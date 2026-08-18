package dev.idadroid.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 上下文窗口管理 — 消息存储、token 估算、自动压缩/截断。
 *
 * 设计参考：
 * - Vercel AI SDK: context management 作为独立 concern
 * - LangGraph: state channels 管理消息历史
 *
 * 与旧 ConversationManager.trimContextIfNeeded() 的核心区别：
 * 1. 消息存储和压缩逻辑封装在一处，不散落在 send() 循环里
 * 2. 压缩就地进行，不创建新对象 — 修复了旧代码 conv 引用不一致的 critical bug
 * 3. token 估算更精确：区分 CJK/ASCII，并考虑 toolCalls 的 arguments 开销
 */
class ContextWindow(
    private val tokenLimit: Int,
    private val compactor: (suspend (List<ChatHttpClient.ChatMessageDto>) -> String?)? = null
) {
    private val messages = mutableListOf<ChatHttpClient.ChatMessageDto>()
    private val mutex = Mutex()

    /** 追加消息 */
    suspend fun add(msg: ChatHttpClient.ChatMessageDto) = mutex.withLock {
        messages.add(msg)
    }

    /** 追加多条消息 */
    suspend fun addAll(msgs: List<ChatHttpClient.ChatMessageDto>) = mutex.withLock {
        messages.addAll(msgs)
    }

    /** 消息快照（线程安全） */
    suspend fun snapshot(): List<ChatHttpClient.ChatMessageDto> = mutex.withLock {
        messages.toList()
    }

    /** 就地替换所有消息 — 用于 compaction */
    suspend fun replaceAll(newMessages: List<ChatHttpClient.ChatMessageDto>) = mutex.withLock {
        messages.clear()
        messages.addAll(newMessages)
    }

    /** 当前消息数量 */
    suspend fun size(): Int = mutex.withLock { messages.size }

    /** 估算当前 token 数 */
    suspend fun estimatedTokens(): Int = mutex.withLock {
        estimateTokensLocked()
    }

    /**
     * 检查并执行上下文压缩。
     *
     * 优先级：
     * 1. LLM 摘要压缩（如果 compactor 已设置）
     * 2. 回退截断（保留近期消息，跳过 tool/assistant 配对）
     *
     * @return 压缩结果
     */
    suspend fun trimIfNeeded(): TrimResult = mutex.withLock {
        val estimated = estimateTokensLocked()
        if (estimated <= tokenLimit) return@withLock TrimResult.NotNeeded
        if (messages.size <= 4) return@withLock TrimResult.NotNeeded

        val beforeTokens = estimated
        val beforeSize = messages.size

        // 尝试 LLM 摘要压缩
        val callback = compactor
        if (callback != null) {
            val snapshot = messages.toList()
            val summary = try { callback(snapshot) } catch (_: Exception) { null }
            if (summary != null) {
                val keepCount = maxOf(4, messages.size / 3)
                val kept = messages.takeLast(keepCount)
                val newMessages = listOf(
                    ChatHttpClient.ChatMessageDto(role = "system", content = summary)
                ) + kept
                messages.clear()
                messages.addAll(newMessages)
                return@withLock TrimResult.Compacted(summary, beforeSize - newMessages.size, beforeTokens - estimateTokensLocked())
            }
        }

        // 回退截断
        val keepRecent = messages.size / 2
        var cutEnd = messages.size - keepRecent
        if (cutEnd <= 1) return@withLock TrimResult.NotNeeded

        // 跳过 tool/assistant(toolCalls) 消息，避免破坏配对
        while (cutEnd > 1) {
            val msg = messages[cutEnd]
            if (msg.role == "tool" || (msg.role == "assistant" && msg.toolCalls.isNotEmpty())) {
                cutEnd--
            } else {
                break
            }
        }

        if (cutEnd > 1) {
            messages.subList(1, cutEnd).clear()
        }
        TrimResult.Truncated(beforeSize - messages.size, beforeTokens - estimateTokensLocked())
    }

    /** 内部 token 估算 — 调用者必须持有 mutex */
    private fun estimateTokensLocked(): Int {
        return messages.sumOf { msg ->
            val content = msg.content.orEmpty()
            val toolCallsSize = msg.toolCalls.sumOf { it.arguments.length + it.name.length }
            estimateTokensForText(content) + estimateTokensForText(toolCallsSize.toString())
        }
    }

    /** 估算纯文本 token — CJK ~2 chars/token, ASCII ~4 chars/token */
    private fun estimateTokensForText(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3000..0x30FF) cjk++
            else ascii++
        }
        return (cjk / 2 + ascii / 4).coerceAtLeast(1)
    }

    /** 工具输出截断 — 防止单个工具结果撑爆上下文 */
    companion object {
        fun truncateToolOutput(output: String, contextTokenLimit: Int): String {
            val maxTokens = (contextTokenLimit / 4).coerceIn(2000, 16000)
            val maxChars = maxTokens * 3
            if (output.length <= maxChars) return output
            val kept = output.substring(0, maxChars)
            val totalLines = output.count { it == '\n' }
            val keptLines = kept.count { it == '\n' }
            return "$kept\n\n... [输出已截断: 显示 ${keptLines}/${totalLines} 行, ${maxChars}/${output.length} 字符] ..."
        }
    }
}
