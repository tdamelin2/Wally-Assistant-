package dev.tim.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.tim.assistant.ui.lists.LaptopTaskRow
import kotlinx.datetime.LocalDate
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Briefing
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Task
import dev.tim.assistant.study.examCountdownText
import dev.tim.assistant.study.upcomingExams
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.lists.RejectedNote
import dev.tim.assistant.ui.lists.TaskGroup
import dev.tim.assistant.ui.lists.dueLine
import dev.tim.assistant.ui.lists.jerusalemDay
import dev.tim.assistant.ui.lists.rememberMinuteClock
import dev.tim.assistant.ui.lists.shortWeekday
import dev.tim.assistant.ui.lists.taskGroupOf

const val GOOGLE_DISCONNECTED_BANNER = "Google disconnected — run google-auth on the server"

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** Today: date and sync line, the briefing, a nudge to check in, open tasks (swipe only) and the next exams. */
@Composable
fun TodayScreen(services: AppServices, onOpenForm: (FormTarget) -> Unit) {
    val tasks: List<Task> = rememberQuery(services) { services.repo.openTasks() }.value ?: emptyList()
    val googleOk: Boolean? = rememberQuery(services) { services.repo.googleOk() }.value
    // The date is computed when the screen opens and again once a minute while it is visible.
    val today = jerusalemDay(rememberMinuteClock())
    val todayText = today.toString()

    // Keyed on the Jerusalem date of the minute clock, so the card changes by itself when the date does.
    val briefing: Briefing? = rememberQuery(services, todayText) { services.repo.briefingFor(todayText) }.value
    val checkins = rememberQuery(services) { services.repo.checkins() }.value

    val exams = rememberQuery(services) { services.repo.exams() }.value
    val courseNames = rememberQuery(services) { services.repo.courses().associate { it.id to it.name } }.value ?: emptyMap()

    val hidden by services.undo.hidden.collectAsState()
    val nextExams = upcomingExams((exams ?: emptyList()).filter { !hidden.hides(UndoTables.EXAM, it.id) }, todayText, limit = 2)
    val openTasks = tasks.filter { !hidden.hides(UndoTables.TASK, it.id) }
    // Null until the check-ins are loaded, so the nudge does not flash on every start.
    val noCheckinToday = checkins?.none { it.localDate == todayText && !hidden.hides(UndoTables.CHECKIN, it.id) } == true
    var briefingOpen by remember { mutableStateOf(false) }

    if (LocalLayoutMode.current == LayoutMode.Wide) {
        LaptopToday(
            services, today, briefing?.text, googleOk == false, noCheckinToday, openTasks, nextExams, courseNames, onOpenForm,
        )
        return
    }
    RefreshBox(services) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${shortWeekday(today)}, ${formatIsoDate(todayText)}", style = MaterialTheme.typography.titleSmall)
                SyncedLine(services)
            }
        }
        if (googleOk == false) {
            item(key = "banner") {
                Text(GOOGLE_DISCONNECTED_BANNER, color = MaterialTheme.colorScheme.error, modifier = Modifier.animateItem())
            }
        }
        if (briefing != null) {
            item(key = "briefing") {
                Surface(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(BRIEFING, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (briefingOpen) "▴" else "▾",
                                Modifier.clickable { briefingOpen = !briefingOpen }.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            briefing.text,
                            style = MaterialTheme.typography.bodyMedium.merge(ContentDirection),
                            maxLines = if (briefingOpen) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (noCheckinToday) {
            item(key = "no-checkin") {
                LogCard(Modifier.animateItem()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(NO_CHECKIN, style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { onOpenForm(FormTarget.Checkin(null)) }) { Text(ADD) }
                    }
                }
            }
        }
        item(key = "tasks-title") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).animateItem(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(TASKS, style = MaterialTheme.typography.titleMedium)
                Text("${openTasks.size} open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (openTasks.isEmpty()) {
            item(key = "tasks-empty") { Text(NO_TASKS, modifier = Modifier.animateItem()) }
        }
        items(openTasks, key = { "task-${it.id}" }) { task ->
            val group = taskGroupOf(task, today)
            SwipeRow(
                onComplete = { services.undo.complete(task.id) },
                onDelete = { services.undo.delete(UndoTables.TASK, task.id) },
                modifier = Modifier.animateItem(),
            ) {
                LogCard(Modifier.clickable { onOpenForm(FormTarget.Task(task)) }) {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge.merge(ContentDirection))
                    val due = dueLine(task, group)
                    val red = group == TaskGroup.Overdue || group == TaskGroup.Today
                    Text(
                        due?.first ?: "No due date",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (red) WallyColors.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RejectedNote(task.syncState)
                }
            }
        }
        item(key = "exams-title") {
            Text(EXAMS, Modifier.padding(horizontal = 4.dp).animateItem(), style = MaterialTheme.typography.titleMedium)
        }
        if (nextExams.isEmpty()) {
            item(key = "exams-empty") { Text(NO_EXAMS, modifier = Modifier.animateItem()) }
        } else {
            item(key = "exams") {
                Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    nextExams.forEachIndexed { i, exam -> ExamTile(exam, todayText, courseNames, i == 0, Modifier.weight(1f)) }
                    if (nextExams.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
        }
    }
    }
}

private fun fullWeekday(day: LocalDate): String = day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

/**
 * The laptop's Today: full weekday and date, the Synced line, the nudge, then two columns. Left: the briefing in
 * full and the tasks card (hover Done / Edit / Delete, no swipe). Right: the next exams.
 */
@Composable
private fun LaptopToday(
    services: AppServices,
    today: LocalDate,
    briefing: String?,
    googleDisconnected: Boolean,
    noCheckinToday: Boolean,
    openTasks: List<Task>,
    nextExams: List<Exam>,
    courseNames: Map<String, String>,
    onOpenForm: (FormTarget) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${fullWeekday(today)}, ${formatDate(today)}", style = MaterialTheme.typography.headlineMedium)
            SyncedLine(services)
        }
        if (googleDisconnected) Text(GOOGLE_DISCONNECTED_BANNER, color = MaterialTheme.colorScheme.error)
        if (noCheckinToday) {
            LogCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(NO_CHECKIN, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { onOpenForm(FormTarget.Checkin(null)) }) { Text(ADD) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (briefing != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(BRIEFING, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(briefing, style = MaterialTheme.typography.bodyLarge.merge(ContentDirection))
                        }
                    }
                }
                LogCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(TASKS, style = MaterialTheme.typography.titleMedium)
                        Text("${openTasks.size} open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (openTasks.isEmpty()) Text(NO_TASKS)
                    for (task in openTasks) LaptopTaskRow(services, task, taskGroupOf(task, today), onOpenForm, withEdit = true)
                }
            }
            Column(Modifier.weight(1f)) {
                LogCard {
                    Text(EXAMS, style = MaterialTheme.typography.titleMedium)
                    if (nextExams.isEmpty()) Text(NO_EXAMS)
                    nextExams.forEachIndexed { i, exam -> LaptopExamTile(exam, today.toString(), courseNames, i == 0) }
                }
            }
        }
    }
}

