package dev.tim.assistant.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** True when the user has allowed "usage access" for this app. */
fun usageAccessGranted(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Reads phone app usage from the system usage log. Holds only the application context. */
class PhoneUsage(context: Context) : UsageSource {
    private val appContext = context.applicationContext

    override suspend fun readSince(fromMs: Long): List<UsageEvent> = withContext(Dispatchers.IO) {
        if (!usageAccessGranted(appContext)) throw UsageUnavailable("usage access not granted")
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: throw UsageUnavailable("no usage service")
        val events = try {
            manager.queryEvents(fromMs, System.currentTimeMillis() + 1)
        } catch (e: SecurityException) {
            throw UsageUnavailable("usage query refused")
        } ?: throw UsageUnavailable("usage query failed")
        val out = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                EVENT_ACTIVITY_RESUMED -> UsageEventType.RESUMED
                EVENT_ACTIVITY_PAUSED -> UsageEventType.PAUSED
                EVENT_SCREEN_NON_INTERACTIVE, EVENT_KEYGUARD_SHOWN, EVENT_DEVICE_SHUTDOWN -> UsageEventType.SCREEN_OFF
                else -> null
            } ?: continue
            val pkg = event.packageName ?: ""
            if (pkg.isEmpty() && type != UsageEventType.SCREEN_OFF) continue
            out += UsageEvent(pkg, type, event.timeStamp)
        }
        out
    }

    private companion object {
        // UsageEvents.Event constants (ACTIVITY_RESUMED == MOVE_TO_FOREGROUND, ACTIVITY_PAUSED == MOVE_TO_BACKGROUND).
        const val EVENT_ACTIVITY_RESUMED = 1
        const val EVENT_ACTIVITY_PAUSED = 2
        const val EVENT_SCREEN_NON_INTERACTIVE = 16
        const val EVENT_KEYGUARD_SHOWN = 17
        const val EVENT_DEVICE_SHUTDOWN = 26
    }
}
