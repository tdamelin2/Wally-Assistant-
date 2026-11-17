package dev.tim.assistant.server.notify

import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Outbox_notification
import dev.tim.assistant.model.NotificationDto
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.notify.NOTIFY_DAILY_CAP
import dev.tim.assistant.notify.inQuietHours
import dev.tim.assistant.notify.minutesOfDay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val JERUSALEM_ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")

const val KIND_CHECKIN = "CHECKIN"
const val KIND_MORNING = "MORNING"
const val KIND_HEADS_UP = "HEADS_UP"
const val KIND_IDEA = "IDEA"
const val KIND_WEEKLY = "WEEKLY"

/** The server's kv keys for the reminder settings (Tim edits them through GET/PUT /v1/notify-settings). */
const val KV_CHECKIN_TIME = "notify.checkinTime"
const val KV_QUIET_START = "notify.quietStart"
const val KV_QUIET_END = "notify.quietEnd"

/**
 * `notify.kinds`: the reminder types that are switched ON, as a comma list of `checkin`, `morningList`, `headsUp`, `ideas`,
 * `weeklyReport`. No value stored = all on; an empty value = all off.
 */
const val KV_KINDS = "notify.kinds"

val NOTIFY_KIND_SWITCHES = listOf("checkin", "morningList", "headsUp", "ideas", "weeklyReport")

/** Reads the settings from the server's kv table; a missing or malformed value falls back to the default. */
fun loadNotifySettings(db: AssistantDb): NotifySettingsDto {
    val defaults = NotifySettingsDto()
    fun time(key: String, fallback: String): String {
        val v = db.kvQueries.get(key).executeAsOneOrNull()?.trim()
        return if (v != null && minutesOfDay(v) != null) v else fallback
    }
    val kindsText = db.kvQueries.get(KV_KINDS).executeAsOneOrNull()
    val on: Set<String> = if (kindsText == null) NOTIFY_KIND_SWITCHES.toSet() else kindsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return NotifySettingsDto(
        checkinTime = time(KV_CHECKIN_TIME, defaults.checkinTime),
        quietStart = time(KV_QUIET_START, defaults.quietStart),
        quietEnd = time(KV_QUIET_END, defaults.quietEnd),
        checkin = "checkin" in on,
        morningList = "morningList" in on,
        headsUp = "headsUp" in on,
        ideas = "ideas" in on,
        weeklyReport = "weeklyReport" in on,
    )
}

private fun kindSwitchOn(kind: String, s: NotifySettingsDto): Boolean = when (kind) {
    KIND_CHECKIN -> s.checkin
    KIND_MORNING -> s.morningList
    KIND_HEADS_UP -> s.headsUp
    KIND_IDEA -> s.ideas
    KIND_WEEKLY -> s.weeklyReport
    else -> false
}

/**
 * The one rule every reminder passes before the server queues it: the kind is switched on, [at] (the time it would fire, epoch
 * ms, read on the Jerusalem clock) is outside quiet hours (a window may cross midnight), and fewer than 6 reminders are already
 * queued for that day ([sentToday]).
 */
fun allowed(kind: String, at: Long, sentToday: Int, settings: NotifySettingsDto): Boolean {
    if (!kindSwitchOn(kind, settings)) return false
    if (sentToday >= NOTIFY_DAILY_CAP) return false
    val local = Instant.ofEpochMilli(at).atZone(JERUSALEM_ZONE)
    return !inQuietHours(local.hour * 60 + local.minute, settings.quietStart, settings.quietEnd)
}

/**
 * The tap target the phone opens. [ref] is the row's `chat_id` column: for HEADS_UP the item (`exam:<id>` or `event:<id>`),
 * for WEEKLY the pinned report chat's id; the other kinds need none. No chat is created for CHECKIN, MORNING or HEADS_UP.
 */
fun targetOf(kind: String, notificationId: Long, ref: String?): String = when (kind) {
    KIND_CHECKIN -> "screen:checkin-add"
    KIND_MORNING -> "screen:today"
    KIND_HEADS_UP -> ref ?: "screen:today"
    KIND_IDEA -> "idea:$notificationId"
    KIND_WEEKLY -> "chat:${ref ?: "weekly-report"}"
    else -> "screen:today"
}

fun notificationDto(row: Outbox_notification): NotificationDto =
    NotificationDto(row.id, row.kind, row.title, row.body, targetOf(row.kind, row.id, row.chat_id), row.fire_at, row.dedupe_key)

/** The Jerusalem calendar date of [ms]. */
fun jerusalemDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(JERUSALEM_ZONE).toLocalDate()

/** Epoch ms of [date] at [hhmm] ("HH:mm") on the Jerusalem clock, or null when [hhmm] is not a time. */
fun jerusalemMillis(date: LocalDate, hhmm: String): Long? {
    val minutes = minutesOfDay(hhmm) ?: return null
    return date.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(JERUSALEM_ZONE).toInstant().toEpochMilli()
}

/** [startMs, endMs) of the Jerusalem day [date]. */
fun jerusalemDayBounds(date: LocalDate): Pair<Long, Long> =
    date.atStartOfDay(JERUSALEM_ZONE).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(JERUSALEM_ZONE).toInstant().toEpochMilli()
