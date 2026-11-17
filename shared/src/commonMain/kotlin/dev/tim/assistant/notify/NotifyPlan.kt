package dev.tim.assistant.notify

import dev.tim.assistant.model.NotificationDto
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.time.JERUSALEM
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

/** What the phone does with one reminder row. */
sealed interface NotifyDecision {
    data object ShowNow : NotifyDecision
    data class ScheduleAt(val at: Long) : NotifyDecision
    data object Skip : NotifyDecision
}

const val NOTIFY_DAILY_CAP = 6
const val NOTIFY_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** Minutes after midnight of an "HH:mm" text, or null when it isn't one. */
fun minutesOfDay(hhmm: String): Int? {
    val parts = hhmm.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

/** True when [minute] (minutes after midnight) is inside quiet hours; a window that crosses midnight (23:00-07:00) is handled. */
fun inQuietHours(minute: Int, quietStart: String, quietEnd: String): Boolean {
    val start = minutesOfDay(quietStart) ?: return false
    val end = minutesOfDay(quietEnd) ?: return false
    return when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }
}

private fun kindEnabled(kind: String, s: NotifySettingsDto): Boolean = when (kind) {
    "CHECKIN" -> s.checkin
    "MORNING" -> s.morningList
    "HEADS_UP" -> s.headsUp
    "IDEA" -> s.ideas
    "WEEKLY" -> s.weeklyReport
    else -> false
}

/**
 * The phone's last check before it shows a reminder (quiet hours use the Jerusalem clock).
 * Skip: a done check-in (CHECKIN rows only), a row more than 24 h past its time, a kind Tim switched off, a display
 * time inside quiet hours (a past row is shown at `now`, a future one at its fireAt), or `shownToday` already at the cap.
 * Otherwise ShowNow when the time has come, ScheduleAt(fireAt) when it is still ahead.
 */
object NotifyPlan {
    fun plan(row: NotificationDto, now: Long, checkinDoneToday: Boolean, settings: NotifySettingsDto, shownToday: Int): NotifyDecision {
        if (!kindEnabled(row.kind, settings)) return NotifyDecision.Skip
        if (row.kind == "CHECKIN" && checkinDoneToday) return NotifyDecision.Skip
        if (now - row.fireAt > NOTIFY_MAX_AGE_MS) return NotifyDecision.Skip
        if (shownToday >= NOTIFY_DAILY_CAP) return NotifyDecision.Skip
        val displayAt = maxOf(now, row.fireAt)
        val local = Instant.fromEpochMilliseconds(displayAt).toLocalDateTime(JERUSALEM)
        if (inQuietHours(local.hour * 60 + local.minute, settings.quietStart, settings.quietEnd)) return NotifyDecision.Skip
        return if (row.fireAt <= now) NotifyDecision.ShowNow else NotifyDecision.ScheduleAt(row.fireAt)
    }
}