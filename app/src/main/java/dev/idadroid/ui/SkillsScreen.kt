package dev.idadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext

/**
 * V6.2: 技能专区 — 独立面板(老板点名"为技能独立开辟一片专门的区域")。
 *
 * - 内置技能(8 项方法论): 分类分组 + 启用开关 + 展开全文
 * - 习得技能(经验强化学习晋升): 单独分组 + 可删除
 * - 底部: 经验引擎状态(模式数/失败签名数)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val skills = remember {
        runCatching {
            dev.idadroid.agent.skills.SkillLibrary.ensureLoaded(context)
            dev.idadroid.agent.skills.SkillLibrary.all(context)
        }.getOrDefault(emptyList())
    }
    val experience = remember {
        runCatching { dev.idadroid.agent.skills.ExperienceEngine(context) }.getOrNull()
    }
    val expSnapshot = remember { experience?.snapshot() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("技能库") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "技能 ${skills.size} 个 · 其中 ${skills.count { it.origin == "learned" }} 个由经验学习习得",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ── 习得技能(经验强化学习晋升) ──
            val learned = skills.filter { it.origin == "learned" }
            if (learned.isNotEmpty()) {
                item(key = "learned-header") {
                    Text("🎓 习得技能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                }
                items(learned, key = { "learned-" + it.id }) { sk ->
                    SkillCardFull(
                        name = sk.name,
                        summary = sk.summary,
                        triggers = sk.triggers,
                        learned = true,
                        body = sk.summary,
                        onDelete = {
                            dev.idadroid.agent.skills.SkillLibrary.removeLearned(context, sk.id)
                        }
                    )
                }
            }
            // ── 内置技能(按分类分组) ──
            item(key = "builtin-header") {
                Text("⚡ 内置技能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            val builtin = skills.filter { it.origin != "learned" }
            val byCategory = builtin.groupBy { it.category }.toSortedMap()
            byCategory.forEach { (category, groupSkills) ->
                item(key = "cat-$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(groupSkills, key = { "skill-" + it.id }) { sk ->
                    SkillCardFull(
                        name = sk.name,
                        summary = sk.summary,
                        triggers = sk.triggers,
                        learned = false,
                        body = runCatching {
                            context.assets.open("skills/${sk.file.substringAfterLast('/')}")
                                .bufferedReader().use { it.readText() }
                        }.getOrDefault("")
                    )
                }
            }
            // ── 经验引擎状态 ──
            if (expSnapshot != null) {
                item(key = "exp-status") {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("经验强化学习", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "工具调用记录 ${expSnapshot.entries.size} 条 · 成功模式 ${expSnapshot.patterns.size} 个" +
                                " · 已晋升 ${expSnapshot.patterns.count { it.promotedToSkill }} 个技能" +
                                " · 已知失败坑 ${expSnapshot.failures.size} 个",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "说明: 会话中连续成功的工具链累计 3 次自动晋升为技能; 失败签名会在下轮提示词中避坑; 30 天未用自动衰减。",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCardFull(
    name: String,
    summary: String,
    triggers: List<String>,
    learned: Boolean,
    body: String,
    onDelete: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
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
                if (learned && onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (triggers.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    triggers.take(6).forEach { t ->
                        Text(
                            "#$t",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(expanded) {
                if (body.isNotBlank()) {
                    Text(
                        body,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}
