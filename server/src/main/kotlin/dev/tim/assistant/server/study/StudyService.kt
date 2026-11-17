package dev.tim.assistant.server.study

import dev.tim.assistant.db.Course
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

const val CHECK_DATE_KEY = "study:lastCheckDate"
const val STUDY_CHECK_INTERVAL_MS = 15L * 60 * 1000
private const val CHECK_HOUR = 6
private const val MAX_COURSE_NAME = 100
private const val MAX_FILE_NAME = 200
private val JERUSALEM: ZoneId = ZoneId.of("Asia/Jerusalem")

/** The date (yyyy-MM-dd) in Asia/Jerusalem at [nowMillis]. */
fun jerusalemDate(nowMillis: Long): String = Instant.ofEpochMilli(nowMillis).atZone(JERUSALEM).toLocalDate().toString()

/** True when Jerusalem local time is 06:00 or later and [lastCheckDate] (yyyy-MM-dd) is before today. */
fun isDueForCheck(nowMillis: Long, lastCheckDate: String?): Boolean {
    val local = Instant.ofEpochMilli(nowMillis).atZone(JERUSALEM)
    if (local.toLocalTime() < LocalTime.of(CHECK_HOUR, 0)) return false
    return lastCheckDate == null || lastCheckDate < local.toLocalDate().toString()
}

/** Files are listed only for Moodle courses Tim switched on (`enabled`) that Moodle still shows (`visible`). */
fun filesWanted(course: Course, moodleCourse: MoodleCourse): Boolean = course.enabled != 0L && moodleCourse.visible

sealed interface CheckOutcome {
    data class Done(val courses: Int, val newFiles: Int) : CheckOutcome

    /** Another check is running. */
    data object Busy : CheckOutcome

    /** No Moodle config or no token. */
    data object NotConnected : CheckOutcome

    /** Moodle said `invalidtoken`; the status is now false. */
    data object Rejected : CheckOutcome

    /** A network or server problem; nothing is marked as checked. */
    data object Failed : CheckOutcome
}

sealed interface ConnectOutcome {
    data class Connected(val courses: Int) : ConnectOutcome

    data object Rejected : ConnectOutcome

    data object Unreachable : ConnectOutcome
}

sealed interface UploadOutcome {
    data class Created(val fileId: String) : UploadOutcome

    data object BadCourse : UploadOutcome
}

enum class DeleteOutcome { DELETED, NOT_FOUND, MOODLE_FILE }

enum class StudySetOutcome { OK, NOT_FOUND }

enum class EnabledOutcome { OK, NOT_FOUND }

/**
 * Moodle ingestion and study-file bookkeeping. Every row write is `writeIfNewer` under the DB write lock
 * with `updatedAt = max(now, stored + 1)`, and an unchanged row is not rewritten. Network and file work
 * never run inside the lock. Extraction happens one file at a time.
 */
