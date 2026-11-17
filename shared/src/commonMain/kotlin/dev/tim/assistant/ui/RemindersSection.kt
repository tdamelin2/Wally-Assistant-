package dev.tim.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.HpResult
import dev.tim.assistant.data.failureText
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.ui.forms.WallyTextField
import kotlinx.coroutines.launch

private val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

/** True for a 24-hour "HH:mm" time such as "21:00". */
fun validReminderTime(text: String): Boolean = TIME_PATTERN.matches(text.trim())

/** What is wrong with the Reminders form, or null when it can be saved. */
fun reminderSettingsError(s: NotifySettingsDto): String? = when {
    !validReminderTime(s.checkinTime) -> "Check-in time must look like 21:00."
    !validReminderTime(s.quietStart) -> "Quiet hours start must look like 23:00."
    !validReminderTime(s.quietEnd) -> "Quiet hours end must look like 07:00."
    s.quietStart.trim() == s.quietEnd.trim() -> "Quiet hours start and end must differ."
    else -> null
}

/** The settings with the time fields trimmed, ready to send. */
fun normalizedReminderSettings(s: NotifySettingsDto): NotifySettingsDto =
    s.copy(checkinTime = s.checkinTime.trim(), quietStart = s.quietStart.trim(), quietEnd = s.quietEnd.trim())

/** Reminder settings kept on the HP (GET/PUT /v1/notify-settings): load on show, save with a message, reschedule on the phone after a save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemindersSection(services: AppServices) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<NotifySettingsDto?>(null) }
    var form by remember { mutableStateOf(NotifySettingsDto()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        when (val r = services.chatApi.notifySettings()) {
            is HpResult.Ok -> { saved = r.value; form = r.value }
            else -> message = r.failureText()
        }
    }

    WallyTextField(
        multiline = false,
        value = form.checkinTime,
        onValueChange = { form = form.copy(checkinTime = it) },
        label = { Text("Check-in time") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    WallyTextField(
        multiline = false,
        value = form.quietStart,
        onValueChange = { form = form.copy(quietStart = it) },
        label = { Text("Quiet hours start") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    WallyTextField(
        multiline = false,
        value = form.quietEnd,
        onValueChange = { form = form.copy(quietEnd = it) },
        label = { Text("Quiet hours end") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    KindRow("Check-in reminder", form.checkin) { form = form.copy(checkin = it) }
    KindRow("Morning list", form.morningList) { form = form.copy(morningList = it) }
    KindRow("Exam and event heads-up", form.headsUp) { form = form.copy(headsUp = it) }
    KindRow("Ideas", form.ideas) { form = form.copy(ideas = it) }
    KindRow("Weekly report", form.weeklyReport) { form = form.copy(weeklyReport = it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !busy,
            onClick = {
                val problem = reminderSettingsError(form)
                if (problem != null) {
                    message = problem
                    return@Button
                }
                val toSave = normalizedReminderSettings(form)
                busy = true
                message = "Saving…"
                scope.launch {
                    when (val r = services.chatApi.putNotifySettings(toSave)) {
                        is HpResult.Ok -> {
                            saved = toSave
                            form = toSave
                            message = "Saved"
                            services.notify?.rescheduleAll()
                        }
                        else -> {
                            // keep the old values on screen: the server still has them
                            saved?.let { form = it }
                            message = r.failureText() ?: "Could not save"
                        }
                    }
                    busy = false
                }
            },
        ) { Text("Save reminders") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun KindRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, modifier = Modifier.weight(1f))
    }
}

/** Texts of the Reminders card, for the label-uniqueness check. */
internal val reminderLabels: List<String> = listOf(
    "Reminders", "Check-in time", "Quiet hours start", "Quiet hours end", "Check-in reminder", "Morning list",
    "Exam and event heads-up", "Ideas", "Weekly report", "Save reminders",
)
