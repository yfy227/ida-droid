package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class PiConfigManager(
    private val context: Context,
    private val paths: EnvironmentPaths
) {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    /** 用户设置的工作区路径 (proot 内可见路径) */
    fun workspaceProotPath(): String = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
    private val workspace get() = File(paths.rootfsDir, workspaceProotPath().removePrefix("/").removePrefix("root/").ifBlank { "root/pi_workspace" })
    private val idaDroidDir get() = File(workspace, ".idadroid")
    private val agentDir get() = File(idaDroidDir, "pi-agent")
    private val piDir get() = File(workspace, ".pi")
    private val configFile get() = File(agentDir, "idadroid-pi-config.json")
    private val settingsFile get() = File(agentDir, "settings.json")
    private val modelsFile get() = File(agentDir, "models.json")
    private val appendSystemFile get() = File(piDir, "APPEND_SYSTEM.md")

    private val lenientJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    fun readUserConfig(): PiUserConfig = runCatching {
        if (!configFile.isFile) return PiUserConfig()
        lenientJson.decodeFromString<PiUserConfig>(configFile.readText())
    }.getOrDefault(PiUserConfig())

    fun readSnapshot(): PiConfigSnapshot {
        val cfg = readUserConfig()
        val modelsText = modelsFile.readTextOrDefault("{}\n")
        return PiConfigSnapshot(
            defaultProvider = cfg.defaultProvider.orEmpty(),
            defaultModel = cfg.defaultModel.orEmpty(),
            defaultThinkingLevel = cfg.defaultThinkingLevel ?: "medium",
            enabledModels = cfg.enabledModels.joinToString(", "),
            settingsText = settingsFile.readTextOrDefault(defaultSettingsText()),
            modelsText = modelsText,
            envText = JsonFormats.pretty.encodeToString(cfg.env),
            appendSystem = appendSystemFile.readTextOrDefault(defaultAppendSystemPrompt()),
            extraArgsText = cfg.extraArgs.joinToString("\n"),
            materializedDir = "${workspaceProotPath()}/.idadroid/pi-agent",
            modelCatalog = parseAgentModelCatalog(modelsText)
        )
    }

    fun saveSnapshot(snapshot: PiConfigSnapshot) {
        agentDir.mkdirs()
        piDir.mkdirs()
        parseObject(snapshot.settingsText.ifBlank { "{}" })
        parseObject(snapshot.modelsText.ifBlank { "{}" })
        val env = parseEnv(snapshot.envText)
        val config = PiUserConfig(
            defaultProvider = snapshot.defaultProvider.trim().takeIf { it.isNotBlank() },
            defaultModel = snapshot.defaultModel.trim().takeIf { it.isNotBlank() },
            defaultThinkingLevel = snapshot.defaultThinkingLevel.trim().takeIf { it.isNotBlank() } ?: "medium",
            enabledModels = snapshot.enabledModels.split(',').map { it.trim() }.filter { it.isNotBlank() },
            env = env,
            extraArgs = snapshot.extraArgsText.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
        )
        configFile.writeText(JsonFormats.pretty.encodeToString(config))
        settingsFile.writeText(snapshot.settingsText.ifBlank { "{}" }.trimEnd() + "\n")
        modelsFile.writeText(snapshot.modelsText.ifBlank { "{}" }.trimEnd() + "\n")
        appendSystemFile.writeText(snapshot.appendSystem.trimEnd() + "\n")
    }

    fun defaultProvider(): String? = readUserConfig().defaultProvider?.takeIf { it.isNotBlank() }
    fun defaultModel(): String? = readUserConfig().defaultModel?.takeIf { it.isNotBlank() }
    fun defaultThinking(): String? = readUserConfig().defaultThinkingLevel?.takeIf { it.isNotBlank() }

    fun runtimeEnvExports(): String {
        val cfg = readUserConfig()
        if (cfg.env.isEmpty()) return ""
        return cfg.env.entries.joinToString("\n") { (key, value) ->
            val safeKey = key.replace(Regex("[^A-Za-z0-9_]"), "_").takeIf { it.isNotBlank() } ?: "IDADROID_ENV"
            "export $safeKey=${dev.idadroid.proot.IdaProotRuntime.shellQuote(value)}"
        }
    }

    fun extraArgs(): List<String> = readUserConfig().extraArgs

    private fun parseObject(text: String): JsonObject {
        val parsed = JsonFormats.pretty.parseToJsonElement(text.ifBlank { "{}" })
        return parsed as? JsonObject ?: error("必须是 JSON object")
    }

    private fun parseEnv(text: String): Map<String, String> {
        val obj = parseObject(text)
        return obj.mapNotNull { (key, value) ->
            val str = (value as? JsonPrimitive)?.contentOrNull
                ?: (value as? JsonPrimitive)?.toString()?.trim('"')
                ?: return@mapNotNull null
            key to str
        }.toMap()
    }

    private fun File.readTextOrDefault(default: String): String = runCatching {
        if (isFile) readText() else default
    }.getOrDefault(default)

    private fun defaultSettingsText(): String = """
        {
          "quietStartup": true,
          "enableInstallTelemetry": false,
          "sessionDir": "${workspaceProotPath()}/.pi-sessions",
          "compaction": {
            "enabled": true,
            "reserveTokens": 16384,
            "keepRecentTokens": 20000
          },
          "retry": {
            "enabled": true,
            "maxRetries": 3,
            "baseDelayMs": 2000,
            "maxDelayMs": 60000
          },
          "steeringMode": "one-at-a-time",
          "followUpMode": "one-at-a-time"
        }
    """.trimIndent() + "\n"

    private fun defaultAppendSystemPrompt(): String = defaultSystemAppendPrompt(
        workspacePath = settings.envSettings.value.workspacePath.ifBlank { "/root/pi_workspace" }
    )
}

