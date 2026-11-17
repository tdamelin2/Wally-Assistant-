package dev.tim.assistant.ui.forms

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.formatIsoDate
import dev.tim.assistant.ui.lists.jerusalemDay
import dev.tim.assistant.ui.lists.jerusalemTime
import kotlin.time.Clock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Journal
import dev.tim.assistant.ui.AppServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The journal writer. A new entry keeps its text as a draft (saved 500 ms after typing stops); editing never touches the draft. */
@Composable
fun JournalForm(services: AppServices, existing: Journal?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { DraftStore(services.repo::setting, services.repo::putSetting) }
    val isNew = existing == null
    var text by remember { mutableStateOf(initialWriterText(existing?.text, if (isNew) decodeJournalDraft(store.load(JOURNAL_DRAFT_KEY)) else null)) }
    // Set once the entry is saved or the draft cleared, so a late debounce can never write the draft back.
    var finished by remember { mutableStateOf(false) }

    fun persist() {
        if (!isNew || finished) return
        if (text.isBlank()) store.clear(JOURNAL_DRAFT_KEY) else store.save(JOURNAL_DRAFT_KEY, encodeJournalDraft(text))
    }
    if (isNew) {
        LaunchedEffect(text) {
            delay(DRAFT_DEBOUNCE_MS)
            persist()
        }
        // Leaving (chip change, another entry opened, window closed) flushes the full text; a saved or cleared
        // draft stays gone because persist() does nothing once finished.
        val persistNow by rememberUpdatedState<() -> Unit>({ persist() })
        DisposableEffect(Unit) { onDispose { persistNow() } }
    }
    val stamp = remember(existing?.id) {
        val ms = existing?.createdAt ?: Clock.System.now().toEpochMilliseconds()
        "${formatIsoDate(jerusalemDay(ms).toString())} · ${jerusalemTime(ms)}"
    }

    WriterScaffold(
        title = if (isNew) "New journal entry" else "Edit journal entry",
        wideTitle = if (isNew) "New entry" else "Edit entry",
        subtitle = stamp,
        canSave = text.isNotBlank() && text.length <= 10_000,
        draftNote = isNew,
        onClose = { persist(); finished = true; onDone() },
        onSave = {
            finished = true
            scope.launch {
                services.repo.saveJournal(existing?.id, text.trim())
                if (isNew) store.clear(JOURNAL_DRAFT_KEY)
                onDone()
                services.syncInBackground()
            }
        },
        onClear = if (isNew) ({ finished = true; store.clear(JOURNAL_DRAFT_KEY); text = ""; finished = false }) else null,
    ) {
        WallyTextField(
            multiline = true,
            value = text,
            onValueChange = { text = it },
            label = { Text("Journal") },
            modifier = Modifier.fillMaxWidth().heightIn(min = if (LocalLayoutMode.current == LayoutMode.Narrow) 240.dp else 420.dp),
        )
    }
}

/** How long typing must pause before a draft is written. */
const val DRAFT_DEBOUNCE_MS = 500L
