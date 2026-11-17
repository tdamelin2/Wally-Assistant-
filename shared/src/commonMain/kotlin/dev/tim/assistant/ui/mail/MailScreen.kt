package dev.tim.assistant.ui.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import dev.tim.assistant.ui.RefreshBox
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Mail_action
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.mail.actionCanDelete
import dev.tim.assistant.mail.actionStatusLine
import dev.tim.assistant.mail.actionTitle
import dev.tim.assistant.mail.draftCanDelete
import dev.tim.assistant.mail.draftStatusLine
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.OnNewRequest
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.UnreadMailRow
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.rememberQuery
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

internal class MailData(
    val unread: List<Mail_item>,
    val drafts: List<Mail_draft>,
    val actions: List<Mail_action>,
    /** Subject by gmailId, for suggestion rows (unread mails still known locally). */
    val subjects: Map<String, String>,
    val draftJob: Ai_job?,
    val triageJob: Ai_job?,
)

/** The Mail tab: unread list with "Draft reply", "New email", "Organise inbox", drafts and suggestions. */
@Composable
fun MailScreen(services: AppServices, requestNew: Int = 0) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var openDraftId by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<Mail_item?>(null) }
    var newEmail by remember { mutableStateOf(false) }
    OnNewRequest(requestNew) { newEmail = true }

    val data = rememberQuery(services) {
        val jobs = repo.jobs()
        val unread = repo.unreadMail().sortedByDescending { it.receivedAt }
        MailData(
            unread = unread,
            drafts = repo.mailDrafts(),
            actions = repo.mailActions(),
            subjects = unread.associate { it.gmailId to it.subject },
            draftJob = jobs.firstOrNull { it.kind == "MAIL_DRAFT" },
            triageJob = jobs.firstOrNull { it.kind == "MAIL_TRIAGE" },
        )
    }.value

    val open = openDraftId?.let { id -> data?.drafts?.firstOrNull { it.id == id } }
    if (LocalLayoutMode.current == LayoutMode.Wide) {
        MailLaptop(services, data, onDraftReply = { replyTo = it })
    } else if (open != null) {
        DraftEditor(services, open, originalSubject = open.replyToGmailId?.let { data?.subjects?.get(it) }, onBack = { openDraftId = null })
    } else {
        RefreshBox(services) {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        Button(
                            enabled = !jobIsActive(data?.triageJob),
                            onClick = {
                                scope.launch {
                                    repo.queueJob("MAIL_TRIAGE", "{}")
                                    services.syncInBackground()
                                }
                            },
                        ) { Text("Organise inbox") }
                    }
                    JobStatusLine(data?.triageJob)
                    JobStatusLine(data?.draftJob)
                }
            }

            val actions = data?.actions ?: emptyList()
            if (actions.isNotEmpty()) {
                item(key = "actions-title") { Text("Wally suggests", style = MaterialTheme.typography.titleMedium, modifier = Modifier.animateItem()) }
                items(actions, key = { "action-${it.id}" }) { a -> Box(Modifier.animateItem()) { ActionRow(services, a, data?.subjects?.get(a.gmailId)) } }
            }

            item(key = "drafts-title") { Text("Drafts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.animateItem()) }
            val drafts = data?.drafts ?: emptyList()
            if (drafts.isEmpty()) item(key = "drafts-empty") { Text("No drafts yet", modifier = Modifier.animateItem()) }
            items(drafts, key = { "draft-${it.id}" }) { d ->
                LogCard(Modifier.animateItem().clickable { openDraftId = d.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.subject.ifBlank { "(no subject)" },
                            style = MaterialTheme.typography.bodyMedium.merge(ContentDirection),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "To: " + d.toAddr.ifBlank { "(not set)" },
                            style = MaterialTheme.typography.bodySmall.merge(ContentDirection),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(draftStatusLine(d.status, d.error), style = MaterialTheme.typography.labelMedium)
                    }
                    if (draftCanDelete(d.status)) {
                        TextButton(onClick = {
                            scope.launch {
                                repo.deleteMailDraft(d.id)
                                services.syncInBackground()
                            }
                        }) { Text("Delete") }
                    }
                }
                }
            }

            item(key = "unread-title") {
                Column(Modifier.animateItem()) {
                    HorizontalDivider(Modifier.padding(bottom = 8.dp))
                    Text("Unread mail", style = MaterialTheme.typography.titleMedium)
                }
            }
            val unread = data?.unread ?: emptyList()
            if (unread.isEmpty()) item(key = "unread-empty") { Text("No unread mail", modifier = Modifier.animateItem()) }
            items(unread, key = { "mail-${it.id}" }) { mail ->
                LogCard(Modifier.animateItem()) {
                    UnreadMailRow(mail) {
                        TextButton(onClick = { replyTo = mail }) { Text("Draft reply") }
                    }
                }
            }
        }
        }
    }

    replyTo?.let { mail ->
        InstructionDialog(
            title = "Draft a reply",
            hint = "What should the reply say?",
            onDismiss = { replyTo = null },
        ) { instruction ->
            replyTo = null
            scope.launch {
                repo.queueJob("MAIL_DRAFT", buildJsonObject { put("gmailId", mail.gmailId); put("instruction", instruction) }.toString())
                services.syncInBackground()
            }
        }
    }
    if (newEmail) {
        InstructionDialog(
            title = "New email",
            hint = "What is the email about?",
            onDismiss = { newEmail = false },
        ) { instruction ->
            newEmail = false
            scope.launch {
                repo.queueJob("MAIL_DRAFT", buildJsonObject { put("gmailId", JsonNull); put("instruction", instruction) }.toString())
                services.syncInBackground()
            }
        }
    }
}

@Composable
internal fun ActionRow(services: AppServices, action: Mail_action, subject: String?) {
    val scope = rememberCoroutineScope()
    val repo = services.repo
    LogCard {
        Text(actionTitle(action.action, action.labelName), style = MaterialTheme.typography.bodyMedium)
        Text(
            subject ?: "(mail no longer unread)",
            style = MaterialTheme.typography.bodySmall.merge(ContentDirection),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action.reason.isNotBlank()) Text(action.reason, style = MaterialTheme.typography.bodySmall.merge(ContentDirection))
        val line = actionStatusLine(action.status)
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action.status == "SUGGESTED") {
                Button(onClick = {
                    scope.launch {
                        repo.decideMailAction(action.id, true)
                        services.syncInBackground()
                    }
                }) { Text("Approve") }
                OutlinedButton(onClick = {
                    scope.launch {
                        repo.decideMailAction(action.id, false)
                        services.syncInBackground()
                    }
                }) { Text("Reject") }
            }
            if (actionCanDelete(action.status)) {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteMailAction(action.id)
                        services.syncInBackground()
                    }
                }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun InstructionDialog(title: String, hint: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            WallyTextField(
                multiline = true,
                value = text,
                onValueChange = { text = it },
                label = { Text(hint) },
                textStyle = ContentDirection,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOk(text.trim()) }) { Text("Write draft") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Headings and screen-level buttons of the Mail screen, for the label-uniqueness check. */
internal val mailLabels: List<String> = listOf(
    "Organise inbox", "Wally suggests", "Drafts", "No drafts yet", "Unread mail", "No unread mail",
)
