package dev.idadroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.ChatMessage

/**
 * V6 分组渲染 — 消息流"工具组"折叠卡(参考 Claude Code 折叠工具组)。
 *
 * 连续的 tool 消息折叠为一张组卡: 标题行(工具数×N + 汇总状态) + 可展开明细。
 * 分组纯函数与 UI 分离, 便于测试。
 */

/** 渲染条目: 单条消息 或 工具组 */
sealed interface RenderItem {
    data class Single(val message: ChatMessage) : RenderItem
    data class ToolGroup(val messages: List<ChatMessage>) : RenderItem
}

/**
 * 消息分组规则:
 * 1. 连续 role=="tool" 的消息(≥2 条)折叠成 ToolGroup(1 条不折叠, 保持原样)
 * 2. 其余消息逐条 Single
 */
fun groupMessagesForRender(messages: List<ChatMessage>): List<RenderItem> {
    val out = mutableListOf<RenderItem>()
    var toolRun = mutableListOf<ChatMessage>()
    fun flushRun() {
        if (toolRun.size >= 2) out += RenderItem.ToolGroup(toolRun.toList())
        else toolRun.forEach { out += RenderItem.Single(it) }
        toolRun = mutableListOf()
    }
    messages.forEach { m ->
        if (m.role == "tool") toolRun += m else { flushRun(); out += RenderItem.Single(m) }
    }
    flushRun()
    return out
}

/** 工具组卡: 标题(工具×N + 成功/失败状态点) + 展开明细(逐工具行) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolGroupCard(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onOpenToolDetail: (ChatMessage) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val okCount = messages.count { it.toolResult?.startsWith("[ERROR") != true && it.toolResult.isNullOrBlank().not() }
    val failCount = messages.size - okCount
    val distinctTools = messages.mapNotNull { it.toolName }.distinct()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 标题行
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Build, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (distinctTools.size == 1)
                        "工具 ${distinctTools.first()} ×${messages.size}"
                    else "工具调用 ×${messages.size}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (failCount > 0) {
                    StatusDot(Color(0xFFE5484D))
                    Spacer(Modifier.width(4.dp))
                    Text("$failCount 失败", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                } else {
                    StatusDot(Color(0xFF30A46C))
                }
                AnimatedVisibility(visible = !expanded) {
                    Icon(Icons.Rounded.ExpandMore, "展开", Modifier.size(18.dp))
                }
                AnimatedVisibility(visible = expanded) {
                    Icon(Icons.Rounded.ExpandLess, "收起", Modifier.size(18.dp))
                }
            }
            // 展开明细
            AnimatedVisibility(visible = expanded) {
                Column {
                    messages.forEach { m ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenToolDetail(m) }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "▸ ${m.toolName ?: "tool"}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                                ),
                                modifier = Modifier.weight(0.34f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                m.toolResult?.take(64) ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.66f)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(7.dp)
            .background(color, RoundedCornerShape(50))
    )
}
