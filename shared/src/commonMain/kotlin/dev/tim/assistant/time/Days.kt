package dev.tim.assistant.time

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

val JERUSALEM: TimeZone = TimeZone.of("Asia/Jerusalem")

/** The calendar day (YYYY-MM-DD) of [instant] in Asia/Jerusalem. */
fun localDateOf(instant: Instant): String =
    instant.toLocalDateTime(JERUSALEM).date.toString()

/** Today's calendar day (YYYY-MM-DD) in Asia/Jerusalem. */
fun todayLocal(clock: Clock = Clock.System): String = localDateOf(clock.now())
