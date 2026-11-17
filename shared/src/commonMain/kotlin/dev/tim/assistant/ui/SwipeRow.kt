package dev.tim.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class UndoKind { Delete, Complete }

/** One swipe waiting for its Undo window. [done] is the target state of a Complete. */
data class PendingAction(
    val token: Long,
    val table: String,
    val id: String,
    val kind: UndoKind,
    val done: Boolean? = null,
)

/**
 * Holds at most one pending action. Nothing touches the database here: the caller commits what [hold] returns
 * (the replaced action) or what [expire] returns. A token resolves at most once, and a stale token never
 * resolves a newer action.
 */
class UndoBuffer {
    private var pending: PendingAction? = null

    val current: PendingAction? get() = pending

    /** Rows to hide from lists: table/id pairs. */
    val hidden: Set<Pair<String, String>>
        get() = pending?.let { setOf(it.table to it.id) } ?: emptySet()

    /** Holds [action]; returns the action it replaced, which the caller must commit now. */
    fun hold(action: PendingAction): PendingAction? {
        val old = pending
        pending = action
        return old
    }

    /** Cancels the pending action if [token] is still the current one. Nothing is written. */
    fun undo(token: Long): Boolean {
        if (pending?.token != token) return false
        pending = null
        return true
    }

    /** Ends the Undo window: returns the action to commit if [token] is still current, once. */
    fun expire(token: Long): PendingAction? {
        val action = pending
        if (action == null || action.token != token) return null
        pending = null
        return action
    }
}

/** True when the table/id pair is waiting for Undo or being committed. */
fun Set<Pair<String, String>>.hides(table: String, id: String): Boolean = (table to id) in this

private val SwipeShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

/**
 * A row with swipe actions: right = complete (when [onComplete] is given), left = delete. The row always snaps
 * back; the action itself decides whether the row leaves (it goes through the UndoController).
 */
@Composable
fun SwipeRow(onComplete: (() -> Unit)?, onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val completeNow by rememberUpdatedState(onComplete)
    val deleteNow by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> completeNow?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    deleteNow()
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = onComplete != null,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = state.dismissDirection
            val (color, onColor, label, align) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeLook(
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "Done", Alignment.CenterStart,
                )
                SwipeToDismissBoxValue.EndToStart -> SwipeLook(
                    MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Delete", Alignment.CenterEnd,
                )
                SwipeToDismissBoxValue.Settled -> SwipeLook(
                    MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, "", Alignment.Center,
                )
            }
            Box(Modifier.fillMaxSize().background(color, SwipeShape).padding(horizontal = 16.dp), contentAlignment = align) {
                Text(label, color = onColor, style = MaterialTheme.typography.labelLarge)
            }
        },
        content = { Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() } },
    )
}

private data class SwipeLook(
    val color: androidx.compose.ui.graphics.Color,
    val onColor: androidx.compose.ui.graphics.Color,
    val label: String,
    val align: Alignment,
)

/** Shared by a checkbox and its title: when [fading] the title fades and gets a strikethrough. */
class CheckOffState(private val scope: CoroutineScope) {
    var fading by mutableStateOf(false)
        private set
    internal var pulse by mutableStateOf(false)

    /** Plays the check-off animation, then runs [action] (which makes the row leave). */
    fun play(action: () -> Unit) {
        if (fading) return
        fading = true
        pulse = true
        scope.launch {
            delay(Motion.CheckMs * 2L)
            action()
            // The row is hidden by the action; if it stays (a failed write) it reads normally again.
            delay(Motion.CheckMs * 2L)
            fading = false
        }
    }
}

@Composable
fun rememberCheckOffState(scope: CoroutineScope): CheckOffState = remember(scope) { CheckOffState(scope) }

/** Checkbox that scales 1 -> 1.2 -> 1 when checked, then lets the row leave via [onChecked]. */
@Composable
fun CheckOffBox(checked: Boolean, state: CheckOffState, onChecked: () -> Unit, onUnchecked: () -> Unit = {}) {
    val scale by animateFloatAsState(
        targetValue = if (state.pulse) 1.2f else 1f,
        animationSpec = tween(Motion.CheckMs),
        finishedListener = { if (state.pulse) state.pulse = false },
    )
    val haptics = LocalHapticFeedback.current
    Checkbox(
        checked = checked || state.fading,
        onCheckedChange = { now ->
            if (now) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                state.play(onChecked)
            } else onUnchecked()
        },
        modifier = Modifier.scale(scale),
    )
}

/** A title that fades and is struck through while [state] is fading. */
@Composable
fun CheckOffTitle(text: String, state: CheckOffState, struck: Boolean = false, style: TextStyle = TextStyle.Default, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (state.fading) 0.4f else 1f, tween(Motion.CheckMs))
    Text(
        text,
        style = style,
        textDecoration = if (state.fading || struck) TextDecoration.LineThrough else null,
        modifier = modifier.alpha(alpha),
    )
}
