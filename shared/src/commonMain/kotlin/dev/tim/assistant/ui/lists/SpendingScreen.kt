package dev.tim.assistant.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Spending
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.WallyColors
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberQuery

/** Spending: this week split by category, then today's rows. */
@Composable
fun SpendingScreen(services: AppServices, onOpenForm: (FormTarget) -> Unit) {
    val historyOpen = rememberHistoryOpen()
    if (historyOpen.value) { HistoryScreen(services, dev.tim.assistant.ui.NavTab.Spending) { historyOpen.value = false }; return }
    val today = jerusalemDay(rememberMinuteClock())
    val rows = rememberQuery(services) { services.repo.spending() }.value ?: emptyList()
    val hidden by services.undo.hidden.collectAsState()
    val visible = rows.filter { !hidden.hides(UndoTables.SPENDING, it.id) }
    val week = weekByCategory(visible, today)
    val todayRows = visible.filter { it.localDate == today.toString() }

    RefreshBox(services) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "week") { WeekCard(week, Modifier.animateItem()) }
            item(key = "today-head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).animateItem(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(SPENT_TODAY, style = MaterialTheme.typography.titleMedium)
                    Text(shekel(todayRows.sumOf { it.amountAgorot }), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (todayRows.isEmpty()) item(key = "empty") { Text(SPENDING_EMPTY, modifier = Modifier.animateItem()) }
            items(todayRows, key = { it.id }) { row ->
                LogRow(
                    onDone = null,
                    onEdit = { onOpenForm(FormTarget.Spending(row)) },
                    onDelete = { services.undo.delete(UndoTables.SPENDING, row.id) },
                    modifier = Modifier.animateItem(),
                ) {
                    SpendingRow(row) { onOpenForm(FormTarget.Spending(row)) }
                }
            }
            item(key = "see-all") { SeeAllButton(SEE_ALL_SPENDING) { historyOpen.value = true } }
        }
    }
}

@Composable
private fun WeekCard(week: List<Pair<String, Long>>, modifier: Modifier) {
    val series = WallyColors.chartSeries
    LogCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(THIS_WEEK, style = MaterialTheme.typography.titleMedium)
            Text(shekel(week.sumOf { it.second }), style = MaterialTheme.typography.titleMedium)
        }
        if (week.isEmpty()) {
            Text(WEEK_EMPTY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                week.forEachIndexed { i, (_, amount) ->
                    Box(Modifier.weight(amount.coerceAtLeast(1L).toFloat()).fillMaxSize().background(series[minOf(i, series.lastIndex)]))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEachIndexed { i, (category, amount) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(series[minOf(i, series.lastIndex)], CircleShape))
                        Text(category, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(shekel(amount), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpendingRow(row: Spending, onClick: () -> Unit) {
    LogCard(Modifier.clickable(onClick = onClick).heightIn(min = 60.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(row.category.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(row.category, style = MaterialTheme.typography.bodyLarge)
                row.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RejectedNote(row.syncState)
            }
            Text(shekel(row.amountAgorot), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private const val SEE_ALL_SPENDING = "See all spending"
private const val THIS_WEEK = "This week"
private const val SPENT_TODAY = "Spent today"
private const val SPENDING_EMPTY = "Nothing spent today"
private const val WEEK_EMPTY = "Nothing spent this week"

/** Texts of the Spending screen, for the label-uniqueness check. */
internal val spendingLabels: List<String> = listOf(THIS_WEEK, SPENT_TODAY, SPENDING_EMPTY, WEEK_EMPTY, SEE_ALL_SPENDING)
