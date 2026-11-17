package dev.tim.assistant.ai.jobs

import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.ui.approvals.CODEX_JOB_KINDS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val JOB_STALE_MS = 30L * 60 * 1000
const val JOB_MAX_ATTEMPTS = 3
const val JOB_TICK_MS = 10_000L
const val JOB_IDLE_SYNC_MS = 30_000L
const val JOB_OFFLINE_WAIT_MS = 60_000L
const val DREAM_JOB_ERROR = "dream analysis failed"

/** Job kinds the server runs (Codex research and meal lookup); the laptop runner never claims or resets them. */
val SERVER_JOB_KINDS: List<String> = CODEX_JOB_KINDS

/** Runs one job kind. Handlers write their outputs through Repository's atomic output operations. */
fun interface JobHandler {
    suspend fun run(job: Ai_job): JobResult
}

/** [resultJson] goes into the job row when the runner completes it (null for dream jobs). */
data class JobResult(val resultJson: String?)

/** Throw from a handler to fail the job at once (no further attempts). */
class JobFailedNow(message: String) : Exception(message)

/** Throw from a handler when the model is unreachable: the job goes back to QUEUED, attempts unchanged. */
class OllamaOffline : Exception()

/** What one [JobRunner.tick] did. */
sealed interface TickResult {
    data object Idle : TickResult
    data class Done(val jobId: String) : TickResult
    data class Retry(val jobId: String) : TickResult
    data class Failed(val jobId: String) : TickResult
    data class Offline(val jobId: String) : TickResult
}

/**
 * Runs queued AI jobs on the desktop. One runner per process; Android never constructs it.
 * [sync] is the app's sync (awaited); a failing sync never counts as a job failure.
 */
class JobRunner(
    private val repo: Repository,
    private val sync: suspend () -> Unit,
    private val handlers: Map<String, JobHandler>,
    private val clock: () -> Long,
    /** Kinds this runner leaves alone (never claims, never stale-resets). Default: the server's kinds; tests of those handlers pass an empty list. */
    private val skipKinds: List<String> = SERVER_JOB_KINDS,
) {
    private var lastSyncAt: Long = Long.MIN_VALUE

    private suspend fun trySync() {
        try {
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or a failed sync: cached QUEUED jobs still run.
        }
        lastSyncAt = clock()
    }

    /** One step: reset stale RUNNING jobs, then run the oldest QUEUED job (claim, sync, handler, finish, sync). */
    suspend fun tick(): TickResult {
        repo.resetStaleJobs(clock() - JOB_STALE_MS, skipKinds)
        val queued = repo.oldestQueuedJob(skipKinds) ?: return TickResult.Idle
        val claim = repo.claimJob(queued.id) ?: return TickResult.Idle
        trySync()
        val handler = handlers[claim.kind]
        val result: TickResult = if (handler == null) {
            repo.failJob(claim, "unknown job kind", failNow = true)
            TickResult.Failed(claim.id)
        } else {
            try {
                val out = handler.run(claim)
                repo.completeJob(claim, out.resultJson)
                TickResult.Done(claim.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OllamaOffline) {
                repo.releaseJob(claim)
                TickResult.Offline(claim.id)
            } catch (e: JobFailedNow) {
                repo.failJob(claim, errorText(claim, e), failNow = true)
                TickResult.Failed(claim.id)
            } catch (e: Exception) {
                repo.failJob(claim, errorText(claim, e), failNow = false)
                if (repo.jobById(claim.id)?.status == "FAILED") TickResult.Failed(claim.id) else TickResult.Retry(claim.id)
            }
        }
        trySync()
        return result
    }

    /** DREAM_* jobs always get the fixed text; other kinds get the exception's message (no stack, no logging). */
    private fun errorText(job: Ai_job, e: Exception): String =
        if (job.kind.startsWith("DREAM_")) DREAM_JOB_ERROR else (e.message?.takeIf { it.isNotBlank() } ?: "job failed").take(500)

    /** How long the loop waits after [result]: 60 s after the model was unreachable, else 10 s. */
    fun delayAfter(result: TickResult): Long = if (result is TickResult.Offline) JOB_OFFLINE_WAIT_MS else JOB_TICK_MS

    /** Awaited sync, then tick every 10 s; while idle, sync at most every 30 s so phone jobs arrive. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        trySync()
        while (true) {
            val result = try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TickResult.Idle
            }
            if (result is TickResult.Idle && clock() - lastSyncAt >= JOB_IDLE_SYNC_MS) trySync()
            delay(delayAfter(result))
        }
    }
}
