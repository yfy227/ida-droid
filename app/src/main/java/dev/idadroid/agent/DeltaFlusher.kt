package dev.idadroid.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 流式 delta 合并器 — 高频 text/thinking delta 的节拍 flush。
 *
 * 设计原因：
 * - SSE 流式 delta 以每秒数十~数百次到达
 * - 若每次都 _state.update + 整条消息列表拷贝 + Compose 重组 → 主线程卡帧
 * - 先累积到缓冲区，再按固定节拍（~30fps）合并 flush
 *
 * 线程安全：
 * - 所有字段访问通过 [lock] 保护
 * - IO 线程的 [finishStreamingFlush] 与 Main 线程的 deltaFlushJob 可能同时操作
 *
 * 从 PiAgentManager 提取，消除 God Class 职责。
 */
class DeltaFlusher(
    private val state: MutableStateFlow<AgentUiState>,
    private val scope: CoroutineScope,
    private val newMessageId: () -> String
) {
    private val lock = Any()
    private val pendingText = StringBuilder()
    private val pendingThinking = StringBuilder()
    @Volatile private var flushJob: Job? = null
    @Volatile private var streaming = false

    /**
     * 累积 delta 到缓冲区。
     * 第一个 delta 立即 flush（让首字瞬间出现），随后启动节拍 flusher。
     */
    fun apply(textDelta: String, thinkingDelta: String) {
        if (textDelta.isEmpty() && thinkingDelta.isEmpty()) return
        synchronized(lock) {
            if (textDelta.isNotEmpty()) pendingText.append(textDelta)
            if (thinkingDelta.isNotEmpty()) pendingThinking.append(thinkingDelta)
            if (!streaming) {
                streaming = true
                flushLocked()
                flushJob?.cancel()
                flushJob = scope.launch(Dispatchers.Main.immediate) {
                    while (isActive) {
                        delay(FLUSH_INTERVAL_MS)
                        flush()
                    }
                }
            }
        }
    }

    /** 把缓冲区 delta 一次性合并进状态。 */
    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    /** 结束本轮流式：停止节拍 flusher，强制 flush 残余 delta。 */
    fun finish() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            flushLocked()
            streaming = false
        }
    }

    /** 内部实现 — 调用者必须持有 [lock]。 */
    private fun flushLocked() {
        if (pendingText.isEmpty() && pendingThinking.isEmpty()) return
        val text = pendingText.toString()
        val thinking = pendingThinking.toString()
        pendingText.setLength(0)
        pendingThinking.setLength(0)
        try {
            state.update { old ->
                val messages = old.messages
                val last = messages.lastOrNull()
                if (last?.role == "assistant") {
                    val updatedLast = last.copy(
                        text = last.text + text,
                        thinking = if (thinking.isNotEmpty()) (last.thinking ?: "") + thinking else last.thinking
                    )
                    val list = messages.toMutableList()
                    list[list.lastIndex] = updatedLast
                    old.copy(messages = list)
                } else {
                    old.copy(messages = messages + ChatMessage(
                        newMessageId(), "assistant", text, System.currentTimeMillis(),
                        thinking = thinking.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("DeltaFlusher", "flush 异常: ${e.message}")
        }
    }

    companion object {
        /** 节拍间隔 ~30fps：兼顾逐字流式视觉流畅与状态更新开销 */
        private const val FLUSH_INTERVAL_MS = 33L
    }
}
