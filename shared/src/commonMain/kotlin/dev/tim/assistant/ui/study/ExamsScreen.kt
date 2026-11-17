package dev.tim.assistant.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.OnNewRequest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Exam
import dev.tim.assistant.study.examCountdownText
import dev.tim.assistant.study.isExamPast
import dev.tim.assistant.study.isValidDate
import dev.tim.assistant.ui.DateField
import dev.tim.assistant.ui.TimeField
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.study.isValidTime
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.SwipeRow
import dev.tim.assistant.ui.SystemBack
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.rememberQuery
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class ExamsData(val exams: List<Exam>, val courses: List<Course>)

/** The exam list: add, edit and delete; exams whose day has passed are marked "past". */
@Composable
fun ExamsScreen(services: AppServices, openAdd: Boolean = false, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Exam?>(null) }
    var adding by remember { mutableStateOf(openAdd) }
    var nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = Clock.System.now().toEpochMilliseconds()
            delay(60_000)
        }
    }
    val today = localDateOf(Instant.fromEpochMilliseconds(nowMs))
    val data = rememberQuery(services) { ExamsData(services.repo.exams(), services.repo.courses()) }.value
    val names = data?.courses.orEmpty().associate { it.id to it.name }

    SystemBack(onBack = onClose)
    val hidden by services.undo.hidden.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { BackIcon() }
            Text("All exams", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }) { Text("Add exam") }
        }
        val exams = data?.exams.orEmpty().filter { !hidden.hides(UndoTables.EXAM, it.id) }
        val upcoming = exams.filter { !isExamPast(it, today) }
        val past = exams.filter { isExamPast(it, today) }
        if (data != null && exams.isEmpty()) Text("No exams yet")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (upcoming.isNotEmpty()) item(key = "upcoming-head") { Text("Upcoming", style = MaterialTheme.typography.titleMedium) }
            items(upcoming, key = { it.id }) { exam -> ExamRow(services, exam, names, today, false) { editing = exam } }
            if (past.isNotEmpty()) item(key = "past-head") { Text("Past", style = MaterialTheme.typography.titleMedium) }
            items(past, key = { it.id }) { exam -> ExamRow(services, exam, names, today, true) { editing = exam } }
        }
    }
    if (adding || editing != null) {
        ExamDialog(
            services, editing, today, data?.courses.orEmpty(),
            onClose = { adding = false; editing = null },
        )
    }
}

