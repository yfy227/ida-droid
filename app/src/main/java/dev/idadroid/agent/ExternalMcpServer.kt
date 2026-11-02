package dev.idadroid.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID

/**
 * V6.6: 外部 MCP Server — 把 IDAdroid 能力暴露给外部 agent(Claude Desktop/其他手机 agent/PC agent)。
 *
 * 协议: MCP JSON-RPC 2.0 over HTTP POST(端口 8823, 局域网可达)
 * 传输: application/json(非流式, 适配 mobile 环境)
 *
 * 暴露工具:
 *  - idadroid_push_file:      外部文件(base64) → 容器 /root/pi_workspace/uploads/ → 返回确定路径
 *  - idadroid_start_analysis: 容器内路径 → 经 ida-mcp 在 IDA 中打开启动分析
 *  - idadroid_status:         环境/IDA/MCP/工具链状态
 *
 * 安全: 仅监听 0.0.0.0:8823, 单轮 token 上限 200MB, 路径白名单校验(禁 .. 穿越)。
 */
class ExternalMcpServer(
    private val context: Context,
    private val port: Int = 8823
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var bridge: McpFileBridge? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 注入 MCP 桥(宿主→容器能力) */
    fun setBridge(bridge: McpFileBridge?) { this.bridge = bridge }

    fun start(): Boolean {
        if (running) return true
        return runCatching {
            serverSocket = ServerSocket()
            serverSocket!!.reuseAddress = true
            serverSocket!!.bind(InetSocketAddress(port), 16)
            running = true
            scope.launch { acceptLoop() }
            Log.i("ExternalMcpServer", "MCP server listening on :$port (tools: push_file/start_analysis/status)")
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun isRunning(): Boolean = running

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch { handleClient(client) }
        }
    }

    private fun handleClient(client: java.net.Socket) {
        runCatching {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            // 读 HTTP 头
            val header = StringBuilder()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                header.append(line).append('\n')
            }
            val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isPost = header.toString().startsWith("POST", ignoreCase = true)
            val body = if (contentLength > 0 && contentLength < 220_000_000) {
                ByteArray(contentLength).also { var off = 0; while (off < contentLength) { val n = input.read(it, off, contentLength - off); if (n < 0) break; off += n } }
            } else ByteArray(0)

            val response = when {
                !isPost -> httpJson(405, buildErrorResponse(null, -32601, "Method Not Allowed: 仅支持 POST /mcp (MCP JSON-RPC)"))
                else -> handleRpc(body.toString(Charsets.UTF_8))
            }
            client.getOutputStream().apply {
                write(response)
                flush()
            }
        }
        runCatching { client.close() }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    // ══════════════ JSON-RPC 分发 ══════════════
    private fun handleRpc(body: String): ByteArray {
        val response = runCatching {
            val req = json.parseToJsonElement(body).jsonObject
            val id = req["id"]
            val method = req["method"]?.jsonPrimitive?.contentOrNull ?: ""
            val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
            when (method) {
                "initialize" -> buildResult(id, buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                    put("serverInfo", buildJsonObject {
                        put("name", "idadroid")
                        put("version", "6.6")
                    })
                })
                "tools/list" -> buildResult(id, buildJsonObject {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "idadroid_push_file")
                            put("description", "将外部文件传入 IDAdroid 容器(返回容器内确定路径, 供后续分析引用)。base64 内容, 最大 200MB。")
                            put("inputSchema", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("name", buildJsonObject { put("type", "string"); put("description", "文件名(如 sample.apk)") })
                                    put("content_base64", buildJsonObject { put("type", "string"); put("description", "文件内容 base64") })
                                })
                                put("required", buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("content_base64")) })
                            })
                        })
                        add(buildJsonObject {
                            put("name", "idadroid_start_analysis")
                            put("description", "按容器内确定路径启动 IDA 分析(需先 push_file)。返回打开状态。")
                            put("inputSchema", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("path", buildJsonObject { put("type", "string"); put("description", "idadroid_push_file 返回的 prootPath") })
                                })
                                put("required", buildJsonArray { add(JsonPrimitive("path")) })
                            })
                        })
                        add(buildJsonObject {
                            put("name", "idadroid_status")
                            put("description", "IDAdroid 环境/IDA/MCP 状态(是否可接收文件/分析)。")
                            put("inputSchema", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) })
                        })
                    })
                })
                "tools/call" -> handleToolCall(id, params)
                "ping" -> buildResult(id, buildJsonObject { })
                else -> buildErrorResponse(id, -32601, "Method not found: $method")
            }
        }.getOrElse { buildErrorResponse(null, -32700, "Parse error: ${it.message?.take(120)}") }
        return httpJson(200, response)
    }

    private fun handleToolCall(id: JsonElement?, params: JsonObject): String {
        val tool = params["name"]?.jsonPrimitive?.contentOrNull ?: return buildErrorResponse(id, -32602, "缺少工具名")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return when (tool) {
            "idadroid_push_file" -> {
                val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return buildErrorResponse(id, -32602, "缺少参数 name")
                if (!isSafeFileName(name)) return buildErrorResponse(id, -32602, "文件名不安全(禁 / \\ ..)")
                val b64 = args["content_base64"]?.jsonPrimitive?.contentOrNull ?: return buildErrorResponse(id, -32602, "缺少参数 content_base64")
                runCatching {
                    val bytes = Base64.getDecoder().decode(b64)
                    if (bytes.size > 200 * 1024 * 1024) return buildErrorResponse(id, -32000, "超过 200MB 上限")
                    // host 临时文件 → MCP 桥 transfer 进容器(指纹去重复用)
                    val tmp = File(context.cacheDir, "ext_mcp_${UUID.randomUUID()}_$name")
                    tmp.writeBytes(bytes)
                    val b = bridge ?: return buildErrorResponse(id, -32001, "MCP 文件桥未就绪(请先在 IDAdroid 中启动 MCP/环境)")
                    val prootPath = runBlocking { withTimeout(120_000) { b.transfer(tmp.absolutePath) } }
                        .fold(onSuccess = { it }, onFailure = { return buildErrorResponse(id, -32002, "传输进容器失败: ${it.message?.take(120)}") })
                    tmp.delete()
                    buildResult(id, buildJsonObject {
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "已传入容器: $prootPath (${bytes.size / 1024}KB)。下一步: idadroid_start_analysis path=$prootPath") }) })
                        put("prootPath", prootPath)
                        put("size", bytes.size)
                    })
                }.getOrElse { buildErrorResponse(id, -32003, "写入失败: ${it.message?.take(120)}") }
            }
            "idadroid_start_analysis" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim() ?: return buildErrorResponse(id, -32602, "缺少参数 path")
                if (!path.startsWith("/") || path.contains("..")) return buildErrorResponse(id, -32602, "路径不合法(需容器内绝对路径, 禁 ..)")
                val b = bridge ?: return buildErrorResponse(id, -32001, "MCP 文件桥未就绪")
                val result = runBlocking { withTimeout(60_000) { b.openInIda(path) } }
                    .fold(onSuccess = { "opened" }, onFailure = { "failed: ${it.message?.take(120)}" })
                buildResult(id, buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "IDA 分析启动: $result (path=$path)。可在 IDAdroid GUI 中查看进度, 或用 ida-mcp 工具链深入。") }) })
                    put("status", result)
                })
            }
            "idadroid_status" -> {
                buildResult(id, buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject {
                        put("type", "text")
                        put("text", buildString {
                            appendLine("IDAdroid 外部 MCP 网关 v6.6")
                            appendLine("文件桥: ${if (bridge != null) "就绪" else "未就绪(需 App 内启动 MCP)"}")
                            appendLine("IDA MCP 端点: ${bridge?.mcpEndpoint() ?: "未启动"}")
                        })
                    }) })
                    put("bridgeReady", bridge != null)
                    put("mcpEndpoint", bridge?.mcpEndpoint() ?: "")
                })
            }
            else -> buildErrorResponse(id, -32601, "Unknown tool: $tool")
        }
    }

    private fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() && name.length < 200 && !name.contains('/') && !name.contains('\\') && !name.contains("..")

    private fun buildResult(id: JsonElement?, result: JsonObject): String =
        buildJsonObject { id?.let { put("id", it) }; put("jsonrpc", "2.0"); put("result", result) }.toString()

    private fun buildErrorResponse(id: JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            id?.let { put("id", it) }
            put("jsonrpc", "2.0")
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }.toString()

    private fun httpJson(status: Int, body: String): ByteArray {
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + body.toByteArray(Charsets.UTF_8)
    }
}
