package dev.idadroid.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * run_shell 工具 — 在 proot 容器内执行 shell 命令。
 *
 * 这是 AI 的核心工具，覆盖 mcpc、deep-index、文件操作等所有命令行能力。
 * 超时上限 600 秒，防止 AI 恶意设置超大值。
 */
class ShellTool : AgentTool {
    override val name = "run_shell"
    override val description = """在 IDA 工作区的 Linux 环境中执行 shell 命令。
可用于：
- 运行 mcpc 命令操作 IDA Pro (如: mcpc call decompile_function '{"name":"main"}')
- 运行 deep-index 工具链 (如: deep-index index /root/pi_workspace/challenge)
- 执行文件操作 (file, strings, readelf, objdump 等)
- 运行 Python 脚本
命令在 proot 容器内执行，工作区路径为 /root/pi_workspace。
重要：mcpc 是操作 IDA 的核心工具，格式为 `mcpc call <tool_name> '<json_args>'`。""".trimIndent()

    override val parameters = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("command", ToolSchema.string("要执行的 shell 命令"))
            put("timeout", ToolSchema.integer("超时秒数，默认 120", 120))
        },
        required = listOf("command")
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = try {
            json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolOutcome.error("参数解析失败: ${e.message ?: "invalid JSON"}")
        }

        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.error("缺少 command 参数")
        val timeoutSec = (args["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 120)
            .coerceIn(1, MAX_SHELL_TIMEOUT_SEC)

        return try {
            val result = context.proot.executeCommandWithTimeout(command, timeoutSec.toLong() * 1000)
            ToolOutcome.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.error("命令执行失败: ${e.message ?: e::class.simpleName ?: "未知错误"}")
        }
    }

    companion object {
        const val MAX_SHELL_TIMEOUT_SEC = 600
    }
}

/**
 * read_file 工具 — 读取工作区文件内容。
 *
 * 自动检测二进制文件并返回十六进制。流式读取前 N 字节，避免大文件 OOM。
 */
class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "读取工作区内文件的内容。自动检测二进制文件并返回十六进制前 N 字节。"
    override val parameters = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("path", ToolSchema.string("工作区内文件相对路径（如 challenge/main.c）或绝对路径"))
            put("max_bytes", ToolSchema.integer("最大读取字节数，默认 65536", 65536))
        },
        required = listOf("path")
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = try {
            json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolOutcome.error("参数解析失败: ${e.message ?: "invalid JSON"}")
        }

        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.error("缺少 path 参数")
        val maxBytes = args["max_bytes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 65536

        val file = try {
            context.resolveWorkspaceFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        if (!file.exists()) return ToolOutcome.error("文件不存在: $path")
        if (!file.isFile) return ToolOutcome.error("不是文件: $path")

        val fileSize = file.length()
        val maxBytesLong = maxBytes.toLong().coerceAtLeast(0L)
        val safeIntMax = (Int.MAX_VALUE - 8).toLong()
        val readLimit = minOf(fileSize, maxBytesLong + 1024L).coerceAtMost(safeIntMax).toInt()
        val actualSize = minOf(fileSize, maxBytesLong).coerceAtMost(safeIntMax).toInt().coerceAtMost(readLimit)

        val bytes = ByteArray(readLimit)
        file.inputStream().use { it.read(bytes) }
        val isBinary = bytes.take(minOf(1024, actualSize)).any { it == 0.toByte() }
        val output = if (isBinary) {
            val hex = bytes.take(actualSize).joinToString("") { "%02x".format(it) }
            "二进制文件 ($path), 大小=$fileSize bytes, 显示前 $actualSize bytes:\n$hex"
        } else {
            val text = String(bytes, 0, actualSize, Charsets.UTF_8)
            if (fileSize > maxBytesLong) {
                "$text\n...(文件被截断，总大小 $fileSize bytes)"
            } else {
                text
            }
        }
        return ToolOutcome.success(output)
    }
}

/**
 * write_file 工具 — 写入文件到工作区。
 */
class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "写入文件到工作区。如果父目录不存在会自动创建。"
    override val parameters = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("path", ToolSchema.string("工作区内文件路径"))
            put("content", ToolSchema.string("文件内容"))
        },
        required = listOf("path", "content")
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = try {
            json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolOutcome.error("参数解析失败: ${e.message ?: "invalid JSON"}")
        }

        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.error("缺少 path 参数")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.error("缺少 content 参数")

        val file = try {
            context.resolveWorkspaceFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ToolOutcome.success("已写入 $path (${content.length} 字符)")
    }
}

/**
 * list_dir 工具 — 列出目录内容。
 */
class ListDirTool : AgentTool {
    override val name = "list_dir"
    override val description = "列出目录内容。"
    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", ToolSchema.string("目录路径"))
        })
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = try {
            json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolOutcome.error("参数解析失败: ${e.message ?: "invalid JSON"}")
        }

        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.error("缺少 path 参数")
        val file = try {
            context.resolveWorkspaceFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        if (!file.exists()) return ToolOutcome.error("目录不存在: $path")
        if (!file.isDirectory) return ToolOutcome.error("不是目录: $path")

        val entries = file.listFiles()?.sortedBy { it.name } ?: return ToolOutcome.success("空目录")
        val output = buildString {
            append("$path/ (${entries.size} items)\n")
            entries.forEach { entry ->
                val type = if (entry.isDirectory) "d" else "f"
                val size = if (entry.isFile) "${entry.length()}" else "-"
                append("[$type] $size\t${entry.name}\n")
            }
        }
        return ToolOutcome.success(output)
    }
}
