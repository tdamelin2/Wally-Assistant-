package dev.tim.assistant.server.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Any failure talking to Ollama (non-2xx, timeout, connection problem, unreadable body). */
class OllamaException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 1_200_000L

/**
 * The server's own Ollama client (own CIO client; [engine] is for tests). The 20 minute timeouts
 * cover a cold model load on the 4 GB HP. Prompts and replies are never logged.
 */
class OllamaClient(baseUrl: String, engine: HttpClientEngine? = null) : AutoCloseable {
    private val url = baseUrl.trimEnd('/') + "/api/chat"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(engine ?: CIO.create()) {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    /** One non-streaming chat call; returns `message.content`. */
    suspend fun chat(model: String, system: String, user: String, numCtx: Int, format: JsonObject? = null, temperature: Double? = null, numPredict: Int? = null): String {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("think", false)
            put("messages", JsonArray(listOf(message("system", system), message("user", user))))
            put("options", buildJsonObject {
                put("num_ctx", numCtx)
                if (temperature != null) put("temperature", temperature)
                if (numPredict != null) put("num_predict", numPredict)
            })
            if (format != null) put("format", format)
        }
        val responseText = try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
            if (!response.status.isSuccess()) throw OllamaException("ollama returned HTTP ${response.status.value}")
            response.bodyAsText()
        } catch (e: OllamaException) {
            throw e
        } catch (e: CancellationException) {
            // a request timeout cancels the call from inside; a real cancellation of the caller must propagate
            currentCoroutineContext().ensureActive()
            throw OllamaException("ollama call timed out", e)
        } catch (e: Exception) {
            throw OllamaException("ollama call failed: ${e::class.simpleName}", e)
        }
        return try {
            json.parseToJsonElement(responseText).jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull
                ?: throw OllamaException("ollama reply has no content")
        } catch (e: OllamaException) {
            throw e
        } catch (e: Exception) {
            throw OllamaException("ollama reply is not the expected shape", e)
        }
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(content))
    }

    override fun close() = client.close()
}
