package dev.idadroid.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * V6.9 / V7-Blueprint L1: SessionStore — 会话/消息/统计的**唯一真相源**。
 *
 * 架构定位(rewrite-v7-blueprint.md):
 *  - engine 内存不再是真相源(会话串数据 P0 的病根)
 *  - UI / Orchestrator / engine 都只与本 store 交互
 *  - 消息原子持久化(jsonl, temp+rename); 统计持久化(stats 侧车文件) → 重启后累积保留
 *  - 内部异步写盘(调用方不等待 IO), 单会话写锁防交叉
 *
 * 兼容: 现有 jsonl 格式不变(loadMessagesInternal 的读取路径可平滑迁移)。
 */
class SessionStore(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }
    private val baseDir: File = File(context.filesDir, "store").apply { mkdirs() }
    private val sessionsDir: File = File(baseDir, "sessions").apply { mkdirs() }
    private val statsDir: File = File(baseDir, "stats").apply { mkdirs() }

    // ── 内存缓存(真相源的运行态镜像; 文件是持久层) ──
    private val messagesCache = ConcurrentHashMap<String, MutableList<ChatMessage>>()
    private val statsCache = ConcurrentHashMap<String, PersistedStats>()
    private val writeMutexes = ConcurrentHashMap<String, Mutex>()
    private val statsFlowMap = ConcurrentHashMap<String, MutableStateFlow<PersistedStats>>()

    /** 变更通知(消息级, UI 可订阅刷新) */
    private val _changes = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    // ════════════════════════════════════════════════════════════
    // 消息 API
    // ════════════════════════════════════════════════════════════

    /** 读取会话消息(优先内存缓存; 未加载则从文件读入缓存) */
    fun messages(sessionId: String): List<ChatMessage> {
        val cached = messagesCache[sessionId]
        if (cached != null) return cached.toList()
        val loaded = loadMessagesFromDisk(sessionId)
        messagesCache[sessionId] = loaded.toMutableList()
        return loaded
    }

    /** 追加消息(内存即时 + 异步原子落盘 + 变更通知) */
    fun appendMessage(sessionId: String, message: ChatMessage) {
        val list = messagesCache.computeIfAbsent(sessionId) {
            loadMessagesFromDisk(sessionId).toMutableList()
        }
        synchronized(list) { list.add(message) }
        _changes.tryEmit(sessionId)
        scope.launch { persistMessagesLocked(sessionId, list.toList()) }
    }

    /** 整体替换(restore/compact 后回写) */
    fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {
        val list = messagesCache.computeIfAbsent(sessionId) { mutableListOf() }
        synchronized(list) {
            list.clear()
            list.addAll(messages)
        }
        _changes.tryEmit(sessionId)
        scope.launch { persistMessagesLocked(sessionId, messages) }
    }

    /** 会话被删 → 清缓存+文件 */
    fun evict(sessionId: String) {
        messagesCache.remove(sessionId)
        statsCache.remove(sessionId)
        statsFlowMap.remove(sessionId)
        scope.launch {
            sessionFile(sessionId).delete()
            statsFile(sessionId).delete()
        }
    }

    // ════════════════════════════════════════════════════════════
    // 统计 API(V6.8 遗留: 重启后累积归零 → 持久化修复)
    // ════════════════════════════════════════════════════════════

    @Serializable
    data class PersistedStats(
        val sessionId: String = "",
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheReadTokens: Long = 0,
        val totalTokens: Long = 0,
        val turns: Int = 0,
        val updatedAt: Long = 0
    )

    fun stats(sessionId: String): PersistedStats = statsCache.computeIfAbsent(sessionId) {
        loadStatsFromDisk(sessionId)
    }

    fun statsFlow(sessionId: String): StateFlow<PersistedStats> =
        statsFlowMap.computeIfAbsent(sessionId) {
            MutableStateFlow(stats(sessionId))
        }.asStateFlow()

    /** turn 结束时累积 usage(持久化 → 重启保留) */
    fun addTurnUsage(sessionId: String, prompt: Int, completion: Int, cached: Int, total: Int) {
        val next = stats(sessionId).let { s ->
            s.copy(
                inputTokens = s.inputTokens + prompt,
                outputTokens = s.outputTokens + completion,
                cacheReadTokens = s.cacheReadTokens + cached,
                totalTokens = s.totalTokens + total,
                turns = s.turns + 1,
                updatedAt = System.currentTimeMillis()
            )
        }
        statsCache[sessionId] = next
        statsFlowMap[sessionId]?.value = next
        scope.launch {
            runCatching { statsFile(sessionId).writeText(json.encodeToString(next)) }
                .onFailure { Log.w("SessionStore", "stats persist fail: ${it.message}") }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 持久化(原子写)
    // ════════════════════════════════════════════════════════════

    private fun sessionFile(sessionId: String): File = File(sessionsDir, "$sessionId.jsonl")
    private fun statsFile(sessionId: String): File = File(statsDir, "$sessionId.stats.json")

    private suspend fun persistMessagesLocked(sessionId: String, messages: List<ChatMessage>) =
        withContext(Dispatchers.IO) {
            val mutex = writeMutexes.computeIfAbsent(sessionId) { Mutex() }
            mutex.withLock {
                runCatching {
                    val file = sessionFile(sessionId)
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    // ChatMessage 不是 @Serializable — 用紧凑 DTO 行(与旧 jsonl 兼容格式)
                    val body = messages.joinToString("\n") { m ->
                        json.encodeToString(StoreLine.serializer(), StoreLine(
                            id = m.id, role = m.role, text = m.text, ts = m.timestamp,
                            thinking = m.thinking, toolCallId = m.toolCallId, toolName = m.toolName,
                            toolResult = m.toolResult, toolStatus = m.toolStatus
                        ))
                    } + "\n"
                    tmp.writeText(body)
                    if (!tmp.renameTo(file)) {
                        file.delete(); tmp.renameTo(file)
                    }
                }.onFailure { Log.w("SessionStore", "persist fail sid=$sessionId: ${it.message}") }
            }
        }

    @Serializable
    private data class StoreLine(
        val id: String = "",
        val role: String = "",
        val text: String = "",
        val ts: Long = 0,
        val thinking: String? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolResult: String? = null,
        val toolStatus: String? = null
    )

    private fun loadMessagesFromDisk(sessionId: String): List<ChatMessage> = runCatching {
        val file = sessionFile(sessionId)
        if (!file.isFile) return@runCatching emptyList<ChatMessage>()
        file.readLines().mapNotNull { line ->
            val t = line.trim()
            if (t.isBlank()) null else runCatching {
                val l = json.decodeFromString(StoreLine.serializer(), t)
                ChatMessage(
                    id = l.id, role = l.role, text = l.text, timestamp = l.ts,
                    thinking = l.thinking, toolCallId = l.toolCallId, toolName = l.toolName,
                    toolResult = l.toolResult, toolStatus = l.toolStatus
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun loadStatsFromDisk(sessionId: String): PersistedStats = runCatching {
        val file = statsFile(sessionId)
        if (!file.isFile) return@runCatching PersistedStats(sessionId = sessionId)
        json.decodeFromString(PersistedStats.serializer(), file.readText())
    }.getOrDefault(PersistedStats(sessionId = sessionId))
}
