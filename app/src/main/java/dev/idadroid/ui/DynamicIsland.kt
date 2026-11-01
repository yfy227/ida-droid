package dev.idadroid.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.SessionStats

/**
 * V6.1 灵动岛(Dynamic Island)— 聊天屏顶部智能状态胶囊。
 *
 * 三态:
 *  - 空闲: 收缩小胶囊(模型名 + MCP/环境状态点 + 上下文水位微条)
 *  - 工作: 全宽呼吸胶囊(阶段动画 + 计时 + 工具计数), 点击展开详情
 *  - 详情(工作态点击): 展开卡片(阶段/耗时/token/上下文/费用/工具统计)
 */
@Composable
fun DynamicIslandBar(
    isWorking: Boolean,
    phase: String?,
    waitDurationMs: Long,
    model: String,
    stats: SessionStats?,
    mcpOnline: Boolean,
    onAbort: () -> Unit = {},
    onOpenSessions: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val breath = rememberInfiniteTransition(label = "island")
    val pulse by breath.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    val phaseText = when (phase) {
        "connecting" -> "正在连接模型…"
        "receiving" -> "流式接收中…"
        "executing_tool" -> "执行工具中…"
        "compacting" -> "压缩上下文…"
        else -> agentStateWorkingText(isWorking)
    }
    val ctxPct = (stats?.contextUsage?.percent ?: 0.0).coerceIn(0.0, 100.0)
    val ctxColor = when {
        ctxPct >= 85 -> MaterialTheme.colorScheme.error
        ctxPct >= 60 -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .animateContentSize(tween(260))
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 42),
            color = if (isWorking) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .let { m ->
                    if (isWorking) m.fillMaxWidth().clickable { expanded = !expanded }
                    else m.clickable { onOpenSessions() }
                }
        ) {
            Column(Modifier.animateContentSize(tween(260))) {
                // ── 主胶囊行 ──
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isWorking) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .alpha(pulse)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Text(
                            phaseText,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatMs(waitDurationMs),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f)
                        )
                        val tools = (stats?.toolCalls ?: 0)
                        if (tools > 0) {
                            AssistChip(
                                onClick = { expanded = !expanded },
                                label = { Text("🛠 $tools", fontSize = 10.sp) },
                                modifier = Modifier.height(22.dp)
                            )
                        }
                    } else {
                        // 空闲态: 状态点 + 模型名 + 水位条
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Text(
                            model.ifBlank { "未选模型" },
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // MCP 状态点
                        Text(
                            if (mcpOnline) "MCP" else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        // 上下文水位微条
                        Box(
                            Modifier
                                .width(42.dp)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(ctxPct.toFloat() / 100f)
                                    .background(ctxColor, RoundedCornerShape(2.dp))
                            )
                        }
                        Text("${ctxPct.toInt()}%", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // ── 工作态展开: 详情卡 ──
                if (isWorking && expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val t = stats?.tokens
                        DetailRow("阶段", phaseText)
                        DetailRow("耗时", formatMs(waitDurationMs))
                        t?.let {
                            val total = it.total ?: ((it.input ?: 0) + (it.output ?: 0))
                            DetailRow("Token", "入 ${it.input ?: 0} · 出 ${it.output ?: 0} · 合计 $total")
                            if ((it.cacheRead ?: 0) > 0) DetailRow("缓存命中", "${it.cacheRead} tokens")
                        }
                        stats?.cost?.let { if (it > 0) DetailRow("费用", "\$%.4f".format(it)) }
                        DetailRow("消息", "用户 ${stats?.userMessages ?: 0} · 助手 ${stats?.assistantMessages ?: 0} · 工具 ${stats?.toolCalls ?: 0}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            OutlinedButton(
                                onClick = onAbort,
                                modifier = Modifier.weight(1f)
                            ) { Text("停止本轮", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun agentStateWorkingText(isWorking: Boolean) = if (isWorking) "处理中…" else "空闲"

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}m${s % 60}s" else "${s}s"
}
