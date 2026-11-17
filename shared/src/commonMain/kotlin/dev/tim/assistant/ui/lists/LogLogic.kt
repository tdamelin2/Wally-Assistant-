package dev.tim.assistant.ui.lists

import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod

/** How many of the five dots of a 1-5 score are filled; scores outside the range are clamped. */
fun checkinDots(score: Long): Int = score.coerceIn(1L, 5L).toInt()

private fun parseDay(iso: String): LocalDate? = try {
    LocalDate.parse(iso.trim())
} catch (e: IllegalArgumentException) {
    null
}

/** The Sunday that starts the week containing [day]. */
fun weekStart(day: LocalDate): LocalDate {
    // kotlinx DayOfWeek: Monday = 0 ... Sunday = 6, so Sunday is 0 days back and Monday 1.
    val back = (day.dayOfWeek.ordinal + 1) % 7
    return day.plus(DatePeriod(days = -back))
}

/**
 * Spending of the Sunday-start week that contains [today], summed per category (agorot), largest first
 * (ties by category name). Rows outside the week, deleted rows and rows with an unreadable date are skipped.
 */
fun weekByCategory(rows: List<Spending>, today: LocalDate): List<Pair<String, Long>> {
    val start = weekStart(today)
    val end = start.plus(DatePeriod(days = 6))
    val sums = LinkedHashMap<String, Long>()
    for (row in rows) {
        if (row.deleted != 0L) continue
        val day = parseDay(row.localDate) ?: continue
        if (day < start || day > end) continue
        sums[row.category] = (sums[row.category] ?: 0L) + row.amountAgorot
    }
    return sums.entries.map { it.key to it.value }.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
}

/** The day's estimated nutrition, and how many meals have no estimate yet. */
data class MealTotal(val kcal: Long, val protein: Long, val carbs: Long, val fat: Long, val missing: Int)

/** Sums the meals that have an estimate (kcal != null); the others are only counted in [MealTotal.missing]. */
fun mealDayTotal(meals: List<Meal>): MealTotal {
    var kcal = 0L
    var protein = 0L
    var carbs = 0L
    var fat = 0L
    var missing = 0
    for (m in meals) {
        val k = m.kcal
        if (k == null) {
            missing++
            continue
        }
        kcal += k
        protein += m.proteinG ?: 0L
        carbs += m.carbsG ?: 0L
        fat += m.fatG ?: 0L
    }
    return MealTotal(kcal, protein, carbs, fat, missing)
}

enum class MealStatus { Estimated, Estimating, Failed }

/** The server gives up silently after three tries; after this long without a lookup job a meal counts as failed. */
const val MEAL_GIVE_UP_MS = 2L * 60 * 60 * 1000

/**
 * Estimated when the meal has kcal; Estimating while its newest lookup job is QUEUED or RUNNING; Failed when that
 * job FAILED. With no lookup job (or a finished one that left no estimate) a meal not estimated within 2 hours
 * of its last change is Failed, otherwise Estimating.
 */
fun mealStatus(meal: Meal, lookup: Ai_job?, nowMs: Long): MealStatus {
    if (meal.kcal != null) return MealStatus.Estimated
    return when (lookup?.status) {
        "QUEUED", "RUNNING" -> MealStatus.Estimating
        "FAILED" -> MealStatus.Failed
        else -> if (nowMs - meal.updatedAt > MEAL_GIVE_UP_MS) MealStatus.Failed else MealStatus.Estimating
    }
}

enum class TaskGroup { Overdue, Today, ThisWeek, Later, NoDate }

/** Days after today that still count as "This week" (tomorrow through today + 6). */
const val THIS_WEEK_DAYS = 6

/** The group one open task belongs to on [today] (see [taskGroups]). */
fun taskGroupOf(task: Task, today: LocalDate): TaskGroup {
    val due = task.dueDate?.let { parseDay(it) } ?: return TaskGroup.NoDate
    return when {
        due < today -> TaskGroup.Overdue
        due == today -> TaskGroup.Today
        today.daysUntil(due) <= THIS_WEEK_DAYS -> TaskGroup.ThisWeek
        else -> TaskGroup.Later
    }
}

/**
 * Open tasks by due date: Overdue (before today), Today, ThisWeek (tomorrow to six days ahead), Later, and NoDate
 * (no due date, or one that can't be read: never Overdue). All five keys are present, in enum order; each list
 * keeps the order of [tasks].
 */
fun taskGroups(tasks: List<Task>, today: LocalDate): Map<TaskGroup, List<Task>> {
    val out = TaskGroup.entries.associateWith { mutableListOf<Task>() }
    for (t in tasks) out.getValue(taskGroupOf(t, today)).add(t)
    return out
}

/** The day of the week short name for a due date line, e.g. "Fri". */
fun shortWeekday(day: LocalDate): String = when (day.dayOfWeek) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}
