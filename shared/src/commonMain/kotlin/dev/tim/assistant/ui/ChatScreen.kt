package dev.tim.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import dev.tim.assistant.generated.resources.ic_chevron_left
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tim.assistant.ai.ChatController
import dev.tim.assistant.generated.resources.Res
import dev.tim.assistant.generated.resources.ic_edit
import dev.tim.assistant.generated.resources.ic_menu
import org.jetbrains.compose.resources.vectorResource
import dev.tim.assistant.model.ChatSummaryDto
import dev.tim.assistant.model.MemoryNoteDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Chat tab. The conversation lives on the HP (services.chat is the process-owned client): Wally finds things by name,
 * adds small things at once with an Undo, asks Yes/No for bigger changes. Phone: one chat with a slide-in list and a Memory
 * screen; laptop: the list is a column next to the chat (Ctrl+B hides it).
 */
@Composable
fun ChatScreen(services: AppServices) {
    val chat = services.chat
    val state by chat.state.collectAsState()
    LaunchedEffect(Unit) { chat.refresh() }
    // Polling for a Codex answer runs only while this screen is shown.
    DisposableEffect(chat) {
        chat.setChatVisible(true)
        onDispose { chat.setChatVisible(false) }
    }
    // A notice (an archived chat, a refused action) stays a few seconds, then goes.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_MS)
            chat.clearNotice()
        }
    }
    if (LocalLayoutMode.current == LayoutMode.Wide) LaptopChat(chat, state) else PhoneChat(chat, state)
}

private const val NOTICE_MS = 6_000L

/** Texts of the phone conversation screen, for the label-uniqueness check (the list drawer and Memory have their own lists). */
internal val chatLabels: List<String> = listOf("Wally", CHAT_OPEN_LIST_LABEL, "New chat", CHAT_PHONE_PLACEHOLDER, "Send", CHAT_ASK_MODEL_LABEL, CHAT_ASK_CODEX_LABEL, "Undo", "Yes", "No", "From", "To", CHAT_OFFLINE_BANNER, "Waiting to send", SLOW_REPLY_HINT)

/** The phone chat-list drawer (it covers the whole shell) and the delete confirmation; the laptop column has its own lists. */
internal val chatListLabels: List<String> = listOf("Chats", "Pinned", "New chat", "Memory", CHAT_LIST_FOOTNOTE, "Delete this chat?", CHAT_DELETE_TEXT, "Delete", "Cancel")

/** Memory screen (phone) and pane (laptop). */
internal val chatMemoryLabels: List<String> = listOf("Memory", "Back", CHAT_MEMORY_BACK_LABEL, CHAT_MEMORY_INTRO, "Summarizing…", "Nothing remembered yet")

internal const val CHAT_ASK_MODEL_LABEL = "Ask model"
internal const val CHAT_ASK_CODEX_LABEL = "Ask Codex"
internal const val CHAT_OPEN_LIST_LABEL = "Open chat list"
internal const val CHAT_MEMORY_BACK_LABEL = "Back to chats"
internal const val CHAT_PHONE_PLACEHOLDER = "Message Wally…"
internal const val CHAT_OFFLINE_BANNER = "Can't reach Wally right now. Your message will send when you're back online."
internal const val CHAT_LIST_FOOTNOTE = "When you start an 11th chat, the oldest is summarized into Memory."
internal const val CHAT_DELETE_TEXT = "Wally will remember a summary of it in Memory."
internal const val CHAT_MEMORY_INTRO = "What Wally remembers from older chats. When a chat is archived or deleted, it's summarized here and Wally uses it in new chats."

@Composable
private fun PhoneChat(chat: ChatController, state: dev.tim.assistant.ai.ChatUiState) {
    val ui = LocalPhoneChatUi.current
    val now = rememberSecondClock()

    if (ui.memory) {
        SystemBack { ui.memory = false }
        MemoryPane(chat, state, now, onBack = { ui.memory = false }, phoneStyle = true)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            val title = state.chats.firstOrNull { it.id == state.currentId }?.title
            // D2-D5: 48 dp icon buttons, the name left-aligned beside the menu icon, a 1 dp line under the header.
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ui.drawer = true }) { Icon(vectorResource(Res.drawable.ic_menu), contentDescription = CHAT_OPEN_LIST_LABEL) }
                Column(Modifier.weight(1f)) {
                    Text(title ?: "Wally", fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (title != null) Text("Wally", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { chat.newChatInBackground() }) { Icon(vectorResource(Res.drawable.ic_edit), contentDescription = "New chat") }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ChatConversation(chat, state, now, enterSends = false, Modifier.weight(1f).padding(horizontal = 12.dp), phoneStyle = true)
        }
    }
}

