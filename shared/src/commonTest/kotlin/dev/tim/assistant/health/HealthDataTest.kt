package dev.tim.assistant.health

import dev.tim.assistant.db.Health_day
import dev.tim.assistant.db.Workout
import dev.tim.assistant.time.JERUSALEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

class HealthDataTest {
    private fun day(date: String, sleep: Long?, steps: Long?, hr: Long? = null, deleted: Long = 0) =
        Health_day("health:$date", 1, deleted, 0, "SYNCED", date, sleep, steps, hr)

    @Test
    fun thirty_labels_end_today() {
        val s = healthSeries(emptyList(), "2026-11-09")
        assertEquals(30, s.labels.size)
        assertEquals("2026-11-09", s.labels.last())
        assertEquals("2026-10-11", s.labels.first())
        assertEquals(30, s.sleepHours.size)
    }

    @Test
    fun null_and_zero_are_kept_apart_and_450_minutes_is_7_and_a_half_hours() {
        val s = healthSeries(
            listOf(day("2026-11-08", 450, 0), day("2026-11-09", null, null, 58)),
            "2026-11-09",
        )
        assertEquals(7.5, s.sleepHours[28])
        assertEquals(0L, s.steps[28])
        assertNull(s.sleepHours[29])
        assertNull(s.steps[29])
        assertEquals(58L, s.restingHr[29])
        assertNull(s.steps[0])
    }

    @Test
    fun deleted_days_are_excluded() {
        val s = healthSeries(listOf(day("2026-11-09", 300, 10, deleted = 1)), "2026-11-09")
        assertNull(s.sleepHours.last())
        assertNull(s.steps.last())
    }

    @Test
    fun recent_workouts_last_30_days_newest_first_without_deleted() {
        fun w(id: String, y: Int, m: Int, d: Int, deleted: Long = 0): Workout {
            val start = LocalDateTime(y, m, d, 9, 0).toInstant(JERUSALEM).toEpochMilliseconds()
            return Workout("workout:$id", 1, deleted, 0, "SYNCED", id, "Running", start, start + 60_000, null)
        }
        val result = recentWorkouts(
            listOf(w("old", 2026, 10, 1), w("a", 2026, 10, 11), w("b", 2026, 11, 9), w("gone", 2026, 11, 8, deleted = 1)),
            "2026-11-09",
        )
        assertEquals(listOf("b", "a"), result.map { it.hcRecordId })
    }
}
