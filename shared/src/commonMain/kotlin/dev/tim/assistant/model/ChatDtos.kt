package dev.tim.assistant.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Phase 17: the HP chat, memory, approvals and notification routes (`/v1/chat/...`, `/v1/memory`, `/v1/approvals/{id}`, `/v1/notifications`). */

@Serializable
data class ChatSummaryDto(
    val id: String,
    val title: String,
    val lastMessage: String? = null,
    val lastUsedAt: Long,
    val pinned: Boolean,
)

@Serializable
data class ChatListResponse(val chats: List<ChatSummaryDto>, val limit: Int = 10, val activeCount: Int)

/** [id] is a client UUID: the same id again returns the same chat and never evicts a second one. */
@Serializable
data class NewChatRequest(val id: String, val firstAssistantText: String? = null, val notificationId: Long? = null)

@Serializable
data class NewChatResponse(val chat: ChatSummaryDto, val archivedTitle: String? = null)

/** One chip of a "which one?" answer. [token] is issued by the server with the message that carries the chip. */
@Serializable
data class ChoiceDto(val kind: String, val id: String, val name: String, val token: String)

/** An approval card inside a chat message. [status] is PENDING, DONE, REJECTED or FAILED. */
@Serializable
data class CardDto(
    val approvalId: String,
    val tool: String,
    val title: String,
    val summary: String,
    val status: String,
    val display: CardDisplayDto? = null,
)

/**
 * Plain-words display for a pending "move exam" card (phase 18). Null means show the generic card.
 * No ids or tool names appear in any of these strings.
 */
@Serializable
data class CardDisplayDto(
    val title: String,
    val itemName: String,
    val before: String,
    val after: String,
    val yesLabel: String,
)

/** An Undo handle for a row an auto tool wrote. The server decides whether Undo is still allowed. */
@Serializable
data class ActionRefDto(val actionId: String, val label: String, val expiresAt: Long, val undone: Boolean)

@Serializable
data class ChatMessageDto(
    val id: String,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val createdAt: Long,
    val actions: List<ActionRefDto> = emptyList(),
    val cards: List<CardDto> = emptyList(),
    val choices: List<ChoiceDto> = emptyList(),
)

/** `GET /v1/chat/brain`: which brain would answer now. [brain] is "xps" or "hp". */
@Serializable
data class ChatBrainResponse(val brain: String)

@Serializable
data class ChatDetailResponse(val chat: ChatSummaryDto, val messages: List<ChatMessageDto>)

/**
 * [messageId] is a client UUID kept in the chat outbox: the same id again returns the stored reply without a second model
 * run (the same id with a different text is a 409). [screen] is the NavTab Tim was on when he sent it.
 */
@Serializable
data class SendMessageRequest(
    val messageId: String,
    val text: String,
    val createdAt: Long,
    val screen: String? = null,
    val choice: ChoiceDto? = null,
    val phoneBrain: Boolean = false,
    /** Phase 20: one of [ROUTES]. A request without it (1.17.0 clients) is [ROUTE_AUTO]. */
    val route: String = ROUTE_AUTO,
)

const val ROUTE_AUTO = "auto"
const val ROUTE_MODEL = "model"
const val ROUTE_CODEX = "codex"
val ROUTES = setOf(ROUTE_AUTO, ROUTE_MODEL, ROUTE_CODEX)

/** The reply text of a message sent with [ROUTE_CODEX]; a client starts polling for the Codex answer only on a reply with exactly this text. */
const val CODEX_ASKED_TEXT = "Asked Codex. The answer will arrive here in a minute or two."

/** 202 answer of `POST /v1/chat/{id}/message`: the phone writes this turn. [turnId] is "r:" + [messageId]. */
@Serializable
data class PhoneTurnDto(
    val turnId: String,
    val messageId: String,
    val system: String,
    val user: String,
    val schema: JsonObject,
    val expiresAt: Long,
)

/** Body of `POST /v1/chat/{id}/phone-result`: the phone's raw decision JSON plus its speed figures. */
@Serializable
data class PhoneResultRequest(
    val turnId: String,
    val messageId: String,
    val decision: String,
    val firstTokenMs: Long? = null,
    val tokensPerSecond: Double? = null,
)

@Serializable
data class SendMessageResponse(val user: ChatMessageDto, val reply: ChatMessageDto, val archivedTitle: String? = null)

@Serializable
data class UndoResponse(val ok: Boolean, val message: String)

@Serializable
data class MemoryNoteDto(
    val id: String,
    val sourceChatTitle: String,
    val summary: String? = null,
    val createdAt: Long,
    val pending: Boolean,
)

@Serializable
data class MemoryResponse(val notes: List<MemoryNoteDto>)

/** [decision] is "yes" or "no". */
@Serializable
data class ApprovalDecisionRequest(val decision: String)

@Serializable
data class ApprovalDecisionResponse(val status: String, val message: String)

/**
 * One phone reminder. [kind] is CHECKIN, MORNING, HEADS_UP, IDEA or WEEKLY. [target] is where a tap goes:
 * `screen:checkin-add`, `screen:today`, `exam:<id>`, `event:<id>`, `idea:<notificationId>` or `chat:<chat id>`.
 */
@Serializable
data class NotificationDto(
    val id: Long,
    val kind: String,
    val title: String,
    val body: String,
    val target: String,
    val fireAt: Long,
    val dedupeKey: String,
)

@Serializable
data class NotificationsResponse(val items: List<NotificationDto>)

@Serializable
data class NotifySettingsDto(
    val checkinTime: String = "21:00",
    val quietStart: String = "23:00",
    val quietEnd: String = "07:00",
    val checkin: Boolean = true,
    val morningList: Boolean = true,
    val headsUp: Boolean = true,
    val ideas: Boolean = true,
    val weeklyReport: Boolean = true,
)
