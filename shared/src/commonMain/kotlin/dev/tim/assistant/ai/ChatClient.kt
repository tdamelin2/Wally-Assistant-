package dev.tim.assistant.ai

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val CHAT_MODEL = "qwen3.5:9b"
const val CHAT_NUM_CTX = 16384
const val CHAT_HISTORY_MAX_CHARS = 2_000
const val XPS_OFFLINE_TEXT = "XPS is offline — turn it on to chat"
/** Request deadline for background job model calls (job calls only; chat streaming has none). */
const val JOB_MODEL_TIMEOUT_MS = 600_000L
/** Request deadline for one non-streamed tool-calling turn. */
const val TOOL_TURN_TIMEOUT_MS = 300_000L
const val DEFAULT_XPS_OLLAMA_URL = "http://xxxxxxxx:11434"

fun xpsOllamaUrl(setting: String?): String =
    setting?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_XPS_OLLAMA_URL

fun chatErrorText(status: Int): String = "Chat error: $status"

@Serializable
data class ChatMessage(val role: String, val content: String)

sealed interface ChatEvent {
    data class Chunk(val text: String) : ChatEvent
    data class Done(val promptEvalCount: Int?) : ChatEvent
    data object XpsOffline : ChatEvent

    /** [status] 0 = the stream broke, an error chunk, a malformed line or the end before done:true. */
    data class ChatError(val status: Int) : ChatEvent
}

/** One tool call the model asked for. [arguments] is empty when the model sent none or unparseable ones. */
data class ToolCall(val name: String, val arguments: JsonObject)

/**
 * A message of a tool-calling conversation. role is system, user, assistant or tool. An assistant message may carry
 * [toolCalls] (echoed back to the model); a tool message carries the result text in [content] and the tool's [toolName].
 */
data class ToolMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolName: String? = null,
)

/** The result of one tool-calling turn. */
sealed interface ToolTurn {
    data class Answer(val text: String) : ToolTurn
    data class Calls(val calls: List<ToolCall>) : ToolTurn

    /** No HTTP response at all (connection refused, host unknown, timeout before a response). */
    data object Offline : ToolTurn

    /** A response arrived but is not a usable answer. [message] never contains model or prompt text. */
    data class Error(val message: String) : ToolTurn
}

/** The result of a one-shot (non-streaming) model call. */
sealed interface Completion {
    /** A complete answer (the response said done:true). */
    data class Ok(val text: String) : Completion

    /** No HTTP response at all (connection refused, host unknown, timeout before a response). */
    data object Offline : Completion

    /** A response arrived but is not a complete answer. [message] never contains model or prompt text. */
    data class Error(val message: String) : Completion
}

/** Streams a chat answer from the Ollama on the XPS. Pass an engine in tests; null uses the platform engine. */
class ChatClient(engine: HttpClientEngine? = null) {
    private val http: HttpClient = if (engine == null) {
        HttpClient { configure() }
    } else {
        HttpClient(engine) { configure() }
    }

    private fun HttpClientConfig<*>.configure() {
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        expectSuccess = false
    }