/** An exam on the laptop: the countdown in a rounded square, the course, and the date with the time. */
@Composable
private fun LaptopExamTile(exam: Exam, today: String, courseNames: Map<String, String>, nearest: Boolean) {
    val countdown = examCountdownText(exam.localDate, today).removePrefix("in ").split(" ", limit = 2)
    val course = exam.courseId?.let { courseNames[it] }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (nearest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (nearest) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.size(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(countdown.first(), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                countdown.getOrNull(1)?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((course ?: exam.title), style = MaterialTheme.typography.bodyLarge.merge(ContentDirection), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (course != null) Text(exam.title, style = MaterialTheme.typography.bodySmall.merge(ContentDirection), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(formatIsoDate(exam.localDate), exam.time?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExamTile(exam: Exam, today: String, courseNames: Map<String, String>, nearest: Boolean, modifier: Modifier) {
    val container = if (nearest) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val content = if (nearest) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        border = if (nearest) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(examCountdownText(exam.localDate, today).removePrefix("in "), style = MaterialTheme.typography.headlineSmall)
            Text(exam.title, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection), maxLines = 2, overflow = TextOverflow.Ellipsis)
            exam.courseId?.let { courseNames[it] }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.merge(ContentDirection), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val BRIEFING = "Briefing"
private const val TASKS = "Tasks"
private const val NO_TASKS = "No open tasks"
private const val EXAMS = "Exams"
private const val NO_EXAMS = "No exams soon"
private const val NO_CHECKIN = "No check-in yet"
private const val ADD = "Add"

/** Headings of the Today screen, for the label-uniqueness check. */
internal val todayLabels: List<String> = listOf(BRIEFING, TASKS, NO_TASKS, EXAMS, NO_EXAMS, NO_CHECKIN, ADD)
