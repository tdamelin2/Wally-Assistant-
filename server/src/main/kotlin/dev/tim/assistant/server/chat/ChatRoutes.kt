package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.tools.ApprovalExecutor
import dev.tim.assistant.ai.tools.ApprovalSink
import dev.tim.assistant.model.ApprovalDecisionRequest
import dev.tim.assistant.model.ApprovalDecisionResponse
import dev.tim.assistant.model.ChatBrainResponse
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.PhoneResultRequest
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.ErrorResponse
import dev.tim.assistant.server.isAuthorized
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.coroutines.cancellation.CancellationException

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = try {
    receive<T>()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private suspend fun ApplicationCall.bad(message: String) = respond(HttpStatusCode.BadRequest, ErrorResponse(message))

/**
 * `/v1/chat/...`, `/v1/memory` and `/v1/approvals/{id}`, below the caller's bearer check (unknown token: 401 before this
 * runs). The WallyCalendar token gets 403 on every one of them. [chat] null (no model configured) answers 503 on send only;
 * lists, memory and approvals still work.
 */
fun Route.chatRoutes(
    calendarToken: String?,
    chat: ChatService,
    sink: ApprovalSink,
    clock: () -> Long = System::currentTimeMillis,
    /** config.tokens (name to token): the route reads the caller's name from it; only "phone" gets a phone turn. */
    tokens: Map<String, String> = emptyMap(),
) {
    val approvals = ApprovalExecutor(sink, clock)

    suspend fun ApplicationCall.allowed(): Boolean {
        if (calendarToken != null && isAuthorized(this, listOf(calendarToken))) {
            respond(HttpStatusCode.Forbidden)
            return false
        }
        return true
    }

    get("/chat/list") {
        if (!call.allowed()) return@get
        call.respond(chat.list())
    }

    get("/chat/brain") {
        if (!call.allowed()) return@get
        call.respond(ChatBrainResponse(chat.brainName()))
    }

    post("/chat") {
        if (!call.allowed()) return@post
        val request = call.receiveOrNull<NewChatRequest>() ?: return@post call.bad("invalid: body")
        val created = chat.create(request) ?: return@post call.bad("invalid: id")
        call.respond(created)
    }

    get("/chat/{id}") {
        if (!call.allowed()) return@get
        val detail = chat.detail(call.parameters["id"].orEmpty())
        if (detail == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not-found")) else call.respond(detail)
    }

    post("/chat/{id}/message") {
        if (!call.allowed()) return@post
        val request = call.receiveOrNull<SendMessageRequest>() ?: return@post call.bad("invalid: body")
        // the caller's identity comes from the bearer token itself, never from the request body
        val tokenName = tokens.entries.firstOrNull { isAuthorized(call, listOf(it.value)) }?.key
        when (val result = chat.send(call.parameters["id"].orEmpty(), request, tokenName)) {
            is SendResult.Ok -> call.respond(result.response)
            is SendResult.PhoneTurn -> call.respond(HttpStatusCode.Accepted, result.turn)
            is SendResult.Conflict -> call.respond(HttpStatusCode.Conflict, ErrorResponse(result.message))
            is SendResult.BadInput -> call.bad(result.message)
            is SendResult.Unavailable -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(result.message))
            SendResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not-found"))
        }
    }

    post("/chat/{id}/phone-result") {
        // the phone's token only: the calendar and laptop tokens are refused, an unknown token never got this far (401)
        val phoneToken = tokens[PHONE_TOKEN_NAME]
        if (phoneToken == null || !isAuthorized(call, listOf(phoneToken))) return@post call.respond(HttpStatusCode.Forbidden)
        val request = call.receiveOrNull<PhoneResultRequest>() ?: return@post call.bad("invalid: body")
        when (val result = chat.phoneResult(call.parameters["id"].orEmpty(), request)) {
            is SendResult.Ok -> call.respond(result.response)
            is SendResult.PhoneTurn -> call.respond(HttpStatusCode.InternalServerError)
            is SendResult.Conflict -> call.respond(HttpStatusCode.Conflict, ErrorResponse(result.message))
            is SendResult.BadInput -> call.bad(result.message)
            is SendResult.Unavailable -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(result.message))
            SendResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not-found"))
        }
    }

    delete("/chat/{id}") {
        if (!call.allowed()) return@delete
        when (chat.delete(call.parameters["id"].orEmpty())) {
            DeleteChatResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            DeleteChatResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not-found"))
            DeleteChatResult.Pinned -> call.respond(HttpStatusCode.Conflict, ErrorResponse("The weekly report chat cannot be deleted"))
        }
    }

    post("/chat/{id}/undo/{actionId}") {
        if (!call.allowed()) return@post
        val answer = chat.undo(call.parameters["id"].orEmpty(), call.parameters["actionId"].orEmpty())
        if (answer == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not-found")) else call.respond(answer)
    }

    get("/memory") {
        if (!call.allowed()) return@get
        call.respond(chat.memory())
    }

    post("/approvals/{id}") {
        if (!call.allowed()) return@post
        val request = call.receiveOrNull<ApprovalDecisionRequest>() ?: return@post call.bad("invalid: body")
        if (request.decision != "yes" && request.decision != "no") return@post call.bad("invalid: decision")
        val answer = decideApproval(approvals, sink, call.parameters["id"].orEmpty(), request.decision == "yes", clock())
        when (answer.httpStatus) {
            200 -> call.respond(ApprovalDecisionResponse(answer.status, answer.message))
            else -> call.respond(HttpStatusCode.fromValue(answer.httpStatus), ErrorResponse(answer.message))
        }
    }
}