class StudyService(
    private val sdb: ServerDb,
    private val store: SyncStore,
    val files: StudyFiles,
    private val moodle: MoodleClient?,
    private val tokens: MoodleTokenStore,
    val status: MoodleStatus,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val extract: (File, String) -> Extraction = TextExtractor::extract,
) {
    private val log = LoggerFactory.getLogger(StudyService::class.java)
    private val db get() = sdb.db
    private val checkGate = Mutex()
    private val workerGate = Mutex()
    private val recheck = AtomicBoolean(false)
    private val downloadUrls = ConcurrentHashMap<String, String>()
    private val jobs = ConcurrentLinkedQueue<Job>()

    val moodleConfigured: Boolean get() = moodle != null

    // ---- row access -------------------------------------------------------------------------------

    private suspend fun <T> read(block: () -> T): T = sdb.writeLock.withLock { block() }

    private fun currentRow(table: String, id: String): SyncRow? = when (table) {
        "course" -> db.courseQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        "study_file" -> db.study_fileQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        else -> throw IllegalArgumentException("not a study table")
    }

    /** Must run inside the write lock and a transaction. Returns true when a row was written. */
    private fun putRow(table: String, id: String, data: JsonObject, deleted: Boolean): Boolean {
        val current = currentRow(table, id)
        if (current != null && current.deleted == deleted && current.data == data) return false
        val updatedAt = maxOf(clock(), (current?.updatedAt ?: -1L) + 1)
        return store.writeIfNewer(SyncRow(table, id, updatedAt, deleted, data))
    }

    private suspend fun writeRow(table: String, id: String, data: JsonObject, deleted: Boolean = false): Boolean =
        sdb.writeLock.withLock { db.transactionWithResult { putRow(table, id, data, deleted) } }

    private fun courseData(moodleId: Long?, name: String, enabled: Boolean) = JsonObject(
        mapOf(
            "moodleId" to (moodleId?.let { JsonPrimitive(it) } ?: JsonNull),
            "name" to JsonPrimitive(name),
            "enabled" to JsonPrimitive(enabled),
        ),
    )

    private fun fileData(
        courseId: String, name: String, origin: String, sizeBytes: Long, textStatus: String, studySetDocId: String?,
    ) = JsonObject(
        mapOf(
            "courseId" to JsonPrimitive(courseId),
            "name" to JsonPrimitive(name),
            "origin" to JsonPrimitive(origin),
            "sizeBytes" to JsonPrimitive(sizeBytes),
            "textStatus" to JsonPrimitive(textStatus),
            "studySetDocId" to (studySetDocId?.let { JsonPrimitive(it) } ?: JsonNull),
        ),
    )

    private fun dev.tim.assistant.db.Study_file.data(textStatus: String = this.textStatus, size: Long = sizeBytes) =
        fileData(courseId, name, origin, size, textStatus, studySetDocId)

    suspend fun file(id: String) = read { db.study_fileQueries.selectById(id).executeAsOneOrNull() }

    // ---- start-up, scheduler -----------------------------------------------------------------------

    /** Sweeps leftovers, checks the stored token once and resumes unfinished work. */
    fun start() {
        launchTracked {
            files.sweepTemps()
            kick()
            if (moodle != null && tokens.exists()) {
                refreshStatus()
                if (status.get() != false && needsResume()) check()
            }
        }
    }

    /** One `core_webservice_get_site_info` call: true / false (invalidtoken) / unchanged on a network error. */
    suspend fun refreshStatus() {
        val client = moodle ?: return
        val token = tokens.read() ?: return
        try {
            client.siteInfo(token)
            status.set(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MoodleException) {
            if (e.isInvalidToken) status.set(false)
        } catch (e: Exception) {
            log.warn("moodle status check failed: {}", e::class.simpleName)
        }
    }

    private suspend fun needsResume(): Boolean = read {
        db.study_fileQueries.selectAll().executeAsList().any {
            it.deleted == 0L && it.origin == "MOODLE" &&
                (it.textStatus == "PENDING" || (it.textStatus == "READY" && !files.hasAll(it.id)))
        }
    }

    /** One Scheduler tick: run the daily check when it is due. */
    suspend fun tick() {
        if (moodle == null || !tokens.exists() || status.get() == false) return
        val last = read { db.kvQueries.get(CHECK_DATE_KEY).executeAsOneOrNull() }
        if (isDueForCheck(clock(), last)) check()
    }

    // ---- Moodle connection --------------------------------------------------------------------------

    /** Verifies [token], stores it, marks Moodle ok and starts a check in the server scope. */
    suspend fun connect(token: String): ConnectOutcome {
        val client = moodle ?: return ConnectOutcome.Unreachable
        val count = try {
            client.courses(token, client.siteInfo(token)).size
        } catch (e: CancellationException) {
            throw e
        } catch (e: MoodleException) {
            return if (e.isInvalidToken) ConnectOutcome.Rejected else ConnectOutcome.Unreachable
        } catch (e: Exception) {
            log.warn("moodle connect failed: {}", e::class.simpleName)
            return ConnectOutcome.Unreachable
        }
        tokens.write(token, clock())
        status.set(true)
        launchTracked { check() }
        return ConnectOutcome.Connected(count)
    }

    /** Starts a check soon; when one is already running it runs once more right after. */
    fun requestCheck() {
        if (moodle == null || !tokens.exists()) return
        recheck.set(true)
        launchTracked { check() }
    }

    // ---- the check ----------------------------------------------------------------------------------

    /** Lists courses and (for switched-on courses) their PDF/PPTX files; downloads continue in the worker. */
    suspend fun check(): CheckOutcome {
        val client = moodle ?: return CheckOutcome.NotConnected
        if (!tokens.exists()) return CheckOutcome.NotConnected
        if (!checkGate.tryLock()) return CheckOutcome.Busy
        var outcome: CheckOutcome
        try {
            var courses = 0
            var newFiles = 0
            do {
                recheck.set(false)
                outcome = checkOnce(client)
                if (outcome is CheckOutcome.Done) {
                    courses = outcome.courses
                    newFiles += outcome.newFiles
                }
            } while (recheck.get() && outcome is CheckOutcome.Done)
            if (outcome is CheckOutcome.Done) outcome = CheckOutcome.Done(courses, newFiles)
        } finally {
            checkGate.unlock()
        }
        if (recheck.get() && outcome is CheckOutcome.Done) launchTracked { check() }
        return outcome
    }

    private suspend fun checkOnce(client: MoodleClient): CheckOutcome {
        val token = tokens.read() ?: return CheckOutcome.NotConnected
        var allOk = true
        var newFiles = 0
        val courses: List<MoodleCourse>
        try {
            val userId = client.siteInfo(token)
            status.set(true)
            courses = client.courses(token, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MoodleException) {
            if (e.isInvalidToken) {
                status.set(false)
                return CheckOutcome.Rejected
            }
            log.warn("moodle check failed: {}", e.errorCode)
            return CheckOutcome.Failed
        } catch (e: Exception) {
            log.warn("moodle check failed: {}", e::class.simpleName)
            return CheckOutcome.Failed
        }
        for (c in courses) upsertMoodleCourse(c)
        for (c in courses) {
            val stored = read { db.courseQueries.selectById("course:${c.id}").executeAsOneOrNull() } ?: continue
            if (stored.deleted != 0L || !filesWanted(stored, c)) continue
            val listed = try {
                client.courseFiles(token, c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MoodleException) {
                if (e.isInvalidToken) {
                    status.set(false)
                    return CheckOutcome.Rejected
                }
                allOk = false
                log.warn("moodle course listing failed: {}", e.errorCode)
                continue
            } catch (e: Exception) {
                allOk = false
                log.warn("moodle course listing failed: {}", e::class.simpleName)
                continue
            }
            for (f in listed) if (discover(stored.id, f)) newFiles++
        }
        if (!allOk) {
            kick()
            return CheckOutcome.Failed
        }
        sdb.writeLock.withLock { db.kvQueries.put(CHECK_DATE_KEY, jerusalemDate(clock())) }
        kick()
        return CheckOutcome.Done(courses.size, newFiles)
    }

    /** Creates or updates the course row; a check never changes the stored `enabled` (new Moodle courses are off). */
    private suspend fun upsertMoodleCourse(c: MoodleCourse) {
        val id = "course:${c.id}"
        val name = c.fullName.trim().take(MAX_COURSE_NAME).ifBlank { "Course ${c.id}" }
        sdb.writeLock.withLock {
            db.transaction {
                val stored = db.courseQueries.selectById(id).executeAsOneOrNull()
                putRow("course", id, courseData(c.id, name, (stored?.enabled ?: 0L) != 0L), deleted = false)
            }
        }
    }

    /** Lists one Moodle file. Returns true when it is a new row. A known id is never relisted. */
    private suspend fun discover(courseId: String, f: MoodleFile): Boolean {
        val name = f.fileName.trim()
        val lower = name.lowercase()
        if (!lower.endsWith(".pdf") && !lower.endsWith(".pptx")) return false
        if (moodle == null || !sameSite(moodle.baseUrl, f.fileUrl)) return false
        val id = "sfile:" + sha256Hex(f.fileUrl)
        val existing = read { db.study_fileQueries.selectById(id).executeAsOneOrNull() }
        if (existing != null) {
            if (existing.deleted == 0L && existing.origin == "MOODLE") {
                if (existing.textStatus == "PENDING") {
                    downloadUrls[id] = f.fileUrl
                } else if (existing.textStatus == "READY" && !files.hasAll(id)) {
                    writeRow("study_file", id, existing.data(textStatus = "PENDING"))
                    downloadUrls[id] = f.fileUrl
                }
            }
            return false
        }
        val shown = f.fileName.take(MAX_FILE_NAME) // the name as Moodle gave it; only the type check trims
        val declared = f.fileSize ?: 0L
        if (declared > MAX_STUDY_FILE_BYTES) {
            writeRow("study_file", id, fileData(courseId, shown, "MOODLE", declared, "FAILED", null))
        } else {
            writeRow("study_file", id, fileData(courseId, shown, "MOODLE", declared, "PENDING", null))
            downloadUrls[id] = f.fileUrl
        }
        return true
    }

    // ---- the worker ---------------------------------------------------------------------------------

    private fun launchTracked(block: suspend () -> Unit): Job {
        jobs.removeIf { !it.isActive }
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("study task failed: {}", e::class.simpleName)
            }
        }
        jobs.add(job)
        return job
    }

    /** Wakes the worker: downloads and extracts every PENDING file, one at a time. */
    fun kick() {
        launchTracked { workerGate.withLock { processPending() } }
    }

    /** Test hook: waits until all started check and worker tasks have finished. */
    suspend fun idle() {
        while (true) {
            val running = jobs.filter { it.isActive }
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    internal suspend fun processPending() {
        val pending = read {
            db.study_fileQueries.selectAll().executeAsList().filter { it.deleted == 0L && it.textStatus == "PENDING" }
        }
        for (row in pending) {
            val current = file(row.id)
            if (current == null || current.deleted != 0L || current.textStatus != "PENDING") continue
            if (current.origin == "UPLOAD") {
                if (files.hasOriginal(current.id)) extractAndStore(current) else setStatus(current, "FAILED")
            } else {
                downloadAndStore(current)
            }
        }
    }

    private suspend fun downloadAndStore(row: dev.tim.assistant.db.Study_file) {
        if (!files.hasOriginal(row.id)) {
            val client = moodle ?: return
            val url = downloadUrls[row.id] ?: return
            val token = tokens.read() ?: return
            val tmp = files.newTemp()
            try {
                withContext(Dispatchers.IO) { client.download(token, url, tmp) }
                files.publish(tmp, files.original(row.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileTooLargeException) {
                setStatus(row, "FAILED")
                return
            } catch (e: MoodleException) {
                if (e.isInvalidToken) status.set(false)
                log.warn("moodle download failed: {}", e.errorCode)
                return
            } catch (e: Exception) {
                log.warn("moodle download failed: {}", e::class.simpleName)
                return
            } finally {
                tmp.delete()
            }
            downloadUrls.remove(row.id)
        }
        extractAndStore(row)
    }

    private suspend fun extractAndStore(row: dev.tim.assistant.db.Study_file) {
        val original = files.original(row.id)
        val extension = row.name.trim().substringAfterLast('.', "")
        val result = try {
            withContext(Dispatchers.IO) { extract(original, extension) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("text extraction failed: {}", e::class.simpleName)
            Extraction.NoText
        }
        when (result) {
            is Extraction.Text -> {
                try {
                    withContext(Dispatchers.IO) { files.writeText(row.id, result.text) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("writing extracted text failed: {}", e::class.simpleName)
                    return
                }
                setStatus(row, "READY", original.length())
            }
            Extraction.NoText -> {
                files.text(row.id).delete()
                setStatus(row, "FAILED", original.length())
            }
        }
    }

    /** Re-reads the row so a delete or a newer change made meanwhile is respected. */
    private suspend fun setStatus(row: dev.tim.assistant.db.Study_file, textStatus: String, size: Long? = null) {
        sdb.writeLock.withLock {
            db.transaction {
                val fresh = db.study_fileQueries.selectById(row.id).executeAsOneOrNull()
                if (fresh != null && fresh.deleted == 0L) {
                    putRow("study_file", fresh.id, fresh.data(textStatus, size ?: fresh.sizeBytes), deleted = false)
                }
            }
        }
    }

    // ---- uploads, deletes, pointers -----------------------------------------------------------------

    /**
     * Stores an upload whose bytes already sit in [temp]: publishes the original, then writes the course (when
     * [courseParam] is a new name) and the PENDING file row in one transaction, then wakes the worker.
     */
    suspend fun createUpload(temp: File, displayName: String, size: Long, courseParam: String): UploadOutcome {
        val given = courseParam.trim()
        if (given.isEmpty() || given.length > MAX_COURSE_NAME) {
            val existing = read { db.courseQueries.selectById(given).executeAsOneOrNull() }
            if (existing == null || existing.deleted != 0L) return UploadOutcome.BadCourse
        }
        val fileId = "sfile:upload:" + UUID.randomUUID()
        files.publish(temp, files.original(fileId))
        try {
            sdb.writeLock.withLock {
                db.transaction {
                    val known = db.courseQueries.selectById(given).executeAsOneOrNull()?.takeIf { it.deleted == 0L }
                    val courseId = known?.id
                        ?: db.courseQueries.selectAll().executeAsList().firstOrNull {
                            it.deleted == 0L && it.moodleId == null && it.name.equals(given, ignoreCase = true)
                        }?.id
                        ?: ("course:manual:" + UUID.randomUUID()).also {
                            putRow("course", it, courseData(null, given, true), deleted = false)
                        }
                    putRow(
                        "study_file", fileId,
                        fileData(courseId, displayName.take(MAX_FILE_NAME), "UPLOAD", size, "PENDING", null),
                        deleted = false,
                    )
                }
            }
        } catch (e: Throwable) {
            files.delete(fileId)
            throw e
        }
        kick()
        return UploadOutcome.Created(fileId)
    }

    /** Deletes an UPLOAD-origin file: tombstone row, then both disk files. */
    suspend fun deleteUpload(id: String): DeleteOutcome {
        val outcome = sdb.writeLock.withLock {
            db.transactionWithResult {
                val row = db.study_fileQueries.selectById(id).executeAsOneOrNull()
                if (row == null || row.deleted != 0L) {
                    DeleteOutcome.NOT_FOUND
                } else if (row.origin != "UPLOAD") {
                    DeleteOutcome.MOODLE_FILE
                } else {
                    putRow("study_file", id, row.data(), deleted = true)
                    DeleteOutcome.DELETED
                }
            }
        }
        if (outcome == DeleteOutcome.DELETED) files.delete(id)
        return outcome
    }

    /** Records the doc that holds the study set; the same docId again is a no-op. */
    suspend fun setStudySet(id: String, docId: String): StudySetOutcome = sdb.writeLock.withLock {
        db.transactionWithResult {
            val row = db.study_fileQueries.selectById(id).executeAsOneOrNull()
            if (row == null || row.deleted != 0L) {
                StudySetOutcome.NOT_FOUND
            } else {
                putRow(
                    "study_file", id,
                    fileData(row.courseId, row.name, row.origin, row.sizeBytes, row.textStatus, docId),
                    deleted = false,
                )
                StudySetOutcome.OK
            }
        }
    }

    /** Switches a course on or off. Turning one on starts a check. */
    suspend fun setCourseEnabled(id: String, enabled: Boolean): EnabledOutcome {
        var turnedOn = false
        val outcome = sdb.writeLock.withLock {
            db.transactionWithResult {
                val row = db.courseQueries.selectById(id).executeAsOneOrNull()
                if (row == null || row.deleted != 0L) {
                    EnabledOutcome.NOT_FOUND
                } else {
                    turnedOn = enabled && row.enabled == 0L
                    putRow("course", id, courseData(row.moodleId, row.name, enabled), deleted = false)
                    EnabledOutcome.OK
                }
            }
        }
        if (turnedOn) requestCheck()
        return outcome
    }
}
