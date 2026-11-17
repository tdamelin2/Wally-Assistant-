package dev.tim.assistant.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import dev.tim.assistant.ui.RefreshBox
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.tim.assistant.ui.forms.WallyTextField
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.PENDING
import dev.tim.assistant.data.REJECTED
import dev.tim.assistant.db.Doc
import dev.tim.assistant.db.Doc_folder
import dev.tim.assistant.generated.resources.Res
import dev.tim.assistant.generated.resources.ic_more_vert
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.LocalShortcutRegistry
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.data.Repository
import dev.tim.assistant.ui.OnNewRequest
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.tim.assistant.ui.SwipeRow
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.approvals.ApprovalsSection
import dev.tim.assistant.ui.approvals.approvalCanDecide
import dev.tim.assistant.ui.rememberQuery
import kotlin.time.Clock
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

internal const val ALL = "\u0000all"
internal const val NONE = "\u0000none"

private class FilesData(val folders: List<Doc_folder>, val docs: List<Doc>, val pendingApprovals: Int)

/** Which doc the editor shows: [docId] null = a new doc created in [folderId]. */
private data class EditSession(val docId: String?, val folderId: String?)

internal sealed interface FileDialog {
    data class NewFolder(val unused: Int = 0) : FileDialog
    data class RenameFolder(val folder: Doc_folder) : FileDialog
    data class DeleteFolder(val folder: Doc_folder) : FileDialog
    data class RenameDoc(val doc: Doc) : FileDialog
    data class MoveDoc(val doc: Doc) : FileDialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(services: AppServices, requestNew: Int = 0, focusSearch: Int = 0) {
    if (LocalLayoutMode.current == LayoutMode.Wide) {
        FilesLaptop(services, requestNew, focusSearch)
        return
    }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    OnNewRequest(focusSearch) { runCatching { searchFocus.requestFocus() } }
    var showApprovals by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(ALL) }
    var edit by remember { mutableStateOf<EditSession?>(null) }
    OnNewRequest(requestNew) { edit = EditSession(null, selected.takeIf { it != ALL && it != NONE }) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val repo = services.repo

    val data = rememberQuery(services, query) { FilesData(
            repo.folders(),
            repo.searchDocs(query),
            repo.approvals().count { approvalCanDecide(it, Clock.System.now().toEpochMilliseconds()) },
        ) }.value

    val session = edit
    if (session != null) {
        DocEditor(services, session.docId, session.folderId, onClose = { edit = null })
        return
    }

    val folders = data?.folders ?: emptyList()
    val folderIds = folders.map { it.id }.toSet()
    // A doc whose folder is missing or deleted shows under "No folder".
    fun effectiveFolder(d: Doc): String? = d.folderId?.takeIf { it in folderIds }
    val hidden by services.undo.hidden.collectAsState()
    val docs = (data?.docs ?: emptyList()).filter { !hidden.hides(UndoTables.DOC, it.id) }.filter {
        when (selected) {
            ALL -> true
            NONE -> effectiveFolder(it) == null
            else -> effectiveFolder(it) == selected
        }
    }
    val selectedFolder = folders.firstOrNull { it.id == selected }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (showApprovals) "Approvals" else "Documents", style = MaterialTheme.typography.titleMedium)
            val waiting = data?.pendingApprovals ?: 0
            TextButton(onClick = { showApprovals = !showApprovals }) {
                Text(if (showApprovals) "Back to files" else "Approvals · $waiting")
            }
        }
        if (showApprovals) {
            RefreshBox(services, Modifier.weight(1f)) { ApprovalsSection(services, Modifier.fillMaxSize()) }
            return@Column
        }
        WallyTextField(
            multiline = false,
            value = query,
            onValueChange = { query = it },
            label = { Text("Search titles and text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
        )
        // While this field is on screen the window's Ctrl+F is handled (it is not when a doc or Approvals is open).
        val shortcutRegistry = LocalShortcutRegistry.current
        DisposableEffect(shortcutRegistry, searchFocus) {
            shortcutRegistry.registerSearch(searchFocus)
            onDispose { shortcutRegistry.unregisterSearch(searchFocus) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = selected == ALL, onClick = { selected = ALL }, label = { Text("All") })
                FilterChip(selected = selected == NONE, onClick = { selected = NONE }, label = { Text("No folder") })
                folders.forEach { f ->
                    FilterChip(selected = selected == f.id, onClick = { selected = f.id }, label = { Text(f.name, maxLines = 1) })
                }
                FilterChip(selected = false, onClick = { dialog = FileDialog.NewFolder() }, label = { Text("+ Folder") })
            }
            if (selectedFolder != null) {
                MenuButton(
                    listOf("Rename folder" to { dialog = FileDialog.RenameFolder(selectedFolder) },
                        "Delete folder" to { dialog = FileDialog.DeleteFolder(selectedFolder) }),
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        RefreshBox(services, Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (docs.isEmpty()) item(key = "empty") { Text(if (query.isBlank()) "No docs here yet" else "Nothing found", modifier = Modifier.animateItem()) }
            items(docs, key = { it.id }) { doc ->
                var menuOpen by remember { mutableStateOf(false) }
                SwipeRow(onComplete = null, onDelete = { services.undo.delete(UndoTables.DOC, doc.id) }, modifier = Modifier.animateItem()) {
                LogCard(
                    Modifier
                        .combinedClickable(onClick = { edit = EditSession(doc.id, null) }, onLongClick = { menuOpen = true })
                        .pointerInput(doc.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                        menuOpen = true
                                        e.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val folderName = folders.firstOrNull { it.id == effectiveFolder(doc) }?.name ?: "No folder"
                        val ai = if (doc.source == "AI") " · AI" else ""
                        Text("$folderName$ai", style = MaterialTheme.typography.bodySmall)
                        if (doc.body.isNotBlank()) Text(doc.body.take(120).replace('\n', ' '), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        if (doc.syncState == PENDING) Text("waiting to sync", style = MaterialTheme.typography.bodySmall)
                        if (doc.syncState == REJECTED) {
                            Text("Rejected by server — edit to retry", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Box {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; dialog = FileDialog.RenameDoc(doc) })
                            DropdownMenuItem(text = { Text("Move") }, onClick = { menuOpen = false; dialog = FileDialog.MoveDoc(doc) })
                        }
                    }
                  }
                }
                }
            }
        }
        }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                error = null
                block()
                services.syncInBackground()
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        }
    }

    FileDialogHost(dialog, folders, selected, { selected = it }, repo, { dialog = null }, ::run)
}

