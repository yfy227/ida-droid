package dev.idadroid.agent

import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 配置导入导出 + API 连接测试 + 模型列表拉取。
 * 借鉴自 Operit 的 ModelConfigConnectionTester / ModelListFetcher / exportAllConfigs。
 */
class AiConfigTools(
    private val paths: EnvironmentPaths,
    private val configManager: PiConfigManager
) {
    private val agentDir get() = File(paths.rootfsDir, "root/pi_workspace/.idadroid/pi-agent")

    /** 导出完整 AI 配置为 JSON 字符串 */
    fun exportConfig(): String {
        val snapshot = configManager.readSnapshot()
        val export = ConfigExport(
            defaultProvider = snapshot.defaultProvider,
            defaultModel = snapshot.defaultModel,
            defaultThinkingLevel = snapshot.defaultThinkingLevel,
            enabledModels = snapshot.enabledModels,
            settingsText = snapshot.settingsText,
            modelsText = snapshot.modelsText,
            envText = snapshot.envText,
            appendSystem = snapshot.appendSystem,
            extraArgsText = snapshot.extraArgsText,
            version = 1,
            exportedAt = System.currentTimeMillis()
        )
        return JsonFormats.pretty.encodeToString(ConfigExport.serializer(), export)
    }

    /** 从 JSON 字符串导入配置，返回是否成功 */
    fun importConfig(json: String): Result<Unit> = runCatching {
        val imported = JsonFormats.pretty.decodeFromString(ConfigExport.serializer(), json)
        val snapshot = PiConfigSnapshot(
            defaultProvider = imported.defaultProvider,
            defaultModel = imported.defaultModel,
            defaultThinkingLevel = imported.defaultThinkingLevel.ifBlank { "medium" },
            enabledModels = imported.enabledModels,
            settingsText = imported.settingsText.ifBlank { "{}" },
            modelsText = imported.modelsText.ifBlank { "{}" },
            envText = imported.envText.ifBlank { "{}" },
            appendSystem = imported.appendSystem,
            extraArgsText = imported.extraArgsText,
            modelCatalog = parseAgentModelCatalog(imported.modelsText.ifBlank { "{}" })
        )
        configManager.saveSnapshot(snapshot)
    }

    /**
     * 测试 API 连接。
     * 返回 Result.success(TestResult) 或 Result.failure。
     */
    suspend fun testConnection(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        modelId: String? = null
    ): Result<TestResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank()) error("API Key 为空")
            if (baseUrl.isBlank()) error("Base URL 为空")

            val completedUrl = EndpointCompleter.complete(baseUrl, providerId)
            val url = URL(completedUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")

                // Provider 特定的认证头
                when (providerId) {
                    "anthropic" -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    "google" -> {
                        // Gemini uses API key as query parameter
                    }
                    else -> {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }
            }

            // 构造最小测试请求
            val testModel = modelId ?: defaultModelForProvider(providerId)
            val requestBody = when (providerId) {
                "anthropic" -> """{"model":"$testModel","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                "google" -> {
                    // Gemini uses different format
                    val geminiUrl = URL("${baseUrl.trimEnd('/')}/v1beta/models?key=$apiKey")
                    val gemConn = (geminiUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        requestMethod = "GET"
                    }
                    val code = gemConn.responseCode
                    val available = if (code in 200..299) {
                        gemConn.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        gemConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    gemConn.disconnect()
                    val models = parseModelList(available, "google")
                    return@runCatching TestResult(
                        success = code in 200..299,
                        httpCode = code,
                        message = if (code in 200..299) "连接成功" else "HTTP $code",
                        availableModels = models
                    )
                }
                else -> """{"model":"$testModel","max_tokens":1,"messages":[{"role":"user","content":"hi"}],"stream":false}"""
            }

            conn.outputStream.use { it.write(requestResponseToBytes(requestBody)) }

            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            conn.disconnect()

            val success = code in 200..299
            val message = if (success) {
                "连接成功"
            } else {
                extractErrorMessage(responseBody) ?: "HTTP $code"
            }

            TestResult(
                success = success,
                httpCode = code,
                message = message,
                availableModels = if (success) emptyList() else emptyList() // 不在测试请求中解析模型
            )
        }
    }

    /**
     * 从 API 拉取可用模型列表（仅支持 OpenAI 兼容的 /v1/models 端点）。
     */
    suspend fun fetchModelList(
        providerId: String,
        baseUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank()) error("API Key 为空")
            if (baseUrl.isBlank()) error("Base URL 为空")

            val modelsUrl = when {
                providerId in setOf("anthropic") -> {
                    // Anthropic doesn't have a public /v1/models endpoint in the same way
                    error("Anthropic 不支持模型列表拉取，请手动添加")
                }
                providerId == "google" -> {
                    "${baseUrl.trimEnd('/')}/v1beta/models?key=$apiKey"
                }
                else -> {
                    val base = baseUrl.trimEnd('/')
                    // 确保 URL 以 /v1 结尾
                    val v1Base = if (base.endsWith("/v1")) base else "$base/v1"
                    "$v1Base/models"
                }
            }

            val url = URL(modelsUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                if (providerId != "google") {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                error("HTTP $code: ${conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""}")
            }
            conn.disconnect()

            parseModelList(body, providerId)
        }
    }

    private fun parseModelList(jsonBody: String, providerId: String): List<String> {
        return try {
            val parsed = JsonFormats.pretty.parseToJsonElement(jsonBody).jsonObject
            when (providerId) {
                "google" -> {
                    // Gemini: { "models": [{ "name": "models/gemini-2.0-flash", ... }] }
                    parsed["models"]?.jsonArray?.mapNotNull { model ->
                        val name = model.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        name?.removePrefix("models/")
                    } ?: emptyList()
                }
                else -> {
                    // OpenAI compatible: { "data": [{ "id": "gpt-4o", ... }] }
                    parsed["data"]?.jsonArray?.mapNotNull { model ->
                        model.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    } ?: emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun defaultModelForProvider(providerId: String): String = when (providerId) {
        "openai" -> "gpt-4o-mini"
        "anthropic" -> "claude-3-5-haiku-20241022"
        "deepseek" -> "deepseek-chat"
        "google" -> "gemini-2.0-flash-lite"
        "mistral" -> "mistral-small-latest"
        "groq" -> "llama-3.1-8b-instant"
        else -> "test"
    }

    private fun extractErrorMessage(body: String): String? = try {
        val parsed = JsonFormats.pretty.parseToJsonElement(body).jsonObject
        parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: parsed["message"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) { null }

    private fun requestResponseToBytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
}

data class TestResult(
    val success: Boolean,
    val httpCode: Int,
    val message: String,
    val availableModels: List<String> = emptyList()
)
