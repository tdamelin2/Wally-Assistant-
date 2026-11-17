package dev.tim.assistant.health

import dev.tim.assistant.db.Health_day
import dev.tim.assistant.db.Workout
import dev.tim.assistant.time.localDateOf
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

const val HEALTH_DAYS = 30

/** Thirty days of health values; [labels] are "YYYY-MM-DD", oldest first, today last. Null = no data. */
data class HealthSeries(
    val labels: List<String>,
    val sleepHours: List<Double?>,
    val steps: List<Long?>,
    val restingHr: List<Long?>,
)

fun healthSeries(days: List<Health_day>, today: String): HealthSeries {
    val end = LocalDate.parse(today)
    val labels = (HEALTH_DAYS - 1 downTo 0).map { end.minus(it, DateTimeUnit.DAY).toString() }
    val byDate = days.filter { it.deleted == 0L }
        .groupBy { it.localDate }
        .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }
    return HealthSeries(
        labels,
        labels.map { d -> byDate[d]?.sleepMinutes?.let { it / 60.0 } },
        labels.map { d -> byDate[d]?.steps },
        labels.map { d -> byDate[d]?.restingHr },
    )
}

/** One day of the laptop Days list; null = no data (shown as a dash, never as 0). */
data class LaptopDayRow(val date: String, val sleepMinutes: Long?, val steps: Long?, val restingHr: Long?)

private fun latestPerDate(days: List<Health_day>): Map<String, Health_day> =
    days.filter { it.deleted == 0L }.groupBy { it.localDate }.mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }

/** The last seven days (today first, then older), one row per date, null for a missing value. */
fun laptopDayRows(days: List<Health_day>, today: String): List<LaptopDayRow> {
    val end = LocalDate.parse(today)
    val byDate = latestPerDate(days)
    return (0 until 7).map { n ->
        val d = end.minus(n, DateTimeUnit.DAY).toString()
        val row = byDate[d]
        LaptopDayRow(d, row?.sleepMinutes, row?.steps, row?.restingHr)
    }
}

/** The three laptop tiles: last night's sleep, yesterday's steps and the latest resting heart rate with its date. */
data class LaptopHealthTiles(val sleepMinutes: Long?, val stepsYesterday: Long?, val restingHr: Long?, val restingHrDate: String?)

fun laptopHealthTiles(days: List<Health_day>, today: String): LaptopHealthTiles {
    val end = LocalDate.parse(today)
    val byDate = latestPerDate(days)
    val yesterday = end.minus(1, DateTimeUnit.DAY).toString()
    val hr = byDate.values.filter { it.restingHr != null && it.localDate <= today }.maxByOrNull { it.localDate }
    return LaptopHealthTiles(byDate[today]?.sleepMinutes, byDate[yesterday]?.steps, hr?.restingHr, hr?.localDate)
}

/** Update time (epoch ms) of the newest stored health row, or null when nothing has synced. */
fun healthSyncedAt(days: List<Health_day>): Long? = days.filter { it.deleted == 0L }.maxOfOrNull { it.updatedAt }

/** Workouts from the last 30 days (by Jerusalem start date), newest first. */
fun recentWorkouts(workouts: List<Workout>, today: String): List<Workout> {
    val first = LocalDate.parse(today).minus(HEALTH_DAYS - 1, DateTimeUnit.DAY).toString()
    return workouts.filter { it.deleted == 0L }
        .filter { val d = localDateOf(Instant.fromEpochMilliseconds(it.startAt)); d >= first && d <= today }
        .sortedByDescending { it.startAt }
}
