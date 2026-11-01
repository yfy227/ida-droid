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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            pluginManager.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件市场") },
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
                    "已启用 ${state.installed.count { it.enabled }} / ${state.installed.size} 个插件 · 市场 ${state.market.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
