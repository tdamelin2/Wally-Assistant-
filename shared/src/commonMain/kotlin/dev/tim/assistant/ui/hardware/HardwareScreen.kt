package dev.tim.assistant.ui.hardware

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.dateTimeLabel
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Hw_check
import dev.tim.assistant.hardware.HardwareCheckRunner
import dev.tim.assistant.hardware.hardwareSections
import dev.tim.assistant.hardware.hwExplainText
import dev.tim.assistant.hardware.hwTakenAt
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.latestJob
import dev.tim.assistant.ui.rememberQuery
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** Shown when the laptop check itself fails to run at all. */
const val HW_RUN_FAILED = "The check could not run"

/** Runs one laptop check and stores it; throws when the check cannot run (the caller shows [HW_RUN_FAILED]). */
internal suspend fun runHardwareCheck(services: AppServices, runner: HardwareCheckRunner) {
    val outcome = runner.run()
    val takenAt = hwTakenAt(outcome.resultJson) ?: Clock.System.now().toEpochMilliseconds()
    services.repo.saveHwCheck(takenAt, outcome.resultJson, outcome.summary)
    services.syncInBackground()
}

/**
 * The Hardware tab. [runner] is null on the phone: it shows the saved checks and answers, and Explain only queues a job
 * that the laptop runs.
 */
@Composable
fun HardwareScreen(services: AppServices, runner: HardwareCheckRunner?) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var openId by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }

    if (LocalLayoutMode.current == LayoutMode.Wide) {
        HardwareLaptop(services, runner)
        return
    }
    val checks = rememberQuery(services) { repo.hwChecks() }.value ?: emptyList()
    val open = openId?.let { id -> checks.firstOrNull { it.id == id } }
    if (open != null) {
        CheckDetail(services, open, onBack = { openId = null })
        return
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (runner != null) {
                Button(
                    enabled = !running,
                    onClick = {
                        scope.launch {
                            running = true
                            runError = null
                            try {
                                runHardwareCheck(services, runner)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                runError = HW_RUN_FAILED
                            } finally {
                                running = false
                            }
                        }
                    },
                ) { Text(if (running) "Checking…" else "Run check") }
            }
        }
        if (runner == null) {
            Text("Checks run on the laptop. This shows the saved checks.", style = MaterialTheme.typography.bodySmall)
        }
        runError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checks.isNotEmpty()) item(key = "explain") { LogCard(Modifier.animateItem()) { ExplainBox(services, checks.first()) } }
            item(key = "past-head") { Text("Past checks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.animateItem()) }
            if (checks.isEmpty()) item(key = "empty") { Text("No checks yet", modifier = Modifier.animateItem()) }
            items(checks, key = { it.id }) { check ->
                LogCard(Modifier.animateItem().clickable { openId = check.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dateTimeLabel(check.takenAt), style = MaterialTheme.typography.titleSmall)
                        Text(check.summary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            repo.deleteHwCheck(check.id)
                            services.syncInBackground()
                        }
                    }) { Text("Delete") }
                }
                }
            }
        }
    }
}

@Composable
private fun CheckDetail(services: AppServices, check: Hw_check, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { BackIcon() }
                Text(dateTimeLabel(check.takenAt), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
        }
        items(hardwareSections(check.resultJson), key = { "section-${it.title}" }) { section ->
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall)
                section.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall.merge(ContentDirection)) }
            }
        }
        item { HorizontalDivider() }
        item { ExplainBox(services, check) }
    }
}

/** "What is going wrong?" with Explain: queues an HW_EXPLAIN job for [check] and shows the saved answer. */
@Composable
private fun ExplainBox(services: AppServices, check: Hw_check) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var problem by remember(check.id) { mutableStateOf("") }
    val job: Ai_job? = rememberQuery(services, check.id) { latestJob(repo.jobs(), "HW_EXPLAIN", "checkId", check.id) }.value
    val answer = if (job?.status == "DONE") hwExplainText(job.resultJson) else null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WallyTextField(
            multiline = true,
            value = problem,
            onValueChange = { problem = it },
            label = { Text("What is going wrong?") },
            textStyle = ContentDirection,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = problem.isNotBlank() && !jobIsActive(job),
            onClick = {
                val text = problem.trim()
                scope.launch {
                    repo.queueJob(
                        "HW_EXPLAIN",
                        buildJsonObject { put("checkId", check.id); put("problem", text) }.toString(),
                    )
                    services.syncInBackground()
                }
            },
        ) { Text("Explain") }
        JobStatusLine(job)
        answer?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection)) }
    }
}

/** Headings and screen-level buttons of the Hardware screen, for the label-uniqueness check. */
internal val hardwareLabels: List<String> = listOf("Run check", "No checks yet", "What is going wrong?", "Explain", "Past checks", "Delete")
