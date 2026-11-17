package dev.tim.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val REMINDER_SYNC_TIMEOUT_MS = 10_000L

/** The system clock fired a scheduled reminder: sync briefly (so a check-in done elsewhere is known), then decide and show. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMINDER) return
        val id = intent.getLongExtra(EXTRA_ROW_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        val services = context.applicationContext.wallyServices()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(REMINDER_SYNC_TIMEOUT_MS) { services.sync.sync(readHealth = false) }
                services.notify?.onAlarm(id)
            } finally {
                pending.finish()
            }
        }
    }
}
