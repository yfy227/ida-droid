package dev.idadroid.agent

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.idadroid.env.EnvironmentPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * 工作区管理器。
 *
 * 负责通过 Android Storage Access Framework (SAF) 让用户选择一个文件夹作为"工作区"，
 * 之后所有由 Agent 生成的代码文件都可以写入到这个工作区中；同时程序也可以读取工作区
 * 中的文件，将其传输到 pi_workspace 容器内供 Agent 使用。
 *
 * 设计要点：
 * 1. 使用 SharedPreferences 持久化已授权的 tree URI，重启后仍可恢复。
 * 2. 通过 takePersistableUriPermission 确保重启后仍能访问该文件夹。
 * 3. 所有文件操作都在 IO 线程执行，避免阻塞主线程。
 * 4. 提供 listFiles / readFile / writeFile / importToContainer / exportFromContainer 等能力。
 */
class WorkspaceManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    /** 当前工作区根 URI（已授权的 tree URI），未选择时为 null。 */
    val currentWorkspaceUri: Uri?
        get() = prefs.getString(KEY_WORKSPACE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 当前工作区的显示名称（用户选择的文件夹名）。 */
    val currentWorkspaceName: String
        get() {
            val uri = currentWorkspaceUri ?: return DEFAULT_WORKSPACE_NAME
            return runCatching { queryDisplayName(uri) }.getOrDefault(DEFAULT_WORKSPACE_NAME)
        }

    /** 是否已选择工作区。 */
    val hasWorkspace: Boolean get() = currentWorkspaceUri != null

    /**
     * 设置工作区。在 Activity 中通过 OpenDocumentTree 获取到 uri 后调用此方法。
     * 会自动 takePersistableUriPermission 以保证重启后仍可访问。
     */
    fun setWorkspace(uri: Uri): Boolean {
        return try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString(KEY_WORKSPACE_URI, uri.toString()).apply()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "无法获取工作区持久权限", e)
            false
        }
    }

    /** 清除当前工作区设置。 */
    fun clearWorkspace() {
        currentWorkspaceUri?.let { uri ->
            runCatching {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.releasePersistableUriPermission(uri, flags)
            }
        }
        prefs.edit().remove(KEY_WORKSPACE_URI).apply()
    }

    /**
     * 列出工作区内指定子目录下的文件和文件夹。
     * @param relativePath 相对于工作区根的路径，如 "" 或 "src/main"
     * @return 文件条目列表
     */
    suspend fun listFiles(relativePath: String = ""): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val rootUri = currentWorkspaceUri ?: return@withContext emptyList()
        runCatching {
            val treeDocId = DocumentsContract.getTreeDocumentId(rootUri)
            // Avoid naive string concatenation with "/" — docId separator is provider-defined.
            // The only safe way is to walk through children for sub-directories.
            val targetDocId = if (relativePath.isBlank()) {
                treeDocId
            } else {
                // Resolve each path segment via SAF child queries instead of string-joining.
                resolveDocId(rootUri, treeDocId, relativePath) ?: return@withContext emptyList()
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, targetDocId)
            val entries = mutableListOf<WorkspaceFileEntry>()
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val size = cursor.getLong(3)
                    val modified = cursor.getLong(4)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    entries += WorkspaceFileEntry(
                        documentId = docId,
                        name = name,
                        relativePath = if (relativePath.isBlank()) name else "$relativePath/$name",
                        isDirectory = isDir,
                        mimeType = mime,
                        size = if (isDir) 0L else size,
                        lastModified = modified
                    )
                }
            }
            entries.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name })
        }.getOrElse {
            Log.e(TAG, "列出工作区文件失败: ${it.message}", it)
            emptyList()
        }
    }

    /** Walk path segments via SAF child queries to resolve a safe document ID for the target dir. */
    private fun resolveDocId(rootUri: Uri, rootDocId: String, relativePath: String): String? {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        var currentDocId = rootDocId
        for (segment in segments) {
            currentDocId = findChildDocumentId(rootUri, currentDocId, segment) ?: return null
        }
        return currentDocId
    }

    /**
     * 读取工作区内指定文件的文本内容。
     * @param relativePath 相对于工作区根的路径
     * @return 文件文本内容，失败返回 null
     */
    suspend fun readFileText(relativePath: String): String? = withContext(Dispatchers.IO) {
        val fileUri = resolveFileUri(relativePath) ?: return@withContext null
        runCatching {
            appContext.contentResolver.openInputStream(fileUri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()
    }

    /**
     * 读取工作区内指定文件的二进制内容。
     */
    suspend fun readFileBytes(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val fileUri = resolveFileUri(relativePath) ?: return@withContext null
        runCatching {
            appContext.contentResolver.openInputStream(fileUri)?.use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    /**
     * 向工作区写入文本文件。如果父目录不存在会自动创建。
     * @param relativePath 相对于工作区根的目标路径
     * @param content 文件内容
     * @return 是否写入成功
     */
    suspend fun writeFileText(relativePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        writeFileBytes(relativePath, content.toByteArray(Charsets.UTF_8))
    }

    /**
     * 向工作区写入二进制文件。
     */
    suspend fun writeFileBytes(relativePath: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val rootUri = currentWorkspaceUri ?: return@withContext false
        runCatching {
            val parts = relativePath.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) return@runCatching false
            val fileName = parts.last()
            val dirParts = parts.dropLast(1)
            var currentDocId = DocumentsContract.getTreeDocumentId(rootUri)
            // 逐层创建目录
            for (dir in dirParts) {
                currentDocId = ensureDirectory(rootUri, currentDocId, dir)
                    ?: return@runCatching false
            }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, currentDocId)
            // 检查文件是否已存在
            val existingUri = findChildDocument(rootUri, currentDocId, fileName)
            val targetUri = if (existingUri != null) {
                existingUri
            } else {
                DocumentsContract.createDocument(
                    appContext.contentResolver,
                    parentUri,
                    guessMimeType(fileName),
                    fileName
                ) ?: return@runCatching false
            }
            appContext.contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return@runCatching false
            true
        }.getOrElse {
            Log.e(TAG, "写入工作区文件失败: ${it.message}", it)
            false
        }
    }

    /**
     * 将工作区中的文件导入到 pi_workspace 容器内，供 Agent 使用。
     * @param relativePath 工作区内文件的相对路径
     * @param containerTarget 容器内目标路径（相对于 /root/pi_workspace），为空则放到 .import
     * @return 导入后在容器内的 proot 路径，失败返回 null
     */
    suspend fun importToContainer(
        relativePath: String,
        containerTarget: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val bytes = readFileBytes(relativePath) ?: return@withContext null
        val fileName = relativePath.substringAfterLast('/')
        val targetDir = containerTarget?.trim()?.ifBlank { null } ?: ".import"
        val hostDir = File(paths.rootfsDir, "root/pi_workspace/$targetDir")
        hostDir.mkdirs()
        val safeName = safeFileName(fileName)
        val targetFile = uniqueFile(hostDir, safeName)
        runCatching {
            targetFile.writeBytes(bytes)
            "/root/pi_workspace/$targetDir/${targetFile.name}"
        }.getOrElse {
            Log.e(TAG, "导入工作区文件到容器失败: ${it.message}", it)
            null
        }
    }

    /**
     * 将容器内 pi_workspace 中的文件导出到工作区。
     * @param containerPath 容器内路径（如 /root/pi_workspace/src/Main.kt 或相对路径 src/Main.kt）
     * @param workspaceTarget 工作区内目标相对路径，为空则使用原文件名
     * @return 是否导出成功
     */
    suspend fun exportFromContainer(
        containerPath: String,
        workspaceTarget: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val hostFile = resolveContainerPathToFile(containerPath) ?: return@withContext false
        if (!hostFile.isFile) return@withContext false
        val bytes = runCatching { hostFile.readBytes() }.getOrNull() ?: return@withContext false
        val target = workspaceTarget?.trim()?.ifBlank { null } ?: hostFile.name
        writeFileBytes(target, bytes)
    }

    /**
     * 获取工作区根目录下所有代码文件的相对路径（递归），用于批量传输。
     * 只返回常见代码/文本文件。
     */
    suspend fun listCodeFiles(): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        collectCodeFiles("", result)
        result
    }

    /** 递归收集代码文件。 */
    private suspend fun collectCodeFiles(dir: String, out: MutableList<String>) {
        val entries = listFiles(dir)
        for (entry in entries) {
            if (entry.isDirectory) {
                collectCodeFiles(entry.relativePath, out)
            } else if (isCodeFile(entry.name)) {
                out += entry.relativePath
            }
        }
    }

    /** 解析工作区内文件为 Uri。 */
    private fun resolveFileUri(relativePath: String): Uri? {
        val rootUri = currentWorkspaceUri ?: return null
        val parts = relativePath.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val treeDocId = DocumentsContract.getTreeDocumentId(rootUri)
        val targetDocId = if (parts.size == 1) "$treeDocId/${parts[0]}" else "$treeDocId/${parts.joinToString("/")}"
        return DocumentsContract.buildDocumentUriUsingTree(rootUri, targetDocId)
    }

    /** 确保目录存在，不存在则创建。返回子目录的 documentId。 */
    private fun ensureDirectory(rootUri: Uri, parentDocId: String, dirName: String): String? {
        val existing = findChildDocumentId(rootUri, parentDocId, dirName)
        if (existing != null) return existing
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, parentDocId)
        val created = DocumentsContract.createDocument(
            appContext.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    /** 查找子文档，返回其 Uri。 */
    private fun findChildDocument(rootUri: Uri, parentDocId: String, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        appContext.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val displayName = cursor.getString(1)
                if (displayName == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                }
            }
        }
        return null
    }

    /** 查找子文档，返回其 documentId。 */
    private fun findChildDocumentId(rootUri: Uri, parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        appContext.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val displayName = cursor.getString(1)
                if (displayName == name) return docId
            }
        }
        return null
    }

    /** 查询 Uri 对应的显示名称。 */
    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else DEFAULT_WORKSPACE_NAME
            } ?: DEFAULT_WORKSPACE_NAME
        }.getOrDefault(DEFAULT_WORKSPACE_NAME)
    }

    /** 将容器路径解析为宿主机文件。 */
    private fun resolveContainerPathToFile(containerPath: String): File? {
        val normalized = containerPath.replace('\\', '/').trim()
        val rel = when {
            normalized.startsWith("/root/pi_workspace/") -> normalized.removePrefix("/root/pi_workspace/")
            normalized == "/root/pi_workspace" -> ""
            normalized.startsWith("/root/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            normalized.startsWith("/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            else -> normalized
        }
        return File(paths.rootfsDir, "root/pi_workspace/$rel")
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim()
        .take(120)
        .ifBlank { "file" }

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
        name.endsWith(".kt", true) -> "text/x-kotlin"
        name.endsWith(".java", true) -> "text/x-java"
        name.endsWith(".py", true) -> "text/x-python"
        name.endsWith(".js", true) -> "text/javascript"
        name.endsWith(".ts", true) -> "text/typescript"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".xml", true) -> "application/xml"
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".html", true) -> "text/html"
        name.endsWith(".css", true) -> "text/css"
        name.endsWith(".sh", true) -> "application/x-sh"
        name.endsWith(".c", true) -> "text/x-c"
        name.endsWith(".cpp", true) || name.endsWith(".cc", true) -> "text/x-c++"
        name.endsWith(".h", true) -> "text/x-c"
        name.endsWith(".go", true) -> "text/x-go"
        name.endsWith(".rs", true) -> "text/x-rust"
        name.endsWith(".yml", true) || name.endsWith(".yaml", true) -> "application/x-yaml"
        name.endsWith(".toml", true) -> "application/toml"
        name.endsWith(".gradle", true) -> "text/x-groovy"
        else -> "application/octet-stream"
    }

    private fun isCodeFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in CODE_EXTENSIONS
    }

    // ==================== 便捷重载方法（供 PiAgentManager 调用） ====================

    /** 将工作区文件条目导入到容器，返回容器内 proot 路径。 */
    suspend fun importToContainer(entry: WorkspaceFileEntry): String? = importToContainer(entry.relativePath)

    /** 将容器内文件导出到工作区，返回写入工作区的文件名。 */
    suspend fun exportFromContainer(containerRelativePath: String): String? {
        val ok = exportFromContainer(containerRelativePath, null)
        return if (ok) containerRelativePath.substringAfterLast('/').ifBlank { containerRelativePath } else null
    }

    /** 读取工作区文件作为草稿附件。 */
    suspend fun readAsDraftAttachment(entry: WorkspaceFileEntry): DraftAttachment? {
        val bytes = readFileBytes(entry.relativePath) ?: return null
        return DraftAttachment(
            name = entry.name,
            mimeType = entry.mimeType.ifBlank { guessMimeType(entry.name) },
            bytes = bytes,
            isImage = entry.mimeType.startsWith("image/")
        )
    }

    /** 读取工作区文件为文本。 */
    suspend fun readAsText(entry: WorkspaceFileEntry): String? = readFileText(entry.relativePath)

    /** 解析路径：支持 ".." 返回上级，"." 或空返回当前。 */
    fun resolvePath(currentPath: String, relativePath: String): String {
        if (relativePath.isBlank() || relativePath == ".") return currentPath
        if (relativePath == "..") {
            if (currentPath.isBlank()) return ""
            val parts = currentPath.split('/').filter { it.isNotBlank() }
            return parts.dropLast(1).joinToString("/")
        }
        return if (currentPath.isBlank()) relativePath else "$currentPath/$relativePath"
    }

    companion object {
        private const val TAG = "WorkspaceManager"
        private const val PREFS_NAME = "idadroid_workspace"
        private const val KEY_WORKSPACE_URI = "workspace_uri"
        private const val DEFAULT_WORKSPACE_NAME = "未选择工作区"
        private val CODE_EXTENSIONS = setOf(
            "kt", "java", "py", "js", "ts", "jsx", "tsx", "json", "xml", "md", "txt",
            "html", "css", "scss", "less", "sh", "bash", "c", "cpp", "cc", "h", "hpp",
            "go", "rs", "rb", "php", "swift", "yml", "yaml", "toml", "gradle", "sql",
            "dockerfile", "makefile", "cmake", "proto", "vue", "svelte"
        )
    }
}

/** 工作区文件条目。 */
@Serializable
data class WorkspaceFileEntry(
    val documentId: String,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val lastModified: Long
)

/** 工作区状态快照，用于 UI 展示。 */
data class WorkspaceState(
    val hasWorkspace: Boolean = false,
    val workspaceName: String = "",
    val workspaceUri: String = "",
    val files: List<WorkspaceFileEntry> = emptyList(),
    val currentPath: String = "",
    val loading: Boolean = false,
    val error: String? = null
)
