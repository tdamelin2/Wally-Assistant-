package dev.tim.assistant.ui.dream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.LocalLayoutMode
import dev.tim.assistant.ui.forms.WriterSecondary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.normalizeTags
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.DateField
import dev.tim.assistant.ui.forms.DRAFT_DEBOUNCE_MS
import dev.tim.assistant.ui.forms.DREAM_DRAFT_KEY
import dev.tim.assistant.ui.forms.DraftStore
import dev.tim.assistant.ui.forms.DreamDraft
import dev.tim.assistant.ui.forms.ScoreRow
import dev.tim.assistant.ui.forms.WriterScaffold
import dev.tim.assistant.ui.forms.decodeDreamDraft
import dev.tim.assistant.ui.forms.encodeDreamDraft
import dev.tim.assistant.ui.forms.isValidDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The dream writer: date ("Last night"), text, mood 1..5, lucid, tags. A new dream keeps all of those as a draft;
 * editing a saved dream never touches the draft. Reading and Interpret live in DreamDetail.
 */
@Composable
fun DreamForm(services: AppServices, dreamId: String?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val existing = remember(dreamId) { dreamId?.let { services.repo.dreamById(it) } }
    val isNew = existing == null
    val store = remember { DraftStore(services.repo::setting, services.repo::putSetting) }
    val draft = remember { if (isNew) decodeDreamDraft(store.load(DREAM_DRAFT_KEY)) else null }
    var date by remember { mutableStateOf(existing?.localDate ?: todayLocal()) }
    var text by remember { mutableStateOf(existing?.text ?: draft?.text ?: "") }
    var mood by remember { mutableStateOf(existing?.mood?.toInt() ?: draft?.mood ?: 3) }
    var lucid by remember { mutableStateOf(existing?.lucid?.let { it != 0L } ?: draft?.lucid ?: false) }
    var tags by remember { mutableStateOf(existing?.tags ?: draft?.tags ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var dateValid by remember { mutableStateOf(true) }
    val dateOk = dateValid && isValidDate(date)
    // Set once the dream is saved or the draft cleared, so a late debounce can never write the draft back.
    var finished by remember { mutableStateOf(false) }

    fun persist() {
        if (!isNew || finished) return
        val snapshot = DreamDraft(text, mood, lucid, tags)
        if (text.isBlank() && tags.isBlank() && mood == 3 && !lucid) store.clear(DREAM_DRAFT_KEY)
        else store.save(DREAM_DRAFT_KEY, encodeDreamDraft(snapshot))
    }
    if (isNew) {
        // Keyed on the whole snapshot, so changing only the mood, Lucid or tags is also saved.
        LaunchedEffect(DreamDraft(text, mood, lucid, tags)) {
            delay(DRAFT_DEBOUNCE_MS)
            persist()
        }
        // Leaving (chip change, another dream opened, window closed) flushes the full snapshot; a saved or cleared
        // draft stays gone because persist() does nothing once finished.
        val persistNow by rememberUpdatedState<() -> Unit>({ persist() })
        DisposableEffect(Unit) { onDispose { persistNow() } }
    }

    fun save(interpret: Boolean) {
        finished = true
        scope.launch {
            try {
                val id = services.repo.saveDream(existing?.id, date.trim(), text, mood, lucid, tags)
                if (isNew) store.clear(DREAM_DRAFT_KEY)
                // Save & interpret: the saved dream's id is known here; one DREAM_INTERPRET job unless one is already waiting or running.
                if (interpret) queueInterpretOnce(services, id)
                onDone()
                services.syncInBackground()
            } catch (e: IllegalArgumentException) {
                error = e.message
                finished = false
            }
        }
    }

    WriterScaffold(
        title = if (isNew) "New dream" else "Edit dream",
        wideTitle = if (isNew) "New dream" else "Edit dream",
        canSave = text.isNotBlank() && text.trim().length <= 20_000 && dateOk,
        draftNote = isNew,
        onClose = { persist(); finished = true; onDone() },
        onSave = { save(false) },
        secondary = WriterSecondary("Save & interpret") { save(true) },
        onClear = if (isNew) ({
            finished = true
            store.clear(DREAM_DRAFT_KEY)
            text = ""; mood = 3; lucid = false; tags = ""
            finished = false
        }) else null,
    ) {
        DateField(LAST_NIGHT_FIELD, date, { date = it }, onValidChange = { dateValid = it })
        WallyTextField(
            multiline = true,
            value = text,
            onValueChange = { text = it },
            label = { Text("What did you dream?") },
            supportingText = { Text("${text.trim().length} / 20000") },
            isError = text.trim().length > 20_000,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (LocalLayoutMode.current == LayoutMode.Narrow) 200.dp else 320.dp),
        )
        ScoreRow("Mood on waking", mood) { mood = it }
        Row(
            Modifier.clickable { lucid = !lucid },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ring = MaterialTheme.colorScheme.primary
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .then(if (lucid) Modifier.background(ring) else Modifier.border(2.dp, ring, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (lucid) Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
            }
            Text("Lucid dream")
        }
        WallyTextField(
            multiline = false,
            value = tags,
            onValueChange = { tags = it },
            label = { Text("Tags (comma separated)") },
            supportingText = { Text("Saved as: ${normalizeTags(tags).ifEmpty { "none" }}") },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private const val LAST_NIGHT_FIELD = "Last night"
