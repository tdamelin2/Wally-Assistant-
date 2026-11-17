package dev.tim.assistant.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class DaysTest {
    private fun day(utc: String) = localDateOf(Instant.parse(utc))

    @Test
    fun dstStartDay_0130LocalIsStillThe27th() {
        // 2026-03-27 01:30 Jerusalem is UTC+2 (clocks jump 02:00 -> 03:00 later that night)
        assertEquals("2026-03-27", day("2026-03-26T23:30:00Z"))
    }

    @Test
    fun dstStartDay_afterTheJump() {
        // 03:00 local (UTC+3) = 00:00 UTC
        assertEquals("2026-03-27", day("2026-03-27T00:00:00Z"))
    }

    @Test
    fun dstEnd_both0130OccurrencesAreThe25th() {
        // first 01:30 (UTC+3) = 22:30 UTC on the 24th; second 01:30 (UTC+2) = 23:30 UTC on the 24th
        assertEquals("2026-10-25", day("2026-10-24T22:30:00Z"))
        assertEquals("2026-10-25", day("2026-10-24T23:30:00Z"))
    }

    @Test
    fun midnightBoundary_summerTime() {
        // UTC+3 in July: 23:59:59 local = 20:59:59 UTC, 00:00:00 local = 21:00:00 UTC
        assertEquals("2026-07-14", day("2026-07-14T20:59:59Z"))
        assertEquals("2026-07-15", day("2026-07-14T21:00:00Z"))
    }

    @Test
    fun midnightBoundary_winterTime() {
        // UTC+2 in January: 23:59:59 local = 21:59:59 UTC, 00:00:00 local = 22:00:00 UTC
        assertEquals("2026-01-09", day("2026-01-09T21:59:59Z"))
        assertEquals("2026-01-10", day("2026-01-09T22:00:00Z"))
    }

    @Test
    fun midnightBoundary_onDstEndNight() {
        // 00:00 local on 2026-10-25 is still UTC+3 = 21:00 UTC on the 24th
        assertEquals("2026-10-24", day("2026-10-24T20:59:59Z"))
        assertEquals("2026-10-25", day("2026-10-24T21:00:00Z"))
    }

    @Test
    fun todayLocal_usesTheGivenClock() {
        val clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-03-26T23:30:00Z")
        }
        assertEquals("2026-03-27", todayLocal(clock))
    }
}
