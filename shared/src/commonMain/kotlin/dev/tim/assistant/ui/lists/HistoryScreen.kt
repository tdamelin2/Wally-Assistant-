package dev.tim.assistant.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.SystemBack
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.WallyColors
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberQuery
import kotlinx.datetime.LocalDate

/** What a history month holds for one kind of log. */
private sealed interface HistoryData {
    data class Checkins(val rows: List<Checkin>) : HistoryData
    data class Spendings(val rows: List<Spending>) : HistoryData
    data class Journals(val rows: List<Journal>) : HistoryData
    data class Meals(val rows: List<Meal>) : HistoryData
    data class Tasks(val rows: List<Task>) : HistoryData
}

/** Whether the screen shows its history instead of its main list; survives rotation. */
@Composable
fun rememberHistoryOpen(): androidx.compose.runtime.MutableState<Boolean> = rememberSaveable { mutableStateOf(false) }

/** The text button that opens a history ('See all ...'). */
@Composable
fun SeeAllButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

/** The saved month of a history screen: two Ints, starting at the current month. */
@Composable
fun rememberHistoryMonth(today: LocalDate): Pair<HistoryMonth, (HistoryMonth) -> Unit> {
    var year by rememberSaveable { mutableStateOf(today.year) }
    var month by rememberSaveable { mutableStateOf(today.month.ordinal + 1) }
    return HistoryMonth(year, month) to { m -> year = m.year; month = m.month }
}

/** Back arrow, the month dropdown (last 12 months) and the screen's own heading. */
@Composable
fun HistoryHeader(title: String, today: LocalDate, month: HistoryMonth, onMonth: (HistoryMonth) -> Unit, onBack: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { BackIcon() }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(monthLabel(month)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (m in previousMonths(today, 12)) {
                    DropdownMenuItem(text = { Text(monthLabel(m)) }, onClick = { open = false; onMonth(m) })
                }
            }
        }
    }
}

/** The history of one Log screen (never dreams): a month picker and that month's rows. */
@Composable
fun HistoryScreen(services: AppServices, kind: NavTab, onBack: () -> Unit) {
    SystemBack(onBack = onBack)
    val today = jerusalemDay(rememberMinuteClock())
    val (month, setMonth) = rememberHistoryMonth(today)
    val (from, to) = monthRange(month)
    val data = rememberQuery(services, Triple(kind, month.year, month.month)) {
        when (kind) {
            NavTab.Checkins -> HistoryData.Checkins(services.repo.checkinsIn(from, to))
            NavTab.Spending -> HistoryData.Spendings(services.repo.spendingIn(from, to))
            NavTab.Journal -> HistoryData.Journals(services.repo.journalIn(from, to))
            NavTab.Meals -> HistoryData.Meals(services.repo.mealsIn(from, to))
            else -> HistoryData.Tasks(services.repo.completedTasksIn(from, to))
        }
    }.value
    val hidden by services.undo.hidden.collectAsState()

    Column {
        HistoryHeader("${kind.label} history", today, month, setMonth, onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val empty = nothingIn(month)
            when (data) {
                null -> Unit
                is HistoryData.Checkins -> {
                    val rows = data.rows.filter { !hidden.hides(UndoTables.CHECKIN, it.id) }
                    if (rows.isEmpty()) emptyItem(empty)
                    items(rows.size, key = { rows[it].id }) { i -> CheckinHistoryRow(rows[i]) }
                }
                is HistoryData.Spendings -> {
                    val rows = data.rows.filter { !hidden.hides(UndoTables.SPENDING, it.id) }
                    if (rows.isEmpty()) emptyItem(empty) else spendingBody(rows)
                }
                is HistoryData.Journals -> {
                    val rows = data.rows.filter { !hidden.hides(UndoTables.JOURNAL, it.id) }
                    if (rows.isEmpty()) emptyItem(empty) else journalBody(rows)
                }
                is HistoryData.Meals -> {
                    val rows = data.rows.filter { !hidden.hides(UndoTables.MEAL, it.id) }
                    if (rows.isEmpty()) emptyItem(empty) else mealBody(rows)
                }
                is HistoryData.Tasks -> {
                    val rows = data.rows.filter { !hidden.hides(UndoTables.TASK, it.id) }
                    if (rows.isEmpty()) emptyItem(empty) else taskBody(rows, month)
                }
            }
        }
    }
}

/** "Nothing in November". */
fun nothingIn(month: HistoryMonth): String = "Nothing in ${monthName(month)}"

private fun LazyListScope.emptyItem(text: String) {
    item(key = "empty") { Text(text, Modifier.padding(4.dp)) }
}

private fun LazyListScope.dayHeading(day: String, trailing: String? = null) {
    item(key = "day-$day") {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatIsoDate(day), style = MaterialTheme.typography.titleSmall)
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CheckinHistoryRow(row: Checkin) {
    LogCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatIsoDate(row.localDate), style = MaterialTheme.typography.titleSmall)
        }
        val meters = listOf("Mood" to row.mood, "Energy" to row.energy, "Sleep" to row.sleepQuality, "Stress" to row.stress)
        for ((label, score) in meters) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Dots(checkinDots(score))
            }
        }
        row.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun LazyListScope.spendingBody(rows: List<Spending>) {
    item(key = "month-total") {
        LogCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Spent in the month", style = MaterialTheme.typography.labelLarge)
                Text(shekel(rows.sumOf { it.amountAgorot }), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    for ((day, dayRows) in rows.groupBy { it.localDate }.toList().sortedByDescending { it.first }) {
        dayHeading(day, shekel(dayRows.sumOf { it.amountAgorot }))
        items(dayRows.size, key = { dayRows[it].id }) { i ->
            val row = dayRows[i]
            LogCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.category, style = MaterialTheme.typography.bodyLarge)
                        row.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(shekel(row.amountAgorot), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun LazyListScope.journalBody(rows: List<Journal>) {
    for ((day, dayRows) in rows.groupBy { jerusalemDay(it.createdAt).toString() }.toList().sortedByDescending { it.first }) {
        dayHeading(day)
        items(dayRows.size, key = { dayRows[it].id }) { i ->
            val row = dayRows[i]
            LogCard {
                Text(jerusalemTime(row.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(row.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun LazyListScope.mealBody(rows: List<Meal>) {
    for ((day, dayRows) in rows.groupBy { it.localDate }.toList().sortedByDescending { it.first }) {
        dayHeading(day, totalLine(mealDayTotal(dayRows)))
        items(dayRows.size, key = { dayRows[it].id }) { i ->
            val row = dayRows[i]
            LogCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(jerusalemTime(row.eatenAt), style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.weight(1f)) {
                        Text(row.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            row.kcal?.let { "≈ $it kcal" } ?: "No estimate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.taskBody(rows: List<Task>, month: HistoryMonth) {
    item(key = "done-count") {
        LogCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Done in ${monthName(month)}", style = MaterialTheme.typography.labelLarge)
                Text("${rows.size}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    for ((day, dayRows) in rows.groupBy { jerusalemDay(it.doneAt ?: it.updatedAt).toString() }.toList().sortedByDescending { it.first }) {
        dayHeading(day)
        items(dayRows.size, key = { dayRows[it].id }) { i ->
            val task = dayRows[i]
            val doneOn = LocalDate.parse(day)
            val due = task.dueDate?.let { try { LocalDate.parse(it.trim()) } catch (e: IllegalArgumentException) { null } }
            val tag = earlyOrLate(due, doneOn)
            LogCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (tag != null) {
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (tag == "late") WallyColors.overdue else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
