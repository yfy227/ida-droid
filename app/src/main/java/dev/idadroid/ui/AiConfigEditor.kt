package dev.idadroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.idadroid.agent.PiConfigSnapshot
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
// Each preset carries a display name, brand color, default base URL, and the
// env-var name that the Pi agent reads for the API key. Inspired by Operit's
// ApiProviderConfigs — one tap pre-fills everything so the user only pastes a key.

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
    ProviderPreset("custom", "自定义", "", "CUSTOM_API_KEY", Color(0xFF6B7280), "手动填写 Base URL")
)

/** 每个 Provider 的常用模型建议列表，帮助用户快速添加而不用手打 ID。 */
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

/**
 * A human-friendly visual editor for the Pi agent's models.json / env config.
 *
 * Design principles (learned from Operit's ModelApiSettingsSection):
 *  - Providers shown as a clean list with brand-colored avatars and display names
 *  - One-tap presets via a dialog (not cramped buttons)
 *  - Simple form per provider: API Key (with show/hide) + Base URL + Model IDs
 *  - Technical details (envKey) auto-generated, hidden from the user
 *  - Raw JSON still available behind a toggle for power users
 */
@Composable
fun AiConfigEditor(
    snapshot: PiConfigSnapshot,
    onSnapshotChange: (PiConfigSnapshot) -> Unit
) {
    val parsed = remember(snapshot.modelsText, snapshot.envText) {
        parseConfig(snapshot.modelsText, snapshot.envText)
    }
    var editingProvider by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var testingProviderId by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }

    // IMPORTANT: plain Column, NOT LazyColumn — this composable is embedded
    // inside a LazyColumn item in PiSettingsTab. A nested LazyColumn crashes
    // with "infinity maximum height constraints".
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConfigStatusBanner(parsed)

        // ── Provider list ──
        parsed.providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                isExpanded = editingProvider == provider.id,
                onToggleExpand = { editingProvider = if (editingProvider == provider.id) null else provider.id },
                onUpdate = { updated ->
                    val newProviders = parsed.providers.map { if (it.id == provider.id) updated else it }
                    val newJson = buildModelsJson(newProviders)
                    onSnapshotChange(snapshot.copy(
                        modelsText = newJson,
                        envText = rebuildEnv(parsed.env, newProviders),
                        modelCatalog = parseAgentModelCatalog(newJson)
                    ))
                },
                onDelete = {
                    val newProviders = parsed.providers.filter { it.id != provider.id }
                    val newJson = buildModelsJson(newProviders)
                    onSnapshotChange(snapshot.copy(
                        modelsText = newJson,
                        envText = removeEnvKeys(parsed.env, listOf(provider.envKey)),
                        modelCatalog = parseAgentModelCatalog(newJson)
                    ))
                    if (editingProvider == provider.id) editingProvider = null
                },
                onTestConnection = {
                    testingProviderId = provider.id
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            testProviderConnection(provider)
                        }
                        testingProviderId = null
                        if (result.success) {
                            val modelInfo = if (result.availableModels.isNotEmpty()) {
                                "，发现 ${result.availableModels.size} 个可用模型"
                            } else ""
                            snackbarHost.showSnackbar(
                                message = "✅ ${provider.displayName} 连接成功$modelInfo",
                                duration = androidx.compose.material3.SnackbarDuration.Short
                            )
                        } else {
                            snackbarHost.showSnackbar(
                                message = "❌ ${provider.displayName}: ${result.message}",
                                duration = androidx.compose.material3.SnackbarDuration.Long
                            )
                        }
                    }
                },
                onFetchModels = {
                    testingProviderId = "${provider.id}_fetch"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            testProviderConnection(provider)
                        }
                        testingProviderId = null
                        if (result.success && result.availableModels.isNotEmpty()) {
                            val existing = provider.models.map { it.id }.toSet()
                            val newModels = result.availableModels.filter { it !in existing }
                            if (newModels.isNotEmpty()) {
                                val updated = provider.copy(models = provider.models + newModels.map { ModelConfig(it, provider.id, it) })
                                val newProviders = parsed.providers.map { if (it.id == provider.id) updated else it }
                                val newJson = buildModelsJson(newProviders)
                                onSnapshotChange(snapshot.copy(
                                    modelsText = newJson,
                                    envText = rebuildEnv(parsed.env, newProviders),
                                    modelCatalog = parseAgentModelCatalog(newJson)
                                ))
                                snackbarHost.showSnackbar("已拉取 ${newModels.size} 个新模型")
                            } else {
                                snackbarHost.showSnackbar("没有新模型可添加")
                            }
                        } else if (!result.success) {
                            snackbarHost.showSnackbar("拉取失败: ${result.message}")
                        } else {
                            snackbarHost.showSnackbar("API 未返回模型列表")
                        }
                    }
                },
                isTesting = testingProviderId == provider.id || testingProviderId == "${provider.id}_fetch"
            )
        }

        // ── Add provider + Config actions row ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加 Provider")
            }
            OutlinedButton(
                onClick = {
                    exportText = exportConfig(snapshot)
                }
            ) {
                Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导出")
            }
            OutlinedButton(
                onClick = { showImportDialog = true }
            ) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入")
            }
        }

        // Snackbar host for test/fetch/import/export feedback
        SnackbarHost(hostState = snackbarHost) { data ->
            Snackbar(data)
        }

        // ── Advanced: raw JSON ──
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { showRawJson = !showRawJson }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (showRawJson) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("高级：直接编辑 JSON", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            AnimatedVisibility(showRawJson, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = snapshot.modelsText,
                        onValueChange = { newText -> onSnapshotChange(snapshot.copy(modelsText = newText, modelCatalog = parseAgentModelCatalog(newText))) },
                        label = { Text("models.json") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        maxLines = 20
                    )
                    OutlinedTextField(
                        value = snapshot.envText,
                        onValueChange = { newText -> onSnapshotChange(snapshot.copy(envText = newText)) },
                        label = { Text("env (JSON)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        maxLines = 12
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            existingIds = parsed.providers.map { it.id },
            onDismiss = { showAddDialog = false },
            onPick = { preset ->
                val newProvider = ProviderConfig(
                    id = preset.id,
                    displayName = preset.displayName,
                    baseUrl = preset.baseUrl,
                    envKey = preset.envKey,
                    color = preset.color,
                    apiKey = "",
                    models = emptyList()
                )
                val newProviders = parsed.providers + newProvider
                val newJson = buildModelsJson(newProviders)
                onSnapshotChange(snapshot.copy(
                    modelsText = newJson,
                    envText = rebuildEnv(parsed.env, newProviders),
                    modelCatalog = parseAgentModelCatalog(newJson)
                ))
                showAddDialog = false
                editingProvider = preset.id
            }
        )
    }

    // ── Export dialog ──
    exportText?.let { json ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("导出配置") },
            text = {
                Column {
                    Text("复制以下 JSON 保存备份:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    ) {
                        Text(
                            json,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exportText = null }) { Text("关闭") } }
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
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val imported = parseImportedConfig(importText.trim())
                    if (imported != null) {
                        onSnapshotChange(imported)
                        scope.launch { snackbarHost.showSnackbar("配置导入成功") }
                        showImportDialog = false
                    } else {
                        scope.launch { snackbarHost.showSnackbar("配置解析失败，格式不正确") }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }
}

// ─── Status banner ─────────────────────────────────────────────────────────────

@Composable
private fun ConfigStatusBanner(parsed: ParsedConfig) {
    val (message, isError) = when {
        parsed.parseError != null -> "配置解析失败：${parsed.parseError}" to true
        parsed.providers.isEmpty() -> "尚未配置 Provider，点击「添加 Provider」开始" to true
        parsed.models.isEmpty() -> "已配置 ${parsed.providers.size} 个 Provider，但还没有模型" to true
        else -> "已配置 ${parsed.providers.size} 个 Provider / ${parsed.models.size} 个模型" to false
    }
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Text(message, modifier = Modifier.weight(1f), color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        }
    }
}

// ─── Provider card ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (ProviderConfig) -> Unit,
    onDelete: () -> Unit,
    onTestConnection: () -> Unit = {},
    onFetchModels: () -> Unit = {},
    isTesting: Boolean = false
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        // Header — always visible
        Row(
            Modifier.fillMaxWidth().clickable { onToggleExpand() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand-colored avatar with first letter
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(provider.color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    provider.displayName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    buildString {
                        append(if (provider.models.isEmpty()) "暂无模型" else "${provider.models.size} 个模型")
                        if (provider.apiKey.isNotBlank()) append(" · Key 已配置")
                        else append(" · 未配置 Key")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }

        // Expanded form
        AnimatedVisibility(isExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HorizontalDivider()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Base URL
                OutlinedTextField(
                    value = provider.baseUrl,
                    onValueChange = { onUpdate(provider.copy(baseUrl = it)) },
                    label = { Text("Base URL") },
                    leadingIcon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key with show/hide
                var showKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = provider.apiKey,
                    onValueChange = { onUpdate(provider.copy(apiKey = it)) },
                    label = { Text("API Key") },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (showKey) "隐藏" else "显示")
                        }
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Models — chip-based editor (like Operit's tag UI): each model
                // shows as a removable chip, with an inline add field at the end.
                Text("模型列表", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (provider.models.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        provider.models.forEach { model ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(model.id, fontSize = 12.sp) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "移除",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable {
                                                val newModels = provider.models.filterNot { it.id == model.id }
                                                onUpdate(provider.copy(models = newModels))
                                            }
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                        }
                    }
                }
                var newModelText by remember(provider.id) { mutableStateOf("") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newModelText,
                        onValueChange = { newModelText = it },
                        label = { Text("添加模型 ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val id = newModelText.trim()
                            if (id.isNotBlank() && provider.models.none { it.id == id }) {
                                onUpdate(provider.copy(models = provider.models + ModelConfig(id, provider.id, id)))
                                newModelText = ""
                            }
                        })
                    )
                    IconButton(onClick = {
                        val id = newModelText.trim()
                        if (id.isNotBlank() && provider.models.none { it.id == id }) {
                            onUpdate(provider.copy(models = provider.models + ModelConfig(id, provider.id, id)))
                            newModelText = ""
                        }
                    }) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加模型")
                    }
                }

                // ── Smart model suggestions ──
                MODEL_SUGGESTIONS[provider.id]?.let { suggestions ->
                    val unadded = suggestions.filter { s -> provider.models.none { it.id == s } }
                    if (unadded.isNotEmpty()) {
                        Text("推荐模型（点击添加）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            unadded.forEach { modelId ->
                                AssistChip(
                                    onClick = {
                                        onUpdate(provider.copy(models = provider.models + ModelConfig(modelId, provider.id, modelId)))
                                    },
                                    label = { Text(modelId, fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }
                }

                // ── API Key validation hint ──
                if (provider.apiKey.isNotBlank() && provider.id != "custom") {
                    val keyValid = validateApiKeyFormat(provider.id, provider.apiKey)
                    if (!keyValid) {
                        Text(
                            "⚠️ API Key 格式可能不正确，请检查是否复制完整",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // ── Test & Fetch buttons ──
                if (provider.apiKey.isNotBlank() && provider.baseUrl.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !isTesting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTesting) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("测试连接", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onFetchModels,
                            enabled = !isTesting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTesting) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("拉取模型", fontSize = 12.sp)
                        }
                    }
                }

                // Delete
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDelete,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}

// ─── Add provider dialog ───────────────────────────────────────────────────────

@Composable
private fun AddProviderDialog(
    existingIds: List<String>,
    onDismiss: () -> Unit,
    onPick: (ProviderPreset) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("选择 Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PROVIDER_PRESETS.forEach { preset ->
                    val alreadyAdded = preset.id != "custom" && existingIds.contains(preset.id)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !alreadyAdded) { onPick(preset) },
                        color = if (alreadyAdded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(preset.color),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    preset.displayName.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(preset.displayName, fontWeight = FontWeight.Medium)
                                Text(preset.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (alreadyAdded) {
                                Text("已添加", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    )
}

// ─── Data classes ──────────────────────────────────────────────────────────────

private data class ModelConfig(
    val id: String,
    val provider: String,
    val name: String
)

private data class ProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val envKey: String,
    val color: Color,
    val apiKey: String,
    val models: List<ModelConfig>
)

private data class ParsedConfig(
    val providers: List<ProviderConfig>,
    val models: List<ModelConfig>,
    val env: Map<String, String>,
    val parseError: String?
)

// ─── Parsing (must match parseAgentModelCatalog format) ────────────────────────
// Format: { "providers": { "openai": { "baseURL":"...", "envKey":"...", "models":[...] } } }

private fun parseConfig(modelsText: String, envText: String): ParsedConfig {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    return try {
        val modelsObj = if (modelsText.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(modelsText).jsonObject
        val envObj = if (envText.isBlank()) emptyMap() else json.parseToJsonElement(envText).let { it as? JsonObject }?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()

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
    val providersObj = buildJsonObject {
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
    }
    return json.encodeToString(JsonObject.serializer(), providersObj)
}

private fun rebuildEnv(env: Map<String, String>, providers: List<ProviderConfig>): String {
    val json = Json { prettyPrint = true }
    val merged = env.toMutableMap()
    providers.forEach { p ->
        if (p.apiKey.isNotBlank()) merged[p.envKey] = p.apiKey
        else merged.remove(p.envKey)
    }
    val obj = buildJsonObject {
        merged.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }
    return json.encodeToString(JsonObject.serializer(), obj)
}

private fun removeEnvKeys(env: Map<String, String>, keys: List<String>): String {
    val json = Json { prettyPrint = true }
    val filtered = env.filterKeys { it !in keys }
    val obj = buildJsonObject {
        filtered.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }
    return json.encodeToString(JsonObject.serializer(), obj)
}

/**
 * 轻量级 API Key 格式校验，仅检查常见前缀和长度。
 * 不做网络请求，只给用户即时提示。
 */
private fun validateApiKeyFormat(providerId: String, apiKey: String): Boolean {
    val key = apiKey.trim()
    if (key.length < 10) return false
    return when (providerId) {
        "openai" -> key.startsWith("sk-") && key.length >= 20
        "anthropic" -> key.startsWith("sk-ant-")
        "deepseek" -> key.length >= 20
        "google" -> key.startsWith("AI") && key.length >= 20
        "openrouter" -> key.startsWith("sk-or-")
        "moonshot" -> key.startsWith("sk-")
        "dashscope" -> key.length >= 20
        "volcengine" -> key.length >= 20
        "baidu" -> key.length >= 20
        "hunyuan" -> key.length >= 20
        "siliconflow" -> key.startsWith("sk-")
        "mistral" -> key.length >= 20
        "groq" -> key.startsWith("gsk_")
        "xai" -> key.startsWith("xai-")
        "together" -> key.length >= 20
        else -> true // custom and unknown providers: skip validation
    }
}

// ─── Connection testing & model fetching ──────────────────────────────────────

private data class TestResult(
    val success: Boolean,
    val httpCode: Int,
    val message: String,
    val availableModels: List<String> = emptyList()
)

/**
 * 测试 Provider 连接：向 /models 端点发送 GET 请求。
 * 借鉴自 Operit 的 ModelConfigConnectionTester。
 */
private fun testProviderConnection(provider: ProviderConfig): TestResult {
    return try {
        val baseUrl = dev.idadroid.agent.EndpointCompleter.complete(provider.baseUrl, provider.id)
        // 尝试 /models 端点（OpenAI 风格）
        val modelsUrl = if (baseUrl.contains("/chat/completions")) {
            baseUrl.removeSuffix("/chat/completions") + "/models"
        } else if (baseUrl.contains("/v1")) {
            baseUrl.removeSuffix("/") + "/models"
        } else {
            baseUrl.removeSuffix("/") + "/v1/models"
        }

        val url = java.net.URL(modelsUrl)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            // Anthropic 需要特殊的 header
            if (provider.id == "anthropic") {
                setRequestProperty("x-api-key", provider.apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
        }

        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }

        if (code in 200..299) {
            val models = parseModelsResponse(body)
            TestResult(true, code, "连接成功", models)
        } else {
            val errMsg = parseErrorMessage(body) ?: "HTTP $code"
            TestResult(false, code, errMsg)
        }
    } catch (e: java.net.UnknownHostException) {
        TestResult(false, -1, "无法解析主机名: ${e.message}")
    } catch (e: java.net.SocketTimeoutException) {
        TestResult(false, -1, "连接超时")
    } catch (e: Exception) {
        TestResult(false, -1, e.message ?: "未知错误")
    }
}

private fun parseModelsResponse(body: String): List<String> {
    return try {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body)
        val obj = parsed as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val data = obj["data"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        data.mapNotNull { item ->
            (item as? kotlinx.serialization.json.JsonObject)?.let {
                (it["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }
        }.filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseErrorMessage(body: String): String? = try {
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
    parsed?.get("error")?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("message")?.let { m -> (m as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } }
        ?: parsed?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
} catch (_: Exception) { null }

// ─── Config export / import ───────────────────────────────────────────────────

private fun exportConfig(snapshot: PiConfigSnapshot): String {
    val export = dev.idadroid.agent.ConfigExport(
        defaultProvider = snapshot.defaultProvider,
        defaultModel = snapshot.defaultModel,
        defaultThinkingLevel = snapshot.defaultThinkingLevel,
        enabledModels = snapshot.enabledModels,
        settingsText = snapshot.settingsText,
        modelsText = snapshot.modelsText,
        envText = snapshot.envText,
        appendSystem = snapshot.appendSystem,
        extraArgsText = snapshot.extraArgsText,
        exportedAt = System.currentTimeMillis()
    )
    return kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }
        .encodeToString(dev.idadroid.agent.ConfigExport.serializer(), export)
}

private fun parseImportedConfig(json: String): PiConfigSnapshot? {
    return try {
        val export = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(dev.idadroid.agent.ConfigExport.serializer(), json)
        PiConfigSnapshot(
            defaultProvider = export.defaultProvider,
            defaultModel = export.defaultModel,
            defaultThinkingLevel = export.defaultThinkingLevel.ifBlank { "medium" },
            enabledModels = export.enabledModels,
            settingsText = export.settingsText.ifBlank { "{}" },
            modelsText = export.modelsText.ifBlank { "{}" },
            envText = export.envText.ifBlank { "{}" },
            appendSystem = export.appendSystem,
            extraArgsText = export.extraArgsText,
            modelCatalog = dev.idadroid.agent.parseAgentModelCatalog(export.modelsText.ifBlank { "{}" })
        )
    } catch (_: Exception) {
        null
    }
}
