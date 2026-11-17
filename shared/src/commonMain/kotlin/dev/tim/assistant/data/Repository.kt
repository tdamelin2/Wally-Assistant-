package dev.tim.assistant.data

import dev.tim.assistant.ai.jobs.JOB_MAX_ATTEMPTS
import dev.tim.assistant.ai.tools.ApprovalReadView
import dev.tim.assistant.ai.tools.approvalDecisionRow
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.upsertFromSyncRow
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Research
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Doc
import dev.tim.assistant.db.Doc_folder
import dev.tim.assistant.db.Dream
import dev.tim.assistant.db.Dream_pattern
import dev.tim.assistant.db.Goal
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Hw_check
import dev.tim.assistant.db.Listen
import dev.tim.assistant.db.Mail_action
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.db.Usage_session
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.time.todayLocal
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.MATCH_EXE
import dev.tim.assistant.usage.MATCH_PACKAGE
import dev.tim.assistant.usage.MATCH_TITLE
import dev.tim.assistant.usage.appMatchType
import dev.tim.assistant.usage.normalizePattern
import dev.tim.assistant.usage.ruleId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val PENDING = "PENDING"
const val SYNCED = "SYNCED"
const val REJECTED = "REJECTED"

val SPENDING_CATEGORIES = listOf("Food", "Transport", "Shopping", "Bills", "Fun", "Health", "Other")

private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000

