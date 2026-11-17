package dev.tim.assistant.server.wallycal

import dev.tim.assistant.server.WallyCalConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The HttpClient for WallyCalendar calls (connect 10 s, whole request 30 s); the caller closes it at shutdown. */
fun newWallyCalHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 30_000
    }
}

/** A finished HTTP exchange. [body] is the raw response text and is never logged. */
class WallyCalReply(val status: Int, val body: String)

/** The call did not complete (network, timeout, unreadable body). Carries only an error class. */
class WallyCalUnreachable(val kind: String, val beforeSend: Boolean = false) : Exception(kind)

/** True for failures that can only happen before any request bytes leave (connection refused, unknown host). */
private fun happenedBeforeSending(e: Throwable): Boolean {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth < 6) {
        if (cur is java.net.UnknownHostException) return true
        if (cur is java.net.ConnectException && cur.message?.lowercase()?.contains("refused") != false) return true
        cur = cur.cause
        depth++
    }
    return false
}

/** Calls WallyCalendar with its "wally" token. Never retries a POST. */
class WallyCalClient(private val http: HttpClient, private val config: WallyCalConfig) {

    suspend fun events(from: String, to: String): WallyCalReply = call {
        http.get("${config.root}/v1/events") {
            header(HttpHeaders.Authorization, "Bearer ${config.token}")
            url {
                parameters.append("from", from)
                parameters.append("to", to)
            }
        }
    }

    suspend fun dreamWeek(): WallyCalReply = call {
        http.get("${config.root}/v1/dream-week") {
            header(HttpHeaders.Authorization, "Bearer ${config.token}")
        }
    }

    suspend fun createEvent(jsonBody: String): WallyCalReply = call {
        http.post("${config.root}/v1/events") {
            header(HttpHeaders.Authorization, "Bearer ${config.token}")
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }
    }

    private suspend fun call(block: suspend () -> io.ktor.client.statement.HttpResponse): WallyCalReply {
        try {
            val response = block()
            return WallyCalReply(response.status.value, response.bodyAsText())
        } catch (e: CancellationException) {
            // a client timeout can surface as a cancellation; real cancellation must still propagate
            currentCoroutineContext().ensureActive()
            throw WallyCalUnreachable("timeout")
        } catch (e: Exception) {
            throw WallyCalUnreachable(e::class.simpleName ?: "error", happenedBeforeSending(e))
        }
    }
}
