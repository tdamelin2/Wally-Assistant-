package dev.tim.assistant.ui.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.LocalShortcutRegistry
import dev.tim.assistant.ui.LocalTextEntry
import dev.tim.assistant.ui.SAVE_GUARD_MS
import dev.tim.assistant.ui.SaveGuard
import dev.tim.assistant.ui.SystemBack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's one text field: an OutlinedTextField that also tells the window which kind of field has focus, so the
 * keyboard shortcuts know when N must type a letter and when Enter must add a line ([multiline]).
 */
@Composable
fun WallyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    multiline: Boolean,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val entry = LocalTextEntry.current
    val token = remember { Any() }
    DisposableEffect(entry, token) { onDispose { entry.blur(token) } }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { if (it.isFocused) entry.focus(token, multiline) else entry.blur(token) },
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        prefix = prefix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
    )
}

/**
 * One Save action for the button and the Enter key: runs [onSave] only when [canSave] is true and no other save
 * started in the last [SAVE_GUARD_MS] (a failed save can then be tried again).
 */
@Composable
private fun rememberSaveAction(canSave: Boolean, onSave: () -> Unit): () -> Unit = rememberSaveActions(canSave, onSave, null).first

/** The Save action and a second action (such as "Save & interpret") that share one saving guard. */
@Composable
private fun rememberSaveActions(canSave: Boolean, onSave: () -> Unit, onSecond: (() -> Unit)?): Pair<() -> Unit, () -> Unit> {
    val scope = rememberCoroutineScope()
    val guard = remember { SaveGuard() }
    val canSaveNow by rememberUpdatedState(canSave)
    val saveNow by rememberUpdatedState(onSave)
    val secondNow by rememberUpdatedState(onSecond)
    return remember(scope, guard) {
        fun guarded(run: () -> Unit) {
            if (canSaveNow && guard.tryStart()) {
                scope.launch {
                    delay(SAVE_GUARD_MS)
                    guard.release()
                }
                run()
            }
        }
        val save: () -> Unit = { guarded { saveNow() } }
        val second: () -> Unit = { guarded { (secondNow ?: saveNow).invoke() } }
        save to second
    }
}

/** A second save button of the laptop writer ("Save & interpret"): [onClick] saves and then does its extra step. */
class WriterSecondary(val label: String, val onClick: () -> Unit)

/**
 * Shared layout of the add and edit forms. Wide: title, bounded scrolling content, Cancel and Save at the bottom
 * right (shown inside a dialog by the caller). Narrow: the inside of a bottom sheet (the caller draws the sheet):
 * title, scrolling content, then Cancel and Save in one bottom row above the keyboard. On the laptop the form
 * registers Save (Enter) and Cancel (Esc) with the window's shortcuts while it is open.
 */
@Composable
fun FormScaffold(
    title: String,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveLabel: String = "Save",
    content: @Composable () -> Unit,
) {
    val save = rememberSaveAction(canSave, onSave)
    if (LocalLayoutMode.current != LayoutMode.Narrow) {
        val registry = LocalShortcutRegistry.current
        val token = remember { Any() }
        val cancelNow by rememberUpdatedState(onCancel)
        DisposableEffect(registry, token) {
            registry.registerForm(token, onSave = { save() }, onCancel = { cancelNow() })
            onDispose { registry.unregisterForm(token) }
        }
    }
    FormLayout(title, canSave, onCancel, save, saveLabel, content, keyHints = true)
}

/** The laptop's own Save wording (for example "Save meal"); the phone keeps plain "Save". */
@Composable
fun laptopSaveLabel(label: String): String = if (LocalLayoutMode.current != LayoutMode.Narrow) label else "Save"

/** A small keyboard-key chip ("Esc", "Enter") inside a laptop form button. */
@Composable
private fun KeyHint(text: String) {
    Text(
        text,
        Modifier.padding(start = 8.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        softWrap = false,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormLayout(
    title: String,
    canSave: Boolean,
    onCancel: () -> Unit,
    save: () -> Unit,
    saveLabel: String,
    content: @Composable () -> Unit,
    keyHints: Boolean = false,
) {
    if (LocalLayoutMode.current == LayoutMode.Narrow) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = save, enabled = canSave, modifier = Modifier.weight(1f)) { Text(saveLabel) }
            }
        }
    } else {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel", maxLines = 1, softWrap = false); if (keyHints) KeyHint("Esc") }
                Button(onClick = save, enabled = canSave) { Text(saveLabel, maxLines = 1, softWrap = false); if (keyHints) KeyHint("Enter") }
            }
        }
    }
}

/**
 * The full-screen writer (Journal, Dream). Narrow: a close cross at the top, the text area, then Save at the bottom
 * with the draft note when [draftNote]; system back closes like the cross. Wide: the normal form layout; it
 * registers Ctrl+Enter (save) and Esc (close) with the window's shortcuts while it is open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WriterScaffold(
    title: String,
    canSave: Boolean,
    draftNote: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onClear: (() -> Unit)? = null,
    wideTitle: String = title,
    subtitle: String? = null,
    secondary: WriterSecondary? = null,
    content: @Composable () -> Unit,
) {
    val (save, saveSecond) = rememberSaveActions(canSave, onSave, secondary?.onClick)
    if (LocalLayoutMode.current == LayoutMode.Narrow) {
        SystemBack(onBack = onClose)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Text("✕", style = MaterialTheme.typography.titleLarge) }
                Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onClear != null) TextButton(onClick = onClear) { Text("Clear") }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (draftNote) Text(DRAFT_KEPT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = save, enabled = canSave, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }
        }
    } else {
        val registry = LocalShortcutRegistry.current
        val token = remember { Any() }
        val closeNow by rememberUpdatedState(onClose)
        DisposableEffect(registry, token) {
            registry.registerWriter(token, onSave = { save() }, onCancel = { closeNow() })
            onDispose { registry.unregisterWriter(token) }
        }
        // The laptop writer: heading and date, a big writing area, then the draft note with the buttons.
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(wideTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
            if (draftNote) Text(DRAFT_KEPT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (onClear != null) TextButton(onClick = onClear) { Text("Clear", maxLines = 1, softWrap = false) }
                else TextButton(onClick = onClose) { Text("Cancel", maxLines = 1, softWrap = false); KeyHint("Esc") }
                if (secondary != null) OutlinedButton(onClick = saveSecond, enabled = canSave) { Text(secondary.label, maxLines = 1, softWrap = false) }
                Button(onClick = save, enabled = canSave) { Text("Save", maxLines = 1, softWrap = false); KeyHint("Ctrl+Enter") }
            }
        }
    }
}

const val DRAFT_KEPT = "Draft kept if you leave"
