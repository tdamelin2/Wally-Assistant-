package dev.tim.assistant.ui.screentime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ai.jobs.parseAppLabelSuggestions
import dev.tim.assistant.db.App_rule
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.HoverActions
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.onHoverChange
import dev.tim.assistant.ui.SwipeRow
import dev.tim.assistant.ui.SystemBack
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.rememberQuery
import dev.tim.assistant.usage.DEVICE_LAPTOP
import dev.tim.assistant.usage.DEVICE_PHONE
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.appMatchType
import dev.tim.assistant.usage.last7Dates
import dev.tim.assistant.usage.normalizePattern
import dev.tim.assistant.usage.ruleId
import dev.tim.assistant.usage.unsortedApps
import kotlinx.coroutines.launch

private val PICKABLE = listOf(Label.PRODUCTIVE, Label.UNPRODUCTIVE, Label.NEUTRAL)
private const val UNSORTED_SHOWN = 30

internal const val LABEL_APPS_TITLE = "Label apps"
internal const val LABEL_NEEDS = "Apps without a label (last 7 days)"
internal const val LABEL_NONE_LEFT = "Every app has a label"
internal const val LABEL_SUGGESTIONS = "Suggestions"
internal const val LABEL_SUGGEST = "Suggest labels"
internal const val LABEL_RULES = "Rules"
internal const val LABEL_NO_RULES = "No rules yet"
internal const val LABEL_ACCEPT = "Accept"
internal const val LABEL_CHANGE = "Change"

/** Texts of the label editor, for the label-uniqueness check. */
internal val labelAppsLabels: List<String> = listOf(
    LABEL_APPS_TITLE, "Phone", "Laptop", LABEL_NEEDS, LABEL_NONE_LEFT, LABEL_SUGGESTIONS, LABEL_SUGGEST,
    LABEL_RULES, LABEL_NO_RULES, LABEL_ACCEPT, LABEL_CHANGE, "Productive", "Unproductive", "Neutral",
)

/**
 * The label editor behind the Screentime banner: one device at a time (Phone or Laptop), three buttons per
 * unlabelled app, label suggestions with Accept, and the rules with Change and swipe-left delete (Undo).
 */
@Composable
fun LabelAppsScreen(services: AppServices, usageAccessDevice: String, onBack: () -> Unit) {
    SystemBack(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { BackIcon() }
            Text(LABEL_APPS_TITLE, style = MaterialTheme.typography.titleLarge)
        }
        LabelAppsBody(services, usageAccessDevice, Modifier.weight(1f), panel = false)
    }
}

/**
 * The label editor itself. [panel] is the laptop side panel of Screentime: it has its own heading, the unlabelled
 * count line, and rules with a Change button and a hover Delete (still Undo-aware) instead of a swipe.
 */
