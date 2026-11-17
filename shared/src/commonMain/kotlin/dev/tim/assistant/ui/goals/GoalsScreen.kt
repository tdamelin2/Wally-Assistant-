package dev.tim.assistant.ui.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import dev.tim.assistant.ui.CheckOffBox
import dev.tim.assistant.ui.CheckOffTitle
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.SwipeRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberCheckOffState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.DreamWeekResult
import dev.tim.assistant.data.GoalProgress
import dev.tim.assistant.data.dreamWeekLine
import dev.tim.assistant.data.parseGoalSteps
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Goal
import dev.tim.assistant.db.Task
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.OnNewRequest
import dev.tim.assistant.ui.LaptopColumns
import dev.tim.assistant.ui.isLaptop
import dev.tim.assistant.ui.stacksSidePanel
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.FormTarget
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.DateField
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.forms.isValidDate
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.latestJob
import dev.tim.assistant.ui.rememberQuery
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class GoalRow(val goal: Goal, val progress: GoalProgress)

private class GoalsData(val rows: List<GoalRow>, val weekJob: Ai_job?, val weekDocTitle: String?, val stepJobs: List<Ai_job>)

private val STATUSES = listOf("ACTIVE", "DONE", "DROPPED")

/** Goals list; opening one shows its detail (progress, linked tasks, Plan steps). */
@Composable
fun GoalsScreen(services: AppServices, requestNew: Int = 0, onOpenForm: (FormTarget) -> Unit) {
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var creating by remember { mutableStateOf(false) }
    OnNewRequest(requestNew) { creating = true }
    var dreamWeek by remember { mutableStateOf<DreamWeekResult?>(null) }
    val weekLine = dreamWeekLine(dreamWeek)
    val scope = rememberCoroutineScope()
    val repo = services.repo
    // One check when the screen opens.
    LaunchedEffect(Unit) {
        val cfg = services.sync.serverConfig()
        dreamWeek = if (cfg == null) DreamWeekResult.Unavailable("no server") else services.client.dreamWeek(cfg)
    }

    val data = rememberQuery(services) {
        val job = latestJobOfKind(repo.jobs(), "IDEAL_WEEK")
        val docId = if (job?.status == "DONE") docIdOf(job.resultJson) else null
        GoalsData(
            repo.goals().map { GoalRow(it, repo.goalProgress(it.id)) },
            job,
            docId?.let { repo.docById(it)?.title },
            repo.jobs().filter { it.kind == "GOAL_STEPS" },
        )
    }.value

    val current = openId?.let { id -> data?.rows?.firstOrNull { it.goal.id == id } }
    val goalList: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rows = data?.rows ?: emptyList()
            RefreshBox(services, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "dream-week") {
                    LogCard(Modifier.animateItem()) {
                        Text("Dream week", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(
                            enabled = !jobIsActive(data?.weekJob),
                            onClick = {
                                scope.launch {
                                    repo.queueJob("IDEAL_WEEK", "{}")
                                    services.syncInBackground()
                                }
                            },
                        ) { Text(if (isLaptop) "Coach me" else "Coach me on my Dream week") }
                        if (weekLine.isNotEmpty()) Text(weekLine, style = MaterialTheme.typography.bodySmall)
                        JobStatusLine(data?.weekJob)
                        data?.weekDocTitle?.let { Text("Saved as \"$it\" in Files > Plans.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (rows.isEmpty()) item(key = "empty") { Text("No goals yet", modifier = Modifier.animateItem()) }
                items(rows, key = { it.goal.id }) { row ->
                    LogCard(Modifier.animateItem().clickable { openId = row.goal.id }) {
                        Text(row.goal.title, style = MaterialTheme.typography.titleMedium)
                        if (isLaptop) row.goal.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                        val meta = listOfNotNull(
                            row.goal.status.takeIf { it != "ACTIVE" }?.lowercase(),
                            row.goal.deadline?.let { "by ${formatIsoDate(it)}" },
                            if (isLaptop) laptopStepsText(row.progress.done, row.progress.total) else "${row.progress.done} / ${row.progress.total} tasks",
                        ).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.bodySmall)
                        if (row.progress.total > 0) {
                            LinearProgressIndicator(progress = { row.progress.done.toFloat() / row.progress.total }, modifier = Modifier.fillMaxWidth())
                        } else if (row.goal.status == "ACTIVE") {
                            OutlinedButton(
                                enabled = !jobIsActive(latestJob(data?.stepJobs ?: emptyList(), "GOAL_STEPS", "goalId", row.goal.id)),
                                onClick = {
                                    scope.launch {
                                        repo.queueJob("GOAL_STEPS", buildJsonObject { put("goalId", row.goal.id) }.toString())
                                        openId = row.goal.id
                                        services.syncInBackground()
                                    }
                                },
                            ) { Text("Plan steps") }
                        }
                    }
                }
            }
            }
        }
    }
    if (isLaptop) {
        val selected = current ?: data?.rows?.firstOrNull()
        val side: (@Composable () -> Unit)? = if (selected == null) null else ({
            androidx.compose.runtime.key(selected.goal.id) {
                GoalDetail(services, selected.goal, selected.progress, onOpenForm, onBack = { openId = null }, onEdit = { editing = selected.goal }, embedded = true)
            }
        })
        BoxWithConstraints(Modifier.fillMaxSize()) { LaptopColumns(stacksSidePanel(maxWidth.value), side) { goalList() } }
    } else if (current != null) {
        GoalDetail(services, current.goal, current.progress, onOpenForm, onBack = { openId = null }, onEdit = { editing = current.goal })
    } else {
        goalList()
    }

    if (creating) {
        GoalDialog(null, onDismiss = { creating = false }) { title, why, deadline, status ->
            creating = false
            scope.launch {
                openId = repo.saveGoal(null, title, why, deadline, status)
                services.syncInBackground()
            }
        }
    }
    editing?.let { goal ->
        GoalDialog(goal, onDismiss = { editing = null }) { title, why, deadline, status ->
            editing = null
            scope.launch {
                repo.saveGoal(goal.id, title, why, deadline, status)
                services.syncInBackground()
            }
        }
    }
}

