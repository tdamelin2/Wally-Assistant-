package dev.tim.assistant.data

import dev.tim.assistant.db.AssistantDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The local database plus the one lock that serializes every write transaction
 * (UI saves and sync pull pages), so a save can never slip between a compare and a write.
 * Never call [write] from inside [write] (the lock is not reentrant).
 */
class LocalStore(val db: AssistantDb) {
    private val lock = Mutex()
    private val version = MutableStateFlow(0L)

    /** Bumped after every committed write; screens reload their lists when it changes. */
    val changes: StateFlow<Long> = version

    suspend fun <T> write(block: () -> T): T {
        val result = lock.withLock { db.transactionWithResult { block() } }
        version.value = version.value + 1
        return result
    }

    /** Runs [block] in one write transaction; signals list reloads only when [block] returns true. */
    suspend fun writeSignalIf(block: () -> Boolean): Boolean {
        val wrote = lock.withLock { db.transactionWithResult { block() } }
        if (wrote) version.value = version.value + 1
        return wrote
    }

    /** Like [write] but does not signal list reloads (used for page writes that touch nothing visible). */
    suspend fun <T> writeQuiet(block: () -> T): T = lock.withLock { db.transactionWithResult { block() } }

    fun setting(key: String): String? = db.kvQueries.get(key).executeAsOneOrNull()

    fun putSetting(key: String, value: String) {
        db.kvQueries.put(key, value)
    }
}

object SettingKeys {
    const val SERVER_URL = "serverUrl"
    const val DEVICE_TOKEN = "deviceToken"
    const val OLLAMA_URL = "ollamaUrl"
    const val CURSOR = "cursor"
    const val CURSOR_SCHEMA_VERSION = "cursorSchemaVersion"
    const val LAST_SYNC_AT = "lastSyncAt"
    const val GOOGLE_OK = "googleOk"
    const val GOOGLE_LAST_FETCH_AT = "googleLastFetchAt"
    const val MOODLE_OK = "moodleOk"
    const val MOODLE_BASE_URL = "moodleBaseUrl"
    const val SPOTIFY_OK = "spotifyOk"
    const val CALENDAR_OK = "calendarOk"
    const val SIDEBAR_COLLAPSED = "ui.sidebarCollapsed"
    const val USAGE_LAST_READ = "usage.lastRead"
    /** Desktop only: keep running in the tray when the window is closed (default on). */
    const val KEEP_IN_TRAY = "ui.keepInTray"
}
