package dev.tim.assistant.ai.jobs

import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.ai.ChatMessage
import dev.tim.assistant.ai.Completion
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.ai.xpsOllamaUrl
import dev.tim.assistant.db.Ai_job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Combined data per job prompt (characters). */
const val JOB_DATA_MAX = 12_000

/** One non-streaming model call. Tests pass a fake; production uses [OllamaModel]. */
fun interface ModelClient {
    suspend fun complete(messages: List<ChatMessage>, format: JsonObject?): Completion
}

/** The XPS Ollama behind [client]; the URL comes from the Ollama setting each call. */
class OllamaModel(private val client: ChatClient, private val repo: Repository) : ModelClient {
    override suspend fun complete(messages: List<ChatMessage>, format: JsonObject?): Completion =
        client.complete(xpsOllamaUrl(repo.setting(SettingKeys.OLLAMA_URL)), messages, format)
}

/** The answer text; Offline becomes [OllamaOffline], anything incomplete a plain failure with a generic message. */
internal suspend fun ModelClient.answer(messages: List<ChatMessage>, format: JsonObject? = null): String =
    when (val c = complete(messages, format)) {
        is Completion.Ok -> c.text
        Completion.Offline -> throw OllamaOffline()
        is Completion.Error -> throw IllegalStateException("model error")
    }

/** Joins [parts] with newlines and keeps at most [max] characters without splitting a surrogate pair. */
internal fun joinCapped(parts: List<String>, max: Int = JOB_DATA_MAX): String =
    ChatContext.safeTake(parts.joinToString("\n"), max)

/** The string field [key] of the job's input JSON, or null. */
internal fun jobInput(job: Ai_job, key: String): String? =
    try {
        val value = (Json.parseToJsonElement(job.inputJson) as? JsonObject)?.get(key)
        if (value is JsonPrimitive && value !is JsonNull && value.isString) value.content else null
    } catch (e: Exception) {
        null
    }
