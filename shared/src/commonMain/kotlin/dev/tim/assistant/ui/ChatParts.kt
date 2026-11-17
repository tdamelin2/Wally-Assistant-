package dev.tim.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import dev.tim.assistant.generated.resources.ic_add
import dev.tim.assistant.generated.resources.ic_ask_codex
import dev.tim.assistant.generated.resources.ic_ask_model
import dev.tim.assistant.generated.resources.ic_calendar
import dev.tim.assistant.generated.resources.ic_schedule
import dev.tim.assistant.generated.resources.ic_undo
import dev.tim.assistant.generated.resources.ic_wifi_off
import dev.tim.assistant.generated.resources.ic_lightbulb
import dev.tim.assistant.generated.resources.ic_push_pin
import dev.tim.assistant.ai.ChatController
import dev.tim.assistant.ai.ChatUiState
import dev.tim.assistant.ai.QueuedMessage
import dev.tim.assistant.model.ActionRefDto
import dev.tim.assistant.model.ROUTE_AUTO
import dev.tim.assistant.model.ROUTE_CODEX
import dev.tim.assistant.model.ROUTE_MODEL
import dev.tim.assistant.generated.resources.Res
import dev.tim.assistant.generated.resources.ic_arrow_upward
import dev.tim.assistant.model.CardDto
import dev.tim.assistant.model.ChatMessageDto
import dev.tim.assistant.model.ChatSummaryDto
import dev.tim.assistant.ui.forms.WallyTextField
import kotlin.time.Clock
import org.jetbrains.compose.resources.vectorResource
import kotlinx.coroutines.delay

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** A clock that ticks every second while the caller is on screen (Undo countdowns, "Now" labels). */
@Composable
internal fun rememberSecondClock(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/** Offline banner, notice, the messages (server messages, then the greyed queued ones, then typing) and the composer. */
@Composable
internal fun ChatConversation(chat: ChatController, state: ChatUiState, now: Long, enterSends: Boolean, modifier: Modifier = Modifier, phoneStyle: Boolean = false) {
    val listState = rememberLazyListState()
    val lastAssistantId = state.messages.lastOrNull { it.role == "assistant" }?.id
    val queued = queuedForDisplay(state.queued, state.messages.map { it.id }.toSet())
    val tail = chatTail(state.typing, state.streamingText)
    val rows = state.messages.size + queued.size + if (tail != ChatTail.None) 1 else 0
    LaunchedEffect(rows, state.currentId) {
        if (rows > 0) listState.scrollToItem(rows - 1)
    }
    // The phone's words grow one row; follow them at the bottom, but leave a user who scrolled up alone.
    LaunchedEffect(state.streamingText) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (followStreamingBubble(tail, lastVisible, rows)) {
            listState.scrollToItem(rows - 1)
            listState.scrollBy(1_000_000f)
        }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.offline) {
            if (phoneStyle) {
                // D17: the frame's amber banner with a crossed-out wifi icon.
                Surface(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), shape = RoundedCornerShape(12.dp), color = WallyColors.warnBanner, contentColor = WallyColors.onWarnBanner) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(vectorResource(Res.drawable.ic_wifi_off), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(CHAT_OFFLINE_BANNER, fontSize = 13.sp)
                    }
                }
            } else {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(CHAT_OFFLINE_BANNER, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        state.notice?.let {
            Surface(Modifier.fillMaxWidth().clickable { chat.clearNotice() }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(it, style = MaterialTheme.typography.bodySmall.merge(ContentDirection), modifier = Modifier.padding(12.dp))
            }
        }
        // Phone: bubbles are 78% (mine) / 82% (Wally) of the list width and the list has the frame's 16 dp top padding.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val look = if (phoneStyle) PhoneBubbles(maxWidth * 0.78f, maxWidth * 0.82f) else null
            LazyColumn(
                Modifier.fillMaxSize(), state = listState,
                contentPadding = if (phoneStyle) PaddingValues(top = 16.dp, bottom = 8.dp) else PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.messages, key = { it.id }) { m ->
                    MessageItem(chat, state.currentId, m, now, active = m.id == lastAssistantId, look = look)
                }
                items(queued, key = { "q-" + it.messageId }) { q -> QueuedBubble(q, look) }
                when (tail) {
                    ChatTail.Dots -> item(key = "typing") { TypingBubble(slowHintAllowed(state.slowBrain, state.streamingText), look) }
                    ChatTail.Streaming -> item(key = "typing") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) { Bubble(false, state.streamingText.orEmpty(), greyed = false, look = look) }
                    }
                    ChatTail.None -> {}
                }
            }
        }
        Composer(chat, enterSends, phoneStyle)
    }
}

