package dev.idadroid.env

import android.system.Os
import java.io.File

class PiWorkspaceMaterializer {
    fun materialize(rootfsDir: File) {
        val workspace = File(rootfsDir, "root/pi_workspace")
        val uploadDir = File(workspace, ".upload")
        val transferDir = File(workspace, ".transfer")
        val sessionDir = File(workspace, ".pi-sessions")
        val piDir = File(workspace, ".pi")
        val idaDroidDir = File(workspace, ".idadroid")
        val piAgentDir = File(idaDroidDir, "pi-agent")
        val logsDir = File(idaDroidDir, "logs")
        val scriptsDir = File(idaDroidDir, "scripts")

        listOf(workspace, uploadDir, transferDir, sessionDir, piDir, idaDroidDir, piAgentDir, logsDir, scriptsDir).forEach { it.mkdirs() }

        File(piDir, "APPEND_SYSTEM.md").writeTextIfMissing(appendSystemPrompt())
        File(piAgentDir, "settings.json").writeTextIfMissing(defaultPiSettings())
        File(piAgentDir, "rpc-stdio-guard.cjs").writeText(rpcStdioGuardScript())
        File(scriptsDir, "validate.sh").writeText(validateScript())
        File(scriptsDir, "start-ida-vnc.sh").writeText(startIdaVncPlaceholder())
        File(scriptsDir, "idadroid-file.sh").writeText(idadroidFileScript())

        listOf(
            File(scriptsDir, "validate.sh"),
            File(scriptsDir, "start-ida-vnc.sh"),
            File(scriptsDir, "idadroid-file.sh")
        ).forEach { script -> runCatching { Os.chmod(script.absolutePath, 493) } }
    }

    private fun File.writeTextIfMissing(text: String) {
        if (!isFile) writeText(text)
    }

    private fun appendSystemPrompt(): String = """
        # IDAdroid workspace
        You are running inside IDAdroid's proot rootfs.
        You are an expert CTF Reverse Engineering (RE) challenge designer.
        The user prompt will provide a CTF RE challenge, which may include attachments.
        Your goal is to solve this challenge and, based on the challenge and your solution steps, design a new CTF RE challenge.
        You need to generate the following content:
         1. Challenge Description / Problem Statement
         2. Challenge Solution Results
         3. Writeup (WP)
        All of this content must be placed in a dedicated folder for each specific challenge under the pi_workspace directory (create a new folder for every new challenge).
         * Working directory: /root/pi_workspace.
         * IDA home: /root/ida-pro-9.3.
         * ida-mcp entry: /root/ida-pro-9.3/ida-mcp.
         * ida-mcp/mcpc usage doc: /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md.
         * Attachments copied from Android live in /root/pi_workspace/.upload.
         * Host-transferred files live in /root/pi_workspace/.transfer (see idadroid-file below).
         * pi sessions live in /root/pi_workspace/.pi-sessions.
        For reverse-engineering tasks, first read IDA_MCP_MCPC_USAGE.md, then use mcpc to call ida-mcp.

        ## File transfer bridge (idadroid-file)
        A helper script at /root/pi_workspace/.idadroid/scripts/idadroid-file.sh bridges the Android host and this container.
        It is recommended to symlink or alias it for convenience:
            alias idadroid-file='/root/pi_workspace/.idadroid/scripts/idadroid-file.sh'
        Usage:
          idadroid-file list            — list files transferred from the Android host
          idadroid-file find <name>     — print the container path of a transferred file (fuzzy match)
          idadroid-file open <name>     — look up a transferred file and open it in IDA via mcpc
          idadroid-file path <name>     — alias of find
        When you need to open a file in IDA, FIRST check whether the file was transferred from the host:
            idadroid-file find <filename>
        If a match is found, use the returned path with mcpc directly — this avoids path-mismatch issues
        between the Android host and the container. If no match is found, fall back to the normal mcpc open_file flow.
        The transfer manifest is also readable directly at /root/pi_workspace/.transfer/manifest.json.

        If you need to use Python, ensure you use a virtual environment. If you require missing dependencies, you may install them proactively.
        Do not delete any files outside of the current project workspace! Do not modify any files in /sdcard/* (if needed, copy them to the current challenge workspace).
    """.trimIndent() + "\n"

