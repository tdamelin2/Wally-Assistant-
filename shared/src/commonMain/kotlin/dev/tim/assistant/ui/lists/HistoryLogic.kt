package dev.tim.assistant.ui.lists

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** A calendar month of a history screen (saved as two Ints, so it survives rotation). */
data class HistoryMonth(val year: Int, val month: Int)

/** First and last day of [m], both inclusive (February of a leap year ends on the 29th). */
fun monthRange(m: HistoryMonth): Pair<LocalDate, LocalDate> {
    val first = LocalDate(m.year, m.month, 1)
    val last = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    return first to last
}

/** The month of [today] and the ones before it, newest first, [count] in all. */
fun previousMonths(today: LocalDate, count: Int): List<HistoryMonth> {
    val first = LocalDate(today.year, today.month.ordinal + 1, 1)
    return (0 until count).map { i ->
        val d = first.minus(DatePeriod(months = i))
        HistoryMonth(d.year, d.month.ordinal + 1)
    }
}

/** "early" when a task was finished before its due day, "late" after it, null on the day or when it had no date. */
fun earlyOrLate(due: LocalDate?, doneOn: LocalDate): String? = when {
    due == null -> null
    doneOn < due -> "early"
    doneOn > due -> "late"
    else -> null
}

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** "November 2026". */
fun monthLabel(m: HistoryMonth): String = "${MONTH_NAMES[m.month - 1]} ${m.year}"

/** Just the month name, for "Nothing in November" and "Done in November". */
fun monthName(m: HistoryMonth): String = MONTH_NAMES[m.month - 1]
