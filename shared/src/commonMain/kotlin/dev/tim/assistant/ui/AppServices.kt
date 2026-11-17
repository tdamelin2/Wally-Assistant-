package dev.tim.assistant.ui

import dev.tim.assistant.ai.ChatController
import dev.tim.assistant.ai.ChatKv
import dev.tim.assistant.data.ChatApi
import dev.tim.assistant.data.DriverFactory
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.data.SyncEngine
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.health.HealthSource
import dev.tim.assistant.notify.LaunchRequests
import dev.tim.assistant.notify.NotifyCoordinator
import dev.tim.assistant.notify.NotifySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The app's service graph. Create exactly one per process, outside composition. */
class AppServices(
    driverFactory: DriverFactory,
    healthSource: HealthSource? = null,
    usageSource: dev.tim.assistant.usage.UsageSource? = null,
    /** True on the laptop (the desktop app runs the job queue). */
    val runsJobs: Boolean = false,
    /** The phone's way to show and schedule reminders (null on the laptop). */
    notifySink: NotifySink? = null,
    /** The phone's own language model (the desktop keeps the default: no model, every generation fails). */
    val phoneBrain: dev.tim.assistant.ai.PhoneBrain = dev.tim.assistant.ai.NoPhoneBrain,
    /** Battery, power saving and foreground of the phone (the desktop keeps the default: never allowed). */
    val deviceState: dev.tim.assistant.ai.DeviceState = dev.tim.assistant.ai.NoDeviceState,
) {
    val store = LocalStore(AssistantDb(driverFactory.createDriver()))
    val repo = Repository(store)
    /** The one HTTP client of the process: sync, Settings and the study jobs all use it. */
    val client = SyncClient()
    val sync = SyncEngine(store, client, healthSource = healthSource, usageSource = usageSource)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    /** The tab Tim was on before he opened Chat (the app keeps it here); a chat message sends it as "Tim is looking at". */
    @kotlin.concurrent.Volatile
    var lastScreen: String? = null

    /** The HP routes (chat, memory, approvals, reminders) over the same server address and device token as sync. */
    val chatApi = ChatApi({ sync.serverConfig() })

    /**
     * The Wally chat lives on the HP; this controller (process-owned, not a screen) keeps the open chat and the outbox of
     * messages written offline, and it is the one place Tim's Yes/No on an approval goes to the server.
     */
    val chat: ChatController by lazy {
        ChatController(
            scope = scope,
            api = chatApi,
            kv = object : ChatKv {
                override fun get(key: String) = repo.setting(key)
                override fun put(key: String, value: String) = repo.putSetting(key, value)
            },
            clock = clock,
            screen = { lastScreen },
            phoneBrain = phoneBrain,
            deviceState = deviceState,
        )
    }

    /** Taps on reminders waiting for the screen to act on them (the phone Activity posts here). */
    val launch = LaunchRequests()

    /** Phone reminders (null on the laptop): fetched from the HP after every successful sync. */
    val notify: NotifyCoordinator? = notifySink?.let {
        NotifyCoordinator(
            api = chatApi,
            kv = object : ChatKv {
                override fun get(key: String) = repo.setting(key)
                override fun put(key: String, value: String) = repo.putSetting(key, value)
            },
            sink = it,
            checkinDoneToday = { repo.checkinForToday() != null },
            clock = clock,
        )
    }

    init {
        // Once after every successful sync (the sync lock is already released): send what was queued offline.
        sync.onSynced { scope.launch { chat.drain() } }
        // The chat listens to the foreground (stop writing when the app leaves, send what waits when it returns).
        if (phoneBrain !== dev.tim.assistant.ai.NoPhoneBrain) chat
        // Reminders are fetched inside the sync call itself, so a background worker waits for them before it ends.
        notify?.let { n -> sync.onSynced { n.fetch() } }
    }

    /** Swipe-then-Undo for the whole process: one pending action, committed through the repository. */
    val undo = UndoController(scope, commit = { commitPendingAction(repo, it) }, onCommitted = { syncInBackground() })

    /** Starts a sync without waiting for it. */
    fun syncInBackground() {
        scope.launch { sync.sync() }
    }
}