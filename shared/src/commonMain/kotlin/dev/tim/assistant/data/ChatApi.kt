package dev.tim.assistant.data

import dev.tim.assistant.model.ApprovalDecisionRequest
import dev.tim.assistant.model.ApprovalDecisionResponse
import dev.tim.assistant.model.ChatBrainResponse
import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChatListResponse
import dev.tim.assistant.model.MemoryResponse
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.NewChatResponse
import dev.tim.assistant.model.NotificationsResponse
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.model.PhoneResultRequest
import dev.tim.assistant.model.PhoneTurnDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.model.UndoResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What an HP chat call answered. [Offline] = no answer at all (or the server is not set up); [Http] = the server said no. */
sealed interface HpResult<out T> {
    data class Ok<T>(val value: T) : HpResult<T>
    data class Offline(val message: String) : HpResult<Nothing>
    data class Http(val status: Int, val message: String) : HpResult<Nothing>
}

/** The text a failed [HpResult] shows Tim, or null for [HpResult.Ok]. */
fun HpResult<*>.failureText(): String? = when (this) {
    is HpResult.Ok -> null
    is HpResult.Offline -> message
    is HpResult.Http -> message
}

/** What `sendMessage` answered: the finished reply (200) or a turn the phone must write itself (202). */
sealed interface SendOutcome {
    data class Reply(val response: SendMessageResponse) : SendOutcome
    data class PhoneTurn(val turn: PhoneTurnDto) : SendOutcome
}

const val HP_OFFLINE_TEXT = "Can't reach Wally right now."
const val HP_NOT_SET_UP_TEXT = "Set up the server in Settings first."

internal const val CHAT_CONNECT_TIMEOUT_MS = 5_000L
internal const val CHAT_REQUEST_TIMEOUT_MS = 240_000L

/**
 * The HP routes of Phase 17 (`/v1/chat/...`, `/v1/memory`, `/v1/approvals/{id}`, `/v1/notifications`,
 * `/v1/notify-settings`). Connect 5 s, request 240 s (a chat turn can run a model). Pass an engine in tests.
 */
class ChatApi(private val config: () -> ServerConfig?, engine: HttpClientEngine? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = if (engine == null) HttpClient { configure() } else HttpClient(engine) { configure() }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = CHAT_CONNECT_TIMEOUT_MS
            requestTimeoutMillis = CHAT_REQUEST_TIMEOUT_MS
            socketTimeoutMillis = CHAT_REQUEST_TIMEOUT_MS
        }
        expectSuccess = false
    }

    private fun errorText(status: Int, body: String): String {
        val fromJson = try {
            (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
        return fromJson?.takeIf { it.isNotBlank() } ?: "HTTP $status"
    }

    private suspend fun <T> call(request: suspend (HttpClient, String, String) -> HttpResponse, parse: suspend (HttpResponse) -> T): HpResult<T> {
        val cfg = config() ?: return HpResult.Offline(HP_NOT_SET_UP_TEXT)
        val base = cfg.baseUrl.trim().trimEnd('/')
        return try {
            val response = request(http, base, cfg.token)
            if (response.status.value in 200..299) HpResult.Ok(parse(response))
            else HpResult.Http(response.status.value, errorText(response.status.value, response.bodyAsText()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HpResult.Offline(HP_OFFLINE_TEXT)
        }
    }

    private fun HttpRequestBuilder.auth(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun seg(value: String) = value.encodeURLPath()

    suspend fun chatList(): HpResult<ChatListResponse> =
        call({ c, base, t -> c.get("$base/v1/chat/list") { auth(t) } }, { it.body<ChatListResponse>() })

    suspend fun newChat(request: NewChatRequest): HpResult<NewChatResponse> = call(
        { c, base, t ->
            c.post("$base/v1/chat") {
                auth(t)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        },
        { it.body<NewChatResponse>() },
    )

    suspend fun chat(id: String): HpResult<ChatDetailResponse> =
        call({ c, base, t -> c.get("$base/v1/chat/${seg(id)}") { auth(t) } }, { it.body<ChatDetailResponse>() })

    /** 200 -> [SendOutcome.Reply]; 202 -> [SendOutcome.PhoneTurn] (the phone writes this turn); any other 2xx parses as a reply. */
    suspend fun sendMessage(chatId: String, request: SendMessageRequest): HpResult<SendOutcome> = call(
        { c, base, t ->
            c.post("$base/v1/chat/${seg(chatId)}/message") {
                auth(t)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        },
        { if (it.status.value == 202) SendOutcome.PhoneTurn(it.body<PhoneTurnDto>()) else SendOutcome.Reply(it.body<SendMessageResponse>()) },
    )

    /** `POST /v1/chat/{id}/phone-result`: the phone's decision for a turn it was handed. */
    suspend fun phoneResult(chatId: String, request: PhoneResultRequest): HpResult<SendMessageResponse> = call(
        { c, base, t ->
            c.post("$base/v1/chat/${seg(chatId)}/phone-result") {
                auth(t)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        },
        { it.body<SendMessageResponse>() },
    )

    suspend fun brain(): HpResult<ChatBrainResponse> =
        call({ c, base, t -> c.get("$base/v1/chat/brain") { auth(t) } }, { it.body<ChatBrainResponse>() })

    suspend fun deleteChat(id: String): HpResult<Unit> =
        call({ c, base, t -> c.delete("$base/v1/chat/${seg(id)}") { auth(t) } }, { })

    suspend fun undo(chatId: String, actionId: String): HpResult<UndoResponse> = call(
        { c, base, t -> c.post("$base/v1/chat/${seg(chatId)}/undo/${seg(actionId)}") { auth(t) } },
        { it.body<UndoResponse>() },
    )

    suspend fun memory(): HpResult<MemoryResponse> =
        call({ c, base, t -> c.get("$base/v1/memory") { auth(t) } }, { it.body<MemoryResponse>() })

    /** [yes] true = approve. A card that is not PENDING any more answers 409 with the server's message. */
    suspend fun decideApproval(id: String, yes: Boolean): HpResult<ApprovalDecisionResponse> = call(
        { c, base, t ->
            c.post("$base/v1/approvals/${seg(id)}") {
                auth(t)
                contentType(ContentType.Application.Json)
                setBody(ApprovalDecisionRequest(if (yes) "yes" else "no"))
            }
        },
        { it.body<ApprovalDecisionResponse>() },
    )

    /** Every reminder row with id > [since]. */
    suspend fun notifications(since: Long): HpResult<NotificationsResponse> = call(
        { c, base, t -> c.get("$base/v1/notifications") { auth(t); parameter("since", since) } },
        { it.body<NotificationsResponse>() },
    )

    suspend fun notifySettings(): HpResult<NotifySettingsDto> =
        call({ c, base, t -> c.get("$base/v1/notify-settings") { auth(t) } }, { it.body<NotifySettingsDto>() })

    /** The answer body is not read (the Contract names none): success is any 2xx. */
    suspend fun putNotifySettings(settings: NotifySettingsDto): HpResult<Unit> = call(
        { c, base, t ->
            c.put("$base/v1/notify-settings") {
                auth(t)
                contentType(ContentType.Application.Json)
                setBody(settings)
            }
        },
        { },
    )
}
