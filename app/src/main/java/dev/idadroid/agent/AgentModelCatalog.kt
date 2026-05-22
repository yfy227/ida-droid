package dev.idadroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val AgentModelCatalogJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

data class AgentConfiguredModel(
    val provider: String,
    val id: String,
    val name: String? = null,
    val reasoning: Boolean? = null,
    val input: List<String>? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null
) {
    fun toPiModel(): PiModel = PiModel(
        id = id,
        provider = provider,
        name = name,
        reasoning = reasoning,
        input = input,
        contextWindow = contextWindow,
        maxTokens = maxTokens
    )
}

data class AgentModelCatalog(
    val providers: List<String> = emptyList(),
    val models: List<AgentConfiguredModel> = emptyList(),
    val parseError: String? = null
) {
    val hasProvider: Boolean get() = providers.isNotEmpty()
    val hasModel: Boolean get() = models.isNotEmpty()
    val isUsable: Boolean get() = hasProvider && hasModel && parseError == null
}

fun parseAgentModelCatalog(text: String): AgentModelCatalog = runCatching {
    val root = AgentModelCatalogJson.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject
        ?: error("models.json 必须是 JSON object")
    val providersObj = root["providers"] as? JsonObject ?: JsonObject(emptyMap())
    val providers = providersObj.entries.mapNotNull { (id, _) -> id.trim().takeIf { it.isNotBlank() } }
    val models = providersObj.entries.flatMap { (providerId, element) ->
        val provider = providerId.trim()
        val obj = element as? JsonObject ?: return@flatMap emptyList()
        val modelsArray = obj["models"] as? JsonArray ?: return@flatMap emptyList()
        modelsArray.mapNotNull { item ->
            val modelObj = item as? JsonObject ?: return@mapNotNull null
            val id = modelObj.stringValue("id").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentConfiguredModel(
                provider = provider,
                id = id,
                name = modelObj.stringValue("name").trim().takeIf { it.isNotBlank() },
                reasoning = modelObj.booleanValue("reasoning"),
                input = modelObj.stringList("input").takeIf { it.isNotEmpty() },
                contextWindow = modelObj.longValue("contextWindow"),
                maxTokens = modelObj.longValue("maxTokens")
            )
        }
    }
    AgentModelCatalog(providers = providers, models = models)
}.getOrElse { AgentModelCatalog(parseError = it.message ?: "models.json 解析失败") }

private fun JsonObject.stringValue(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.replace(",", "")?.trim()?.toLongOrNull()
private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
    is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    else -> emptyList()
}
