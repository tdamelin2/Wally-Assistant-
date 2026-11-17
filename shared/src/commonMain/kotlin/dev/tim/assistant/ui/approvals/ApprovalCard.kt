package dev.tim.assistant.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ai.tools.researchQueuedText
import dev.tim.assistant.ai.tools.researchJobReport
import dev.tim.assistant.data.APPROVAL_DONE
import dev.tim.assistant.data.APPROVAL_FAILED
import dev.tim.assistant.db.Approval
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.rememberQuery
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** The clock cards use for their expiry line; it ticks every 30 seconds while the card is on screen. */
@Composable
fun rememberNowMs(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/**
 * One approval: what Wally wants to do, Approve / Reject, the live expiry line and the result or error. Approve and
 * Reject go to the Wally server, which applies the change; offline or on a 409 nothing is written on this device and
 * the server's message shows. An expired card has both buttons disabled.
 */
@Composable
fun ApprovalCard(services: AppServices, row: Approval, now: Long, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember(row.id) { mutableStateOf(false) }
    var message by remember(row.id) { mutableStateOf<String?>(null) }
    val canDecide = approvalCanDecide(row, now)
    val job = if (row.tool == "research_question" && row.status == APPROVAL_DONE && row.resultText != null) {
        rememberQuery(services, row.resultText) { services.repo.jobById(row.resultText!!)?.let { it to researchJobReport(services.repo, it) } }.value
    } else {
        null
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.summary, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
            Text(
                approvalStatusLabel(row, now) + if (canDecide) " - " + approvalExpiryText(row, now) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val result = when {
                row.tool == "research_question" && row.status == APPROVAL_DONE ->
                    if (job != null) job.second ?: researchQueuedText(services.runsJobs) else row.resultText
                else -> row.resultText
            }
            if (!result.isNullOrBlank()) {
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall.merge(ContentDirection),
                    color = if (row.status == APPROVAL_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canDecide && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                message = services.chat.decideApproval(row.id, true).message
                                services.syncInBackground()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Approve") }
                OutlinedButton(
                    enabled = canDecide && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                message = services.chat.decideApproval(row.id, false).message
                                services.syncInBackground()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Reject") }
            }
        }
    }
}
