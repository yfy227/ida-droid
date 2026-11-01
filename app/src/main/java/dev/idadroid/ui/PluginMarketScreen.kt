package dev.idadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import dev.idadroid.agent.PluginManager

/**
 * V5.1: 插件市场 — 参考 DSHA 插件生态的安装/启用/卸载体验。
 * 内置市场源(assets) + 启用即注入 agent 工具链(function calling)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketScreen(
    pluginManager: PluginManager,
    onBackClick: () -> Unit
) {
    val state by pluginManager.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // V6.1: 能力市场 — 技能区数据(内置 + 经验学习晋升的 learned 技能)
    val skills by remember {
        mutableStateOf(
            runCatching {
                dev.idadroid.agent.skills.SkillLibrary.ensureLoaded(context)
                dev.idadroid.agent.skills.SkillLibrary.all(context)
            }.getOrDefault(emptyList())
        )
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            pluginManager.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("能力市场") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "技能 ${skills.size} · 已启用插件 ${state.installed.count { it.enabled }} / ${state.installed.size} · 市场插件 ${state.market.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ── V6.1: 技能区(内置方法论 + 经验学习晋升; 触发词命中即被 agent 引用) ──
            if (skills.isNotEmpty()) {
                item(key = "skills-header") {
                    Text(
                        "技能 · ${skills.count { it.origin == "learned" }} 个来自经验学习",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                val bySkillCategory = skills.groupBy { it.category }.toSortedMap()
                bySkillCategory.forEach { (category, groupSkills) ->
                    item(key = "skill-cat-$category") {
                        Text(
                            category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(groupSkills, key = { "skill-" + it.id }) { sk ->
                        SkillCard(
                            name = sk.name,
                            summary = sk.summary,
                            triggers = sk.triggers,
                            learned = sk.origin == "learned",
                            body = runCatching {
                                context.assets.open("skills/${sk.file.substringAfterLast('/')}")
                                    .bufferedReader().use { it.readText() }
                            }.getOrDefault("")
                        )
                    }
                }
            }
            // ── 已安装 ──
            items(state.installed, key = { "inst-" + it.manifest.id }) { p ->
                PluginCard(
                    title = p.manifest.name,
                    subtitle = "v${p.manifest.version} · ${p.manifest.tools.size} 工具 · ${p.manifest.author}",
                    description = p.manifest.description,
                    toolNames = p.manifest.tools.map { it.name },
                    enabled = p.enabled,
                    onToggle = { pluginManager.setEnabled(p.manifest.id, it) },
                    onUninstall = { pluginManager.uninstall(p.manifest.id) }
                )
            }
            // ── 市场未安装(V6: 按 category 分组, 组标题+组内卡) ──
            val notInstalled = state.market.filter { m -> state.installed.none { it.manifest.id == m.id } }
            val byCategory = notInstalled.groupBy { it.category }.toSortedMap()
            byCategory.forEach { (category, plugins) ->
                item(key = "cat-$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(plugins, key = { "market-" + it.id }) { m ->
                    PluginCard(
                        title = m.name,
                        subtitle = "v${m.version} · ${m.tools.size} 工具 · ${m.author}",
                        description = m.description,
                        toolNames = m.tools.map { it.name },
                        enabled = false,
                        installed = false,
                        onInstall = { pluginManager.install(m) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    title: String,
    subtitle: String,
    description: String,
    toolNames: List<String>,
    enabled: Boolean,
    installed: Boolean = true,
    onToggle: (Boolean) -> Unit = {},
    onInstall: () -> Unit = {},
    onUninstall: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (installed && enabled) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!installed) {
                    androidx.compose.material3.TextButton(onClick = onInstall) { Text("安装") }
                } else {
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                toolNames.take(4).forEach { t ->
                    Badge(containerColor = MaterialTheme.colorScheme.outline) {
                        Text(t, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            if (installed) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.TextButton(onClick = onUninstall) {
                        Text("卸载", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}


// ══════════════════════════════════════════════════════════════
// V6.1: 技能卡 — 点开看完整方法论(触发词/流程/工具组合)
// ══════════════════════════════════════════════════════════════
@Composable
private fun SkillCard(
    name: String,
    summary: String,
    triggers: List<String>,
    learned: Boolean,
    body: String
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (learned) Icons.Rounded.School else Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = if (learned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    if (learned) "习得" else "内置",
                    fontSize = 10.sp,
                    color = if (learned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            if (learned) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (triggers.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    triggers.take(6).forEach { t ->
                        Text(
                            "#$t",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    RoundedCornerShape(50)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            AnimatedVisibility(expanded) {
                if (body.isNotBlank()) {
                    Text(
                        body,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}
