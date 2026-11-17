package dev.tim.assistant.ui.screentime

import dev.tim.assistant.db.App_rule
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.usage.DEVICE_LAPTOP
import dev.tim.assistant.usage.DEVICE_PHONE
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.MATCH_EXE
import dev.tim.assistant.usage.MATCH_PACKAGE
import dev.tim.assistant.usage.MATCH_TITLE
import dev.tim.assistant.usage.last7Dates
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

enum class ScreentimeRange(val label: String) { Today("This day"), Week("7 days") }

/** Every heading and screen-level button of the Screentime screen, for the label-uniqueness check. */
internal val screentimeLabels: List<String> = listOf(
    "Grant usage access", "This day", "7 days", "All", "Phone", "Laptop", "Minutes per label", "Top apps",
    "Label apps", "Listening",
)

/** The dates a range covers; the first is today. */
fun datesFor(range: ScreentimeRange, today: String): List<String> =
    if (range == ScreentimeRange.Today) listOf(today) else last7Dates(today)

/** "45 min" or "2 h 5 min". */
fun minutesText(minutes: Long): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** Minutes for an app's time, never "0 min" for a session of seconds. */
fun msText(ms: Long): String = if (ms in 1..59_999) "<1 min" else minutesText(ms / 60_000L)

fun labelText(label: Label): String = when (label) {
    Label.PRODUCTIVE -> "Productive"
    Label.UNPRODUCTIVE -> "Unproductive"
    Label.NEUTRAL -> "Neutral"
    Label.UNSORTED -> "Not labelled"
}

fun deviceText(device: String): String = if (device == DEVICE_LAPTOP) "Laptop" else "Phone"

/** The device filter chip text; null is All. */
fun deviceOptionText(device: String?): String = if (device == null) "All" else deviceText(device)

/** Phone first on Android (the phone has a usage-access capability), laptop on the desktop. */
fun defaultDevice(hasPhoneUsage: Boolean): String = if (hasPhoneUsage) DEVICE_PHONE else DEVICE_LAPTOP

/** "EXE code.exe" style description of a rule. */
fun ruleText(rule: App_rule): String {
    val kind = when (rule.matchType) {
        MATCH_EXE -> "Program"
        MATCH_PACKAGE -> "App"
        MATCH_TITLE -> "Title contains"
        else -> rule.matchType
    }
    return "$kind ${rule.pattern}"
}

/** "2026-11-05 14:30" in Jerusalem time. */
fun playedAtText(ms: Long): String =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(JERUSALEM).toString().replace('T', ' ').take(16)

/** The Spotify line in Settings and the Listening section. */
fun spotifyText(ok: Boolean?): String = when (ok) {
    true -> "Connected"
    false -> "Reconnect Spotify on the server"
    null -> "Not connected"
}
