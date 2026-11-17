package dev.tim.assistant.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.ApiResult
import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Study_file
import dev.tim.assistant.data.studySetDocId
import dev.tim.assistant.study.examCountdownText
import dev.tim.assistant.study.studyFileStatusText
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.LaptopColumns
import dev.tim.assistant.ui.LeaveGuardEffect
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.OnNewRequest
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.StudyAddSheet
import dev.tim.assistant.ui.files.DocEditor
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.latestJob
import dev.tim.assistant.ui.rememberQuery
import dev.tim.assistant.ui.stacksSidePanel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class LaptopStudyData(val study: StudyData, val exams: List<Exam>)

/** What the middle column shows instead of the course page. */
private sealed interface StudySub {
    data class Cards(val fileId: String) : StudySub
    data class Quiz(val quizId: String) : StudySub
    data class Summary(val docId: String) : StudySub
}

/**
 * Laptop Study: Moodle line, courses and Upload on the left; the selected course (files, Practice / Quiz / Summary,
 * the existing practice views) in the middle; All exams on the right (under the course when the window is narrow).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyLaptop(services: AppServices, picker: FilePicker?, requestNew: Int) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var sub by remember { mutableStateOf<StudySub?>(null) }
    var summaryDirty by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showOff by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var uploadOpen by remember { mutableStateOf(false) }
    var examAdd by remember { mutableIntStateOf(0) }
    var deleteFile by remember { mutableStateOf<Study_file?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val overrides = remember { mutableStateMapOf<String, Boolean>() }
    OnNewRequest(requestNew) { addSheet = true }

    LeaveGuardEffect(sub is StudySub.Summary && summaryDirty)

    /** Runs [action] now, or after the user agrees to discard when the open summary has unsaved changes. */
    fun guarded(action: () -> Unit) {
        if (sub is StudySub.Summary && summaryDirty) pendingSwitch = action else action()
    }

    val data = rememberQuery(services) { LaptopStudyData(loadStudyData(repo), repo.exams()) }.value
    val study = data?.study
    val today = localDateOf(Clock.System.now())
    val all = study?.active.orEmpty() + study?.off.orEmpty()
    val current = all.firstOrNull { it.course.id == selectedId } ?: study?.active?.firstOrNull()

    fun examText(course: Course): String {
        val next = data?.exams.orEmpty().filter { it.courseId == course.id && it.localDate >= today }
            .minWithOrNull(compareBy({ it.localDate }, { it.time ?: "" }, { it.id })) ?: return "No exam"
        return "Exam " + examCountdownText(next.localDate, today)
    }

    fun queueStudySet(file: Study_file) {
        scope.launch {
            val busy = repo.jobs().any { it.kind == "STUDY_SET" && jobIsActive(it) && latestJob(listOf(it), "STUDY_SET", "fileId", file.id) != null }
            if (busy) return@launch
            repo.queueJob("STUDY_SET", buildJsonObject { put("fileId", file.id) }.toString())
            services.syncInBackground()
        }
    }

    fun queueQuiz(course: Course) {
        scope.launch {
            val busy = repo.jobs().any { it.kind == "QUIZ" && jobIsActive(it) && latestJob(listOf(it), "QUIZ", "courseId", course.id) != null }
            if (busy) return@launch
            repo.queueJob("QUIZ", buildJsonObject { put("courseId", course.id) }.toString())
            services.syncInBackground()
        }
    }

    fun setEnabled(course: Course, enabled: Boolean) {
        val cfg = services.sync.serverConfig()
        if (cfg == null) {
            message = "Set up the server in Settings first"
            return
        }
        overrides[course.id] = enabled
        scope.launch {
            val result = services.client.setCourseEnabled(cfg, course.id, enabled)
            message = failureText(result)
            if (result is ApiResult.Ok) services.sync.sync()
            overrides.remove(course.id)
        }
    }

    val moodleFiles = all.flatMap { it.files }.filter { it.file.origin != "UPLOAD" }
    val moodleChecked = moodleFiles.maxOfOrNull { it.file.updatedAt }

    val leftColumn: @Composable () -> Unit = {
        Column(
            Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (study?.moodleOk == false) {
                Text("Reconnect Moodle", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                Text("Wally lost access to Moodle. Open Settings to log in again.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Moodle · " + (if (moodleChecked == null) "not checked yet" else "checked ${clockText(moodleChecked)}"), style = MaterialTheme.typography.bodySmall)
            }
            if (study?.moodleOk == true && all.any { it.course.moodleId != null } && all.none { it.course.moodleId != null && it.course.enabled != 0L }) {
                Text("Turn on the courses you want Wally to check.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Courses", style = MaterialTheme.typography.titleSmall)
            if (study != null && all.isEmpty()) Text("No courses yet. Connect Moodle in Settings, or upload a PDF or PPTX.", style = MaterialTheme.typography.bodySmall)
            study?.active.orEmpty().forEach { b ->
                CourseRow(b.course, examText(b.course), current?.course?.id == b.course.id) { guarded { selectedId = b.course.id; sub = null } }
            }
            if (study != null && study.off.isNotEmpty()) {
                TextButton(onClick = { showOff = !showOff }) { Text("Other courses (${study.off.size})" + if (showOff) " - hide" else " - show") }
                if (showOff) study.off.forEach { b ->
                    CourseRow(b.course, examText(b.course), current?.course?.id == b.course.id) { guarded { selectedId = b.course.id; sub = null } }
                }
            }
            Button(onClick = { uploadOpen = true }, enabled = picker != null) { Text("Upload file") }
        }
    }

    val middleColumn: @Composable () -> Unit = {
        val s = sub
        when {
            s is StudySub.Cards -> CardsView(services, s.fileId, onClose = { sub = null })
            s is StudySub.Quiz -> QuizView(services, s.quizId, onClose = { sub = null })
            s is StudySub.Summary -> DocEditor(services, s.docId, null, onClose = { sub = null }, onDirtyChange = { summaryDirty = it })
            current == null -> Text(
                if (study == null) "" else "Pick a course",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> CoursePage(
                services, current, study?.jobs.orEmpty(), overrides, message, moodleChecked = clockOrNull(current.files.filter { it.file.origin != "UPLOAD" }.maxOfOrNull { it.file.updatedAt }),
                onToggle = ::setEnabled, onMakeSet = ::queueStudySet, onQuiz = ::queueQuiz, onSub = { sub = it }, onDelete = { deleteFile = it },
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaptopColumns(stacksSidePanel(maxWidth.value), side = { LaptopExams(services, examAdd) }) {
            Row(Modifier.fillMaxSize()) {
                leftColumn()
                Column(Modifier.weight(1f).fillMaxHeight()) { RefreshBox(services, Modifier.fillMaxSize()) { middleColumn() } }
            }
        }
    }

    if (addSheet) StudyAddSheet(
        canUpload = picker != null,
        onUpload = { addSheet = false; uploadOpen = true },
        onExam = { addSheet = false; examAdd++ },
        onDismiss = { addSheet = false },
    )
    if (uploadOpen && picker != null) UploadDialog(services, picker, all.map { it.course }, onClose = { uploadOpen = false })
    deleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            title = { Text("Delete this file?") },
            text = { Text("${file.name} and its text are removed from the server. Cards and the summary stay.", style = ContentText) },
            confirmButton = {
                TextButton(onClick = {
                    deleteFile = null
                    val cfg = services.sync.serverConfig()
                    scope.launch {
                        if (cfg == null) {
                            message = "Set up the server in Settings first"
                        } else {
                            val r = services.client.deleteStudyFile(cfg, file.id)
                            message = failureText(r)
                            if (r is ApiResult.Ok) services.sync.sync()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("Cancel") } },
        )
    }
    pendingSwitch?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Discard your changes?") },
            confirmButton = { TextButton(onClick = { pendingSwitch = null; summaryDirty = false; action() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("Keep editing") } },
        )
    }
}

/** One course in the left column: name and its next exam; the selected one is tinted. */
@Composable
private fun CourseRow(course: Course, exam: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(course.name, style = MaterialTheme.typography.bodyMedium.merge(ContentText), maxLines = 2)
        Text(exam, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The selected course: header, Moodle switch, Practice / Quiz / Summary tiles, quizzes and the file list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoursePage(
    services: AppServices,
    block: CourseBlock,
    jobs: List<dev.tim.assistant.db.Ai_job>,
    overrides: Map<String, Boolean>,
    message: String?,
    moodleChecked: String?,
    onToggle: (Course, Boolean) -> Unit,
    onMakeSet: (Study_file) -> Unit,
    onQuiz: (Course) -> Unit,
    onSub: (StudySub) -> Unit,
    onDelete: (Study_file) -> Unit,
) {
    val course = block.course
    val scope = rememberCoroutineScope()
    val quizJob = latestJob(jobs, "QUIZ", "courseId", course.id)
    val cards = block.files.sumOf { it.cards }
    val firstCards = block.files.firstOrNull { it.cards > 0 }
    val firstSummary = block.files.firstOrNull { it.hasDoc }
    val summaries = block.files.count { it.hasDoc }
    val newestQuiz = block.quizzes.maxByOrNull { it.updatedAt }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleLarge.merge(ContentText))
                Text(
                    "${block.files.size} files · last checked ${moodleChecked ?: "never"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (course.moodleId != null) item(key = "switch") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check Moodle for new files", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = overrides[course.id] ?: (course.enabled != 0L), onCheckedChange = { onToggle(course, it) })
            }
        }
        message?.let { m -> item(key = "message") { Text(m, color = MaterialTheme.colorScheme.error) } }
        item(key = "tiles") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudyTile("Practice", "$cards flashcards", firstCards != null, Modifier.weight(1f)) { firstCards?.let { onSub(StudySub.Cards(it.file.id)) } }
                StudyTile(
                    "Quiz", if (block.quizzes.isEmpty()) "10 questions from your files" else "${block.quizzes.size} quizzes",
                    newestQuiz != null || (block.hasStudySet && !jobIsActive(quizJob)), Modifier.weight(1f),
                ) { if (newestQuiz != null) onSub(StudySub.Quiz(newestQuiz.id)) else onQuiz(course) }
                StudyTile("Summary", if (summaries == 0) "No summary yet" else "Read the course summary", firstSummary != null, Modifier.weight(1f)) {
                    firstSummary?.let { onSub(StudySub.Summary(studySetDocId(it.file.id))) }
                }
            }
        }
        item(key = "quiz-status") {
            Column {
                if (!block.hasStudySet) Text("Make a study set first", style = MaterialTheme.typography.bodySmall)
                JobStatusLine(quizJob)
            }
        }
        items(block.quizzes, key = { "quiz-${it.id}" }) { quiz ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(quizLabel(quiz), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onSub(StudySub.Quiz(quiz.id)) }) { Text(if (quiz.score == null) "Take" else "Retake") }
                TextButton(onClick = { scope.launch { services.repo.deleteQuiz(quiz.id); services.syncInBackground() } }) { Text("Delete") }
            }
        }
        if (block.hasStudySet) item(key = "new-quiz") {
            OutlinedButton(onClick = { onQuiz(course) }, enabled = !jobIsActive(quizJob)) { Text("New quiz") }
        }
        item(key = "files-head") {
            Column {
                Text("Course files", style = MaterialTheme.typography.titleSmall)
                if (block.files.any { it.file.origin == "UPLOAD" }) {
                    Text("Hover a file to delete it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (block.files.isEmpty()) Text("No files yet", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(block.files, key = { "file-${it.file.id}" }) { row ->
            val file = row.file
            val job = latestJob(jobs, "STUDY_SET", "fileId", file.id)
            val content: @Composable () -> Unit = {
                Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium.merge(ContentText))
                        Text(studyFileStatusText(file), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (row.hasDoc) Button(onClick = { onSub(StudySub.Summary(studySetDocId(file.id))) }) { Text("Open") }
                            if (row.cards > 0) OutlinedButton(onClick = { onSub(StudySub.Cards(file.id)) }) { Text("Cards (${row.cards})") }
                            OutlinedButton(onClick = { onMakeSet(file) }, enabled = file.textStatus == "READY" && !jobIsActive(job)) { Text("Make study set") }
                        }
                        JobStatusLine(job)
                    }
                }
            }
            if (file.origin == "UPLOAD") {
                LogRow(onDone = null, onEdit = null, onDelete = { onDelete(file) }, modifier = Modifier.animateItem(), endInset = 0.dp) { content() }
            } else {
                content()
            }
        }
    }
}

/** One of the Practice / Quiz / Summary tiles of a course. */
@Composable
private fun StudyTile(title: String, caption: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(caption, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun clockOrNull(ms: Long?): String? = ms?.let(::clockText)

/** HH:mm in Jerusalem time. */
private fun clockText(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(JERUSALEM).time
    return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
}

/** Texts of the laptop Study screen, for the label-uniqueness check (left column and course page). */
internal val laptopStudyLabels: List<String> = listOf(
    "Courses", "Upload file", "Reconnect Moodle", "Check Moodle for new files", "Practice", "Quiz", "Summary", "Course files",
    "Open", "Make study set", "New quiz", "Take", "Retake", "Delete", "No files yet",
)

/** Texts of the laptop All exams pane. */
internal val laptopExamsLabels: List<String> = listOf("All exams", "+ Exam", "Past", "No exams yet")
