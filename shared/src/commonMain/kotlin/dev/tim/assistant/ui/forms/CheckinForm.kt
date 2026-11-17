package dev.tim.assistant.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.DateField
import dev.tim.assistant.ui.formatIsoDate
import kotlinx.coroutines.launch

@Composable
fun CheckinForm(services: AppServices, existing: Checkin?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Editing keeps the check-in's own day (read-only); a new one picks its day, today first.
    var date by remember { mutableStateOf(existing?.localDate ?: todayLocal()) }
    var dateValid by remember { mutableStateOf(true) }
    var mood by remember { mutableIntStateOf((existing?.mood ?: 3L).toInt()) }
    var energy by remember { mutableIntStateOf((existing?.energy ?: 3L).toInt()) }
    var sleep by remember { mutableIntStateOf((existing?.sleepQuality ?: 3L).toInt()) }
    var stress by remember { mutableIntStateOf((existing?.stress ?: 3L).toInt()) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    val dateOk = existing != null || (dateValid && isValidDate(date))

    // A new check-in on a day that already has one starts from that day's scores (saving replaces it).
    LaunchedEffect(date) {
        if (existing != null || !dateOk) return@LaunchedEffect
        val other = services.repo.checkinForDate(date.trim()) ?: return@LaunchedEffect
        mood = other.mood.toInt(); energy = other.energy.toInt(); sleep = other.sleepQuality.toInt(); stress = other.stress.toInt()
        note = other.note ?: ""
    }

    FormScaffold(
        title = if (existing == null) "New check-in" else "Edit check-in",
        canSave = dateOk,
        saveLabel = laptopSaveLabel("Save check-in"),
        onCancel = onDone,
        onSave = {
            scope.launch {
                services.repo.saveCheckin(date.trim(), mood, energy, sleep, stress, note)
                onDone()
                services.syncInBackground()
            }
        },
    ) {
        if (existing != null) {
            Text(formatIsoDate(existing.localDate), style = MaterialTheme.typography.titleMedium)
        } else {
            DateField("Date", date, { date = it }, onValidChange = { dateValid = it })
        }
        ScoreRow("Mood", mood) { mood = it }
        ScoreRow("Energy", energy) { energy = it }
        ScoreRow("Sleep quality", sleep) { sleep = it }
        ScoreRow("Stress", stress) { stress = it }
        WallyTextField(
            multiline = true,
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A label and five buttons 1..5; the chosen one is filled. */
@Composable
internal fun ScoreRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in 1..5) {
            val pad = PaddingValues(0.dp)
            if (n == value) {
                Button(onClick = { onChange(n) }, modifier = Modifier.weight(1f), contentPadding = pad) { Text("$n") }
            } else {
                OutlinedButton(onClick = { onChange(n) }, modifier = Modifier.weight(1f), contentPadding = pad) { Text("$n") }
            }
        }
    }
}