    private fun defaultPiSettings(): String = """
        {
          "quietStartup": true,
          "enableInstallTelemetry": false,
          "sessionDir": "/root/pi_workspace/.pi-sessions",
          "compaction": {
            "enabled": true,
            "reserveTokens": 16384,
            "keepRecentTokens": 20000
          },
          "retry": {
            "enabled": true,
            "maxRetries": 3,
            "baseDelayMs": 2000,
            "maxDelayMs": 60000
          },
          "steeringMode": "one-at-a-time",
          "followUpMode": "one-at-a-time"
        }
    """.trimIndent() + "\n"

    private fun rpcStdioGuardScript(): String = """
        // Generated by IDAdroid. Loaded via NODE_OPTIONS for pi RPC runtimes.
        (() => {
          const RETRYABLE = new Set(["ENOBUFS", "EAGAIN", "EWOULDBLOCK"]);
          for (const stream of [process.stdout, process.stderr]) installGuard(stream);

          function installGuard(stream) {
            if (!stream || stream.__idadroidRpcStdioGuard) return;
            Object.defineProperty(stream, "__idadroidRpcStdioGuard", { value: true });
            const originalWrite = stream.write.bind(stream);
            const queue = [];
            let writing = false;
            let closed = false;

            stream.on("error", (err) => {
              if (err && (RETRYABLE.has(err.code) || err.code === "EPIPE")) {
                if (err.code === "EPIPE") closed = true;
                return;
              }
              throw err;
            });

            stream.write = function guardedWrite(chunk, encoding, callback) {
              if (typeof encoding === "function") {
                callback = encoding;
                encoding = undefined;
              }
              if (closed) {
                if (typeof callback === "function") queueMicrotask(() => callback());
                return false;
              }
              queue.push({ chunk, encoding, callback });
              pump();
              return queue.length < 1024;
            };

            function pump() {
              if (writing || closed) return;
              const item = queue.shift();
              if (!item) return;
              writing = true;
              let settled = false;
              const done = (err) => {
                if (settled) return;
                settled = true;
                writing = false;
                if (err) {
                  if (RETRYABLE.has(err.code)) {
                    queue.unshift(item);
                    setTimeout(pump, 25);
                    return;
                  }
                  if (err.code === "EPIPE") closed = true;
                }
                if (typeof item.callback === "function") item.callback(err);
                setImmediate(pump);
              };
              try {
                originalWrite(item.chunk, item.encoding, done);
              } catch (err) {
                done(err);
              }
            }
          }
        })();
    """.trimIndent() + "\n"

    private fun validateScript(): String = """
        #!/usr/bin/env bash
        set -euo pipefail
        echo "IDAdroid quick validation"
        echo "arch=$(uname -m)"
        test -d /root/ida-pro-9.3 && echo "IDA: ok" || echo "IDA: missing"
        test -x /root/ida-pro-9.3/ida-mcp && echo "ida-mcp: ok" || echo "ida-mcp: missing/not executable"
        command -v pi >/dev/null && pi --version || echo "pi: missing"
        command -v node >/dev/null && node --version || echo "node: missing"
        command -v mcpc >/dev/null && mcpc --version || echo "mcpc: missing"
    """.trimIndent() + "\n"

    private fun startIdaVncPlaceholder(): String = """
        #!/usr/bin/env bash
        set -euo pipefail
        echo "start-ida-vnc.sh will be generated in Phase 3."
        echo "M1 only validates/imports rootfs and opens a proot terminal."
        exit 2
    """.trimIndent() + "\n"

