package dev.tim.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.google.hhmm
import kotlin.time.Clock
import kotlinx.coroutines.delay

fun syncBarText(status: SyncStatus, nowMs: Long): String = when (status) {
    is SyncStatus.Synced ->
        if (nowMs - status.at < 60_000) "Synced just now" else "Synced ${(nowMs - status.at) / 60_000} min ago"
    is SyncStatus.Offline -> "Offline — ${status.pending} waiting"
    SyncStatus.WrongToken -> "Wrong token — check Settings"
    SyncStatus.NotConfigured -> "Not set up — open Settings"
    SyncStatus.Syncing -> "Syncing…"
}

enum class SyncIcon { Synced, Syncing, NotSynced }

fun syncIconFor(status: SyncStatus): SyncIcon = when (status) {
    is SyncStatus.Synced -> SyncIcon.Synced
    SyncStatus.Syncing -> SyncIcon.Syncing
    else -> SyncIcon.NotSynced
}

/** The full coloured bar shows only when something needs attention. */
fun showsErrorBar(status: SyncStatus): Boolean = when (status) {
    is SyncStatus.Offline, SyncStatus.WrongToken, SyncStatus.NotConfigured -> true
    is SyncStatus.Synced, SyncStatus.Syncing -> false
}

fun sidebarSyncText(status: SyncStatus): String = when (status) {
    is SyncStatus.Synced -> "Synced ${hhmm(status.at)}"
    else -> syncBarText(status, 0L)
}

/** The current time in ms, refreshed every 30 seconds and whenever the sync status changes. */
@Composable
private fun rememberSyncClock(status: SyncStatus): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    LaunchedEffect(status) { now = Clock.System.now().toEpochMilliseconds() }
    return now
}

/** The quiet "Synced just now" line under a phone screen title. */
@Composable
fun SyncedLine(services: AppServices, modifier: Modifier = Modifier) {
    val status by services.sync.status.collectAsState()
    val now = rememberSyncClock(status)
    Text(
        syncBarText(status, now),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun SyncBar(services: AppServices) {
    val status by services.sync.status.collectAsState()
    val now = rememberSyncClock(status)
    val bad = status is SyncStatus.Offline || status == SyncStatus.WrongToken || status == SyncStatus.NotConfigured
    val color = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Text(
        syncBarText(status, now),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
