package dev.tim.assistant.ui.hardware

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.LaptopColumns
import dev.tim.assistant.ui.forms.WallyTextField
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.latestJob
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.rememberQuery
import dev.tim.assistant.ui.stacksSidePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/**
 * Laptop Hardware: on the left the "What is going wrong?" form (for the selected check), Explain and the past checks;
 * on the right the selected check's explanation in full, its details and Delete.
 */
@Composable
internal fun HardwareLaptop(services: AppServices, runner: HardwareCheckRunner?) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    val checks = rememberQuery(services) { repo.hwChecks() }.value ?: emptyList()
    // Nothing picked (or the picked check is gone): the newest check is shown.
    val selected: Hw_check? = checks.firstOrNull { it.id == selectedId } ?: checks.firstOrNull()

    val left: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runner != null) {
                Button(
                    enabled = !running,
                    onClick = {
                        scope.launch {
                            running = true
                            runError = null
                            try {
                                runHardwareCheck(services, runner)
                                selectedId = null
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
            runError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (selected != null) {
                LogCard { ExplainForm(services, selected) }
            }
            Text("Past checks", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (checks.isEmpty()) item(key = "empty") { Text("No checks yet", modifier = Modifier.animateItem()) }
                items(checks, key = { it.id }) { check ->
                    val isOpen = selected?.id == check.id
                    LogCard(
                        Modifier.animateItem()
                            .then(if (isOpen) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { selectedId = check.id },
                    ) {
                        Text(dateTimeLabel(check.takenAt), style = MaterialTheme.typography.titleSmall)
                        Text(check.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    val right: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (selected == null) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (runner == null) "Checks run on the laptop. This shows the saved checks." else "Run a check to see it here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                CheckPane(services, selected, onDeleted = { selectedId = null })
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaptopColumns(stacksSidePanel(maxWidth.value), side = right) { left() }
    }
}

/** The right column: one check's explanation in full, its details and Delete (the explain job is found by this check's id). */
@Composable
private fun CheckPane(services: AppServices, check: Hw_check, onDeleted: () -> Unit) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    val job: Ai_job? = rememberQuery(services, check.id) { latestJob(repo.jobs(), "HW_EXPLAIN", "checkId", check.id) }.value
    val answer = if (job?.status == "DONE") hwExplainText(job.resultJson) else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dateTimeLabel(check.takenAt), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    repo.deleteHwCheck(check.id)
                    services.syncInBackground()
                    onDeleted()
                }
            }) { Text("Delete") }
        }
        Text(check.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        JobStatusLine(job)
        if (answer != null) {
            Text(answer, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
        } else if (job == null) {
            Text("No explanation yet. Write what is going wrong and press Explain.", style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
        hardwareSections(check.resultJson).forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall)
                section.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall.merge(ContentDirection)) }
            }
        }
    }
}

/** "What is going wrong?" with Explain: queues an HW_EXPLAIN job for [check]. */
@Composable
private fun ExplainForm(services: AppServices, check: Hw_check) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var problem by remember(check.id) { mutableStateOf("") }
    val job: Ai_job? = rememberQuery(services, check.id) { latestJob(repo.jobs(), "HW_EXPLAIN", "checkId", check.id) }.value
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
    }
}
