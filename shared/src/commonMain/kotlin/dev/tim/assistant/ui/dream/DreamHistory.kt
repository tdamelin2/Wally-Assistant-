package dev.tim.assistant.ui.dream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.SystemBack
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.lists.Dots
import dev.tim.assistant.ui.lists.HistoryHeader
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.lists.checkinDots
import dev.tim.assistant.ui.lists.jerusalemDay
import dev.tim.assistant.ui.lists.monthRange
import dev.tim.assistant.ui.lists.nothingIn
import dev.tim.assistant.ui.lists.rememberHistoryMonth
import dev.tim.assistant.ui.lists.rememberMinuteClock
import dev.tim.assistant.ui.lists.weekStart
import dev.tim.assistant.ui.rememberQuery
import kotlinx.datetime.LocalDate

/** Every dream of a chosen month, grouped by week with Dreams and Lucid counts; tap opens the dream. */
@Composable
fun DreamHistory(services: AppServices, onBack: () -> Unit, onOpen: (String) -> Unit) {
    SystemBack(onBack = onBack)
    val today = jerusalemDay(rememberMinuteClock())
    val (month, setMonth) = rememberHistoryMonth(today)
    val (from, to) = monthRange(month)
    val rows = rememberQuery(services, month) { services.repo.dreamsIn(from, to) }.value
    val hidden by services.undo.hidden.collectAsState()
    val dreams = (rows ?: emptyList()).filter { !hidden.hides(UndoTables.DREAM, it.id) }
    val weeks = dreams.groupBy { weekStart(LocalDate.parse(it.localDate)) }.toList().sortedByDescending { it.first }

    Column {
        HistoryHeader("Dreams history", today, month, setMonth, onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rows != null && dreams.isEmpty()) item(key = "empty") { Text(nothingIn(month), Modifier.padding(4.dp)) }
            for ((start, inWeek) in weeks) {
                item(key = "week-$start") {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Week of ${formatIsoDate(start.toString())}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Dreams ${inWeek.size} · Lucid ${inWeek.count { it.lucid != 0L }}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(inWeek.size, key = { inWeek[it].id }) { i ->
                    val dream = inWeek[i]
                    LogCard(Modifier.clickable { onOpen(dream.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(formatIsoDate(dream.localDate), style = MaterialTheme.typography.labelLarge)
                            Dots(checkinDots(dream.mood))
                        }
                        Text(dream.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
