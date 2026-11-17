package dev.tim.assistant.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.DOC_BODY_MAX
import dev.tim.assistant.data.docBodyTooLong
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.BackIcon
import dev.tim.assistant.ui.MarkdownView
import kotlinx.coroutines.launch

/** Edits one doc ([docId] null = a new one in [startFolderId]) with an Edit / Preview switch. */
@Composable
fun DocEditor(services: AppServices, docId: String?, startFolderId: String?, onClose: () -> Unit, onDirtyChange: (Boolean) -> Unit = {}) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    val existing = remember(docId) { docId?.let { repo.docById(it) } }
    val folders = remember { repo.folders() }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var folderId by remember {
        mutableStateOf((existing?.folderId ?: startFolderId)?.takeIf { id -> folders.any { it.id == id } })
    }
    var preview by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val dirty = title != (existing?.title ?: "") || body != (existing?.body ?: "") ||
        folderId != (existing?.folderId ?: startFolderId)?.takeIf { id -> folders.any { it.id == id } }
    val tooLong = docBodyTooLong(body)
    val titleOk = title.trim().length in 1..200
    val back = { if (dirty) confirmDiscard = true else onClose() }
    // The laptop's Files screen asks before replacing a dirty editor, so it needs to know.
    val latestOnDirty = rememberUpdatedState(onDirtyChange)
    LaunchedEffect(dirty) { latestOnDirty.value(dirty) }
    DisposableEffect(Unit) { onDispose { latestOnDirty.value(false) } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { BackIcon() }
            Text(
                if (existing == null) "New doc" else "Edit doc",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = titleOk && !tooLong,
                onClick = {
                    scope.launch {
                        try {
                            repo.saveDoc(existing?.id, folderId, title, body, existing?.source ?: "USER")
                            onClose()
                            services.syncInBackground()
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                        }
                    }
                },
            ) { Text("Save") }
        }
        WallyTextField(
            multiline = false,
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            isError = title.isNotEmpty() && !titleOk,
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FolderPicker(folders.map { it.id to it.name }, folderId) { folderId = it }
            FilterChip(selected = !preview, onClick = { preview = false }, label = { Text("Edit") })
            FilterChip(selected = preview, onClick = { preview = true }, label = { Text("Preview") })
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (preview) {
                MarkdownView(body)
            } else {
                WallyTextField(
                    multiline = true,
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Text (supports # headings, - bullets, **bold**, *italic*, [links](https://...))") },
                    isError = tooLong,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            if (tooLong) "Too long: ${body.length} / $DOC_BODY_MAX characters" else "${body.length} / $DOC_BODY_MAX characters",
            style = MaterialTheme.typography.bodySmall,
            color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** A button that opens a menu of folders plus "No folder". */
@Composable
internal fun FolderPicker(folders: List<Pair<String, String>>, selectedId: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Folder: " + (folders.firstOrNull { it.first == selectedId }?.second ?: "No folder"), maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("No folder") }, onClick = { open = false; onSelect(null) })
            folders.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}