/** Phone look of the chat (frames ChatMain/ChatCard): the widest a bubble may be. Null on the laptop (generic look, C21). */
internal class PhoneBubbles(val mineMax: Dp, val wallyMax: Dp)

@Composable
private fun MessageItem(chat: ChatController, chatId: String?, m: ChatMessageDto, now: Long, active: Boolean, look: PhoneBubbles?) {
    if (m.role == "user") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) { Bubble(true, m.text, greyed = false, look = look) }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.text.isNotBlank()) Bubble(false, m.text, greyed = false, look = look)
        for (card in m.cards) CardView(chat, card, look != null)
        for (action in m.actions) if (chatId != null) UndoRow(chat, chatId, action, now, look != null)
        if (m.choices.isNotEmpty()) ChoiceRow(chat, m, active, look != null)
    }
}

@Composable
internal fun Bubble(mine: Boolean, text: String, greyed: Boolean, modifier: Modifier = Modifier, look: PhoneBubbles? = null) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp)
    if (look != null) {
        // D18: Wally's bubble is white with a 1 dp line; text 15 sp / 21 sp; greyed (queued) is 60% opaque.
        Surface(
            modifier = modifier.widthIn(max = if (mine) look.mineMax else look.wallyMax).alpha(if (greyed) 0.6f else 1f),
            shape = shape,
            color = if (mine) scheme.primary else scheme.surface,
            contentColor = if (mine) scheme.onPrimary else scheme.onSurface,
            border = if (mine) null else BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Text(text.ifEmpty { "…" }, fontSize = 15.sp, lineHeight = 21.sp, style = ContentDirection, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        return
    }
    Surface(
        modifier = modifier.widthIn(max = 560.dp).alpha(if (greyed) 0.55f else 1f),
        shape = shape,
        color = if (mine) scheme.primary else scheme.surfaceVariant,
        contentColor = if (mine) scheme.onPrimary else scheme.onSurfaceVariant,
    ) {
        Text(text.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** A message waiting for the server: greyed, with a small clock line (phone: the clock sits inside the bubble). */
@Composable
private fun QueuedBubble(q: QueuedMessage, look: PhoneBubbles?) {
    if (look != null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                Modifier.widthIn(max = look.mineMax).alpha(0.6f),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(q.text.ifEmpty { "…" }, fontSize = 15.sp, lineHeight = 21.sp, style = ContentDirection, modifier = Modifier.weight(1f, fill = false))
                    Icon(vectorResource(Res.drawable.ic_schedule), contentDescription = "Waiting to send", modifier = Modifier.size(14.dp))
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Bubble(true, q.text, greyed = true)
        Text("Waiting to send", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TypingBubble(slowBrain: Boolean, look: PhoneBubbles?) {
    val transition = rememberInfiniteTransition(label = "typing")
    var waited by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        delay(SLOW_REPLY_AFTER_MS + 1)
        waited = SLOW_REPLY_AFTER_MS + 1
    }
    val scheme = MaterialTheme.colorScheme
    val phone = look != null
    Surface(
        shape = if (phone) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp) else RoundedCornerShape(18.dp),
        color = if (phone) scheme.surface else scheme.surfaceVariant,
        border = if (phone) BorderStroke(1.dp, scheme.outlineVariant) else null,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until 3) {
                    val a by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                        label = "dot$i",
                    )
                    // D19 (phone): the frame's dots are light, mid, light green (#9CCBAE, #4E9A73, #9CCBAE).
                    val dot = if (!phone) scheme.primary else if (i == 1) scheme.secondary else scheme.inversePrimary
                    Box(Modifier.size(8.dp).alpha(a).background(dot, CircleShape))
                }
            }
            slowReplyHint(waited, slowBrain)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The Undo button and its countdown; the server decides at the tap whether the time is still open. */
@Composable
private fun UndoRow(chat: ChatController, chatId: String, action: ActionRefDto, now: Long, phone: Boolean) {
    val left = undoCountdownText(action.expiresAt, now)
    when {
        action.undone -> Text("Undone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        left != null && phone -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // D20: the frame's Undo is a 36 dp dark pill, 13 sp medium, with an undo arrow; the countdown is 12 sp.
            Button(
                onClick = { chat.undoInBackground(chatId, action.actionId) },
                modifier = Modifier.heightIn(min = 36.dp),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
            ) {
                Icon(vectorResource(Res.drawable.ic_undo), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Undo", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text(left, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        left != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { chat.undoInBackground(chatId, action.actionId) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
            ) { Text("Undo") }
            Text(left, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Unit
    }
}

/** "Which one?" chips; only the newest answer's chips work (their tokens belong to that message). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(chat: ChatController, m: ChatMessageDto, active: Boolean, phone: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in m.choices) {
            if (phone) {
                // D21: pick chips are 40 dp, 20 dp round, 1.5 dp green line, white fill, dark-green 14 sp medium text.
                OutlinedButton(
                    enabled = active,
                    onClick = { chat.choose(c) },
                    modifier = Modifier.heightIn(min = 40.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                ) { Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = ContentDirection) }
            } else {
                OutlinedButton(
                    enabled = active,
                    onClick = { chat.choose(c) },
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                ) { Text(c.name, style = ContentDirection) }
            }
        }
    }
}

/** A change Wally wants to make: what it does, and Yes / No while it is waiting. The server applies it. */
@Composable
private fun CardView(chat: ChatController, card: CardDto, phone: Boolean) {
    if (phone) {
        PhoneCard(chat, card)
        return
    }
    Surface(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(card.summary, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
            when (card.status) {
                "PENDING" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { chat.decideInBackground(card.approvalId, false) }) { Text("No") }
                    Button(onClick = { chat.decideInBackground(card.approvalId, true) }) { Text("Yes") }
                }
                "DONE" -> Text("Done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                "REJECTED" -> Text("Not done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("Failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * The phone approval card (frame ChatCard, D22-D26): white, 1.5 dp green line while pending, 18 dp round, 14/16 dp padding.
 * With [CardDto.display] it shows calendar icon + title, the item name, a From (struck through) / To grid and No + yesLabel;
 * without it, the generic title / summary / Yes / No in the same styling.
 */
@Composable
private fun PhoneCard(chat: ChatController, card: CardDto) {
    val scheme = MaterialTheme.colorScheme
    val d = card.display
    val pending = card.status == "PENDING"
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = if (pending) BorderStroke(1.5.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d != null) Icon(vectorResource(Res.drawable.ic_calendar), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Text(d?.title ?: card.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.primary, style = ContentDirection)
            }
            if (d != null) {
                Text(d.itemName, fontSize = 16.sp, fontWeight = FontWeight.Medium, style = ContentDirection)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("From", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Text(d.before, fontSize = 15.sp, color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough, style = ContentDirection)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("To", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Text(d.after, fontSize = 15.sp, fontWeight = FontWeight.Medium, style = ContentDirection)
                    }
                }
            } else {
                Text(card.summary, fontSize = 16.sp, fontWeight = FontWeight.Medium, style = ContentDirection)
            }
            when (card.status) {
                "PENDING" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { chat.decideInBackground(card.approvalId, false) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = scheme.surface, contentColor = scheme.onSurface),
                        border = BorderStroke(1.dp, scheme.outline),
                    ) { Text("No", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                    Button(
                        onClick = { chat.decideInBackground(card.approvalId, true) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text(d?.yesLabel ?: "Yes", fontSize = 15.sp, fontWeight = FontWeight.Medium, style = ContentDirection) }
                }
                "DONE" -> Text("Done", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.primary)
                "REJECTED" -> Text("Not done", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
                else -> Text("Failed", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.error)
            }
        }
    }
}

/**
 * The message box. [enterSends] (laptop): Enter sends, Shift+Enter adds a line; the phone keeps Enter as a new line.
 * The Send button works the same on both.
 */
@Composable
private fun Composer(chat: ChatController, enterSends: Boolean, phoneStyle: Boolean) {
    var input by remember { mutableStateOf("") }
    val sendVia = { route: String ->
        if (chat.send(input, route = route)) input = ""
    }
    val send = { sendVia(ROUTE_AUTO) }
    if (phoneStyle) {
        PhoneComposer(input, onChange = { input = it }, onSend = { send() }, onAskModel = { sendVia(ROUTE_MODEL) }, onAskCodex = { sendVia(ROUTE_CODEX) })
        return
    }
    // Laptop: the text box on top, the three buttons in a row that wraps when the pane is narrow.
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WallyTextField(
            multiline = true,
            value = input,
            onValueChange = { input = it },
            label = { Text("Message Wally") },
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().then(
                if (!enterSends) Modifier else Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                    when (composerKeyAction(enter, e.isShiftPressed, e.isAltPressed)) {
                        ComposerKey.Send -> { send(); true }
                        ComposerKey.AskModel -> { if (input.isNotBlank()) sendVia(ROUTE_MODEL); true }
                        ComposerKey.AskCodex -> { if (input.isNotBlank()) sendVia(ROUTE_CODEX); true }
                        else -> false
                    }
                },
            ),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val has = input.isNotBlank()
            OutlinedButton(onClick = { sendVia(ROUTE_MODEL) }, enabled = has) {
                Icon(vectorResource(Res.drawable.ic_ask_model), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(CHAT_ASK_MODEL_LABEL)
            }
            OutlinedButton(onClick = { sendVia(ROUTE_CODEX) }, enabled = has) {
                Icon(vectorResource(Res.drawable.ic_ask_codex), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(CHAT_ASK_CODEX_LABEL)
            }
            Button(onClick = { send() }, enabled = has) { Text("Send") }
        }
    }
}

/** The phone message box (D6-D8): one white pill with the text area and a round green up-arrow Send button. */
@Composable
private fun PhoneComposer(input: String, onChange: (String) -> Unit, onSend: () -> Unit, onAskModel: () -> Unit, onAskCodex: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = input,
                onValueChange = onChange,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.merge(ContentDirection).copy(color = scheme.onSurface),
                cursorBrush = cursorBrush(scheme.primary),
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) Text(CHAT_PHONE_PLACEHOLDER, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            val has = input.isNotBlank()
            PhoneAskButton(Res.drawable.ic_ask_model, CHAT_ASK_MODEL_LABEL, has, onAskModel)
            PhoneAskButton(Res.drawable.ic_ask_codex, CHAT_ASK_CODEX_LABEL, has, onAskCodex)
            Box(
                Modifier.size(44.dp).background(scheme.primary.copy(alpha = if (input.isNotBlank()) 1f else 0.4f), CircleShape)
                    .clickable(enabled = input.isNotBlank(), onClickLabel = "Send", onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(vectorResource(Res.drawable.ic_arrow_upward), contentDescription = "Send", tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A round 44 dp secondary-colour icon button left of Send (same size as Send). */
@Composable
private fun PhoneAskButton(icon: org.jetbrains.compose.resources.DrawableResource, label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).background(scheme.secondary.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(vectorResource(icon), contentDescription = label, tint = scheme.onSecondary, modifier = Modifier.size(20.dp))
    }
}

/**
 * The list of chats (phone drawer or laptop column): the pinned report first, then the newest. A tap opens a chat; swipe
 * (phone) or the hover bin (laptop) asks before deleting.
 */
@Composable
internal fun ChatListPanel(
    state: ChatUiState,
    now: Long,
    newAtTop: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onMemory: () -> Unit,
    onDelete: (ChatSummaryDto) -> Unit,
    onHide: (() -> Unit)?,
    modifier: Modifier = Modifier,
    phoneStyle: Boolean = false,
) {
    if (phoneStyle) {
        PhoneChatList(state, now, onSelect, onNew, onMemory, onDelete, modifier)
        return
    }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.titleLarge)
            Text(chatCountText(state.activeCount, state.limit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (onHide != null) androidx.compose.material3.TextButton(onClick = onHide) { Text("Hide chats") }
        }
        if (newAtTop) Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New chat") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.chats, key = { it.id }) { chat ->
                val row: @Composable () -> Unit = { ChatRow(chat, selected = chat.id == state.currentId, now = now, onClick = { onSelect(chat.id) }) }
                if (chatCanDelete(chat)) LogRow(onDone = null, onEdit = null, onDelete = { onDelete(chat) }, content = row) else row()
            }
        }
        if (!newAtTop) Text(CHAT_LIST_FOOTNOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.TextButton(onClick = onMemory) { Text("Memory") }
            if (!newAtTop) Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("New chat") }
        }
    }
}

/** The phone drawer's content (D11-D16): header with the count, pinned report, chat rows, the note, Memory and New chat. */
@Composable
private fun PhoneChatList(
    state: ChatUiState,
    now: Long,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onMemory: () -> Unit,
    onDelete: (ChatSummaryDto) -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("Chats", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(chatCountText(state.activeCount, state.limit), fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.chats, key = { it.id }) { chat ->
                val row: @Composable () -> Unit = {
                    PhoneChatRow(chat, selected = chat.id == state.currentId, now = now, onClick = { onSelect(chat.id) })
                }
                if (chatCanDelete(chat)) LogRow(onDone = null, onEdit = null, onDelete = { onDelete(chat) }, modifier = Modifier.clip(rowShape), content = row)
                else row()
                if (chat.pinned) HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), thickness = 1.dp, color = scheme.outlineVariant)
            }
            item(key = "footnote") {
                Text(CHAT_LIST_FOOTNOTE, fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 0.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onMemory, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp)) {
                Icon(vectorResource(Res.drawable.ic_lightbulb), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Memory", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
                Icon(vectorResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("New chat", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PhoneChatRow(chat: ChatSummaryDto, selected: Boolean, now: Long, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (chat.pinned) scheme.primaryContainer else if (selected) scheme.surfaceVariant else WallyColors.clear,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (chat.pinned) Icon(vectorResource(Res.drawable.ic_push_pin), contentDescription = "Pinned", tint = scheme.primary, modifier = Modifier.padding(end = 6.dp).size(14.dp))
                Text(chat.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, style = TextStyle(textDirection = TextDirection.Content), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(chatTimeLabel(chat.lastUsedAt, now), fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            Text(chatPreview(chat), fontSize = 13.sp, style = TextStyle(textDirection = TextDirection.Content), maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummaryDto, selected: Boolean, now: Long, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (chat.pinned) Box(Modifier.padding(end = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(chat.title, style = MaterialTheme.typography.titleSmall.merge(ContentDirection), maxLines = 1, modifier = Modifier.weight(1f))
                Text(chatTimeLabel(chat.lastUsedAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(chatPreview(chat), style = MaterialTheme.typography.bodySmall.merge(ContentDirection), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
