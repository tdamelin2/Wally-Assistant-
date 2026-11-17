package dev.tim.assistant.data

import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Health_day
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.db.Workout
import dev.tim.assistant.health.HealthMapper
import dev.tim.assistant.health.HealthSource
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.UnknownTableException
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.usage.UsageSource
import dev.tim.assistant.usage.buildPhoneSessions
import dev.tim.assistant.model.upsertFromSyncRow
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

sealed interface SyncStatus {
    data class Synced(val at: Long) : SyncStatus
    data class Offline(val pending: Int) : SyncStatus
    data object WrongToken : SyncStatus
    data object NotConfigured : SyncStatus
    data object Syncing : SyncStatus
}

sealed interface ConnectionResult {
    data object Connected : ConnectionResult
    data object WrongToken : ConnectionResult
    data object Unreachable : ConnectionResult
}

private const val PUSH_BATCH = 500
/** Encoded row JSON bytes per push batch (a single larger row is sent alone). */
internal const val PUSH_BATCH_BYTES = 4_000_000
private const val PULL_PAGE = 1000
private const val USAGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** Pushes PENDING rows, pulls newer rows, records health. One [sync] runs at a time. */
class SyncEngine(
    private val store: LocalStore,
    private val client: SyncClient,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val healthSource: HealthSource? = null,
    private val usageSource: UsageSource? = null,
) {
    private val syncLock = Mutex()
    private val db get() = store.db
    private val state = MutableStateFlow<SyncStatus>(initialStatus())

    val status: StateFlow<SyncStatus> get() = state

    private fun initialStatus(): SyncStatus {
        val last = store.setting(SettingKeys.LAST_SYNC_AT)?.toLongOrNull()
        return when {
            config() == null -> SyncStatus.NotConfigured
            last != null -> SyncStatus.Synced(last)
            else -> SyncStatus.Offline(pendingCount(store))
        }
    }

    private fun config(): ServerConfig? {
        val url = store.setting(SettingKeys.SERVER_URL)?.trim().orEmpty()
        val token = store.setting(SettingKeys.DEVICE_TOKEN)?.trim().orEmpty()
        return if (url.isEmpty() || token.isEmpty()) null else ServerConfig(url, token)
    }

    /** The server address and device token from settings, or null when either is missing. */
    fun serverConfig(): ServerConfig? = config()

    /** Re-reads settings and publishes NotConfigured / Offline when nothing is syncing. */
    fun refreshStatus() {
        if (state.value == SyncStatus.Syncing) return
        if (config() == null) state.value = SyncStatus.NotConfigured
        else if (state.value == SyncStatus.NotConfigured) state.value = SyncStatus.Offline(pendingCount(store))
    }

    suspend fun testConnection(baseUrl: String, token: String): ConnectionResult {
        if (baseUrl.isBlank()) return ConnectionResult.Unreachable
        return when (client.health(ServerConfig(baseUrl, token.trim()))) {
            is ApiResult.Ok -> ConnectionResult.Connected
            ApiResult.Unauthorized -> ConnectionResult.WrongToken
            is ApiResult.Failure -> ConnectionResult.Unreachable
        }
    }

    @kotlin.concurrent.Volatile
    private var syncedListeners: List<suspend () -> Unit> = emptyList()

    /** Calls [listener] after every sync that ended Synced, once the sync lock is released (so it may sync again or take its own time). */
    fun onSynced(listener: suspend () -> Unit) {
        syncedListeners = syncedListeners + listener
    }

    /** [readHealth] false (background runs) skips only the Health Connect import; everything else is as usual. */
    suspend fun sync(readHealth: Boolean = true): SyncStatus {
        val result = syncOnce(readHealth)
        if (result is SyncStatus.Synced) {
            for (l in syncedListeners) {
                try {
                    l()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logWarning("after-sync step failed: ${e::class.simpleName}")
                }
            }
        }
        return result
    }

    private suspend fun syncOnce(readHealth: Boolean): SyncStatus = syncLock.withLock {
        // Phone usage is collected even when no server is set up.
        try {
            importUsage()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("usage import failed: ${e::class.simpleName}")
        }
        val cfg = config()
        if (cfg == null) {
            state.value = SyncStatus.NotConfigured
            return@withLock SyncStatus.NotConfigured
        }
        state.value = SyncStatus.Syncing
        val result = try {
            runSync(cfg, readHealth)
        } catch (e: kotlinx.coroutines.CancellationException) {
            state.value = SyncStatus.Offline(pendingCount(store))
            throw e
        } catch (e: Exception) {
            SyncStatus.Offline(pendingCount(store))
        }
        state.value = result
        result
    }

    /**
     * Reads Health Connect (outside any store lock) and stores changed days/workouts as PENDING.
     * A failing source never stops the rest of the sync; a persistence failure still does.
     */
    private suspend fun importHealth() {
        val source = healthSource ?: return
        val raw = try {
            source.readLast7Days()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("health read failed: ${e::class.simpleName}")
            null
        } ?: return
        val mapped = HealthMapper.map(raw)
        store.writeSignalIf {
            var wrote = false
            for (d in mapped.days) {
                val id = "health:${d.localDate}"
                val stored = db.health_dayQueries.selectById(id).executeAsOneOrNull()
                val same = stored != null && stored.deleted == 0L && stored.localDate == d.localDate &&
                    stored.sleepMinutes == d.sleepMinutes && stored.steps == d.steps && stored.restingHr == d.restingHr
                if (same) continue
                db.health_dayQueries.upsert(
                    Health_day(
                        id, nextUpdatedAt(stored?.updatedAt), 0, stored?.serverSeq ?: 0, PENDING,
                        d.localDate, d.sleepMinutes, d.steps, d.restingHr,
                    ),
                )
                wrote = true
            }
            for (w in mapped.workouts) {
                val id = "workout:${w.hcRecordId}"
                val stored = db.workoutQueries.selectById(id).executeAsOneOrNull()
                val same = stored != null && stored.deleted == 0L && stored.hcRecordId == w.hcRecordId &&
                    stored.type == w.type && stored.startAt == w.startAt && stored.endAt == w.endAt &&
                    stored.distanceMeters == w.distanceMeters
                if (same) continue
                db.workoutQueries.upsert(
                    Workout(
                        id, nextUpdatedAt(stored?.updatedAt), 0, stored?.serverSeq ?: 0, PENDING,
                        w.hcRecordId, w.type, w.startAt, w.endAt, w.distanceMeters,
                    ),
                )
                wrote = true
            }
            wrote
        }
    }

    /**
     * Phone usage: re-reads events from the last open session's start (or the last read, at most 7 days back),
     * builds sessions up to now and stores changed ones as PENDING. A failed read advances nothing.
     */
    private suspend fun importUsage() {
        val source = usageSource ?: return
        val until = now()
        val last = store.setting(SettingKeys.USAGE_LAST_READ)?.toLongOrNull() ?: 0L
        val from = maxOf(last, until - USAGE_WINDOW_MS)
        val events = try {
            source.readSince(from)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("usage read failed: ${e::class.simpleName}")
            return
        }
        val built = buildPhoneSessions(events, until)
        val nextRead = built.openStartAt ?: until
        store.writeSignalIf {
            var wrote = false
            for (s in built.sessions) {
                val stored = db.usage_sessionQueries.selectById(s.id).executeAsOneOrNull()
                val same = stored != null && stored.deleted == 0L && stored.device == s.device && stored.app == s.app &&
                    stored.title == s.title && stored.startAt == s.startAt && stored.endAt == s.endAt &&
                    stored.localDate == s.localDate
                if (same) continue
                db.usage_sessionQueries.upsert(
                    Usage_session(
                        s.id, nextUpdatedAt(stored?.updatedAt), 0, stored?.serverSeq ?: 0, PENDING,
                        s.device, s.app, s.title, s.startAt, s.endAt, s.localDate,
                    ),
                )
                wrote = true
            }
            store.putSetting(SettingKeys.USAGE_LAST_READ, nextRead.toString())
            wrote
        }
    }

    private fun nextUpdatedAt(previous: Long?): Long = if (previous == null) now() else maxOf(now(), previous + 1)

    private suspend fun runSync(cfg: ServerConfig, readHealth: Boolean): SyncStatus {
        if (readHealth) importHealth()
        pushPending(cfg)?.let { return it }
        pullAll(cfg)?.let { return it }
        when (val health = client.health(cfg)) {
            is ApiResult.Ok -> {
                val okText = health.value.googleOk?.toString() ?: ""
                val fetchText = health.value.googleLastFetchAt?.toString() ?: ""
                val moodleOkText = health.value.moodleOk?.toString() ?: ""
                val moodleUrlText = health.value.moodleBaseUrl ?: ""
                val spotifyOkText = health.value.spotifyOk?.toString() ?: ""
                val calendarOkText = health.value.calendarOk?.toString() ?: ""
                // Signal (store.write) only when a value changed, so Today reloads the banner and "Updated".
                val changed = (store.setting(SettingKeys.GOOGLE_OK) ?: "") != okText ||
                    (store.setting(SettingKeys.GOOGLE_LAST_FETCH_AT) ?: "") != fetchText ||
                    (store.setting(SettingKeys.MOODLE_OK) ?: "") != moodleOkText ||
                    (store.setting(SettingKeys.MOODLE_BASE_URL) ?: "") != moodleUrlText ||
                    (store.setting(SettingKeys.SPOTIFY_OK) ?: "") != spotifyOkText ||
                    (store.setting(SettingKeys.CALENDAR_OK) ?: "") != calendarOkText
                if (changed) {
                    store.write {
                        store.putSetting(SettingKeys.GOOGLE_OK, okText)
                        store.putSetting(SettingKeys.GOOGLE_LAST_FETCH_AT, fetchText)
                        store.putSetting(SettingKeys.MOODLE_OK, moodleOkText)
                        store.putSetting(SettingKeys.MOODLE_BASE_URL, moodleUrlText)
                        store.putSetting(SettingKeys.SPOTIFY_OK, spotifyOkText)
                        store.putSetting(SettingKeys.CALENDAR_OK, calendarOkText)
                    }
                }
            }
            ApiResult.Unauthorized -> return SyncStatus.WrongToken
            is ApiResult.Failure -> return SyncStatus.Offline(pendingCount(store))
        }
        val at = now()
        store.putSetting(SettingKeys.LAST_SYNC_AT, at.toString())
        return SyncStatus.Synced(at)
    }

    /** Returns a terminal status when the push failed, null when everything went through. */
    private suspend fun pushPending(cfg: ServerConfig): SyncStatus? {
        val snapshot = store.writeQuiet { pendingRows() }
        for (batch in pushBatches(snapshot)) {
            val byId = batch.groupBy { it.id }
            when (val res = client.push(cfg, batch)) {
                is ApiResult.Ok -> store.write {
                    for (id in res.value.accepted) byId[id]?.forEach { markSynced(it) }
                    for (rej in res.value.rejected) byId[rej.id]?.forEach { markRejected(it) }
                }
                ApiResult.Unauthorized -> return SyncStatus.WrongToken
                is ApiResult.Failure -> return SyncStatus.Offline(pendingCount(store))
            }
        }
        return null
    }

    private suspend fun pullAll(cfg: ServerConfig): SyncStatus? {
        resetCursorAfterUpgrade()
        while (true) {
            val since = store.setting(SettingKeys.CURSOR)?.toLongOrNull() ?: 0L
            val page: PullResponse = when (val res = client.pull(cfg, since)) {
                is ApiResult.Ok -> res.value
                ApiResult.Unauthorized -> return SyncStatus.WrongToken
                is ApiResult.Failure -> return SyncStatus.Offline(pendingCount(store))
            }
            try {
                val applyPage = {
                    for (row in page.rows) {
                        if (!shouldApply(row)) continue
                        try {
                            upsertFromSyncRow(db, row, 0, SYNCED)
                        } catch (e: UnknownTableException) {
                            logWarning("skipped a pulled row for unknown table '${e.table}'")
                        }
                    }
                    store.putSetting(SettingKeys.CURSOR, page.cursor.toString())
                }
                // An empty page changes no visible row, so it must not make screens reload.
                if (page.rows.isEmpty()) store.writeQuiet(applyPage) else store.write(applyPage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return SyncStatus.Offline(pendingCount(store))
            }
            if (page.rows.size < PULL_PAGE || page.cursor <= since) return null
        }
    }

    /**
     * After an app upgrade the next pull must start at 0 so rows skipped earlier (tables the old build did
     * not know) arrive. Cursor and marker are written together, so an interrupted replay resumes from
     * the saved cursor instead of skipping rows.
     */
    private suspend fun resetCursorAfterUpgrade() {
        val version = AssistantDb.Schema.version.toString()
        if (store.setting(SettingKeys.CURSOR_SCHEMA_VERSION) == version) return
        store.writeQuiet {
            store.putSetting(SettingKeys.CURSOR, "0")
            store.putSetting(SettingKeys.CURSOR_SCHEMA_VERSION, version)
        }
    }

    // ---- row helpers (all called inside a store write / read) ---------------------------------

    private fun pendingRows(): List<SyncRow> =
        db.checkinQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.spendingQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.journalQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.taskQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.health_dayQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.workoutQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.mealQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.doc_folderQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.docQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.ai_jobQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.dreamQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.dream_patternQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.goalQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.cardQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.quizQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.examQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.usage_sessionQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.app_ruleQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.mail_draftQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.mail_actionQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.hw_checkQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.approvalQueries.selectPending().executeAsList().map { toSyncRow(it) } +
            db.researchQueries.selectPending().executeAsList().map { toSyncRow(it) }

    /** Splits [rows] into push batches of at most [PUSH_BATCH] rows and [PUSH_BATCH_BYTES] encoded bytes. */
    internal fun pushBatches(rows: List<SyncRow>): List<List<SyncRow>> {
        val out = ArrayList<List<SyncRow>>()
        var current = ArrayList<SyncRow>()
        var bytes = 0L
        for (row in rows) {
            val size = Json.encodeToString(SyncRow.serializer(), row).encodeToByteArray().size.toLong()
            if (current.isNotEmpty() && (current.size >= PUSH_BATCH || bytes + size > PUSH_BATCH_BYTES)) {
                out += current
                current = ArrayList()
                bytes = 0
            }
            current += row
            bytes += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }
    private fun markSynced(row: SyncRow) {
        when (row.table) {
            "checkin" -> db.checkinQueries.markSynced(row.id, row.updatedAt)
            "spending" -> db.spendingQueries.markSynced(row.id, row.updatedAt)
            "journal" -> db.journalQueries.markSynced(row.id, row.updatedAt)
            "task" -> db.taskQueries.markSynced(row.id, row.updatedAt)
            "health_day" -> db.health_dayQueries.markSynced(row.id, row.updatedAt)
            "workout" -> db.workoutQueries.markSynced(row.id, row.updatedAt)
            "meal" -> db.mealQueries.markSynced(row.id, row.updatedAt)
            "doc_folder" -> db.doc_folderQueries.markSynced(row.id, row.updatedAt)
            "doc" -> db.docQueries.markSynced(row.id, row.updatedAt)
            "ai_job" -> db.ai_jobQueries.markSynced(row.id, row.updatedAt)
            "dream" -> db.dreamQueries.markSynced(row.id, row.updatedAt)
            "dream_pattern" -> db.dream_patternQueries.markSynced(row.id, row.updatedAt)
            "goal" -> db.goalQueries.markSynced(row.id, row.updatedAt)
            "card" -> db.cardQueries.markSynced(row.id, row.updatedAt)
            "quiz" -> db.quizQueries.markSynced(row.id, row.updatedAt)
            "exam" -> db.examQueries.markSynced(row.id, row.updatedAt)
            "usage_session" -> db.usage_sessionQueries.markSynced(row.id, row.updatedAt)
            "app_rule" -> db.app_ruleQueries.markSynced(row.id, row.updatedAt)
            "mail_draft" -> db.mail_draftQueries.markSynced(row.id, row.updatedAt)
            "mail_action" -> db.mail_actionQueries.markSynced(row.id, row.updatedAt)
            "hw_check" -> db.hw_checkQueries.markSynced(row.id, row.updatedAt)
            "approval" -> db.approvalQueries.markSynced(row.id, row.updatedAt)
            "research" -> db.researchQueries.markSynced(row.id, row.updatedAt)
        }
    }

    private fun markRejected(row: SyncRow) {
        when (row.table) {
            "checkin" -> db.checkinQueries.markRejected(row.id, row.updatedAt)
            "spending" -> db.spendingQueries.markRejected(row.id, row.updatedAt)
            "journal" -> db.journalQueries.markRejected(row.id, row.updatedAt)
            "task" -> db.taskQueries.markRejected(row.id, row.updatedAt)
            "health_day" -> db.health_dayQueries.markRejected(row.id, row.updatedAt)
            "workout" -> db.workoutQueries.markRejected(row.id, row.updatedAt)
            "meal" -> db.mealQueries.markRejected(row.id, row.updatedAt)
            "doc_folder" -> db.doc_folderQueries.markRejected(row.id, row.updatedAt)
            "doc" -> db.docQueries.markRejected(row.id, row.updatedAt)
            "ai_job" -> db.ai_jobQueries.markRejected(row.id, row.updatedAt)
            "dream" -> db.dreamQueries.markRejected(row.id, row.updatedAt)
            "dream_pattern" -> db.dream_patternQueries.markRejected(row.id, row.updatedAt)
            "goal" -> db.goalQueries.markRejected(row.id, row.updatedAt)
            "card" -> db.cardQueries.markRejected(row.id, row.updatedAt)
            "quiz" -> db.quizQueries.markRejected(row.id, row.updatedAt)
            "exam" -> db.examQueries.markRejected(row.id, row.updatedAt)
            "usage_session" -> db.usage_sessionQueries.markRejected(row.id, row.updatedAt)
            "app_rule" -> db.app_ruleQueries.markRejected(row.id, row.updatedAt)
            "mail_draft" -> db.mail_draftQueries.markRejected(row.id, row.updatedAt)
            "mail_action" -> db.mail_actionQueries.markRejected(row.id, row.updatedAt)
            "hw_check" -> db.hw_checkQueries.markRejected(row.id, row.updatedAt)
            "approval" -> db.approvalQueries.markRejected(row.id, row.updatedAt)
            "research" -> db.researchQueries.markRejected(row.id, row.updatedAt)
        }
    }

    /** The PLAN predicate: no local row, or (not PENDING and incoming >= local), or (PENDING and incoming > local). */
    private fun shouldApply(row: SyncRow): Boolean {
        val local: Pair<Long, String>? = when (row.table) {
            "checkin" -> db.checkinQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "spending" -> db.spendingQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "journal" -> db.journalQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "task" -> db.taskQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "health_day" -> db.health_dayQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "workout" -> db.workoutQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "meal" -> db.mealQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "cal_event" -> db.cal_eventQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "mail_item" -> db.mail_itemQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "briefing" -> db.briefingQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "doc_folder" -> db.doc_folderQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "doc" -> db.docQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "ai_job" -> db.ai_jobQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "dream" -> db.dreamQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "dream_pattern" -> db.dream_patternQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "goal" -> db.goalQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "course" -> db.courseQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "study_file" -> db.study_fileQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "card" -> db.cardQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "quiz" -> db.quizQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "exam" -> db.examQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "usage_session" -> db.usage_sessionQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "app_rule" -> db.app_ruleQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "listen" -> db.listenQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "mail_draft" -> db.mail_draftQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "mail_action" -> db.mail_actionQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "hw_check" -> db.hw_checkQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "approval" -> db.approvalQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            "research" -> db.researchQueries.selectById(row.id).executeAsOneOrNull()?.let { it.updatedAt to it.syncState }
            else -> null
        }
        return when {
            local == null -> true
            local.second == PENDING -> row.updatedAt > local.first
            else -> row.updatedAt >= local.first
        }
    }
}