@Composable
internal fun LabelAppsBody(services: AppServices, usageAccessDevice: String, modifier: Modifier = Modifier, panel: Boolean) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf(usageAccessDevice) }
    var changing by remember { mutableStateOf<App_rule?>(null) }
    val hidden by services.undo.hidden.collectAsState()

    val data = rememberQuery(services) {
        val dates = last7Dates(todayLocal())
        LabelData(dates, repo.usageSessions(dates.last(), dates.first()), repo.appRules(), repo.jobs().firstOrNull { it.kind == "APP_LABELS" })
    }.value

    Column(modifier.fillMaxSize()) {
        if (data == null) {
            Text("Loading…", Modifier.padding(16.dp))
            return@Column
        }
        val rules = data.rules.filter { !hidden.hides(UndoTables.APP_RULE, it.id) }
        val unsorted = unsortedApps(data.sessions, rules, device, data.dates)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (panel) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (panel) item(key = "panel-title") { Text(LABEL_APPS_TITLE, style = MaterialTheme.typography.titleLarge) }
            item(key = "devices") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DEVICE_PHONE, DEVICE_LAPTOP).forEach { d ->
                        FilterChip(selected = device == d, onClick = { device = d }, label = { Text(deviceText(d)) })
                    }
                }
            }
            item(key = "needs") {
                Text(if (panel) panelNeedsText(device, unsorted.size) else LABEL_NEEDS, style = MaterialTheme.typography.titleMedium)
            }
            if (unsorted.isEmpty()) item(key = "none") { Text(LABEL_NONE_LEFT, style = MaterialTheme.typography.bodyMedium) }
            items(unsorted.take(UNSORTED_SHOWN), key = { "unsorted:$device:${it.app}" }) { u ->
                Card(Modifier.fillMaxWidth().animateItem(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(u.app, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(msText(u.ms))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PICKABLE.forEach { label ->
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                    onClick = {
                                        scope.launch {
                                            repo.saveRule(appMatchType(device), u.app, label)
                                            services.syncInBackground()
                                        }
                                    },
                                ) { Text(labelButtonText(label), maxLines = 1) }
                            }
                        }
                    }
                }
            }

            item(key = "sug-head") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(LABEL_SUGGESTIONS, style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        enabled = !jobIsActive(data.labelsJob),
                        onClick = {
                            scope.launch {
                                repo.queueJob("APP_LABELS", "{}")
                                services.syncInBackground()
                            }
                        },
                    ) { Text(LABEL_SUGGEST) }
                    JobStatusLine(data.labelsJob)
                }
            }
            val suggestions = if (data.labelsJob?.status == "DONE") {
                val have = rules.map { it.id }.toSet()
                parseAppLabelSuggestions(data.labelsJob.resultJson)
                    .filter { it.device == device && ruleId(appMatchType(it.device), normalizePattern(it.app)) !in have }
            } else {
                emptyList()
            }
            items(suggestions, key = { "sug:${it.device}:${it.app}" }) { s ->
                Row(Modifier.fillMaxWidth().animateItem(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.app} → ${labelText(s.label)}", Modifier.weight(1f))
                    Button(onClick = {
                        scope.launch {
                            repo.acceptSuggestion(s.device, s.app, s.label)
                            services.syncInBackground()
                        }
                    }) { Text(LABEL_ACCEPT) }
                }
            }

            item(key = "rules-head") { Text(LABEL_RULES, style = MaterialTheme.typography.titleMedium) }
            if (rules.isEmpty()) item(key = "no-rules") { Text(LABEL_NO_RULES, style = MaterialTheme.typography.bodyMedium) }
            items(rules, key = { "rule:${it.id}" }) { rule ->
                val ruleCard: @Composable (Modifier) -> Unit = { cardModifier ->
                    var hovered by remember { mutableStateOf(false) }
                    Card(cardModifier.fillMaxWidth().onHoverChange { hovered = it }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(ruleText(rule), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                                Text(
                                    Label.entries.firstOrNull { it.name == rule.label }?.let { labelText(it) } ?: rule.label,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (panel) HoverActions(visible = hovered, onDelete = { services.undo.delete(UndoTables.APP_RULE, rule.id) })
                            TextButton(onClick = { changing = rule }) { Text(LABEL_CHANGE) }
                        }
                    }
                }
                if (panel) {
                    ruleCard(Modifier.animateItem())
                } else {
                    SwipeRow(onComplete = null, onDelete = { services.undo.delete(UndoTables.APP_RULE, rule.id) }, modifier = Modifier.animateItem()) {
                        ruleCard(Modifier)
                    }
                }
            }
        }
    }

    changing?.let { rule ->
        AlertDialog(
            onDismissRequest = { changing = null },
            title = { Text("Label ${rule.pattern}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PICKABLE.forEach { label ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                changing = null
                                scope.launch {
                                    repo.saveRule(rule.matchType, rule.pattern, label)
                                    services.syncInBackground()
                                }
                            },
                        ) { Text(labelText(label)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { changing = null }) { Text("Cancel") } },
        )
    }
}

/** The panel's count line, for example "Laptop apps not labelled yet · 12". */
internal fun panelNeedsText(device: String, count: Int): String = "${deviceText(device)} apps not labelled yet · $count"

private class LabelData(
    val dates: List<String>,
    val sessions: List<dev.tim.assistant.db.Usage_session>,
    val rules: List<App_rule>,
    val labelsJob: dev.tim.assistant.db.Ai_job?,
)

private fun labelButtonText(label: Label): String = when (label) {
    Label.PRODUCTIVE -> "Productive"
    Label.UNPRODUCTIVE -> "Unproductive"
    else -> "Neutral"
}
