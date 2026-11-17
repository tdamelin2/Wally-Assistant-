package dev.tim.assistant.server.chat

import dev.tim.assistant.model.ChoiceDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Tim can undo an auto write for this long; the server decides, the countdown on screen only follows expiresAt. */
const val UNDO_WINDOW_MS = 30_000L

const val MAX_ACTIVE_CHATS = 10
const val PINNED_CHAT_ID = "weekly-report"
const val PINNED_CHAT_TITLE = "Weekly report"
const val DEFAULT_CHAT_TITLE = "New chat"

/** chat_message.request_state of a reply that a turn is still writing; NULL once it is final. */
const val STATE_RUNNING = "RUNNING"

/** chat_message.request_state of a reply whose answer the phone is still writing (PLAN 19.3); [PHONE_TURN_MS] is how long it may take. */
const val STATE_PHONE_PENDING = "PHONE_PENDING"
const val PHONE_TURN_MS = 180_000L

/** A phone turn with less than this left is treated as expired, so a late result is never turned into "I was interrupted". */
const val PHONE_MIN_LEFT_MS = 60_000L

/** The longest message the phone brain takes; longer ones go to the home server's rung (which says "too long" above 600). */
const val PHONE_MAX_CHARS = 2_000

/** The server's record of one Undo handle: which row an auto tool wrote and the stamp it had right after the write. */
@Serializable
data class StoredAction(
    val actionId: String,
    val label: String,
    val expiresAt: Long,
    val undone: Boolean = false,
    val table: String,
    val rowId: String,
    val createdUpdatedAt: Long,
    val chatId: String,
    val messageId: String,
)

/** A tool call waiting for Tim to pick one of several matches (the chip tap supplies the id). */
@Serializable
data class PendingAction(val tool: String, val args: Map<String, String>)

/** What a reply carries besides its text (stored as chat_message.card_json). [dream] marks a turn that handled a dream. */
@Serializable
data class MessageExtras(
    val actions: List<StoredAction> = emptyList(),
    /** Approval ids of the cards of this reply. */
    val cards: List<String> = emptyList(),
    val choices: List<ChoiceDto> = emptyList(),
    val dream: Boolean = false,
    /** Which rung answered: "code", "sorter", "xps" or "hp". Set only on replies of a chat turn. */
    val brain: String? = null,
    /** The tool call a "which one?" chip tap completes (the chip supplies the id). */
    val pending: PendingAction? = null,
    /** While the reply is PHONE_PENDING: when the phone's turn runs out (epoch ms). Cleared when the reply is finished. */
    val expiresAt: Long? = null,
    /** One entry per suggestion button of a question reply, in button order (a read-only question is stored as tool "ask"). */
    val options: List<PendingAction> = emptyList(),
    /** The id of Tim's message a question (or a continued turn) is about. */
    val sourceMessageId: String? = null,
    /** True on a reply that continues "Something else" from an HP suggestion, so the HP model is not asked again. */
    val fromHp: Boolean = false,
    /** The ISO Asia/Jerusalem day of the source message's raw request time: a tap saving an add is honoured only on that day. */
    val sourceDay: String? = null,
) {
    val isEmpty: Boolean get() =
        actions.isEmpty() && cards.isEmpty() && choices.isEmpty() && !dream && brain == null && pending == null && expiresAt == null &&
            options.isEmpty() && sourceMessageId == null && !fromHp && sourceDay == null
}

private val extrasJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun parseExtras(cardJson: String?): MessageExtras =
    if (cardJson.isNullOrBlank()) MessageExtras() else try {
        extrasJson.decodeFromString(MessageExtras.serializer(), cardJson)
    } catch (e: Exception) {
        MessageExtras()
    }

fun MessageExtras.encode(): String? = if (isEmpty) null else extrasJson.encodeToString(MessageExtras.serializer(), this)

/** The chat title: the first 6 words of [text], at most 60 characters. */
fun titleFrom(text: String): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6).joinToString(" ")
    val cut = if (words.length > 60) words.take(60).trimEnd() else words
    return cut.ifBlank { DEFAULT_CHAT_TITLE }
}
