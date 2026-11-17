package dev.tim.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime

private val LAPTOP_DATE_FORMAT: DateTimeFormat<LocalDate> = LocalDate.Format {
    monthNumber()
    char('-')
    day()
    char('-')
    year()
}

private val TIME_SHAPE = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")

/** The date for display: laptop = MM-dd-yyyy, phone = the locale's medium format. Storage stays ISO. */
fun formatDate(date: LocalDate, laptop: Boolean = isLaptop): String =
    if (laptop) LAPTOP_DATE_FORMAT.format(date) else localeMediumDate(date)

/** [formatDate] of a stored "YYYY-MM-DD" text; text that is not a real ISO date is returned unchanged. */
fun formatIsoDate(iso: String, laptop: Boolean = isLaptop): String {
    val date = try {
        LocalDate.parse(iso.trim())
    } catch (e: IllegalArgumentException) {
        return iso
    }
    return formatDate(date, laptop)
}

/** Strict whole-string "MM-dd-yyyy" (two-digit month and day, four-digit year, a real date); null otherwise. */
fun parseLaptopDate(text: String): LocalDate? = LAPTOP_DATE_FORMAT.parseOrNull(text.trim())

/** The calendar day a date picker's selected millisecond (midnight UTC) stands for. */
fun pickerMillisToDate(ms: Long): LocalDate = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date

private fun dateToPickerMillis(date: LocalDate): Long = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/** 24-hour "HH:mm". */
fun formatTime(hour: Int, minute: Int): String = hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

private fun parseTimeOrNull(text: String): Pair<Int, Int>? {
    val m = TIME_SHAPE.matchEntire(text.trim()) ?: return null
    return m.groupValues[1].toInt() to m.groupValues[2].toInt()
}

private fun isoOrNull(text: String): LocalDate? = try {
    LocalDate.parse(text.trim())
} catch (e: IllegalArgumentException) {
    null
}

/**
 * A date input. [value] is the canonical "YYYY-MM-DD" ("" = none); the typed draft and its validity are kept here,
 * so an unfinished draft never overwrites the value. [onValidChange] tells the form whether Save may be enabled.
 * Laptop: typeable MM-dd-yyyy plus a calendar button. Phone: read-only locale text plus the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
    onValidChange: (Boolean) -> Unit = {},
) {
    val laptop = isLaptop
    val date = isoOrNull(value)
    var draft by remember(value) { mutableStateOf(if (date == null) "" else formatDate(date)) }
    var pickerOpen by remember { mutableStateOf(false) }
    val valid = when {
        !laptop -> date != null || optional
        draft.isBlank() -> optional
        else -> parseLaptopDate(draft) != null
    }
    LaunchedEffect(valid) { onValidChange(valid) }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        WallyTextField(
            multiline = false,
            value = draft,
            onValueChange = { text ->
                draft = text
                val parsed = parseLaptopDate(text)
                if (parsed != null) onValueChange(parsed.toString()) else if (text.isBlank() && optional) onValueChange("")
            },
            readOnly = !laptop,
            label = { Text(label) },
            placeholder = { if (!laptop) Text("None") },
            isError = !valid,
            supportingText = { if (laptop && !valid) Text("Use MM-dd-yyyy, for example 11-10-2026", color = MaterialTheme.colorScheme.error) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { pickerOpen = true }) { Text("Pick") }
        if (optional && value.isNotBlank()) TextButton(onClick = { draft = ""; onValueChange("") }) { Text("Clear") }
    }

    if (pickerOpen) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.let { dateToPickerMillis(it) })
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onValueChange(pickerMillisToDate(it).toString()) }
                    pickerOpen = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** A 24-hour time input; [value] is the canonical "HH:mm" ("" = none). Same draft/validity rules as [DateField]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
    onValidChange: (Boolean) -> Unit = {},
) {
    val laptop = isLaptop
    val parsed = parseTimeOrNull(value)
    var draft by remember(value) { mutableStateOf(if (parsed == null) "" else formatTime(parsed.first, parsed.second)) }
    var pickerOpen by remember { mutableStateOf(false) }
    val valid = when {
        !laptop -> parsed != null || optional
        draft.isBlank() -> optional
        else -> parseTimeOrNull(draft) != null
    }
    LaunchedEffect(valid) { onValidChange(valid) }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        WallyTextField(
            multiline = false,
            value = draft,
            onValueChange = { text ->
                draft = text
                val t = parseTimeOrNull(text)
                if (t != null) onValueChange(formatTime(t.first, t.second)) else if (text.isBlank() && optional) onValueChange("")
            },
            readOnly = !laptop,
            label = { Text(label) },
            placeholder = { if (!laptop) Text("None") },
            isError = !valid,
            supportingText = { if (laptop && !valid) Text("Use a 24-hour time like 13:05", color = MaterialTheme.colorScheme.error) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { pickerOpen = true }) { Text("Pick") }
        if (optional && value.isNotBlank()) TextButton(onClick = { draft = ""; onValueChange("") }) { Text("Clear") }
    }

    if (pickerOpen) {
        val state = rememberTimePickerState(initialHour = parsed?.first ?: 12, initialMinute = parsed?.second ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = { onValueChange(formatTime(state.hour, state.minute)); pickerOpen = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Cancel") } },
        )
    }
}
