package dev.idadroid.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * V5: pull_file 工具 — MCP 主动拉文件(替代用户手动上传)。
 *
 * 行为:
 * 1. 按名字模糊搜索宿主(Download/Documents/DCIM/workspace/.mcp-transfer)
 * 2. 按候选序号(默认 0 = 最新)传输进 /root/.mcp-transfer(内容指纹去重)
 * 3. open_in_ida=true 时经 ida-mcp 直接在容器内 IDA 打开(会话未启动则跳过并提示)
 * 4. 返回结构化 JSON(prootPath + 候选列表 + IDA 打开结果)
 *
 * 与 idadroid-file 脚本桥的关系: 双通道并存 — function calling 通道(本工具,
 * host 原生执行)为主, run_shell 通道(脚本+HTTP 桥)保留兼容。
 */
class PullFileTool : AbstractAgentTool() {
    override val name = "pull_file"
    override val description = """从 Android 手机宿主存储主动拉取文件进 proot 容器(无需用户手动上传)。
搜索范围: /sdcard/Download、/sdcard/Documents、/sdcard/DCIM、agent 工作区。
流程: 按名字模糊搜索宿主文件 → 复制到容器 /root/.mcp-transfer/ (内容指纹去重,同一文件重复拉取直接复用) → 可选自动在 IDA 中打开。
用法: 用户说"分析 xxx.apk / 看看 xxx.so"时, 直接调用本工具, 再用 mcpc 或 deep-index 后续分析。
参数: name=文件名(支持部分匹配); pick_index=多候选时选择(默认0最新); open_in_ida=是否自动在IDA打开(默认true)。""".trimIndent()

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "要拉取的文件名或部分文件名, 如 challenge.apk")
            })
            put("pick_index", buildJsonObject {
                put("type", "integer")
                put("description", "当搜索到多个候选时的选择序号(默认 0 = 最新修改的文件)")
            })
            put("open_in_ida", buildJsonObject {
                put("type", "boolean")
                put("description", "拉取后是否自动经 ida-mcp 在 IDA 中打开(默认 true)")
            })
        })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("name")) })
    }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val bridge = context.fileBridge
            ?: return ToolOutcome.error("MCP 文件桥未接线(宿主拉取能力不可用)。请改用 run_shell + idadroid-file 通道。")

        val fileName = args.getString("name")
            ?: return ToolOutcome.error("缺少参数 name(要拉取的文件名)")

        val candidates = bridge.searchHost(fileName)
        if (candidates.isEmpty()) {
            return ToolOutcome.error(
                "宿主端未找到匹配 '$fileName' 的文件。已搜索: Download/Documents/DCIM/工作区。\n" +
                "建议: 让用户把文件放到 /sdcard/Download 后重试, 或确认文件名。"
            )
        }

        val pickIndex = args.getInt("pick_index") ?: 0
        if (pickIndex < 0 || pickIndex >= candidates.size) {
            return ToolOutcome.error(
                "pick_index=$pickIndex 超出候选范围(0..${candidates.size - 1})。\n" +
                candidates.take(5).joinToString("\n") { "  [${candidates.indexOf(it)}] ${it.name} (${it.size}B)" }
            )
        }
        val picked = candidates[pickIndex]

        val prootPath = bridge.transfer(picked.hostPath).getOrElse { e ->
            return ToolOutcome.error("传输失败: ${e.message}")
        }

        val wantOpen = args.getBoolean("open_in_ida") ?: true
        val idaResult = if (wantOpen) {
            bridge.openInIda(prootPath).fold(
                onSuccess = { "opened" },
                onFailure = { "skipped: ${it.message?.take(120)}" }
            )
        } else "skipped_by_arg"

        return ToolOutcome.success(buildJsonObject {
            put("ok", true)
            put("name", picked.name)
            put("size", picked.size)
            put("prootPath", prootPath)
            put("ida", idaResult)
            put("candidates", buildJsonArray {
                candidates.take(10).forEachIndexed { i, c ->
                    add(buildJsonObject {
                        put("index", i)
                        put("name", c.name)
                        put("size", c.size)
                        put("modified_ms", c.lastModified)
                    })
                }
            })
            put("hint", "已拉取 ${picked.name} → 可用 mcpc / deep-index / run_shell 继续分析")
        }.toString())
    }
}

