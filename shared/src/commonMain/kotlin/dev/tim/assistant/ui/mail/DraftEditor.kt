package dev.tim.assistant.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.mail.canSendDraft
import dev.tim.assistant.mail.draftIsEditable
import dev.tim.assistant.mail.draftStatusLine
import dev.tim.assistant.mail.isValidRecipient
import dev.tim.assistant.mail.startsNewConversation
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.rememberQuery
import kotlinx.coroutines.launch

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** Note shown when a reply's subject no longer matches the original. */
const val NEW_CONVERSATION_NOTE = "Changed subject: sends as a new conversation"

/**
 * Edits one draft. Only a DRAFT or FAILED draft is editable; Send stores APPROVED and syncs (the server sends it once).
 * SENDING, SENT and UNKNOWN drafts are read-only and follow the stored row as the server updates it.
 */
@Composable
fun DraftEditor(
    services: AppServices,
    initial: Mail_draft,
    originalSubject: String?,
    onBack: () -> Unit,
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    val live = rememberQuery(services, initial.id) { repo.mailDraftById(initial.id) }.value ?: initial
    var to by remember(initial.id) { mutableStateOf(initial.toAddr) }
    var subject by remember(initial.id) { mutableStateOf(initial.subject) }
    var body by remember(initial.id) { mutableStateOf(initial.body) }
    var message by remember(initial.id) { mutableStateOf<String?>(null) }
    val editable = draftIsEditable(live.status)
    // What is stored (the opened draft, then the last successful Save): the editor is dirty when the fields differ from it.
    var savedTo by remember(initial.id) { mutableStateOf(initial.toAddr) }
    var savedSubject by remember(initial.id) { mutableStateOf(initial.subject) }
    var savedBody by remember(initial.id) { mutableStateOf(initial.body) }
    val dirty = editable && (to != savedTo || subject != savedSubject || body != savedBody)
    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { BackIcon() }
            Text("Draft", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text(draftStatusLine(live.status, live.error), style = MaterialTheme.typography.labelLarge)
        if (editable) {
            WallyTextField(
                multiline = false,
                value = to,
                onValueChange = { to = it },
                label = { Text("To") },
                singleLine = true,
                isError = to.isNotBlank() && !isValidRecipient(to),
                textStyle = ContentDirection,
                modifier = Modifier.fillMaxWidth(),
            )
            WallyTextField(
                multiline = false,
                value = subject,
                onValueChange = { subject = it.replace("\r", "").replace("\n", " ") },
                label = { Text("Subject") },
                singleLine = true,
                textStyle = ContentDirection,
                modifier = Modifier.fillMaxWidth(),
            )
            if (live.replyToGmailId != null && originalSubject != null && startsNewConversation(subject, originalSubject)) {
                Text(NEW_CONVERSATION_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            WallyTextField(
                multiline = true,
                value = body,
                onValueChange = { body = it },
                label = { Text("Body") },
                minLines = 8,
                textStyle = ContentDirection,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val saved = repo.saveMailDraft(initial.id, to, subject, body, send = false)
                        if (saved) { savedTo = to; savedSubject = subject; savedBody = body }
                        message = if (saved) "Saved" else "This draft can no longer be changed"
                        services.syncInBackground()
                    }
                }) { Text("Save") }
                Button(
                    enabled = canSendDraft(to, subject, body),
                    onClick = {
                        scope.launch {
                            if (repo.saveMailDraft(initial.id, to, subject, body, send = true)) {
                                savedTo = to; savedSubject = subject; savedBody = body
                                services.syncInBackground()
                                onBack()
                            } else {
                                message = "This draft can no longer be changed"
                            }
                        }
                    },
                ) { Text(if (live.status == "FAILED") "Send again" else "Send") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("To: " + live.toAddr, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
            Text(live.subject, style = MaterialTheme.typography.titleMedium.merge(ContentDirection))
            Text(live.body, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
        }
    }
}
