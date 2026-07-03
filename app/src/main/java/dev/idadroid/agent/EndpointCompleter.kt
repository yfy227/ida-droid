package dev.idadroid.agent

import java.net.URL

/**
 * 自动补全 API 端点 URL，借鉴自 Operit 的 EndpointCompleter。
 *
 * 规则:
 * - 裸域名 (如 https://api.example.com) → 追加 /v1/chat/completions
 * - 以 /v1 结尾 → 追加 /chat/completions
 * - 以 # 结尾 → 禁用自动补全，移除 # 后原样返回
 * - Anthropic 端点 → 追加 /v1/messages
 * - Gemini 端点 → 原样返回（Gemini URL 格式特殊）
 */
object EndpointCompleter {

    fun complete(endpoint: String, providerId: String = ""): String {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return trimmed

        // # 后缀：禁用自动补全
        if (trimmed.endsWith("#")) return trimmed.removeSuffix("#")

        val withoutSlash = trimmed.removeSuffix("/")

        return when (providerId) {
            "anthropic" -> completeAnthropic(withoutSlash)
            "google" -> trimmed // Gemini URL 格式特殊，不补全
            else -> completeOpenAiStyle(withoutSlash)
        }
    }

    private fun completeOpenAiStyle(withoutSlash: String): String {
        try {
            val url = URL(withoutSlash)
            val path = url.path.removeSuffix("/")

            // 裸域名：追加 /v1/chat/completions
            if (path.isEmpty()) {
                return "$withoutSlash/v1/chat/completions"
            }

            // 以 /v1 结尾：追加 /chat/completions
            if (path.endsWith("/v1", ignoreCase = true)) {
                return "$withoutSlash/chat/completions"
            }

            // 已经包含完整路径，原样返回
            return withoutSlash
        } catch (_: Exception) {
            return withoutSlash
        }
    }

    private fun completeAnthropic(withoutSlash: String): String {
        try {
            val url = URL(withoutSlash)
            val path = url.path.removeSuffix("/")

            if (path.isEmpty()) {
                return "$withoutSlash/v1/messages"
            }

            if (path.endsWith("/anthropic", ignoreCase = true)) {
                return "$withoutSlash/v1/messages"
            }

            if (path.endsWith("/v1", ignoreCase = true)) {
                return "$withoutSlash/messages"
            }

            return withoutSlash
        } catch (_: Exception) {
            return withoutSlash
        }
    }

    /**
     * 判断端点是否需要补全（用于 UI 提示）。
     */
    fun needsCompletion(endpoint: String, providerId: String = ""): Boolean {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty() || trimmed.endsWith("#")) return false
        return complete(trimmed, providerId) != trimmed.removeSuffix("/")
    }
}