/** The folder and doc dialogs (new / rename / delete folder, rename / move doc), shared by the phone and laptop Files screens. */
@Composable
internal fun FileDialogHost(
    d: FileDialog?,
    folders: List<Doc_folder>,
    selected: String,
    onSelect: (String) -> Unit,
    repo: Repository,
    dismiss: () -> Unit,
    run: (suspend () -> Unit) -> Unit,
) {
    when (d) {
        null -> {}
        is FileDialog.NewFolder -> NameDialog("New folder", "", 100, dismiss) { name ->
            dismiss()
            run { onSelect(repo.saveFolder(null, name)) }
        }
        is FileDialog.RenameFolder -> NameDialog("Rename folder", d.folder.name, 100, dismiss) { name ->
            dismiss()
            run { repo.saveFolder(d.folder.id, name) }
        }
        is FileDialog.RenameDoc -> NameDialog("Rename doc", d.doc.title, 200, dismiss) { name ->
            dismiss()
            run { repo.renameDoc(d.doc.id, name) }
        }
        is FileDialog.DeleteFolder -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Delete folder \"${d.folder.name}\"?") },
            text = { Text("Its docs are kept and move to No folder.") },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    if (selected == d.folder.id) onSelect(ALL)
                    run { repo.deleteFolder(d.folder.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        is FileDialog.MoveDoc -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Move \"${d.doc.title}\" to") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { dismiss(); run { repo.moveDoc(d.doc.id, null) } }) { Text("No folder") }
                    folders.forEach { f ->
                        TextButton(onClick = { dismiss(); run { repo.moveDoc(d.doc.id, f.id) } }) { Text(f.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}

/** A ⋮ button with a small menu of (label, action) pairs. */
@Composable
internal fun MenuButton(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(vectorResource(Res.drawable.ic_more_vert), contentDescription = "Options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, max: Int, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ok = name.isNotBlank() && name.trim().length <= max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            WallyTextField(
                multiline = false,
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = !ok,
                supportingText = { Text("${name.trim().length} / $max") },
            )
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onOk(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Headings and screen-level buttons of the Files screen, for the label-uniqueness check. */
internal val filesLabels: List<String> = listOf("New folder", "Documents", "Approvals", "Back to files", "+ Folder", "All", "No folder")
