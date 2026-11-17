package dev.tim.assistant.google

import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.time.JERUSALEM
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

private val HTML_ENTITIES = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&amp;" to "&")

/** Decodes the few HTML entities Gmail puts in snippets; &amp; last so "&amp;lt;" stays "&lt;". */
fun decodeSnippet(text: String): String =
    HTML_ENTITIES.fold(text) { acc, (from, to) -> acc.replace(from, to) }

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Sun 8 Nov" for a YYYY-MM-DD date. */
fun dayHeader(localDate: String): String {
    val d = LocalDate.parse(localDate)
    return "${WEEKDAYS[d.dayOfWeek.ordinal]} ${d.day} ${MONTHS[d.month.ordinal]}"
}

/** "HH:MM" of [ms] in Asia/Jerusalem. */
fun hhmm(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(JERUSALEM)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** "All day" or "HH:MM–HH:MM". */
fun eventTimeLabel(event: Cal_event): String =
    if (event.allDay != 0L) "All day" else "${hhmm(event.startAt)}–${hhmm(event.endAt)}"
