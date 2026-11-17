package dev.tim.assistant.ui.study

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import dev.tim.assistant.ui.RefreshBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.ApiResult
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Quiz
import dev.tim.assistant.db.Study_file
import dev.tim.assistant.study.STUDY_FILE_MAX_BYTES
import dev.tim.assistant.study.sortCourses
import dev.tim.assistant.study.studyFileStatusText
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.data.Repository
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.OnNewRequest
import dev.tim.assistant.ui.StudyAddSheet
import dev.tim.assistant.ui.files.DocEditor
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.latestJob
import dev.tim.assistant.ui.rememberQuery
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val ContentText = TextStyle(textDirection = TextDirection.Content)

/** A fixed, short text for a failed call; null when [result] succeeded. */
internal fun <T> failureText(result: ApiResult<T>): String? = when (result) {
    is ApiResult.Ok -> null
    ApiResult.Unauthorized -> "Wrong device token - check Settings"
    is ApiResult.Failure -> result.message
}

private sealed interface StudyView {
    data object Main : StudyView
    data object Exams : StudyView
    data object ExamsAdd : StudyView
    data class Cards(val fileId: String) : StudyView
    data class Quiz(val quizId: String) : StudyView
    data class Summary(val docId: String) : StudyView
}

internal class FileRow(val file: Study_file, val cards: Int, val hasDoc: Boolean)

internal class CourseBlock(val course: Course, val files: List<FileRow>, val quizzes: List<Quiz>, val hasStudySet: Boolean)

internal class StudyData(val active: List<CourseBlock>, val off: List<CourseBlock>, val jobs: List<Ai_job>, val moodleOk: Boolean?)

