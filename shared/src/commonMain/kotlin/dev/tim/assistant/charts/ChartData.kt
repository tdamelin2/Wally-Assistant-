package dev.tim.assistant.charts

import dev.tim.assistant.data.SPENDING_CATEGORIES
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.time.JERUSALEM
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

const val CHECKIN_DAYS = 30
const val MONTHS = 6
const val WEEKS = 8

/** The four check-in scores for the last [CHECKIN_DAYS] days; `labels` are "YYYY-MM-DD", oldest first, today last. */
data class CheckinSeries(
    val labels: List<String>,
    val mood: List<Int?>,
    val energy: List<Int?>,
    val sleepQuality: List<Int?>,
    val stress: List<Int?>,
)

fun checkinSeries(checkins: List<Checkin>, today: String): CheckinSeries {
    val end = LocalDate.parse(today)
    val labels = (CHECKIN_DAYS - 1 downTo 0).map { end.minus(it, DateTimeUnit.DAY).toString() }
    // one check-in per day; if a duplicate ever exists the most recently updated one wins
    val byDate = checkins.filter { it.deleted == 0L }
        .groupBy { it.localDate }
        .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }
    fun series(pick: (Checkin) -> Long) = labels.map { day -> byDate[day]?.let { pick(it).toInt() } }
    return CheckinSeries(
        labels,
        series { it.mood }, series { it.energy }, series { it.sleepQuality }, series { it.stress },
    )
}

/** Spending of the current month (the month of [today]) per category, in [SPENDING_CATEGORIES] order, in agorot. */
fun spendingByCategory(spending: List<Spending>, today: String): List<Pair<String, Long>> {
    val month = today.take(7)
    val live = spending.filter { it.deleted == 0L && it.localDate.take(7) == month }
    return SPENDING_CATEGORIES.map { c -> c to live.filter { it.category == c }.sumOf { it.amountAgorot } }
}

/** Total spending for each of the last [MONTHS] months as ("YYYY-MM", agorot), oldest first, current month last. */
fun monthlyTotals(spending: List<Spending>, today: String): List<Pair<String, Long>> {
    val year = today.substring(0, 4).toInt()
    val month = today.substring(5, 7).toInt()
    val index = year * 12 + (month - 1)
    val live = spending.filter { it.deleted == 0L }
    return (MONTHS - 1 downTo 0).map { back ->
        val i = index - back
        val key = (i / 12).toString().padStart(4, '0') + "-" + ((i % 12) + 1).toString().padStart(2, '0')
        key to live.filter { it.localDate.take(7) == key }.sumOf { it.amountAgorot }
    }
}

/** Tasks finished in each of the last [WEEKS] Sunday-start weeks as (Sunday date, count), oldest first, current week last. */
fun tasksDonePerWeek(tasks: List<Task>, today: String): List<Pair<String, Int>> {
    val thisSunday = sundayOf(LocalDate.parse(today))
    val starts = (WEEKS - 1 downTo 0).map { thisSunday.minus(it * 7, DateTimeUnit.DAY) }
    val counts = HashMap<LocalDate, Int>()
    for (t in tasks) {
        if (t.deleted != 0L || t.done == 0L) continue
        val doneAt = t.doneAt ?: continue
        val day = Instant.fromEpochMilliseconds(doneAt).toLocalDateTime(JERUSALEM).date
        val week = sundayOf(day)
        counts[week] = (counts[week] ?: 0) + 1
    }
    return starts.map { it.toString() to (counts[it] ?: 0) }
}

private fun sundayOf(date: LocalDate): LocalDate = date.minus((date.dayOfWeek.ordinal + 1) % 7, DateTimeUnit.DAY)