    /**
     * Container-side helper that bridges the Android file-transfer service.
     *
     * It reads the transfer manifest (written by FileTransferManager on the host)
     * and, for the `open` sub-command, looks up the transferred file path and
     * invokes `mcpc` to open it in IDA — so the agent never has to guess where
     * a host file landed inside the container.
     *
     * NOTE: This is a raw shell script embedded in a Kotlin triple-quoted string.
     * Every literal `$` is escaped as $DOLLAR (resolved at the top of the script)
     * to avoid Kotlin string-template interpolation.
     */
    private fun idadroidFileScript(): String {
        val D = "\$"  // shell dollar sign
        return """
        #!/usr/bin/env bash
        # idadroid-file — bridge between Android host file transfers and the container.
        # Generated by IDAdroid. Do not edit manually.
        set -euo pipefail

        MANIFEST="/root/pi_workspace/.transfer/manifest.json"
        TRANSFER_DIR="/root/pi_workspace/.transfer"
        MCP_HOST="127.0.0.1"
        MCP_PORT="8766"

        die() { echo "idadroid-file: ${D}{*}" >&2; exit 1; }

        # Try the HTTP bridge first (preferred), fall back to the local manifest file.
        fetch_manifest() {
            if command -v curl >/dev/null 2>&1; then
                local body
                if body=$(curl -fsS --max-time 5 "http://${D}{MCP_HOST}:${D}{MCP_PORT}/api/transfers" 2>/dev/null); then
                    printf '%s' "${D}{body}"
                    return 0
                fi
            fi
            if [ -f "${D}MANIFEST" ]; then
                cat "${D}MANIFEST"
                return 0
            fi
            echo '{"entries":[]}'
        }

        # Extract the container path for a file whose name contains ${D}1.
        # Uses grep/sed to avoid a hard dependency on jq.
        find_path() {
            local needle="${D}1"
            local manifest
            manifest=$(fetch_manifest)
            # manifest entries look like: {"id":"...","name":"foo.bin","prootPath":"/root/.../foo.bin",...}
            # Walk each entry, extract name + prootPath, fuzzy-match.
            printf '%s\n' "${D}manifest" \
              | tr '{' '\n' \
              | grep '"prootPath"' \
              | while IFS= read -r entry; do
                    local name path
                    name=$(printf '%s' "${D}entry" | sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
                    path=$(printf '%s' "${D}entry" | sed -n 's/.*"prootPath"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
                    if [ -z "${D}path" ]; then continue; fi
                    # exact or substring match (case-insensitive)
                    if printf '%s' "${D}name" | grep -qiF "${D}needle"; then
                        printf '%s\n' "${D}path"
                        return 0
                    fi
                done
            return 1
        }

        cmd_list() {
            local manifest
            manifest=$(fetch_manifest)
            if command -v jq >/dev/null 2>&1; then
                jq -r '.entries[] | "\(.id)\t\(.name)\t\(.prootPath)\t\(.sizeBytes) bytes"' <<<"${D}manifest" 2>/dev/null \
                  || echo "${D}manifest"
            else
                # Fallback: just print names
                printf '%s\n' "${D}manifest" | tr '{' '\n' | grep '"name"' \
                  | sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
                  | while IFS= read -r n; do [ -n "${D}n" ] && echo "${D}n"; done
            fi
        }

        cmd_find() {
            [ ${D}# -ge 1 ] || die "usage: idadroid-file find <name>"
            local path
            path=$(find_path "${D}1") || die "no transferred file matching '${D}1'"
            printf '%s\n' "${D}path"
        }

        cmd_open() {
            [ ${D}# -ge 1 ] || die "usage: idadroid-file open <name>"
            local needle="${D}1"
            local path
            path=$(find_path "${D}needle") || die "no transferred file matching '${D}needle' — falling back to normal mcpc flow"
            echo "idadroid-file: found transferred file → ${D}path"
            shift
            if command -v mcpc >/dev/null 2>&1; then
                exec mcpc call open_file --path "${D}path" "${D}@"
            else
                echo "idadroid-file: mcpc not found, printing path for manual use:" >&2
                printf '%s\n' "${D}path"
            fi
        }

        usage() {
            cat <<EOF
idadroid-file — IDAdroid file transfer bridge

Usage:
  idadroid-file list            List files transferred from the Android host
  idadroid-file find <name>     Print the container path of a transferred file
  idadroid-file path <name>     Alias of find
  idadroid-file open <name>     Open a transferred file in IDA via mcpc

The transfer manifest is at ${D}MANIFEST
The HTTP bridge is at http://${D}{MCP_HOST}:${D}{MCP_PORT}/api/transfers
EOF
        }

        [ ${D}# -ge 1 ] || { usage; exit 0; }
        sub="${D}1"; shift || true
        case "${D}sub" in
            list)    cmd_list "${D}@" ;;
            find|path) cmd_find "${D}@" ;;
            open)    cmd_open "${D}@" ;;
            -h|--help|help) usage ;;
            *) die "unknown sub-command '${D}sub' (try: idadroid-file help)" ;;
        esac
        """.trimIndent() + "\n"
    }
}