    fun send(
        baseUrl: String,
        messages: List<ChatMessage>,
        model: String = CHAT_MODEL,
        numCtx: Int = CHAT_NUM_CTX,
    ): Flow<ChatEvent> = channelFlow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("think", false)
            put("options", buildJsonObject { put("num_ctx", numCtx) })
            put("messages", buildJsonArray {
                for (m in messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
            })
        }.toString()
        var gotResponse = false
        try {
            http.preparePost(chatUrl(baseUrl)) {
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                gotResponse = true
                val status = response.status.value
                if (status !in 200..299) {
                    send(ChatEvent.ChatError(status))
                    return@execute
                }
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = parseLine(line)
                    if (obj == null || obj["error"] != null) {
                        send(ChatEvent.ChatError(0))
                        return@execute
                    }
                    val content = (obj["message"] as? JsonObject)?.get("content")?.let { (it as? JsonPrimitive)?.content }
                    if (!content.isNullOrEmpty()) send(ChatEvent.Chunk(content))
                    if ((obj["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                        send(ChatEvent.Done((obj["prompt_eval_count"] as? JsonPrimitive)?.intOrNull))
                        return@execute
                    }
                }
                send(ChatEvent.ChatError(0))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(if (gotResponse) ChatEvent.ChatError(0) else ChatEvent.XpsOffline)
        }
    }

    /**
     * One non-streaming answer (stream:false, think:false) for background jobs. [format] is an Ollama JSON
     * schema for structured output. Ok only for a complete `done:true` response.
     */
    suspend fun complete(
        baseUrl: String,
        messages: List<ChatMessage>,
        format: JsonObject? = null,
        model: String = CHAT_MODEL,
        numCtx: Int = CHAT_NUM_CTX,
    ): Completion {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("think", false)
            if (format != null) put("format", format)
            put("options", buildJsonObject { put("num_ctx", numCtx) })
            put("messages", buildJsonArray {
                for (m in messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
            })
        }.toString()
        val response = try {
            http.post(chatUrl(baseUrl)) {
                timeout { requestTimeoutMillis = JOB_MODEL_TIMEOUT_MS }
                setBody(TextContent(body, ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Completion.Offline
        }
        return try {
            val status = response.status.value
            if (status !in 200..299) return Completion.Error("HTTP $status")
            val obj = parseLine(response.bodyAsText().trim()) ?: return Completion.Error("bad response")
            if (obj["error"] != null) return Completion.Error("model error")
            if ((obj["done"] as? JsonPrimitive)?.booleanOrNull != true) return Completion.Error("incomplete response")
            val content = (obj["message"] as? JsonObject)?.get("content") as? JsonPrimitive
            if (content == null || !content.isString) Completion.Error("bad response") else Completion.Ok(content.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Completion.Error("bad response")
        }
    }
    /**
     * One non-streamed tool-calling turn (stream:false, think:false) against /api/chat. [tools] is the Ollama tool
     * definition array (null sends none, used for the final answer turn). Assistant tool calls in [messages] are echoed
     * back and tool results are sent as role `tool` with `tool_name`. Never throws except on cancellation.
     */
    suspend fun chatTurn(
        baseUrl: String,
        messages: List<ToolMessage>,
        tools: JsonArray?,
        model: String = CHAT_MODEL,
        numCtx: Int = CHAT_NUM_CTX,
        temperature: Double? = null,
    ): ToolTurn {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("think", false)
            if (tools != null) put("tools", tools)
            put("options", buildJsonObject {
                put("num_ctx", numCtx)
                if (temperature != null) put("temperature", temperature)
            })
            put("messages", buildJsonArray { for (m in messages) add(toolMessageJson(m)) })
        }.toString()
        val response = try {
            http.post(chatUrl(baseUrl)) {
                timeout { requestTimeoutMillis = TOOL_TURN_TIMEOUT_MS }
                setBody(TextContent(body, ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolTurn.Offline
        }
        return try {
            val status = response.status.value
            if (status !in 200..299) return ToolTurn.Error("HTTP $status")
            val obj = parseLine(response.bodyAsText().trim()) ?: return ToolTurn.Error("bad response")
            if (obj["error"] != null) return ToolTurn.Error("model error")
            if ((obj["done"] as? JsonPrimitive)?.booleanOrNull != true) return ToolTurn.Error("incomplete response")
            val message = obj["message"] as? JsonObject ?: return ToolTurn.Error("bad response")
            val calls = parseToolCalls(message["tool_calls"])
            if (calls.isNotEmpty()) return ToolTurn.Calls(calls)
            val content = message["content"] as? JsonPrimitive
            if (content == null || !content.isString) ToolTurn.Error("bad response") else ToolTurn.Answer(content.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolTurn.Error("bad response")
        }
    }

    fun close() = http.close()

    private fun toolMessageJson(m: ToolMessage): JsonObject = buildJsonObject {
        put("role", m.role)
        put("content", m.content)
        if (m.toolName != null) put("tool_name", m.toolName)
        if (m.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                for (c in m.toolCalls) add(buildJsonObject {
                    put("function", buildJsonObject {
                        put("name", c.name)
                        put("arguments", c.arguments)
                    })
                })
            })
        }
    }

    private fun parseToolCalls(element: JsonElement?): List<ToolCall> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val fn = (item as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val args = when (val a = fn["arguments"]) {
                is JsonObject -> a
                is JsonPrimitive -> if (a.isString) parseLine(a.content) ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
                else -> JsonObject(emptyMap())
            }
            ToolCall(name, args)
        }
    }

    private fun parseLine(line: String): JsonObject? =
        try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            null
        }

    private fun chatUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        return "$withScheme/api/chat"
    }
}

/** Earlier turns for a new question: the newest turns that fit in [CHAT_HISTORY_MAX_CHARS], oldest dropped first. */
fun trimHistory(history: List<ChatMessage>, maxChars: Int = CHAT_HISTORY_MAX_CHARS): List<ChatMessage> {
    var total = 0
    val kept = ArrayList<ChatMessage>()
    for (m in history.asReversed()) {
        if (total + m.content.length > maxChars) break
        total += m.content.length
        kept += m
    }
    return kept.asReversed()
}
