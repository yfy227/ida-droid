package dev.idadroid.mcp

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lightweight JSON-RPC style client for the in-container `ida-mcp` HTTP server.
 *
 * The ida-mcp server (started by [IdaMcpSessionManager]) exposes MCP tools over
 * Streamable HTTP. This client wraps the minimal subset we need from the host
 * side: invoking the `open_file` tool so that an externally-triggered file
 * transfer can be immediately opened in IDA Pro without the user having to
 * switch to the agent terminal and type `mcpc call open_file ...` manually.
 *
 * The client is intentionally tiny — it speaks the MCP "tools/call" request
 * shape over a single POST and parses the text result. It does NOT implement
 * the full MCP protocol (streaming, notifications, etc.) because the host only
 * needs fire-and-forget tool invocations.
 */
class IdaMcpClient(
    private val endpoint: String
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /**
     * Calls the `open_file` tool on the ida-mcp server, asking IDA to open the
     * file at [prootPath] (a path visible inside the proot container, e.g.
     * `/root/.mcp-transfer/foo.bin`).
     *
     * Returns the tool's text result on success, or throws on HTTP/protocol
     * failure. The caller is expected to have already transferred the file into
     * the container before invoking this.
     */
    suspend fun openFile(prootPath: String): String = withContext(Dispatchers.IO) {
        callTool("open_file", mapOf("path" to JsonPrimitive(prootPath)))
    }

    /**
     * Generic MCP `tools/call` invocation. Exposed so future host-side
     * integrations can trigger other ida-mcp tools (e.g. `get_metadata`,
     * `decompile_function`) without reaching for the agent.
     *
     * @param toolName the MCP tool name, e.g. `"open_file"`
     * @param args the tool arguments as a JSON object map
     * @return the tool's text result (content[0].text), or the raw response
     *         body if the shape is unexpected
     */
    suspend fun callTool(toolName: String, args: Map<String, JsonPrimitive>): String = withContext(Dispatchers.IO) {
        val url = endpoint.removeSuffix("/") + "/mcp"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
        }
        try {
            val payload = buildToolCallPayload(toolName, args)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            if (code !in 200..299) {
                throw IllegalStateException("ida-mcp tools/call '$toolName' failed: HTTP $code ${body.take(300)}")
            }
            extractToolResult(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Builds the MCP `tools/call` JSON-RPC request body.
     *
     * MCP over Streamable HTTP uses JSON-RPC 2.0 envelopes. A tools/call looks
     * like: `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":...,"arguments":{...}}}`
     */
    private fun buildToolCallPayload(toolName: String, args: Map<String, JsonPrimitive>): String {
        val arguments = JsonObject(args)
        val params = JsonObject(mapOf(
            "name" to JsonPrimitive(toolName),
            "arguments" to arguments
        ))
        val request = JsonObject(mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to JsonPrimitive(1),
            "method" to JsonPrimitive("tools/call"),
            "params" to params
        ))
        return json.encodeToString(JsonObject.serializer(), request)
    }

    /**
     * Extracts the text result from a tools/call response.
     *
     * ida-mcp's Streamable HTTP endpoint may respond with either:
     *  - `application/json`: a single JSON-RPC envelope, or
     *  - `text/event-stream`: SSE frames like `data: {...}\n\n`.
     *
     * We normalise both to a single JSON object before extracting
     * `result.content[0].text`. If the shape is unexpected we return the raw
     * body so the caller at least sees something useful.
     */
    private fun extractToolResult(body: String): String {
        val jsonText = extractJsonFromSse(body)
        val parsed = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return jsonText
        val result = parsed["result"]?.jsonObject
            ?: run {
                // Error response: {"jsonrpc":"2.0","id":1,"error":{"code":...,"message":"..."}}
                val error = parsed["error"]?.jsonObject
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                throw IllegalStateException(message ?: "ida-mcp returned no result: ${jsonText.take(300)}")
            }
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        val content = result["content"]
        val text = content
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: jsonText
        if (isError) {
            throw IllegalStateException("ida-mcp tool error: $text")
        }
        return text
    }

    /**
     * If [body] is an SSE stream, extract the last `data:` frame's JSON payload.
     * Otherwise return [body] unchanged. We take the last frame because MCP
     * servers may emit progress notifications before the final result.
     */
    private fun extractJsonFromSse(body: String): String {
        if (!body.contains("data:")) return body
        val dataLines = body.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotBlank() }
            .toList()
        return dataLines.lastOrNull() ?: body
    }
}
