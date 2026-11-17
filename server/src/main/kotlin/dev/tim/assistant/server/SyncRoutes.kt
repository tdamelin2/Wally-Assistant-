package dev.tim.assistant.server

import dev.tim.assistant.model.PushRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.sql.SQLException

/** True when [e] or any cause is SQLite's "database or disk is full" error. */
fun isSqliteFull(e: Throwable): Boolean {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth < 10) {
        if (t is SQLException && (t.errorCode == 13 || t.message?.contains("SQLITE_FULL") == true)) return true
        t = t.cause
        depth++
    }
    return false
}

/** [calendarToken] is the WallyCalendar `calendar` token: a push that carries it may not write mail rows. */
fun Route.syncRoutes(store: SyncStore, calendarToken: String? = null) {
    post("/sync/push") {
        val request = call.receive<PushRequest>()
        if (request.rows.size > MAX_PUSH_ROWS) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val fromCalendar = calendarToken != null && isAuthorized(call, listOf(calendarToken))
        val response = try {
            store.push(request.rows, fromCalendar)
        } catch (e: Exception) {
            if (isSqliteFull(e)) {
                call.respond(HttpStatusCode.InsufficientStorage)
                return@post
            }
            throw e
        }
        call.respond(response)
    }
    get("/sync/pull") {
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        if (since == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        call.respond(store.pull(since))
    }
}
