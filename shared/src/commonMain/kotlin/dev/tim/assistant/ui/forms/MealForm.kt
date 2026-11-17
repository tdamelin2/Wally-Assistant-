package dev.tim.assistant.ui.forms

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.db.Meal
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.ui.DateField
import dev.tim.assistant.ui.TimeField
import dev.tim.assistant.ui.jobIsActive
import dev.tim.assistant.ui.rememberQuery
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

const val MEAL_TYPE = "MEAL"
const val SNACK_TYPE = "SNACK"
const val MEAL_MAX_CHARS = 1000

private val TIME = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")
private val DATE_SHAPE = Regex("""^\d{4}-\d{2}-\d{2}$""")

/** Strictly parses "YYYY-MM-DD" and "HH:MM" (Asia/Jerusalem) into epoch milliseconds; null when either is invalid. */
fun parseEatenAt(date: String, time: String): Long? {
    val d = date.trim()
    val t = time.trim()
    if (!DATE_SHAPE.matches(d)) return null
    val m = TIME.matchEntire(t) ?: return null
    val localDate = try {
        LocalDate.parse(d)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val local = LocalDateTime(localDate, LocalTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
    return local.toInstant(JERUSALEM).toEpochMilliseconds()
}

@Composable
fun MealForm(services: dev.tim.assistant.ui.AppServices, existing: Meal?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = remember(existing) {
        val at = existing?.let { Instant.fromEpochMilliseconds(it.eatenAt) } ?: Clock.System.now()
        val local = at.toLocalDateTime(JERUSALEM)
        local.date.toString() to local.time.toString().take(5)
    }
    var type by remember { mutableStateOf(existing?.type ?: MEAL_TYPE) }
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var date by remember { mutableStateOf(initial.first) }
    var time by remember { mutableStateOf(initial.second) }

    val unchanged = existing != null && date.trim() == initial.first && time.trim() == initial.second
    var dateValid by remember { mutableStateOf(true) }
    var timeValid by remember { mutableStateOf(true) }
    val lookup = rememberQuery(services, existing?.id) { existing?.id?.let { services.repo.latestMealLookup(it) } }.value
    val eatenAt: Long? = if (!dateValid || !timeValid) null else if (unchanged) existing?.eatenAt else parseEatenAt(date, time)

    FormScaffold(
        title = if (existing == null) "New meal" else "Edit meal",
        canSave = text.isNotBlank() && text.length <= MEAL_MAX_CHARS && eatenAt != null,
        saveLabel = laptopSaveLabel("Save meal"),
        onCancel = onDone,
        onSave = {
            if (eatenAt != null) {
                scope.launch {
                    services.repo.saveMeal(existing?.id, type, text, eatenAt)
                    onDone()
                    services.syncInBackground()
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == MEAL_TYPE, onClick = { type = MEAL_TYPE }, label = { Text("Meal") })
            FilterChip(selected = type == SNACK_TYPE, onClick = { type = SNACK_TYPE }, label = { Text("Snack") })
        }
        WallyTextField(
            multiline = true,
            value = text,
            onValueChange = { if (it.length <= MEAL_MAX_CHARS) text = it },
            label = { Text("What did you eat?") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        DateField("Date", date, { date = it }, onValidChange = { dateValid = it })
        TimeField("Time", time, { time = it }, onValidChange = { timeValid = it })
        Text("Calories and nutrition are estimates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (existing != null) {
            val unsaved = text.trim() != existing.text.trim()
            OutlinedButton(
                enabled = !unsaved && !jobIsActive(lookup),
                onClick = {
                    scope.launch {
                        services.repo.queueJob("MEAL_LOOKUP", buildJsonObject { put("mealId", existing.id) }.toString())
                        services.syncInBackground()
                    }
                },
            ) { Text("Look up") }
            if (unsaved) Text("Save your changes first, then look up.", style = MaterialTheme.typography.bodySmall)
        }
    }
}