/** Everything the Study screens list: the courses with their files and quizzes, the study jobs and the Moodle state. */
internal fun loadStudyData(repo: Repository): StudyData {
    val (active, off) = sortCourses(repo.courses())
    fun block(c: Course): CourseBlock {
        val files = repo.studyFiles(c.id).map { FileRow(it, repo.cardCount(it.id), repo.docById(dev.tim.assistant.data.studySetDocId(it.id)) != null) }
        return CourseBlock(c, files, repo.quizzesForCourse(c.id), repo.studySetDocs(c.id).isNotEmpty())
    }
    return StudyData(
        active.map(::block), off.map(::block),
        repo.jobs().filter { it.kind == "STUDY_SET" || it.kind == "QUIZ" }, repo.moodleOk(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyScreen(services: AppServices, picker: FilePicker?, requestNew: Int = 0) {
    if (LocalLayoutMode.current == LayoutMode.Wide) {
        StudyLaptop(services, picker, requestNew)
        return
    }
    var view by remember { mutableStateOf<StudyView>(StudyView.Main) }
    var addSheet by remember { mutableStateOf(false) }
    var uploadOpen by remember { mutableStateOf(false) }
    OnNewRequest(requestNew) { addSheet = true }
    // A tap on an exam reminder opens All exams once.
    val examsRequested by services.launch.examsRequested.collectAsState()
    LaunchedEffect(examsRequested) {
        if (examsRequested) { view = StudyView.Exams; services.launch.examsRequested.value = false }
    }
    if (addSheet) StudyAddSheet(
        canUpload = picker != null,
        onUpload = { addSheet = false; view = StudyView.Main; uploadOpen = true },
        onExam = { addSheet = false; view = StudyView.ExamsAdd },
        onDismiss = { addSheet = false },
    )
    when (val v = view) {
        StudyView.Main -> StudyMain(services, picker, uploadOpen, { uploadOpen = it }, onOpen = { view = it })
        StudyView.Exams -> ExamsScreen(services, onClose = { view = StudyView.Main })
        StudyView.ExamsAdd -> ExamsScreen(services, openAdd = true, onClose = { view = StudyView.Main })
        is StudyView.Cards -> CardsView(services, v.fileId, onClose = { view = StudyView.Main })
        is StudyView.Quiz -> QuizView(services, v.quizId, onClose = { view = StudyView.Main })
        is StudyView.Summary -> DocEditor(services, v.docId, null, onClose = { view = StudyView.Main })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyMain(services: AppServices, picker: FilePicker?, uploadOpen: Boolean, setUploadOpen: (Boolean) -> Unit, onOpen: (StudyView) -> Unit) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var showOff by remember { mutableStateOf(false) }
    var deleteFile by remember { mutableStateOf<Study_file?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val overrides = remember { mutableStateMapOf<String, Boolean>() }

    val data = rememberQuery(services) { loadStudyData(repo) }.value

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

    val allMoodle = (data?.active.orEmpty() + data?.off.orEmpty()).filter { it.course.moodleId != null }
    val noneOn = allMoodle.isNotEmpty() && allMoodle.none { it.course.enabled != 0L }

    RefreshBox(services) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth().animateItem(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { onOpen(StudyView.Exams) }) { Text("All exams") }
                Button(onClick = { setUploadOpen(true) }, enabled = picker != null) { Text("Upload file") }
            }
        }
        if (data?.moodleOk == false) {
            item(key = "reconnect") {
                Column(Modifier.animateItem()) {
                    Text("Reconnect Moodle", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    Text("Wally lost access to Moodle. Open Settings to log in again.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (data?.moodleOk == true && noneOn) {
            item(key = "turn-on") { Text("Turn on the courses you want Wally to check.", modifier = Modifier.animateItem()) }
        }
        message?.let { m -> item(key = "message") { Text(m, color = MaterialTheme.colorScheme.error, modifier = Modifier.animateItem()) } }
        if (data != null && data.active.isEmpty() && data.off.isEmpty()) {
            item(key = "empty") { Text("No courses yet. Connect Moodle in Settings, or upload a PDF or PPTX.", modifier = Modifier.animateItem()) }
        }
        if (data != null) {
            items(data.active, key = { "course-${it.course.id}" }) { block ->
                Box(Modifier.animateItem()) {
                    CourseCard(services, block, data.jobs, overrides, onToggle = ::setEnabled, onMakeSet = ::queueStudySet,
                        onQuiz = ::queueQuiz, onOpen = onOpen, onDelete = { deleteFile = it })
                }
            }
            if (data.off.isNotEmpty()) {
                item(key = "other") {
                    TextButton(modifier = Modifier.animateItem(), onClick = { showOff = !showOff }) {
                        Text("Other courses (${data.off.size})" + if (showOff) " - hide" else " - show")
                    }
                }
                if (showOff) {
                    items(data.off, key = { "course-${it.course.id}" }) { block ->
                        Box(Modifier.animateItem()) {
                            CourseCard(services, block, data.jobs, overrides, onToggle = ::setEnabled, onMakeSet = ::queueStudySet,
                                onQuiz = ::queueQuiz, onOpen = onOpen, onDelete = { deleteFile = it })
                        }
                    }
                }
            }
        }
    }
    }

    if (uploadOpen && picker != null) {
        UploadDialog(services, picker, data?.active.orEmpty().map { it.course } + data?.off.orEmpty().map { it.course }, onClose = { setUploadOpen(false) })
    }
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseCard(
    services: AppServices,
    block: CourseBlock,
    jobs: List<Ai_job>,
    overrides: Map<String, Boolean>,
    onToggle: (Course, Boolean) -> Unit,
    onMakeSet: (Study_file) -> Unit,
    onQuiz: (Course) -> Unit,
    onOpen: (StudyView) -> Unit,
    onDelete: (Study_file) -> Unit,
) {
    val course = block.course
    val scope = rememberCoroutineScope()
    val quizJob = latestJob(jobs, "QUIZ", "courseId", course.id)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(course.name, style = MaterialTheme.typography.titleMedium.merge(ContentText), modifier = Modifier.weight(1f))
                if (course.moodleId != null) {
                    Text("Check files", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = overrides[course.id] ?: (course.enabled != 0L), onCheckedChange = { onToggle(course, it) })
                }
            }
            if (block.files.isEmpty()) Text("No files yet", style = MaterialTheme.typography.bodySmall)
            for (row in block.files) {
                val file = row.file
                val job = latestJob(jobs, "STUDY_SET", "fileId", file.id)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium.merge(ContentText))
                    Text(studyFileStatusText(file), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onMakeSet(file) },
                            enabled = file.textStatus == "READY" && !jobIsActive(job),
                        ) { Text("Make study set") }
                        if (row.cards > 0) OutlinedButton(onClick = { onOpen(StudyView.Cards(file.id)) }) { Text("Cards (${row.cards})") }
                        if (row.hasDoc) {
                            OutlinedButton(onClick = { onOpen(StudyView.Summary(dev.tim.assistant.data.studySetDocId(file.id))) }) { Text("Open summary") }
                        }
                        if (file.origin == "UPLOAD") TextButton(onClick = { onDelete(file) }) { Text("Delete file") }
                    }
                    JobStatusLine(job)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onQuiz(course) }, enabled = block.hasStudySet && !jobIsActive(quizJob)) { Text("Practice quiz") }
                if (!block.hasStudySet) Text("Make a study set first", style = MaterialTheme.typography.bodySmall)
            }
            JobStatusLine(quizJob)
            for (quiz in block.quizzes) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(quizLabel(quiz), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onOpen(StudyView.Quiz(quiz.id)) }) { Text(if (quiz.score == null) "Take" else "Retake") }
                    TextButton(onClick = { scope.launch { services.repo.deleteQuiz(quiz.id); services.syncInBackground() } }) { Text("Delete") }
                }
            }
        }
    }
}