/** Single source of truth for the default APPEND_SYSTEM.md content. */
fun defaultSystemAppendPrompt(workspacePath: String = "/root/pi_workspace"): String = """
# IDAdroid — AI 逆向工程助手环境

## 你的身份
你是一个运行在 IDAdroid proot rootfs 内的 AI 逆向工程助手。你拥有完整的 IDA Pro 9.3、ida-mcp、jadx、Python 和 Node.js 工具链，可以自主操作 IDA 进行二进制分析。

## 环境信息
- 工作目录: $workspacePath
- IDA 主目录: /root/ida-pro-9.3
- ida-mcp 入口: /root/ida-pro-9.3/ida-mcp
- ida-mcp 使用文档: /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md
- Android 附件: $workspacePath/.upload
- MCP 文件传输: /root/.mcp-transfer (独立于工作区，专门给 MCP 快速打开文件)
- pi 会话: $workspacePath/.pi-sessions

## 工作准则

### 逆向分析
1. 始终先阅读 IDA_MCP_MCPC_USAGE.md，再通过 mcpc 调用 ida-mcp 操作 IDA
2. 分析时注重逻辑理解和证据链：每个结论都应基于反汇编/反编译的具体证据
3. 对关键函数、字符串、交叉引用做好记录，形成结构化分析笔记
4. 遇到加壳/混淆时，先尝试识别保护方案，再选择对应的脱壳/去混淆策略

### 任务适应性
- **CTF 逆向**: 解题并输出 writeup，同时可基于解题过程设计新挑战
- **漏洞分析**: 识别潜在漏洞类型，评估危害等级，给出修复建议
- **恶意软件分析**: 提取 IOC、行为特征、网络通信指标
- **通用逆向**: 准确描述程序逻辑、数据结构、关键算法
- **CTF 出题**: 基于解题经验设计合理的 RE 挑战，生成题目描述、解题结果和 writeup

### 工具使用
- 优先使用 mcpc + ida-mcp 进行 IDA 自动化操作
- 使用 idadroid-file 桥接 Android 主机文件传输
- 如需 Python，使用虚拟环境；可主动安装缺失依赖
- jadx、python、npm 均已安装

### 文件传输桥 (idadroid-file)
MCP 传输目录独立于工作区，专门用于快速把外部文件传进容器供 MCP/IDA 打开。
别名: `alias idadroid-file='$workspacePath/.idadroid/scripts/idadroid-file.sh'`
- `idadroid-file list` — 列出所有已上传文件
- `idadroid-file find <name>` — 查找文件路径（模糊匹配）
- `idadroid-file open <name>` — **自动搜索主机→传输进容器→mcpc打开**（一步到位）
- `idadroid-file latest` — 查看最近上传的文件
- `idadroid-file path <name>` — find 的别名

`idadroid-file open` 的工作流程:
1. 先在 `/root/.mcp-transfer/` 中查找已上传的文件
2. 没找到则通过 HTTP bridge 在 Android 主机端搜索（/sdcard, Download 等）
3. 主机端找到后自动传输进容器，然后用 `mcpc call open_file` 打开
4. 主机端也没找到则推荐最近上传的文件

agent 只需提供文件名，idadroid-file 会自动完成搜索、传输、打开全流程。

### 安全约束
- **不要删除**工作区以外的任何文件
- **不要修改** /sdcard/ 下的文件（如需要，先复制到当前工作区）
- 每个 CTF 挑战/分析任务在工作区下建独立子目录

## 深度索引模式 (Deep Index Mode)
当用户开启深度索引模式时，可通过 `$workspacePath/.idadroid/scripts/deep-index.sh` 使用统一工具链。
它整合了 CodeGraph（代码图谱）、ECC（代码地图 + 安全 + onboarding）和 codebase-memory-mcp（持久记忆）:
    alias deep-index='$workspacePath/.idadroid/scripts/deep-index.sh'
运行 `deep-index help` 查看完整命令列表。典型工作流:
  deep-index index $workspacePath/<challenge>
  deep-index codemap $workspacePath/<challenge>
  deep-index symbols $workspacePath/<challenge>
  deep-index security $workspacePath/<challenge>
  deep-index memory-store <key> <insight>
深度索引模式开启时，优先使用这些结构化工具而非原始 grep/find。

## 输出规范
- 分析结论需附带证据（函数地址、反编译片段等）
- 标记不确定性：对推测性结论使用"可能/疑似"等措辞
- 关键发现用 **加粗** 标注
- 长报告分段，使用清晰的 markdown 标题层级

## 智能工作流
当用户发送模糊请求时，按以下策略自适应:
1. **分析请求类型**: 判断是 CTF 解题、漏洞分析、恶意软件分析、通用逆向还是学习咨询
2. **选择工具链**: 简单查询用 mcpc 直接操作 IDA；复杂分析启用深度索引模式
3. **渐进式深入**: 从宏观（文件类型、架构、入口）到微观（关键函数、算法、数据结构）
4. **主动建议**: 发现关键信息时主动提示用户可能的价值和后续方向
5. **错误恢复**: 工具调用失败时尝试替代方案，不轻易中断分析流程
6. **上下文记忆**: 同一会话中记住之前的分析结论，避免重复劳动
""".trimIndent() + "\n"
