package dev.tim.assistant.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The work name and period of the phone's background sync (WorkManager's minimum period is 15 minutes). */
const val SYNC_WORK_NAME = "wally-sync"
const val SYNC_WORK_MINUTES = 15L

/** What a background sync run tells WorkManager to do next. */
enum class WorkOutcome { Success, Retry, Failure }

/** Synced is done; Offline/Syncing try again later; a wrong or missing setup cannot fix itself. */
fun workResultFor(status: SyncStatus): WorkOutcome = when (status) {
    is SyncStatus.Synced -> WorkOutcome.Success
    is SyncStatus.Offline -> WorkOutcome.Retry
    SyncStatus.Syncing -> WorkOutcome.Retry
    SyncStatus.WrongToken, SyncStatus.NotConfigured -> WorkOutcome.Failure
}

/**
 * Runs [sync] after every [interval] until the returned Job is cancelled. A failing sync never stops the loop;
 * cancellation always does.
 */
fun CoroutineScope.launchSyncTimer(
    interval: Duration = 10.minutes,
    wait: suspend (Duration) -> Unit = { delay(it) },
    sync: suspend () -> Unit,
): Job = launch {
    while (isActive) {
        wait(interval)
        try {
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("timer sync failed: ${e::class.simpleName}")
        }
    }
}
