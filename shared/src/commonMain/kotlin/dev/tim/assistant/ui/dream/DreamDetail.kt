package dev.tim.assistant.ui.dream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.SystemBack
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.latestJob
import dev.tim.assistant.ui.lists.Dots
import dev.tim.assistant.ui.lists.checkinDots
import dev.tim.assistant.ui.rememberQuery
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One dream, read-first: date, mood, Lucid, tags, the full text and its interpretation; Edit opens the writer. */
@Composable
fun DreamDetail(services: AppServices, dreamId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val wide = LocalLayoutMode.current != LayoutMode.Narrow
    if (!wide) SystemBack(onBack = onBack)
    val scope = rememberCoroutineScope()
    val dream = rememberQuery(services, dreamId) { services.repo.dreamById(dreamId) }.value
    val job = rememberQuery(services, dreamId) { latestJob(services.repo.jobs(), "DREAM_INTERPRET", "dreamId", dreamId) }.value

    Column(if (wide) Modifier.fillMaxSize() else Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 18.dp else 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) IconButton(onClick = onBack) { BackIcon() }
            Text(dream?.let { formatIsoDate(it.localDate) } ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (dream != null) OutlinedButton(onClick = onEdit) { Text("Edit") }
        }
        if (dream == null) return@Column
        val tags = dream.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Dots(checkinDots(dream.mood))
                if (dream.lucid != 0L) Text("Lucid", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                for (tag in tags) Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(dream.text, style = MaterialTheme.typography.bodyLarge)
            val analysis = dream.analysis
            if (!analysis.isNullOrBlank()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Interpretation", style = MaterialTheme.typography.titleMedium)
                        Text(analysis, style = MaterialTheme.typography.bodyMedium)
                        dream.analysisModel?.let { Text("by $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Button(
                enabled = !jobIsActive(job),
                onClick = {
                    scope.launch {
                        queueInterpretOnce(services, dreamId)
                        services.syncInBackground()
                    }
                },
            ) { Text(if (analysis.isNullOrBlank()) "Interpret" else "Interpret again") }
            JobStatusLine(job)
        }
    }
}

/** Queues one DREAM_INTERPRET job for [dreamId] unless one is already QUEUED or RUNNING for it. */
suspend fun queueInterpretOnce(services: AppServices, dreamId: String) {
    if (jobIsActive(latestJob(services.repo.jobs(), "DREAM_INTERPRET", "dreamId", dreamId))) return
    services.repo.queueJob("DREAM_INTERPRET", buildJsonObject { put("dreamId", dreamId) }.toString())
}
