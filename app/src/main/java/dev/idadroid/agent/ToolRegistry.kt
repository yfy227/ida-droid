package dev.idadroid.agent

import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import dev.idadroid.settings.IdaDroidSettings
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Agent 工具系统 — 插件化工具注册与执行。
 *
 * 设计参考：
 * - ToolRegistry (arxiv 2507.10593): protocol-agnostic tool management
 * - Operit: Android AI agent tool-calling 架构
 * - OpenAI function calling: tools + tool_choice
 *
 * 核心概念：
 * - [AgentTool] — 每个工具实现一个接口，自包含定义和执行逻辑
 * - [ToolRegistry] — 工具注册表，统一管理注册/查找/执行
 * - [ToolContext] — 工具执行上下文（proot、路径、设置）
 *
 * 与旧 ToolEventBus 的区别：
 * - 工具自包含定义和执行，不再需要在 when 分支中硬编码
 * - 新增工具只需实现 AgentTool 接口并注册，无需修改核心代码
 * - 工具返回结构化 [ToolOutcome]，不再用字符串前缀判断成功/失败
 */
class ToolRegistry {

    private val tools = linkedMapOf<String, AgentTool>()

    /** 注册工具 */
    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    /** 获取所有已注册工具的 OpenAI function calling 定义 */
    fun definitions(): List<ChatHttpClient.ToolDefinition> =
        tools.values.map { it.toToolDefinition() }

    /** 按名称查找工具 */
    fun find(name: String): AgentTool? = tools[name]

    /** 执行工具调用 — 内置审计日志和异常保护 */
    suspend fun execute(
        name: String,
        argsJson: String,
        context: ToolContext
    ): ToolOutcome {
        val tool = tools[name]
            ?: return ToolOutcome.error("未知工具 '$name'，可用: ${tools.keys.joinToString(", ")}")

        val startTime = System.currentTimeMillis()
        return try {
            val result = tool.execute(argsJson, context)
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.d("ToolRegistry",
                "工具 $name 执行${if (result.success) "成功" else "失败"} (${elapsed}ms)")
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            val msg = e.message ?: e::class.simpleName ?: "未知错误"
            android.util.Log.w("ToolRegistry",
                "工具 $name 执行异常 (${elapsed}ms): $msg")
            ToolOutcome.error("工具 '$name' 执行异常: $msg")
        }
    }

    /** 获取所有已注册工具名 */
    fun toolNames(): List<String> = tools.keys.toList()
}

/**
 * 工具执行上下文 — 提供工具运行所需的环境依赖。
 *
 * 通过 context 传递而非构造函数注入，使工具实现与具体环境解耦。
 */
class ToolContext(
    val proot: IdaProotRuntime,
    val paths: EnvironmentPaths,
    val settings: IdaDroidSettings
) {
    /** 工作区在主机文件系统上的相对路径 */
    val workspaceRel: String get() {
        val ws = settings.envSettings.value.workspacePath
            .ifBlank { IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
        return ws.removePrefix("/").ifBlank { "root/pi_workspace" }
    }

    /** 工作区在主机文件系统上的根目录 */
    val workspaceDir: File get() = File(paths.rootfsDir, workspaceRel)

    /**
     * 将工作区内路径解析为实际文件系统路径，防止路径遍历攻击。
     *
     * 安全策略：
     * - 绝对路径 (/xxx) 在 rootfs 内解析
     * - 相对路径在工作区内解析
     * - 规范化后必须在工作区内，否则抛出 SecurityException
     */
    fun resolveWorkspaceFile(path: String): File {
        val resolved = if (path.startsWith("/")) {
            File(paths.rootfsDir, path.removePrefix("/"))
        } else {
            File(workspaceDir, path)
        }
        val canonicalWorkspace = workspaceDir.canonicalFile
        val canonicalResolved = resolved.canonicalFile
        if (!canonicalResolved.path.startsWith(canonicalWorkspace.path + File.separator) &&
            canonicalResolved.path != canonicalWorkspace.path) {
            throw SecurityException("路径越界：$path")
        }
        return canonicalResolved
    }
}

/**
 * 工具执行结果 — 结构化输出，取代旧的字符串前缀判断。
 *
 * @property success 是否成功
 * @property output 输出文本（给 AI 看）
 * @property error 错误信息（仅 success=false 时有意义）
 */
data class ToolOutcome(
    val success: Boolean,
    val output: String,
    val error: String? = null
) {
    companion object {
        fun success(output: String) = ToolOutcome(true, output, null)
        fun error(message: String) = ToolOutcome(false, message, message)
    }
}

/**
 * Agent 工具接口 — 每个工具实现此接口。
 *
 * 实现者需提供：
 * - [name] 工具名（唯一标识，OpenAI function calling 中的 function name）
 * - [description] 工具描述（给 AI 看的说明）
 * - [parameters] JSON Schema 参数定义
 * - [execute] 执行逻辑
 *
 * 推荐继承 [AbstractAgentTool] 而非直接实现此接口，
 * 以获得共享的 Json 实例和参数解析辅助方法。
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject

    /** 转换为 OpenAI ToolDefinition */
    fun toToolDefinition(): ChatHttpClient.ToolDefinition =
        ChatHttpClient.ToolDefinition(name, description, parameters)

    /**
     * 执行工具。
     *
     * @param argsJson 参数 JSON 字符串
     * @param context 工具执行上下文
     * @return 结构化执行结果
     */
    suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome
}

/**
 * 工具基类 — 共享 Json 实例和参数解析辅助方法。
 *
 * 子类只需实现 [doExecute] 方法，参数解析由基类处理。
 * 共享 Json 实例避免每个工具重复创建。
 */
abstract class AbstractAgentTool : AgentTool {
    /** 共享 Json 实例 — 所有工具复用 */
    protected val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析参数 JSON 字符串为 JsonObject。
     * 解析失败返回 null，调用方应处理 null 并返回 ToolOutcome.error。
     */
    protected fun parseArgs(argsJson: String): JsonObject? = try {
        json.parseToJsonElement(argsJson).jsonObject
    } catch (_: Exception) { null }

    /** 获取字符串参数，缺失时返回 null */
    protected fun JsonObject.getString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    /** 获取整数参数，缺失或无效时返回 null */
    protected fun JsonObject.getInt(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    /** 获取布尔参数，缺失时返回 null */
    protected fun JsonObject.getBoolean(key: String): Boolean? =
        this[key]?.let { it.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }
}

/**
 * 工具 JSON Schema 构建辅助函数。
 */
object ToolSchema {
    /** 创建 string 参数 */
    fun string(desc: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", desc)
        enum?.let { put("enum", kotlinx.serialization.json.JsonArray(it.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
    }

    /** 创建 integer 参数 */
    fun integer(desc: String, default: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", desc)
        default?.let { put("default", it) }
    }

    /** 创建 object properties 包装 */
    fun objectSchema(properties: JsonObject, required: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
}
