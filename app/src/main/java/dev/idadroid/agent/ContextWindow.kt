package dev.idadroid.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 上下文窗口管理 V5 — 非破坏分层压缩 + 缓存对齐。
 *
 * 设计参考:
 * - pi-mono context pruning: 原始消息保留在 session(LLM 视图才压缩) → 重启/回看历史无损
 * - Claude Code auto-compact: 80% 软阈值(摘要) / 95% 硬截断兜底
 * - OpenAI prompt caching: LLM 视图 append-only — 已生成的视图条目永不重算,
 *   新消息只追加 → 请求前缀字节级稳定 → 缓存命中率最大化。
 *   压缩/摘要是**有意识的缓存重置点**(报告 cacheReset=true), 而不是随机时机的前缀漂移。
 *
 * 两层结构:
 * - [snapshot] 完整历史(持久化/恢复/导出用, 永不压缩)
 * - [llmMessages] LLM 请求视图(append-only 投影 + 压缩重置)
 */
class ContextWindow(
    private val tokenLimit: Int,
    private val compactor: (suspend (List<ChatHttpClient.ChatMessageDto>) -> String?)? = null
) {
    private val messages = mutableListOf<ChatHttpClient.ChatMessageDto>()
    private val mutex = Mutex()

    // ── LLM 视图缓存(append-only) ──────────────────────────────
    // view[i] 与 messages[i] 一一对应直到 cachedViewSourceCount;
    // 压缩发生后 view 与 messages 不再对齐, cachedViewSourceCount = -1 且 view 独立演化
    private var cachedView: List<ChatHttpClient.ChatMessageDto> = emptyList()
    private var cachedViewSourceCount = 0
    /** 摘要前缀 — 压缩后作为 view 的第 0 条(替代被摘要的旧消息) */
    private var compactedPrefix: ChatHttpClient.ChatMessageDto? = null
    /** view 中仍保留图片的消息位置(messages 下标), 用于 evict 策略 */
    private val viewImageSources = mutableListOf<Int>()

    /** 追加消息 */
    suspend fun add(msg: ChatHttpClient.ChatMessageDto) = mutex.withLock {
        messages.add(msg)
        appendToViewLocked(msg, messages.size - 1)
    }

    /** 追加多条消息 */
    suspend fun addAll(msgs: List<ChatHttpClient.ChatMessageDto>) = mutex.withLock {
        msgs.forEach { m ->
            messages.add(m)
            appendToViewLocked(m, messages.size - 1)
        }
    }

    /** 完整消息快照(线程安全, 永不压缩 — 持久化用) */
    suspend fun snapshot(): List<ChatHttpClient.ChatMessageDto> = mutex.withLock {
        messages.toList()
    }

    /**
     * LLM 请求视图 — 发给 API 的消息.
     * append-only: 除压缩重置点外, 历史前缀字节不变(缓存对齐).
     */
    suspend fun llmMessages(): List<ChatHttpClient.ChatMessageDto> = mutex.withLock {
        buildViewLocked()
    }

    /** 就地替换所有完整消息 — 用于外部恢复/手动压缩(视图全量重建 = 缓存重置点) */
    suspend fun replaceAll(newMessages: List<ChatHttpClient.ChatMessageDto>) = mutex.withLock {
        messages.clear()
        messages.addAll(newMessages)
        cachedView = messages.toList()
        cachedViewSourceCount = messages.size
        compactedPrefix = null
        viewImageSources.clear()
    }

    /** 当前完整消息数量 */
    suspend fun size(): Int = mutex.withLock { messages.size }

    /** 估算 LLM 视图 token 数(阈值判断依据) */
    suspend fun estimatedTokens(): Int = mutex.withLock {
        estimateTokensOfLocked(buildViewLocked())
    }

    /**
     * 轮边界压缩 — runAgentLoop 每轮 LLM 调用前调用.
     *
     * 阈值策略(参考 Claude Code):
     * - < 80%: noop(什么都不做, 前缀稳定)
     * - >= 80% 软阈值: ①先 evict 老图片(体积大头) ②仍超则 LLM 摘要(compactor) → 缓存重置点
     * - >= 95% 硬阈值兜底: 无 compactor 或摘要失败时硬截断(保 system+最近 KEEP_RECENT 条)
     */
    suspend fun compactIfNeeded(): CompactionReport = mutex.withLock {
        val beforeTokens = estimateTokensOfLocked(buildViewLocked())
        if (beforeTokens < softThreshold()) return@withLock CompactionReport.noop(beforeTokens, tokenLimit)

        // L1: evict 旧图片(保留最近 K 条带图消息)
        var evicted = evictOldImagesLocked()
        var tokens = estimateTokensOfLocked(buildViewLocked())

        // L2: LLM 摘要压缩
        if (tokens >= softThreshold() && compactor != null) {
            val view = buildViewLocked()
            if (view.size > KEEP_RECENT + 1) {
                val summary = runCatching { compactor(view) }.getOrNull()
                if (!summary.isNullOrBlank()) {
                    val before = view.size
                    applyCompactionLocked(summary)
                    tokens = estimateTokensOfLocked(buildViewLocked())
                    return@withLock CompactionReport(
                        strategy = "summarize${if (evicted > 0) "+evict-images" else ""}",
                        beforeTokens = beforeTokens, afterTokens = tokens,
                        limitTokens = tokenLimit,
                        removedMessages = before - buildViewLocked().size,
                        keptMessages = buildViewLocked().size,
                        cacheReset = true, evictedImages = evicted, summary = summary
                    )
                }
            }
        }

        // L3: 硬截断兜底
        if (tokens >= hardThreshold()) {
            val before = buildViewLocked().size
            hardTruncateLocked()
            tokens = estimateTokensOfLocked(buildViewLocked())
            return@withLock CompactionReport(
                strategy = "hard-truncate${if (evicted > 0) "+evict-images" else ""}",
                beforeTokens = beforeTokens, afterTokens = tokens,
                limitTokens = tokenLimit,
                removedMessages = before - buildViewLocked().size,
                keptMessages = buildViewLocked().size,
                cacheReset = true, evictedImages = evicted, summary = null
            )
        }

        if (evicted > 0) {
            CompactionReport(
                strategy = "evict-images", beforeTokens = beforeTokens, afterTokens = tokens,
                limitTokens = tokenLimit,
                removedMessages = 0, keptMessages = buildViewLocked().size,
                cacheReset = true, evictedImages = evicted, summary = null
            )
        } else {
            CompactionReport.noop(beforeTokens, tokenLimit)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 内部 — 调用者必须持有 mutex
    // ══════════════════════════════════════════════════════════

    /** 新消息进入 view(append-only 路径, 无缓存重置) */
    private fun appendToViewLocked(msg: ChatHttpClient.ChatMessageDto, sourceIndex: Int) {
        if (compactedPrefix == null && cachedViewSourceCount == messages.size - 1) {
            // 正常 append 路径: view 与 messages 同步
            if (msg.images.isNotEmpty()) viewImageSources.add(sourceIndex)
            cachedView = cachedView + msg
            cachedViewSourceCount = messages.size
        } else {
            // 压缩后 view 与 messages 已分叉: 新消息继续 append 到 view 尾部
            if (msg.images.isNotEmpty()) viewImageSources.add(sourceIndex)
            cachedView = cachedView + msg
        }
    }

    private fun buildViewLocked(): List<ChatHttpClient.ChatMessageDto> = cachedView

    /** L1: evict 旧图片 — 只保留最近 K 条带图消息的图片 */
    private fun evictOldImagesLocked(): Int {
        val view = cachedView
        var evicted = 0
        // 找 view 中带图消息, 保留最后 K 条
        val imageIdx = view.mapIndexedNotNull { i, m -> if (m.images.isNotEmpty()) i else null }
        if (imageIdx.size <= MAX_RETAINED_IMAGE_MESSAGES) return 0
        val keepFrom = imageIdx[imageIdx.size - MAX_RETAINED_IMAGE_MESSAGES]
        val newView = view.mapIndexed { i, m ->
            if (m.images.isNotEmpty() && i < keepFrom) {
                evicted++
                m.copy(images = emptyList())
            } else m
        }
        cachedView = newView
        return evicted
    }

    /** L2: 应用摘要 — [原 system] + [摘要消息] + [最近 KEEP_RECENT 条原文] */
    private fun applyCompactionLocked(summary: String) {
        val view = cachedView
        val head = view.firstOrNull()?.takeIf { it.role == "system" }
        var tail = view.takeLast(KEEP_RECENT)
        if (head != null && tail.isNotEmpty() && tail[0] === head) tail = tail.drop(1)
        val summaryMsg = ChatHttpClient.ChatMessageDto(
            role = "system",
            content = "[对话历史摘要 — 早期消息已压缩, 关键结论如下]\n$summary"
        )
        compactedPrefix = summaryMsg
        cachedView = listOfNotNull(head, summaryMsg) + tail
    }

    /** L3: 硬截断 — 保 [system] + [最近 KEEP_RECENT 条] */
    private fun hardTruncateLocked() {
        val view = cachedView
        val head = view.firstOrNull()?.takeIf { it.role == "system" }
        var tail = view.takeLast(KEEP_RECENT)
        if (head != null && tail.isNotEmpty() && tail[0] === head) tail = tail.drop(1)
        cachedView = listOfNotNull(head) + tail
        compactedPrefix = null
    }

    private fun softThreshold(): Int = (tokenLimit * SOFT_THRESHOLD_PCT / 100)
    private fun hardThreshold(): Int = (tokenLimit * HARD_THRESHOLD_PCT / 100)

    private fun estimateTokensOfLocked(msgs: List<ChatHttpClient.ChatMessageDto>): Int {
        return msgs.sumOf { msg ->
            val content = msg.content.orEmpty()
            val toolCallsSize = msg.toolCalls.sumOf { it.arguments.length + it.name.length }
            val imageTokens = msg.images.size * IMAGE_TOKEN_COST
            estimateTokensForText(content) + estimateTokensForText(toolCallsSize.toString()) + imageTokens
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

    companion object {
        /** 软阈值(%) — LLM 摘要触发线(参考 Claude Code 95% 硬阈值, 移动端更保守) */
        const val SOFT_THRESHOLD_PCT = 80
        /** 硬阈值(%) — 截断兜底线 */
        const val HARD_THRESHOLD_PCT = 95
        /** 摘要/截断后保留的最近消息条数 */
        const val KEEP_RECENT = 8
        /** view 中最多保留图片的消息条数(超出 evict 最老的) */
        const val MAX_RETAINED_IMAGE_MESSAGES = 3
        /** 每张图片的粗估 token(base64→视觉token 简化常数) */
        const val IMAGE_TOKEN_COST = 1_000

        /** 工具输出截断 — 防止单个工具结果撑爆上下文(消息进入 view 前调用) */
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
