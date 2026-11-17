package dev.tim.assistant.server.chat.ladder

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Chosen by the Phase 18 gate (reports/phase-18-measure.md): the prefixed variant won the tie. */
const val EMBED_PREFIX = "task: classification | query: "

/** Turns a text into a vector, or null when that is not possible right now. Never throws (except cancellation). */
fun interface TextEmbedder {
    suspend fun embed(text: String, timeoutMs: Long): FloatArray?
}

/**
 * Ollama `/api/embed` client for the sorter. The embedding model is kept loaded (`keep_alive: -1`) so the short
 * timeout holds. It does not take the generation lock. Any failure gives null; texts are never logged.
 */
class Embedder(baseUrl: String, private val model: String, engine: HttpClientEngine? = null) : TextEmbedder, AutoCloseable {
    private val url = baseUrl.trimEnd('/') + "/api/embed"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(engine ?: CIO.create()) { install(HttpTimeout) }

    override suspend fun embed(text: String, timeoutMs: Long): FloatArray? {
        val body = buildJsonObject {
            put("model", model)
            put("input", EMBED_PREFIX + text)
            put("keep_alive", -1)
        }
        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = timeoutMs
                    socketTimeoutMillis = timeoutMs
                    connectTimeoutMillis = timeoutMs
                }
                setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
            }
            if (!response.status.isSuccess()) return null
            val arr: JsonArray = json.parseToJsonElement(response.bodyAsText()).jsonObject["embeddings"]!!.jsonArray
            if (arr.size != 1) return null
            val values = arr[0].jsonArray
            FloatArray(values.size) { (values[it] as JsonPrimitive).jsonPrimitive.content.toFloat() }
        } catch (e: CancellationException) {
            // a request timeout cancels the call from inside; a real cancellation of the caller must propagate
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun close() = client.close()
}
