package dev.tim.assistant.ui

import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.tim.assistant.data.SyncStatus

/**
 * Pull down on the scrolling list to sync. The spinner follows the sync status; a pull while a sync is already
 * running does nothing. The [content] must contain the scrolling list (an empty list still scrolls).
 */
@Composable
fun RefreshBox(services: AppServices, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val status by services.sync.status.collectAsState()
    val syncing = status == SyncStatus.Syncing
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = { if (!syncing) services.syncInBackground() },
        modifier = modifier,
    ) { content() }
}
