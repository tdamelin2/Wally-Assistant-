package dev.tim.assistant.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import java.security.MessageDigest

/** True when the call carries `Authorization: Bearer <t>` and t matches one configured token. */
fun isAuthorized(call: ApplicationCall, tokens: Collection<String>): Boolean {
    val header = call.request.headers[HttpHeaders.Authorization] ?: return false
    if (!header.startsWith("Bearer ", ignoreCase = true)) return false
    val presented = header.substring(7).trim().toByteArray(Charsets.UTF_8)
    var ok = false
    for (t in tokens) {
        if (MessageDigest.isEqual(presented, t.toByteArray(Charsets.UTF_8))) ok = true
    }
    return ok
}

/** Rejects every call below this route with 401 before any other processing. */
fun Route.requireBearerToken(tokens: Collection<String>) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (!isAuthorized(call, tokens)) {
            call.respond(HttpStatusCode.Unauthorized)
            finish()
        }
    }
}