@Composable
private fun LazyItemScope.ExamRow(services: AppServices, exam: Exam, names: Map<String, String>, today: String, past: Boolean, onEdit: () -> Unit) {
    SwipeRow(onComplete = null, onDelete = { services.undo.delete(UndoTables.EXAM, exam.id) }, modifier = Modifier.animateItem()) {
        LogCard(Modifier.clickable(onClick = onEdit)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(exam.title, style = MaterialTheme.typography.bodyLarge.merge(ContentText))
                    val course = exam.courseId?.let { names[it] }
                    Text(
                        listOfNotNull(course, formatIsoDate(exam.localDate) + (exam.time?.let { " $it" } ?: "")).joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall.merge(ContentText),
                    )
                }
                Text(
                    if (past) "past" else examCountdownText(exam.localDate, today),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExamDialog(services: AppServices, exam: Exam?, today: String, courses: List<Course>, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember(exam?.id) { mutableStateOf(exam?.title ?: "") }
    var date by remember(exam?.id) { mutableStateOf(exam?.localDate ?: today) }
    var time by remember(exam?.id) { mutableStateOf(exam?.time ?: "") }
    var courseId by remember(exam?.id) { mutableStateOf(exam?.courseId) }
    val titleOk = title.trim().length in 1..200
    var dateValid by remember { mutableStateOf(true) }
    var timeValid by remember { mutableStateOf(true) }
    val dateOk = dateValid && isValidDate(date.trim())
    val timeOk = timeValid && (time.isBlank() || isValidTime(time.trim()))

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (exam == null) "New exam" else "Edit exam") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WallyTextField(title, { title = it.take(200) }, multiline = false, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField("Date", date, { date = it }, onValidChange = { dateValid = it })
                TimeField("Time (optional)", time, { time = it }, optional = true, onValidChange = { timeValid = it })
                Text("Course (optional)", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = courseId == null, onClick = { courseId = null }, label = { Text("None") })
                    courses.forEach { c ->
                        FilterChip(selected = courseId == c.id, onClick = { courseId = c.id }, label = { Text(c.name, maxLines = 1) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = titleOk && dateOk && timeOk,
                onClick = {
                    scope.launch {
                        services.repo.saveExam(exam?.id, courseId, title, date.trim(), time.trim().ifEmpty { null })
                        services.syncInBackground()
                        onClose()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** The laptop's All exams pane: countdown tiles for what is coming, then Past; add and edit use the same exam form. */
@Composable
internal fun LaptopExams(services: AppServices, addRequest: Int) {
    var editing by remember { mutableStateOf<Exam?>(null) }
    var adding by remember { mutableStateOf(false) }
    OnNewRequest(addRequest) { adding = true }
    var nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = Clock.System.now().toEpochMilliseconds()
            delay(60_000)
        }
    }
    val today = localDateOf(Instant.fromEpochMilliseconds(nowMs))
    val data = rememberQuery(services) { ExamsData(services.repo.exams(), services.repo.courses()) }.value
    val names = data?.courses.orEmpty().associate { it.id to it.name }
    val hidden by services.undo.hidden.collectAsState()
    val exams = data?.exams.orEmpty().filter { !hidden.hides(UndoTables.EXAM, it.id) }
    val upcoming = exams.filter { !isExamPast(it, today) }.sortedWith(compareBy({ it.localDate }, { it.time ?: "" }, { it.id }))
    val past = exams.filter { isExamPast(it, today) }.sortedWith(compareByDescending<Exam> { it.localDate }.thenBy { it.id })

    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All exams", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }) { Text("+ Exam") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (data != null && exams.isEmpty()) item(key = "none") { Text("No exams yet") }
            itemsIndexed(upcoming, key = { _, e -> e.id }) { i, exam ->
                LogRow(onDone = null, onEdit = { editing = exam }, onDelete = { services.undo.delete(UndoTables.EXAM, exam.id) }, modifier = Modifier.animateItem()) {
                    LaptopExamTile(exam, today, names, nearest = i == 0, onClick = { editing = exam })
                }
            }
            if (past.isNotEmpty()) item(key = "past-head") { Text("Past", style = MaterialTheme.typography.titleSmall) }
            items(past, key = { it.id }) { exam ->
                LogRow(onDone = null, onEdit = { editing = exam }, onDelete = { services.undo.delete(UndoTables.EXAM, exam.id) }, modifier = Modifier.animateItem()) {
                    Column(Modifier.fillMaxWidth().clickable { editing = exam }.padding(vertical = 4.dp)) {
                        Text(
                            listOfNotNull(exam.title, exam.courseId?.let { names[it] }).joinToString(" - "),
                            style = MaterialTheme.typography.bodyMedium.merge(ContentText),
                        )
                        Text(formatIsoDate(exam.localDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        ExamDialog(services, editing, today, data?.courses.orEmpty(), onClose = { adding = false; editing = null })
    }
}

/** An upcoming exam: the days left in a rounded square, the title, and the course with MM-dd-yyyy date and time. */
@Composable
private fun LaptopExamTile(exam: Exam, today: String, courseNames: Map<String, String>, nearest: Boolean, onClick: () -> Unit) {
    val countdown = examCountdownText(exam.localDate, today).removePrefix("in ").split(" ", limit = 2)
    val course = exam.courseId?.let { courseNames[it] } ?: "No course"
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (nearest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (nearest) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.size(60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(countdown.first(), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                countdown.getOrNull(1)?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(exam.title, style = MaterialTheme.typography.bodyLarge.merge(ContentText), maxLines = 2)
            Text(
                listOfNotNull(course, formatIsoDate(exam.localDate), exam.time?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.merge(ContentText),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}