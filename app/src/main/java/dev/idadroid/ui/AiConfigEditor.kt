package dev.idadroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.ConfigExport
import dev.idadroid.agent.EndpointCompleter
import dev.idadroid.agent.PiConfigSnapshot
import dev.idadroid.agent.TestResult
import dev.idadroid.agent.parseAgentModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─── Provider catalog ──────────────────────────────────────────────────────────

private data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val envKey: String,
    val color: Color,
    val description: String
)

private val PROVIDER_PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY", Color(0xFF10A37F), "GPT-4o / o1 / o3 系列"),
    ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com", "ANTHROPIC_API_KEY", Color(0xFFD97757), "Claude 3.5 / 3.7 / 4 系列"),
    ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com", "DEEPSEEK_API_KEY", Color(0xFF4D6BFE), "DeepSeek-V3 / R1 系列"),
    ProviderPreset("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "GOOGLE_API_KEY", Color(0xFF4285F4), "Gemini 2.0 / 2.5 系列"),
    ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", Color(0xFF8B5CF6), "聚合 300+ 模型"),
    ProviderPreset("moonshot", "Moonshot KIMI", "https://api.moonshot.cn/v1", "MOONSHOT_API_KEY", Color(0xFF1A1A2E), "Kimi K1.5 / K2 系列"),
    ProviderPreset("dashscope", "阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", Color(0xFF615CED), "Qwen 系列"),
    ProviderPreset("volcengine", "火山引擎豆包", "https://ark.cn-beijing.volces.com/api/v3", "ARK_API_KEY", Color(0xFF1664FF), "Doubao 系列"),
    ProviderPreset("baidu", "百度千帆", "https://qianfan.baidubce.com/v2", "BAIDU_API_KEY", Color(0xFF2932E1), "ERNIE 系列"),
    ProviderPreset("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", "HUNYUAN_API_KEY", Color(0xFF0053E0), "Hunyuan 系列"),
    ProviderPreset("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "SILICONFLOW_API_KEY", Color(0xFF00B4A6), "硅基流动聚合平台"),
    ProviderPreset("mistral", "Mistral AI", "https://api.mistral.ai/v1", "MISTRAL_API_KEY", Color(0xFFFF5200), "Mistral / Codestral 系列"),
    ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY", Color(0xFFF55036), "超低延迟推理"),
    ProviderPreset("xai", "xAI Grok", "https://api.x.ai/v1", "XAI_API_KEY", Color(0xFF000000), "Grok 系列"),
    ProviderPreset("together", "Together AI", "https://api.together.xyz/v1", "TOGETHER_API_KEY", Color(0xFF0F6FFF), "开源模型聚合"),
    ProviderPreset("openai-generic", "自定义 (OpenAI 兼容)", "", "OPENAI_API_KEY", Color(0xFF6B7280), "手动填写 Base URL，使用 OpenAI 兼容协议")
)

private val MODEL_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "openai" to listOf("gpt-4o", "gpt-4o-mini", "o1", "o1-mini", "o3", "o3-mini", "gpt-4.1", "gpt-4.1-mini"),
    "anthropic" to listOf("claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022"),
    "deepseek" to listOf("deepseek-chat", "deepseek-reasoner"),
    "google" to listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash", "gemini-2.0-flash-lite"),
    "openrouter" to listOf("anthropic/claude-sonnet-4", "openai/gpt-4o", "google/gemini-2.5-flash", "deepseek/deepseek-chat", "x-ai/grok-3"),
    "moonshot" to listOf("moonshot-v1-128k", "moonshot-v1-32k", "moonshot-v1-8k", "kimi-thinking-preview"),
    "dashscope" to listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long", "qwen3-235b-a22b"),
    "volcengine" to listOf("doubao-1-5-pro-256k", "doubao-1-5-pro-32k", "doubao-1-5-lite-32k", "deepseek-r1-250120"),
    "baidu" to listOf("ernie-4.0-8k-latest", "ernie-4.0-turbo-8k", "ernie-speed-128k", "deepseek-r1"),
    "hunyuan" to listOf("hunyuan-turbos", "hunyuan-standard", "hunyuan-lite", "hunyuan-large"),
    "siliconflow" to listOf("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen2.5-72B-Instruct", "meta-llama/Llama-3.3-70B-Instruct"),
    "mistral" to listOf("mistral-large-latest", "mistral-small-latest", "codestral-latest", "open-mixtral-8x22b"),
    "groq" to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "deepseek-r1-distill-llama-70b", "mixtral-8x7b-32768"),
    "xai" to listOf("grok-3", "grok-3-mini", "grok-2-vision"),
    "together" to listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo", "deepseek-ai/DeepSeek-V3")
)

