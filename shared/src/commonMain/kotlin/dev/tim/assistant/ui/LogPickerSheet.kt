package dev.tim.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The six things the + on Today can log, in tile order. */
val logPickerTiles: List<Pair<String, FormTarget>> = listOf(
    "Task" to FormTarget.Task(null),
    "Check-in" to FormTarget.Checkin(null),
    "Spending" to FormTarget.Spending(null),
    "Journal" to FormTarget.Journal(null),
    "Meal" to FormTarget.Meal(null),
    "Dream" to FormTarget.Dream(null),
)

/** "What do you want to log?": six 96 dp tiles and Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogPickerSheet(onPick: (FormTarget) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What do you want to log?", style = MaterialTheme.typography.titleLarge)
            logPickerTiles.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (label, target) -> PickerTile(label) { onPick(target) } }
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun PickerTile(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** The Study +: "Upload file" or "Add exam". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyAddSheet(canUpload: Boolean, onUpload: () -> Unit, onExam: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onUpload, enabled = canUpload, modifier = Modifier.fillMaxWidth()) { Text("Upload file") }
            OutlinedButton(onClick = onExam, modifier = Modifier.fillMaxWidth()) { Text("Add exam") }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
