package dev.tim.assistant.server.wallycal

import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ErrorResponse
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.isAuthorized
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

private const val MAX_BODY_CHARS = 256 * 1024
private const val MAX_CLIENT_KEY = 200
private const val KV_PREFIX = "calcreate:"
private const val ATTEMPT_PREFIX = "calattempt:"
private val routeJson = Json { ignoreUnknownKeys = true }

/** The event keys forwarded to WallyCalendar, in this order. Everything else in the request is dropped. */
private val FORWARDED_KEYS = listOf(
    "title", "startAt", "endAt", "allDay", "location", "note", "color", "repeatRule", "repeatUntil", "reminderMinutes",
)

sealed interface CreateOutcome {
    data class Created(val id: String, val fresh: Boolean) : CreateOutcome
    data class PassThrough(val status: Int, val body: String) : CreateOutcome
    data object Unreachable : CreateOutcome
}

/**
 * The calls the routes make to WallyCalendar. One create Mutex serializes creates (the DB write lock
 * is never held during HTTP); the stored `calcreate:<clientKey>` answer makes a repeated key a replay.
 */
class WallyCalService(
    private val sdb: ServerDb,
    private val client: WallyCalClient,
    private val poller: WallyCalPoller,
) {
    private val log = LoggerFactory.getLogger(WallyCalService::class.java)
    private val createLock = Mutex()

    /** WallyCalendar's answer, or null when it could not be reached. */
    suspend fun dreamWeek(): WallyCalReply? = try {
        client.dreamWeek()
    } catch (e: WallyCalUnreachable) {
        null
    }

    suspend fun createEvent(clientKey: String, forwardBody: JsonObject): CreateOutcome = createLock.withLock {
        val kvKey = KV_PREFIX + clientKey
        sdb.db.kvQueries.get(kvKey).executeAsOneOrNull()?.let { return@withLock CreateOutcome.Created(it, fresh = false) }
        // a leftover attempt means an earlier POST for this key may have reached WallyCalendar: never send again
        val attemptKey = ATTEMPT_PREFIX + clientKey
        if (sdb.db.kvQueries.get(attemptKey).executeAsOneOrNull() != null) {
            log.warn("wallycal create refused: an earlier attempt for this key has an unknown outcome")
            return@withLock CreateOutcome.Unreachable
        }
        sdb.writeLock.withLock { sdb.db.transaction { sdb.db.kvQueries.put(attemptKey, "1") } }
        val reply = try {
            client.createEvent(forwardBody.toString())
        } catch (e: WallyCalUnreachable) {
            log.warn("wallycal create failed: {}", e.kind)
            if (e.beforeSend) clearAttempt(attemptKey)
            return@withLock CreateOutcome.Unreachable
        }
        when (reply.status) {
            201 -> {
                val id = try {
                    ((routeJson.parseToJsonElement(reply.body) as? JsonObject)?.get("id") as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                } catch (e: Exception) {
                    null
                } ?: return@withLock CreateOutcome.Unreachable
                // the id is stored and the attempt removed in one step, before the poll
                sdb.writeLock.withLock {
                    sdb.db.transaction {
                        sdb.db.kvQueries.put(kvKey, id)
                        sdb.driver.execute(null, "DELETE FROM kv WHERE key = ?", 1) { bindString(0, attemptKey) }
                    }
                }
                try {
                    poller.pollNow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("wallycal poll after create failed: {}", e::class.simpleName)
                }
                CreateOutcome.Created(id, fresh = true)
            }
            400 -> {
                clearAttempt(attemptKey)
                CreateOutcome.PassThrough(400, reply.body)
            }
            else -> {
                log.warn("wallycal create answered http {}", reply.status)
                CreateOutcome.Unreachable
            }
        }
    }

    private suspend fun clearAttempt(attemptKey: String) {
        sdb.writeLock.withLock {
            sdb.db.transaction { sdb.driver.execute(null, "DELETE FROM kv WHERE key = ?", 1) { bindString(0, attemptKey) } }
        }
    }
}

private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) = respond(status, ErrorResponse(message))

private fun forwardBody(request: JsonObject): JsonObject {
    val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    for (key in FORWARDED_KEYS) {
        out[key] = request[key] ?: when (key) {
            "color" -> JsonPrimitive("BLUE")
            "allDay" -> JsonPrimitive(false)
            "repeatRule" -> JsonPrimitive("NONE")
            else -> JsonNull
        }
    }
    return JsonObject(out)
}

/** Registers the calendar routes below the caller's bearer check. */
fun Route.wallyCalRoutes(config: Config, service: WallyCalService?) {
    val calendarToken = config.tokens["calendar"]
    route("/calendar") {
        // the inbound make-task token must never reach these calls (it would loop back to WallyCalendar)
        suspend fun ApplicationCall.allowed(): Boolean {
            if (calendarToken != null && isAuthorized(this, listOf(calendarToken))) {
                respond(HttpStatusCode.Forbidden)
                return false
            }
            if (config.wallycal == null || service == null) {
                fail(HttpStatusCode.ServiceUnavailable, "calendar-not-configured")
                return false
            }
            return true
        }

        get("/dream-week") {
            if (!call.allowed()) return@get
            val reply = service!!.dreamWeek()
            when {
                reply == null -> call.fail(HttpStatusCode.BadGateway, "calendar-unreachable")
                reply.status == 200 -> call.respondText(reply.body, ContentType.Application.Json, HttpStatusCode.OK)
                reply.status == 404 && isNotSent(reply.body) -> call.fail(HttpStatusCode.NotFound, "not-sent")
                else -> call.fail(HttpStatusCode.BadGateway, "calendar-unreachable")
            }
        }

        post("/events") {
            if (!call.allowed()) return@post
            val text = call.receiveText()
            val request = if (text.length > MAX_BODY_CHARS) null else try {
                routeJson.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            }
            if (request == null) {
                call.fail(HttpStatusCode.BadRequest, "invalid: body")
                return@post
            }
            val clientKey = (request["clientKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (clientKey == null || clientKey.isEmpty() || clientKey.length > MAX_CLIENT_KEY) {
                call.fail(HttpStatusCode.BadRequest, "invalid: clientKey")
                return@post
            }
            when (val outcome = service!!.createEvent(clientKey, forwardBody(request))) {
                is CreateOutcome.Created -> call.respondText(
                    JsonObject(mapOf("id" to JsonPrimitive(outcome.id))).toString(),
                    ContentType.Application.Json,
                    if (outcome.fresh) HttpStatusCode.Created else HttpStatusCode.OK,
                )
                is CreateOutcome.PassThrough -> call.respondText(
                    outcome.body, ContentType.Application.Json, HttpStatusCode.fromValue(outcome.status),
                )
                CreateOutcome.Unreachable -> call.fail(HttpStatusCode.BadGateway, "calendar-unreachable")
            }
        }
    }
}

private fun isNotSent(body: String): Boolean = try {
    ((routeJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.content == "not-sent"
} catch (e: Exception) {
    false
}
