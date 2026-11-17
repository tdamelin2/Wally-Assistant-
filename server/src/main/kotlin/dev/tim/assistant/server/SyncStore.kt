package dev.tim.assistant.server

import dev.tim.assistant.ai.jobs.SERVER_JOB_KINDS
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushResponse
import dev.tim.assistant.model.Rejected
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.model.upsertFromSyncRow
import dev.tim.assistant.server.ai.Estimate
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

const val PULL_PAGE_SIZE = 1000
const val MAX_PUSH_ROWS = 500
private const val FUTURE_WARN_MS = 5 * 60 * 1000L
private const val SEQ_KEY = "seqCounter"
private val MAIL_WRITE_TABLES = setOf("mail_draft", "mail_action")
/** Tables the make-task-only `calendar` token may never push (mail rows plus approvals, research and jobs). */
private val CALENDAR_FORBIDDEN_TABLES = MAIL_WRITE_TABLES + setOf("approval", "research", "ai_job")
private val DRAFT_LOCKED = setOf("SENDING", "SENT", "UNKNOWN")
private val ACTION_LOCKED = setOf("DONE", "FAILED")
/** Tables whose server-owned state a stale client push may not change (Phase 17): decided approvals, server-run jobs. */
private val OWNED_STATE_TABLES = setOf("approval", "ai_job")
const val REJECT_FORBIDDEN = "forbidden"
const val REJECT_STATE = "invalid: status"

/**
 * Server-side sync storage. [beforeWrite] runs just before each row write inside the push
 * transaction; tests use it to simulate SQLITE_FULL. Database failures propagate (they are never
 * turned into per-row rejections).
 */
