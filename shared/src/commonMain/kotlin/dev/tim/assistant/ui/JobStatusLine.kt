package dev.tim.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.tim.assistant.db.Ai_job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What to show for [job]: queued, working, failed with its error, or null (no job, or finished). */
fun jobStatusText(job: Ai_job?): String? = when (job?.status) {
    "QUEUED" -> "Queued — runs when Wally is open on the laptop"
    "RUNNING" -> "Working…"
    "FAILED" -> "Failed: ${job.error ?: "unknown error"}"
    else -> null
}

/** True while a job is queued or running (a button that starts another one should be disabled). */
fun jobIsActive(job: Ai_job?): Boolean = job?.status == "QUEUED" || job?.status == "RUNNING"

/** The newest job of [kind] whose input has the string field [key] equal to [value]; [jobs] is newest first. */
fun latestJob(jobs: List<Ai_job>, kind: String, key: String, value: String): Ai_job? =
    jobs.firstOrNull { it.kind == kind && inputField(it.inputJson, key) == value }

private fun inputField(inputJson: String, key: String): String? =
    try {
        (Json.parseToJsonElement(inputJson) as? JsonObject)?.get(key)
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    } catch (e: Exception) {
        null
    }

/** One small line under a button showing [jobStatusText]; nothing when there is nothing to say. */
@Composable
fun JobStatusLine(job: Ai_job?, modifier: Modifier = Modifier) {
    val text = jobStatusText(job) ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (job?.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