private fun presetById(id: String): ProviderPreset? = PROVIDER_PRESETS.firstOrNull { it.id == id }

// ─── Data classes for JSON parsing ─────────────────────────────────────────────

private data class ModelConfig(val id: String, val provider: String, val name: String)
private data class ProviderConfig(
    val id: String, val displayName: String, val baseUrl: String,
    val envKey: String, val color: Color, val apiKey: String, val models: List<ModelConfig>
)
private data class ParsedConfig(
    val providers: List<ProviderConfig>, val models: List<ModelConfig>,
    val env: Map<String, String>, val parseError: String?
)

private fun parseConfig(modelsText: String, envText: String): ParsedConfig {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    return try {
        val modelsObj = if (modelsText.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(modelsText).jsonObject
        val envObj = if (envText.isBlank()) emptyMap() else json.parseToJsonElement(envText).let { (it as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap() }
        val providersList = mutableListOf<ProviderConfig>()
        val modelsList = mutableListOf<ModelConfig>()
        val providersObj = modelsObj["providers"] as? JsonObject ?: JsonObject(emptyMap())
        providersObj.forEach { (providerId, element) ->
            val id = providerId.trim()
            if (id.isBlank()) return@forEach
            val obj = element as? JsonObject ?: JsonObject(emptyMap())
            val baseUrl = obj["baseURL"]?.jsonPrimitive?.contentOrNull ?: obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: ""
            val envKey = obj["envKey"]?.jsonPrimitive?.contentOrNull ?: "${id.uppercase()}_API_KEY"
            val apiKey = envObj[envKey] ?: ""
            val preset = presetById(id)
            val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: preset?.displayName ?: id
            val color = preset?.color ?: Color(0xFF6B7280)
            val providerModels = (obj["models"] as? JsonArray)?.mapNotNull { item ->
                val modelObj = item as? JsonObject ?: return@mapNotNull null
                val mid = modelObj["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mname = modelObj["name"]?.jsonPrimitive?.contentOrNull ?: mid
                ModelConfig(mid, id, mname)
            } ?: emptyList()
            providersList.add(ProviderConfig(id, displayName, baseUrl, envKey, color, apiKey, providerModels))
            modelsList.addAll(providerModels)
        }
        ParsedConfig(providersList, modelsList, envObj, null)
    } catch (e: Exception) {
        ParsedConfig(emptyList(), emptyList(), emptyMap(), e.message ?: "解析失败")
    }
}

private fun buildModelsJson(providers: List<ProviderConfig>): String {
    val json = Json { prettyPrint = true }
    // 格式: { "providers": { "openai": { "baseURL":"...", "envKey":"...", "models":[...] } } }
    val providersObj = buildJsonObject {
        put("providers", buildJsonObject {
            providers.forEach { p ->
                put(p.id, buildJsonObject {
                    if (p.baseUrl.isNotBlank()) put("baseURL", JsonPrimitive(p.baseUrl))
                    put("envKey", JsonPrimitive(p.envKey))
                    if (p.displayName != p.id) put("displayName", JsonPrimitive(p.displayName))
                    put("models", buildJsonArray {
                        p.models.forEach { m ->
                            add(buildJsonObject {
                                put("id", JsonPrimitive(m.id))
                                if (m.name.isNotBlank() && m.name != m.id) put("name", JsonPrimitive(m.name))
                            })
                        }
                    })
                })
            }
        })
    }
    return json.encodeToString(JsonObject.serializer(), providersObj)
}

private fun rebuildEnv(env: Map<String, String>, providers: List<ProviderConfig>): String {
    val merged = env.toMutableMap()
    providers.forEach { p -> if (p.apiKey.isNotBlank()) merged[p.envKey] = p.apiKey else merged.remove(p.envKey) }
    val obj = buildJsonObject { merged.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
}

private fun removeEnvKeys(env: Map<String, String>, keys: List<String>): String {
    val filtered = env.filterKeys { it !in keys }
    val obj = buildJsonObject { filtered.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
}

private fun validateApiKeyFormat(providerId: String, apiKey: String): Boolean {
    val key = apiKey.trim()
    if (key.length < 10) return false
    return when (providerId) {
        "openai" -> key.startsWith("sk-") && key.length >= 20
        "anthropic" -> key.startsWith("sk-ant-")
        "google" -> key.startsWith("AI") && key.length >= 20
        "openrouter" -> key.startsWith("sk-or-")
        "moonshot" -> key.startsWith("sk-")
        "groq" -> key.startsWith("gsk_")
        "xai" -> key.startsWith("xai-")
        "siliconflow" -> key.startsWith("sk-")
        else -> true
    }
}

// ─── Main UI: Operit-style simple form ────────────────────────────────────────
// Provider 下拉选择 → Base URL (自动填充) → API Key → Model Name
// 四行搞定，不做一堆卡片

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigEditor(
    snapshot: PiConfigSnapshot,
    onSnapshotChange: (PiConfigSnapshot) -> Unit
) {
    val parsed = remember(snapshot.modelsText, snapshot.envText) { parseConfig(snapshot.modelsText, snapshot.envText) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var showExportDialog by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var testingProvider by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProviderPicker by remember { mutableStateOf(false) }

    // 当前选中的 provider — 始终是 ProviderConfig 类型
    val currentProvider = remember(parsed.providers, snapshot.defaultProvider) {
        parsed.providers.firstOrNull { it.id == snapshot.defaultProvider }
            ?: parsed.providers.firstOrNull()
            ?: run {
                val p = PROVIDER_PRESETS.first()
                ProviderConfig(id = p.id, displayName = p.displayName, baseUrl = p.baseUrl, envKey = p.envKey, color = p.color, apiKey = "", models = emptyList())
            }
    }
    val preset = presetById(currentProvider.id) ?: PROVIDER_PRESETS.last()

    // 本地输入缓冲 — 避免每次按键都触发 snapshot 重算导致输入被覆盖
    var baseUrlInput by remember(currentProvider.id) { mutableStateOf(currentProvider.baseUrl) }
    var apiKeyInput by remember(currentProvider.id) { mutableStateOf(currentProvider.apiKey) }
    var modelInput by remember(currentProvider.id) { mutableStateOf(snapshot.defaultModel) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── 状态提示 ──
        parsed.parseError?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("配置解析失败：$err", color = MaterialTheme.colorScheme.onError, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }

        // ── Provider 选择 ──
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI 配置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                // Provider 选择 (点击打开搜索弹窗)
                OutlinedTextField(
                    value = currentProvider.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    leadingIcon = { Box(Modifier.size(24.dp).clip(CircleShape).background(currentProvider.color), contentAlignment = Alignment.Center) { Text(currentProvider.displayName.take(1), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) } },
                    trailingIcon = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { showProviderPicker = true }
                )

                // Base URL (自动填充，可编辑) — 本地缓冲，失焦写回
                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    label = { Text("Base URL") },
                    leadingIcon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        updateProvider(snapshot, parsed, currentProvider.copy(baseUrl = baseUrlInput), onSnapshotChange)
                    })
                )
                // 端点补全提示
                if (currentProvider.baseUrl.isNotBlank() && !currentProvider.baseUrl.endsWith("#")) {
                    val completed = EndpointCompleter.complete(currentProvider.baseUrl, currentProvider.id)
                    if (completed != currentProvider.baseUrl.trim().removeSuffix("/")) {
                        Text("→ 实际请求: $completed", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // API Key — 本地缓冲，失焦写回
                var showKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key") },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (showKey) "隐藏" else "显示") } },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        updateProvider(snapshot, parsed, currentProvider.copy(apiKey = apiKeyInput.trim()), onSnapshotChange)
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
                // Key 格式校验提示
                if (currentProvider.apiKey.isNotBlank() && currentProvider.id != "openai-generic") {
                    if (!validateApiKeyFormat(currentProvider.id, currentProvider.apiKey)) {
                        Text("⚠️ API Key 格式可能不正确", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                // Model Name + 选择按钮 — 本地缓冲，失焦写回
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            onSnapshotChange(snapshot.copy(defaultModel = modelInput.trim(), defaultProvider = currentProvider.id))
                        })
                    )
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Rounded.ExpandMore, contentDescription = "选择模型")
                    }
                }

                // 推荐模型 chips
                MODEL_SUGGESTIONS[currentProvider.id]?.let { suggestions ->
                    val unadded = suggestions
                    if (unadded.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            unadded.take(5).forEach { modelId ->
                                AssistChip(
                                    onClick = {
                                        modelInput = modelId
                                        onSnapshotChange(snapshot.copy(defaultModel = modelId, defaultProvider = currentProvider.id))
                                    },
                                    label = { Text(modelId, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 操作按钮行
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            // 先把输入缓冲写回，再测试
                            val provider = currentProvider.copy(apiKey = apiKeyInput.trim(), baseUrl = baseUrlInput.trim())
                            updateProvider(snapshot, parsed, provider, onSnapshotChange)
                            testingProvider = currentProvider.id
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { testProviderConnection(provider) }
                                testingProvider = null
                                snackbarHost.showSnackbar(if (result.success) "✅ ${provider.displayName} 连接成功" else "❌ ${result.message}")
                            }
                        },
                        enabled = testingProvider == null && apiKeyInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testingProvider == currentProvider.id) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                        else { Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(6.dp)); Text("测试", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val provider = currentProvider.copy(apiKey = apiKeyInput.trim(), baseUrl = baseUrlInput.trim())
                            updateProvider(snapshot, parsed, provider, onSnapshotChange)
                            testingProvider = "${currentProvider.id}_fetch"
                            scope.launch {
                                val models = withContext(Dispatchers.IO) { fetchAvailableModels(provider) }
                                testingProvider = null
                                if (models.isNotEmpty()) {
                                    val updated = provider.copy(models = models.map { ModelConfig(it, provider.id, it) })
                                    updateProvider(snapshot, parsed, updated, onSnapshotChange)
                                    snackbarHost.showSnackbar("已拉取 ${models.size} 个模型")
                                } else { snackbarHost.showSnackbar("拉取失败或未返回模型") }
                            }
                        },
                        enabled = testingProvider == null && apiKeyInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testingProvider == "${currentProvider.id}_fetch") { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                        else { Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(6.dp)); Text("拉取模型", fontSize = 12.sp)
                    }
                }

                // 导入/导出
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showExportDialog = exportConfig(snapshot) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("导出", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { showImportDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("导入", fontSize = 12.sp)
                    }
                }
            }
        }

        // ── 高级设置 (可折叠) ──
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("高级设置", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
        }
        AnimatedVisibility(showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Thinking Level
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Thinking", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("off", "minimal", "low", "medium", "high", "xhigh").forEach { level ->
                                val selected = snapshot.defaultThinkingLevel == level
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { onSnapshotChange(snapshot.copy(defaultThinkingLevel = level)) }
                                ) {
                                    Text(level, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
                // APPEND_SYSTEM.md
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("APPEND_SYSTEM.md", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = snapshot.appendSystem,
                            onValueChange = { onSnapshotChange(snapshot.copy(appendSystem = it)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }
                // Extra args
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("额外启动参数 (每行一个)", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = snapshot.extraArgsText,
                            onValueChange = { onSnapshotChange(snapshot.copy(extraArgsText = it)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }
                // Raw JSON
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("models.json (原始 JSON)", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = snapshot.modelsText,
                            onValueChange = { onSnapshotChange(snapshot.copy(modelsText = it, modelCatalog = parseAgentModelCatalog(it))) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHost) { Snackbar(it) }
    }

    // ── Model picker bottom sheet (搜索 + 完整列表) ──
    if (showModelPicker) {
        val sheetState = rememberModalBottomSheetState()
        var modelSearch by remember { mutableStateOf("") }
        // 合并已配置模型 + 推荐模型 + 已输入的模型
        val allModels = remember(currentProvider, modelSearch) {
            val configured = currentProvider.models.map { it.id }
            val suggested = MODEL_SUGGESTIONS[currentProvider.id] ?: emptyList()
            val combined = (configured + suggested).distinct()
            if (modelSearch.isBlank()) combined else combined.filter { it.contains(modelSearch, ignoreCase = true) }
        }
        ModalBottomSheet(onDismissRequest = { showModelPicker = false }, sheetState = sheetState) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择模型", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = modelSearch, onValueChange = { modelSearch = it },
                    placeholder = { Text("搜索或输入模型名...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { if (modelSearch.isNotEmpty()) IconButton(onClick = { modelSearch = "" }) { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) } },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                // 如果搜索的内容不在列表里，可以直接用它作为模型名
                if (modelSearch.isNotBlank() && allModels.none { it.equals(modelSearch, ignoreCase = true) }) {
                    Surface(modifier = Modifier.fillMaxWidth().clickable {
                        modelInput = modelSearch.trim()
                        onSnapshotChange(snapshot.copy(defaultModel = modelSearch.trim(), defaultProvider = currentProvider.id)); showModelPicker = false
                    }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("使用 \"$modelSearch\"", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allModels.size) { idx ->
                        val modelId = allModels[idx]
                        val isConfigured = currentProvider.models.any { it.id == modelId }
                        val isSelected = modelId == snapshot.defaultModel
                        Surface(modifier = Modifier.fillMaxWidth().clickable {
                            modelInput = modelId
                            onSnapshotChange(snapshot.copy(defaultModel = modelId, defaultProvider = currentProvider.id)); showModelPicker = false
                        }, shape = RoundedCornerShape(8.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(modelId, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (!isConfigured) Text("推荐", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ── Provider picker dialog (搜索 + 列表) ──
    if (showProviderPicker) {
        var providerSearch by remember { mutableStateOf("") }
        val filteredPresets = remember(providerSearch) {
            if (providerSearch.isEmpty()) PROVIDER_PRESETS
            else PROVIDER_PRESETS.filter { it.displayName.contains(providerSearch, ignoreCase = true) || it.id.contains(providerSearch, ignoreCase = true) || it.description.contains(providerSearch, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showProviderPicker = false },
            title = { Text("选择 Provider") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = providerSearch, onValueChange = { providerSearch = it },
                        placeholder = { Text("搜索 Provider...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { if (providerSearch.isNotEmpty()) IconButton(onClick = { providerSearch = "" }) { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filteredPresets.size) { idx ->
                            val p = filteredPresets[idx]
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { switchProvider(snapshot, parsed, p, onSnapshotChange); showProviderPicker = false },
                                shape = RoundedCornerShape(8.dp),
                                color = if (p.id == currentProvider.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(p.color), contentAlignment = Alignment.Center) { Text(p.displayName.take(1), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.displayName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(p.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (p.id == currentProvider.id) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderPicker = false }) { Text("取消") } }
        )
    }

    // ── Export dialog ──
    showExportDialog?.let { json ->
        AlertDialog(
            onDismissRequest = { showExportDialog = null },
            title = { Text("导出配置") },
            text = {
                Column {
                    Text("复制以下 JSON 保存备份:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        Text(json, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExportDialog = null }) { Text("关闭") } }
        )
    }

    // ── Import dialog ──
    if (showImportDialog) {
        var importText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入配置") },
            text = {
                Column {
                    Text("粘贴之前导出的 JSON:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText, onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = { TextButton(onClick = {
                parseImportedConfig(importText.trim())?.let { onSnapshotChange(it); scope.launch { snackbarHost.showSnackbar("配置导入成功") }; showImportDialog = false }
                ?: scope.launch { snackbarHost.showSnackbar("解析失败") }
            }) { Text("导入") } },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }
}

// ─── Helper: switch/update provider in config ──────────────────────────────────

private fun switchProvider(snapshot: PiConfigSnapshot, parsed: ParsedConfig, preset: ProviderPreset, onSnapshotChange: (PiConfigSnapshot) -> Unit) {
    val existing = parsed.providers.firstOrNull { it.id == preset.id }
    if (existing != null) {
        onSnapshotChange(snapshot.copy(defaultProvider = preset.id, defaultModel = existing.models.firstOrNull()?.id ?: ""))
    } else {
        // 新增 provider
        val newProvider = ProviderConfig(preset.id, preset.displayName, preset.baseUrl, preset.envKey, preset.color, "", emptyList())
        val newProviders = parsed.providers + newProvider
        val newJson = buildModelsJson(newProviders)
        onSnapshotChange(snapshot.copy(
            modelsText = newJson, envText = rebuildEnv(parsed.env, newProviders),
            defaultProvider = preset.id, defaultModel = "",
            modelCatalog = parseAgentModelCatalog(newJson)
        ))
    }
}

private fun updateProvider(snapshot: PiConfigSnapshot, parsed: ParsedConfig, updated: ProviderConfig, onSnapshotChange: (PiConfigSnapshot) -> Unit) {
    val newProviders = if (parsed.providers.any { it.id == updated.id }) parsed.providers.map { if (it.id == updated.id) updated else it } else listOf(updated)
    val newJson = buildModelsJson(newProviders)
    onSnapshotChange(snapshot.copy(
        modelsText = newJson, envText = rebuildEnv(parsed.env, newProviders),
        defaultProvider = updated.id, modelCatalog = parseAgentModelCatalog(newJson)
    ))
}

// ─── Connection testing ────────────────────────────────────────────────────────

private fun testProviderConnection(provider: ProviderConfig): TestResult {
    return try {
        when (provider.id) {
            "anthropic" -> testAnthropic(provider)
            "google" -> testGemini(provider)
            else -> testOpenAiStyle(provider)
        }
    } catch (e: java.net.UnknownHostException) { TestResult(false, -1, "无法解析主机名: ${e.message}") }
    catch (e: java.net.SocketTimeoutException) { TestResult(false, -1, "连接超时") }
    catch (e: Exception) { TestResult(false, -1, e.message ?: "未知错误") }
}

private fun testOpenAiStyle(provider: ProviderConfig): TestResult {
    val base = provider.baseUrl.trim().removeSuffix("/")
    val modelsUrl = when {
        base.contains("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
        base.endsWith("/v1") -> base + "/models"
        base.contains("/v1/") -> base.substringBefore("/v1/") + "/v1/models"
        else -> base + "/v1/models"
    }
    val conn = (java.net.URL(modelsUrl).openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
        setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
    }
    try {
        val code = conn.responseCode; val body = readBody(conn, code)
        return if (code in 200..299) TestResult(true, code, "连接成功", parseModels(body))
        else TestResult(false, code, parseError(body) ?: "HTTP $code")
    } finally {
        conn.disconnect()
    }
}

private fun testAnthropic(provider: ProviderConfig): TestResult {
    val base = provider.baseUrl.trim().removeSuffix("/")
    val url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
    val body = """{"model":"claude-3-5-haiku-20241022","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 15_000; doOutput = true
        setRequestProperty("x-api-key", provider.apiKey); setRequestProperty("anthropic-version", "2023-06-01")
        setRequestProperty("Content-Type", "application/json")
    }
    try {
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode; val resp = readBody(conn, code)
        return when {
            code in 200..299 -> TestResult(true, code, "连接成功")
            code == 400 -> TestResult(true, code, "连接成功（Key 有效，模型可能需要更新）")
            code == 401 || code == 403 -> TestResult(false, code, "API Key 无效")
            else -> TestResult(false, code, parseError(resp) ?: "HTTP $code")
        }
    } finally {
        conn.disconnect()
    }
}

private fun testGemini(provider: ProviderConfig): TestResult {
    val base = provider.baseUrl.trim().removeSuffix("/")
    val url = if (base.contains("/v1beta")) "$base/models?key=${provider.apiKey}" else "$base/v1beta/models?key=${provider.apiKey}"
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000 }
    try {
        val code = conn.responseCode; val body = readBody(conn, code)
        return if (code in 200..299) TestResult(true, code, "连接成功", parseGeminiModels(body))
        else TestResult(false, code, parseError(body) ?: "HTTP $code")
    } finally {
        conn.disconnect()
    }
}

private fun readBody(conn: java.net.HttpURLConnection, code: Int): String =
    if (code in 200..299) conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
    else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

private fun parseModels(body: String): List<String> = try {
    val obj = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    (obj["data"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }?.filter { it.isNotBlank() } ?: emptyList()
} catch (_: Exception) { emptyList() }

private fun parseGeminiModels(body: String): List<String> = try {
    val obj = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    (obj["models"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }?.filter { it.isNotBlank() } ?: emptyList()
} catch (_: Exception) { emptyList() }

private fun parseError(body: String): String? = try {
    val obj = Json.parseToJsonElement(body) as? JsonObject
    obj?.get("error")?.let { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull } ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
} catch (_: Exception) { null }

// ─── Model fetching ────────────────────────────────────────────────────────────

private fun fetchAvailableModels(provider: ProviderConfig): List<String> = try {
    when (provider.id) {
        "google" -> fetchGemini(provider)
        "anthropic" -> fetchAnthropic(provider)
        else -> fetchOpenAi(provider)
    }
} catch (_: Exception) { emptyList() }

private fun fetchOpenAi(provider: ProviderConfig): List<String> {
    val base = provider.baseUrl.trim().removeSuffix("/")
    val url = when { base.contains("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"; base.endsWith("/v1") -> base + "/models"; base.contains("/v1/") -> base.substringBefore("/v1/") + "/v1/models"; else -> base + "/v1/models" }
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Authorization", "Bearer ${provider.apiKey}") }
    try {
        if (conn.responseCode !in 200..299) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return parseModels(body)
    } finally {
        conn.disconnect()
    }
}

private fun fetchAnthropic(provider: ProviderConfig): List<String> {
    val base = provider.baseUrl.trim().removeSuffix("/").removeSuffix("/v1")
    val conn = (java.net.URL("$base/v1/models").openConnection() as java.net.HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("x-api-key", provider.apiKey); setRequestProperty("anthropic-version", "2023-06-01") }
    try {
        if (conn.responseCode !in 200..299) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return parseModels(body)
    } finally {
        conn.disconnect()
    }
}

private fun fetchGemini(provider: ProviderConfig): List<String> {
    val base = provider.baseUrl.trim().removeSuffix("/")
    val url = if (base.contains("/v1beta")) "$base/models?key=${provider.apiKey}" else "$base/v1beta/models?key=${provider.apiKey}"
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000 }
    try {
        if (conn.responseCode !in 200..299) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return parseGeminiModels(body)
    } finally {
        conn.disconnect()
    }
}

// ─── Config export / import ────────────────────────────────────────────────────

private fun exportConfig(snapshot: PiConfigSnapshot): String {
    val export = ConfigExport(
        defaultProvider = snapshot.defaultProvider, defaultModel = snapshot.defaultModel,
        defaultThinkingLevel = snapshot.defaultThinkingLevel, enabledModels = snapshot.enabledModels,
        settingsText = snapshot.settingsText, modelsText = snapshot.modelsText, envText = snapshot.envText,
        appendSystem = snapshot.appendSystem, extraArgsText = snapshot.extraArgsText,
        exportedAt = System.currentTimeMillis()
    )
    return Json { prettyPrint = true; encodeDefaults = true }.encodeToString(ConfigExport.serializer(), export)
}

private fun parseImportedConfig(json: String): PiConfigSnapshot? = try {
    val export = Json { ignoreUnknownKeys = true }.decodeFromString(ConfigExport.serializer(), json)
    PiConfigSnapshot(
        defaultProvider = export.defaultProvider, defaultModel = export.defaultModel,
        defaultThinkingLevel = export.defaultThinkingLevel.ifBlank { "medium" },
        enabledModels = export.enabledModels, settingsText = export.settingsText.ifBlank { "{}" },
        modelsText = export.modelsText.ifBlank { "{}" }, envText = export.envText.ifBlank { "{}" },
        appendSystem = export.appendSystem, extraArgsText = export.extraArgsText,
        modelCatalog = parseAgentModelCatalog(export.modelsText.ifBlank { "{}" })
    )
} catch (_: Exception) { null }
