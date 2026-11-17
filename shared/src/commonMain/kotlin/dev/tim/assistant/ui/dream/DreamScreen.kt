package dev.tim.assistant.ui.dream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.lists.weekStart
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Dream
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.SwipeRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.lists.Dots
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.lists.RejectedNote
import dev.tim.assistant.ui.lists.SeeAllButton
import dev.tim.assistant.ui.lists.rememberHistoryOpen
import dev.tim.assistant.ui.lists.checkinDots
import dev.tim.assistant.ui.lists.jerusalemDay
import dev.tim.assistant.ui.lists.rememberMinuteClock
import dev.tim.assistant.ui.rememberQuery

/** How many of the newest dreams the main screen lists; the rest are behind See all dreams. */
private const val RECENT_DREAMS = 10

/** The dream journal: recent dream cards; tap opens one, swipe left deletes. */
@Composable
fun DreamScreen(services: AppServices, onOpenForm: (FormTarget) -> Unit) {
    val historyOpen = rememberHistoryOpen()
    if (historyOpen.value) {
        DreamHistory(services, onBack = { historyOpen.value = false }) { id -> onOpenForm(FormTarget.DreamRead(id)) }
        return
    }
    val today = jerusalemDay(rememberMinuteClock()).toString()
    val all = rememberQuery(services) { services.repo.dreams() }.value ?: emptyList()
    val hidden by services.undo.hidden.collectAsState()
    if (LocalLayoutMode.current == LayoutMode.Wide) {
        LaptopDreamList(services, all.filter { !hidden.hides(UndoTables.DREAM, it.id) }, today, onOpenForm) { historyOpen.value = true }
        return
    }
    val dreams = all.filter { !hidden.hides(UndoTables.DREAM, it.id) }.take(RECENT_DREAMS)

    RefreshBox(services) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "head") { Text(RECENT, Modifier.padding(horizontal = 4.dp).animateItem(), style = MaterialTheme.typography.titleMedium) }
            if (dreams.isEmpty()) item(key = "empty") { Text(DREAMS_EMPTY, modifier = Modifier.animateItem()) }
            items(dreams, key = { it.id }) { dream ->
                SwipeRow(onComplete = null, onDelete = { services.undo.delete(UndoTables.DREAM, dream.id) }, modifier = Modifier.animateItem()) {
                    DreamCard(dream, today) { onOpenForm(FormTarget.DreamRead(dream.id)) }
                }
            }
            item(key = "see-all") { SeeAllButton(SEE_ALL_DREAMS) { historyOpen.value = true } }
        }
    }
}

@Composable
private fun DreamCard(dream: Dream, today: String, laptop: Boolean = false, onInterpret: () -> Unit = {}, onClick: () -> Unit) {
    val date = if (dream.localDate == today) "$LAST_NIGHT · ${formatIsoDate(dream.localDate)}" else formatIsoDate(dream.localDate)
    val tags = dream.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    LogCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(date, style = MaterialTheme.typography.labelLarge)
            Dots(checkinDots(dream.mood))
        }
        Text(dream.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        val interpreted = !dream.analysis.isNullOrBlank()
        if (laptop) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dream.lucid != 0L) Text(LUCID, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                for (tag in tags) Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (interpreted) Text(INTERPRETED_MARK, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                else OutlinedButton(onClick = onInterpret, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                    Text(INTERPRET, style = MaterialTheme.typography.labelMedium)
                }
            }
        } else if (dream.lucid != 0L || tags.isNotEmpty() || interpreted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dream.lucid != 0L) Text(LUCID, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                for (tag in tags) Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (interpreted) Text(INTERPRETED, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        RejectedNote(dream.syncState)
    }
}

/** Laptop: this week's and last week's dreams in two groups; the pane on the right shows the opened dream. */
@Composable
private fun LaptopDreamList(services: AppServices, dreams: List<Dream>, today: String, onOpenForm: (FormTarget) -> Unit, onSeeAll: () -> Unit) {
    val scope = rememberCoroutineScope()
    val thisWeekStart = weekStart(LocalDate.parse(today))
    val lastWeekStart = thisWeekStart.minus(DatePeriod(days = 7)).toString()
    val thisWeek = dreams.filter { it.localDate >= thisWeekStart.toString() }
    val lastWeek = dreams.filter { it.localDate < thisWeekStart.toString() && it.localDate >= lastWeekStart }
    RefreshBox(services) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (thisWeek.isEmpty() && lastWeek.isEmpty()) item(key = "empty") { Text(LAPTOP_DREAMS_EMPTY, modifier = Modifier.animateItem()) }
            for ((heading, rows) in listOf(THIS_WEEK to thisWeek, LAST_WEEK to lastWeek)) {
                if (rows.isEmpty()) continue
                item(key = "head-$heading") { Text(heading, Modifier.padding(horizontal = 4.dp).animateItem(), style = MaterialTheme.typography.titleMedium) }
                items(rows, key = { it.id }) { dream ->
                    LogRow(
                        onDone = null,
                        onEdit = { onOpenForm(FormTarget.Dream(dream.id)) },
                        onDelete = { services.undo.delete(UndoTables.DREAM, dream.id) },
                        modifier = Modifier.animateItem(),
                    ) {
                        DreamCard(dream, today, laptop = true, onInterpret = {
                            scope.launch {
                                queueInterpretOnce(services, dream.id)
                                services.syncInBackground()
                            }
                        }) { onOpenForm(FormTarget.DreamRead(dream.id)) }
                    }
                }
            }
            item(key = "see-all") { SeeAllButton(SEE_ALL_DREAMS, onSeeAll) }
        }
    }
}

private const val SEE_ALL_DREAMS = "See all dreams"
private const val THIS_WEEK = "This week"
private const val LAST_WEEK = "Last week"
private const val LAPTOP_DREAMS_EMPTY = "No dreams this week or last week"
private const val INTERPRET = "Interpret"
private const val INTERPRETED_MARK = "✓ Interpreted"
private const val RECENT = "Recent"
private const val DREAMS_EMPTY = "No dreams yet"
private const val LAST_NIGHT = "Last night"
private const val LUCID = "Lucid"
private const val INTERPRETED = "Interpreted"

/** Headings of the Dreams screen, for the label-uniqueness check. */
internal val dreamLabels: List<String> = listOf(RECENT, DREAMS_EMPTY, SEE_ALL_DREAMS)

/** Texts of the laptop Dreams list pane, for the label-uniqueness check. */
internal val laptopDreamLabels: List<String> = listOf(THIS_WEEK, LAST_WEEK, LAPTOP_DREAMS_EMPTY, SEE_ALL_DREAMS, INTERPRET, INTERPRETED_MARK)
