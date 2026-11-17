package dev.tim.assistant.server.codex

import dev.tim.assistant.ai.jobs.JOB_MAX_ATTEMPTS
import dev.tim.assistant.ai.jobs.JOB_STALE_MS
import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.ai.jobs.OllamaOffline
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.research.isCodexLimitText
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.chat.ServerWriter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val JOB_ERROR_MAX_CHARS = 500

/** The string value of [key] in a job's input JSON, or null (missing, not a string, not JSON). */
fun jobInputText(job: Ai_job, key: String): String? =
    try {
        val value = (Json.parseToJsonElement(job.inputJson) as? JsonObject)?.get(key)
        if (value is JsonPrimitive && value !is JsonNull && value.isString) value.content else null
    } catch (e: Exception) {
        null
    }

/**
 * The server's own claim / finish / retry rules for the ai_job kinds it runs itself (RESEARCH, MEAL_LOOKUP). Every change is
 * one [ServerWriter] transaction, stamped newer than the stored row, and guarded by the claim: a job is "claimed" by the
 * `startedAt` stamp of its RUNNING row, so a finish that arrives after a stale reset (or a second claim) writes nothing.
 */
class ServerJobs(private val sdb: ServerDb, private val writer: ServerWriter) {
    private val db get() = sdb.db

    /** The oldest QUEUED, live job of [kind] (createdAt, then id), or null. */
    fun oldestQueued(kind: String): Ai_job? =
        db.ai_jobQueries.selectAll().executeAsList()
            .filter { it.deleted == 0L && it.kind == kind && it.status == "QUEUED" }
            .minWithOrNull(compareBy<Ai_job> { it.createdAt }.thenBy { it.id })

    /** QUEUED -> RUNNING with startedAt = now, in one transaction. Null when the job is no longer QUEUED. */
    suspend fun claim(id: String): Ai_job? = writer.transaction { tx ->
        val job = tx.db.ai_jobQueries.selectById(id).executeAsOneOrNull()
        if (job == null || job.deleted != 0L || job.status != "QUEUED") {
            null
        } else {
            val claimed = job.copy(status = "RUNNING", startedAt = tx.now, error = null)
            tx.write(toSyncRow(claimed))
            tx.db.ai_jobQueries.selectById(id).executeAsOne()
        }
    }

    /** The stored job when it is still RUNNING under exactly this claim. */
    fun liveClaim(db: AssistantDb, claim: Ai_job): Ai_job? =
        db.ai_jobQueries.selectById(claim.id).executeAsOneOrNull()
            ?.takeIf { it.deleted == 0L && it.status == "RUNNING" && it.startedAt == claim.startedAt }

    /** DONE with [resultJson]; false (nothing written) when the claim is no longer live. */
    suspend fun complete(claim: Ai_job, resultJson: String?): Boolean = writer.transaction { tx ->
        val live = liveClaim(tx.db, claim) ?: return@transaction false
        tx.write(toSyncRow(live.copy(status = "DONE", resultJson = resultJson, error = null)))
        true
    }

    /** A failed attempt: attempts + 1, back to QUEUED (FAILED at the third attempt or at once when [failNow]). Guarded by the claim. */
    suspend fun fail(claim: Ai_job, error: String, failNow: Boolean): Boolean = failInner(claim, error, failNow).first

    /** (written, the job when this write moved it to FAILED). */
    private suspend fun failInner(claim: Ai_job, error: String, failNow: Boolean): Pair<Boolean, Ai_job?> = writer.transaction { tx ->
        val live = liveClaim(tx.db, claim) ?: return@transaction false to null
        val attempts = live.attempts + 1
        val failed = failNow || attempts >= JOB_MAX_ATTEMPTS
        val next = live.copy(
            attempts = attempts, status = if (failed) "FAILED" else "QUEUED", error = error.take(JOB_ERROR_MAX_CHARS),
            startedAt = if (failed) live.startedAt else null,
        )
        tx.write(toSyncRow(next))
        true to (if (failed) next else null)
    }
    /** Back to QUEUED with attempts unchanged (no model was reachable right now). Guarded by the claim. */
    suspend fun release(claim: Ai_job): Boolean = writer.transaction { tx ->
        val live = liveClaim(tx.db, claim) ?: return@transaction false
        tx.write(toSyncRow(live.copy(status = "QUEUED", startedAt = null)))
        true
    }

    /**
     * RUNNING jobs of [kinds] started before now - 30 minutes go back to QUEUED with attempts + 1 (FAILED at the third),
     * the same rule the laptop runner used. Returns how many were reset.
     */
    suspend fun resetStale(kinds: Collection<String>, now: Long, onFailed: (suspend (Ai_job, String) -> Unit)? = null): Int {
        val failedJobs = ArrayList<Ai_job>()
        val count = writer.transaction { tx ->
            val cutoff = now - JOB_STALE_MS
            val stale = tx.db.ai_jobQueries.selectAll().executeAsList().filter {
                it.deleted == 0L && it.kind in kinds && it.status == "RUNNING" && (it.startedAt ?: 0L) < cutoff
            }
            for (job in stale) {
                val attempts = job.attempts + 1
                val failed = attempts >= JOB_MAX_ATTEMPTS
                val next = job.copy(
                    attempts = attempts, status = if (failed) "FAILED" else "QUEUED", startedAt = null,
                    error = if (failed) "interrupted too many times" else "interrupted",
                )
                tx.write(toSyncRow(next))
                if (failed) failedJobs += next
            }
            stale.size
        }
        // after the transaction has returned: a callback may open its own writer transaction
        for (job in failedJobs) notifyFailed(onFailed, job)
        return count
    }

    private suspend fun notifyFailed(onFailed: (suspend (Ai_job, String) -> Unit)?, job: Ai_job) {
        if (onFailed == null) return
        try {
            onFailed(job, job.error ?: "job failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a posting problem never changes the job: the reconciliation pass retries it later
        }
    }

    /**
     * Runs [body] for a claimed job and ends the job when [body] throws: [OllamaOffline] puts it back unchanged,
     * [JobFailedNow] fails it at once, any other exception is one failed attempt. [body] itself finishes a job that works.
     */
    suspend fun guarded(claim: Ai_job, onFailed: (suspend (Ai_job, String) -> Unit)? = null, body: suspend () -> Unit) {
        val failed: Ai_job? = try {
            body()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: OllamaOffline) {
            release(claim)
            null
        } catch (e: JobFailedNow) {
            failInner(claim, errorText(e), failNow = true).second
        } catch (e: Exception) {
            failInner(claim, errorText(e), failNow = false).second
        }
        if (failed != null) notifyFailed(onFailed, failed)
    }

    private fun errorText(e: Exception): String {
        val message = e.message ?: ""
        if (e !is JobFailedNow && isCodexLimitText(message)) return CODEX_LIMIT_TEXT
        return message.takeIf { it.isNotBlank() }?.take(JOB_ERROR_MAX_CHARS) ?: "job failed"
    }
}