/**
 * V5.1: mcp_call 工具 — host 侧直调 ida-mcp 工具(结构化通道)。
 *
 * 与 run_shell → mcpc 的区别: 参数 JSON 原生传递(不经 shell 引号转义),
 * 结果直接返回(不经终端文本回读), 失败结构化。覆盖 40+ IDA MCP 工具。
 */
class McpCallTool : AbstractAgentTool() {
    override val name = "mcp_call"
    override val description = """直接调用容器内 ida-mcp 的工具(结构化通道, 优先于 run_shell 调 mcpc)。
常用工具: open_file / list_functions / decompile_function / get_function_by_name / list_strings / xrefs_to ...
先用 mcp_status 查看 ida-mcp 会话状态与可用工具列表。
args_json 必须是合法 JSON(如 {"name":"main"})。""".trimIndent()

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("tool_name", buildJsonObject {
                put("type", "string")
                put("description", "ida-mcp 工具名, 如 decompile_function")
            })
            put("args_json", buildJsonObject {
                put("type", "string")
                put("description", "工具参数的 JSON 字符串, 如 {\"name\":\"main\"} (无参数传 {})")
            })
        })
        put("required", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("tool_name"))
        })
    }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val bridge = context.fileBridge
            ?: return ToolOutcome.error("MCP 桥未接线。请改用 run_shell 调 mcpc。")
        val toolName = args.getString("tool_name")
            ?: return ToolOutcome.error("缺少参数 tool_name")
        val innerArgsJson = args.getString("args_json") ?: "{}"
        // 校验 JSON 合法性, 提前给出可读错误
        try {
            kotlinx.serialization.json.Json.parseToJsonElement(innerArgsJson)
        } catch (e: Exception) {
            return ToolOutcome.error("args_json 不是合法 JSON: ${e.message}\n示例: {\"name\":\"main\"}")
        }
        // V6.7 MCP 优化: 超时自适应 — 按工具类型分级(读类 60s / 汇总类 300s / 全库分析类 600s)
        val timeoutMs = when {
            toolName.startsWith("decompile") || toolName.startsWith("get_") ||
                toolName.startsWith("list_") || toolName.startsWith("xrefs") ||
                toolName == "open_file" || toolName == "close_file" -> 60_000L
            toolName.startsWith("search") || toolName.startsWith("scan") ||
                toolName.startsWith("deep") -> 300_000L
            toolName.contains("batch") || toolName.contains("all") -> 600_000L
            else -> 120_000L
        }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            bridge.callMcpTool(toolName, innerArgsJson)
        }?.fold(
            onSuccess = { ToolOutcome.success(it) },
            onFailure = { ToolOutcome.error("ida-mcp 调用失败: ${it.message}") }
        ) ?: run {
            ToolOutcome.error("ida-mcp '$toolName' 超时(${timeoutMs / 1000}s)。建议: 拆小分析范围或稍后重试(自动重试已启用)。")
        }
    }
}

/**
 * V5.1: mcp_status 工具 — ida-mcp 会话体检。
 */
class McpStatusTool : AbstractAgentTool() {
    override val name = "mcp_status"
    override val description = """查看 ida-mcp 会话状态(端点/PID/健康检查)与可用工具列表。
建议在调用 mcp_call 前先执行一次; 会话未启动时会给出启动指引。无参数。""".trimIndent()

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val bridge = context.fileBridge
            ?: return ToolOutcome.error("MCP 桥未接线(宿主无法探测 ida-mcp 会话)。")
        val tools = bridge.listMcpTools()
        return ToolOutcome.success(buildJsonObject {
            put("status", bridge.mcpStatusJson())
            if (tools != null) {
                put("tools_available", tools.size)
                put("tools", buildJsonArray {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description.take(120))
                        })
                    }
                })
                put("hint", "用 mcp_call {\"tool_name\":\"<name>\",\"args_json\":{...}} 调用")
            } else {
                put("tools_available", 0)
                put("hint", "ida-mcp 会话未启动或未就绪 — 提示用户在 MCP 面板启动会话")
            }
        }.toString())
    }
}
