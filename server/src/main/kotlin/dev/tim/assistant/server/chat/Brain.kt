package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.CHAT_MODEL
import dev.tim.assistant.ai.CHAT_NUM_CTX
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val HP_NUM_CTX = 8192
const val BRAIN_PROBE_TIMEOUT_MS = 2_000L

/** Which Ollama answers a request: its address, the model name and the context size to send. */
data class BrainSelection(val baseUrl: String, val model: String, val numCtx: Int)

/**
 * Chooses the model for one request: the XPS chat model when the XPS Ollama answers /api/tags within
 * 2 seconds and lists that model, else the HP briefing model. Pass the selection through the whole
 * request and its retries. The HP generation lock is the caller's (Main's one Mutex): HP chat uses
 * withLock, background work tryLock.
 */
class Brain(
    private val xpsUrl: String?,
    private val hpUrl: String,
    private val hpModel: String,
    private val xpsModel: String = CHAT_MODEL,
    private val xpsNumCtx: Int = CHAT_NUM_CTX,
    private val hpNumCtx: Int = HP_NUM_CTX,
    probeTimeoutMs: Long = BRAIN_PROBE_TIMEOUT_MS,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(engine ?: CIO.create()) {
        install(HttpTimeout) {
            connectTimeoutMillis = probeTimeoutMs
            requestTimeoutMillis = probeTimeoutMs
            socketTimeoutMillis = probeTimeoutMs
        }
    }

    /** The HP model, whatever the XPS says (the retry target when an XPS call failed before any tool ran). */
    fun hpSelection(): BrainSelection = BrainSelection(hpUrl.trimEnd('/'), hpModel, hpNumCtx)

    /** true when [selection] is the HP model: those runs share the one generation lock; XPS runs do not. */
    fun isHp(selection: BrainSelection): Boolean = selection.baseUrl == hpUrl.trimEnd('/')

    suspend fun pick(): BrainSelection {
        if (xpsUrl != null && xpsHasModel(xpsUrl.trimEnd('/'))) return BrainSelection(xpsUrl.trimEnd('/'), xpsModel, xpsNumCtx)
        return BrainSelection(hpUrl.trimEnd('/'), hpModel, hpNumCtx)
    }

    private suspend fun xpsHasModel(base: String): Boolean {
        return try {
            val response = client.get("$base/api/tags")
            if (!response.status.isSuccess()) return false
            val models = json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]?.jsonArray ?: JsonArray(emptyList())
            models.any { m ->
                val o = m.jsonObject
                (o["name"]?.jsonPrimitive?.contentOrNull ?: o["model"]?.jsonPrimitive?.contentOrNull) == xpsModel
            }
        } catch (e: CancellationException) {
            // a request timeout cancels the call from inside; a real cancellation of the caller must propagate
            currentCoroutineContext().ensureActive()
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun close() = client.close()
}
