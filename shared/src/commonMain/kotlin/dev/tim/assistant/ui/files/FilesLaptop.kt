package dev.tim.assistant.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.PENDING
import dev.tim.assistant.data.REJECTED
import dev.tim.assistant.db.Doc
import dev.tim.assistant.db.Doc_folder
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.LaptopColumns
import dev.tim.assistant.ui.LeaveGuardEffect
import dev.tim.assistant.ui.LocalShortcutRegistry
import dev.tim.assistant.ui.LogRow
import dev.tim.assistant.ui.MarkdownView
import dev.tim.assistant.ui.OnNewRequest
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.approvals.ApprovalsSection
import dev.tim.assistant.ui.approvals.approvalCanDecide
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.forms.WallyTextField
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.rememberQuery
import dev.tim.assistant.ui.stacksSidePanel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch

private class LaptopFilesData(val folders: List<Doc_folder>, val docs: List<Doc>, val openDoc: Doc?, val pendingApprovals: Int)

/** What the right column of the laptop Files screen shows. */
private sealed interface FilesPane {
    data object None : FilesPane
    data object Approvals : FilesPane
    data class Read(val docId: String) : FilesPane
    /** [docId] null = a new doc in [folderId]; [key] makes every opening a fresh editor (no stale text). */
    data class Editing(val docId: String?, val folderId: String?, val key: Int) : FilesPane
}

/**
 * Laptop Files: folders on the left, the doc list in the middle, the open doc (read view, editor or Approvals) on the
 * right (under the list when the window is narrow). A dirty editor asks before anything replaces it.
 */
