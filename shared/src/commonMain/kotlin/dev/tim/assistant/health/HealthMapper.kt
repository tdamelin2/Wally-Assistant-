package dev.tim.assistant.health

import dev.tim.assistant.time.localDateOf
import kotlin.time.Instant

data class HealthDayValues(val localDate: String, val sleepMinutes: Long?, val steps: Long?, val restingHr: Long?)

data class WorkoutValues(
    val hcRecordId: String,
    val type: String,
    val startAt: Long,
    val endAt: Long,
    val distanceMeters: Long?,
)

data class MappedHealth(val days: List<HealthDayValues>, val workouts: List<WorkoutValues>)

/** Turns raw Health Connect data into the rows Wally stores. Pure: no database, no clock. */
object HealthMapper {
    private fun dateOf(ms: Long) = localDateOf(Instant.fromEpochMilliseconds(ms))

    fun map(raw: RawHealth): MappedHealth {
        val sleep = raw.sleep.filter { it.origin == GARMIN_PACKAGE }
            .groupBy { dateOf(it.endAt) }
            .mapValues { (_, list) -> list.sumOf { ((it.endAt - it.startAt) / 60_000 - it.awakeMinutes).coerceAtLeast(0L) } }
        val hr = raw.restingHr.filter { it.origin == GARMIN_PACKAGE }
            .groupBy { dateOf(it.at) }
            .mapValues { (_, list) -> list.maxWith(compareBy<RawRestingHr>({ it.at }, { it.id })).bpm }
        val dates = (sleep.keys + hr.keys + raw.dailySteps.keys).toSet().sorted()
        val days = dates.map { HealthDayValues(it, sleep[it], raw.dailySteps[it], hr[it]) }
        val workouts = raw.exercise.filter { it.origin == GARMIN_PACKAGE }
            .map { WorkoutValues(it.id, it.type, it.startAt, it.endAt, it.distanceMeters) }
        return MappedHealth(days, workouts)
    }
}