/** What the phone Chat tab shares with the shell: the list drawer, the Memory screen and the delete question. */
class PhoneChatUi {
    var drawer by mutableStateOf(false)
    var memory by mutableStateOf(false)
    var deleting by mutableStateOf<ChatSummaryDto?>(null)
}

internal val LocalPhoneChatUi = staticCompositionLocalOf { PhoneChatUi() }

/**
 * The phone chat-list drawer (D9-D10), drawn by NarrowShell above the whole shell (bottom bar included) so the scrim covers
 * everything. Back and a tap on the scrim close it; the delete question lives here too.
 */
@Composable
fun PhoneChatDrawer(services: AppServices, ui: PhoneChatUi) {
    val chat = services.chat
    val state by chat.state.collectAsState()
    val now = rememberSecondClock()
    val scope = rememberCoroutineScope()
    SystemBack(enabled = ui.drawer) { ui.drawer = false }
    ui.deleting?.let { target ->
        DeleteChatDialog(onConfirm = { ui.deleting = null; scope.launch { chat.delete(target.id) } }, onDismiss = { ui.deleting = null })
    }
    if (!ui.drawer) return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(WallyColors.drawerScrim).clickable(interactionSource = null, indication = null) { ui.drawer = false })
        Surface(
            Modifier.fillMaxHeight().width(320.dp),
            shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            ChatListPanel(
                state = state,
                now = now,
                newAtTop = false,
                onSelect = { ui.drawer = false; chat.selectInBackground(it) },
                onNew = { ui.drawer = false; chat.newChatInBackground() },
                onMemory = { ui.drawer = false; ui.memory = true },
                onDelete = { ui.deleting = it },
                onHide = null,
                phoneStyle = true,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        }
    }
}

@Composable
internal fun DeleteChatDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this chat?") },
        text = { Text(CHAT_DELETE_TEXT) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The older chats Wally summarized: a pending note reads "Summarizing…"; tap a note to read all of it. */
@Composable
internal fun MemoryPane(chat: ChatController, state: dev.tim.assistant.ai.ChatUiState, now: Long, onBack: (() -> Unit)?, phoneStyle: Boolean = false) {
    LaunchedEffect(Unit) { chat.loadMemory() }
    // A pending note turns into its summary: look again every few seconds while any is pending.
    val anyPending = state.memory.any { it.pending }
    LaunchedEffect(anyPending) {
        while (anyPending) {
            delay(MEMORY_POLL_MS)
            chat.loadMemory()
        }
    }
    if (phoneStyle) {
        // D27-D30: the frame's Memory screen: 48 dp chevron "Back to chats" + 20 sp title, 13 sp intro, white 16 dp note cards.
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onBack?.invoke() }) { Icon(vectorResource(Res.drawable.ic_chevron_left), contentDescription = CHAT_MEMORY_BACK_LABEL) }
                Text("Memory", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "intro") {
                    Text(CHAT_MEMORY_INTRO, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp))
                }
                if (state.memoryLoaded && state.memory.isEmpty()) {
                    item(key = "empty") { Text("Nothing remembered yet", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
                items(state.memory, key = { it.id }) { note -> PhoneMemoryRow(note, now) }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
            Text("Memory", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = if (onBack == null) 4.dp else 0.dp))
        }
        Text(CHAT_MEMORY_INTRO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.memoryLoaded && state.memory.isEmpty()) {
            Text("Nothing remembered yet", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.memory, key = { it.id }) { note -> MemoryRow(note, now) }
        }
    }
}

private const val MEMORY_POLL_MS = 5_000L

/** D28-D30: white note card, 1 dp line, 16 dp round; name 15 sp medium (one line), date 12 sp, body 14 sp / 20 sp, three lines. */
@Composable
private fun PhoneMemoryRow(note: MemoryNoteDto, now: Long) {
    var open by remember(note.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val pending = note.pending || note.summary == null
    Surface(
        Modifier.fillMaxWidth().alpha(if (pending) 0.75f else 1f).clickable(enabled = !pending) { open = !open },
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text(note.sourceChatTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(textDirection = TextDirection.Content), modifier = Modifier.weight(1f))
                Text(chatTimeLabel(note.createdAt, now), fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            if (pending) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = scheme.primary, trackColor = scheme.inversePrimary)
                    Text("Summarizing…", fontSize = 14.sp, lineHeight = 20.sp, color = WallyColors.noteBody)
                }
            } else {
                Text(note.summary!!, fontSize = 14.sp, lineHeight = 20.sp, color = WallyColors.noteBody, style = TextStyle(textDirection = TextDirection.Content), maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MemoryRow(note: MemoryNoteDto, now: Long) {
    var open by remember(note.id) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().clickable(enabled = !note.pending) { open = !open },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(note.sourceChatTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(chatTimeLabel(note.createdAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (note.pending || note.summary == null) {
                Text("Summarizing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(note.summary!!, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 3)
            }
        }
    }
}
