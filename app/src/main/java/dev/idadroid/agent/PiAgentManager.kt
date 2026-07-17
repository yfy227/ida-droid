package dev.idadroid.agent

import android.content.Context
import android.net.Uri
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

class PiAgentManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    /** 用户设置的工作区路径 (proot 内可见路径)，默认 /root/pi_workspace */
    private val workspaceProotPath: String get() = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
    /** 工作区在主机文件系统上的根目录 */
    private val workspaceHostRoot: File get() {
        val ws = workspaceProotPath
        // /root/xxx → 在 rootfs 内
        if (ws.startsWith("/root/")) return File(paths.rootfsDir, ws.removePrefix("/"))
        // /sdcard/xxx 或 /storage/xxx → proot 已绑定，直接用主机路径
        if (ws.startsWith("/sdcard") || ws.startsWith("/storage")) return File(ws)
        // 其他情况默认在 rootfs 内
        return File(paths.rootfsDir, ws.removePrefix("/").ifBlank { "root/pi_workspace" })
    }
    private val repo = AgentSessionRepository(appContext, paths)
    private val configManager = PiConfigManager(appContext, paths)
    val aiConfigTools = AiConfigTools(paths, configManager)
    private val attachmentManager = AttachmentManager(appContext, paths)
    val workspaceManager = WorkspaceManager(appContext, paths)
    private val deepIndexToolChain = dev.idadroid.deepindex.DeepIndexToolChain(appContext, paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, PiRpcRuntime>()
    // Send lock: prevents concurrent sendPrompt calls from racing. Operit uses
    // a similar coordination pattern in MessageCoordinationDelegate.
    private val sendMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var sendingInProgress = false
    /** 当主动 abort 上一轮对话时设为 true，收到 abort 错误事件后静默处理 */
    @Volatile private var suppressAbortError = false

    // ==================== 新架构：分层对话引擎 ====================
    // Layer 1 (ChatHttpClient) + Layer 2 (ToolEventBus) + Layer 3 (ConversationManager)
    // 取代 pi --mode rpc 持久管道，直接用 HTTPS + function calling
    private val conversationManager = ConversationManager(appContext, paths, dev.idadroid.proot.IdaProotRuntime(appContext, paths))
    @Volatile private var currentSendJob: Job? = null

    // ==================== 流式 delta 合并器 ====================
    // 高频 text_delta / thinking_delta 事件会以每秒数十~数百次的频率到达，
    // 若每次都立即 _state.update + 整条消息列表拷贝 + 触发 Compose 重组，
    // 会导致主线程被疯狂打帧、Markdown 反复全量重解析（O(n^2)），表现为
    // 回复"粘成一坨"地攒着一起蹦出来。这里把 delta 先累积到缓冲区，再按
    // 固定节拍（约 30fps）合并 flush，既保证逐字流式的视觉流畅，又把状态
    // 更新与重组次数压到可控范围。
    private val pendingTextDelta = StringBuilder()
    private val pendingThinkingDelta = StringBuilder()
    private var deltaFlushJob: Job? = null
    private var deltaStreaming = false
    private val _state = MutableStateFlow(AgentUiState(activity = "agent 未启动"))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    fun refresh(createDefaultIfReady: Boolean = false) {
        if (!paths.rootfsDir.isDirectory) {
            _state.value = AgentUiState(activity = "rootfs 未导入")
            return
        }
        val store = repo.loadStore()
        val defaultSnapshot = if (createDefaultIfReady && store.sessions.isEmpty()) configManager.readSnapshot() else null
        val defaultPair = defaultSnapshot?.let(::resolveDefaultModel)
        val defaultProvider = defaultSnapshot?.defaultProvider?.trim()?.takeIf { it.isNotBlank() }
        val defaultModel = defaultSnapshot?.defaultModel?.trim()?.takeIf { it.isNotBlank() }
        val defaultThinking = defaultSnapshot?.defaultThinkingLevel?.trim()?.takeIf { it.isNotBlank() }
        val active = when {
            store.activeSessionId != null && store.sessions.any { it.id == store.activeSessionId } -> store.activeSessionId
            store.sessions.isNotEmpty() -> store.sessions.first().id
            createDefaultIfReady -> repo.ensureDefaultSession(
                provider = defaultProvider ?: defaultPair?.provider,
                model = defaultModel ?: defaultPair?.id,
                thinkingLevel = defaultThinking
            ).id
            else -> null
        }
        val sessions = store.sessions
        _state.update { old ->
            val activeSession = sessions.firstOrNull { it.id == active }
            old.copy(
                sessions = sessions,
                activeSessionId = active,
                status = activeSession?.status ?: "idle",
                error = activeSession?.error,
                modelLabel = modelLabel(activeSession),
                piConfig = configManager.readSnapshot(),
                activity = if (active == null) "点击新建 Session 开始" else old.activity,
                workspace = old.workspace.copy(
                    hasWorkspace = workspaceManager.hasWorkspace,
                    workspaceName = workspaceManager.currentWorkspaceName,
                    workspaceUri = workspaceManager.currentWorkspaceUri?.toString().orEmpty()
                )
            )
        }
        active?.let { loadMessages(it) }
    }

    fun createSession(name: String = "") {
        scope.launch {
            runCatching {
                requireReady()
                val snapshot = configManager.readSnapshot()
                val defaultPair = resolveDefaultModel(snapshot)
                repo.createSession(
                    name = name.ifBlank { null },
                    provider = configManager.defaultProvider() ?: defaultPair?.provider,
                    model = configManager.defaultModel() ?: defaultPair?.id,
                    thinkingLevel = configManager.defaultThinking()
                )
            }.onSuccess { session ->
                refresh()
                selectSession(session.id)
            }.onFailure { error -> setError("新建 Session 失败：${error.message}") }
        }
    }

    fun selectSession(id: String) {
        scope.launch {
            runCatching { repo.setActive(id) }
                .onSuccess {
                    refresh()
                    loadMessages(id)
                }
                .onFailure { error -> setError("切换 Session 失败：${error.message}") }
        }
    }

    fun renameSession(id: String, name: String) {
        if (name.isBlank()) return
        scope.launch {
            runCatching { repo.patchSession(id) { it.copy(name = name.trim()) } }
                .onSuccess { refresh() }
                .onFailure { error -> setError("重命名失败：${error.message}") }
        }
    }

    fun deleteSession(id: String) {
        scope.launch {
            runCatching {
                conversationManager.abort()
                runtimes.remove(id)?.stop()
                repo.deleteSession(id)
            }.onSuccess { refresh(createDefaultIfReady = true) }
                .onFailure { error -> setError("删除 Session 失败：${error.message}") }
        }
    }

    fun startSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: createDefaultSessionWithConfiguredModel().id
            // 新架构：不需要启动 pi 进程，只需更新状态
            repo.updateRuntimeStatus(sessionId, "running", null)
            _state.update { it.copy(status = "running", error = null, activeSessionId = sessionId, sessions = repo.listSessions(), activity = "就绪") }
        }
    }

    fun stopSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            // 新架构：abort 当前对话 + 更新状态
            conversationManager.abort()
            repo.updateRuntimeStatus(sessionId, "idle", null)
            _state.update { it.copy(status = "idle", turnActive = false, processingPhase = null, activity = "已停止") }
            refresh()
        }
    }

    fun sendPrompt(text: String, attachments: List<DraftAttachment> = emptyList(), sendMode: String? = null) {
        // Guard: drop the call if a send is already in flight. This prevents
        // double-tap races where the user hits the send button twice quickly
        // and two prompts get queued into the RPC runtime simultaneously.
        if (sendingInProgress) {
            _state.update { it.copy(activity = "上一条消息仍在发送中，请稍候…") }
            return
        }
        scope.launch {
            sendingInProgress = true
            try {
                sendPromptInternal(text, attachments, sendMode)
            } finally {
                sendingInProgress = false
            }
        }
    }

    private suspend fun sendPromptInternal(text: String, attachments: List<DraftAttachment>, sendMode: String?) {
        val sessionId = _state.value.activeSessionId ?: run {
            val created = createDefaultSessionWithConfiguredModel()
            refresh()
            created.id
        }
        val trimmed = text.trimEnd()
        if (trimmed.isBlank() && attachments.isEmpty()) return
        sendMutex.withLock {
            try {
                val session = repo.setActive(sessionId)
                val stored = attachmentManager.storeAttachments(attachments)
                val uploadedPaths = stored.map { it.prootPath }
                val displayAttachments = stored.map { it.display }
                val displayMessage = buildString {
                    append(trimmed)
                    if (uploadedPaths.isNotEmpty()) {
                        if (isNotEmpty() && !last().isWhitespace()) append(' ')
                        append(uploadedPaths.joinToString(" ") { attachmentManager.fileRef(it) })
                    }
                }.ifBlank { attachmentSummary(displayAttachments) }

                appendMessage(ChatMessage(newMessageId(), "user", displayMessage, System.currentTimeMillis(), attachments = displayAttachments))
                setTurnActive(sessionId, true)
                val promptStartTime = System.currentTimeMillis()
                _state.update { it.copy(activity = "正在发送消息…", processingPhase = "connecting", promptSentAt = promptStartTime, firstDeltaAt = 0L) }

                // ==================== 新架构路径 ====================
                // 解析 API Key / Base URL / Model 从 PiConfigManager
                val convConfig = resolveConvConfig(sessionId, session)
                    ?: run {
                        setTurnActive(sessionId, false)
                        appendMessage(ChatMessage(newMessageId(), "system", "配置缺失：请先在设置中配置 API Key 和 Base URL", System.currentTimeMillis()))
                        setError("配置缺失：请先在设置中配置 API Key 和 Base URL")
                        return@withLock
                    }

                val expanded = attachmentManager.expandFileReferencesForPrompt(displayMessage, session.cwd)
                currentSendJob = coroutineContext[Job]

                try {
                    kotlinx.coroutines.withTimeout(PROMPT_TIMEOUT_MS) {
                        conversationManager.send(expanded.message, expanded.images.map { it.data }, convConfig) { event ->
                            handleConvEvent(sessionId, event, promptStartTime)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    appendMessage(ChatMessage(newMessageId(), "system", "发送超时（3分钟无完成）", System.currentTimeMillis()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.i("PiAgentManager", "Prompt cancelled by abort (sessionId=$sessionId)")
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.simpleName ?: "未知异常"
                    appendMessage(ChatMessage(newMessageId(), "system", "发送失败：$msg", System.currentTimeMillis()))
                    setError("发送失败：$msg")
                } finally {
                    currentSendJob = null
                    finishStreamingFlush()
                    if (_state.value.turnActive) setTurnActive(sessionId, false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                setTurnActive(sessionId, false)
                val msg = e.message ?: e::class.simpleName ?: "未知异常"
                appendMessage(ChatMessage(newMessageId(), "system", "发送失败：$msg", System.currentTimeMillis()))
                setError("发送失败：$msg")
            }
        }
    }

    fun abort(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            // 标记：后续收到的 abort 相关错误事件静默处理
            suppressAbortError = true
            // 新架构：cancel sendJob 让 ConversationManager.send() 的流收集中断
            currentSendJob?.let { job ->
                job.cancel()
                try { job.join() } catch (_: kotlinx.coroutines.CancellationException) {}
            }
            currentSendJob = null
            conversationManager.abort()
            finishStreamingFlush()
            setTurnActive(sessionId, false)
            repo.updateRuntimeStatus(sessionId, "running", null)
        }
    }

    fun loadMessages(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            _state.update { it.copy(messagesLoading = true) }
            val messages = withContext(Dispatchers.IO) { loadMessagesInternal(sessionId) }
            _state.update { it.copy(messages = messages, messagesLoading = false) }
        }
    }

    fun clearRawLog() {
        _state.update { it.copy(rawLogLines = emptyList(), stderrTail = "") }
    }

    // ==================== 工作区相关 ====================

    /** 设置工作区 URI（由 SAF 选择回调传入）。 */
    fun setWorkspace(uri: android.net.Uri) {
        scope.launch {
            runCatching {
                workspaceManager.setWorkspace(uri)
                _state.update { it.copy(workspace = it.workspace.copy(
                    hasWorkspace = true,
                    workspaceName = workspaceManager.currentWorkspaceName,
                    workspaceUri = uri.toString(),
                    currentPath = "",
                    error = null
                )) }
                refreshWorkspaceFiles()
            }.onFailure { error -> setError("设置工作区失败：${error.message}") }
        }
    }

    /** 清除当前工作区。 */
    fun clearWorkspace() {
        workspaceManager.clearWorkspace()
        _state.update { it.copy(workspace = WorkspaceState()) }
    }

    /** 刷新工作区文件列表。 */
    fun refreshWorkspaceFiles(path: String = _state.value.workspace.currentPath) {
        scope.launch {
            _state.update { it.copy(workspace = it.workspace.copy(loading = true, error = null)) }
            try {
                val files = withContext(Dispatchers.IO) { workspaceManager.listFiles(path) }
                _state.update { it.copy(workspace = it.workspace.copy(
                    files = files,
                    currentPath = path,
                    loading = false
                )) }
            } catch (error: Exception) {
                _state.update { it.copy(workspace = it.workspace.copy(
                    loading = false,
                    error = "读取工作区失败：${error.message}"
                )) }
            }
        }
    }

    /** 进入工作区子目录。 */
    fun navigateWorkspace(relativePath: String) {
        val next = workspaceManager.resolvePath(_state.value.workspace.currentPath, relativePath)
        refreshWorkspaceFiles(next)
    }

    /** 将工作区中的文件导入到 pi_workspace 容器内，供 Agent 使用。 */
    fun importWorkspaceFileToContainer(entry: WorkspaceFileEntry, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { workspaceManager.importToContainer(entry) }
            }.onSuccess { prootPath ->
                val path = prootPath ?: "未知路径"
                appendMessage(ChatMessage(newMessageId(), "system", "已从工作区导入文件：${entry.name} → $path", System.currentTimeMillis()))
                onResult(true, path)
            }.onFailure { error ->
                setError("导入工作区文件失败：${error.message}")
                onResult(false, error.message ?: "未知错误")
            }
        }
    }

    /** 将 pi_workspace 容器内的文件导出到工作区。 */
    fun exportFileToWorkspace(containerRelativePath: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { workspaceManager.exportFromContainer(containerRelativePath) }
            }.onSuccess { name ->
                val fileName = name ?: containerRelativePath.substringAfterLast('/').ifBlank { containerRelativePath }
                appendMessage(ChatMessage(newMessageId(), "system", "已导出到工作区：$containerRelativePath → $fileName", System.currentTimeMillis()))
                refreshWorkspaceFiles()
                onResult(true, fileName)
            }.onFailure { error ->
                setError("导出到工作区失败：${error.message}")
                onResult(false, error.message ?: "未知错误")
            }
        }
    }

    /** 读取工作区文件作为草稿附件（用于发送给 Agent）。 */
    suspend fun readWorkspaceFileAsAttachment(entry: WorkspaceFileEntry): DraftAttachment? {
        return runCatching {
            withContext(Dispatchers.IO) { workspaceManager.readAsDraftAttachment(entry) }
        }.onFailure { error ->
            setError("读取工作区文件失败：${error.message}")
        }.getOrNull()
    }

    /** 将指定助手回复保存为工作区文件（满足"生成的代码文件都放工作区"）。 */
    fun saveAssistantMessageToWorkspace(messageId: String, fileName: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val message = _state.value.messages.firstOrNull { it.id == messageId && it.role == "assistant" }
        if (message == null) {
            onResult(false, "未找到该回复")
            return
        }
        val content = message.text.ifBlank { message.thinking ?: "" }
        if (content.isBlank()) {
            onResult(false, "回复内容为空")
            return
        }
        val safeName = safeFileName(fileName).ifBlank { "reply.txt" }
        val finalName = if (safeName.contains('.')) safeName else "$safeName.txt"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { workspaceManager.writeFileText(finalName, content) }
            }.onSuccess { ok ->
                if (ok) {
                    appendMessage(ChatMessage(newMessageId(), "system", "已保存到工作区：$finalName", System.currentTimeMillis()))
                    refreshWorkspaceFiles()
                    onResult(true, finalName)
                } else {
                    setError("保存到工作区失败：工作区未就绪")
                    onResult(false, "工作区未就绪")
                }
            }.onFailure { error ->
                setError("保存到工作区失败：${error.message}")
                onResult(false, error.message ?: "未知错误")
            }
        }
    }

    /** 将工作区文件内容作为文本引用插入到输入框。 */
    suspend fun readWorkspaceFileAsText(entry: WorkspaceFileEntry): String? {
        return runCatching {
            withContext(Dispatchers.IO) { workspaceManager.readAsText(entry) }
        }.onFailure { error ->
            setError("读取工作区文件失败：${error.message}")
        }.getOrNull()
    }

    suspend fun readDraftAttachment(uri: android.net.Uri): DraftAttachment = attachmentManager.readDraft(uri)

    fun loadSessionModels(force: Boolean = false) {
        scope.launch {
            if (_state.value.sessionModels.isNotEmpty() && !force) return@launch
            _state.update { it.copy(modelLoading = true) }
            runCatching {
                // 新架构：直接从 config 读模型列表，不需要 pi agent
                configManager.readSnapshot().modelCatalog.models.map { it.toPiModel() }
            }.onSuccess { models ->
                _state.update { it.copy(sessionModels = models, modelLoading = false) }
            }.onFailure { error ->
                val fallback = configManager.readSnapshot().modelCatalog.models.map { it.toPiModel() }
                _state.update { it.copy(sessionModels = fallback, modelLoading = false) }
                if (fallback.isEmpty()) setError("加载模型失败：${error.message}")
            }
        }
    }

    fun setSessionModel(model: PiModel) {
        val rawProvider = model.providerNameOrNull() ?: return setError("模型缺少 provider")
        // 新架构：provider 映射保留用于 session 存储一致性
        val provider = when (rawProvider) {
            "custom", "openai-generic" -> "openai"
            else -> rawProvider
        }
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                // 新架构：不需要 runtime.setModel()，下次 sendPrompt 会用新 config
                repo.patchSession(sessionId) { it.copy(provider = provider, model = model.id, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refresh() }
                .onFailure { error -> setError("切换模型失败：${error.message}") }
        }
    }

    fun setSessionModelManual(provider: String, modelId: String) {
        if (provider.isBlank() || modelId.isBlank()) return setError("provider/model 不能为空")
        setSessionModel(PiModel(id = modelId.trim(), provider = provider.trim()))
    }

    fun chooseThinking(level: String) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                // 新架构：thinking level 存在 session 里，下次 sendPrompt 时传给 ConvConfig
                repo.patchSession(sessionId) { it.copy(thinkingLevel = level, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refresh() }
                .onFailure { error -> setError("切换 Thinking 失败：${error.message}") }
        }
    }

    fun setAutoCompaction(enabled: Boolean) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                // 新架构：auto compaction 由 ConversationManager 的 maxToolRounds 控制
                repo.patchSession(sessionId) { it.copy(autoCompactionEnabled = enabled, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refresh() }
                .onFailure { error -> setError("切换 Compact 失败：${error.message}") }
        }
    }

    fun compact(customInstructions: String? = null) {
        // 上下文压缩：保留 system 消息和最近 1/3 的对话历史，
        // 生成一条摘要消息替代被移除的内容。
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            val messages = _state.value.messages
            if (messages.size <= 6) {
                appendMessage(ChatMessage(newMessageId(), "system", "消息较少，无需压缩。", System.currentTimeMillis()))
                return@launch
            }

            val keepCount = maxOf(4, messages.size / 3)
            val toSummarize = messages.dropLast(keepCount)
            val kept = messages.takeLast(keepCount)

            // 生成简单摘要
            val summary = buildString {
                append("[上下文压缩]\n")
                append("已压缩 ${toSummarize.size} 条早期消息。\n")
                if (customInstructions.isNotBlank()) {
                    append("用户指示：$customInstructions\n")
                }
                append("\n压缩前的关键内容摘要：\n")
                toSummarize.filter { it.role == "user" || it.role == "assistant" }
                    .takeLast(5)
                    .forEach { msg ->
                        val role = if (msg.role == "user") "用户" else "助手"
                        val preview = msg.text.take(200).replace("\n", " ")
                        append("- $role: $preview...\n")
                    }
            }

            // 重置 ConversationManager 并用压缩后的历史恢复
            conversationManager.reset()
            val convConfig = _state.value.activeSessionId?.let { id ->
                val session = repo.listSessions().firstOrNull { it.id == id }
                session?.let { resolveConvConfig(id, it) }
            }
            if (convConfig != null) {
                val restoredMessages = kept.map { msg ->
                    ChatHttpClient.ChatMessageDto(
                        role = msg.role,
                        content = msg.text.ifBlank { null },
                        toolCallId = msg.toolCallId,
                        name = msg.toolName
                    )
                }
                conversationManager.restoreFromMessages(restoredMessages, convConfig)
            }

            _state.update { it.copy(messages = listOf(
                ChatMessage(newMessageId(), "system", summary, System.currentTimeMillis())
            ) + kept) }
            refresh()
        }
    }

    fun getPiConfigSnapshot(): PiConfigSnapshot = configManager.readSnapshot()

    fun savePiConfig(snapshot: PiConfigSnapshot) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { configManager.saveSnapshot(snapshot) } }
                .onSuccess { _state.update { it.copy(piConfig = configManager.readSnapshot(), activity = "Pi 配置已保存；重启 session 后生效") } }
                .onFailure { error -> setError("保存 Pi 配置失败：${error.message}") }
        }
    }

    // ==================== Deep Index Mode ====================

    fun enableDeepIndexMode() {
        deepIndexToolChain.setEnabled(true)
        _state.update { it.copy(activity = "深度索引模式已开启：deep-index 工具链已就绪，agent 将使用 CodeGraph + ECC + Memory 联动分析") }
    }

    fun disableDeepIndexMode() {
        deepIndexToolChain.setEnabled(false)
        _state.update { it.copy(activity = "深度索引模式已关闭") }
    }

    fun isDeepIndexEnabled(): Boolean = deepIndexToolChain.isEnabled()

    suspend fun listFiles(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = workspaceFile(path)
        require(dir.isDirectory) { "目录不存在：${workspaceAbsPath(path)}" }
        dir.listFiles().orEmpty().map { file ->
            FileEntry(
                name = file.name,
                path = workspaceRelPath(file),
                type = if (file.isDirectory) "directory" else "file",
                size = if (file.isFile) file.length() else 0L,
                modifiedAt = Instant.ofEpochMilli(file.lastModified()).toString()
            )
        }
    }

    suspend fun uploadFile(path: String, draft: DraftAttachment): FileEntry = withContext(Dispatchers.IO) {
        val dir = workspaceFile(path).apply { mkdirs() }
        val target = uniqueFile(dir, draft.name)
        target.writeBytes(draft.bytes)
        FileEntry(target.name, workspaceRelPath(target), "file", target.length(), Instant.ofEpochMilli(target.lastModified()).toString())
    }

    suspend fun importInstalledApk(packageName: String, label: String, apkPath: String, destinationPath: String = "."): FileEntry = withContext(Dispatchers.IO) {
        val source = File(apkPath)
        require(source.isFile && source.canRead()) { "无法读取 APK：$apkPath" }
        val dir = workspaceFile(destinationPath).apply { mkdirs() }
        val target = uniqueFile(dir, "${safeFileName(label.ifBlank { packageName }).removeSuffix(".apk")}.apk")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        FileEntry(target.name, workspaceRelPath(target), "file", target.length(), Instant.ofEpochMilli(target.lastModified()).toString())
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        workspaceFile(path).mkdirs()
    }

    suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    suspend fun fileForSharing(path: String): File = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        require(file.isFile) { "文件不存在：${workspaceAbsPath(path)}" }
        file
    }

    suspend fun saveFileAs(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        require(file.isFile) { "文件不存在：${workspaceAbsPath(path)}" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt") ?: error("无法写入目标文件")
        output.use { target -> file.inputStream().use { source -> source.copyTo(target) } }
    }

    suspend fun readFileText(path: String, maxBytes: Long = 512L * 1024L): String = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        require(file.isFile) { "文件不存在：${workspaceAbsPath(path)}" }
        require(file.length() <= maxBytes) { "文件过大：${file.length() / 1024} KiB，仅支持预览 ${maxBytes / 1024} KiB 内文本" }
        file.readText(Charsets.UTF_8)
    }

    fun workspaceAbsPath(path: String): String = when {
        path.startsWith(workspaceProotPath) -> path
        path == "." || path.isBlank() -> workspaceProotPath
        else -> "$workspaceProotPath/${path.trimStart('/')}"
    }

    fun fileRef(path: String): String = attachmentManager.fileRef(path)

    private fun createDefaultSessionWithConfiguredModel(): AgentSessionRecord {
        val snapshot = configManager.readSnapshot()
        val defaultPair = resolveDefaultModel(snapshot)
        return repo.ensureDefaultSession(
            provider = snapshot.defaultProvider.trim().takeIf { it.isNotBlank() } ?: defaultPair?.provider,
            model = snapshot.defaultModel.trim().takeIf { it.isNotBlank() } ?: defaultPair?.id,
            thinkingLevel = snapshot.defaultThinkingLevel.trim().takeIf { it.isNotBlank() }
        )
    }

    private suspend fun refreshRuntimeState(sessionId: String) {
        // 新架构：不依赖 pi agent runtime state，只需 refresh
        refresh()
    }

    private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    private suspend fun loadMessagesInternal(sessionId: String): List<ChatMessage> {
        // 新架构：从 ConversationManager 获取当前消息
        val convMessages = conversationManager.getMessages()
        if (convMessages.isNotEmpty()) {
            return convMessages.mapNotNull { msg ->
                when (msg.role) {
                    "user" -> ChatMessage(newMessageId(), "user", msg.content ?: "", System.currentTimeMillis())
                    "assistant" -> ChatMessage(newMessageId(), "assistant", msg.content ?: "", System.currentTimeMillis())
                    "tool" -> ChatMessage(newMessageId(), "tool", msg.content ?: "", System.currentTimeMillis(),
                        toolCallId = msg.toolCallId, toolName = msg.name, toolResult = msg.content, toolStatus = "done"
                    )
                    "system" -> ChatMessage(newMessageId(), "system", msg.content ?: "", System.currentTimeMillis())
                    else -> null
                }
            }
        }
        // 回退：从 session file 读取（旧数据兼容）
        val session = repo.listSessions().firstOrNull { it.id == sessionId } ?: return emptyList()
        val file = session.sessionFile?.let(::sessionFileToHostFile)?.takeIf { it.isFile } ?: return emptyList()
        val messages = file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) null else runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        }
        return normalizePiMessages(messages)
    }

    private fun sessionFileToHostFile(sessionFile: String): File? {
        val normalized = sessionFile.replace('\\', '/').trim()
        val rel = when {
            normalized.startsWith("$workspaceProotPath/") -> normalized.removePrefix("$workspaceProotPath/")
            normalized.startsWith("/root/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            normalized.startsWith("/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            else -> normalized
        }
        return File(paths.rootfsDir, "root/pi_workspace/$rel")
    }

    private fun setTurnActive(sessionId: String, active: Boolean) {
        if (!active) {
            // 本轮对话结束：把流式缓冲区里残余的 delta 强制 flush 干净，
            // 确保最后一段文字不会因为节拍 flusher 还没到点而丢失。
            finishStreamingFlush()
        }
        repo.updateRuntimeStatus(sessionId, if (active) "working" else "running", null)
        _state.update { it.copy(
            turnActive = active,
            status = if (active) "working" else "running",
            sessions = repo.listSessions(),
            processingPhase = if (active) it.processingPhase else null,
            promptSentAt = if (active) it.promptSentAt else 0L,
            firstDeltaAt = if (active) it.firstDeltaAt else 0L
        ) }
    }

    private fun appendMessage(message: ChatMessage) {
        _state.update { it.copy(messages = it.messages + message) }
    }

    private fun appendSystemError(message: String) {
        // 主动 abort 时不显示 abort 错误
        if (suppressAbortError) {
            if (message.contains("abort", ignoreCase = true) || message.contains("Request was aborted")) {
                suppressAbortError = false
                return
            }
        }
        val text = formatAgentErrorMessage(message)
        _state.update { old ->
            if (old.messages.lastOrNull()?.let { it.role == "system" && it.text == text } == true) old
            else old.copy(messages = old.messages + ChatMessage(newMessageId(), "system", text, System.currentTimeMillis()))
        }
    }

    private fun applyAssistantDeltas(textDelta: String, thinkingDelta: String) {
        if (textDelta.isEmpty() && thinkingDelta.isEmpty()) return
        // 累积到缓冲区，避免每个 delta 都触发一次整列表拷贝 + 全屏重组。
        if (textDelta.isNotEmpty()) pendingTextDelta.append(textDelta)
        if (thinkingDelta.isNotEmpty()) pendingThinkingDelta.append(thinkingDelta)
        // 本轮第一个 delta：立即 flush 一次，让流式游标与首字瞬间出现，
        // 随后启动节拍 flusher（约 30fps）持续合并后续 delta。
        if (!deltaStreaming) {
            deltaStreaming = true
            flushPendingDeltas()
            deltaFlushJob?.cancel()
            deltaFlushJob = scope.launch(Dispatchers.Main.immediate) {
                while (isActive) {
                    delay(STREAM_FLUSH_INTERVAL_MS)
                    flushPendingDeltas()
                }
            }
        }
    }

    /** 把缓冲区里累积的 delta 一次性合并进状态（一次 _state.update）。 */
    private fun flushPendingDeltas() {
        if (pendingTextDelta.isEmpty() && pendingThinkingDelta.isEmpty()) return
        val textDelta = pendingTextDelta.toString()
        val thinkingDelta = pendingThinkingDelta.toString()
        pendingTextDelta.setLength(0)
        pendingThinkingDelta.setLength(0)
        _state.update { old ->
            val messages = old.messages
            val last = messages.lastOrNull()
            if (last?.role == "assistant") {
                // 只更新最后一条消息，避免全列表拷贝
                val updatedLast = last.copy(
                    text = last.text + textDelta,
                    thinking = if (thinkingDelta.isNotEmpty()) (last.thinking ?: "") + thinkingDelta else last.thinking
                )
                // toMutableList + lastIndex 赋值比 + 新列表更高效
                val list = messages.toMutableList()
                list[list.lastIndex] = updatedLast
                old.copy(messages = list)
            } else {
                old.copy(messages = messages + ChatMessage(newMessageId(), "assistant", textDelta, System.currentTimeMillis(), thinking = thinkingDelta.takeIf { it.isNotEmpty() }))
            }
        }
    }

    /** 结束本轮流式：停止节拍 flusher，并把残余 delta 强制 flush 干净，确保不丢字。 */
    private fun finishStreamingFlush() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        flushPendingDeltas()
        deltaStreaming = false
    }

    private fun upsertTool(toolCallId: String, name: String, args: JsonElement?, result: String?, resultMeta: ToolResultMeta?, status: String) {
        _state.update { old ->
            val list = old.messages.toMutableList()
            val idx = list.indexOfFirst { it.role == "tool" && it.toolCallId == toolCallId }
            if (idx >= 0) {
                val current = list[idx]
                list[idx] = current.copy(
                    toolName = name,
                    toolArgs = args ?: current.toolArgs,
                    toolResult = result ?: current.toolResult,
                    toolResultMeta = resultMeta ?: current.toolResultMeta,
                    toolStatus = status,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                list += ChatMessage(newMessageId(), "tool", "", System.currentTimeMillis(), toolCallId = toolCallId, toolName = name, toolArgs = args, toolResult = result, toolResultMeta = resultMeta, toolStatus = status)
            }
            old.copy(messages = list)
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(error = message, activity = message, status = "error", turnActive = false, processingPhase = null, promptSentAt = 0L, firstDeltaAt = 0L) }
    }

    // ==================== 新架构：配置解析 + 事件映射 ====================

    /**
     * 从 PiConfigManager 解析出 ConversationManager 需要的配置。
     * 读取 API Key、Base URL、Model、System Prompt。
     */
    private suspend fun resolveConvConfig(sessionId: String, session: AgentSessionRecord): ConversationManager.ConvConfig? {
        val snapshot = configManager.readSnapshot()
        val userConfig = configManager.readUserConfig()

        // Provider 映射
        val rawProvider = (session.provider ?: snapshot.defaultProvider).trim()
        val providerId = when (rawProvider) {
            "custom", "openai-generic", "openai" -> "openai-generic"
            else -> rawProvider
        }.ifBlank { return null }

        // 从 env 中找 API Key — 优先匹配 provider 对应的环境变量名
        val envKeyForProvider = when (providerId) {
            "openai-generic" -> listOf("OPENAI_API_KEY")
            "anthropic" -> listOf("ANTHROPIC_API_KEY")
            "deepseek" -> listOf("DEEPSEEK_API_KEY")
            "google" -> listOf("GOOGLE_API_KEY", "GEMINI_API_KEY")
            "openrouter" -> listOf("OPENROUTER_API_KEY")
            "moonshot" -> listOf("MOONSHOT_API_KEY")
            "dashscope" -> listOf("DASHSCOPE_API_KEY")
            "ark" -> listOf("ARK_API_KEY")
            "baidu" -> listOf("BAIDU_API_KEY")
            "hunyuan" -> listOf("HUNYUAN_API_KEY")
            "siliconflow" -> listOf("SILICONFLOW_API_KEY")
            "mistral" -> listOf("MISTRAL_API_KEY")
            "groq" -> listOf("GROQ_API_KEY")
            "xai" -> listOf("XAI_API_KEY")
            "together" -> listOf("TOGETHER_API_KEY")
            else -> emptyList()
        }
        val apiKey = envKeyForProvider.firstNotNullOfOrNull { keyName ->
            userConfig.env[keyName]?.takeIf { it.isNotBlank() }
        } ?: userConfig.env.entries.firstOrNull { (_, v) -> v.isNotBlank() }?.value
            ?: return null

        // 从 models.json 找 Base URL — 尝试多个可能的 key
        val modelsObj = runCatching {
            dev.idadroid.util.JsonFormats.pretty.parseToJsonElement(snapshot.modelsText).let {
                (it as? JsonObject)?.get("providers") as? JsonObject
            }
        }.getOrNull()
        // 尝试 providerId 和原始 rawProvider 两种 key
        val providerObj = modelsObj?.get(providerId) as? JsonObject
            ?: modelsObj?.get(rawProvider) as? JsonObject
            ?: modelsObj?.get("openai-generic") as? JsonObject
            ?: modelsObj?.get("openai") as? JsonObject
        val baseUrl = providerObj?.get("baseURL")?.jsonPrimitive?.contentOrNull
            ?: providerObj?.get("baseUrl")?.jsonPrimitive?.contentOrNull
            ?: when (providerId) {
                "openai-generic" -> "https://api.openai.com/v1"
                "deepseek" -> "https://api.deepseek.com/v1"
                "anthropic" -> "https://api.anthropic.com/v1"
                else -> return null
            }

        val model = (session.model ?: snapshot.defaultModel).trim().ifBlank { return null }
        val thinkingLevel = session.thinkingLevel ?: snapshot.defaultThinkingLevel.trim().takeIf { it.isNotBlank() }
        val systemPrompt = snapshot.appendSystem.ifBlank { defaultSystemAppendPrompt(workspaceProotPath) }

        return ConversationManager.ConvConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
            systemPrompt = systemPrompt,
            thinkingLevel = thinkingLevel
        )
    }

    /**
     * 将 ConvEvent 映射到现有 UI 状态更新。
     * 复用 pendingTextDelta / pendingThinkingDelta / upsertTool 等现有机制。
     */
    private fun handleConvEvent(sessionId: String, event: ConversationManager.ConvEvent, promptStartTime: Long) {
        when (event) {
            is ConversationManager.ConvEvent.TextDelta -> {
                if (_state.value.firstDeltaAt == 0L) {
                    _state.update { it.copy(firstDeltaAt = System.currentTimeMillis(), processingPhase = "receiving") }
                }
                applyAssistantDeltas(textDelta = event.text, thinkingDelta = "")
            }
            is ConversationManager.ConvEvent.ThinkingDelta -> {
                if (_state.value.firstDeltaAt == 0L) {
                    _state.update { it.copy(firstDeltaAt = System.currentTimeMillis(), processingPhase = "receiving") }
                }
                applyAssistantDeltas(textDelta = "", thinkingDelta = event.text)
            }
            is ConversationManager.ConvEvent.ToolCallStart -> {
                finishStreamingFlush()
                _state.update { it.copy(processingPhase = "executing_tool") }
                upsertTool(
                    toolCallId = event.toolCallId,
                    name = event.toolName,
                    args = runCatching { json.parseToJsonElement(event.args) }.getOrNull(),
                    result = null,
                    resultMeta = null,
                    status = "running"
                )
            }
            is ConversationManager.ConvEvent.ToolCallResult -> {
                upsertTool(
                    toolCallId = event.toolCallId,
                    name = event.toolName,
                    args = null,
                    result = event.result,
                    resultMeta = null,
                    status = if (event.success) "done" else "error"
                )
                _state.update { it.copy(processingPhase = "connecting") }
            }
            is ConversationManager.ConvEvent.PhaseChange -> {
                _state.update { it.copy(processingPhase = event.phase) }
            }
            is ConversationManager.ConvEvent.Error -> {
                appendSystemError(event.message)
            }
            is ConversationManager.ConvEvent.TokenUsageUpdate -> {
                _state.update { it.copy(
                    activity = "Token 用量 — 输入: ${event.promptTokens} / 输出: ${event.completionTokens} / 总计: ${event.totalTokens}"
                ) }
            }
            is ConversationManager.ConvEvent.Retrying -> {
                appendMessage(ChatMessage(
                    newMessageId(), "system",
                    "网络重试中 (${event.attempt}/3)：${event.reason}，${event.delayMs / 1000}s 后重试...",
                    System.currentTimeMillis()
                ))
            }
            ConversationManager.ConvEvent.TurnEnd -> {
                finishStreamingFlush()
                setTurnActive(sessionId, false)
                repo.updateRuntimeStatus(sessionId, "running", null)
                _state.update { it.copy(processingPhase = null, promptSentAt = 0L, firstDeltaAt = 0L) }
            }
        }
    }

    private fun workspaceFile(path: String): File {
        val normalized = normalizeWorkspacePath(path)
        val rel = when {
            normalized == "." -> ""
            normalized.startsWith("$workspaceProotPath/") -> normalized.removePrefix("$workspaceProotPath/")
            normalized == workspaceProotPath -> ""
            normalized.startsWith("/") -> error("路径必须位于 $workspaceProotPath：$path")
            else -> normalized
        }
        val root = workspaceHostRoot.canonicalFile
        val file = if (rel.isBlank()) root else File(root, rel).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径越界：$path" }
        return file
    }

    private fun normalizeWorkspacePath(path: String): String {
        val trimmed = path.trim().ifBlank { "." }.replace('\\', '/')
        val wsPath = workspaceProotPath
        val prefix = if (trimmed.startsWith(wsPath)) wsPath else ""
        val body = if (prefix.isNotBlank()) trimmed.removePrefix(prefix).trimStart('/') else trimmed
        val parts = mutableListOf<String>()
        body.split('/').forEach { part ->
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        val rel = parts.joinToString("/")
        return if (prefix.isNotBlank()) listOf(prefix, rel).filter { it.isNotBlank() }.joinToString("/") else rel.ifBlank { "." }
    }

    private fun workspaceRelPath(file: File): String {
        val root = workspaceHostRoot.canonicalFile
        val canonical = file.canonicalFile
        return canonical.relativeTo(root).path.replace('\\', '/').ifBlank { "." }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = safeFileName(name).ifBlank { "upload" }
        val dot = safe.lastIndexOf('.').takeIf { it > 0 && it < safe.lastIndex }
        val base = dot?.let { safe.substring(0, it) } ?: safe
        val ext = dot?.let { safe.substring(it) }.orEmpty()
        var candidate = File(dir, safe)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$i$ext")
            i++
        }
        return candidate
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim()
        .take(120)
        .ifBlank { "upload" }

    private fun requireReady() {
        require(paths.readyMarker.isFile && paths.rootfsDir.isDirectory) { "rootfs 尚未 ready，请先导入并验证环境" }
    }

    private fun resolveDefaultModel(snapshot: PiConfigSnapshot): AgentConfiguredModel? {
        val provider = snapshot.defaultProvider.trim().takeIf { it.isNotBlank() }
        val model = snapshot.defaultModel.trim().takeIf { it.isNotBlank() }
        val models = snapshot.modelCatalog.models
        if (provider != null && model != null) {
            models.firstOrNull { it.provider == provider && it.id == model }?.let { return it }
            return AgentConfiguredModel(provider = provider, id = model)
        }
        if (provider != null) models.firstOrNull { it.provider == provider }?.let { return it }
        if (model != null) models.firstOrNull { it.id == model }?.let { return it }
        return models.firstOrNull()
    }

    private fun mergeModels(primary: List<PiModel>, fallback: List<PiModel>): List<PiModel> {
        val seen = linkedSetOf<String>()
        return (primary + fallback).filter { model ->
            val key = "${model.providerNameOrNull().orEmpty()}/${model.id}"
            seen.add(key)
        }
    }

    private fun modelLabel(session: AgentSessionRecord?): String = listOfNotNull(
        session?.provider?.takeIf { it.isNotBlank() },
        session?.model?.takeIf { it.isNotBlank() },
        session?.thinkingLevel?.takeIf { it.isNotBlank() }?.let { "thinking=$it" }
    ).joinToString(" / ")

    private companion object {
        /** 流式 delta 合并 flush 节拍，约 30fps：兼顾逐字流式的视觉流畅与状态更新开销。 */
        const val STREAM_FLUSH_INTERVAL_MS = 33L
        /** 单次 prompt 调用的超时时间。超过后中止并提示用户，避免 UI 永久卡在 working 状态。 */
        const val PROMPT_TIMEOUT_MS = 180_000L // 3 分钟
    }
}
