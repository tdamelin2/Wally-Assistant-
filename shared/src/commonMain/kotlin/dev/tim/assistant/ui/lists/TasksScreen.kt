package dev.tim.assistant.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.LogRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Task
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.SwipeRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.WallyColors
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberQuery
import kotlinx.datetime.LocalDate

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** Tasks: open tasks grouped by due date; swipe right = done, left = delete, tap = edit. */
@Composable
fun TasksScreen(services: AppServices, onOpenForm: (FormTarget) -> Unit) {
    val historyOpen = rememberHistoryOpen()
    if (historyOpen.value) { HistoryScreen(services, dev.tim.assistant.ui.NavTab.Tasks) { historyOpen.value = false }; return }
    val today = jerusalemDay(rememberMinuteClock())
    val tasks = rememberQuery(services) { services.repo.openTasks() }.value ?: emptyList()
    val hidden by services.undo.hidden.collectAsState()
    val groups = taskGroups(tasks.filter { !hidden.hides(UndoTables.TASK, it.id) }, today)

    if (LocalLayoutMode.current == LayoutMode.Wide) {
        LaptopTasks(services, groups, onOpenForm) { historyOpen.value = true }
        return
    }
    RefreshBox(services) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (groups.values.all { it.isEmpty() }) item(key = "empty") { Text(TASKS_EMPTY, modifier = Modifier.animateItem()) }
            for ((group, rows) in groups) {
                if (rows.isEmpty()) continue
                item(key = "head-${group.name}") {
                    Text(groupLabel(group), Modifier.padding(start = 4.dp, top = 6.dp).animateItem(), style = MaterialTheme.typography.titleMedium)
                }
                items(rows, key = { it.id }) { task ->
                    SwipeRow(
                        onComplete = { services.undo.complete(task.id) },
                        onDelete = { services.undo.delete(UndoTables.TASK, task.id) },
                        modifier = Modifier.animateItem(),
                    ) {
                        LogCard(Modifier.clickable { onOpenForm(FormTarget.Task(task)) }) {
                            Text(task.title, style = MaterialTheme.typography.bodyLarge.merge(ContentDirection))
                            dueLine(task, group)?.let { (text, overdue) ->
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (overdue) WallyColors.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RejectedNote(task.syncState)
                        }
                    }
                }
            }
            item(key = "see-all") { SeeAllButton(SEE_COMPLETED) { historyOpen.value = true } }
        }
    }
}

/** The laptop's Tasks list: one card, a small caption per due group, compact rows with hover Done / Delete. */
@Composable
private fun LaptopTasks(services: AppServices, groups: Map<TaskGroup, List<Task>>, onOpenForm: (FormTarget) -> Unit, onSeeAll: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
        item(key = "card") {
            LogCard {
                if (groups.values.all { it.isEmpty() }) Text(TASKS_EMPTY)
                for ((group, rows) in groups) {
                    if (rows.isEmpty()) continue
                    Text(laptopGroupLabel(group), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (task in rows) LaptopTaskRow(services, task, group, onOpenForm, withEdit = false)
                }
                SeeAllButton(SEE_COMPLETED, onSeeAll)
            }
        }
    }
}

/**
 * One compact laptop task row: title, due text and (while hovered) Done / Delete, plus Edit when [withEdit]. Click
 * opens the task in the side form. Done and Delete go through the Undo controller.
 */
@Composable
internal fun LaptopTaskRow(services: AppServices, task: Task, group: TaskGroup, onOpenForm: (FormTarget) -> Unit, withEdit: Boolean) {
    val open = { onOpenForm(FormTarget.Task(task)) }
    LogRow(
        onDone = { services.undo.complete(task.id) },
        onEdit = if (withEdit) open else null,
        onDelete = { services.undo.delete(UndoTables.TASK, task.id) },
    ) {
        val due = dueLine(task, group)
        val red = group == TaskGroup.Overdue || group == TaskGroup.Today
        Row(
            Modifier.fillMaxWidth().clickable(onClick = open).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge.merge(ContentDirection))
                RejectedNote(task.syncState)
            }
            Text(
                due?.first ?: "No due date",
                style = MaterialTheme.typography.bodySmall,
                color = if (red) WallyColors.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(if (withEdit) 144.dp else 96.dp))
        }
    }
}

private fun laptopGroupLabel(group: TaskGroup): String = when (group) {
    TaskGroup.Today -> "Today"
    else -> groupLabel(group)
}

/** The small line under a task title and whether it is shown in the overdue red; null for tasks with no date. */
internal fun dueLine(task: Task, group: TaskGroup): Pair<String, Boolean>? {
    val due = task.dueDate ?: return null
    val day = try { LocalDate.parse(due.trim()) } catch (e: IllegalArgumentException) { return null }
    return when (group) {
        TaskGroup.Overdue -> "Was due ${formatIsoDate(due)}" to true
        TaskGroup.Today -> "Due today" to false
        TaskGroup.ThisWeek -> shortWeekday(day) to false
        TaskGroup.Later -> "Due ${formatIsoDate(due)}" to false
        TaskGroup.NoDate -> null
    }
}

private fun groupLabel(group: TaskGroup): String = when (group) {
    TaskGroup.Overdue -> "Overdue"
    TaskGroup.Today -> "Due today"
    TaskGroup.ThisWeek -> "This week"
    TaskGroup.Later -> "Later"
    TaskGroup.NoDate -> "No date"
}

private const val SEE_COMPLETED = "See completed tasks"
private const val TASKS_EMPTY = "No open tasks"

/** Texts of the Tasks screen, for the label-uniqueness check. */
internal val tasksLabels: List<String> = TaskGroup.entries.map { groupLabel(it) } + TASKS_EMPTY + SEE_COMPLETED

/** Texts of the laptop Tasks list (its own pane next to the chips), for the label-uniqueness check. */
internal val laptopTasksLabels: List<String> = TaskGroup.entries.map { laptopGroupLabel(it) } + TASKS_EMPTY + SEE_COMPLETED
