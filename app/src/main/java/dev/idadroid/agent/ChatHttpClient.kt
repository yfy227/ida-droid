package dev.idadroid.agent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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

/**
 * Layer 1: 直接 HTTPS 对话客户端
 *
 * 直接调用 OpenAI 兼容的 /v1/chat/completions 接口，支持：
 * - SSE 流式输出
 * - Function calling (tools)
 * - 思考/推理 (thinking/reasoning)
 *
 * 不依赖 pi agent，不依赖 proot 管道，不依赖环境变量传递。
 * 直接用已配好的 baseUrl + apiKey + model。
 */
class ChatHttpClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    data class ChatMessageDto(
        val role: String,        // system / user / assistant / tool
        val content: String? = null,
        val images: List<String> = emptyList(),  // base64 data URIs for vision
        val toolCalls: List<ToolCallDto> = emptyList(),
        val toolCallId: String? = null,
        val name: String? = null
    )

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
            val toolCalls: List<ToolCallDto>
        ) : StreamEvent
        /** 错误 */
        data class Error(val message: String, val httpCode: Int? = null) : StreamEvent
    }

    /**
     * 发送对话请求，返回 SSE 流事件。
     *
     * @param messages 对话历史
     * @param tools 可用工具定义
     * @param systemPrompt 系统提示词（会作为第一条 system 消息插入）
     * @param thinkingLevel 思考级别 (low/medium/high，部分模型支持)
     * @param signalAbort 用于外部取消的 lambda（返回 true 时中断）
     */
    fun chat(
        messages: List<ChatMessageDto>,
        tools: List<ToolDefinition> = emptyList(),
        systemPrompt: String? = null,
        thinkingLevel: String? = null,
        temperature: Double? = null
    ): Flow<StreamEvent> = flow {
        val allMessages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(ChatMessageDto(role = "system", content = systemPrompt))
            }
            addAll(messages)
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                allMessages.forEach { msg ->
                    add(buildJsonObject {
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
                        // tool 角色消息
                        if (msg.role == "tool") {
                            put("tool_call_id", msg.toolCallId)
                            msg.name?.let { put("name", it) }
                        }
                    })
                }
            })
            put("stream", true)
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
            thinkingLevel?.takeIf { it.isNotBlank() }?.let {
                // 部分 OpenAI 兼容 API 通过额外字段支持思考
                put("reasoning_effort", it)
            }
            temperature?.let { put("temperature", it) }
        }

        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 300_000  // 5 分钟读超时（流式模式不会真的等这么久）
            doInput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "text/event-stream")
            outputStream.write(json.encodeToString(JsonObject.serializer(), requestBody).toByteArray())
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
            emit(StreamEvent.Error(errorBody, responseCode))
            connection.disconnect()
            return@flow
        }

        // SSE 流式读取
        val pendingToolCalls = mutableMapOf<Int, MutableMap<String, String>>()

        try {
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    kotlinx.coroutines.coroutineScope { ensureActive() }
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        val finalToolCalls = pendingToolCalls.toSortedMap().values.mapNotNull { m ->
                            val id = m["id"] ?: return@mapNotNull null
                            val name = m["name"] ?: return@mapNotNull null
                            ToolCallDto(id, name, m["arguments"] ?: "{}")
                        }
                        emit(StreamEvent.Finish(if (finalToolCalls.isNotEmpty()) "tool_calls" else "stop", finalToolCalls))
                        return@flow
                    }

                    val chunk = try {
                        json.parseToJsonElement(data).jsonObject
                    } catch (e: Exception) { continue }

                    val choices = chunk["choices"]?.jsonArray ?: continue
                    val choice = choices.firstOrNull()?.jsonObject ?: continue
                    val delta = choice["delta"]?.jsonObject ?: continue
                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

                    // 文本增量
                    delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        if (text.isNotEmpty()) emit(StreamEvent.TextDelta(text))
                    }

                    // 思考/推理增量
                    delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        if (text.isNotEmpty()) emit(StreamEvent.ThinkingDelta(text))
                    }
                    delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        if (text.isNotEmpty()) emit(StreamEvent.ThinkingDelta(text))
                    }

                    // 工具调用增量
                    delta["tool_calls"]?.jsonArray?.forEach { tc ->
                        val tcObj = tc.jsonObject
                        val index = tcObj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val entry = pendingToolCalls.getOrPut(index) { mutableMapOf() }
                        tcObj["id"]?.jsonPrimitive?.contentOrNull?.let { entry["id"] = it }
                        tcObj["function"]?.jsonObject?.let { fn ->
                            fn["name"]?.jsonPrimitive?.contentOrNull?.let { entry["name"] = it }
                            fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { entry["arguments"] = (entry["arguments"] ?: "") + it }
                        }
                    }

                    // finish_reason
                    if (finishReason != null) {
                        val finalToolCalls = pendingToolCalls.toSortedMap().values.mapNotNull { m ->
                            val id = m["id"] ?: return@mapNotNull null
                            val name = m["name"] ?: return@mapNotNull null
                            ToolCallDto(id, name, m["arguments"] ?: "{}")
                        }
                        emit(StreamEvent.Finish(finishReason, finalToolCalls))
                        return@flow
                    }
                }
                // 流自然结束但没收到 [DONE] 或 finish_reason
                val finalToolCalls = pendingToolCalls.toSortedMap().values.mapNotNull { m ->
                    val id = m["id"] ?: return@mapNotNull null
                    val name = m["name"] ?: return@mapNotNull null
                    ToolCallDto(id, name, m["arguments"] ?: "{}")
                }
                emit(StreamEvent.Finish(if (finalToolCalls.isNotEmpty()) "tool_calls" else "stop", finalToolCalls))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "网络错误"))
        } finally {
            connection.disconnect()
        }
    }
}
