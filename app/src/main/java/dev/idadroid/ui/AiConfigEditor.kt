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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.PiConfigSnapshot
import dev.idadroid.agent.parseAgentModelCatalog
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
    ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", Color(0xFF8B5CF6), "聚合 300+ 模型"),
    ProviderPreset("moonshot", "Moonshot KIMI", "https://api.moonshot.cn/v1", "MOONSHOT_API_KEY", Color(0xFF1A1A2E), "Kimi K1.5 / K2 系列"),
    ProviderPreset("dashscope", "阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", Color(0xFF615CED), "Qwen 系列"),
    ProviderPreset("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "SILICONFLOW_API_KEY", Color(0xFF00B4A6), "硅基流动聚合平台"),
    ProviderPreset("custom", "自定义", "", "CUSTOM_API_KEY", Color(0xFF6B7280), "手动填写 Base URL")
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
                }
            )
        }

        // ── Add provider button ──
        OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加 Provider")
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

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (ProviderConfig) -> Unit,
    onDelete: () -> Unit
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

                // Models — simple comma-separated text field (much friendlier than
                // a separate add/edit dialog for each model)
                val modelsText = provider.models.joinToString(", ") { it.id }
                OutlinedTextField(
                    value = modelsText,
                    onValueChange = { newText ->
                        val newModels = newText.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }.map { ModelConfig(it, provider.id, it) }
                        onUpdate(provider.copy(models = newModels))
                    },
                    label = { Text("模型 ID（逗号分隔）") },
                    supportingText = { Text("例如：gpt-4o, gpt-4o-mini, o1-preview", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth()
                )

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
