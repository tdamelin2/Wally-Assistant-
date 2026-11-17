package dev.tim.assistant.ui.mail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import dev.tim.assistant.data.ApiResult
import dev.tim.assistant.data.MAIL_FORBIDDEN
import dev.tim.assistant.data.MAIL_GOOGLE_DOWN
import dev.tim.assistant.data.MAIL_NOT_FOUND
import dev.tim.assistant.data.MAIL_NOT_SET_UP
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.google.hhmm
import dev.tim.assistant.mail.draftCanDelete
import dev.tim.assistant.mail.draftStatusLine
import dev.tim.assistant.model.MailBody
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.JobStatusLine
import dev.tim.assistant.ui.LaptopColumns
import dev.tim.assistant.ui.LeaveGuardEffect
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.stacksSidePanel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** What the right pane of laptop Mail shows. */
private sealed interface MailPane {
    data object None : MailPane
    data class Read(val gmailId: String) : MailPane
    data class Draft(val id: String) : MailPane
}

/** How loading one mail body went. */
private sealed interface BodyState {
    data object Loading : BodyState
    data class Loaded(val body: MailBody) : BodyState
    data class Failed(val text: String) : BodyState
}

/** The plain-words text for a failed body load: a known mail failure, a server error, or (anything else) offline. */
internal fun mailBodyFailureText(message: String): String = when {
    message == MAIL_NOT_FOUND -> "This email was not found. It may have been moved or deleted."
    message == MAIL_FORBIDDEN || message == MAIL_GOOGLE_DOWN || message == MAIL_NOT_SET_UP -> message
    message.startsWith("HTTP ") -> "The server could not load this email ($message)."
    else -> "You appear to be offline, so this email could not be loaded."
}

/**
 * Laptop Mail: Wally's suggestions, unread mail and drafts on the left; the selected mail (loaded on demand from the
 * server) or the selected draft's editor on the right. A draft with unsaved edits asks before it is replaced.
 */