internal fun quizLabel(quiz: Quiz): String {
    val at = quiz.takenAt
    val date = if (at == null) null else Instant.fromEpochMilliseconds(at).toLocalDateTime(JERUSALEM).date.toString()
    return if (quiz.score == null) "Quiz - not taken yet" else "Quiz - ${quiz.score}/10" + (date?.let { " ($it)" } ?: "")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UploadDialog(services: AppServices, picker: FilePicker, courses: List<Course>, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun start() {
        val target = newName.trim().take(100).ifEmpty { selected }
        if (target == null) {
            status = "Choose a course or type a new course name"
            return
        }
        scope.launch {
            val picked = picker.pick() ?: return@launch
            val lower = picked.name.trim().lowercase()
            when {
                !(lower.endsWith(".pdf") || lower.endsWith(".pptx")) -> status = "Only PDF or PPTX"
                picked.bytes.size > STUDY_FILE_MAX_BYTES -> status = "Too large (max 50 MB)"
                else -> {
                    val cfg = services.sync.serverConfig()
                    if (cfg == null) {
                        status = "Set up the server in Settings first"
                    } else {
                        busy = true
                        status = "Uploading..."
                        val r = services.client.uploadStudyFile(cfg, target, picked.bytes, picked.name.trim())
                        busy = false
                        status = failureText(r) ?: "Uploaded - Wally is reading the file"
                        if (r is ApiResult.Ok) services.sync.sync()
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Upload a PDF or PPTX") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Course")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    courses.forEach { c ->
                        FilterChip(selected = selected == c.id && newName.isBlank(), onClick = { selected = c.id; newName = "" }, label = { Text(c.name, maxLines = 1) })
                    }
                }
                WallyTextField(
                    multiline = false,
                    value = newName,
                    onValueChange = { newName = it.take(100) },
                    label = { Text("or a new course name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = ::start, enabled = !busy) { Text("Choose file") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Close") } },
    )
}

/** Headings and screen-level buttons of the Study screen, for the label-uniqueness check. */
internal val studyLabels: List<String> = listOf("All exams", "Upload file", "Reconnect Moodle", "Check files", "No files yet")
