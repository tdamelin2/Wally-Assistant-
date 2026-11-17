package dev.tim.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ai.ChatController
import dev.tim.assistant.ai.ChatUiState
import dev.tim.assistant.model.ChatSummaryDto
import kotlinx.coroutines.launch

/** The laptop chat: the list of chats is a column next to the conversation; Ctrl+B or "Hide chats" folds it away. */
@Composable
internal fun LaptopChat(chat: ChatController, state: ChatUiState) {
    val now = rememberSecondClock()
    val scope = rememberCoroutineScope()
    var memory by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatSummaryDto?>(null) }
    deleting?.let { target -> DeleteChatDialog(onConfirm = { deleting = null; scope.launch { chat.delete(target.id) } }, onDismiss = { deleting = null }) }

    Row(Modifier.fillMaxSize()) {
        if (!state.listHidden) {
            Surface(
                Modifier.width(LIST_COLUMN_WIDTH).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                ChatListPanel(
                    state = state,
                    now = now,
                    newAtTop = true,
                    onSelect = { memory = false; chat.selectInBackground(it) },
                    onNew = { memory = false; chat.newChatInBackground() },
                    onMemory = { memory = true },
                    onDelete = { deleting = it },
                    onHide = { chat.toggleListHidden() },
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (memory) {
                MemoryPane(chat, state, now, onBack = { memory = false })
            } else {
                val title = state.chats.firstOrNull { it.id == state.currentId }?.title
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 28.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.listHidden) TextButton(onClick = { chat.toggleListHidden() }) { Text("Chats") }
                    Text(title ?: "Wally", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
                ChatConversation(chat, state, now, enterSends = true, Modifier.weight(1f).padding(horizontal = 28.dp))
            }
        }
    }
}

private val LIST_COLUMN_WIDTH = 288.dp

/** Texts on screen together on the laptop with the list column shown. */
internal val laptopChatLabels: List<String> =
    listOf("Chats", "Hide chats", "New chat", "Memory", "Wally", "Message Wally", "Send", CHAT_ASK_MODEL_LABEL, CHAT_ASK_CODEX_LABEL, "Undo", "Yes", "No", CHAT_OFFLINE_BANNER, "Waiting to send", SLOW_REPLY_HINT)

/** The laptop chat with the list hidden: the show button replaces the column. */
internal val laptopChatHiddenLabels: List<String> =
    listOf("Chats", "Wally", "Message Wally", "Send", CHAT_ASK_MODEL_LABEL, CHAT_ASK_CODEX_LABEL, "Undo", "Yes", "No", CHAT_OFFLINE_BANNER, "Waiting to send", SLOW_REPLY_HINT)