@Composable
internal fun MailLaptop(services: AppServices, data: MailData?, onDraftReply: (Mail_item) -> Unit) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var pane by remember { mutableStateOf<MailPane>(MailPane.None) }
    var draftDirty by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    LeaveGuardEffect(pane is MailPane.Draft && draftDirty)

    fun guarded(action: () -> Unit) {
        if (pane is MailPane.Draft && draftDirty) pendingSwitch = action else action()
    }

    val unread = data?.unread ?: emptyList()
    val drafts = data?.drafts ?: emptyList()
    val actions = data?.actions ?: emptyList()
    val today = localDateOf(Clock.System.now())

    val left: @Composable () -> Unit = {
        RefreshBox(services) {
            LazyColumn(Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "triage") {
                    Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            enabled = !jobIsActive(data?.triageJob),
                            onClick = {
                                scope.launch {
                                    repo.queueJob("MAIL_TRIAGE", "{}")
                                    services.syncInBackground()
                                }
                            },
                        ) { Text("Organise inbox") }
                        JobStatusLine(data?.triageJob)
                        JobStatusLine(data?.draftJob)
                    }
                }
                if (actions.isNotEmpty()) {
                    item(key = "actions-title") {
                        Text("Wally suggests · ${actions.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.animateItem())
                    }
                    items(actions, key = { "action-${it.id}" }) { a ->
                        Box(Modifier.animateItem()) { ActionRow(services, a, data?.subjects?.get(a.gmailId)) }
                    }
                }
                item(key = "unread-title") {
                    Text("Unread · ${unread.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.animateItem())
                }
                if (unread.isEmpty()) item(key = "unread-empty") { Text("No unread mail", modifier = Modifier.animateItem()) }
                items(unread, key = { "mail-${it.id}" }) { mail ->
                    val isOpen = (pane as? MailPane.Read)?.gmailId == mail.gmailId
                    LogCard(
                        Modifier.animateItem()
                            .then(if (isOpen) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { guarded { pane = MailPane.Read(mail.gmailId); draftDirty = false } },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                mail.sender,
                                style = MaterialTheme.typography.bodyMedium.merge(ContentDirection),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(mailTimeText(mail.receivedAt, today), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            mail.subject,
                            style = MaterialTheme.typography.bodySmall.merge(ContentDirection),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item(key = "drafts-title") {
                    Text("Drafts · ${drafts.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.animateItem())
                }
                if (drafts.isEmpty()) item(key = "drafts-empty") { Text("No drafts yet", modifier = Modifier.animateItem()) }
                items(drafts, key = { "draft-${it.id}" }) { d ->
                    val isOpen = (pane as? MailPane.Draft)?.id == d.id
                    LogCard(
                        Modifier.animateItem()
                            .then(if (isOpen) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { guarded { pane = MailPane.Draft(d.id); draftDirty = false } },
                    ) {
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
                                    if (isOpen) { pane = MailPane.None; draftDirty = false }
                                }) { Text("Delete") }
                            }
                        }
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
            val p = pane
            val mail = (p as? MailPane.Read)?.let { r -> unread.firstOrNull { it.gmailId == r.gmailId } }
            val draft = (p as? MailPane.Draft)?.let { d -> drafts.firstOrNull { it.id == d.id } }
            when {
                draft != null -> key(draft.id) {
                    DraftEditor(
                        services, draft,
                        originalSubject = draft.replyToGmailId?.let { data?.subjects?.get(it) },
                        onBack = { pane = MailPane.None; draftDirty = false },
                        onDirtyChange = { draftDirty = it },
                    )
                }
                mail != null -> key(mail.gmailId) { MailReadPane(services, mail, today, onDraftReply) }
                else -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Pick a mail or a draft to read it here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaptopColumns(stacksSidePanel(maxWidth.value), side = right) { left() }
    }

    pendingSwitch?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Discard your changes?") },
            confirmButton = { TextButton(onClick = { pendingSwitch = null; draftDirty = false; action() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("Keep editing") } },
        )
    }
}

/** The reading pane: subject, Draft reply, sender and time, and the body fetched from the server for this mail. */
@Composable
private fun MailReadPane(services: AppServices, mail: Mail_item, today: String, onDraftReply: (Mail_item) -> Unit) {
    val cfg = services.sync.serverConfig()
    var reload by remember { mutableIntStateOf(0) }
    // One load per mail and server setting; opening another mail cancels this one, so a late answer is never shown.
    var state by remember(mail.gmailId, cfg, reload) { mutableStateOf<BodyState>(BodyState.Loading) }
    LaunchedEffect(mail.gmailId, cfg, reload) {
        state = if (cfg == null) {
            BodyState.Failed("Set up the server in Settings to read mail here.")
        } else {
            when (val r = services.client.mailBody(cfg, mail.gmailId)) {
                is ApiResult.Ok -> BodyState.Loaded(r.value)
                ApiResult.Unauthorized -> BodyState.Failed("The server did not accept this device's token. Check Settings.")
                is ApiResult.Failure -> BodyState.Failed(mailBodyFailureText(r.message))
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                mail.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.titleLarge.merge(ContentDirection),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onDraftReply(mail) }) { Text("Draft reply") }
        }
        Text(
            "From ${mail.sender} · ${formatIsoDate(localDateOf(Instant.fromEpochMilliseconds(mail.receivedAt)))} ${hhmm(mail.receivedAt)}",
            style = MaterialTheme.typography.bodySmall.merge(ContentDirection),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        when (val s = state) {
            BodyState.Loading -> Text("Loading the email…", style = MaterialTheme.typography.bodyMedium)
            is BodyState.Loaded -> Text(s.body.text, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
            is BodyState.Failed -> {
                Text(s.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { reload++ }) { Text("Try again") }
            }
        }
    }
}

/** The time of a mail in the list: HH:mm for today, else MM-dd-yyyy. */
private fun mailTimeText(receivedAt: Long, today: String): String {
    val day = localDateOf(Instant.fromEpochMilliseconds(receivedAt))
    return if (day == today) hhmm(receivedAt) else formatIsoDate(day)
}

/** Texts of the laptop Mail screen, for the label-uniqueness check. */
internal val laptopMailLabels: List<String> = listOf(
    "Organise inbox", "Wally suggests", "Unread", "Drafts", "No unread mail", "No drafts yet", "Draft reply", "Try again",
    "Pick a mail or a draft to read it here", "Loading the email…", "Discard your changes?", "Keep editing",
)
