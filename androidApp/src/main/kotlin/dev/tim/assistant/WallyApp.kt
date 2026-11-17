package dev.tim.assistant

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.tim.assistant.data.DriverFactory
import dev.tim.assistant.data.SYNC_WORK_MINUTES
import dev.tim.assistant.data.SYNC_WORK_NAME
import dev.tim.assistant.health.HealthConnectSource
import dev.tim.assistant.ui.AppServices
import dev.tim.assistant.usage.PhoneUsage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Owns the one service graph for the whole process (the activity and the background worker share it). */
class WallyApp : Application() {
    private var services: AppServices? = null
    private val deviceState by lazy { AndroidDeviceState(applicationContext) }

    @Synchronized
    fun services(): AppServices =
        services ?: AppServices(
            DriverFactory(applicationContext),
            HealthConnectSource(applicationContext),
            PhoneUsage(applicationContext),
            notifySink = Notifier(applicationContext),
            phoneBrain = LiteRtPhoneBrain(applicationContext, speculative = true),
            deviceState = deviceState,
        ).also { services = it }

    /** Under critical memory pressure the phone model is the first thing to give back. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) {
            synchronized(this) { services }?.phoneBrain?.unload()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Foreground = an activity is started: the phone brain only writes while Tim has the app open.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = deviceState.activityStarted()
            override fun onActivityStopped(activity: Activity) = deviceState.activityStopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_WORK_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(SYNC_WORK_NAME)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        // Alarms are lost when the phone restarts: after every app start ask the clock again for reminders still waiting.
        CoroutineScope(Dispatchers.Default).launch { services().notify?.rescheduleAll() }
    }
}

/** The shared services of this process. */
fun Context.wallyServices(): AppServices = (applicationContext as WallyApp).services()
