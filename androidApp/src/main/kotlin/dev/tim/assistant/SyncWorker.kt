package dev.tim.assistant

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.data.WorkOutcome
import dev.tim.assistant.data.workResultFor
import kotlin.time.Clock

/** Background sync every 15 minutes: no Health Connect read (not allowed in the background) and no job queue. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val status = applicationContext.wallyServices().sync.sync(readHealth = false)
        return when (workResultFor(status)) {
            WorkOutcome.Success -> {
                Log.i("SyncWorker", "sync done at ${Clock.System.now()}")
                Result.success()
            }
            WorkOutcome.Retry -> Result.retry()
            WorkOutcome.Failure -> {
                Log.w("SyncWorker", if (status == SyncStatus.WrongToken) "sync failed: wrong token" else "sync failed: not configured")
                Result.failure()
            }
        }
    }
}
