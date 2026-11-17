package dev.tim.assistant.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberQuery
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus

/** Check-ins: today's and yesterday's cards with four 5-dot meters each. */
@Composable
fun CheckinsScreen(services: AppServices, onOpenForm: (FormTarget) -> Unit) {
    val historyOpen = rememberHistoryOpen()
    if (historyOpen.value) { HistoryScreen(services, dev.tim.assistant.ui.NavTab.Checkins) { historyOpen.value = false }; return }
    val today = jerusalemDay(rememberMinuteClock())
    val yesterday = today.minus(DatePeriod(days = 1))
    val rows = rememberQuery(services) { services.repo.checkins() }.value ?: emptyList()
    val hidden by services.undo.hidden.collectAsState()
    val visible = rows.filter { !hidden.hides(UndoTables.CHECKIN, it.id) }
    val cards = listOf("Today" to today, "Yesterday" to yesterday).mapNotNull { (name, day) ->
        visible.firstOrNull { it.localDate == day.toString() }?.let { Triple(name, day.toString(), it) }
    }

    RefreshBox(services) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cards.isEmpty()) item(key = "empty") { Text(CHECKINS_EMPTY, modifier = Modifier.animateItem()) }
            items(cards.size, key = { cards[it].third.id }) { i ->
                val (name, day, row) = cards[i]
                LogRow(
                    onDone = null,
                    onEdit = { onOpenForm(FormTarget.Checkin(row)) },
                    onDelete = { services.undo.delete(UndoTables.CHECKIN, row.id) },
                    modifier = Modifier.animateItem(),
                ) {
                    CheckinCard(name, day, row) { onOpenForm(FormTarget.Checkin(row)) }
                }
            }
            item(key = "see-all") { SeeAllButton(SEE_ALL_CHECKINS) { historyOpen.value = true } }
        }
    }
}

@Composable
private fun CheckinCard(name: String, day: String, row: Checkin, onClick: () -> Unit) {
    LogCard(Modifier.clickable(onClick = onClick)) {
        // "Today, Nov 11": the day name and its date in one heading, so no bare "Today" repeats the hub label.
        Text("$name, ${formatIsoDate(day)}", style = MaterialTheme.typography.titleMedium)
        val meters = listOf("Mood" to row.mood, "Energy" to row.energy, "Sleep" to row.sleepQuality, "Stress" to row.stress)
        for (pair in meters.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((label, score) in pair) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Dots(checkinDots(score))
                    }
                }
            }
        }
        row.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        RejectedNote(row.syncState)
    }
}

private const val SEE_ALL_CHECKINS = "See all check-ins"
internal const val CHECKINS_EMPTY = "No check-in today or yesterday"

/** Texts of the Check-ins screen, for the label-uniqueness check. */
internal val checkinsLabels: List<String> = listOf(CHECKINS_EMPTY, SEE_ALL_CHECKINS)
