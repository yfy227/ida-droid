package dev.idadroid.mcp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Manages file transfers from the Android host into the proot container.
 *
 * Files are copied to `/root/.mcp-transfer/` inside the container
 * (mapped to `rootfsDir/root/.mcp-transfer/` on the host). This is
 * **independent of the agent workspace** (`/root/pi_workspace`) so that
 * transferred files don't clutter the workspace and can be referenced
 * separately by MCP tools.
 *
 * A JSON manifest at `.mcp-transfer/manifest.json` records every transfer.
 * The HTTP server exposes this manifest so the agent can discover files.
 * When the agent calls `idadroid-file open <name>`, the script first checks
 * this manifest; if a recent transfer matches, it returns the path and
 * asks the agent whether to use it.
 */
class FileTransferManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Host-side directory that maps to `/root/.mcp-transfer` in the container. */
    private val transferHostDir: File get() = File(paths.rootfsDir, "root/.mcp-transfer")
    private val manifestFile: File get() = File(transferHostDir, "manifest.json")

    /** Proot-visible path for the transfer directory. */
    val transferProotDir: String get() = "/root/.mcp-transfer"

    private val mutex = Mutex()

    @Serializable
    data class TransferEntry(
        val id: String,
        val name: String,
        val hostPath: String,
        val prootPath: String,
        val size: Long,
        val mimeType: String,
        val transferredAt: Long
    )

    @Serializable
    data class Manifest(
        val entries: List<TransferEntry> = emptyList(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    /** Ensure the transfer directory and manifest exist. */
    fun ensureReady() {
        transferHostDir.mkdirs()
        if (!manifestFile.isFile) saveManifest(Manifest())
    }

    /** Returns the current manifest (empty if not yet created). */
    fun loadManifest(): Manifest {
        ensureReady()
        return runCatching {
            json.decodeFromString(Manifest.serializer(), manifestFile.readText())
        }.getOrDefault(Manifest())
    }

    /** Returns all transfer entries, newest first. */
    fun listTransfers(): List<TransferEntry> = loadManifest().entries.sortedByDescending { it.transferredAt }

    /** Returns the most recently transferred file, or null if none. */
    fun latestTransfer(): TransferEntry? = listTransfers().firstOrNull()

    /** Returns recent transfers within the given time window (ms), newest first. */
    fun recentTransfers(withinMs: Long = 300_000): List<TransferEntry> {
        val cutoff = System.currentTimeMillis() - withinMs
        return listTransfers().filter { it.transferredAt >= cutoff }
    }

    /** Finds a transfer entry whose name (case-insensitive) or id matches [query]. */
    fun findTransfer(query: String): TransferEntry? {
        val q = query.trim()
        if (q.isBlank()) return null
        return listTransfers().firstOrNull { entry ->
            entry.id == q ||
                entry.name.equals(q, ignoreCase = true) ||
                entry.name.contains(q, ignoreCase = true) ||
                entry.prootPath.endsWith(q, ignoreCase = true)
        }
    }

    /**
     * Transfers a file identified by Android [uri] into the container.
     * Returns the resulting [TransferEntry] or throws on failure.
     */
    suspend fun transferUri(uri: Uri): TransferEntry = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            ensureReady()
            val displayName = queryDisplayName(uri) ?: "transfer-${System.currentTimeMillis()}.bin"
            val target = uniqueFile(transferHostDir, displayName)
            val mime = appContext.contentResolver.getType(uri) ?: guessMimeType(displayName)
            var size = 0L
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    size = input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法读取所选文件")

            val entry = TransferEntry(
                id = "xfr-${System.currentTimeMillis()}-${target.name.hashCode().and(0xFFFF)}",
                name = target.name,
                hostPath = target.absolutePath,
                prootPath = "$transferProotDir/${target.name}",
                size = size,
                mimeType = mime,
                transferredAt = System.currentTimeMillis()
            )
            appendEntry(entry)
            entry
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Transfers a file identified by a host-side [path] (e.g. `/sdcard/foo.bin`)
     * into the container. Returns the resulting [TransferEntry] or throws on failure.
     */
    suspend fun transferHostPath(path: String): TransferEntry = withContext(Dispatchers.IO) {
        val source = File(path)
        require(source.isFile) { "文件不存在或不可读：$path" }
        mutex.lock()
        try {
            ensureReady()
            val target = uniqueFile(transferHostDir, source.name)
            val size = source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val entry = TransferEntry(
                id = "xfr-${System.currentTimeMillis()}-${target.name.hashCode().and(0xFFFF)}",
                name = target.name,
                hostPath = target.absolutePath,
                prootPath = "$transferProotDir/${target.name}",
                size = size,
                mimeType = guessMimeType(target.name),
                transferredAt = System.currentTimeMillis()
            )
            appendEntry(entry)
            entry
        } finally {
            mutex.unlock()
        }
    }

    /** Removes a transfer entry (and deletes the underlying file) by id. */
    suspend fun removeTransfer(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val manifest = loadManifest()
            val entry = manifest.entries.firstOrNull { it.id == id } ?: return@withContext false
            runCatching { File(entry.hostPath).delete() }
            saveManifest(Manifest(manifest.entries.filterNot { it.id == id }))
            true
        } finally {
            mutex.unlock()
        }
    }

    /** Serialises the manifest as JSON (for HTTP responses). */
    fun manifestJson(): String {
        return json.encodeToString(Manifest.serializer(), loadManifest())
    }

    private fun appendEntry(entry: TransferEntry) {
        val manifest = loadManifest()
        val updated = manifest.copy(
            entries = (manifest.entries + entry).takeLast(MAX_MANIFEST_ENTRIES),
            updatedAt = System.currentTimeMillis()
        )
        saveManifest(updated)
    }

    private fun saveManifest(manifest: Manifest) {
        ensureReady()
        manifestFile.writeText(json.encodeToString(Manifest.serializer(), manifest))
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim()
        .take(120)
        .ifBlank { "transfer.bin" }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = safeFileName(name)
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

    private fun guessMimeType(name: String): String = when {
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".bin", true) -> "application/octet-stream"
        name.endsWith(".so", true) -> "application/x-sharedlib"
        name.endsWith(".dex", true) -> "application/x-dex"
        name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
        name.endsWith(".zip", true) -> "application/zip"
        name.endsWith(".gz", true) -> "application/gzip"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        else -> "application/octet-stream"
    }

    companion object {
        private const val MAX_MANIFEST_ENTRIES = 64
    }
}
