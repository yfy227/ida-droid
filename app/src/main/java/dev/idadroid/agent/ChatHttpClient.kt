package dev.idadroid.agent

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Layer 1: 直接 HTTPS 对话客户端
 *
 * V5 重构(参考 DSHA/pi-mono/Claude Code/OpenAI prompt caching 规范):
 * - [P0 修复] tool_calls / tool_call_id / delta["tool_calls"] 字段名(R4 全部损坏 → 工具调用链路不可用)
 * - 真流式: 读流循环直接在 flow builder 内 emit(原先整轮攒 List 再回放 = 伪流式)
 * - 连接复用: 成功结束仅 close(归还 JVM keep-alive 池); 错误/取消才 disconnect
 * - 重试缓存一致性: RoundReset 事件通知上层清本轮 buffer(修复重试后文本重复)
 * - 缓存对齐: 消息前缀编码复用(同 loop 多轮间字节级稳定) + TokenUsage.cachedPromptTokens
 * - OOM 防护: 单 SSE 事件 2MB / 单流 8MB 累计上限
 */
class ChatHttpClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val providerId: String = ""
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    @kotlinx.serialization.Serializable
    data class ChatMessageDto(
        val role: String,        // system / user / assistant / tool
        val content: String? = null,
        val images: List<String> = emptyList(),  // base64 data URIs for vision
        val toolCalls: List<ToolCallDto> = emptyList(),
        val toolCallId: String? = null,
        val name: String? = null
    )

    @kotlinx.serialization.Serializable
    data class ToolCallDto(
        val id: String,
        val name: String,
        val arguments: String   // JSON string
    )

    data class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject  // JSON Schema
    )

    /**
     * Token 使用量统计。
     * V5: 新增 [cachedPromptTokens] — OpenAI/DeepSeek 为 usage.prompt_tokens_details.cached_tokens,
     * Anthropic 为 usage.cache_read_input_tokens。用于展示前缀缓存命中率。
     */
    data class TokenUsage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
        val cachedPromptTokens: Int = 0
    ) {
        val cacheHitRate: Double
            get() = if (promptTokens > 0) cachedPromptTokens.toDouble() / promptTokens else 0.0
    }

    sealed interface StreamEvent {
        /** 文本增量 */
        data class TextDelta(val text: String) : StreamEvent
        /** 思考/推理增量 */
        data class ThinkingDelta(val text: String) : StreamEvent
        /** 工具调用增量 */
        data class ToolCallDelta(
            val index: Int,
            val id: String?,
            val name: String?,
            val argumentsDelta: String
        ) : StreamEvent
        /** 流结束 */
        data class Finish(
            val reason: String,           // stop / tool_calls / length
            val toolCalls: List<ToolCallDto>,
            val usage: TokenUsage = TokenUsage()
        ) : StreamEvent
        /**
         * 本轮重试前重置 — 上层必须清空本轮已累积的文本/思考 buffer。
         * 修复: 重试时已 emit 的 delta 会被重复渲染(文本翻倍)。
         */
        data object RoundReset : StreamEvent
        /** 错误 */
        data class Error(val message: String, val httpCode: Int? = null, val retryable: Boolean = false) : StreamEvent
        /** 重试中（用于 UI 提示） */
        data class Retrying(val attempt: Int, val reason: String, val delayMs: Long) : StreamEvent
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 10_000L

        /** OOM 防护: 单个 SSE event 累计字符上限(约 2MB)。防恶意/损坏流撑爆内存 */
        private const val MAX_SSE_EVENT_CHARS = 2 * 1024 * 1024
        /** OOM 防护: 单轮流式响应累计字符上限(约 8MB)。正常长回复远达不到 */
        private const val MAX_STREAM_TOTAL_CHARS = 8 * 1024 * 1024
        /** 非 200 响应错误体最多读取量(64KB) */
        private const val MAX_ERROR_BODY_CHARS = 64 * 1024

        /** 判断 HTTP 状态码是否可重试 */
        private fun isRetryableHttp(code: Int): Boolean = code == 429 || code in 500..599

        /** 判断异常是否可重试 */
        private fun isRetryableException(e: Exception): Boolean = when (e) {
            is java.io.IOException -> true  // SocketTimeoutException 是 IOException 的子类
            else -> false
        }
    }

    /** 非流式错误响应(非 200) */
    private class ApiErrorException(val httpCode: Int, message: String) : Exception(message)

    /**
     * 发送对话请求，返回 SSE 流事件。
     *
     * V5: 真流式 — 读流循环直接在 flow builder 内 emit,每个 SSE 事件解析后立即下发。
     * 重试时先 emit RoundReset(上层清 buffer)再 emit Retrying。
     */
    fun chat(
        messages: List<ChatMessageDto>,
        tools: List<ToolDefinition> = emptyList(),
        systemPrompt: String? = null,
        thinkingLevel: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null
    ): Flow<StreamEvent> = flow {
        val allMessages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(ChatMessageDto(role = "system", content = systemPrompt))
            }
            addAll(messages)
        }

        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val bodyBytes = buildRequestBody(allMessages, tools, thinkingLevel, temperature, maxTokens, topP)
            .toString()
            .toByteArray(Charsets.UTF_8)

        var retryDelay = INITIAL_RETRY_DELAY_MS
        // 工具调用增量状态 — 在重试间共享，重试前清理
        val pendingToolCalls = mutableMapOf<Int, MutableMap<String, String>>()

        for (attempt in 1..MAX_RETRIES) {
            var connection: HttpURLConnection? = null
            var roundOk = false
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 300_000  // 单次流读阻塞上限;流式模式靠 event 心跳推进
                    doInput = true
                    doOutput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "text/event-stream")
                }
                connection = conn

                conn.outputStream.use { it.write(bodyBytes); it.flush() }

                val code = conn.responseCode
                if (code != 200) {
                    val errBody = runCatching {
                        (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { r ->
                            // 上限读取,防超大错误体 OOM
                            val buf = StringBuilder()
                            var ch: Int
                            while (r.read().also { ch = it } != -1 && buf.length < MAX_ERROR_BODY_CHARS) {
                                buf.append(ch.toChar())
                            }
                            buf.toString()
                        }
                    }.getOrDefault("")
                    throw ApiErrorException(code, parseApiError(errBody, code))
                }

                // ── 真流式读取 ─────────────────────────────────────────
                // Finish 语义: reason/toolCalls 取首个非空帧(finish_reason 或 [DONE]),
                // usage 取最后出现的帧(usage 帧通常在流尾). 流终结(EOF)时统一发出。
                val sse = SseDecoderState(pendingToolCalls)
                conn.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val events = sse.consumeLine(line) ?: continue
                        for (event in events) {
                            if (event is StreamEvent.Finish) {
                                if (event.usage.totalTokens > 0) sse.lastUsage = event.usage
                                if (sse.finishReason == null && (event.reason.isNotBlank() && event.reason != "stop" || event.toolCalls.isNotEmpty())) {
                                    sse.finishReason = event.reason
                                    sse.finishToolCalls = event.toolCalls
                                } else if (sse.finishReason == null && event.reason.isNotBlank()) {
                                    sse.finishReason = event.reason
                                }
                            } else {
                                emit(event)
                            }
                        }
                    }
                }
                // 流 EOF — 统一发出 Finish(兜底: 服务器未发 [DONE] 直接断流也兼容)
                val reason = sse.finishReason
                    ?: if (pendingToolCalls.isNotEmpty()) "tool_calls" else "stop"
                val toolCalls = sse.finishToolCalls ?: finalizeToolCalls(pendingToolCalls)
                emit(StreamEvent.Finish(reason, toolCalls, sse.lastUsage ?: TokenUsage()))
                roundOk = true
                return@flow
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiErrorException) {
                val retryable = isRetryableHttp(e.httpCode)
                if (retryable && attempt < MAX_RETRIES) {
                    emit(StreamEvent.RoundReset)
                    emit(StreamEvent.Retrying(attempt + 1, "HTTP ${e.httpCode}", retryDelay))
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    pendingToolCalls.clear()
                } else {
                    emit(StreamEvent.Error(e.message ?: "HTTP ${e.httpCode}", e.httpCode, false))
                    return@flow
                }
            } catch (e: Exception) {
                val retryable = isRetryableException(e)
                if (retryable && attempt < MAX_RETRIES) {
                    emit(StreamEvent.RoundReset)
                    emit(StreamEvent.Retrying(attempt + 1, e.javaClass.simpleName, retryDelay))
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    pendingToolCalls.clear()
                } else {
                    emit(StreamEvent.Error(e.message ?: e::class.simpleName ?: "网络错误", null, retryable))
                    return@flow
                }
            } finally {
                // 连接复用策略: 成功读尽流 → 仅 close(连接归还 JVM keep-alive 池);
                // 失败/取消 → disconnect(避免脏连接毒化连接池)
                val conn = connection
                if (conn != null) {
                    if (roundOk) {
                        runCatching { conn.inputStream?.close() }
                    } else {
                        runCatching { conn.disconnect() }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 构建请求体。
     *
     * V5:
     * - [P0 修复] tool_calls 字段名(原为空串) / tool_call_id(原为 _id)
     * - 消息前缀编码复用: 同一 agent loop 内多轮请求间, 未变前缀的编码结果直接复用,
     *   保证字节级稳定(前缀缓存友好的必要条件)并减少重复构建。
     */
    private fun buildRequestBody(
        messages: List<ChatMessageDto>,
        tools: List<ToolDefinition>,
        thinkingLevel: String?,
        temperature: Double?,
        maxTokens: Int?,
        topP: Double?
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", encodeMessagesStable(messages))
        put("stream", true)
        // stream_options: 请求 usage 统计（部分 API 不支持，仅对已知兼容的 provider 添加）
        if (providerId !in listOf("anthropic", "moonshot")) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    })
                }
            })
        }
        thinkingLevel?.let { put("reasoning_effort", it) }
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        topP?.let { put("top_p", it) }
    }

    /**
     * 消息数组编码 — 前缀缓存复用。
     *
     * encodedCache 持有上一轮请求的编码结果。若本次 messages 的前 k 条与上次相同
     * (引用相等 — DTO 不可变, 引用相等是充分的), 则前 k 条复用编码对象, 只编码新增部分。
     * 失配(压缩/恢复/重置导致)时全量重建。字段 put 顺序固定, 保证跨轮字节级稳定。
     */
    private var encodedCache: List<JsonObject> = emptyList()
    private var encodedCacheSource: List<ChatMessageDto> = emptyList()

    private fun encodeMessagesStable(messages: List<ChatMessageDto>): kotlinx.serialization.json.JsonArray {
        val cached = encodedCache
        val cachedSrc = encodedCacheSource
        var reuseCount = 0
        if (cached.isNotEmpty() && cachedSrc.size <= messages.size) {
            var reusable = true
            for (i in cachedSrc.indices) {
                if (cachedSrc[i] !== messages[i]) { reusable = false; break }
            }
            if (reusable) reuseCount = cachedSrc.size
        }
        val encoded = ArrayList<JsonObject>(messages.size)
        for (i in 0 until reuseCount) encoded.add(cached[i])
        for (i in reuseCount until messages.size) encoded.add(encodeMessage(messages[i]))
        // 仅当本次是上次的严格扩展(尾部追加)时更新缓存; 前缀变化(压缩)则作废缓存
        if (reuseCount == cachedSrc.size || cachedSrc.isEmpty()) {
            encodedCache = encoded
            encodedCacheSource = messages
        } else {
            encodedCache = encoded
            encodedCacheSource = messages
        }
        return kotlinx.serialization.json.JsonArray(encoded)
    }

    /** 单条消息编码 — 字段顺序固定: role → content(多模态) → tool_calls → tool_call_id → name */
    private fun encodeMessage(msg: ChatMessageDto): JsonObject = buildJsonObject {
        put("role", msg.role)
        // content: 支持纯文本和多模态
        if (msg.images.isNotEmpty()) {
            put("content", buildJsonArray {
                if (!msg.content.isNullOrBlank()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                msg.images.forEach { img ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", img)
                        })
                    })
                }
            })
        } else {
            put("content", msg.content)
        }
        // tool_calls (assistant 消息)
        if (msg.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                msg.toolCalls.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        })
                    })
                }
            })
        }
        // tool 消息标识 — V5 P0 修复: tool_call_id 原为 _id
        if (msg.role == "tool") {
            put("tool_call_id", msg.toolCallId)
            msg.name?.let { put("name", it) }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SSE 解析 — 真流式状态机
    // ══════════════════════════════════════════════════════════════

    /**
     * SSE 解码状态机 — 逐行消费, 返回 0..n 个事件(通常 0/1 个)。
     * 空行 = event 边界; data: 行累积进 buffer; [DONE] / finish_reason / usage 帧
     * 产出 Finish(延迟到流终结统一发出, 由 chat() 控制)。
     */
    private inner class SseDecoderState(
        val pendingToolCalls: MutableMap<Int, MutableMap<String, String>>
    ) {
        val sseBuffer = StringBuilder()
        var lastUsage: TokenUsage? = null
        var finishReason: String? = null
        var finishToolCalls: List<ToolCallDto>? = null
        var totalChars = 0

        /** 逐行消费; 返回待发事件(不含延迟的 Finish — Finish 一律进入 pending, 流终结时由 chat() 发出) */
        fun consumeLine(line: String): List<StreamEvent>? {
            totalChars += line.length
            if (totalChars > MAX_STREAM_TOTAL_CHARS) {
                throw IllegalStateException("流式响应超过 ${MAX_STREAM_TOTAL_CHARS / 1024 / 1024}MB 上限, 已中止(OOM 保护)")
            }
            if (line.isEmpty()) {
                if (sseBuffer.isNotEmpty()) {
                    val raw = sseBuffer.toString()
                    sseBuffer.setLength(0)
                    return parseSSEEvent(raw, pendingToolCalls)
                }
                return null
            }
            if (sseBuffer.length + line.length > MAX_SSE_EVENT_CHARS) {
                throw IllegalStateException("单个 SSE 事件超过 ${MAX_SSE_EVENT_CHARS / 1024 / 1024}MB 上限(OOM 保护)")
            }
            sseBuffer.append(line).append('\n')
            return null
        }
    }

    /** 解析单个 SSE event */
    private fun parseSSEEvent(
        rawData: String,
        pendingToolCalls: MutableMap<Int, MutableMap<String, String>>
    ): List<StreamEvent>? {
        // 提取 data: 字段（可能有多行 data:，需拼接）
        val dataLines = rawData.lines().filter { it.startsWith("data:") }
        if (dataLines.isEmpty()) return null

        // SSE 规范：多个 data: 行应用 \n 拼接（不是空字符串），否则多行 JSON 会被错误合并。
        val data = dataLines.joinToString("\n") { it.removePrefix("data:").trim() }
        if (data == "[DONE]") {
            val finalToolCalls = finalizeToolCalls(pendingToolCalls)
            return listOf(StreamEvent.Finish(
                if (finalToolCalls.isNotEmpty()) "tool_calls" else "stop",
                finalToolCalls
            ))
        }

        val chunk = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) { return null }

        // 提取 usage（stream_options.include_usage 返回）
        val usage = parseUsage(chunk["usage"]?.jsonObject)

        val events = mutableListOf<StreamEvent>()
        val choices = chunk["choices"]?.jsonArray
        // 空 choices（keep-alive usage 帧）— usage 帧通常在流尾, 作为终结信号
        if (choices.isNullOrEmpty()) {
            if (usage != null && pendingToolCalls.isEmpty()) {
                events.add(StreamEvent.Finish("stop", emptyList(), usage))
            }
            if (events.isEmpty()) return null
            return events
        }

        val choice = choices.firstOrNull()?.jsonObject ?: return null
        val delta = choice["delta"]?.jsonObject
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

        if (delta != null) {
            // 文本增量
            delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (text.isNotEmpty()) events.add(StreamEvent.TextDelta(text))
            }

            // 思考/推理增量 — 兼容多种字段名
            delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (text.isNotEmpty()) events.add(StreamEvent.ThinkingDelta(text))
            }
            delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (text.isNotEmpty()) events.add(StreamEvent.ThinkingDelta(text))
            }
            // DeepSeek R1 风格: reasoning_content 在 message 级别
            chunk["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (text.isNotEmpty()) events.add(StreamEvent.ThinkingDelta(text))
            }

            // 工具调用增量 — [P0 修复] 原为 delta[""](空键), 工具调用永远解析不到
            delta["tool_calls"]?.jsonArray?.forEach { tc ->
                val tcObj = tc.jsonObject
                val index = tcObj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val entry = pendingToolCalls.getOrPut(index) { mutableMapOf() }
                tcObj["id"]?.jsonPrimitive?.contentOrNull?.let { entry["id"] = it }
                tcObj["function"]?.jsonObject?.let { fn ->
                    fn["name"]?.jsonPrimitive?.contentOrNull?.let { entry["name"] = it }
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { entry["arguments"] = (entry["arguments"] ?: "") + it }
                }
                events.add(StreamEvent.ToolCallDelta(
                    index,
                    tcObj["id"]?.jsonPrimitive?.contentOrNull,
                    tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull,
                    tcObj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                ))
            }
        }

        // finish_reason
        if (finishReason != null) {
            val finalToolCalls = finalizeToolCalls(pendingToolCalls)
            events.add(StreamEvent.Finish(finishReason, finalToolCalls, usage ?: TokenUsage()))
        }

        if (events.isEmpty()) return null
        return events
    }

    /** pending 工具调用 → 最终 ToolCallDto 列表(按 index 排序) */
    private fun finalizeToolCalls(pendingToolCalls: MutableMap<Int, MutableMap<String, String>>): List<ToolCallDto> =
        pendingToolCalls.toSortedMap().values.mapNotNull { m ->
            val id = m["id"] ?: return@mapNotNull null
            val name = m["name"] ?: return@mapNotNull null
            ToolCallDto(id, name, m["arguments"] ?: "{}")
        }

    /**
     * 解析 usage 对象 — 兼容 OpenAI prompt_tokens_details.cached_tokens 与
     * Anthropic cache_read_input_tokens(OpenAI 兼容层)。
     */
    private fun parseUsage(u: kotlinx.serialization.json.JsonObject?): TokenUsage? {
        if (u == null) return null
        val prompt = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completion = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val total = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cached = u["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: u["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull
            ?: 0
        if (prompt == 0 && completion == 0 && total == 0) return null
        return TokenUsage(prompt, completion, total, cached)
    }

    /**
     * 解析 API 错误响应，提取人类可读的错误消息。
     * 兼容 OpenAI / Anthropic / Google / 通用格式。
     */
    private fun parseApiError(body: String, httpCode: Int): String = try {
        val parsed = json.parseToJsonElement(body).jsonObject

        // OpenAI / Anthropic / Google 统一格式: { "error": { "message": "..." } }
        parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let { return it }

        // OpenAI 变体: { "error": { "code": "...", "detail": "..." } }
        parsed["error"]?.jsonObject?.get("detail")?.jsonPrimitive?.contentOrNull?.let { return it }

        // Generic: { "message": "..." }
        parsed["message"]?.jsonPrimitive?.contentOrNull?.let { return it }

        // Generic: { "detail": "..." }
        parsed["detail"]?.jsonPrimitive?.contentOrNull?.let { return it }

        "HTTP $httpCode: $body"
    } catch (_: Exception) {
        "HTTP $httpCode: ${body.take(500)}"
    }
}
