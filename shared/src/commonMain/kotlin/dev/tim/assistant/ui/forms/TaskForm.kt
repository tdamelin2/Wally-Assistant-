package dev.tim.assistant.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Task
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.DateField
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private fun parseDay(text: String): LocalDate? =
    try { LocalDate.parse(text.trim()) } catch (e: IllegalArgumentException) { null }

@Composable
fun TaskForm(services: AppServices, existing: Task?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.parse(todayLocal()) }
    val existingDue = remember(existing) { existing?.dueDate?.let { parseDay(it) } }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var chip by remember { mutableStateOf(chipForDue(existingDue, today)) }
    // The date behind the Pick chip: starts as the task's own date, so backing out of the picker keeps the old value.
    var picked by remember { mutableStateOf(existingDue?.toString() ?: "") }
    var pickedValid by remember { mutableStateOf(true) }
    val pickedDate = if (pickedValid) parseDay(picked) else null
    val due = dueFromChip(chip, today, pickedDate)
    val dueOk = chip != DueChip.Pick || pickedDate != null

    FormScaffold(
        title = if (existing == null) "New task" else "Edit task",
        canSave = title.isNotBlank() && title.length <= 200 && dueOk,
        saveLabel = laptopSaveLabel("Save task"),
        onCancel = onDone,
        onSave = {
            scope.launch {
                val done = existing?.done?.let { it != 0L } ?: false
                services.repo.saveTask(existing?.id, title.trim(), due?.toString(), done)
                onDone()
                services.syncInBackground()
            }
        },
    ) {
        WallyTextField(
            multiline = false,
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Due")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in DueChip.entries) {
                FilterChip(selected = chip == c, onClick = { chip = c }, label = { Text(c.label) })
            }
        }
        if (chip == DueChip.Pick) {
            DateField("Due date", picked, { picked = it }, onValidChange = { pickedValid = it })
        }
    }
}
