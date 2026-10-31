package dev.idadroid.agent

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * V5.1: Agent 插件系统 — 参考 DSHA 插件生态与 Operit 插件化工具注册。
 *
 * 插件 = 声明式 JSON manifest(一组容器内 shell 命令工具模板)。
 * 安装后其工具动态注册进 ToolRegistry(参与 function calling),
 * 禁用/卸载即时移除 — 零代码扩展 agent 能力。
 *
 * 市场源: 应用内置 assets/plugins/market.json(可离线安装);
 * 插件本体存储: filesDir/plugins/<id>.json。
 */
@Serializable
data class PluginParamDef(
    val name: String,
    val description: String,
    val default: String? = null,
    val required: Boolean = false
)

@Serializable
data class PluginToolDef(
    val name: String,
    val description: String,
    /** 命令模板 — {{param}} 占位符替换参数值, 在 proot 容器内执行 */
    val command: String,
    val timeoutSec: Int = 120,
    /** 参数声明(schema 生成 + 提示替换) */
    val params: List<PluginParamDef> = emptyList()
)

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "IDAdroid",
    val tools: List<PluginToolDef> = emptyList()
)

data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean,
    val installedAt: Long
)

data class PluginsUiState(
    val installed: List<InstalledPlugin> = emptyList(),
    val market: List<PluginManifest> = emptyList(),
    val message: String? = null
)

/**
 * 插件工具 — manifest 工具定义包装为 function calling 工具。
 * 执行 = 参数模板替换 → proot 容器内 shell(单引号转义防注入)。
 */
class PluginTool(
    private val def: PluginToolDef
) : AbstractAgentTool() {
    override val name = def.name
    override val description = def.description

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            def.params.forEach { p ->
                put(p.name, buildJsonObject {
                    put("type", "string")
                    put("description", p.description)
                })
            }
        })
        val required = def.params.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) {
            put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        put("additionalProperties", true)
    }

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        // 收集全部参数(未知参数也允许 — 命令模板可引用)
        val values = mutableMapOf<String, String>()
        args.forEach { (k, v) ->
            values[k] = v.toString().trim('"')
        }
        var command = def.command
        for ((k, v) in values) {
            command = command.replace("{{$k}}", shellEscape(v))
        }
        // 未提供的占位符替换为空串(避免字面 {{}} 泄漏进 shell)
        command = Regex("\\{\\{\\w+}}").replace(command, "")
        val timeoutMs = (def.timeoutSec.coerceIn(5, 600)) * 1000L
        return try {
            val output = context.proot.executeCommandWithTimeout(command, timeoutMs)
            val text = output.trim()
            if (text.isEmpty()) ToolOutcome.success("(命令执行完成, 无输出)")
            else ToolOutcome.success(text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.error("插件工具 '$name' 执行失败: ${e.message ?: e::class.simpleName}")
        }
    }

    /** 单引号转义 — 参数值只能作为字面量进入 shell */
    private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

class PluginManager(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pluginsDir = File(context.filesDir, "plugins").apply { mkdirs() }

    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    init { refresh() }

    /** 刷新已安装插件 + 市场目录 */
    fun refresh() {
        val installed = pluginsDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching {
                    val m = json.decodeFromString(PluginManifest.serializer(), f.readText())
                    // 命名约定: <id>.json / <id>.on.json = 启用; <id>.off.json = 禁用
                    val enabled = !f.nameWithoutSuffix.endsWith(".off")
                    InstalledPlugin(m, enabled, f.lastModified())
                }.getOrNull()
            } ?: emptyList()
        val market = loadMarketCatalog()
        _state.update { it.copy(installed = installed, market = market, message = null) }
    }

    private val File.nameWithoutSuffix: String get() = name.substringBeforeLast(".")

    /** 内置市场目录(assets) */
    private fun loadMarketCatalog(): List<PluginManifest> = runCatching {
        val text = context.assets.open("plugins/market.json").bufferedReader().use { it.readText() }
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PluginManifest.serializer()), text)
    }.getOrDefault(emptyList())

    /** 安装(从市场 manifest) */
    fun install(manifest: PluginManifest) {
        val file = File(pluginsDir, "${manifest.id}.json")
        file.writeText(json.encodeToString(PluginManifest.serializer(), manifest))
        refresh()
        _state.update { it.copy(message = "已安装 ${manifest.name} (${manifest.tools.size} 个工具)") }
    }

    /** 卸载 */
    fun uninstall(id: String) {
        pluginsDir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        refresh()
        _state.update { it.copy(message = "已卸载 $id") }
    }

    /** 启用/禁用 — 文件名约定 <id>.json/<id>.on.json = 启用, <id>.off.json = 禁用 */
    fun setEnabled(id: String, enabled: Boolean) {
        val current = pluginsDir.listFiles()?.firstOrNull { it.name.startsWith("$id.") }
            ?: return
        val target = File(pluginsDir, if (enabled) "$id.on.json" else "$id.off.json")
        current.renameTo(target)
        refresh()
    }

    /** 当前启用的插件工具(注册进 ToolRegistry; 执行走 ToolContext.proot) */
    fun activeTools(): List<AgentTool> =
        _state.value.installed.filter { it.enabled }.flatMap { p ->
            p.manifest.tools.map { PluginTool(it) }
        }

    /** 是否已安装 */
    fun isInstalled(id: String): Boolean = _state.value.installed.any { it.manifest.id == id }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
