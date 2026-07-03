package dev.idadroid.mcp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A minimal HTTP server that exposes [FileTransferManager] to the agent running
 * inside the proot container. Because the container shares the host's network
 * namespace, the agent can reach this server via `curl http://127.0.0.1:<port>/...`.
 *
 * Endpoints:
 *  - `GET  /api/transfers`          → JSON manifest of all transfer entries
 *  - `GET  /api/transfers?name=foo` → single entry matching `foo` (or 404)
 *  - `POST /api/transfer?path=<host_path>` → copy a host file into the container
 *  - `DELETE /api/transfers?id=<id>` → remove a transfer entry and its file
 *  - `GET  /health`                 → `{"status":"ok"}`
 */
class FileTransferHttpServer(
    private val manager: FileTransferManager,
    private val port: Int = DEFAULT_PORT
) {
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptJob: Job? = null

    val isRunning: Boolean get() = running
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        if (running) return
        // Recreate the scope if it was previously cancelled by stop().
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        val socket = ServerSocket(port)
        serverSocket = socket
        running = true
        acceptJob = scope.launch {
            Log.i(TAG, "Listening on 127.0.0.1:${socket.localPort}")
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "accept() failed", e)
                    break
                }
                launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Cancel the scope but DO NOT make it un-restartable — start() recreates
        // the scope if needed so the server can be started again after stop().
        scope.cancel()
    }

    private suspend fun handleClient(client: java.net.Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondText(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val rawPath = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            // Read body if present (read raw bytes from the underlying stream)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val rawInput = socket.getInputStream()
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = rawInput.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            val pathOnly = rawPath.substringBefore('?')
            val queryStr = rawPath.substringAfter('?', "")
            val query = parseQuery(queryStr)

            try {
                route(method, pathOnly, query, body, output)
            } catch (e: Exception) {
                Log.e(TAG, "route error: $method $pathOnly", e)
                respondJson(output, 500, "{\"error\":\"${escapeJson(e.message ?: "Internal error")}\"}")
            }
        }
    }

    private suspend fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String,
        output: OutputStream
    ) {
        when {
            path == "/health" && method == "GET" ->
                respondJson(output, 200, "{\"status\":\"ok\",\"port\":$port}")

            path == "/api/transfers" && method == "GET" -> {
                val name = query["name"]
                if (name != null) {
                    val entry = manager.findTransfer(name)
                    if (entry != null) {
                        respondJson(output, 200, manager.manifestJson().let { it })
                    } else {
                        respondJson(output, 404, "{\"error\":\"no transfer matching '$name'\"}")
                    }
                } else {
                    respondJson(output, 200, manager.manifestJson())
                }
            }

            path == "/api/transfer" && method == "POST" -> {
                val hostPath = query["path"]
                if (hostPath.isNullOrBlank()) {
                    respondJson(output, 400, "{\"error\":\"missing 'path' query parameter\"}")
                    return
                }
                val entry = manager.transferHostPath(hostPath)
                respondJson(output, 200, entryJson(entry))
            }

            path == "/api/transfers" && method == "DELETE" -> {
                val id = query["id"]
                if (id.isNullOrBlank()) {
                    respondJson(output, 400, "{\"error\":\"missing 'id' query parameter\"}")
                    return
                }
                val ok = manager.removeTransfer(id)
                respondJson(output, if (ok) 200 else 404, "{\"removed\":$ok}")
            }

            else -> respondJson(output, 404, "{\"error\":\"not found: $method $path\"}")
        }
    }

    private fun entryJson(entry: FileTransferManager.TransferEntry): String {
        return """{"id":"${escapeJson(entry.id)}","name":"${escapeJson(entry.name)}",""" +
            """"prootPath":"${escapeJson(entry.prootPath)}","size":${entry.size},""" +
            """"mimeType":"${escapeJson(entry.mimeType)}","transferredAt":${entry.transferredAt}}"""
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) pair to ""
            else URLDecoder.decode(pair.substring(0, eq), "UTF-8") to
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }.toMap()
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun respondText(output: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun respondJson(output: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    companion object {
        private const val TAG = "FileTransferHttpServer"
        const val DEFAULT_PORT = 8766
    }
}
