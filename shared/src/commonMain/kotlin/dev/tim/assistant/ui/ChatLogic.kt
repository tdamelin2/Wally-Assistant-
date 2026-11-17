package dev.tim.assistant.ui

import dev.tim.assistant.ai.QueuedMessage
import dev.tim.assistant.model.ChatSummaryDto
import dev.tim.assistant.time.JERUSALEM
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Pure rules of the chat screens (kept out of the composables so they can be tested). */

private const val MINUTE_MS = 60_000L
private const val DAY_MS = 24 * 60 * MINUTE_MS
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Now" (under 5 minutes), the time ("14:05") later the same day, "Yesterday", the weekday name within the last six days, else "Nov 6". Days are Jerusalem days. */
fun chatTimeLabel(timestamp: Long, now: Long, zone: TimeZone = JERUSALEM): String {
    if (now - timestamp < 5 * MINUTE_MS) return "Now"
    val at = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone)
    val day = at.date
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val ago = today.toEpochDays() - day.toEpochDays()
    return when {
        ago <= 0 -> "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
        ago == 1L -> "Yesterday"
        ago < 7 -> WEEKDAYS[dayIndex(day)]
        else -> "${MONTHS[day.monthNumber - 1]} ${day.dayOfMonth}"
    }
}

private fun dayIndex(day: LocalDate): Int = when (day.dayOfWeek) {
    DayOfWeek.MONDAY -> 0
    DayOfWeek.TUESDAY -> 1
    DayOfWeek.WEDNESDAY -> 2
    DayOfWeek.THURSDAY -> 3
    DayOfWeek.FRIDAY -> 4
    DayOfWeek.SATURDAY -> 5
    else -> 6
}

/** The "N of 10" under the chats heading; the pinned report is not counted. */
/** How long a reply may take before the typing row explains that the home server is thinking. */
const val SLOW_REPLY_AFTER_MS = 10_000L

/** The hint under the typing dots when the slow HP model is answering. */
const val SLOW_REPLY_HINT = "Thinking on the home server. This can take about a minute."

/** The hint to show after [waitedMs] of waiting for a reply, or null while it is still quick. */
fun slowReplyHint(waitedMs: Long, hpBrain: Boolean): String? =
    if (hpBrain && waitedMs > SLOW_REPLY_AFTER_MS) SLOW_REPLY_HINT else null

/** What sits under the messages while a reply is coming. */
enum class ChatTail { None, Dots, Streaming }

/**
 * The last row of the chat: the words so far once the phone has written some, typing dots while it has started but written
 * nothing (or while the HP is answering), otherwise nothing. [streamingText] is already limited to the open chat.
 */
fun chatTail(typing: Boolean, streamingText: String?): ChatTail = when {
    streamingText == null -> if (typing) ChatTail.Dots else ChatTail.None
    streamingText.isEmpty() -> ChatTail.Dots
    else -> ChatTail.Streaming
}

/** The slow-mode hint belongs to the HP's typing row only: never while the phone writes. */
fun slowHintAllowed(slowBrain: Boolean, streamingText: String?): Boolean = slowBrain && streamingText == null

/** True when the list shows its last row ([lastVisibleIndex] of [rowCount] rows): only then a growing bubble is followed. */
fun isAtListBottom(lastVisibleIndex: Int?, rowCount: Int): Boolean = rowCount <= 0 || (lastVisibleIndex != null && lastVisibleIndex >= rowCount - 1)

/** Whether the list should scroll along with the growing streaming bubble: never for a user who scrolled up. */
fun followStreamingBubble(tail: ChatTail, lastVisibleIndex: Int?, rowCount: Int): Boolean =
    tail == ChatTail.Streaming && isAtListBottom(lastVisibleIndex, rowCount)

fun chatCountText(active: Int, limit: Int): String = "$active of $limit"

/** Whole seconds left to Undo (rounded up), 0 once expired. */
fun undoSecondsLeft(expiresAt: Long, now: Long): Int = if (expiresAt <= now) 0 else ((expiresAt - now + 999) / 1000).toInt()

/** The countdown next to the Undo button ("24 s", "2 min"), or null when the time is up. */
fun undoCountdownText(expiresAt: Long, now: Long): String? {
    val s = undoSecondsLeft(expiresAt, now)
    return when {
        s <= 0 -> null
        s < 120 -> "$s s"
        else -> "${(s + 59) / 60} min"
    }
}

/** What the composer does with a key. */
enum class ComposerKey { Send, Newline, AskModel, AskCodex, Ignore }

/**
 * The laptop composer: Enter sends, Shift+Enter adds a line; any other key is left alone. (Ctrl+Enter also sends.) The
 * phone keeps Enter as a new line. Alt+Enter asks a model directly, Alt+Shift+Enter asks Codex.
 */
fun composerKeyAction(isEnter: Boolean, shift: Boolean, alt: Boolean = false): ComposerKey = when {
    !isEnter -> ComposerKey.Ignore
    alt && shift -> ComposerKey.AskCodex
    alt -> ComposerKey.AskModel
    shift -> ComposerKey.Newline
    else -> ComposerKey.Send
}

/** Tim's messages that wait for the server, shown after the server's messages of the same chat, oldest first. */
fun queuedForDisplay(queued: List<QueuedMessage>, serverMessageIds: Set<String>): List<QueuedMessage> =
    queued.filter { it.messageId !in serverMessageIds }.sortedBy { it.createdAt }

/** The preview line of a chat row; "No messages yet" for an empty chat. */
fun chatPreview(chat: ChatSummaryDto): String = chat.lastMessage?.takeIf { it.isNotBlank() }?.replace('\n', ' ') ?: "No messages yet"

/** True when [chat] may be deleted by swipe or the row menu (the pinned report may not). */
fun chatCanDelete(chat: ChatSummaryDto): Boolean = !chat.pinned
