package dev.idadroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.AgentModelCatalog
import dev.idadroid.agent.PiConfigSnapshot
import dev.idadroid.agent.PiModel
import dev.idadroid.agent.parseAgentModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─── Provider presets ──────────────────────────────────────────────────────────
// Inspired by Operit's quick-config templates: one tap fills in the base URL
// and provider type so the user only needs to paste an API key.

private data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val envKey: String,
    val description: String
)

private val PROVIDER_PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY", "GPT-4o / o1 / o3 系列"),
    ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com", "ANTHROPIC_API_KEY", "Claude 3.5 / 3.7 / 4 系列"),
    ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com", "DEEPSEEK_API_KEY", "DeepSeek-V3 / R1 系列"),
    ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "聚合 300+ 模型"),
    ProviderPreset("moonshot", "Moonshot KIMI", "https://api.moonshot.cn/v1", "MOONSHOT_API_KEY", "Kimi K1.5 / K2 系列"),
    ProviderPreset("dashscope", "阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", "Qwen 系列"),
    ProviderPreset("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "SILICONFLOW_API_KEY", "硅基流动聚合平台"),
    ProviderPreset("custom", "自定义", "", "CUSTOM_API_KEY", "手动填写 Base URL")
)

/**
 * A human-friendly visual editor for the Pi agent's models.json / env config.
 *
 * Instead of asking the user to hand-edit JSON (the old approach), this editor
 * presents providers as expandable cards with form fields for API key, base URL,
 * and model list — directly inspired by Operit's ModelApiSettingsSection.
 *
 * Changes are written back to [PiConfigSnapshot.modelsText] and
 * [PiConfigSnapshot.envText] as JSON, so the rest of the app is unaffected.
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
    var showAddProvider by remember { mutableStateOf(false) }
    var showAddModel by remember { mutableStateOf<String?>(null) }
    var showRawJson by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        // ── Status banner ──
        item { ConfigStatusBanner(parsed) }

        // ── Quick presets ──
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("快速添加 Provider", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("点击下方预设可一键创建 Provider，只需填入 API Key 即可使用。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    ProviderPresetRow(parsed.providerIds) { preset ->
                        val updated = applyPreset(parsed, preset)
                        val newJson = buildModelsJson(updated.providers, updated.models)
                        onSnapshotChange(snapshot.copy(
                            modelsText = newJson,
                            envText = rebuildEnv(updated.env, updated.providers),
                            modelCatalog = parseAgentModelCatalog(newJson)
                        ))
                    }
                }
            }
        }

        // ── Provider cards ──
        items(parsed.providers, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider,
                isExpanded = editingProvider == provider.id,
                onToggleExpand = { editingProvider = if (editingProvider == provider.id) null else provider.id },
                onUpdate = { updated ->
                    val newProviders = parsed.providers.map { if (it.id == provider.id) updated else it }
                    val newJson = buildModelsJson(newProviders, parsed.models)
                    onSnapshotChange(snapshot.copy(
                        modelsText = newJson,
                        envText = rebuildEnv(parsed.env, newProviders),
                        modelCatalog = parseAgentModelCatalog(newJson)
                    ))
                },
                onDelete = {
                    val newProviders = parsed.providers.filter { it.id != provider.id }
                    val newModels = parsed.models.filter { it.provider != provider.id }
                    val newJson = buildModelsJson(newProviders, newModels)
                    onSnapshotChange(snapshot.copy(
                        modelsText = newJson,
                        envText = removeEnvKeys(parsed.env, provider.envKeys),
                        modelCatalog = parseAgentModelCatalog(newJson)
                    ))
                    if (editingProvider == provider.id) editingProvider = null
                },
                onAddModel = { showAddModel = provider.id }
            )
        }

        // ── Add provider button ──
        item {
            OutlinedButton(
                onClick = { showAddProvider = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加自定义 Provider")
            }
        }

        // ── Advanced: raw JSON ──
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { showRawJson = !showRawJson }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (showRawJson) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("高级：直接编辑 JSON", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("models.json / env", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
    }

    // ── Add provider dialog ──
    if (showAddProvider) {
        AddProviderDialog(
            existingIds = parsed.providerIds,
            onDismiss = { showAddProvider = false },
            onConfirm = { id, baseUrl, envKey ->
                val newProvider = ProviderConfig(
                    id = id,
                    baseUrl = baseUrl,
                    envKey = envKey,
                    apiKey = "",
                    models = emptyList()
                )
                val newProviders = parsed.providers + newProvider
                val newJson = buildModelsJson(newProviders, parsed.models)
                onSnapshotChange(snapshot.copy(
                    modelsText = newJson,
                    envText = rebuildEnv(parsed.env, newProviders),
                    modelCatalog = parseAgentModelCatalog(newJson)
                ))
                showAddProvider = false
                editingProvider = id
            }
        )
    }

    // ── Add model dialog ──
    showAddModel?.let { providerId ->
        AddModelDialog(
            providerId = providerId,
            onDismiss = { showAddModel = null },
            onConfirm = { modelId, modelName, reasoning ->
                val newModel = ModelConfig(
                    id = modelId,
                    provider = providerId,
                    name = modelName,
                    reasoning = reasoning
                )
                val newModels = parsed.models + newModel
                val newJson = buildModelsJson(parsed.providers, newModels)
                onSnapshotChange(snapshot.copy(
                    modelsText = newJson,
                    modelCatalog = parseAgentModelCatalog(newJson)
                ))
                showAddModel = null
            }
        )
    }
}

// ─── Data classes for parsed config ────────────────────────────────────────────

private data class ModelConfig(
    val id: String,
    val provider: String,
    val name: String,
    val reasoning: Boolean = false
)

private data class ProviderConfig(
    val id: String,
    val baseUrl: String,
    val envKey: String,
    val apiKey: String,
    val models: List<ModelConfig>
) {
    val envKeys: List<String> get() = listOf(envKey)
}

private data class ParsedConfig(
    val providers: List<ProviderConfig>,
    val models: List<ModelConfig>,
    val env: Map<String, String>,
    val parseError: String?
) {
    val providerIds: List<String> get() = providers.map { it.id }
}

// ─── Parsing ───────────────────────────────────────────────────────────────────

private fun parseConfig(modelsText: String, envText: String): ParsedConfig {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    return try {
        val modelsObj = if (modelsText.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(modelsText).jsonObject
        val envObj = if (envText.isBlank()) emptyMap() else json.parseToJsonElement(envText).let { it as? JsonObject }?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()

        val providersList = mutableListOf<ProviderConfig>()
        val modelsList = mutableListOf<ModelConfig>()

        // Parse "providers" array
        modelsObj["providers"]?.jsonArray?.forEach { elem ->
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val baseUrl = obj["baseURL"]?.jsonPrimitive?.contentOrNull ?: obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: ""
            val envKey = obj["envKey"]?.jsonPrimitive?.contentOrNull ?: "${id.uppercase()}_API_KEY"
            val apiKey = envObj[envKey] ?: ""
            providersList.add(ProviderConfig(id, baseUrl, envKey, apiKey, emptyList()))
        }

        // Parse "models" array
        modelsObj["models"]?.jsonArray?.forEach { elem ->
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val provider = obj["provider"]?.jsonPrimitive?.contentOrNull ?: ""
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            modelsList.add(ModelConfig(id, provider, name, reasoning))
        }

        // Attach models to their providers
        val withModels = providersList.map { p ->
            p.copy(models = modelsList.filter { it.provider == p.id })
        }

        ParsedConfig(withModels, modelsList, envObj, null)
    } catch (e: Exception) {
        ParsedConfig(emptyList(), emptyList(), emptyMap(), e.message ?: "解析失败")
    }
}

private fun buildModelsJson(providers: List<ProviderConfig>, models: List<ModelConfig>): String {
    val json = Json { prettyPrint = true }
    val obj = buildJsonObject {
        put("providers", buildJsonArray {
            providers.forEach { p ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(p.id))
                    if (p.baseUrl.isNotBlank()) put("baseURL", JsonPrimitive(p.baseUrl))
                    put("envKey", JsonPrimitive(p.envKey))
                })
            }
        })
        put("models", buildJsonArray {
            models.forEach { m ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(m.id))
                    put("provider", JsonPrimitive(m.provider))
                    if (m.name.isNotBlank()) put("name", JsonPrimitive(m.name))
                    if (m.reasoning) put("reasoning", JsonPrimitive(true))
                })
            }
        })
    }
    return json.encodeToString(JsonObject.serializer(), obj)
}

private fun rebuildEnv(env: Map<String, String>, providers: List<ProviderConfig>): String {
    val json = Json { prettyPrint = true }
    val merged = env.toMutableMap()
    providers.forEach { p ->
        if (p.apiKey.isNotBlank()) merged[p.envKey] = p.apiKey
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

private fun applyPreset(parsed: ParsedConfig, preset: ProviderPreset): ParsedConfig {
    if (preset.id != "custom" && parsed.providerIds.contains(preset.id)) return parsed
    val id = if (preset.id == "custom") "custom-${System.currentTimeMillis()}" else preset.id
    val newProvider = ProviderConfig(id, preset.baseUrl, preset.envKey, "", emptyList())
    val newProviders = parsed.providers + newProvider
    return parsed.copy(providers = newProviders)
}

// ─── UI Components ─────────────────────────────────────────────────────────────

@Composable
private fun ConfigStatusBanner(parsed: ParsedConfig) {
    val (message, isError) = when {
        parsed.parseError != null -> "配置解析失败：${parsed.parseError}" to true
        parsed.providers.isEmpty() -> "尚未配置任何 Provider。点击下方预设快速开始。" to true
        parsed.models.isEmpty() -> "已配置 ${parsed.providers.size} 个 Provider，但还没有 Model。" to true
        else -> "已配置 ${parsed.providers.size} 个 Provider / ${parsed.models.size} 个 Model" to false
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

@Composable
private fun ProviderPresetRow(existingIds: List<String>, onPick: (ProviderPreset) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PROVIDER_PRESETS.take(4).forEach { preset ->
            val alreadyAdded = preset.id != "custom" && existingIds.contains(preset.id)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (alreadyAdded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !alreadyAdded) { onPick(preset) }
            ) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(preset.displayName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (alreadyAdded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
                    if (alreadyAdded) {
                        Text("已添加", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    Spacer(Modifier.heightIn(min = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PROVIDER_PRESETS.drop(4).forEach { preset ->
            val alreadyAdded = preset.id != "custom" && existingIds.contains(preset.id)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (alreadyAdded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !alreadyAdded) { onPick(preset) }
            ) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(preset.displayName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (alreadyAdded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer)
                    if (alreadyAdded) {
                        Text("已添加", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (ProviderConfig) -> Unit,
    onDelete: () -> Unit,
    onAddModel: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        // Header
        Row(
            Modifier.fillMaxWidth().clickable { onToggleExpand() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(provider.id.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.id, fontWeight = FontWeight.Bold)
                Text(
                    if (provider.models.isEmpty()) "暂无模型" else "${provider.models.size} 个模型",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (provider.apiKey.isBlank()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(4.dp)) {
                    Text("未配置 Key", fontSize = 10.sp, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }

        // Expanded content
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

                // API Key (password field with show/hide toggle)
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

                // Env key
                OutlinedTextField(
                    value = provider.envKey,
                    onValueChange = { onUpdate(provider.copy(envKey = it)) },
                    label = { Text("环境变量名") },
                    supportingText = { Text("API Key 将写入此环境变量", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Models list
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模型列表", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onAddModel) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加模型")
                    }
                }
                if (provider.models.isEmpty()) {
                    Text("暂无模型，点击「添加模型」创建", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    provider.models.forEach { model ->
                        ModelRow(
                            model = model,
                            onUpdate = { updated ->
                                val newModels = provider.models.map { if (it.id == model.id && it.provider == model.provider) updated else it }
                                onUpdate(provider.copy(models = newModels))
                            },
                            onDelete = {
                                val newModels = provider.models.filterNot { it.id == model.id && it.provider == model.provider }
                                onUpdate(provider.copy(models = newModels))
                            }
                        )
                    }
                }

                // Delete provider
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDelete, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除 Provider")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelConfig,
    onUpdate: (ModelConfig) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!editing) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.id, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    if (model.name != model.id) {
                        Text(model.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (model.reasoning) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text("推理", fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = model.id,
                    onValueChange = { onUpdate(model.copy(id = it)) },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model.name,
                    onValueChange = { onUpdate(model.copy(name = it)) },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = model.reasoning, onCheckedChange = { onUpdate(model.copy(reasoning = it)) })
                    Text("推理模型 (reasoning)", fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { editing = false }) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    existingIds: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (id: String, baseUrl: String, envKey: String) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var envKey by remember { mutableStateOf("") }
    val canConfirm = id.isNotBlank() && id !in existingIds

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = { onConfirm(id.trim(), baseUrl.trim(), envKey.ifBlank { "${id.uppercase()}_API_KEY" }.trim()) }, enabled = canConfirm) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加自定义 Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Provider ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = envKey, onValueChange = { envKey = it }, label = { Text("环境变量名 (可选)") }, supportingText = { Text("默认为 <ID>_API_KEY") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    )
}

@Composable
private fun AddModelDialog(
    providerId: String,
    onDismiss: () -> Unit,
    onConfirm: (id: String, name: String, reasoning: Boolean) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = { onConfirm(id.trim(), name.ifBlank { id }.trim(), reasoning) }, enabled = id.isNotBlank()) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加模型 ($providerId)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("显示名称 (可选)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = reasoning, onCheckedChange = { reasoning = it })
                    Text("推理模型 (reasoning)")
                }
            }
        }
    )
}
