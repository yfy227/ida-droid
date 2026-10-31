package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString

class AgentSessionRepository(
    context: Context,
    private val paths: EnvironmentPaths
) {
    private val settings = dev.idadroid.settings.IdaDroidSettings(context.applicationContext)
    private val storeFile: java.io.File get() {
        val ws = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
        val rel = ws.removePrefix("/").ifBlank { "root/pi_workspace" }
        return java.io.File(paths.rootfsDir, "$rel/.idadroid/agent-sessions.json")
    }
    // Protects all read/write access to storeFile so that concurrent coroutines don't race.
    private val lock = Any()

    fun loadStore(): AgentSessionStore = synchronized(lock) { loadStoreInternal() }

    private fun loadStoreInternal(): AgentSessionStore = synchronized(lock) {
        runCatching {
            if (!storeFile.isFile) return@synchronized AgentSessionStore()
            JsonFormats.pretty.decodeFromString<AgentSessionStore>(storeFile.readText())
        }.getOrDefault(AgentSessionStore())
    }

    fun saveStore(store: AgentSessionStore) = synchronized(lock) { saveStoreInternal(store) }

    private fun saveStoreInternal(store: AgentSessionStore) = synchronized(lock) {
        storeFile.parentFile?.mkdirs()
        val tmp = java.io.File(storeFile.parentFile, "${storeFile.name}.tmp")
        tmp.writeText(JsonFormats.pretty.encodeToString(store))
        if (!tmp.renameTo(storeFile)) {
            // renameTo 在某些文件系统上可能失败（如目标被占用），回退到 copy+delete
            storeFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun listSessions(): List<AgentSessionRecord> = loadStore().sessions

    fun activeSessionId(): String? = loadStore().activeSessionId

    fun ensureDefaultSession(provider: String? = null, model: String? = null, thinkingLevel: String? = null): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        val existing = store.sessions.firstOrNull { it.id == store.activeSessionId } ?: store.sessions.firstOrNull()
        if (existing != null) {
            val patched = existing.copy(
                provider = existing.provider ?: provider?.trim()?.takeIf { it.isNotBlank() },
                model = existing.model ?: model?.trim()?.takeIf { it.isNotBlank() },
                thinkingLevel = existing.thinkingLevel ?: thinkingLevel?.trim()?.takeIf { it.isNotBlank() }
            )
            val nextSessions = if (patched == existing) store.sessions else store.sessions.map { if (it.id == existing.id) patched else it }
            if (store.activeSessionId == existing.id && patched == existing) return@synchronized existing
            saveStoreInternal(store.copy(sessions = nextSessions, activeSessionId = existing.id))
            return@synchronized patched
        }
        val now = Instant.now().toString()
        val session = AgentSessionRecord(
            id = "session-${UUID.randomUUID()}",
            name = "默认会话",
            status = "idle",
            cwd = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH },
            provider = provider?.trim()?.takeIf { it.isNotBlank() },
            model = model?.trim()?.takeIf { it.isNotBlank() },
            thinkingLevel = thinkingLevel?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now
        )
        saveStoreInternal(AgentSessionStore(listOf(session), session.id))
        session
    }

    fun createSession(name: String? = null, provider: String? = null, model: String? = null, thinkingLevel: String? = null): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        val now = Instant.now().toString()
        val session = AgentSessionRecord(
            id = "session-${UUID.randomUUID()}",
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: "Session ${store.sessions.size + 1}",
            status = "idle",
            cwd = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH },
            provider = provider?.trim()?.takeIf { it.isNotBlank() },
            model = model?.trim()?.takeIf { it.isNotBlank() },
            thinkingLevel = thinkingLevel?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now
        )
        saveStoreInternal(store.copy(sessions = store.sessions + session, activeSessionId = session.id))
        session
    }

    fun setActive(id: String): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        val session = store.sessions.firstOrNull { it.id == id } ?: error("session 不存在：$id")
        saveStoreInternal(store.copy(activeSessionId = id))
        session
    }

    fun patchSession(id: String, patch: (AgentSessionRecord) -> AgentSessionRecord): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        var updated: AgentSessionRecord? = null
        val sessions = store.sessions.map { current ->
            if (current.id == id) {
                patch(current).copy(updatedAt = Instant.now().toString()).also { updated = it }
            } else current
        }
        val result = updated ?: error("session 不存在：$id")
        saveStoreInternal(store.copy(sessions = sessions, activeSessionId = store.activeSessionId ?: id))
        result
    }

    fun deleteSession(id: String) = synchronized(lock) {
        val store = loadStoreInternal()
        val nextSessions = store.sessions.filterNot { it.id == id }
        val nextActive = when {
            store.activeSessionId != id -> store.activeSessionId
            nextSessions.isNotEmpty() -> nextSessions.first().id
            else -> null
        }
        saveStoreInternal(store.copy(sessions = nextSessions, activeSessionId = nextActive))
    }

    fun updateRuntimeStatus(id: String, status: String, error: String? = null): AgentSessionRecord? = runCatching {
        patchSession(id) { it.copy(status = status, error = error ?: it.error, lastActiveAt = Instant.now().toString()) }
    }.getOrNull()

    fun setSessionFile(id: String, sessionFile: String?): AgentSessionRecord? {
        val value = sessionFile?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { patchSession(id) { it.copy(sessionFile = value, lastActiveAt = Instant.now().toString()) } }.getOrNull()
    }
}
