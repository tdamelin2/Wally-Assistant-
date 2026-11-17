package dev.tim.assistant.server.google

import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ErrorResponse
import dev.tim.assistant.server.isAuthorized
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tim.assistant.server.google.MailRoutes")

/** `GET /mail/{gmailId}/body` and `GET /mail/labels`, below the caller's /v1 bearer check. */
fun Route.mailRoutes(config: Config, api: GoogleApi?) {
    val calendarToken = config.tokens["calendar"]

    // the WallyCalendar token may not read mail
    suspend fun ApplicationCall.allowed(): Boolean {
        if (calendarToken != null && isAuthorized(this, listOf(calendarToken))) {
            respond(HttpStatusCode.Forbidden)
            return false
        }
        if (api == null) {
            respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("google-not-configured"))
            return false
        }
        return true
    }

    get("/mail/labels") {
        if (!call.allowed()) return@get
        val result = try {
            api!!.userLabels()
        } catch (e: GoogleAuthException) {
            api!!.invalidateAuth()
            log.warn("mail labels auth failure")
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("google-unavailable"))
            return@get
        }
        if (result.failure != null || !result.complete) {
            log.warn("mail labels failed: {}", result.failure)
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("google-unavailable"))
            return@get
        }
        call.respond(result.items)
    }

    get("/mail/{gmailId}/body") {
        if (!call.allowed()) return@get
        val gmailId = call.parameters["gmailId"].orEmpty()
        val result = try {
            api!!.mailBody(gmailId)
        } catch (e: GoogleAuthException) {
            api!!.invalidateAuth()
            log.warn("mail body auth failure")
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("google-unavailable"))
            return@get
        }
        when {
            result.failure != null || !result.complete -> {
                log.warn("mail body failed: {}", result.failure)
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("google-unavailable"))
            }
            result.items.isEmpty() -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not-found"))
            else -> call.respond(result.items.first())
        }
    }
}
