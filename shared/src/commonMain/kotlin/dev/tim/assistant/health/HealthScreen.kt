package dev.tim.assistant.health

import dev.tim.assistant.ui.formatIsoDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import dev.tim.assistant.ui.RefreshBox
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.tim.assistant.charts.HealthLineChart
import dev.tim.assistant.charts.NullableBarChart
import dev.tim.assistant.db.Workout
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.time.localDateOf
import kotlinx.datetime.toLocalDateTime
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.isLaptop
import dev.tim.assistant.ui.lists.LogCard
import dev.tim.assistant.ui.rememberQuery
import kotlin.time.Instant

private class HealthScreenData(
    val series: HealthSeries,
    val workouts: List<Workout>,
    val rows: List<dev.tim.assistant.db.Health_day>,
    val today: String,
)

internal fun sleepText(minutes: Long): String = "${minutes / 60} h ${minutes % 60} min"

internal fun distanceKm(meters: Long): String {
    val tenths = (meters + 50) / 100
    return "${tenths / 10}.${tenths % 10} km"
}

@OptIn(ExperimentalLayoutApi::class)
/** Health tab: 30-day sleep and steps charts, a per-day list and the recent workouts. [healthUi] is null on the laptop. */
@Composable
fun HealthScreen(services: AppServices, healthUi: HealthUi?) {
    val data = rememberQuery(services) {
        val today = todayLocal()
        val rows = services.repo.healthDays()
        val series = healthSeries(rows, today)
        HealthScreenData(series, recentWorkouts(services.repo.workouts(), today), rows, today)
    }.value

    // Phone permission state: re-checked when the tab opens, when the app returns to the foreground and after a request.
    var available by remember { mutableStateOf(healthUi?.available() ?: false) }
    var granted by remember { mutableStateOf(true) }
    suspend fun recheck() {
        if (healthUi == null) return
        available = healthUi.available()
        granted = if (available) healthUi.hasPermissions() else true
    }
    var resumeTick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(healthUi, resumeTick) { recheck() }

    RefreshBox(services) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (healthUi != null) {
            if (!available) {
                item { Text("Health Connect not available") }
            } else if (!granted) {
                item {
                    Button(onClick = {
                        healthUi.requestPermissions { ok -> granted = ok }
                    }) { Text("Allow Health Connect") }
                }
            }
        }
        if (data == null) {
            item { Text("Loading") }
            return@LazyColumn
        }
        if (isLaptop) {
            item(key = "laptop") { LaptopHealth(data) }
            return@LazyColumn
        }
        item(key = "tiles") { HealthTiles(data) }
        item { Text("Sleep, last 30 days", style = MaterialTheme.typography.titleMedium) }
        item { HealthLineChart(data.series.sleepHours, data.series.labels, guideHours = 8.0) }
        item { Text("Steps, last 30 days", style = MaterialTheme.typography.titleMedium) }
        item { NullableBarChart(data.series.steps, data.series.labels) }
        item { Text("Workouts, last 30 days", style = MaterialTheme.typography.titleMedium) }
        if (data.workouts.isEmpty()) {
            item { Text("No workouts") }
        } else {
            items(data.workouts, key = { it.id }) { w ->
                val minutes = ((w.endAt - w.startAt) / 60_000).coerceAtLeast(0)
                val km = w.distanceMeters?.let { ", " + distanceKm(it) } ?: ""
                Text("${w.type}, ${formatIsoDate(localDateOf(Instant.fromEpochMilliseconds(w.startAt)))}, $minutes min$km", modifier = Modifier.animateItem())
            }
        }
    }
    }
}

@Composable
private fun HealthTiles(data: HealthScreenData) {
    val sleep = data.series.sleepHours.filterNotNull()
    val steps = data.series.steps.filterNotNull()
    val sleepAvg = if (sleep.isEmpty()) "no data" else { val m = (sleep.average() * 60).toLong(); sleepText(m) }
    val stepsAvg = if (steps.isEmpty()) "no data" else steps.average().toLong().toString()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HealthTile("Sleep (avg)", sleepAvg, Modifier.weight(1f))
        HealthTile("Steps (avg)", stepsAvg, Modifier.weight(1f))
        HealthTile("Workouts", data.workouts.size.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun HealthTile(title: String, value: String, modifier: Modifier) {
    LogCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** "From Garmin · synced 14:05" (Jerusalem time), or "No health data synced yet". */
internal fun garminLine(syncedAt: Long?): String {
    if (syncedAt == null) return "No health data synced yet"
    val time = Instant.fromEpochMilliseconds(syncedAt).toLocalDateTime(JERUSALEM).toString().substring(11, 16)
    return "From Garmin · synced $time"
}

private fun dash(value: String?): String = value ?: "—"

/** The laptop Health pane: Garmin line, three tiles, 30-day charts, workouts and the last seven days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaptopHealth(data: HealthScreenData) {
    val tiles = laptopHealthTiles(data.rows, data.today)
    val dayRows = laptopDayRows(data.rows, data.today)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(garminLine(healthSyncedAt(data.rows)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HealthTile("Sleep last night", dash(tiles.sleepMinutes?.let { sleepText(it) }), Modifier.weight(1f))
            HealthTile("Steps yesterday", dash(tiles.stepsYesterday?.toString()), Modifier.weight(1f))
            HealthTile(
                "Resting HR",
                dash(tiles.restingHr?.let { "$it bpm" + if (tiles.restingHrDate != null && tiles.restingHrDate != data.today) " (${formatIsoDate(tiles.restingHrDate)})" else "" }),
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LogCard(Modifier.weight(1f)) {
                Text("Sleep, last 30 days", style = MaterialTheme.typography.titleMedium)
                HealthLineChart(data.series.sleepHours, data.series.labels, guideHours = 8.0)
            }
            LogCard(Modifier.weight(1f)) {
                Text("Steps, last 30 days", style = MaterialTheme.typography.titleMedium)
                NullableBarChart(data.series.steps, data.series.labels)
            }
        }
        LogCard {
            Text("Workouts, last 30 days", style = MaterialTheme.typography.titleMedium)
            if (data.workouts.isEmpty()) {
                Text("No workouts")
            } else {
                data.workouts.forEach { w ->
                    val minutes = ((w.endAt - w.startAt) / 60_000).coerceAtLeast(0)
                    val km = w.distanceMeters?.let { ", " + distanceKm(it) } ?: ""
                    Text("${w.type}, ${formatIsoDate(localDateOf(Instant.fromEpochMilliseconds(w.startAt)))}, $minutes min$km")
                }
            }
        }
        LogCard {
            Text("Days", style = MaterialTheme.typography.titleMedium)
            dayRows.forEach { r ->
                Column(Modifier.fillMaxWidth()) {
                    Text(formatIsoDate(r.date), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Sleep: " + dash(r.sleepMinutes?.let { sleepText(it) }))
                        Text("Steps: " + dash(r.steps?.toString()))
                        Text("Resting HR: " + dash(r.restingHr?.let { "$it bpm" }))
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** Texts the laptop Health pane adds (the phone tiles are not shown there). */
internal val laptopHealthLabels: List<String> = listOf(
    "Sleep last night", "Steps yesterday", "Resting HR", "Sleep, last 30 days", "Steps, last 30 days",
    "Workouts, last 30 days", "Days", "From Garmin",
)

/** Headings and screen-level buttons of the Health screen, for the label-uniqueness check. */
internal val healthLabels: List<String> = listOf(
    "Allow Health Connect", "Sleep (avg)", "Steps (avg)", "Workouts", "Sleep, last 30 days", "Steps, last 30 days", "Days", "Workouts, last 30 days",
)
