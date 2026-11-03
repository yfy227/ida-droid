package dev.idadroid.v7

import dev.idadroid.agent.EndpointCompleter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * V7 L2 实现: OpenAI 兼容协议的无状态 Runner(单轮)。
 *
 * 自包含(不复用 V6 ChatHttpClient——零补丁要求: 传输层统一在这一个文件里重写):
 *  - 端点补全(裸域→/v1, # 禁用)  - 协议感知 headers(Bearer / x-api-key)
 *  - SSE 流式解析(data: 拼接 / [DONE] / usage 帧)
 *  - 网络重试(429/5xx, 最多 2 次, 指数退避)
 *
 * 事件语义(解 C3 四职责耦合): 只发事实, 不做状态决策。
 */
class OpenAiCompatRunner : StatelessAgentRunner {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun runOnce(request: RunRequest): Flow<EngineEvent> = flow {
        val cfg = request.config
        val url = resolveEndpoint(cfg)
        val body = buildRequestBody(request)

        var attempt = 0
        var retryDelay = 800L
        while (true) {
            try {
                val conn = openConnection(url, cfg, body)
                val code = conn.responseCode
                if (code != 200) {
                    val errText = readLimited(conn.errorStream ?: conn.inputStream, 8_192)
                    conn.disconnect()
                    val retryable = code == 429 || code in 500..599
                    if (retryable && attempt < 2) {
                        emit(EngineEvent.Failed(code, describeHttp(code, errText), true)) // 先告知(可展示)
                        attempt++; delay(retryDelay); retryDelay *= 2
                        continue
                    }
                    emit(EngineEvent.Failed(code, describeHttp(code, errText), false))
                    return@flow
                }

                // ── SSE 流解析 ──
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val usageAcc = intArrayOf(0, 0, 0, 0)
                val toolCallsAcc = mutableListOf<ApiToolCall>()
                val finishReasons = mutableListOf<String>()
                val contentSb = StringBuilder()
                val reasoningSb = StringBuilder()
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val delta = parseChunk(payload) ?: continue
                        // content 增量
                        delta.contentDelta?.let { contentSb.append(it); emit(EngineEvent.Delta(it)) }
                        delta.reasoningDelta?.let { reasoningSb.append(it); emit(EngineEvent.Thinking(it)) }
                        // 工具调用流式累积
                        delta.toolCallDelta?.forEach { tc ->
                            val idx = tc.index
                            while (toolCallsAcc.size <= idx) toolCallsAcc.add(ApiToolCall("", "", ""))
                            val old = toolCallsAcc[idx]
                            toolCallsAcc[idx] = ApiToolCall(
                                callId = old.callId + (tc.id ?: ""),
                                toolName = old.toolName + (tc.name ?: ""),
                                argsJson = old.argsJson + (tc.args ?: "")
                            )
                        }
                        delta.finishReason?.let { finishReasons.add(it) }
                        delta.usage?.let { u ->
                            usageAcc[0] = u[0]; usageAcc[1] = u[1]; usageAcc[2] = u[2]; usageAcc[3] = u[3]
                        }
                    }
                } finally {
                    runCatching { reader.close() }
                    conn.disconnect()
                }

                if (usageAcc[3] > 0) emit(EngineEvent.Usage(usageAcc[0], usageAcc[1], usageAcc[2], usageAcc[3]))
                val pending = toolCallsAcc.filter { it.toolName.isNotBlank() || it.argsJson.isNotBlank() }
                emit(EngineEvent.RoundFinished(
                    finishReason = finishReasons.firstOrNull { it.isNotBlank() && it != "stop" } ?: finishReasons.firstOrNull(),
                    assistantText = contentSb.toString(),
                    pendingToolCalls = pending.map { PendingToolCall(it.callId, it.toolName, it.argsJson) }
                ))
                return@flow
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt < 2) {
                    attempt++; delay(retryDelay); retryDelay *= 2; continue
                }
                emit(EngineEvent.Failed(null, "网络错误: ${e.message?.take(160)}", false))
                return@flow
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── 端点/请求构造 ──

    private fun resolveEndpoint(cfg: AgentEndpointConfig): String {
        val base = cfg.baseUrl.trim().removeSuffix("/")
        if (base.endsWith("#")) return base.removeSuffix("#")   // 显式禁用补全
        return when (cfg.protocol) {
            "anthropic" -> EndpointCompleter.complete(base, "anthropic")
            "google" -> EndpointCompleter.complete(base, "google")
            "openai-responses" -> EndpointCompleter.complete(base, "openai")
                .removeSuffix("/chat/completions") + "/responses"
            else -> EndpointCompleter.complete(base, "openai")
        }
    }

    private fun buildRequestBody(req: RunRequest): ByteArray {
        val messages = buildJsonArray {
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(buildJsonObject { put("role", "system"); put("content", it) })
            }
            req.messages.forEach { add(encodeMessage(it)) }
            add(encodeMessage(ApiMessage(role = "user", text = req.userText, images = req.images)))
        }
        val body = buildJsonObject {
            put("model", req.config.model)
            put("messages", messages)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            if (req.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    req.tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", json.parseToJsonElement(t.parametersJson))
                            })
                        })
                    }
                })
            }
            req.config.maxTokens?.let { put("max_tokens", it) }
            req.config.temperature?.let { put("temperature", it) }
            req.config.topP?.let { put("top_p", it) }
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeMessage(m: ApiMessage): JsonObject = buildJsonObject {
        put("role", m.role)
        if (m.images.isNotEmpty()) {
            put("content", buildJsonArray {
                if (m.text.isNotBlank()) add(buildJsonObject {
                    put("type", "text"); put("text", m.text)
                })
                m.images.forEach { img ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", img) })
                    })
                }
            })
        } else if (m.role == "tool") {
            put("content", m.toolResult ?: "")
            put("tool_call_id", m.toolCallId ?: "")
            m.toolName?.let { put("name", it) }
        } else {
            put("content", m.text)
        }
        if (m.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                m.toolCalls.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.callId)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tc.toolName)
                            put("arguments", tc.argsJson)
                        })
                    })
                }
            })
        }
    }

    private fun openConnection(url: String, cfg: AgentEndpointConfig, body: ByteArray): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 300_000        // 长思考模型单轮可久
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        if (cfg.protocol == "anthropic") {
            conn.setRequestProperty("x-api-key", cfg.apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        }
        conn.outputStream.use { it.write(body) }
        return conn
    }

    // ── SSE chunk 解析(轻量, 只提事实) ──
    private data class Chunk(
        val contentDelta: String?,
        val reasoningDelta: String?,
        val toolCallDelta: List<ChunkTool>?,
        val finishReason: String?,
        val usage: IntArray?
    )

    private fun parseChunk(payload: String): Chunk? = runCatching {
        val obj = json.parseToJsonElement(payload).jsonObject
        val choices = obj["choices"] as? JsonArray ?: return@runCatching null
        val choice = choices.firstOrNull() as? JsonObject ?: return@runCatching null
        val delta = choice["delta"] as? JsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
        val reasoning = (delta?.get("reasoning_content") ?: delta?.get("reasoning"))?.jsonPrimitive?.contentOrNull
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val toolDeltas = (delta?.get("tool_calls") as? JsonArray)?.mapNotNull { el ->
            val tc = el as? JsonObject ?: return@mapNotNull null
            val fn = tc["function"] as? JsonObject
            ChunkTool(
                (tc["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0),
                tc["id"]?.jsonPrimitive?.contentOrNull,
                fn?.get("name")?.jsonPrimitive?.contentOrNull,
                fn?.get("arguments")?.jsonPrimitive?.contentOrNull
            )
        }
        val usage = (obj["usage"] as? JsonObject)?.let { u ->
            intArrayOf(
                u["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                u["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                u["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                u["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            )
        }
        Chunk(content, reasoning, toolDeltas?.takeIf { it.isNotEmpty() }, finish, usage)
    }.getOrNull()

    private data class ChunkTool(val index: Int, val id: String?, val name: String?, val args: String?)

    private fun readLimited(stream: java.io.InputStream?, maxChars: Int): String {
        if (stream == null) return ""
        return runCatching {
            stream.bufferedReader().use { r ->
                val buf = StringBuilder()
                var ch: Int
                while (r.read().also { ch = it } != -1 && buf.length < maxChars) buf.append(ch.toChar())
                buf.toString()
            }
        }.getOrDefault("")
    }

    private fun describeHttp(code: Int, body: String): String {
        val hint = when (code) {
            401 -> "API Key 无效或未授权"
            403 -> "无权限(Key 权限不足/地区限制)"
            404 -> "端点不存在 — 检查 Base URL 与协议选择"
            429 -> "限流, 稍后重试"
            in 500..599 -> "站点服务端故障"
            else -> ""
        }
        return "HTTP $code${if (hint.isNotBlank()) " · $hint" else ""} · ${body.take(160)}"
    }
}
