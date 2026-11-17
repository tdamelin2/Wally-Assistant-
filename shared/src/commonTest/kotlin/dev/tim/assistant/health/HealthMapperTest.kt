package dev.tim.assistant.health

import dev.tim.assistant.time.JERUSALEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

class HealthMapperTest {
    private val other = "com.android.healthconnect.phone.x"

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(2026, month, day, hour, minute).toInstant(JERUSALEM).toEpochMilliseconds()

    private fun raw(
        sleep: List<RawSleep> = emptyList(),
        hr: List<RawRestingHr> = emptyList(),
        exercise: List<RawExercise> = emptyList(),
        steps: Map<String, Long> = emptyMap(),
    ) = RawHealth(sleep, hr, exercise, steps)

    @Test
    fun non_garmin_records_are_ignored() {
        val mapped = HealthMapper.map(
            raw(
                sleep = listOf(RawSleep("s", at(11, 4, 23, 0), at(11, 5, 7, 0), other)),
                hr = listOf(RawRestingHr("h", at(11, 5, 8, 0), 55, other)),
                exercise = listOf(RawExercise("e", at(11, 5, 9, 0), at(11, 5, 10, 0), "Running", null, other)),
            ),
        )
        assertTrue(mapped.days.isEmpty())
        assertTrue(mapped.workouts.isEmpty())
    }

    @Test
    fun sleep_across_midnight_counts_on_the_end_date_with_awake_minutes_subtracted() {
        val mapped = HealthMapper.map(
            raw(sleep = listOf(RawSleep("s", at(11, 4, 23, 30), at(11, 5, 7, 0), GARMIN_PACKAGE, awakeMinutes = 30))),
        )
        assertEquals(listOf(HealthDayValues("2026-11-05", 420, null, null)), mapped.days)
    }

    @Test
    fun two_sleep_sessions_ending_the_same_day_add_up() {
        val mapped = HealthMapper.map(
            raw(
                sleep = listOf(
                    RawSleep("a", at(11, 4, 23, 0), at(11, 5, 5, 0), GARMIN_PACKAGE),
                    RawSleep("b", at(11, 5, 14, 0), at(11, 5, 14, 30), GARMIN_PACKAGE),
                ),
            ),
        )
        assertEquals(390L, mapped.days.single().sleepMinutes)
    }

    @Test
    fun steps_are_copied_including_a_measured_zero() {
        val mapped = HealthMapper.map(raw(steps = mapOf("2026-11-05" to 0L, "2026-11-06" to 4000L)))
        assertEquals(
            listOf(HealthDayValues("2026-11-05", null, 0, null), HealthDayValues("2026-11-06", null, 4000, null)),
            mapped.days,
        )
    }

    @Test
    fun latest_resting_hr_wins_and_a_tie_picks_the_larger_id() {
        val later = HealthMapper.map(
            raw(hr = listOf(RawRestingHr("a", at(11, 5, 6, 0), 50, GARMIN_PACKAGE), RawRestingHr("b", at(11, 5, 9, 0), 58, GARMIN_PACKAGE))),
        )
        assertEquals(58L, later.days.single().restingHr)
        val tie = HealthMapper.map(
            raw(hr = listOf(RawRestingHr("a", at(11, 5, 6, 0), 50, GARMIN_PACKAGE), RawRestingHr("b", at(11, 5, 6, 0), 52, GARMIN_PACKAGE))),
        )
        assertEquals(52L, tie.days.single().restingHr)
        val tieReversed = HealthMapper.map(
            raw(hr = listOf(RawRestingHr("b", at(11, 5, 6, 0), 52, GARMIN_PACKAGE), RawRestingHr("a", at(11, 5, 6, 0), 50, GARMIN_PACKAGE))),
        )
        assertEquals(52L, tieReversed.days.single().restingHr)
    }

    @Test
    fun a_day_with_no_garmin_data_gets_no_row() {
        val mapped = HealthMapper.map(raw(steps = mapOf("2026-11-05" to 100L)))
        assertEquals(listOf("2026-11-05"), mapped.days.map { it.localDate })
    }

    @Test
    fun workout_keeps_id_type_and_times() {
        val mapped = HealthMapper.map(
            raw(exercise = listOf(RawExercise("rec1", at(11, 5, 9, 0), at(11, 5, 9, 45), "Running", 5000, GARMIN_PACKAGE))),
        )
        assertEquals(listOf(WorkoutValues("rec1", "Running", at(11, 5, 9, 0), at(11, 5, 9, 45), 5000)), mapped.workouts)
        assertNull(HealthMapper.map(raw(exercise = listOf(RawExercise("r", 1, 2, "Other", null, GARMIN_PACKAGE)))).workouts.single().distanceMeters)
    }

    @Test
    fun sleep_ending_on_the_dst_change_night_counts_on_that_date() {
        // Israel leaves summer time on 2026-10-25 at 02:00 (clock goes back to 01:00).
        val start = at(10, 24, 23, 0)
        val end = at(10, 25, 7, 0)
        val mapped = HealthMapper.map(raw(sleep = listOf(RawSleep("s", start, end, GARMIN_PACKAGE))))
        assertEquals("2026-10-25", mapped.days.single().localDate)
        assertEquals((end - start) / 60_000, mapped.days.single().sleepMinutes)
    }
}