class SyncStore(
    private val sdb: ServerDb,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeWrite: () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(SyncStore::class.java)
    private val db get() = sdb.db

    /**
     * Called (outside any lock) after a push transaction committed an APPROVED mail draft or action.
     * Main points it at the Gmail write worker; it must never block.
     */
    @Volatile
    var gmailWake: (() -> Unit)? = null

    /**
     * One transaction under writeLock for the whole request. [fromCalendarToken] is true when the
     * request carried the WallyCalendar `calendar` token: it may not write mail drafts or actions.
     */
    suspend fun push(rows: List<SyncRow>, fromCalendarToken: Boolean = false): PushResponse {
        var wake = false
        val response = sdb.writeLock.withLock {
            db.transactionWithResult {
                val accepted = ArrayList<String>()
                val rejected = ArrayList<Rejected>()
                var approved = false
                for (row in rows) {
                    if (row.table in CALENDAR_FORBIDDEN_TABLES && fromCalendarToken) {
                        rejected += Rejected(row.id, REJECT_FORBIDDEN)
                        continue
                    }
                    var reason = validateRow(row)
                    // a client may only push DRAFT/APPROVED, but deleting a row the worker already
                    // owns pushes it back unchanged (with its worker status) plus deleted=true
                    if (reason != null && row.table in MAIL_WRITE_TABLES && isPureDeleteOfLocked(row)) reason = null
                    if (reason != null) {
                        if (row.table in MAIL_WRITE_TABLES) restamp(row)
                        rejected += Rejected(row.id, reason)
                        continue
                    }
                    if (row.table in MAIL_WRITE_TABLES) {
                        val fence = fenceReason(row)
                        if (fence != null) {
                            restamp(row)
                            rejected += Rejected(row.id, fence)
                            continue
                        }
                    }
                    if (row.table in OWNED_STATE_TABLES) {
                        val owned = ownedStateFence(row)
                        if (owned != null) {
                            restampStored(row)
                            rejected += Rejected(row.id, owned)
                            continue
                        }
                    }
                    if (row.updatedAt > clock() + FUTURE_WARN_MS) {
                        log.warn("row updatedAt is more than 5 minutes in the future: table={} id={}", row.table, row.id)
                    }
                    if (writeIfNewer(row) && row.table in MAIL_WRITE_TABLES && (row.data["status"] as? JsonPrimitive)?.content == "APPROVED") {
                        approved = true
                    }
                    accepted += row.id
                }
                wake = approved
                PushResponse(accepted, rejected)
            }
        }
        if (wake) gmailWake?.invoke()
        return response
    }

    /**
     * The state fence for mail_draft and mail_action: once the server worker owns a row (draft
     * SENDING/SENT/UNKNOWN, action DONE/FAILED) a client push is refused, except a pure delete (deleted
     * true, every other field equal to the stored row). Returns the rejection reason or null.
     */
    private fun fenceReason(row: SyncRow): String? {
        val stored = storedMail(row) ?: return null
        if (!isLocked(row.table, stored)) return null
        return if (isPureDelete(row, stored)) null else REJECT_STATE
    }

    private fun storedMail(row: SyncRow): SyncRow? = when (row.table) {
        "mail_draft" -> db.mail_draftQueries.selectById(row.id).executeAsOneOrNull()?.let { toSyncRow(it) }
        else -> db.mail_actionQueries.selectById(row.id).executeAsOneOrNull()?.let { toSyncRow(it) }
    }

    private fun isLocked(table: String, stored: SyncRow): Boolean {
        val status = (stored.data["status"] as? JsonPrimitive)?.content
        return status in (if (table == "mail_draft") DRAFT_LOCKED else ACTION_LOCKED)
    }

    private fun isPureDelete(row: SyncRow, stored: SyncRow): Boolean = row.deleted && row.data == stored.data

    private fun isPureDeleteOfLocked(row: SyncRow): Boolean {
        val stored = storedMail(row) ?: return false
        return isLocked(row.table, stored) && isPureDelete(row, stored)
    }

    private fun storedOwned(row: SyncRow): SyncRow? = when (row.table) {
        "approval" -> db.approvalQueries.selectById(row.id).executeAsOneOrNull()?.let { toSyncRow(it) }
        else -> db.ai_jobQueries.selectById(row.id).executeAsOneOrNull()?.let { toSyncRow(it) }
    }

    /**
     * The fence for approval and ai_job rows (the server decides approvals and runs RESEARCH / MEAL_LOOKUP jobs): with a stored
     * row, a client may not change an approval at all (a decided one stays decided, a PENDING one is decided only through
     * POST /v1/approvals) and may not move a server-kind job out of QUEUED. A first insert (nothing stored), a pure delete and
     * a QUEUED job pushed as QUEUED stay allowed. Returns the rejection reason or null.
     */
    private fun ownedStateFence(row: SyncRow): String? {
        val stored = storedOwned(row) ?: return null
        if (isPureDelete(row, stored)) return null
        val status = (row.data["status"] as? JsonPrimitive)?.content
        val storedStatus = (stored.data["status"] as? JsonPrimitive)?.content
        return when (row.table) {
            "approval" -> if (status == storedStatus && row.data == stored.data) null else REJECT_STATE
            else -> {
                val kind = (stored.data["kind"] as? JsonPrimitive)?.content
                if (kind !in SERVER_JOB_KINDS) null
                else if (status == "QUEUED" && storedStatus == "QUEUED") null
                else REJECT_STATE
            }
        }
    }

    private fun restampStored(row: SyncRow) {
        val stored = storedOwned(row) ?: return
        forceWrite(stored.copy(updatedAt = maxOf(clock(), row.updatedAt + 1, stored.updatedAt + 1)))
    }

    /**
     * After a refused push the stored row is rewritten with a newer updatedAt and a new serverSeq, so
     * the client's REJECTED copy is replaced on its next pull.
     */
    private fun restamp(row: SyncRow) {
        val stored = storedMail(row) ?: return
        forceWrite(stored.copy(updatedAt = maxOf(clock(), row.updatedAt + 1, stored.updatedAt + 1)))
    }

    /**
     * Stores [row] unconditionally with the next serverSeq (used for server-side status writes and
     * re-stamps). Same caller rules as [writeIfNewer]: hold writeLock, be inside a transaction.
     */
    internal fun forceWrite(row: SyncRow) {
        beforeWrite()
        val seq = (db.kvQueries.get(SEQ_KEY).executeAsOneOrNull()?.toLongOrNull() ?: 0L) + 1
        upsertFromSyncRow(db, row, serverSeq = seq, syncState = "SYNCED")
        db.kvQueries.put(SEQ_KEY, seq.toString())
    }

    /**
     * Stores [row] with the next serverSeq only when no stored row exists or the incoming updatedAt is
     * greater. The caller must already hold writeLock and be inside a transaction; this never takes
     * the Mutex itself. Returns true when the row was written.
     */
    internal fun writeIfNewer(row: SyncRow): Boolean {
        val stored = storedUpdatedAt(row.table, row.id)
        if (stored != null && row.updatedAt <= stored) return false
        beforeWrite()
        val seq = (db.kvQueries.get(SEQ_KEY).executeAsOneOrNull()?.toLongOrNull() ?: 0L) + 1
        upsertFromSyncRow(db, row, serverSeq = seq, syncState = "SYNCED")
        db.kvQueries.put(SEQ_KEY, seq.toString())
        return true
    }

    /**
     * Stores a nutrition estimate on a meal with a new serverSeq, keeping the meal's updatedAt and user
     * fields. Re-reads the meal first: it must still exist, have exactly [updatedAt], not be deleted and
     * still have no kcal. Otherwise nothing is written and the counter is untouched (returns false). The
     * caller must already hold writeLock and be inside a transaction.
     */
    fun writeEstimate(id: String, updatedAt: Long, e: Estimate, model: String): Boolean {
        val meal = db.mealQueries.selectById(id).executeAsOneOrNull() ?: return false
        if (meal.updatedAt != updatedAt || meal.deleted != 0L || meal.kcal != null) return false
        beforeWrite()
        val seq = (db.kvQueries.get(SEQ_KEY).executeAsOneOrNull()?.toLongOrNull() ?: 0L) + 1
        db.mealQueries.writeEstimate(e.kcal, e.proteinG, e.carbsG, e.fatG, model, seq, id, updatedAt)
        db.kvQueries.put(SEQ_KEY, seq.toString())
        return true
    }

    internal fun storedUpdatedAt(table: String, id: String): Long? = when (table) {
        "checkin" -> db.checkinQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "spending" -> db.spendingQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "journal" -> db.journalQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "task" -> db.taskQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "health_day" -> db.health_dayQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "workout" -> db.workoutQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "meal" -> db.mealQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "doc_folder" -> db.doc_folderQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "doc" -> db.docQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "ai_job" -> db.ai_jobQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "dream" -> db.dreamQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "dream_pattern" -> db.dream_patternQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "goal" -> db.goalQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "cal_event" -> db.cal_eventQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "mail_item" -> db.mail_itemQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "briefing" -> db.briefingQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "course" -> db.courseQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "study_file" -> db.study_fileQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "card" -> db.cardQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "quiz" -> db.quizQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "exam" -> db.examQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "usage_session" -> db.usage_sessionQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "app_rule" -> db.app_ruleQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "listen" -> db.listenQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "mail_draft" -> db.mail_draftQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "mail_action" -> db.mail_actionQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "hw_check" -> db.hw_checkQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "approval" -> db.approvalQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        "research" -> db.researchQueries.selectById(id).executeAsOneOrNull()?.updatedAt
        else -> throw IllegalArgumentException("unknown table '$table'")
    }

    /** The whole pull runs under writeLock in one transaction, so no push can slip a lower seq in. */
    suspend fun pull(since: Long): PullResponse = sdb.writeLock.withLock {
        db.transactionWithResult {
            val all = ArrayList<Pair<Long, SyncRow>>()
            db.checkinQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.spendingQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.journalQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.taskQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.health_dayQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.workoutQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.mealQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.doc_folderQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.docQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.ai_jobQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.dreamQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.dream_patternQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.goalQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.cal_eventQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.mail_itemQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.briefingQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.courseQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.study_fileQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.cardQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.quizQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.examQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.usage_sessionQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.app_ruleQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.listenQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.mail_draftQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.mail_actionQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.hw_checkQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.approvalQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            db.researchQueries.selectChangedSince(since).executeAsList().forEach { all += it.serverSeq to toSyncRow(it) }
            all.sortBy { it.first }
            val page = all.take(PULL_PAGE_SIZE)
            PullResponse(page.map { it.second }, page.lastOrNull()?.first ?: since)
        }
    }
}
