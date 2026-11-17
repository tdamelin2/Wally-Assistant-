package dev.tim.assistant.data

import dev.tim.assistant.model.HealthResponse
import dev.tim.assistant.model.MailBody
import dev.tim.assistant.model.MailLabel
import kotlinx.serialization.builtins.ListSerializer
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushRequest
import dev.tim.assistant.model.PushResponse
import dev.tim.assistant.model.SyncRow
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.Headers
import io.ktor.http.encodeURLPath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Where the server is and which token to send. */
data class ServerConfig(val baseUrl: String, val token: String)

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data object Unauthorized : ApiResult<Nothing>
    data class Failure(val message: String) : ApiResult<Nothing>
}

/** HTTP calls to the Ktor server (SPEC section 6). Pass an engine in tests; null uses the platform engine. */
class SyncClient(engine: HttpClientEngine? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = if (engine == null) {
        HttpClient { configure() }
    } else {
        HttpClient(engine) { configure() }
    }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
    }

    private fun url(cfg: ServerConfig, path: String) = cfg.baseUrl.trim().trimEnd('/') + path

    private suspend fun <T> call(block: suspend () -> HttpResponse, parse: suspend (HttpResponse) -> T): ApiResult<T> =
        try {
            val response = block()
            when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status.value in 200..299 -> ApiResult.Ok(parse(response))
                else -> ApiResult.Failure("HTTP ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: e::class.simpleName ?: "error")
        }

    suspend fun push(cfg: ServerConfig, rows: List<SyncRow>): ApiResult<PushResponse> = call(
        {
            http.post(url(cfg, "/v1/sync/push")) {
                header(HttpHeaders.Authorization, "Bearer ${cfg.token}")
                contentType(ContentType.Application.Json)
                setBody(PushRequest(rows))
            }
        },
        { it.body<PushResponse>() },
    )

    suspend fun pull(cfg: ServerConfig, since: Long): ApiResult<PullResponse> = call(
        {
            http.get(url(cfg, "/v1/sync/pull")) {
                header(HttpHeaders.Authorization, "Bearer ${cfg.token}")
                parameter("since", since)
            }
        },
        { it.body<PullResponse>() },
    )

    suspend fun health(cfg: ServerConfig): ApiResult<HealthResponse> = call(
        {
            http.get(url(cfg, "/v1/health")) {
                header(HttpHeaders.Authorization, "Bearer ${cfg.token}")
            }
        },
        { it.body<HealthResponse>() },
    )
    /** The Dream week via the server (`/v1/calendar/dream-week`). Only a 404 with error `not-sent` is [DreamWeekResult.NotSent]. */
    suspend fun dreamWeek(cfg: ServerConfig): DreamWeekResult =
        try {
            val response = http.get(url(cfg, "/v1/calendar/dream-week")) {
                header(HttpHeaders.Authorization, "Bearer ${cfg.token}")
            }
            val status = response.status.value
            when {
                status == 401 -> DreamWeekResult.Unauthorized
                status == 200 -> DreamWeekResult.Sent(json.decodeFromString(DreamWeek.serializer(), response.bodyAsText()))
                status == 404 && notSent(response.bodyAsText()) -> DreamWeekResult.NotSent
                else -> DreamWeekResult.Unavailable("HTTP $status")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DreamWeekResult.Unavailable(e.message ?: e::class.simpleName ?: "error")
        }

    private fun notSent(body: String): Boolean =
        try {
            (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.content == "not-sent"
        } catch (e: Exception) {
            false
        }

    // ---- Study (SPEC section 6). Ordinary calls keep 30 s; file and Moodle calls wait up to 10 minutes. ----

    private fun HttpRequestBuilder.longTimeout() {
        timeout {
            requestTimeoutMillis = STUDY_TIMEOUT_MS
            socketTimeoutMillis = STUDY_TIMEOUT_MS
        }
    }

    private fun HttpRequestBuilder.auth(cfg: ServerConfig) {
        header(HttpHeaders.Authorization, "Bearer ${cfg.token}")
    }

    /**
     * Runs a Study call. [messageFor] maps a failing status to a fixed message (null = "HTTP <code>");
     * [timeoutMessage] replaces the generic message when the connection broke after the request started.
     */
    private suspend fun <T> studyCall(
        messageFor: (Int) -> String?,
        timeoutMessage: String = NEEDS_SERVER,
        block: suspend () -> HttpResponse,
        parse: suspend (HttpResponse) -> T,
    ): ApiResult<T> =
        try {
            val response = block()
            when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status.value in 200..299 -> ApiResult.Ok(parse(response))
                else -> ApiResult.Failure(messageFor(response.status.value) ?: "HTTP ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectTimeoutException) {
            ApiResult.Failure(NEEDS_SERVER)
        } catch (e: Exception) {
            ApiResult.Failure(timeoutMessage)
        }

    private fun intField(body: String, key: String): Int =
        (json.parseToJsonElement(body) as JsonObject)[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    private fun stringField(body: String, key: String): String =
        (json.parseToJsonElement(body) as JsonObject)[key]?.jsonPrimitive?.content ?: error("missing $key")

    /** Sends the Moodle login link; returns the number of courses the server found. */
    suspend fun moodleToken(cfg: ServerConfig, link: String): ApiResult<Int> = studyCall(
        messageFor = {
            when (it) {
                400 -> "bad link"
                502 -> "Moodle rejected the link"
                503 -> MOODLE_NOT_SET_UP
                else -> null
            }
        },
        block = {
            http.post(url(cfg, "/v1/study/moodle-token")) {
                auth(cfg)
                longTimeout()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("link", link) }.toString())
            }
        },
        parse = { intField(it.bodyAsText(), "courses") },
    )

    /** Asks the server to look for new Moodle files now; returns how many new files it listed. */
    suspend fun studySyncNow(cfg: ServerConfig): ApiResult<Int> = studyCall(
        messageFor = {
            when (it) {
                409 -> "A check is already running"
                503 -> MOODLE_NOT_SET_UP
                else -> null
            }
        },
        block = {
            http.post(url(cfg, "/v1/study/sync-now")) {
                auth(cfg)
                longTimeout()
            }
        },
        parse = { intField(it.bodyAsText(), "newFiles") },
    )

    /** Uploads a PDF or PPTX; [courseId] is an existing course id or a new course name. Returns the file id. */
    suspend fun uploadStudyFile(cfg: ServerConfig, courseId: String, bytes: ByteArray, name: String): ApiResult<String> = studyCall(
        messageFor = {
            when (it) {
                413 -> "Too large (max 50 MB)"
                415 -> "Only PDF or PPTX"
                else -> null
            }
        },
        timeoutMessage = UPLOAD_UNKNOWN,
        block = {
            http.post(url(cfg, "/v1/study/upload")) {
                auth(cfg)
                longTimeout()
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("courseId", courseId)
                            append(
                                "file", bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, name).toString(),
                                    )
                                },
                            )
                        },
                    ),
                )
            }
        },
        parse = { stringField(it.bodyAsText(), "fileId") },
    )

    /** The extracted text of a study file. Failure("HTTP 409") = no text (not READY), Failure("HTTP 404") = unknown file. */
    suspend fun studyFileText(cfg: ServerConfig, fileId: String): ApiResult<String> = studyCall(
        messageFor = { null },
        block = {
            http.get(url(cfg, "/v1/study/files/${fileId.encodeURLPath()}/text")) {
                auth(cfg)
                longTimeout()
            }
        },
        parse = { it.bodyAsText() },
    )

    /** Records which doc is the study set of [fileId]. Safe to repeat. */
    suspend fun setStudySet(cfg: ServerConfig, fileId: String, docId: String): ApiResult<Unit> = studyCall(
        messageFor = { null },
        block = {
            http.post(url(cfg, "/v1/study/files/${fileId.encodeURLPath()}/study-set")) {
                auth(cfg)
                longTimeout()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("docId", docId) }.toString())
            }
        },
        parse = { },
    )

    /** Deletes an uploaded file (server removes the row and both files). */
    suspend fun deleteStudyFile(cfg: ServerConfig, fileId: String): ApiResult<Unit> = studyCall(
        messageFor = {
            when (it) {
                409 -> "Moodle files cannot be deleted"
                404 -> "File not found"
                else -> null
            }
        },
        block = {
            http.delete(url(cfg, "/v1/study/files/${fileId.encodeURLPath()}")) {
                auth(cfg)
                longTimeout()
            }
        },
        parse = { },
    )

    /** Switches checking of one Moodle course on or off (ordinary 30 s timeout). */
    suspend fun setCourseEnabled(cfg: ServerConfig, courseId: String, enabled: Boolean): ApiResult<Unit> = studyCall(
        messageFor = { null },
        block = {
            http.put(url(cfg, "/v1/study/courses/${courseId.encodeURLPath()}/enabled")) {
                auth(cfg)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("enabled", enabled) }.toString())
            }
        },
        parse = { },
    )

    // ---- Mail (Phase 12): reads for the AI jobs. Ordinary 30 s timeout. ----

    private fun mailMessage(status: Int): String? = when (status) {
        403 -> MAIL_FORBIDDEN
        404 -> MAIL_NOT_FOUND
        502 -> MAIL_GOOGLE_DOWN
        503 -> MAIL_NOT_SET_UP
        else -> null
    }

    /** One message's text. Failure messages: [MAIL_NOT_FOUND], [MAIL_FORBIDDEN], [MAIL_GOOGLE_DOWN], [MAIL_NOT_SET_UP]. */
    suspend fun mailBody(cfg: ServerConfig, gmailId: String): ApiResult<MailBody> = studyCall(
        messageFor = ::mailMessage,
        block = {
            http.get(url(cfg, "/v1/mail/${gmailId.encodeURLPath()}/body")) { auth(cfg) }
        },
        parse = { json.decodeFromString(MailBody.serializer(), it.bodyAsText()) },
    )

    /** The user's Gmail labels. Same failure messages as [mailBody]. */
    suspend fun mailLabels(cfg: ServerConfig): ApiResult<List<MailLabel>> = studyCall(
        messageFor = ::mailMessage,
        block = {
            http.get(url(cfg, "/v1/mail/labels")) { auth(cfg) }
        },
        parse = { json.decodeFromString(ListSerializer(MailLabel.serializer()), it.bodyAsText()) },
    )
}

/** What `POST /v1/calendar/events` answered. */
sealed interface CreateEventResult {
    data class Created(val id: String, val fresh: Boolean) : CreateEventResult
    data class Rejected(val body: String) : CreateEventResult
    data object CalendarUnreachable : CreateEventResult
    data object CalendarNotConfigured : CreateEventResult

    /** The Wally server could not be reached (or refused the key): nothing was created. */
    data object ServerUnreachable : CreateEventResult

    /** Any other status, or a success answer without an id. */
    data class Other(val status: Int) : CreateEventResult
}

const val MAIL_NOT_FOUND = "Email not found"
const val MAIL_FORBIDDEN = "This key may not read mail"
const val MAIL_GOOGLE_DOWN = "Google not reachable from the server"
const val MAIL_NOT_SET_UP = "Google not set up on the server"

const val STUDY_TIMEOUT_MS = 600_000L
const val NEEDS_SERVER = "Needs the server - try again when connected"
const val MOODLE_NOT_SET_UP = "Moodle not set up on the server"
const val UPLOAD_UNKNOWN = "Upload result unknown - sync and check the list"
