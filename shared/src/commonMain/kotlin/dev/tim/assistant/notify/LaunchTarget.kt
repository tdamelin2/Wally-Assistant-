package dev.tim.assistant.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where tapping a reminder takes Tim (the `target` text of a notification row, see the Phase 17 contract). */
sealed interface LaunchTarget {
    /** `screen:checkin-add`: the check-in form. */
    data object CheckinForm : LaunchTarget

    /** `screen:today`. */
    data object Today : LaunchTarget

    /** `exam:<id>`: Study, All exams. */
    data class Exam(val id: String) : LaunchTarget

    /** `event:<id>`: Today, where the calendar row is. */
    data class Event(val id: String) : LaunchTarget

    /** `idea:<notificationId>`: a new chat that starts with the idea text; created only on this tap. */
    data class Idea(val notificationId: Long) : LaunchTarget

    /** `chat:<id>`: an existing chat (the pinned weekly report). */
    data class Chat(val id: String) : LaunchTarget
}

/** The destination of a `target` text, or null when it is empty or unknown (the app then just opens). */
fun parseLaunchTarget(text: String?): LaunchTarget? {
    val t = text?.trim().orEmpty()
    val colon = t.indexOf(':')
    if (colon <= 0) return null
    val kind = t.substring(0, colon)
    val rest = t.substring(colon + 1).trim()
    if (rest.isEmpty()) return null
    return when (kind) {
        "screen" -> when (rest) {
            "checkin-add" -> LaunchTarget.CheckinForm
            "today" -> LaunchTarget.Today
            else -> null
        }
        "exam" -> LaunchTarget.Exam(rest)
        "event" -> LaunchTarget.Event(rest)
        "idea" -> rest.toLongOrNull()?.let { LaunchTarget.Idea(it) }
        "chat" -> LaunchTarget.Chat(rest)
        else -> null
    }
}

/** A tap waiting for the app to act on it. [body] is the reminder text (an idea chat opens with it). */
data class LaunchRequest(val target: LaunchTarget, val body: String?)

/** Hand-over from the Activity (a notification tap) to the app screen; the app consumes each request once. */
class LaunchRequests {
    private val _pending = MutableStateFlow<LaunchRequest?>(null)
    val pending: StateFlow<LaunchRequest?> = _pending

    /** Study opens its All-exams view once when this is true, then clears it. */
    val examsRequested = MutableStateFlow(false)

    /** Queues the tap; false when [targetText] is not a known destination. */
    fun post(targetText: String?, body: String?): Boolean {
        val target = parseLaunchTarget(targetText) ?: return false
        _pending.value = LaunchRequest(target, body)
        return true
    }

    fun consume(request: LaunchRequest) {
        _pending.compareAndSet(request, null)
    }
}
