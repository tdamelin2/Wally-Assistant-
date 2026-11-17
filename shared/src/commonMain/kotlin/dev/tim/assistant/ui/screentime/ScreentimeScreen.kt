package dev.tim.assistant.ui.screentime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.Listen
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.ui.RefreshBox
import dev.tim.assistant.ui.LaptopColumns
import dev.tim.assistant.ui.UndoTables
import dev.tim.assistant.ui.isLaptop
import dev.tim.assistant.ui.stacksSidePanel
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.tim.assistant.ui.hides
import dev.tim.assistant.ui.rememberQuery
import dev.tim.assistant.usage.DEVICE_LAPTOP
import dev.tim.assistant.usage.DEVICE_PHONE
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.UsageAccess
import dev.tim.assistant.usage.dayTotals
import dev.tim.assistant.usage.topApps
import dev.tim.assistant.usage.unsortedApps

private class ScreentimeData(
    val dates: List<String>,
    val sessions: List<Usage_session>,
    val rules: List<App_rule>,
    val listens: List<Listen>,
    val spotifyOk: Boolean?,
)

/** Which device the totals cover: null is All. */
private val DEVICE_OPTIONS: List<String?> = listOf(null, DEVICE_LAPTOP, DEVICE_PHONE)

/**
 * Screentime: All, Laptop or Phone for This day or 7 days; minutes per label, top apps, a banner for apps
 * without a label (opens [LabelAppsScreen]) and the Listening list. [usageAccess] is non-null on the phone only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreentimeScreen(services: AppServices, usageAccess: UsageAccess?) {
    val repo = services.repo
    var editing by remember { mutableStateOf(false) }
    if (editing && !isLaptop) {
        LabelAppsScreen(services, defaultDevice(usageAccess != null)) { editing = false }
        return
    }
    var range by remember { mutableStateOf(ScreentimeRange.Today) }
    var device by remember { mutableStateOf<String?>(null) }
    val hidden by services.undo.hidden.collectAsState()

    val data = rememberQuery(services, range) {
        val dates = datesFor(range, todayLocal())
        ScreentimeData(
            dates,
            repo.usageSessions(dates.last(), dates.first()),
            repo.appRules(),
            repo.listens(50),
            repo.spotifyOk(),
        )
    }.value

    // Phone usage access: checked when the tab opens and whenever the app returns to the foreground.
    var granted by remember { mutableStateOf(usageAccess?.granted() ?: true) }
    var resumeTick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(usageAccess, resumeTick) {
        val now = usageAccess?.granted() ?: true
        if (now && !granted) services.syncInBackground()
        granted = now
    }

    val list: @Composable () -> Unit = {
    RefreshBox(services) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (usageAccess != null && !granted) {
            item(key = "grant") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Allow usage access so Wally can see which apps you use.")
                    Button(onClick = { usageAccess.openSettings() }) { Text("Grant usage access") }
                }
            }
        }
        item(key = "filters") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DEVICE_OPTIONS.forEach { d ->
                    FilterChip(selected = device == d, onClick = { device = d }, label = { Text(deviceOptionText(d)) })
                }
                ScreentimeRange.entries.forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
                }
            }
        }
        if (data == null) {
            item(key = "loading") { Text("Loading…") }
        } else {
            val rules = data.rules.filter { !hidden.hides(UndoTables.APP_RULE, it.id) }
            val totals = dayTotals(data.sessions, rules, device, data.dates)
            val hasTime = data.sessions.any { (device == null || it.device == device) && it.localDate in data.dates }

            item(key = "minutes-head") { Text("Minutes per label", style = MaterialTheme.typography.titleMedium) }
            if (!hasTime) item(key = "nothing") { Text("Nothing recorded yet", style = MaterialTheme.typography.bodyMedium) }
            items(data.dates, key = { "day:$it" }) { date ->
                val day = totals.getValue(date)
                Column(Modifier.animateItem()) {
                    Text(date, style = MaterialTheme.typography.labelLarge)
                    Text(
                        Label.entries.joinToString(" · ") { "${labelText(it)} ${minutesText(day.getValue(it))}" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            val unlabelled = listOf(DEVICE_PHONE, DEVICE_LAPTOP).sumOf { unsortedApps(data.sessions, rules, it, data.dates).size }
            if (unlabelled > 0 && !isLaptop) {
                item(key = "banner") {
                    Card(
                        Modifier.fillMaxWidth().clickable { editing = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (unlabelled == 1) "1 app has no label yet" else "$unlabelled apps have no label yet",
                                Modifier.weight(1f),
                            )
                            TextButton(onClick = { editing = true }) { Text("Label apps") }
                        }
                    }
                }
            }

            val top = topApps(data.sessions, device, data.dates)
            if (top.isNotEmpty()) {
                item(key = "top-head") { HorizontalDivider(); Text("Top apps", style = MaterialTheme.typography.titleMedium) }
                items(top, key = { "top:${it.app}" }) { t ->
                    Row(Modifier.animateItem().fillMaxWidth()) {
                        Text(t.app, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(msText(t.ms))
                    }
                }
            }

            item(key = "listen-head") { HorizontalDivider(); Text("Listening", style = MaterialTheme.typography.titleMedium) }
            if (data.spotifyOk != true) {
                item(key = "spotify-off") { Text("Spotify not connected", style = MaterialTheme.typography.bodyMedium) }
            } else if (data.listens.isEmpty()) {
                item(key = "no-plays") { Text("No plays yet", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(data.listens, key = { "listen:${it.id}" }) { play ->
                    Column(Modifier.animateItem()) {
                        Text(
                            play.trackName,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        )
                        Text(
                            "${play.artist} · ${playedAtText(play.playedAt)}",
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        )
                    }
                }
            }
        }
    }
    }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (isLaptop) {
            LaptopColumns(stacksSidePanel(maxWidth.value), side = { LabelAppsBody(services, defaultDevice(false), panel = true) }) { list() }
        } else {
            list()
        }
    }
}
