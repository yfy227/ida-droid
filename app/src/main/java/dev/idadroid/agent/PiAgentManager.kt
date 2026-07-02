package dev.idadroid.agent

import android.content.Context
import android.net.Uri
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.env.PiWorkspaceMaterializer
import dev.idadroid.proot.ProotBinaryInstaller
import java.io.File
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class PiAgentManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val repo = AgentSessionRepository(paths)
    private val configManager = PiConfigManager(paths)
    val aiConfigTools = AiConfigTools(paths, configManager)
    private val attachmentManager = AttachmentManager(appContext, paths)
    val workspaceManager = WorkspaceManager(appContext, paths)
    private val deepIndexToolChain = dev.idadroid.deepindex.DeepIndexToolChain(appContext, paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    private val runtimes = mutableMapOf<String, PiRpcRuntime>()
    private val rawLines = ArrayDeque<String>()
    // Send lock: prevents concurrent sendPrompt calls from racing. Operit uses
    // a similar coordination pattern in MessageCoordinationDelegate.
    private val sendMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var sendingInProgress = false

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
                stopSessionInternal(id)
                repo.deleteSession(id)
            }.onSuccess { refresh(createDefaultIfReady = true) }
                .onFailure { error -> setError("删除 Session 失败：${error.message}") }
        }
    }

    fun startSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: createDefaultSessionWithConfiguredModel().id
            runCatching { startSessionInternal(sessionId) }
                .onSuccess { refreshRuntimeState(sessionId) }
                .onFailure { error -> setError("启动 pi RPC 失败：${error.message}") }
        }
    }

    fun stopSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            runCatching { stopSessionInternal(sessionId) }
                .onSuccess { refresh() }
                .onFailure { error -> setError("停止 pi RPC 失败：${error.message}") }
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
        // Acquire the send mutex so only one prompt flows through the RPC
        // runtime at a time. If the user sends again while we're still
        // attaching files or waiting for the runtime, the second call waits.
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
                _state.update { it.copy(activity = "正在发送消息…") }
                val expanded = attachmentManager.expandFileReferencesForPrompt(displayMessage, session.cwd)
                val runtime = startSessionInternal(sessionId)
                when (sendMode) {
                    "steer" -> runtime.steer(expanded.message)
                    "followUp" -> runtime.followUp(expanded.message)
                    else -> {
                        val wasWorking = _state.value.isWorking
                        if (wasWorking) runCatching { runtime.abort() }
                        // Wrap the prompt call with a timeout so a hung RPC
                        // doesn't leave the UI stuck in "working" forever.
                        runCatching {
                            kotlinx.coroutines.withTimeout(PROMPT_TIMEOUT_MS) {
                                runtime.prompt(expanded.message, expanded.images)
                            }
                        }.onFailure { e ->
                            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                                appendMessage(ChatMessage(newMessageId(), "system", "发送超时，请检查 Agent 是否响应", System.currentTimeMillis()))
                                runCatching { runtime.abort() }
                            } else {
                                throw e
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                setTurnActive(sessionId, false)
                appendMessage(ChatMessage(newMessageId(), "system", "发送失败：${e.message}", System.currentTimeMillis()))
                setError("发送失败：${e.message}")
            }
        }
    }

    fun abort(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            runCatching { runtimes[sessionId]?.abort() }
                .onSuccess {
                    setTurnActive(sessionId, false)
                    repo.updateRuntimeStatus(sessionId, "running", null)
                    refreshRuntimeState(sessionId)
                }
                .onFailure { error -> setError("中止失败：${error.message}") }
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
        rawLines.clear()
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
            val sessionId = _state.value.activeSessionId ?: createDefaultSessionWithConfiguredModel().id
            _state.update { it.copy(modelLoading = true) }
            runCatching {
                val runtime = startSessionInternal(sessionId)
                val data = runtime.availableModels()
                ((data as? JsonObject)?.get("models") as? JsonArray).orEmpty().mapNotNull { item ->
                    runCatching { json.decodeFromJsonElement<PiModel>(item) }.getOrNull()
                }
            }.onSuccess { models ->
                val fallback = configManager.readSnapshot().modelCatalog.models.map { it.toPiModel() }
                _state.update { it.copy(sessionModels = mergeModels(models, fallback), modelLoading = false) }
            }.onFailure { error ->
                val fallback = configManager.readSnapshot().modelCatalog.models.map { it.toPiModel() }
                _state.update { it.copy(sessionModels = fallback, modelLoading = false) }
                if (fallback.isEmpty()) setError("加载模型失败：${error.message}")
            }
        }
    }

    fun setSessionModel(model: PiModel) {
        val provider = model.providerNameOrNull() ?: return setError("模型缺少 provider")
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                val runtime = runtimes[sessionId]?.takeIf { it.isActive() }
                if (runtime != null) runtime.setModel(provider, model.id)
                repo.patchSession(sessionId) { it.copy(provider = provider, model = model.id, lastActiveAt = Instant.now().toString()) }
            }.onSuccess {
                if (runtimes[sessionId]?.isActive() == true) refreshRuntimeState(sessionId) else refresh()
            }.onFailure { error -> setError("切换模型失败：${error.message}") }
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
                val runtime = startSessionInternal(sessionId)
                runtime.setThinkingLevel(level)
                repo.patchSession(sessionId) { it.copy(thinkingLevel = level, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refreshRuntimeState(sessionId) }
                .onFailure { error -> setError("切换 Thinking 失败：${error.message}") }
        }
    }

    fun setAutoCompaction(enabled: Boolean) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                val runtime = startSessionInternal(sessionId)
                runtime.setAutoCompaction(enabled)
                repo.patchSession(sessionId) { it.copy(autoCompactionEnabled = enabled, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refreshRuntimeState(sessionId) }
                .onFailure { error -> setError("切换 Compact 失败：${error.message}") }
        }
    }

    fun compact(customInstructions: String? = null) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                appendMessage(ChatMessage(newMessageId(), "system", if (customInstructions.isNullOrBlank()) "已触发手动上下文压缩。" else "已触发手动上下文压缩：$customInstructions", System.currentTimeMillis()))
                startSessionInternal(sessionId).compact(customInstructions)
            }.onFailure { error -> setError("Compact 失败：${error.message}") }
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
        path.startsWith("/root/pi_workspace") -> path
        path == "." || path.isBlank() -> "/root/pi_workspace"
        else -> "/root/pi_workspace/${path.trimStart('/')}"
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

    private suspend fun startSessionInternal(sessionId: String): PiRpcRuntime = withContext(Dispatchers.IO) {
        requireReady()
        ProotBinaryInstaller(appContext, paths).ensureInstalled()
        PiWorkspaceMaterializer().materialize(paths.rootfsDir)
        val snapshot = configManager.readSnapshot()
        require(snapshot.modelCatalog.isUsable) {
            snapshot.modelCatalog.parseError?.let { "models.json 配置无效：$it" } ?: "请先在 models.json 中配置至少一个 provider 和 model"
        }
        val session = repo.patchSession(sessionId) {
            require(!it.provider.isNullOrBlank() && !it.model.isNullOrBlank()) { "当前 Session 未配置 provider/model，请先选择模型" }
            it.copy(
                status = "starting",
                error = null,
                thinkingLevel = it.thinkingLevel ?: snapshot.defaultThinkingLevel.trim().takeIf { value -> value.isNotBlank() },
                lastActiveAt = Instant.now().toString()
            )
        }
        withContext(Dispatchers.Main) {
            _state.update { it.copy(status = "starting", error = null, activity = "正在启动 pi --mode rpc...", activeSessionId = sessionId, sessions = repo.listSessions()) }
        }
        runtimes[sessionId]?.takeIf { it.isActive() }?.let { return@withContext it }
        runtimes[sessionId]?.stop()
        val events = MutableSharedFlow<PiRuntimeEvent>(extraBufferCapacity = 256)
        val runtime = PiRpcRuntime(appContext, session, events, paths)
        runtimes[sessionId] = runtime
        scope.launch(Dispatchers.Main.immediate) {
            events.collect { event ->
                try {
                    handleRuntimeEvent(sessionId, event)
                } catch (e: Exception) {
                    // A single failing event must NOT kill the collector —
                    // otherwise all subsequent streaming events (text deltas,
                    // turn_end, RPC responses) are silently dropped and the
                    // user sees "sent but no reply" indefinitely.
                    android.util.Log.e("PiAgentManager", "handleRuntimeEvent failed for $sessionId", e)
                    _state.update { it.copy(activity = "事件处理异常：${e.message}") }
                }
            }
        }
        runtime.start()
        runtime
    }

    private suspend fun stopSessionInternal(sessionId: String) {
        runtimes.remove(sessionId)?.stop()
        repo.updateRuntimeStatus(sessionId, "stopped", null)
    }

    private suspend fun refreshRuntimeState(sessionId: String) {
        try {
            refreshRuntimeStateInternal(sessionId)
        } catch (e: Exception) {
            android.util.Log.e("PiAgentManager", "refreshRuntimeState failed for $sessionId", e)
        }
    }

    private suspend fun refreshRuntimeStateInternal(sessionId: String) {
        val runtime = runtimes[sessionId]?.takeIf { it.isActive() } ?: run {
            refresh()
            return
        }
        val state = runCatching { runtime.getState(timeoutMs = 30_000) }.getOrNull()
        val obj = state as? JsonObject
        val sessionFile = obj?.string("sessionFile")
        if (!sessionFile.isNullOrBlank()) repo.setSessionFile(sessionId, sessionFile)
        val stats = runCatching { json.decodeFromJsonElement<SessionStats>(runtime.getStats()) }.getOrNull()
        if (obj != null) {
            val modelObj = obj.obj("model")
            repo.patchSession(sessionId) { session ->
                session.copy(
                    status = if (session.status == "starting") "running" else session.status,
                    provider = modelObj?.string("provider") ?: modelObj?.string("providerId") ?: modelObj?.string("providerName") ?: session.provider,
                    model = modelObj?.string("id") ?: session.model,
                    thinkingLevel = obj.string("thinkingLevel") ?: session.thinkingLevel,
                    autoCompactionEnabled = obj.boolean("autoCompactionEnabled") ?: session.autoCompactionEnabled,
                    lastActiveAt = Instant.now().toString()
                )
            }
        }
        refresh()
        _state.update { it.copy(activeStats = stats ?: it.activeStats) }
    }

    private suspend fun loadMessagesInternal(sessionId: String): List<ChatMessage> {
        val runtime = runtimes[sessionId]?.takeIf { it.isActive() }
        if (runtime != null) {
            val data = runCatching { runtime.getMessages() }.getOrNull()
            val messages = ((data as? JsonObject)?.get("messages") as? JsonArray)?.toList().orEmpty()
            if (messages.isNotEmpty()) return normalizePiMessages(messages)
        }
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
            normalized.startsWith("/root/pi_workspace/") -> normalized.removePrefix("/root/pi_workspace/")
            normalized.startsWith("/root/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            normalized.startsWith("/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            else -> normalized
        }
        return File(paths.rootfsDir, "root/pi_workspace/$rel")
    }

    private suspend fun handleRuntimeEvent(sessionId: String, event: PiRuntimeEvent) {
        when (event) {
            is PiRuntimeEvent.Status -> {
                repo.updateRuntimeStatus(sessionId, event.status, event.error)
                _state.update { it.copy(status = event.status, error = event.error, sessions = repo.listSessions(), activity = event.error ?: event.status) }
            }
            is PiRuntimeEvent.Stderr -> {
                val next = (_state.value.stderrTail + event.text).takeLast(12_000)
                _state.update { it.copy(stderrTail = next) }
            }
            is PiRuntimeEvent.RawStdout -> appendRaw("stdout: ${event.line}")
            is PiRuntimeEvent.Exited -> {
                repo.updateRuntimeStatus(sessionId, "error", event.error)
                _state.update { it.copy(status = "error", error = event.error, turnActive = false, sessions = repo.listSessions()) }
            }
            is PiRuntimeEvent.RpcEvent -> handleAgentEvent(sessionId, event.event)
        }
    }

    private suspend fun handleAgentEvent(sessionId: String, event: JsonObject) {
        when (event.string("type")) {
            "agent_start", "turn_start", "message_start" -> setTurnActive(sessionId, true)
            "agent_end", "turn_end" -> {
                setTurnActive(sessionId, false)
                refreshRuntimeState(sessionId)
            }
            "message_end" -> {
                setTurnActive(sessionId, false)
                appendAssistantError(event.obj("message"))
                refreshRuntimeState(sessionId)
            }
            "message_update" -> handleMessageUpdate(sessionId, event.obj("assistantMessageEvent") ?: return)
            "tool_execution_start" -> {
                setTurnActive(sessionId, true)
                upsertTool(
                    toolCallId = event.string("toolCallId") ?: "${event.string("toolName")}-${System.currentTimeMillis()}",
                    name = event.string("toolName") ?: "tool",
                    args = event["args"],
                    result = null,
                    resultMeta = null,
                    status = "running"
                )
            }
            "tool_execution_update" -> upsertTool(
                toolCallId = event.string("toolCallId") ?: "${event.string("toolName")}-${System.currentTimeMillis()}",
                name = event.string("toolName") ?: "tool",
                args = event["args"],
                result = resultToText(event["partialResult"]),
                resultMeta = toolResultMeta(event["partialResult"]),
                status = "running"
            )
            "tool_execution_end" -> upsertTool(
                toolCallId = event.string("toolCallId") ?: "${event.string("toolName")}-${System.currentTimeMillis()}",
                name = event.string("toolName") ?: "tool",
                args = event["args"],
                result = resultToText(event["result"]),
                resultMeta = toolResultMeta(event["result"]),
                status = if (event.boolean("isError") == true) "error" else "done"
            )
            "queue_update" -> _state.update {
                it.copy(activeQueue = QueueState(event.arrayStrings("steering"), event.arrayStrings("followUp")))
            }
            "compaction_start" -> {
                setTurnActive(sessionId, true)
                appendMessage(ChatMessage(newMessageId(), "system", "正在压缩上下文：${event.string("reason") ?: "manual"}", System.currentTimeMillis()))
            }
            "compaction_end" -> {
                setTurnActive(sessionId, false)
                appendMessage(ChatMessage(newMessageId(), "system", when {
                    !event.string("errorMessage").isNullOrBlank() -> "上下文压缩失败：${event.string("errorMessage")}"
                    event.boolean("aborted") == true -> "上下文压缩已取消"
                    else -> "上下文压缩完成"
                }, System.currentTimeMillis()))
                refreshRuntimeState(sessionId)
            }
            "extension_error" -> appendSystemError(
                listOfNotNull(
                    event.string("extensionPath")?.takeIf { it.isNotBlank() }?.let { "Extension: $it" },
                    event.string("event")?.takeIf { it.isNotBlank() }?.let { "Event: $it" },
                    event.string("error")
                ).joinToString("\n").ifBlank { "extension error" }
            )
            "extension_ui_request" -> {
                if (event.string("method") == "notify" && event.string("notifyType") == "error") {
                    appendSystemError(event.string("message") ?: "extension notify error")
                } else {
                    appendRaw("event: ${event.string("type") ?: event.toString()}")
                }
            }
            "auto_retry_end" -> if (event.boolean("success") == false) event.string("finalError")?.let { appendSystemError(it) }
            else -> appendRaw("event: ${event.string("type") ?: event.toString()}")
        }
    }

    private fun handleMessageUpdate(sessionId: String, delta: JsonObject) {
        when (delta.string("type")) {
            "start", "text_start", "thinking_start", "reasoning_start" -> setTurnActive(sessionId, true)
            "text_delta" -> applyAssistantDeltas(textDelta = delta.string("delta") ?: delta.string("text") ?: "", thinkingDelta = "")
            "thinking_delta", "reasoning_delta" -> applyAssistantDeltas(textDelta = "", thinkingDelta = delta.string("delta") ?: delta.string("text") ?: "")
            "toolcall_start", "toolcall_delta", "toolcall_end" -> {
                setTurnActive(sessionId, true)
                val tool = toolCallFromDelta(delta)
                val id = tool.toolCallId ?: delta.string("id") ?: delta.string("contentIndex") ?: "tool"
                upsertTool(id, tool.toolName ?: "tool", tool.toolArgs, null, null, "pending")
            }
            "done", "error" -> setTurnActive(sessionId, false)
        }
    }

    private fun setTurnActive(sessionId: String, active: Boolean) {
        if (!active) {
            // 本轮对话结束：把流式缓冲区里残余的 delta 强制 flush 干净，
            // 确保最后一段文字不会因为节拍 flusher 还没到点而丢失。
            finishStreamingFlush()
        }
        repo.updateRuntimeStatus(sessionId, if (active) "working" else "running", null)
        _state.update { it.copy(turnActive = active, status = if (active) "working" else "running", sessions = repo.listSessions()) }
    }

    private fun appendMessage(message: ChatMessage) {
        _state.update { it.copy(messages = it.messages + message) }
    }

    private fun appendAssistantError(message: JsonObject?) {
        if (message?.string("role") != "assistant") return
        appendSystemError(assistantErrorMessage(message) ?: return)
    }

    private fun appendSystemError(message: String) {
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
            val list = old.messages.toMutableList()
            val last = list.lastOrNull()
            if (last?.role == "assistant") {
                list[list.lastIndex] = last.copy(
                    text = last.text + textDelta,
                    thinking = if (thinkingDelta.isNotEmpty()) (last.thinking ?: "") + thinkingDelta else last.thinking
                )
            } else {
                list += ChatMessage(newMessageId(), "assistant", textDelta, System.currentTimeMillis(), thinking = thinkingDelta.takeIf { it.isNotEmpty() })
            }
            old.copy(messages = list)
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

    private fun appendRaw(line: String) {
        rawLines += "${Instant.now()} $line"
        while (rawLines.size > 120) rawLines.removeFirst()
        _state.update { it.copy(rawLogLines = rawLines.toList()) }
    }

    private fun setError(message: String) {
        _state.update { it.copy(error = message, activity = message, status = "error", turnActive = false) }
    }

    private fun workspaceFile(path: String): File {
        val normalized = normalizeWorkspacePath(path)
        val rel = when {
            normalized == "." -> ""
            normalized.startsWith("/root/pi_workspace/") -> normalized.removePrefix("/root/pi_workspace/")
            normalized == "/root/pi_workspace" -> ""
            normalized.startsWith("/") -> error("路径必须位于 /root/pi_workspace：$path")
            else -> normalized
        }
        val root = File(paths.rootfsDir, "root/pi_workspace").canonicalFile
        val file = if (rel.isBlank()) root else File(root, rel).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径越界：$path" }
        return file
    }

    private fun normalizeWorkspacePath(path: String): String {
        val trimmed = path.trim().ifBlank { "." }.replace('\\', '/')
        val prefix = if (trimmed.startsWith("/root/pi_workspace")) "/root/pi_workspace" else ""
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
        val root = File(paths.rootfsDir, "root/pi_workspace").canonicalFile
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
        const val PROMPT_TIMEOUT_MS = 600_000L // 10 分钟
    }
}
