package dev.idadroid.deepindex

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the "Deep Index Mode" — a unified tool chain that combines the core
 * capabilities of three open-source projects into a single agent-facing surface:
 *
 *  - **CodeGraph** (codegraph-ai/codegraph): semantic code graph — symbols,
 *    call chains, dependency graphs, impact analysis. In this integration the
 *    heavy Rust binary is replaced by a shell-side implementation built on
 *    ctags / ripgrep / grep that runs inside the proot container.
 *
 *  - **ECC** (affaan-m/ECC): engineering capability framework — token-lean
 *    codemaps, AgentShield-style security scanning, and codebase onboarding.
 *    The command definitions are distilled into the container-side script.
 *
 *  - **codebase-memory-mcp**: persistent semantic memory over a codebase.
 *    Implemented here as a JSON-backed key/value store with BM25-style keyword
 *    retrieval, materialised under `.idadroid/deep-index/memory.json`.
 *
 * When the mode is enabled, the agent's APPEND_SYSTEM prompt is augmented with
 * instructions to use the `deep-index` helper script, and a status banner is
 * shown in the chat UI.
 */
class DeepIndexToolChain(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow(DeepIndexStatus())
    val status: StateFlow<DeepIndexStatus> = _status.asStateFlow()

    /** Toggle the deep index mode on or off. */
    fun toggle() {
        setEnabled(!_enabled.value)
    }

    /** Explicitly enable or disable the mode. */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (enabled) {
            _status.value = _status.value.copy(
                enabled = true,
                message = "深度索引模式已开启 — Agent 将联动 CodeGraph / ECC / codebase-memory 工具链"
            )
        } else {
            _status.value = DeepIndexStatus()
        }
    }

    /** Whether the mode is currently active. */
    fun isEnabled(): Boolean = _enabled.value

    /** System-prompt fragment injected into APPEND_SYSTEM when the mode is active. */
    fun systemPromptFragment(): String = if (!_enabled.value) "" else """
        ## Deep Index Mode (active)
        The deep-index tool chain is ENABLED for this session. A unified helper
        script is available at /root/pi_workspace/.idadroid/scripts/deep-index.sh
        (recommended alias: `deep-index`). It combines three open-source toolkits:

        ### CodeGraph-style — structural code intelligence
          deep-index symbols <query>     — find functions/classes by name (fuzzy)
          deep-index callers <symbol>    — who calls this function?
          deep-index callees <symbol>    — what does this function call?
          deep-index deps <file>         — import / dependency graph for a file
          deep-index impact <symbol>     — blast-radius: what breaks if this changes
          deep-index entry-points        — find main(), HTTP handlers, CLI commands

        ### ECC-style — engineering capability (codemaps + security + onboarding)
          deep-index codemap [path]      — generate token-lean architecture codemap
          deep-index security [path]     — AgentShield-style security audit
          deep-index onboard [path]      — 4-phase codebase onboarding guide

        ### codebase-memory-mcp-style — persistent semantic memory
          deep-index memory-store <key> <value>   — persist a code insight
          deep-index memory-search <query>        — keyword-search stored memories
          deep-index memory-list                  — list all stored memories
          deep-index memory-context <file>        — memories relevant to a file

        ### Index management
          deep-index index [path]        — (re)build the symbol + dependency index
          deep-index status              — show index freshness and coverage

        When analysing code in Deep Index Mode, PREFER these tools over raw
        grep/find. They produce structured, token-lean output that preserves
        your context budget. Run `deep-index index` once at the start of a
        session to build the graph, then use the query commands.
    """.trimIndent() + "\n"

    companion object {
        const val SCRIPT_NAME = "deep-index.sh"
        const val SCRIPT_PROOT_PATH = "/root/pi_workspace/.idadroid/scripts/deep-index.sh"
        const val MEMORY_FILE = "/root/pi_workspace/.idadroid/deep-index/memory.json"
        const val INDEX_DIR = "/root/pi_workspace/.idadroid/deep-index"
    }
}

data class DeepIndexStatus(
    val enabled: Boolean = false,
    val message: String = "",
    val lastIndexAt: String? = null,
    val symbolsIndexed: Int = 0,
    val memoriesStored: Int = 0
)
