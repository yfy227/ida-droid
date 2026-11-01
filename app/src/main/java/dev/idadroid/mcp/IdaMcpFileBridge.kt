package dev.idadroid.mcp

import dev.idadroid.agent.McpFileBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * V5: [McpFileBridge] 的 ida-mcp 实现 — 把 host 文件拉取 + ida-mcp 直调
 * 注入 agent 工具层(PullFileTool / McpCallTool / McpStatusTool)。
 *
 * 由 UI 层(IdaDroidApp)创建并连线: agentManager.setMcpFileBridge(IdaMcpFileBridge(mcpManager))
 * agent 层只依赖 agent/McpFileBridge 接口, 零 mcp 反向依赖(DSHA 式分层纪律)。
 */
class IdaMcpFileBridge(
    private val sessionManager: IdaMcpSessionManager
) : McpFileBridge {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun client(): IdaMcpClient? {
        val state = sessionManager.state.value
        if (state.status != IdaMcpStatus.Running) return null
        return runCatching { IdaMcpClient(state.endpoint) }.getOrNull()
    }

    override fun searchHost(name: String): List<McpFileBridge.HostCandidate> =
        sessionManager.transfers.searchHostCandidates(name).map {
            McpFileBridge.HostCandidate(it.name, it.absolutePath, it.length(), it.lastModified())
        }

    override suspend fun transfer(hostPath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { sessionManager.transfers.transferHostPath(hostPath).prootPath }
    }

    override suspend fun openInIda(prootPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val c = client() ?: return@withContext Result.failure(IllegalStateException("ida-mcp 会话未启动"))
        runCatching { c.openFile(prootPath); }.map { }
    }

    override fun mcpEndpoint(): String? = client()?.let { sessionManager.state.value.endpoint }

    override suspend fun callMcpTool(toolName: String, argsJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            val c = client() ?: return@withContext Result.failure(IllegalStateException("ida-mcp 会话未启动 — 请在 MCP 面板启动"))
            runCatching {
                // argsJson → Map<String, JsonPrimitive>(一层解析; 复杂嵌套值保持字符串形式按需由 ida-mcp 解析)
                val args = runCatching {
                    json.parseToJsonElement(argsJson).let { el ->
                        (el as? kotlinx.serialization.json.JsonObject)?.mapValues { (_, v) ->
                            when {
                                v is JsonPrimitive -> v
                                else -> JsonPrimitive(v.toString())
                            }
                        } ?: emptyMap()
                    }
                }.getOrDefault(emptyMap())
                c.callTool(toolName, args)
            }
        }

    override suspend fun listMcpTools(): List<McpFileBridge.McpToolInfo>? = withContext(Dispatchers.IO) {
        val c = client() ?: return@withContext null
        runCatching { c.listTools() }.getOrNull()?.map {
            McpFileBridge.McpToolInfo(it.first, it.second)
        }
    }

    override fun mcpStatusJson(): String {
        val s = sessionManager.state.value
        return buildJsonObject {
            put("status", s.status.name)
            put("endpoint", s.endpoint)
            put("pid", s.pid ?: -1)
            put("healthy", s.lastHealthOk)
            put("autoRestarts", s.autoRestartCount)
        }.toString()
    }
}
