package dev.tim.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.tim.assistant.ui.forms.WallyTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.tim.assistant.ui.screentime.spotifyText
import dev.tim.assistant.usage.DesktopControls
import dev.tim.assistant.usage.UsageAccess
import androidx.compose.ui.unit.dp
import dev.tim.assistant.ai.xpsOllamaUrl
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.tim.assistant.data.ApiResult
import dev.tim.assistant.study.moodleLaunchUrl
import dev.tim.assistant.ui.study.failureText
import kotlin.random.Random
import dev.tim.assistant.data.ConnectionResult
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.time.JERUSALEM
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    services: AppServices,
    usageAccess: UsageAccess? = null,
    desktopControls: DesktopControls? = null,
) {
    val repo = services.repo
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(repo.setting(SettingKeys.SERVER_URL) ?: "") }
    var token by remember { mutableStateOf(repo.setting(SettingKeys.DEVICE_TOKEN) ?: "") }
    var ollama by remember { mutableStateOf(xpsOllamaUrl(repo.setting(SettingKeys.OLLAMA_URL))) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val status by services.sync.status.collectAsState()

    fun save(key: String, value: String) {
        repo.putSetting(key, value.trim())
        services.sync.refreshStatus()
    }

    val lastSync = (status as? SyncStatus.Synced)?.at ?: repo.setting(SettingKeys.LAST_SYNC_AT)?.toLongOrNull()

    if (LocalLayoutMode.current == LayoutMode.Wide) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsCard("Sync") {
                    SyncedLine(services)
                    Text(
                        if (lastSync == null) "Last sync: never"
                        else "Last sync: " + Instant.fromEpochMilliseconds(lastSync).toLocalDateTime(JERUSALEM).toString().replace('T', ' ').take(16),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { services.syncInBackground() }) { Text("Sync now") }
                    WallyTextField(
                        multiline = false,
                        value = url,
                        onValueChange = { url = it; save(SettingKeys.SERVER_URL, it) },
                        label = { Text("Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WallyTextField(
                        multiline = false,
                        value = token,
                        onValueChange = { token = it; save(SettingKeys.DEVICE_TOKEN, it) },
                        label = { Text("Device token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        testResult = "Testing…"
                        scope.launch {
                            testResult = when (services.sync.testConnection(url, token)) {
                                ConnectionResult.Connected -> "Connected"
                                ConnectionResult.WrongToken -> "Wrong token"
                                ConnectionResult.Unreachable -> "Server unreachable"
                            }
                        }
                    }) { Text("Test connection") }
                    testResult?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                }
                SettingsCard("Connections") {
                    MoodleSection(services)
                    SpotifySection(services)
                    Text("Screentime", style = MaterialTheme.typography.titleMedium)
                    Text("Laptop usage is recorded by this app.")
                }
                SettingsCard("Reminders") { RemindersSection(services) }
                if (desktopControls != null) {
                    SettingsCard("Laptop only") {
                        LaptopOnlyRows(desktopControls)
                        WallyTextField(
                            multiline = false,
                            value = ollama,
                            onValueChange = { ollama = it; save(SettingKeys.OLLAMA_URL, it) },
                            label = { Text("XPS Ollama URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (LocalLayoutMode.current == LayoutMode.Narrow) SyncStatusLine(services)
        Text("Sync", style = MaterialTheme.typography.titleMedium)
        WallyTextField(
            multiline = false,
            value = url,
            onValueChange = { url = it; save(SettingKeys.SERVER_URL, it) },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        WallyTextField(
            multiline = false,
            value = token,
            onValueChange = { token = it; save(SettingKeys.DEVICE_TOKEN, it) },
            label = { Text("Device token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Laptop-only: the phone keeps the stored value and its default, it just does not show the field.
        if (isLaptop) {
            WallyTextField(
                multiline = false,
                value = ollama,
                onValueChange = { ollama = it; save(SettingKeys.OLLAMA_URL, it) },
                label = { Text("XPS Ollama URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                testResult = "Testing…"
                scope.launch {
                    testResult = when (services.sync.testConnection(url, token)) {
                        ConnectionResult.Connected -> "Connected"
                        ConnectionResult.WrongToken -> "Wrong token"
                        ConnectionResult.Unreachable -> "Server unreachable"
                    }
                }
            }) { Text("Test connection") }
            Button(onClick = { services.syncInBackground() }) { Text("Sync now") }
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(
            if (lastSync == null) "Last sync: never"
            else "Last sync: " + Instant.fromEpochMilliseconds(lastSync).toLocalDateTime(JERUSALEM).toString().replace('T', ' ').take(16),
        )
        Text("Connections", style = MaterialTheme.typography.titleMedium)
        MoodleSection(services)
        ScreentimeSection(usageAccess, desktopControls)
        SpotifySection(services)
        Text("Reminders", style = MaterialTheme.typography.titleMedium)
        RemindersSection(services)
        if (!isLaptop) {
            Text("Phone brain", style = MaterialTheme.typography.titleMedium)
            PhoneBrainSection(services)
        }
    }
}

/** One rounded card of the laptop Settings page. */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The laptop's tray and start-with-Windows switches (the "Laptop only" card). */
@Composable
private fun LaptopOnlyRows(controls: DesktopControls) {
    var tray by remember { mutableStateOf(controls.keepInTray()) }
    var autostart by remember { mutableStateOf(controls.autostartEnabled()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = autostart,
            enabled = controls.autostartAvailable(),
            onCheckedChange = { autostart = it; controls.setAutostart(it) },
        )
        Text("Start with Windows", modifier = Modifier.weight(1f))
    }
    if (!controls.autostartAvailable()) {
        Text("Available in the installed app only.", style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = tray, onCheckedChange = { tray = it; controls.setKeepInTray(it) })
        Text("Keep running in tray when closed", modifier = Modifier.weight(1f))
    }
}

/** The sync status as text, with a clock that refreshes while the screen is shown (the phone has no sidebar to show it). */
@Composable
private fun SyncStatusLine(services: AppServices) {
    val status by services.sync.status.collectAsState()
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    LaunchedEffect(status) { now = Clock.System.now().toEpochMilliseconds() }
    Text(syncBarText(status, now), style = MaterialTheme.typography.titleMedium)
}

/** Screentime settings: phone usage access, or the laptop's tray and start-with-Windows switches. */
@Composable
private fun ScreentimeSection(usageAccess: UsageAccess?, controls: DesktopControls?) {
    if (usageAccess == null && controls == null) return
    Text("Screentime", style = MaterialTheme.typography.titleMedium)
    if (usageAccess != null) {
        var granted by remember { mutableStateOf(usageAccess.granted()) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = usageAccess.granted() }
        Text(if (granted) "Usage access: allowed" else "Usage access: not allowed")
        OutlinedButton(onClick = { usageAccess.openSettings() }) {
            Text(if (granted) "Open usage access settings" else "Grant usage access")
        }
    }
    if (controls != null) {
        var tray by remember { mutableStateOf(controls.keepInTray()) }
        var autostart by remember { mutableStateOf(controls.autostartEnabled()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = tray, onCheckedChange = { tray = it; controls.setKeepInTray(it) })
            Text("Keep running in tray when the window is closed", modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = autostart,
                enabled = controls.autostartAvailable(),
                onCheckedChange = { autostart = it; controls.setAutostart(it) },
            )
            Text("Start with Windows", modifier = Modifier.weight(1f))
        }
        if (!controls.autostartAvailable()) {
            Text("Available in the installed app only.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The Spotify status the server reported at the last sync. */
@Composable
private fun SpotifySection(services: AppServices) {
    val version by services.store.changes.collectAsState()
    val ok = remember(version) { services.repo.spotifyOk() }
    val calendarOk = remember(version) { services.repo.calendarOk() }
    Text("Spotify", style = MaterialTheme.typography.titleMedium)
    Text(spotifyText(ok))
    Text(calendarStatusLine(calendarOk))
}

/** The Wally Calendar status the server reported at the last sync. */
fun calendarStatusLine(calendarOk: Boolean?): String = when (calendarOk) {
    true -> "Wally Calendar: connected"
    false -> "Wally Calendar: not reachable - Wally keeps the saved events"
    null -> "Wally Calendar: not set up"
}

/** Moodle login for the Study tab: open the login page, paste the link the browser tried to open, check now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodleSection(services: AppServices) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val version by services.store.changes.collectAsState()
    val baseUrl = remember(version) { services.repo.moodleBaseUrl() }
    val moodleOk = remember(version) { services.repo.moodleOk() }
    var link by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Moodle", style = MaterialTheme.typography.titleMedium)
    Text(
        when (moodleOk) {
            true -> "Connected"
            false -> "Reconnect Moodle - the login expired"
            null -> if (baseUrl == null) "Not set up on the server" else "Not connected yet"
        },
    )
    OutlinedButton(
        onClick = { if (baseUrl != null) uriHandler.openUri(moodleLaunchUrl(baseUrl, Random.nextInt(1, 1_000_000_000))) },
        enabled = baseUrl != null,
    ) { Text("Open Moodle login") }
    Text("In Chrome: F12 > Network > launch.php > location", style = MaterialTheme.typography.bodySmall)
    WallyTextField(
        multiline = false,
        value = link,
        onValueChange = { link = it },
        label = { Text("Paste Moodle link") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = link.isNotBlank() && !busy,
            onClick = {
                val cfg = services.sync.serverConfig()
                if (cfg == null) {
                    result = "Set up the server above first"
                } else {
                    busy = true
                    result = "Connecting..."
                    val pasted = link.trim()
                    scope.launch {
                        val r = services.client.moodleToken(cfg, pasted)
                        result = failureText(r) ?: "Connected - ${(r as ApiResult.Ok).value} courses found"
                        if (r is ApiResult.Ok) link = ""
                        busy = false
                        services.sync.sync()
                    }
                }
            },
        ) { Text("Connect") }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                val cfg = services.sync.serverConfig()
                if (cfg == null) {
                    result = "Set up the server above first"
                } else {
                    busy = true
                    result = "Checking..."
                    scope.launch {
                        val r = services.client.studySyncNow(cfg)
                        result = failureText(r) ?: "Check started - ${(r as ApiResult.Ok).value} new files"
                        busy = false
                        services.sync.sync()
                    }
                }
            },
        ) { Text("Check now") }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/** Headings and screen-level buttons of the Settings screen, for the label-uniqueness check. */
internal val settingsLabels: List<String> = listOf(
    "Sync", "Connections", "Server URL", "Device token", "XPS Ollama URL", "Test connection", "Sync now", "Screentime", "Spotify", "Moodle",
    "Open Moodle login", "Paste Moodle link", "Connect", "Check now",
    "Laptop only", "Start with Windows", "Keep running in tray when closed",
) + reminderLabels
