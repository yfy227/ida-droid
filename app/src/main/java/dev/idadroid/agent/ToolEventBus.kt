package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Layer 2: 工具事件总线
 *
 * 将 IDA/MCP/Shell 工具暴露为 OpenAI function calling 格式。
 * AI 主动调用工具 → 这里执行 → 结果返回给对话流。
 *
 * 执行策略：轮询而非阻塞。每个工具有默认超时和轮询间隔。
 */
class ToolEventBus(
    private val context: Context,
    private val proot: IdaProotRuntime,
    private val paths: EnvironmentPaths
) {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val workspaceProotRel: String get() {
        val ws = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
        return ws.removePrefix("/").ifBlank { "root/pi_workspace" }
    }

    /**
     * 返回所有可用工具的 OpenAI function calling 定义。
     *
     * 工具清单：
     * 1. run_shell    — 在 proot 内执行 shell 命令（覆盖 mcpc, deep-index 等）
     * 2. read_file    — 读取工作区文件
     * 3. write_file   — 写入工作区文件
     * 4. list_dir     — 列出目录内容
     * 5. mcp_tool     — 调用 IDA MCP 工具（如果 ida-mcp 服务可用）
     */
    fun toolDefinitions(): List<ChatHttpClient.ToolDefinition> = listOf(
        ChatHttpClient.ToolDefinition(
            name = "run_shell",
            description = """在 IDA 工作区的 Linux 环境中执行 shell 命令。
可用于：
- 运行 mcpc 命令操作 IDA Pro (如: mcpc call decompile_function '{"name":"main"}')
- 运行 deep-index 工具链 (如: deep-index index /root/pi_workspace/challenge)
- 执行文件操作 (file, strings, readelf, objdump 等)
- 运行 Python 脚本
命令在 proot 容器内执行，工作区路径为 /root/pi_workspace。
重要：mcpc 是操作 IDA 的核心工具，格式为 `mcpc call <tool_name> '<json_args>'`。""".trimIndent(),
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "要执行的 shell 命令")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put("description", "超时秒数，默认 120")
                        put("default", 120)
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("command"))))
            }
        ),
        ChatHttpClient.ToolDefinition(
            name = "read_file",
            description = "读取工作区内文件的内容。自动检测二进制文件并返回十六进制前 N 字节。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "工作区内文件相对路径（如 challenge/main.c）或绝对路径")
                    })
                    put("max_bytes", buildJsonObject {
                        put("type", "integer")
                        put("description", "最大读取字节数，默认 65536")
                        put("default", 65536)
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
            }
        ),
        ChatHttpClient.ToolDefinition(
            name = "write_file",
            description = "写入文件到工作区。如果父目录不存在会自动创建。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "工作区内文件路径")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "文件内容")
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(
                    JsonPrimitive("path"), JsonPrimitive("content")
                )))
            }
        ),
        ChatHttpClient.ToolDefinition(
            name = "list_dir",
            description = "列出目录内容。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "目录路径，默认 /root/pi_workspace")
                        put("default", "/root/pi_workspace")
                    })
                })
            }
        )
    )

    /**
     * 执行工具调用。
     *
     * @param toolName 工具名
     * @param argsJson 参数 JSON 字符串
     * @return 工具执行结果文本
     */
    suspend fun execute(
        toolName: String,
        argsJson: String
    ): String = withContext(Dispatchers.IO) {
        val args = try {
            json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return@withContext "错误: 参数解析失败: ${e.message ?: e::class.simpleName ?: "未知错误"}"
        }

        when (toolName) {
            "run_shell" -> executeShell(args)
            "read_file" -> executeReadFile(args)
            "write_file" -> executeWriteFile(args)
            "list_dir" -> executeListDir(args)
            else -> "错误: 未知工具 '$toolName'"
        }
    }

    /**
     * 判断工具执行结果是否成功。
     *
     * 统一的成功/失败判断逻辑，替代字符串前缀匹配。
     * 规则：
     * - 以 "错误:" 开头 → 失败
     * - 以 "Error:" 或 "ERROR:" 开头 → 失败
     * - 其他 → 成功
     *
     * 注意：这是启发式判断，对包含 "错误:" 但实际成功的边缘情况可能误判。
     * 未来应改为工具返回结构化结果。
     */
    fun isToolResultSuccess(toolName: String, result: String): Boolean {
        val trimmed = result.trim()
        if (trimmed.isEmpty()) return false
        // 检查失败前缀
        val failurePrefixes = listOf("错误:", "Error:", "ERROR:", "exception:", "Exception:")
        return failurePrefixes.none { prefix -> trimmed.startsWith(prefix) }
    }

    /**
     * 在 proot 内执行 shell 命令。
     * 使用轮询方式检查进程完成，避免长时间阻塞。
     */
    private suspend fun executeShell(args: JsonObject): String {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return "错误: 缺少 command 参数"
        val timeoutSec = args["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 120

        return try {
            proot.executeCommandWithTimeout(command, timeoutSec.toLong() * 1000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "错误: 命令执行失败: ${e.message ?: e::class.simpleName ?: "未知错误"}"
        }
    }

    private fun executeReadFile(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return "错误: 缺少 path 参数"
        val maxBytes = args["max_bytes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 65536

        val file = resolveWorkspaceFile(path)
        if (!file.exists()) return "错误: 文件不存在: $path"
        if (!file.isFile) return "错误: 不是文件: $path"

        val fileSize = file.length().toInt()
        val actualSize = minOf(fileSize, maxBytes)

        // 流式读取前 maxBytes+1024 字节，避免大文件 OOM
        val readSize = minOf(fileSize, maxBytes + 1024)
        val bytes = ByteArray(readSize)
        file.inputStream().use { it.read(bytes) }
        val isBinary = bytes.take(minOf(1024, readSize)).any { it == 0.toByte() }
        return if (isBinary) {
            val hex = bytes.take(actualSize).joinToString("") { "%02x".format(it) }
            "二进制文件 ($path), 大小=$fileSize bytes, 显示前 $actualSize bytes:\n$hex"
        } else {
            val text = String(bytes, 0, actualSize, Charsets.UTF_8)
            if (fileSize > maxBytes) {
                "$text\n...(文件被截断，总大小 $fileSize bytes)"
            } else {
                text
            }
        }
    }

    private fun executeWriteFile(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return "错误: 缺少 path 参数"
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return "错误: 缺少 content 参数"

        val file = resolveWorkspaceFile(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "已写入 $path (${content.length} 字符)"
    }

    private fun executeListDir(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return "错误: 缺少 path 参数"
        val file = resolveWorkspaceFile(path)
        if (!file.exists()) return "错误: 目录不存在: $path"
        if (!file.isDirectory) return "错误: 不是目录: $path"

        val entries = file.listFiles()?.sortedBy { it.name } ?: return "空目录"
        return buildString {
            append("$path/ (${entries.size} items)\n")
            entries.forEach { entry ->
                val type = if (entry.isDirectory) "d" else "f"
                val size = if (entry.isFile) "${entry.length()}" else "-"
                append("[$type] $size\t${entry.name}\n")
            }
        }
    }

    /** 将工作区内相对路径解析为实际文件系统路径，防止路径遍历攻击 */
    private fun resolveWorkspaceFile(path: String): File {
        val workspace = File(paths.rootfsDir, workspaceProotRel)
        val resolved = if (path.startsWith("/")) {
            File(paths.rootfsDir, path.removePrefix("/"))
        } else {
            File(workspace, path)
        }
        // 路径遍历保护：规范化后必须在工作区内
        val canonicalWorkspace = workspace.canonicalFile
        val canonicalResolved = resolved.canonicalFile
        if (!canonicalResolved.path.startsWith(canonicalWorkspace.path + File.separator) &&
            canonicalResolved.path != canonicalWorkspace.path) {
            throw SecurityException("路径越界：$path")
        }
        return canonicalResolved
    }
}