@Composable
internal fun FilesLaptop(services: AppServices, requestNew: Int, focusSearch: Int) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    OnNewRequest(focusSearch) { runCatching { searchFocus.requestFocus() } }
    // The search box stays mounted (also while a doc is open), so Ctrl+F always has something to focus.
    val shortcutRegistry = LocalShortcutRegistry.current
    DisposableEffect(shortcutRegistry, searchFocus) {
        shortcutRegistry.registerSearch(searchFocus)
        onDispose { shortcutRegistry.unregisterSearch(searchFocus) }
    }
    var selected by remember { mutableStateOf(ALL) }
    var pane by remember { mutableStateOf<FilesPane>(FilesPane.None) }
    var editorDirty by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var editCount by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    /** Runs [action] now, or after the user agrees to discard when the open editor has unsaved changes. */
    fun guarded(action: () -> Unit) {
        if (pane is FilesPane.Editing && editorDirty) pendingSwitch = action else action()
    }

    LeaveGuardEffect(pane is FilesPane.Editing && editorDirty)

    val openId = when (val p = pane) {
        is FilesPane.Read -> p.docId
        is FilesPane.Editing -> p.docId
        else -> null
    }
    val data = rememberQuery(services, query to openId) {
        LaptopFilesData(
            repo.folders(),
            repo.searchDocs(query),
            openId?.let { repo.docById(it) },
            repo.approvals().count { approvalCanDecide(it, Clock.System.now().toEpochMilliseconds()) },
        )
    }.value
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
    val today = localDateOf(Clock.System.now())

    // A doc that Undo is hiding (just deleted) is no longer open.
    LaunchedEffect(hidden, openId) {
        if (openId != null && hidden.hides(UndoTables.DOC, openId)) pane = FilesPane.None
    }

    fun openNew() {
        editCount++
        pane = FilesPane.Editing(null, selected.takeIf { it != ALL && it != NONE }, editCount)
    }
    OnNewRequest(requestNew) { guarded { openNew() } }

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

    fun folderCaption(doc: Doc): String {
        val name = folders.firstOrNull { it.id == effectiveFolder(doc) }?.name ?: "No folder"
        return name + if (doc.source == "AI") " · AI" else ""
    }

    fun editedText(doc: Doc): String {
        val day = localDateOf(Instant.fromEpochMilliseconds(doc.updatedAt))
        return if (day == today) "edited today" else formatIsoDate(day)
    }

    val leftColumn: @Composable () -> Unit = {
        Column(Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FolderRow("All docs", selected == ALL) { guarded { selected = ALL; if (pane is FilesPane.Editing) pane = FilesPane.None } }
            folders.forEach { f ->
                FolderRow(f.name, selected == f.id, trailing = {
                    if (selected == f.id) MenuButton(
                        listOf("Rename folder" to { dialog = FileDialog.RenameFolder(f) }, "Delete folder" to { dialog = FileDialog.DeleteFolder(f) }),
                    )
                }) { guarded { selected = f.id; if (pane is FilesPane.Editing) pane = FilesPane.None } }
            }
            FolderRow("No folder", selected == NONE) { guarded { selected = NONE; if (pane is FilesPane.Editing) pane = FilesPane.None } }
            FolderRow("+ New folder", false) { guarded { dialog = FileDialog.NewFolder() } }
            FolderRow("Approvals · ${data?.pendingApprovals ?: 0}", pane == FilesPane.Approvals) { guarded { pane = FilesPane.Approvals } }
        }
    }

    val middleColumn: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { guarded { openNew() } }) { Text("+ New doc") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            RefreshBox(services, Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (docs.isEmpty()) item(key = "empty") {
                        Text(if (query.isBlank()) "No docs here yet" else "Nothing found", modifier = Modifier.animateItem())
                    }
                    items(docs, key = { it.id }) { doc ->
                        val isOpen = openId == doc.id
                        LogRow(
                            onDone = null,
                            onEdit = null,
                            onDelete = { services.undo.delete(UndoTables.DOC, doc.id) },
                            modifier = Modifier.animateItem().then(
                                if (isOpen) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)) else Modifier,
                            ),
                        ) {
                            LogCard(Modifier.clickable { guarded { pane = FilesPane.Read(doc.id) } }) {
                                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(folderCaption(doc) + " · " + editedText(doc), style = MaterialTheme.typography.bodySmall)
                                if (doc.syncState == PENDING) Text("waiting to sync", style = MaterialTheme.typography.bodySmall)
                                if (doc.syncState == REJECTED) {
                                    Text("Rejected by server — edit to retry", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val rightColumn: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val p = pane) {
                FilesPane.None -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Pick a doc to read it here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilesPane.Approvals -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                    RefreshBox(services, Modifier.weight(1f)) { ApprovalsSection(services, Modifier.fillMaxSize()) }
                }
                is FilesPane.Read -> {
                    val doc = data?.openDoc
                    if (doc == null) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { Text("Pick a doc to read it here") }
                    } else {
                        key(doc.id) {
                            DocReadPane(
                                doc, "${folderCaption(doc)} · ${editedText(doc)}",
                                onEdit = { guarded { editCount++; pane = FilesPane.Editing(doc.id, null, editCount) } },
                                onMove = { dialog = FileDialog.MoveDoc(doc) },
                                onRename = { dialog = FileDialog.RenameDoc(doc) },
                                onDelete = {
                                    pane = FilesPane.None
                                    services.undo.delete(UndoTables.DOC, doc.id)
                                },
                            )
                        }
                    }
                }
                is FilesPane.Editing -> key(p.key) {
                    DocEditor(
                        services, p.docId, p.folderId,
                        onClose = { pane = p.docId?.let { FilesPane.Read(it) } ?: FilesPane.None; editorDirty = false },
                        onDirtyChange = { editorDirty = it },
                    )
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Documents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            WallyTextField(
                multiline = false,
                value = query,
                onValueChange = { query = it },
                label = { Text("Search titles and text") },
                singleLine = true,
                modifier = Modifier.width(300.dp).focusRequester(searchFocus),
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LaptopColumns(stacksSidePanel(maxWidth.value), side = rightColumn) {
                Row(Modifier.fillMaxSize()) {
                    leftColumn()
                    Box(Modifier.weight(1f).fillMaxHeight()) { middleColumn() }
                }
            }
        }
    }

    FileDialogHost(dialog, folders, selected, { selected = it }, repo, { dialog = null }, ::run)

    pendingSwitch?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Discard your changes?") },
            confirmButton = { TextButton(onClick = { pendingSwitch = null; editorDirty = false; action() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("Keep editing") } },
        )
    }
}

/** One row of the folder column: the selected one is tinted; [trailing] holds the folder's menu. */
@Composable
private fun FolderRow(text: String, selected: Boolean, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** The right column's read view of one doc: title, Edit, a menu (Move, Rename, Delete) and the rendered text. */
@Composable
private fun DocReadPane(doc: Doc, caption: String, onEdit: () -> Unit, onMove: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(doc.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onEdit) { Text("Edit") }
            MenuButton(listOf("Move" to onMove, "Rename" to onRename, "Delete" to onDelete))
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) { MarkdownView(doc.body) }
    }
}

/** Texts of the laptop Files screen, for the label-uniqueness check (the folder column, the list and the read view). */
internal val laptopFilesLabels: List<String> = listOf(
    "Documents", "Search titles and text", "All docs", "No folder", "+ New folder", "Approvals", "+ New doc", "Edit", "Move", "Rename", "Delete",
)
