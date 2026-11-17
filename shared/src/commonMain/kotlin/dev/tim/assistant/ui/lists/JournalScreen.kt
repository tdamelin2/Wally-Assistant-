package dev.tim.assistant.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Journal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberQuery
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus

/** Journal: today's and yesterday's entries as cards (time and the first three lines). */
@Composable
fun JournalScreen(services: AppServices, onOpenForm: (FormTarget) -> Unit) {
    val historyOpen = rememberHistoryOpen()
    if (historyOpen.value) { HistoryScreen(services, dev.tim.assistant.ui.NavTab.Journal) { historyOpen.value = false }; return }
    val today = jerusalemDay(rememberMinuteClock())
    val yesterday = today.minus(DatePeriod(days = 1))
    val rows = rememberQuery(services) { services.repo.journal() }.value ?: emptyList()
    val hidden by services.undo.hidden.collectAsState()
    val visible = rows.filter { !hidden.hides(UndoTables.JOURNAL, it.id) }
    val todayRows = visible.filter { jerusalemDay(it.createdAt) == today }
    val yesterdayRows = visible.filter { jerusalemDay(it.createdAt) == yesterday }

    RefreshBox(services) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (todayRows.isEmpty() && yesterdayRows.isEmpty()) item(key = "empty") { Text(JOURNAL_EMPTY, modifier = Modifier.animateItem()) }
            if (todayRows.isNotEmpty()) {
                item(key = "head-today") { DayHeading("Today", today.toString(), Modifier.animateItem()) }
                items(todayRows, key = { it.id }) { row -> EntryRow(services, row, onOpenForm, Modifier.animateItem()) }
            }
            if (yesterdayRows.isNotEmpty()) {
                item(key = "head-yesterday") { DayHeading("Yesterday", yesterday.toString(), Modifier.animateItem()) }
                items(yesterdayRows, key = { it.id }) { row -> EntryRow(services, row, onOpenForm, Modifier.animateItem()) }
            }
            item(key = "see-all") { SeeAllButton(SEE_ALL_JOURNAL) { historyOpen.value = true } }
        }
    }
}

/** "Today, Nov 11": the day name and its date in one heading, so no bare "Today" repeats the hub label. */
@Composable
private fun DayHeading(name: String, day: String, modifier: Modifier) {
    Text("$name, ${formatIsoDate(day)}", modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun EntryRow(services: AppServices, row: Journal, onOpenForm: (FormTarget) -> Unit, modifier: Modifier) {
    val wide = LocalLayoutMode.current == LayoutMode.Wide
    LogRow(onDone = null, onEdit = { onOpenForm(FormTarget.Journal(row)) }, onDelete = { services.undo.delete(UndoTables.JOURNAL, row.id) }, modifier = modifier) {
        LogCard(Modifier.clickable { onOpenForm(FormTarget.Journal(row)) }) {
            Text(jerusalemTime(row.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(row.text, maxLines = if (wide) 2 else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            RejectedNote(row.syncState)
        }
    }
}

private const val SEE_ALL_JOURNAL = "See all entries"
private const val JOURNAL_EMPTY = "No entries today or yesterday"

/** Texts of the Journal screen, for the label-uniqueness check. */
internal val journalLabels: List<String> = listOf(JOURNAL_EMPTY, SEE_ALL_JOURNAL)
