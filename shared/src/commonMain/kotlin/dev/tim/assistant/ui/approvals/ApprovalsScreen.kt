package dev.tim.assistant.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ai.tools.answerWithSources
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Research
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.rememberQuery
import kotlin.time.Instant
import kotlinx.coroutines.launch

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

private class ApprovalsData(val approvals: List<Approval>, val research: List<Research>, val limitFailureAt: Long?)

/** The Approvals section of Files: cards waiting for Tim, the history of decided ones, and the research memory. */
@Composable
fun ApprovalsSection(services: AppServices, modifier: Modifier = Modifier) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    val now = rememberNowMs()
    val data = rememberQuery(services) {
        ApprovalsData(repo.approvals(), repo.researchList(), repo.lastJobFailureAt(CODEX_JOB_KINDS, CODEX_LIMIT_TEXT))
    }.value

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (data == null) return@LazyColumn
        if (data.approvals.isEmpty()) {
            item(key = "none") {
                Text(
                    "Nothing is waiting. When Wally suggests something in Chat, you can approve it there or here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(orderedApprovals(data.approvals, now), key = { it.id }) { row -> Box(Modifier.animateItem()) { ApprovalCard(services, row, now) } }

        item(key = "research-title") { Text("Research", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
        researchBanner(data.limitFailureAt, now)?.let { text ->
            item(key = "research-banner") {
                Card(Modifier.fillMaxWidth()) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (data.research.isEmpty()) {
            item(key = "research-none") {
                Text(
                    "No saved research yet. Answers Wally looks up with Codex are kept here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(data.research, key = { it.id }) { row ->
            Card(Modifier.fillMaxWidth().animateItem()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.question, style = MaterialTheme.typography.titleSmall.merge(ContentDirection))
                    Text(answerWithSources(row), style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                    Text(
                        "Asked " + localDateOf(Instant.fromEpochMilliseconds(row.askedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { scope.launch { repo.deleteResearch(row.id); services.syncInBackground() } }) { Text("Delete") }
                }
            }
        }
    }
}