private fun latestJobOfKind(jobs: List<Ai_job>, kind: String): Ai_job? = jobs.firstOrNull { it.kind == kind }

private fun docIdOf(resultJson: String?): String? =
    try {
        ((Json.parseToJsonElement(resultJson ?: return null) as? JsonObject)?.get("docId") as? JsonPrimitive)?.content
    } catch (e: Exception) {
        null
    }

@Composable
private fun GoalDetail(
    services: AppServices,
    goal: Goal,
    progress: GoalProgress,
    onOpenForm: (FormTarget) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    embedded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val repo = services.repo
    var confirmDelete by remember { mutableStateOf(false) }
    var addedMessage by remember { mutableStateOf<String?>(null) }
    var unchecked by remember { mutableStateOf(setOf<Int>()) }

    val detail = rememberQuery(services, goal.id) {
        Pair(repo.tasksForGoal(goal.id), latestJob(repo.jobs(), "GOAL_STEPS", "goalId", goal.id))
    }.value
    val hidden by services.undo.hidden.collectAsState()
    val tasks: List<Task> = detail?.first ?: emptyList()
    val shownTasks = tasks.filter { !hidden.hides(UndoTables.TASK, it.id) }
    val job = detail?.second
    val steps = if (job?.status == "DONE") parseGoalSteps(job.resultJson) else emptyList()

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!embedded) IconButton(onClick = onBack) { BackIcon() }
            Text(goal.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        RefreshBox(services, Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "summary") {
                Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    goal.why?.let { Text(it) }
                    Text(
                        listOfNotNull(goal.status.lowercase(), goal.deadline?.let { "by ${formatIsoDate(it)}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (embedded) "Steps · ${progress.done} of ${progress.total} done" else "${progress.done} of ${progress.total} tasks done",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (progress.total > 0) {
                        LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(
                        enabled = !jobIsActive(job) && goal.status == "ACTIVE",
                        onClick = {
                            scope.launch {
                                repo.queueJob("GOAL_STEPS", buildJsonObject { put("goalId", goal.id) }.toString())
                                addedMessage = null
                                unchecked = emptySet()
                                services.syncInBackground()
                            }
                        },
                    ) { Text(if (embedded && progress.total > 0) "Plan more steps" else "Plan steps") }
                    JobStatusLine(job)
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
            }
            if (steps.isNotEmpty() && job != null) {
                item(key = "steps") {
                    Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Suggested steps", style = MaterialTheme.typography.titleSmall)
                        steps.forEachIndexed { index, step ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = index !in unchecked,
                                    onCheckedChange = { on -> unchecked = if (on) unchecked - index else unchecked + index },
                                )
                                Text(step.title + (step.dueDate?.let { "  (${formatIsoDate(it)})" } ?: ""))
                            }
                        }
                        Button(
                            enabled = unchecked.size < steps.size,
                            onClick = {
                                val selected = steps.indices.filter { it !in unchecked }.toSet()
                                scope.launch {
                                    val n = repo.addGoalStepTasks(job.id, goal.id, steps, selected)
                                    addedMessage = if (n == 0) "Nothing new to add." else "Added $n task${if (n == 1) "" else "s"}."
                                    services.syncInBackground()
                                }
                            },
                        ) { Text("Add selected as tasks") }
                        addedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (shownTasks.isEmpty()) item(key = "no-tasks") { Text("No tasks linked yet. Link a task to this goal in its Edit form.", modifier = Modifier.animateItem()) }
            items(shownTasks, key = { it.id }) { t ->
                val off = rememberCheckOffState(services.scope)
                SwipeRow(
                    onComplete = if (t.done == 0L) ({ services.undo.complete(t.id) }) else null,
                    onDelete = { services.undo.delete(UndoTables.TASK, t.id) },
                    modifier = Modifier.animateItem(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenForm(FormTarget.Task(t)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckOffBox(
                            checked = t.done != 0L,
                            state = off,
                            onChecked = { services.undo.complete(t.id) },
                            onUnchecked = {
                                scope.launch {
                                    repo.setTaskDone(t.id, false)
                                    services.syncInBackground()
                                }
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            CheckOffTitle(t.title, off, struck = t.done != 0L)
                            t.dueDate?.let { Text("Due ${formatIsoDate(it)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this goal?") },
            text = { Text("Its tasks are kept; they just stop being linked to a goal that exists.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteGoal(goal.id)
                        services.syncInBackground()
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GoalDialog(existing: Goal?, onDismiss: () -> Unit, onSave: (String, String?, String?, String) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var why by remember { mutableStateOf(existing?.why ?: "") }
    var deadline by remember { mutableStateOf(existing?.deadline ?: "") }
    var status by remember { mutableStateOf(existing?.status ?: "ACTIVE") }
    val titleOk = title.trim().length in 1..200
    var deadlineValid by remember { mutableStateOf(true) }
    val dateOk = deadlineValid && (deadline.isBlank() || isValidDate(deadline))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New goal" else "Edit goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WallyTextField(multiline = false, value = title, onValueChange = { title = it }, label = { Text("Goal") }, singleLine = true, isError = title.isNotEmpty() && !titleOk)
                WallyTextField(multiline = true, value = why, onValueChange = { why = it }, label = { Text("Why it matters (optional)") })
                DateField("Deadline (optional)", deadline, { deadline = it }, optional = true, onValidChange = { deadlineValid = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    STATUSES.forEach { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s.lowercase()) }) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = titleOk && dateOk,
                onClick = { onSave(title.trim(), why.trim().ifBlank { null }, deadline.trim().ifBlank { null }, status) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Headings and screen-level buttons of the Goals screen, for the label-uniqueness check. */
internal val goalsLabels: List<String> = listOf("New goal", "Dream week", "Coach me on my Dream week", "No goals yet")

/** "2 of 5 steps", or "No steps yet" when the goal has none. */
internal fun laptopStepsText(done: Int, total: Int): String = if (total == 0) "No steps yet" else "$done of $total steps"

/** Laptop Goals: the card list on the left and the selected goal on the right. */
internal val laptopGoalsLabels: List<String> = listOf("Dream week", "Coach me", "No goals yet", "Plan steps")
internal val laptopGoalDetailLabels: List<String> = listOf("Edit", "Delete", "Plan more steps", "Suggested steps", "Add selected as tasks")
