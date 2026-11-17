package dev.tim.assistant.server.notify

import dev.tim.assistant.model.NotificationsResponse
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.server.ErrorResponse
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.isAuthorized
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlin.coroutines.cancellation.CancellationException

/** One replay never returns more than this many rows (the phone asks again from the last id it saw). */
const val NOTIFY_REPLAY_LIMIT = 200L

private val HHMM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

/** null when [s] is a valid settings body, else the reason (HH:mm times only; the switches are booleans by type). */
fun validateNotifySettings(s: NotifySettingsDto): String? = when {
    !HHMM.matches(s.checkinTime) -> "invalid: checkinTime"
    !HHMM.matches(s.quietStart) -> "invalid: quietStart"
    !HHMM.matches(s.quietEnd) -> "invalid: quietEnd"
    else -> null
}

/** Reads and writes the reminder rows and settings of the private outbox. */
class NotifyService(private val sdb: ServerDb, private val writer: ServerWriter, private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * Every row with id above [since], oldest first. `delivered_at` is only "offered at" (set the first time a row is returned),
     * so a lost response never hides a reminder: the next call with the same cursor returns it again.
     */
    suspend fun since(since: Long): NotificationsResponse = writer.transaction { tx ->
        val rows = tx.db.chatQueries.selectNotificationsSince(since, NOTIFY_REPLAY_LIMIT).executeAsList()
        for (r in rows) if (r.delivered_at == null) tx.db.chatQueries.markNotificationDelivered(clock(), r.id)
        NotificationsResponse(rows.map { notificationDto(it) })
    }

    fun settings(): NotifySettingsDto = loadNotifySettings(sdb.db)

    /** Stores [s] in the server's kv (the format [loadNotifySettings] reads); the caller validated it. */
    suspend fun save(s: NotifySettingsDto) {
        writer.transaction { tx ->
            tx.db.kvQueries.put(KV_CHECKIN_TIME, s.checkinTime)
            tx.db.kvQueries.put(KV_QUIET_START, s.quietStart)
            tx.db.kvQueries.put(KV_QUIET_END, s.quietEnd)
            val on = buildList {
                if (s.checkin) add("checkin")
                if (s.morningList) add("morningList")
                if (s.headsUp) add("headsUp")
                if (s.ideas) add("ideas")
                if (s.weeklyReport) add("weeklyReport")
            }
            tx.db.kvQueries.put(KV_KINDS, on.joinToString(","))
        }
    }
}

/**
 * `GET /v1/notifications?since=<id>`, `GET` and `PUT /v1/notify-settings`, below the caller's bearer check (unknown token: 401
 * before this runs). The WallyCalendar token gets 403 on all of them.
 */
fun Route.notifyRoutes(calendarToken: String?, service: NotifyService) {
    suspend fun ApplicationCall.allowed(): Boolean {
        if (calendarToken != null && isAuthorized(this, listOf(calendarToken))) {
            respond(HttpStatusCode.Forbidden)
            return false
        }
        return true
    }

    get("/notifications") {
        if (!call.allowed()) return@get
        val raw = call.request.queryParameters["since"]
        val since = if (raw == null) 0L else raw.toLongOrNull()
        if (since == null || since < 0) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid: since"))
        call.respond(service.since(since))
    }

    get("/notify-settings") {
        if (!call.allowed()) return@get
        call.respond(service.settings())
    }

    put("/notify-settings") {
        if (!call.allowed()) return@put
        val body = try {
            call.receive<NotifySettingsDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid: body"))
        }
        val problem = validateNotifySettings(body)
        if (problem != null) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
        service.save(body)
        call.respond(service.settings())
    }
}
