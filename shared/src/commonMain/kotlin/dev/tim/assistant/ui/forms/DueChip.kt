package dev.tim.assistant.ui.forms

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The due-date choices of the task sheet. */
enum class DueChip(val label: String) { None("None"), Today("Today"), Tomorrow("Tomorrow"), Pick("Pick date") }

/** The due date a chip stands for: null for None, and for Pick until a date is picked. */
fun dueFromChip(chip: DueChip, today: LocalDate, picked: LocalDate?): LocalDate? = when (chip) {
    DueChip.None -> null
    DueChip.Today -> today
    DueChip.Tomorrow -> today.plus(DatePeriod(days = 1))
    DueChip.Pick -> picked
}

/** The chip an existing task's due date selects: today and tomorrow have their chip, any other date is Pick. */
fun chipForDue(due: LocalDate?, today: LocalDate): DueChip = when (due) {
    null -> DueChip.None
    today -> DueChip.Today
    today.plus(DatePeriod(days = 1)) -> DueChip.Tomorrow
    else -> DueChip.Pick
}