/** All local reads and writes of the four entry types. Writes go through [LocalStore.write]. */
class Repository(
    private val store: LocalStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val today: () -> String = { todayLocal() },
) {
    private val db get() = store.db

    val changes get() = store.changes

    // ---- settings ---------------------------------------------------------------------------

    fun setting(key: String): String? = store.setting(key)

    fun putSetting(key: String, value: String) = store.putSetting(key, value)

    // ---- Google (read-only rows synced from the server) -------------------------------------------

    /** Every stored calendar event that is not deleted; the Today grouping applies the time window. */
    fun calendarEvents(): List<dev.tim.assistant.db.Cal_event> =
        db.cal_eventQueries.selectAll().executeAsList().filter { it.deleted == 0L }

    /** Every stored mail item that is not deleted. */
    fun unreadMail(): List<dev.tim.assistant.db.Mail_item> =
        db.mail_itemQueries.selectAll().executeAsList().filter { it.deleted == 0L }

    /** The briefing for [today] (YYYY-MM-DD, Jerusalem); null when missing or deleted. */
    fun briefingFor(today: String): dev.tim.assistant.db.Briefing? =
        db.briefingQueries.selectById("briefing:$today").executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** true / false from the last health check; null when unknown (empty or missing). */
    fun googleOk(): Boolean? = when (setting(SettingKeys.GOOGLE_OK)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun googleLastFetchAt(): Long? = setting(SettingKeys.GOOGLE_LAST_FETCH_AT)?.toLongOrNull()

    /** Last Moodle state reported by the server's health check: true / false / null (not set up or unknown). */
    fun moodleOk(): Boolean? = when (setting(SettingKeys.MOODLE_OK)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** Last Spotify state reported by the server's health check: true / false / null (not set up or unknown). */
    fun spotifyOk(): Boolean? = when (setting(SettingKeys.SPOTIFY_OK)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** Last Wally Calendar state reported by the server's health check: true / false / null (not set up or unknown). */
    fun calendarOk(): Boolean? = when (setting(SettingKeys.CALENDAR_OK)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** The configured public Moodle address from the server, or null when none is configured. */
    fun moodleBaseUrl(): String? = setting(SettingKeys.MOODLE_BASE_URL)?.takeIf { it.isNotBlank() }
    // ---- writes -----------------------------------------------------------------------------

    /** The updatedAt every write uses: beats the previous version even when another clock is ahead. */
    private fun nextUpdatedAt(previous: Long?): Long =
        if (previous == null) now() else maxOf(now(), previous + 1)

    /** Today's check-in if it exists and is not deleted (the form edits it), else null. */
    fun checkinForToday(): Checkin? =
        db.checkinQueries.selectById(checkinIdFor(today())).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** The check-in of [localDate] if it exists and is not deleted. */
    fun checkinForDate(localDate: String): Checkin? =
        db.checkinQueries.selectById(checkinIdFor(localDate)).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    private fun checkinIdFor(date: String) = "checkin:$date"

    suspend fun saveCheckin(mood: Int, energy: Int, sleepQuality: Int, stress: Int, note: String?): String =
        saveCheckin(today(), mood, energy, sleepQuality, stress, note)

    /** Saves the check-in of [localDate] (id `checkin:<localDate>`), so a past day never overwrites today's. */
    suspend fun saveCheckin(localDate: String, mood: Int, energy: Int, sleepQuality: Int, stress: Int, note: String?): String {
        val id = checkinIdFor(localDate)
        store.write {
            val prev = db.checkinQueries.selectById(id).executeAsOneOrNull()
            db.checkinQueries.upsert(
                Checkin(
                    id, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    localDate, mood.toLong(), energy.toLong(), sleepQuality.toLong(),
                    stress.toLong(), note?.takeIf { it.isNotBlank() },
                ),
            )
        }
        return id
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveSpending(id: String?, amountAgorot: Long, category: String, note: String?, localDate: String): String {
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.spendingQueries.selectById(rowId).executeAsOneOrNull()
            db.spendingQueries.upsert(
                Spending(
                    rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    amountAgorot, category, note?.takeIf { it.isNotBlank() }, localDate,
                ),
            )
        }
        return rowId
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveJournal(id: String?, text: String): String {
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.journalQueries.selectById(rowId).executeAsOneOrNull()
            db.journalQueries.upsert(
                Journal(
                    rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    text, prev?.createdAt ?: now(),
                ),
            )
        }
        return rowId
    }

    /** Saves a task and keeps the goal link stored on it (read inside the write). Today/Log toggles use this. */
    suspend fun saveTask(id: String?, title: String, dueDate: String?, done: Boolean): String =
        saveTaskRow(id, title, dueDate, done, keepGoal = true, goalId = null)

    /** Saves a task and sets ([goalId] non-blank) or clears (null/blank) its goal link. */
    suspend fun saveTask(id: String?, title: String, dueDate: String?, done: Boolean, goalId: String?): String =
        saveTaskRow(id, title, dueDate, done, keepGoal = false, goalId = goalId)

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun saveTaskRow(
        id: String?, title: String, dueDate: String?, done: Boolean, keepGoal: Boolean, goalId: String?,
    ): String {
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.taskQueries.selectById(rowId).executeAsOneOrNull()
            val wasDone = prev != null && prev.done != 0L && prev.deleted == 0L
            val prevDoneAt = prev?.doneAt
            val doneAt = when {
                !done -> null
                wasDone && prevDoneAt != null -> prevDoneAt
                else -> now()
            }
            db.taskQueries.upsert(
                Task(
                    rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    title, dueDate?.takeIf { it.isNotBlank() }, if (done) 1L else 0L, doneAt,
                    if (keepGoal) prev?.goalId else goalId?.takeIf { it.isNotBlank() },
                ),
            )
        }
        return rowId
    }

    /**
     * Sets only the done state of a task, re-reading the live row inside the write so a delayed tap can never
     * overwrite a newer edit or bring back a deleted task. Returns false (and writes nothing) when the row is
     * missing, deleted or already in that state.
     */
    suspend fun setTaskDone(id: String, done: Boolean): Boolean = store.write {
        val prev = db.taskQueries.selectById(id).executeAsOneOrNull()
        if (prev == null || prev.deleted != 0L || (prev.done != 0L) == done) {
            false
        } else {
            db.taskQueries.upsert(
                prev.copy(
                    updatedAt = nextUpdatedAt(prev.updatedAt),
                    syncState = PENDING,
                    done = if (done) 1L else 0L,
                    doneAt = if (done) now() else null,
                ),
            )
            true
        }
    }

    /**
     * Saves a meal or snack. The current row is read inside the write: changing the (trimmed) text clears
     * the five nutrition fields so the meal is estimated again; changing only type or time keeps the
     * stored (latest) nutrition, including a value pulled after the form was opened.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveMeal(id: String?, type: String, text: String, eatenAt: Long): String {
        val rowId = id ?: Uuid.random().toString()
        val newText = text.trim()
        store.write {
            val prev = db.mealQueries.selectById(rowId).executeAsOneOrNull()
            val keep = prev != null && prev.text.trim() == newText
            db.mealQueries.upsert(
                Meal(
                    rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    type, newText, eatenAt, localDateOf(Instant.fromEpochMilliseconds(eatenAt)),
                    if (keep) prev?.kcal else null, if (keep) prev?.proteinG else null,
                    if (keep) prev?.carbsG else null, if (keep) prev?.fatG else null,
                    if (keep) prev?.estimateModel else null,
                ),
            )
        }
        return rowId
    }

    suspend fun deleteMeal(id: String) = store.write {
        db.mealQueries.selectById(id).executeAsOneOrNull()?.let {
            db.mealQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    suspend fun deleteCheckin
(id: String) = store.write {
        db.checkinQueries.selectById(id).executeAsOneOrNull()?.let {
            db.checkinQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    suspend fun deleteSpending(id: String) = store.write {
        db.spendingQueries.selectById(id).executeAsOneOrNull()?.let {
            db.spendingQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    suspend fun deleteJournal(id: String) = store.write {
        db.journalQueries.selectById(id).executeAsOneOrNull()?.let {
            db.journalQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    suspend fun deleteTask(id: String) = store.write {
        db.taskQueries.selectById(id).executeAsOneOrNull()?.let {
            db.taskQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    // ---- reads (tombstones hidden) -----------------------------------------------------------

    fun checkins(): List<Checkin> =
        db.checkinQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareByDescending<Checkin> { it.localDate }.thenByDescending { it.updatedAt })

    fun spending(): List<Spending> =
        db.spendingQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareByDescending<Spending> { it.localDate }.thenByDescending { it.updatedAt })

    fun journal(): List<Journal> =
        db.journalQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedByDescending { it.createdAt }

    /** Meals and snacks, newest eatenAt first. */
    fun meals(): List<Meal> =
        db.mealQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareByDescending<Meal> { it.eatenAt }.thenByDescending { it.updatedAt })

    // History month reads: in-memory filters of the lists above, both ends inclusive (ISO dates compare as text).
    private fun inRange(day: String, from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): Boolean =
        day >= from.toString() && day <= to.toString()

    fun checkinsIn(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): List<Checkin> =
        checkins().filter { inRange(it.localDate, from, to) }

    fun spendingIn(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): List<Spending> =
        spending().filter { inRange(it.localDate, from, to) }

    /** Journal has no localDate: the Jerusalem date of createdAt decides. */
    fun journalIn(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): List<Journal> =
        journal().filter { inRange(localDateOf(Instant.fromEpochMilliseconds(it.createdAt)), from, to) }

    fun mealsIn(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): List<Meal> =
        meals().filter { inRange(it.localDate, from, to) }

    fun dreamsIn(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): List<Dream> =
        dreams().filter { inRange(it.localDate, from, to) }

    /** Done tasks finished in the range by the Jerusalem date of doneAt (updatedAt when doneAt is missing), newest first. */
    fun completedTasksIn(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): List<Task> =
        db.taskQueries.selectAll().executeAsList()
            .filter { it.deleted == 0L && it.done != 0L && inRange(localDateOf(Instant.fromEpochMilliseconds(it.doneAt ?: it.updatedAt)), from, to) }
            .sortedByDescending { it.doneAt ?: it.updatedAt }

    fun taskById(id: String): Task? = db.taskQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }
    fun mealById(id: String): Meal? = db.mealQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }
    fun spendingById(id: String): Spending? = db.spendingQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }
    fun checkinById(id: String): Checkin? = db.checkinQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }
    fun journalById(id: String): Journal? = db.journalQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** Health days (sleep, steps, resting HR) that are not deleted, newest date first. */
    fun healthDays(): List<dev.tim.assistant.db.Health_day> =
        db.health_dayQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedByDescending { it.localDate }

    /** Workouts that are not deleted, newest first. */
    fun workouts(): List<dev.tim.assistant.db.Workout> =
        db.workoutQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedByDescending { it.startAt }

    /** Every non-deleted task regardless of age (the charts count all finished tasks). */
    fun allTasksForCharts(): List<Task> =
        db.taskQueries.selectAll().executeAsList().filter { it.deleted == 0L }

    /** Open tasks: due date first (earliest), no due date last. */
    fun openTasks(): List<Task> =
        db.taskQueries.selectAll().executeAsList().filter { it.deleted == 0L && it.done == 0L }
            .sortedWith(compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate ?: "" }.thenByDescending { it.updatedAt })

    /** Log list: open tasks first, then done tasks until 7 days after doneAt. */
    fun tasksForLog(): List<Task> {
        val cutoff = now() - SEVEN_DAYS_MS
        val all = db.taskQueries.selectAll().executeAsList().filter { it.deleted == 0L }
        val open = all.filter { it.done == 0L }
            .sortedWith(compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate ?: "" }.thenByDescending { it.updatedAt })
        val done = all.filter { it.done != 0L && (it.doneAt ?: it.updatedAt) >= cutoff }
            .sortedByDescending { it.doneAt ?: it.updatedAt }
        return open + done
    }

    // ---- Files (folders and docs) --------------------------------------------------------------

    /** Folders that are not deleted, by name. */
    fun folders(): List<Doc_folder> =
        db.doc_folderQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareBy<Doc_folder> { it.name.lowercase() }.thenBy { it.id })

    /** Docs that are not deleted, newest edit first. */
    fun docs(): List<Doc> =
        db.docQueries.selectAll().executeAsList().filter { it.deleted == 0L }.sortedByDescending { it.updatedAt }

    /** Docs whose title or body contains [query] (case-insensitive for plain letters); blank query lists all. */
    fun searchDocs(query: String): List<Doc> {
        val q = query.trim()
        if (q.isEmpty()) return docs()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.docQueries.search(escaped).executeAsList()
    }

    fun docById(id: String): Doc? = db.docQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** Creates ([id] null) or renames a folder; the name is trimmed and must be 1..100 characters. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveFolder(id: String?, name: String): String {
        val clean = name.trim()
        require(clean.length in 1..100) { "folder name must be 1..100 characters" }
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.doc_folderQueries.selectById(rowId).executeAsOneOrNull()
            db.doc_folderQueries.upsert(
                Doc_folder(rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, clean),
            )
        }
        return rowId
    }

    /** Moves every doc of the folder to "No folder" and soft-deletes the folder, in one transaction. */
    suspend fun deleteFolder(id: String) = store.write {
        for (d in db.docQueries.selectAll().executeAsList()) {
            if (d.deleted == 0L && d.folderId == id) {
                db.docQueries.upsert(d.copy(folderId = null, updatedAt = nextUpdatedAt(d.updatedAt), syncState = PENDING))
            }
        }
        db.doc_folderQueries.selectById(id).executeAsOneOrNull()?.let {
            db.doc_folderQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /**
     * Creates ([id] null) or edits a doc. The title is trimmed and must be 1..200 characters; a body over
     * 200 000 characters is refused with IllegalArgumentException("Too long").
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveDoc(id: String?, folderId: String?, title: String, body: String, source: String = "USER"): String {
        val cleanTitle = title.trim()
        require(cleanTitle.length in 1..200) { "doc title must be 1..200 characters" }
        require(!docBodyTooLong(body)) { "Too long" }
        require(source == "USER" || source == "AI") { "bad source" }
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.docQueries.selectById(rowId).executeAsOneOrNull()
            db.docQueries.upsert(
                Doc(rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, folderId?.takeIf { it.isNotBlank() }, cleanTitle, body, source),
            )
        }
        return rowId
    }

    suspend fun renameDoc(id: String, title: String) {
        val cleanTitle = title.trim()
        require(cleanTitle.length in 1..200) { "doc title must be 1..200 characters" }
        store.write {
            db.docQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.let {
                db.docQueries.upsert(it.copy(title = cleanTitle, updatedAt = nextUpdatedAt(it.updatedAt), syncState = PENDING))
            }
            Unit
        }
    }

    suspend fun moveDoc(id: String, folderId: String?) = store.write {
        db.docQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.let {
            db.docQueries.upsert(it.copy(folderId = folderId?.takeIf { f -> f.isNotBlank() }, updatedAt = nextUpdatedAt(it.updatedAt), syncState = PENDING))
        }
        Unit
    }

    suspend fun deleteDoc(id: String) = store.write {
        db.docQueries.selectById(id).executeAsOneOrNull()?.let {
            db.docQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    // ---- Jobs ------------------------------------------------------------------------------------

    /** Jobs that are not deleted, newest first. */
    fun jobs(): List<Ai_job> =
        db.ai_jobQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareByDescending<Ai_job> { it.createdAt }.thenByDescending { it.id })

    /** The newest MEAL_LOOKUP job (by createdAt, then id) whose input names [mealId]; null when there is none. */
    fun latestMealLookup(mealId: String): Ai_job? = jobs().firstOrNull { job ->
        job.kind == "MEAL_LOOKUP" && try {
            (Json.parseToJsonElement(job.inputJson) as? JsonObject)?.get("mealId")?.jsonPrimitive?.content == mealId
        } catch (e: Exception) {
            false
        }
    }

    fun jobById(id: String): Ai_job? = db.ai_jobQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** When the newest FAILED job of one of [kinds] with exactly this [error] last changed (epoch ms), or null. */
    fun lastJobFailureAt(kinds: Collection<String>, error: String): Long? =
        db.ai_jobQueries.selectAll().executeAsList()
            .filter { it.deleted == 0L && it.status == "FAILED" && it.kind in kinds && it.error == error }
            .maxOfOrNull { it.updatedAt }

    /** Queues a job (QUEUED, attempts 0). The desktop runner picks it up. Returns the job id. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun queueJob(kind: String, inputJson: String, id: String? = null): String {
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.ai_jobQueries.selectById(rowId).executeAsOneOrNull()
            db.ai_jobQueries.upsert(
                Ai_job(rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, kind, inputJson, "QUEUED", null, null, 0, now(), null),
            )
        }
        return rowId
    }

    /** The oldest QUEUED job (createdAt, then id) whose kind is not in [exceptKinds] (jobs the server runs), or null. */
    fun oldestQueuedJob(exceptKinds: List<String> = emptyList()): Ai_job? =
        db.ai_jobQueries.selectOldestQueuedExcept(exceptKinds).executeAsOneOrNull()

    /**
     * RUNNING jobs that started before [cutoff] go back to QUEUED with attempts + 1 (FAILED at the third attempt).
     * Jobs of a kind in [exceptKinds] (owned by the server) are left alone. Returns how many were reset.
     */
    suspend fun resetStaleJobs(cutoff: Long, exceptKinds: List<String> = emptyList()): Int = store.write {
        val stale = db.ai_jobQueries.selectStaleRunningExcept(exceptKinds, cutoff).executeAsList()
        for (job in stale) {
            val attempts = job.attempts + 1
            val failed = attempts >= JOB_MAX_ATTEMPTS
            db.ai_jobQueries.upsert(
                job.copy(
                    updatedAt = nextUpdatedAt(job.updatedAt), syncState = PENDING, attempts = attempts,
                    status = if (failed) "FAILED" else "QUEUED", startedAt = null,
                    error = if (failed) "interrupted too many times" else "interrupted",
                ),
            )
        }
        stale.size
    }

    /** Claims a QUEUED job: RUNNING + startedAt + PENDING in one transaction. Null when it is no longer QUEUED. */
    suspend fun claimJob(id: String): Ai_job? = store.write {
        val job = db.ai_jobQueries.selectById(id).executeAsOneOrNull()
        if (job == null || job.deleted != 0L || job.status != "QUEUED") {
            null
        } else {
            val claimed = job.copy(updatedAt = nextUpdatedAt(job.updatedAt), syncState = PENDING, status = "RUNNING", startedAt = now())
            db.ai_jobQueries.upsert(claimed)
            claimed
        }
    }

    /** The stored job when it is still RUNNING under exactly this claim (same startedAt). Call inside a write. */
    private fun liveClaim(claim: Ai_job): Ai_job? =
        db.ai_jobQueries.selectById(claim.id).executeAsOneOrNull()
            ?.takeIf { it.deleted == 0L && it.status == "RUNNING" && it.startedAt == claim.startedAt }

    private fun writeJobDone(job: Ai_job, resultJson: String?) {
        db.ai_jobQueries.upsert(
            job.copy(updatedAt = nextUpdatedAt(job.updatedAt), syncState = PENDING, status = "DONE", resultJson = resultJson, error = null),
        )
    }

    /** DONE with [resultJson] when the claim is still live; a no-op (false) for any other state. */
    suspend fun completeJob(claim: Ai_job, resultJson: String?): Boolean = store.write {
        val live = liveClaim(claim)
        if (live != null) writeJobDone(live, resultJson)
        live != null
    }

    /** A failed attempt: attempts + 1, back to QUEUED (FAILED at the third, or at once when [failNow]). Guarded by the claim. */
    suspend fun failJob(claim: Ai_job, error: String, failNow: Boolean): Boolean = store.write {
        val live = liveClaim(claim)
        if (live != null) {
            val attempts = live.attempts + 1
            val failed = failNow || attempts >= JOB_MAX_ATTEMPTS
            db.ai_jobQueries.upsert(
                live.copy(
                    updatedAt = nextUpdatedAt(live.updatedAt), syncState = PENDING, attempts = attempts,
                    status = if (failed) "FAILED" else "QUEUED", error = error, startedAt = if (failed) live.startedAt else null,
                ),
            )
        }
        live != null
    }

    /** Back to QUEUED with attempts unchanged (the model was unreachable). Guarded by the claim. */
    suspend fun releaseJob(claim: Ai_job): Boolean = store.write {
        val live = liveClaim(claim)
        if (live != null) {
            db.ai_jobQueries.upsert(live.copy(updatedAt = nextUpdatedAt(live.updatedAt), syncState = PENDING, status = "QUEUED", startedAt = null))
        }
        live != null
    }

    // ---- Atomic job outputs (one write each: claim check, source recheck, outputs + DONE together) ----

    /** Writes the analysis onto the dream the job read at [expectedUpdatedAt]; never revives or overwrites an edited dream. */
    suspend fun completeDreamInterpret(claim: Ai_job, dreamId: String, expectedUpdatedAt: Long, analysis: String, model: String): OutputResult =
        store.write {
            val live = liveClaim(claim)
            val dream = db.dreamQueries.selectById(dreamId).executeAsOneOrNull()
            when {
                live == null -> OutputResult.STALE_CLAIM
                dream == null || dream.deleted != 0L -> OutputResult.SOURCE_GONE
                dream.updatedAt != expectedUpdatedAt -> OutputResult.SOURCE_CHANGED
                else -> {
                    db.dreamQueries.upsert(
                        dream.copy(updatedAt = nextUpdatedAt(dream.updatedAt), syncState = PENDING, analysis = analysis, analysisModel = model),
                    )
                    writeJobDone(live, null)
                    OutputResult.WRITTEN
                }
            }
        }

    /**
     * Writes the `dreampattern:<localDate>` row: inserted when absent, replaced while it is live, left alone
     * (job still DONE) when Tim deleted it.
     */
    suspend fun completeDreamPatterns(claim: Ai_job, localDate: String, text: String, model: String): OutputResult = store.write {
        val live = liveClaim(claim)
        if (live == null) {
            OutputResult.STALE_CLAIM
        } else {
            val id = "dreampattern:$localDate"
            val prev = db.dream_patternQueries.selectById(id).executeAsOneOrNull()
            // A deleted row stays deleted for a job queued before the deletion; a later job writes a fresh live row.
            if (prev == null || prev.deleted == 0L || claim.createdAt > prev.updatedAt) {
                db.dream_patternQueries.upsert(
                    Dream_pattern(id, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, localDate, text, model),
                )
            }
            writeJobDone(live, null)
            OutputResult.WRITTEN
        }
    }

    /** Stores a validated GOAL_STEPS result on the job when the goal still exists. */
    suspend fun completeGoalSteps(claim: Ai_job, goalId: String, resultJson: String): OutputResult = store.write {
        val live = liveClaim(claim)
        val goal = db.goalQueries.selectById(goalId).executeAsOneOrNull()
        when {
            live == null -> OutputResult.STALE_CLAIM
            goal == null || goal.deleted != 0L -> OutputResult.SOURCE_GONE
            else -> {
                writeJobDone(live, resultJson)
                OutputResult.WRITTEN
            }
        }
    }

    /**
     * Saves the ideal-week doc `doc:idealweek:<jobId>` (inserted only when absent) in the live folder named
     * "Plans" or `docfolder:plans`, then marks the job DONE with `{"docId":..}`.
     */
    suspend fun completeIdealWeek(claim: Ai_job, localDate: String, markdown: String): OutputResult = store.write {
        val live = liveClaim(claim)
        if (live == null) {
            OutputResult.STALE_CLAIM
        } else {
            val folders = db.doc_folderQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            val folderId = folders.filter { it.name == "Plans" }.minByOrNull { it.id }?.id ?: run {
                val stored = db.doc_folderQueries.selectById(PLANS_FOLDER_ID).executeAsOneOrNull()
                db.doc_folderQueries.upsert(
                    Doc_folder(PLANS_FOLDER_ID, nextUpdatedAt(stored?.updatedAt), 0, stored?.serverSeq ?: 0, PENDING, "Plans"),
                )
                PLANS_FOLDER_ID
            }
            val docId = "doc:idealweek:${claim.id}"
            if (db.docQueries.selectById(docId).executeAsOneOrNull() == null) {
                db.docQueries.upsert(Doc(docId, now(), 0, 0, PENDING, folderId, "Dream week coaching $localDate", markdown, "AI"))
            }
            writeJobDone(live, """{"docId":"$docId"}""")
            OutputResult.WRITTEN
        }
    }

    // ---- Dreams (only the dream allow-list may call these) ------------------------------------------

    /** Dreams that are not deleted, newest date first. */
    fun dreams(): List<Dream> =
        db.dreamQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareByDescending<Dream> { it.localDate }.thenByDescending { it.updatedAt })

    fun dreamById(id: String): Dream? = db.dreamQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** Creates ([id] null) or edits a dream; an edit keeps the stored analysis. Tags are normalized. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveDream(id: String?, localDate: String, text: String, mood: Int, lucid: Boolean, tags: String): String {
        val clean = text.trim()
        require(clean.length in 1..20_000) { "dream text must be 1..20000 characters" }
        require(mood in 1..5) { "mood must be 1..5" }
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.dreamQueries.selectById(rowId).executeAsOneOrNull()
            db.dreamQueries.upsert(
                Dream(
                    rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    localDate, clean, mood.toLong(), if (lucid) 1L else 0L, normalizeTags(tags),
                    prev?.analysis, prev?.analysisModel,
                ),
            )
        }
        return rowId
    }

    suspend fun deleteDream(id: String) = store.write {
        db.dreamQueries.selectById(id).executeAsOneOrNull()?.let {
            db.dreamQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /** Pattern texts that are not deleted, newest date first. */
    fun dreamPatterns(): List<Dream_pattern> =
        db.dream_patternQueries.selectAll().executeAsList().filter { it.deleted == 0L }.sortedByDescending { it.localDate }

    /** Tombstones a pattern summary like [deleteDream]. */
    suspend fun deleteDreamPattern(id: String) = store.write {
        db.dream_patternQueries.selectById(id).executeAsOneOrNull()?.let {
            db.dream_patternQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /** Writes (or replaces) the `dreampattern:<localDate>` row. */
    suspend fun saveDreamPattern(localDate: String, text: String, model: String): String {
        val id = "dreampattern:$localDate"
        store.write {
            val prev = db.dream_patternQueries.selectById(id).executeAsOneOrNull()
            db.dream_patternQueries.upsert(
                Dream_pattern(id, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, localDate, text, model),
            )
        }
        return id
    }

    // ---- Goals -----------------------------------------------------------------------------------

    /** Goals that are not deleted: ACTIVE first, then by newest edit. */
    fun goals(): List<Goal> =
        db.goalQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareBy<Goal> { it.status != "ACTIVE" }.thenByDescending { it.updatedAt })

    fun goalById(id: String): Goal? = db.goalQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveGoal(id: String?, title: String, why: String?, deadline: String?, status: String = "ACTIVE"): String {
        val cleanTitle = title.trim()
        require(cleanTitle.length in 1..200) { "goal title must be 1..200 characters" }
        require(status in listOf("ACTIVE", "DONE", "DROPPED")) { "bad status" }
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.goalQueries.selectById(rowId).executeAsOneOrNull()
            db.goalQueries.upsert(
                Goal(
                    rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    cleanTitle, why?.trim()?.takeIf { it.isNotEmpty() }, deadline?.takeIf { it.isNotBlank() }, status,
                ),
            )
        }
        return rowId
    }

    suspend fun deleteGoal(id: String) = store.write {
        db.goalQueries.selectById(id).executeAsOneOrNull()?.let {
            db.goalQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /** Non-deleted tasks linked to [goalId]. */
    fun tasksForGoal(goalId: String): List<Task> =
        db.taskQueries.selectByGoal(goalId).executeAsList().sortedWith(compareBy<Task> { it.done }.thenBy { it.dueDate ?: "9999" }.thenBy { it.id })

    // ---- Study ----------------------------------------------------------------------------------------

    fun courses(): List<dev.tim.assistant.db.Course> =
        db.courseQueries.selectAll().executeAsList().filter { it.deleted == 0L }.sortedBy { it.name.lowercase() }

    fun courseById(id: String): dev.tim.assistant.db.Course? =
        db.courseQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    fun studyFiles(courseId: String): List<dev.tim.assistant.db.Study_file> =
        db.study_fileQueries.selectByCourse(courseId).executeAsList().sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    fun studyFileById(id: String): dev.tim.assistant.db.Study_file? =
        db.study_fileQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    fun cardsForFile(fileId: String): List<dev.tim.assistant.db.Card> =
        db.cardQueries.selectByFile(fileId).executeAsList().sortedWith(compareBy({ cardIndex(it.id) }, { it.id }))

    private fun cardIndex(id: String): Int = id.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE

    fun cardCount(fileId: String): Int = db.cardQueries.selectByFile(fileId).executeAsList().size

    fun quizzesForCourse(courseId: String): List<dev.tim.assistant.db.Quiz> =
        db.quizQueries.selectByCourse(courseId).executeAsList().sortedByDescending { it.updatedAt }

    fun quizById(id: String): dev.tim.assistant.db.Quiz? =
        db.quizQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    fun exams(): List<dev.tim.assistant.db.Exam> = db.examQueries.selectAllActive().executeAsList()

    fun examById(id: String): dev.tim.assistant.db.Exam? =
        db.examQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** The study-set docs of the course's files (`doc:studyset:<fileId>`), newest edit first; deleted docs are skipped. */
    fun studySetDocs(courseId: String): List<Doc> =
        studyFiles(courseId).mapNotNull { db.docQueries.selectById(studySetDocId(it.id)).executeAsOneOrNull()?.takeIf { d -> d.deleted == 0L } }
            .sortedWith(compareByDescending<Doc> { it.updatedAt }.thenBy { it.id })

    /** Marks a card Knew it / Didn't (`KNEW` or `DIDNT`) and stamps the review time. */
    suspend fun markCard(id: String, result: String) {
        require(result == "KNEW" || result == "DIDNT") { "bad result" }
        store.write {
            db.cardQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.let {
                db.cardQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), syncState = PENDING, lastResult = result, lastReviewedAt = now()))
            }
            Unit
        }
    }

    /** Saves the score (0..10) and the time together on one quiz. */
    suspend fun saveQuizResult(id: String, score: Int) {
        require(score in 0..10) { "score must be 0..10" }
        store.write {
            db.quizQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.let {
                db.quizQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), syncState = PENDING, score = score.toLong(), takenAt = now()))
            }
            Unit
        }
    }

    suspend fun deleteQuiz(id: String) = store.write {
        db.quizQueries.selectById(id).executeAsOneOrNull()?.let {
            db.quizQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /** Creates ([id] null) or edits an exam. Title 1..200, date YYYY-MM-DD, time null or HH:mm, course null or an id. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveExam(id: String?, courseId: String?, title: String, localDate: String, time: String?): String {
        val cleanTitle = title.trim()
        require(cleanTitle.length in 1..200) { "exam title must be 1..200 characters" }
        require(dev.tim.assistant.study.isValidDate(localDate)) { "bad date" }
        val cleanTime = time?.trim()?.takeIf { it.isNotEmpty() }
        require(cleanTime == null || dev.tim.assistant.study.isValidTime(cleanTime)) { "bad time" }
        val cleanCourse = courseId?.takeIf { it.isNotBlank() }
        require(cleanCourse == null || cleanCourse.length in 1..200) { "bad course" }
        val rowId = id ?: Uuid.random().toString()
        store.write {
            val prev = db.examQueries.selectById(rowId).executeAsOneOrNull()
            db.examQueries.upsert(
                dev.tim.assistant.db.Exam(
                    rowId, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING,
                    cleanCourse, cleanTitle, localDate, cleanTime,
                ),
            )
        }
        return rowId
    }

    suspend fun deleteExam(id: String) = store.write {
        db.examQueries.selectById(id).executeAsOneOrNull()?.let {
            db.examQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /**
     * Publishes a finished STUDY_SET in one transaction: the summary doc `doc:studyset:<fileId>` in the course's
     * "Study - <course>" folder, cards `card:<fileId>:<n>` (marks reset), extra old cards soft-deleted, job DONE.
     * Nothing is written when the claim is stale (STALE_CLAIM) or the file is gone (SOURCE_GONE).
     */
    suspend fun completeStudySet(claim: Ai_job, fileId: String, summary: String, cards: List<dev.tim.assistant.study.StudyCard>): OutputResult =
        store.write {
            val live = liveClaim(claim)
            val file = db.study_fileQueries.selectById(fileId).executeAsOneOrNull()
            if (live == null) return@write OutputResult.STALE_CLAIM
            if (file == null || file.deleted != 0L) return@write OutputResult.SOURCE_GONE
            val course = db.courseQueries.selectById(file.courseId).executeAsOneOrNull()?.takeIf { it.deleted == 0L }
            val folderName = "Study \u2013 " + (course?.name ?: "Course").trim().take(100)
            val folders = db.doc_folderQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            val folderId = folders.filter { it.name == folderName }.minByOrNull { it.id }?.id ?: run {
                val id = "docfolder:study:" + dev.tim.assistant.study.sha256Hex(file.courseId).take(16)
                val stored = db.doc_folderQueries.selectById(id).executeAsOneOrNull()
                db.doc_folderQueries.upsert(Doc_folder(id, nextUpdatedAt(stored?.updatedAt), 0, stored?.serverSeq ?: 0, PENDING, folderName))
                id
            }
            val docId = studySetDocId(fileId)
            val prevDoc = db.docQueries.selectById(docId).executeAsOneOrNull()
            db.docQueries.upsert(
                Doc(docId, nextUpdatedAt(prevDoc?.updatedAt), 0, prevDoc?.serverSeq ?: 0, PENDING, folderId, file.name.trim().take(200).ifEmpty { "Study set" }, summary, "AI"),
            )
            cards.forEachIndexed { n, card ->
                val id = "card:$fileId:$n"
                val prev = db.cardQueries.selectById(id).executeAsOneOrNull()
                db.cardQueries.upsert(
                    dev.tim.assistant.db.Card(id, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, fileId, card.front, card.back, null, null),
                )
            }
            for (old in db.cardQueries.selectByFile(fileId).executeAsList()) {
                if (cardIndex(old.id) >= cards.size) {
                    db.cardQueries.upsert(old.copy(updatedAt = nextUpdatedAt(old.updatedAt), deleted = 1, syncState = PENDING))
                }
            }
            writeJobDone(live, """{"docId":"$docId","cards":${cards.size}}""")
            OutputResult.WRITTEN
        }

    /** Writes the quiz `quiz:<jobId>` (only when absent) and marks the job DONE with `{"quizId"}`, in one transaction. */
    suspend fun completeQuiz(claim: Ai_job, courseId: String, questionsJson: String): OutputResult = store.write {
        val live = liveClaim(claim)
        if (live == null) return@write OutputResult.STALE_CLAIM
        val course = db.courseQueries.selectById(courseId).executeAsOneOrNull()
        if (course == null || course.deleted != 0L) return@write OutputResult.SOURCE_GONE
        val id = "quiz:${claim.id}"
        if (db.quizQueries.selectById(id).executeAsOneOrNull() == null) {
            db.quizQueries.upsert(dev.tim.assistant.db.Quiz(id, now(), 0, 0, PENDING, courseId, questionsJson, null, null))
        }
        writeJobDone(live, """{"quizId":"$id"}""")
        OutputResult.WRITTEN
    }
    /** done / total over every non-deleted task linked to the goal (old finished tasks included). */
    fun goalProgress(goalId: String): GoalProgress {
        val linked = db.taskQueries.selectByGoal(goalId).executeAsList()
        return GoalProgress(linked.count { it.done != 0L }, linked.size)
    }

    /**
     * Creates the tasks `task:goalstep:<jobId>:<index>` for the [selected] step indexes, linked to [goalId].
     * A task that already exists (even deleted, edited or done) is never touched. Returns how many were created.
     */
    suspend fun addGoalStepTasks(jobId: String, goalId: String, steps: List<GoalStep>, selected: Set<Int>): Int = store.write {
        val goal = db.goalQueries.selectById(goalId).executeAsOneOrNull()
        var created = 0
        if (goal != null && goal.deleted == 0L) {
            for (index in selected.sorted()) {
                val step = steps.getOrNull(index) ?: continue
                val id = "task:goalstep:$jobId:$index"
                if (db.taskQueries.selectById(id).executeAsOneOrNull() != null) continue
                db.taskQueries.upsert(Task(id, now(), 0, 0, PENDING, step.title, step.dueDate, 0, null, goalId))
                created++
            }
        }
        created
    }

    // ---- Screentime ---------------------------------------------------------------------------------

    /** Non-deleted usage sessions whose start date is in [fromDate]..[toDate] (YYYY-MM-DD), by start time. */
    fun usageSessions(fromDate: String, toDate: String): List<Usage_session> =
        db.usage_sessionQueries.selectByDateRange(fromDate, toDate).executeAsList()

    /**
     * Upserts usage sessions as PENDING (laptop recorder). Unchanged rows are skipped. Does not signal list
     * reloads: the recorder writes every few seconds and nothing on screen needs to follow that.
     */
    suspend fun saveUsageRows(rows: List<dev.tim.assistant.usage.UsageRow>) {
        if (rows.isEmpty()) return
        store.writeQuiet {
            for (s in rows) {
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
            }
        }
    }

    /** Non-deleted labelling rules, by id. */
    fun appRules(): List<App_rule> = db.app_ruleQueries.selectAllActive().executeAsList()

    /** The latest [limit] plays (written by the server), newest first. */
    fun listens(limit: Int = 50): List<Listen> = db.listenQueries.selectRecent(limit.toLong()).executeAsList()

    /**
     * Creates or changes the rule for [matchType] + [pattern] (trimmed, lowercased) and returns its id,
     * or null when the pattern is empty, longer than 200 characters or the type or label is unknown.
     * Rules never rewrite sessions: labels are worked out when they are shown.
     */
    suspend fun saveRule(matchType: String, pattern: String, label: Label): String? {
        val clean = normalizePattern(pattern)
        if (clean.isEmpty() || clean.length > 200 || label == Label.UNSORTED) return null
        if (matchType != MATCH_PACKAGE && matchType != MATCH_EXE && matchType != MATCH_TITLE) return null
        val id = ruleId(matchType, clean)
        store.write {
            val prev = db.app_ruleQueries.selectById(id).executeAsOneOrNull()
            if (prev == null || prev.deleted != 0L || prev.label != label.name) {
                db.app_ruleQueries.upsert(
                    App_rule(id, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, matchType, clean, label.name),
                )
            }
        }
        return id
    }

    suspend fun deleteRule(id: String) = store.write {
        db.app_ruleQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.let {
            db.app_ruleQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /**
     * Accepts a suggested label for an app: an EXE rule (laptop) or PACKAGE rule (phone). Does nothing when a
     * live rule for that app already exists. Returns true when a rule was created.
     */
    suspend fun acceptSuggestion(device: String, app: String, label: Label): Boolean {
        val type = appMatchType(device)
        val clean = normalizePattern(app)
        if (clean.isEmpty() || clean.length > 200 || label == Label.UNSORTED) return false
        val id = ruleId(type, clean)
        return store.writeSignalIf {
            val prev = db.app_ruleQueries.selectById(id).executeAsOneOrNull()
            if (prev != null && prev.deleted == 0L) {
                false
            } else {
                db.app_ruleQueries.upsert(
                    App_rule(id, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, type, clean, label.name),
                )
                true
            }
        }
    }

    fun pendingCount(): Int = pendingCount(store)

    // ---- Mail drafts, mail actions, hardware checks (Phase 12) -------------------------------------

    /** Live drafts, newest first. */
    fun mailDrafts(): List<Mail_draft> =
        db.mail_draftQueries.selectAll().executeAsList().filter { it.deleted == 0L }.sortedByDescending { it.updatedAt }

    fun mailDraftById(id: String): Mail_draft? =
        db.mail_draftQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /**
     * Saves the editor fields of an existing draft. [send] false keeps/sets DRAFT, true sets APPROVED. Only a
     * DRAFT, APPROVED or FAILED draft can change; SENDING, SENT and UNKNOWN are final on this side. Returns false when refused.
     */
    suspend fun saveMailDraft(id: String, toAddr: String, subject: String, body: String, send: Boolean): Boolean = store.write {
        val prev = db.mail_draftQueries.selectById(id).executeAsOneOrNull()
        if (prev == null || prev.deleted != 0L || prev.status !in EDITABLE_DRAFT_STATUSES) {
            false
        } else {
            db.mail_draftQueries.upsert(
                prev.copy(
                    updatedAt = nextUpdatedAt(prev.updatedAt), syncState = PENDING, toAddr = toAddr.trim(), subject = subject,
                    body = body, status = if (send) DRAFT_APPROVED else DRAFT_DRAFT, sentAt = null, error = null,
                ),
            )
            true
        }
    }

    /** Tombstones a draft (not while SENDING). Returns false when refused. */
    suspend fun deleteMailDraft(id: String): Boolean = store.write {
        val prev = db.mail_draftQueries.selectById(id).executeAsOneOrNull()
        if (prev == null || prev.deleted != 0L || prev.status == "SENDING") {
            false
        } else {
            db.mail_draftQueries.upsert(prev.copy(updatedAt = nextUpdatedAt(prev.updatedAt), syncState = PENDING, deleted = 1))
            true
        }
    }

    /** Live suggestions, ordered by id. */
    fun mailActions(): List<Mail_action> =
        db.mail_actionQueries.selectAll().executeAsList().filter { it.deleted == 0L }.sortedBy { it.id }

    fun mailActionsByStatus(status: String): List<Mail_action> =
        db.mail_actionQueries.selectByStatus(status).executeAsList().sortedBy { it.id }

    /** SUGGESTED -> APPROVED or REJECTED. Returns false for any other state. */
    suspend fun decideMailAction(id: String, approve: Boolean): Boolean = store.write {
        val prev = db.mail_actionQueries.selectById(id).executeAsOneOrNull()
        if (prev == null || prev.deleted != 0L || prev.status != "SUGGESTED") {
            false
        } else {
            db.mail_actionQueries.upsert(
                prev.copy(updatedAt = nextUpdatedAt(prev.updatedAt), syncState = PENDING, status = if (approve) "APPROVED" else "REJECTED"),
            )
            true
        }
    }

    /** Tombstones a handled suggestion (anything but APPROVED, which the server may still be running). */
    suspend fun deleteMailAction(id: String): Boolean = store.write {
        val prev = db.mail_actionQueries.selectById(id).executeAsOneOrNull()
        if (prev == null || prev.deleted != 0L || prev.status == "APPROVED") {
            false
        } else {
            db.mail_actionQueries.upsert(prev.copy(updatedAt = nextUpdatedAt(prev.updatedAt), syncState = PENDING, deleted = 1))
            true
        }
    }

    /** Live hardware checks, newest first. */
    fun hwChecks(): List<Hw_check> =
        db.hw_checkQueries.selectAll().executeAsList().filter { it.deleted == 0L }.sortedByDescending { it.takenAt }

    fun hwCheckById(id: String): Hw_check? =
        db.hw_checkQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** Stores a finished check as `hw:<takenAt>`. */
    suspend fun saveHwCheck(takenAt: Long, resultJson: String, summary: String): String {
        val id = "hw:$takenAt"
        store.write {
            val prev = db.hw_checkQueries.selectById(id).executeAsOneOrNull()
            db.hw_checkQueries.upsert(
                Hw_check(id, nextUpdatedAt(prev?.updatedAt), 0, prev?.serverSeq ?: 0, PENDING, takenAt, resultJson, summary),
            )
        }
        return id
    }

    suspend fun deleteHwCheck(id: String) = store.write {
        db.hw_checkQueries.selectById(id).executeAsOneOrNull()?.let {
            db.hw_checkQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    /**
     * Creates the draft `maildraft:<jobId>` (inserted only when absent: a retried job never makes a second one and an
     * edited or approved draft is never overwritten) and marks the job DONE with `{"draftId":..}`, in one write.
     */
    suspend fun completeMailDraft(
        claim: Ai_job, toAddr: String, subject: String, body: String, replyToGmailId: String?,
    ): OutputResult = store.write {
        val live = liveClaim(claim)
        if (live == null) {
            OutputResult.STALE_CLAIM
        } else {
            val id = mailDraftId(claim.id)
            if (db.mail_draftQueries.selectById(id).executeAsOneOrNull() == null) {
                db.mail_draftQueries.upsert(
                    Mail_draft(id, now(), 0, 0, PENDING, toAddr, subject, body, replyToGmailId, null, DRAFT_DRAFT, null, null),
                )
            }
            writeJobDone(live, buildJsonObject { put("draftId", id) }.toString())
            OutputResult.WRITTEN
        }
    }

    /**
     * Creates the suggestions `mailaction:<jobId>:<n>` (each inserted only when absent) as SUGGESTED and marks the job
     * DONE with `{"count":n}`, in one write.
     */
    suspend fun completeMailTriage(claim: Ai_job, actions: List<MailActionDraft>): OutputResult = store.write {
        val live = liveClaim(claim)
        if (live == null) {
            OutputResult.STALE_CLAIM
        } else {
            actions.forEachIndexed { index, a ->
                val id = mailActionId(claim.id, index)
                if (db.mail_actionQueries.selectById(id).executeAsOneOrNull() == null) {
                    db.mail_actionQueries.upsert(
                        Mail_action(id, now(), 0, 0, PENDING, a.gmailId, a.action, a.labelId, a.labelName, a.reason, "SUGGESTED"),
                    )
                }
            }
            writeJobDone(live, buildJsonObject { put("count", actions.size) }.toString())
            OutputResult.WRITTEN
        }
    }

    /** Stores the HW_EXPLAIN answer `{"text":..}` on the job when the check it was asked about still exists. */
    suspend fun completeHwExplain(claim: Ai_job, checkId: String, text: String): OutputResult = store.write {
        val live = liveClaim(claim)
        val check = db.hw_checkQueries.selectById(checkId).executeAsOneOrNull()
        when {
            live == null -> OutputResult.STALE_CLAIM
            check == null || check.deleted != 0L -> OutputResult.SOURCE_GONE
            else -> {
                writeJobDone(live, buildJsonObject { put("text", text) }.toString())
                OutputResult.WRITTEN
            }
        }
    }

    // ---- Approvals (Phase 13) ---------------------------------------------------------------------

    /** Live approvals, newest first. */
    fun approvals(): List<Approval> =
        db.approvalQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareByDescending<Approval> { it.createdAt }.thenByDescending { it.id })

    /**
     * Decides an approval in ONE write transaction (the local [dev.tim.assistant.ai.tools.ApprovalSink.decide]): re-reads the
     * row, requires PENDING and not expired, checks physically whether the output already exists (tombstones count; then it
     * is marked DONE without writing), otherwise runs [effect], which returns every row to write (the target rows and the
     * approval's own DONE/FAILED row), and applies them as PENDING rows. An [ApprovalEffectFailed] from the effect (it throws
     * before writing anything) ends the approval FAILED. No network, no suspension and no nested store helpers inside [effect].
     */
    suspend fun decideApproval(id: String, now: Long, effect: (ApprovalReadView) -> List<SyncRow>): ApproveOutcome = store.write {
        val row = db.approvalQueries.selectById(id).executeAsOneOrNull()
        when {
            row == null || row.deleted != 0L -> ApproveOutcome.NOT_FOUND
            row.status != APPROVAL_PENDING -> ApproveOutcome.NOT_PENDING
            isApprovalExpired(row.createdAt, now) -> ApproveOutcome.EXPIRED
            else -> {
                val receiptKey = if (approvalUsesReceipt(row.tool)) approvalReceiptKey(id) else null
                val exists = if (receiptKey != null) {
                    db.kvQueries.get(receiptKey).executeAsOneOrNull() != null
                } else {
                    approvalOutputExists(db, row.tool, id)
                }
                if (exists) {
                    applyDecisionRows(listOf(approvalDecisionRow(row, APPROVAL_DONE, row.resultText ?: "Already done", now)))
                    ApproveOutcome.DONE_EXISTING
                } else {
                    val rows = try {
                        effect(ApprovalReadView(row, db))
                    } catch (e: ApprovalEffectFailed) {
                        applyDecisionRows(listOf(approvalDecisionRow(row, APPROVAL_FAILED, e.message, now)))
                        return@write ApproveOutcome.FAILED
                    }
                    applyDecisionRows(rows)
                    val decided = rows.firstOrNull { it.table == "approval" && it.id == id }?.data?.get("status")?.jsonPrimitive?.content
                    if (decided == APPROVAL_FAILED) {
                        ApproveOutcome.FAILED
                    } else {
                        if (receiptKey != null) db.kvQueries.put(receiptKey, "1")
                        ApproveOutcome.DONE
                    }
                }
            }
        }
    }

    private fun applyDecisionRows(rows: List<SyncRow>) {
        for (r in rows) upsertFromSyncRow(db, r, serverSeqOf(r.table, r.id), PENDING)
    }

    /** The stored serverSeq of a row an approval overwrites (0 for a new row), so a local update keeps its place in the sync order. */
    private fun serverSeqOf(table: String, id: String): Long = when (table) {
        "task" -> db.taskQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "doc" -> db.docQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "goal" -> db.goalQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "exam" -> db.examQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "ai_job" -> db.ai_jobQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "mail_draft" -> db.mail_draftQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "mail_action" -> db.mail_actionQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "app_rule" -> db.app_ruleQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "meal" -> db.mealQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "spending" -> db.spendingQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "checkin" -> db.checkinQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "journal" -> db.journalQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "dream" -> db.dreamQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        "approval" -> db.approvalQueries.selectById(id).executeAsOneOrNull()?.serverSeq
        else -> null
    } ?: 0L
    // ---- Research (Phase 13) ----------------------------------------------------------------------

    /** The research row [id] when it is live and not expired at [now] (expiresAt == now is a miss). */
    fun researchValid(id: String, now: Long): Research? =
        db.researchQueries.selectValid(id, now).executeAsOneOrNull()

    fun researchById(id: String): Research? =
        db.researchQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    /** Live research rows, newest first. */
    fun researchList(): List<Research> =
        db.researchQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            .sortedWith(compareByDescending<Research> { it.askedAt }.thenByDescending { it.id })

    /** Live research whose question or answer contains [q] (case-insensitive), newest first, at most [limit]. */
    fun searchResearch(q: String, limit: Int): List<Research> =
        db.researchQueries.search(q.trim()).executeAsList().take(limit)

    suspend fun deleteResearch(id: String) = store.write {
        db.researchQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.let {
            db.researchQueries.upsert(it.copy(updatedAt = nextUpdatedAt(it.updatedAt), deleted = 1, syncState = PENDING))
        }
        Unit
    }

    // ---- Job helpers (Phase 13) -------------------------------------------------------------------

    /** Queues a job under a fixed [id]; inserted only when no row (even a deleted one) has that id. Returns [id]. */
    suspend fun queueJobWithId(id: String, kind: String, inputJson: String): String {
        store.write {
            if (db.ai_jobQueries.selectById(id).executeAsOneOrNull() == null) {
                db.ai_jobQueries.upsert(Ai_job(id, now(), 0, 0, PENDING, kind, inputJson, "QUEUED", null, null, 0, now(), null))
            }
        }
        return id
    }

    /** A QUEUED or RUNNING job of [kind] whose input holds `researchId` or `mealId` equal to [key], or null. */
    fun activeJobFor(kind: String, key: String): Ai_job? =
        db.ai_jobQueries.selectAll().executeAsList().firstOrNull { job ->
            job.deleted == 0L && job.kind == kind && (job.status == "QUEUED" || job.status == "RUNNING") &&
                runCatching {
                    val o = Json.parseToJsonElement(job.inputJson) as? JsonObject
                    o != null && (o["researchId"]?.jsonPrimitive?.content == key || o["mealId"]?.jsonPrimitive?.content == key)
                }.getOrDefault(false)
        }
}

const val APPROVAL_PENDING = "PENDING"
const val APPROVAL_APPROVED = "APPROVED"
const val APPROVAL_REJECTED = "REJECTED"
const val APPROVAL_EXPIRED = "EXPIRED"
const val APPROVAL_DONE = "DONE"
const val APPROVAL_FAILED = "FAILED"

/** An approval is valid for exactly 7 days; it is expired only after that. */
const val APPROVAL_VALID_MS = 7L * 24 * 60 * 60 * 1000

fun isApprovalExpired(createdAt: Long, now: Long): Boolean = now - createdAt > APPROVAL_VALID_MS

/** What the screens show: a PENDING approval past its 7 days displays as EXPIRED (never written to the database). */
fun approvalDisplayStatus(row: Approval, now: Long): String =
    if (row.status == APPROVAL_PENDING && isApprovalExpired(row.createdAt, now)) APPROVAL_EXPIRED else row.status

enum class ApproveOutcome { DONE, DONE_EXISTING, NOT_PENDING, EXPIRED, NOT_FOUND, FAILED }

/** Thrown by a local approval effect (before it writes anything) when its target no longer exists; the approval ends FAILED with this message. */
class ApprovalEffectFailed(message: String) : Exception(message)

fun approvalReceiptKey(approvalId: String): String = "approval-applied:$approvalId"

/** Tools that change an existing record (and set_app_rule) are made exactly-once by a local receipt, not by an output row. */
fun approvalUsesReceipt(tool: String): Boolean = tool == "set_app_rule" || tool.startsWith("update_")

/** The output row kind an approval tool writes (`<kind>:<approvalId>`), or null when it has no `<kind>:<id>` output. */
fun approvalOutputKind(tool: String): String? = when (tool) {
    "create_task" -> "task"
    "create_doc" -> "doc"
    "create_goal" -> "goal"
    "add_exam" -> "exam"
    "queue_job" -> "ai_job"
    "draft_email" -> "mail_draft"
    "suggest_mail_action" -> "mail_action"
    "add_meal" -> "meal"
    "add_spending" -> "spending"
    "add_checkin" -> "checkin"
    "add_journal" -> "journal"
    else -> null
}

/** `<kind>:<approvalId>` for the tool's output, or null (set_app_rule, create_event, research tools). */
fun approvalOutputId(tool: String, approvalId: String): String? = approvalOutputKind(tool)?.let { "$it:$approvalId" }

/** Physical check including tombstones: does the row this approval would write already exist? */
private fun approvalOutputExists(db: AssistantDb, tool: String, approvalId: String): Boolean {
    val id = approvalOutputId(tool, approvalId) ?: return false
    return when (approvalOutputKind(tool)) {
        "task" -> db.taskQueries.selectById(id).executeAsOneOrNull() != null
        "doc" -> db.docQueries.selectById(id).executeAsOneOrNull() != null
        "goal" -> db.goalQueries.selectById(id).executeAsOneOrNull() != null
        "exam" -> db.examQueries.selectById(id).executeAsOneOrNull() != null
        "ai_job" -> db.ai_jobQueries.selectById(id).executeAsOneOrNull() != null
        "mail_draft" -> db.mail_draftQueries.selectById(id).executeAsOneOrNull() != null
        "mail_action" -> db.mail_actionQueries.selectById(id).executeAsOneOrNull() != null
        "meal" -> db.mealQueries.selectById(id).executeAsOneOrNull() != null
        "spending" -> db.spendingQueries.selectById(id).executeAsOneOrNull() != null
        "checkin" -> db.checkinQueries.selectById(id).executeAsOneOrNull() != null
        "journal" -> db.journalQueries.selectById(id).executeAsOneOrNull() != null
        else -> false
    }
}

/** Lowercase, trim, collapse runs of whitespace: the same rule as the research package's questionKey. */
internal fun researchQuestionKey(text: String): String =
    text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

/** One validated triage suggestion handed to [Repository.completeMailTriage]. */
data class MailActionDraft(val gmailId: String, val action: String, val labelId: String?, val labelName: String?, val reason: String)

fun mailDraftId(jobId: String): String = "maildraft:$jobId"
fun mailActionId(jobId: String, n: Int): String = "mailaction:$jobId:$n"

private const val DRAFT_DRAFT = "DRAFT"
private const val DRAFT_APPROVED = "APPROVED"
private val EDITABLE_DRAFT_STATUSES = setOf("DRAFT", "APPROVED", "FAILED")

/** done / total for a goal. */
data class GoalProgress(val done: Int, val total: Int)

/** What an atomic job-output write did. */
enum class OutputResult { WRITTEN, STALE_CLAIM, SOURCE_GONE, SOURCE_CHANGED }

const val PLANS_FOLDER_ID = "docfolder:plans"

/** The id of the summary doc of a study file's study set. */
fun studySetDocId(fileId: String): String = "doc:studyset:$fileId"
internal fun pendingCount(store: LocalStore): Int {
    val db = store.db
    return db.checkinQueries.selectPending().executeAsList().size +
        db.spendingQueries.selectPending().executeAsList().size +
        db.journalQueries.selectPending().executeAsList().size +
        db.taskQueries.selectPending().executeAsList().size +
        db.health_dayQueries.selectPending().executeAsList().size +
        db.workoutQueries.selectPending().executeAsList().size +
        db.mealQueries.selectPending().executeAsList().size +
        db.doc_folderQueries.selectPending().executeAsList().size +
        db.docQueries.selectPending().executeAsList().size +
        db.ai_jobQueries.selectPending().executeAsList().size +
        db.dreamQueries.selectPending().executeAsList().size +
        db.dream_patternQueries.selectPending().executeAsList().size +
        db.goalQueries.selectPending().executeAsList().size +
        db.cardQueries.selectPending().executeAsList().size +
        db.quizQueries.selectPending().executeAsList().size +
        db.examQueries.selectPending().executeAsList().size +
        db.usage_sessionQueries.selectPending().executeAsList().size +
        db.app_ruleQueries.selectPending().executeAsList().size +
        db.mail_draftQueries.selectPending().executeAsList().size +
        db.mail_actionQueries.selectPending().executeAsList().size +
        db.hw_checkQueries.selectPending().executeAsList().size +
        db.approvalQueries.selectPending().executeAsList().size +
        db.researchQueries.selectPending().executeAsList().size
}
