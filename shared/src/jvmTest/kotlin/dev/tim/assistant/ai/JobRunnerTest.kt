package dev.tim.assistant.ai

import dev.tim.assistant.ai.jobs.JOB_IDLE_SYNC_MS
import dev.tim.assistant.ai.jobs.JOB_OFFLINE_WAIT_MS
import dev.tim.assistant.ai.jobs.JOB_STALE_MS
import dev.tim.assistant.ai.jobs.JOB_TICK_MS
import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.ai.jobs.JobHandler
import dev.tim.assistant.ai.jobs.JobResult
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.OllamaOffline
import dev.tim.assistant.ai.jobs.TickResult
import dev.tim.assistant.db.Ai_job
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class JobRunnerTest {
    private val rig = JobRig()
    private val repo = rig.repo
    private var syncCalls = 0
    private var syncFails = false
    private var onSync: suspend () -> Unit = {}

    private fun runner(vararg handlers: Pair<String, JobHandler>) = JobRunner(
        repo,
        sync = {
            syncCalls++
            onSync()
            if (syncFails) throw IOException("no network")
        },
        handlers = handlers.toMap(),
        clock = { rig.clock },
    )

    private suspend fun queue(kind: String = "TEST", input: String = "{}", id: String? = null, advance: Long = 1000): String {
        rig.clock += advance
        return repo.queueJob(kind, input, id)
    }

    private val ok = JobHandler { JobResult("""{"done":true}""") }

    @Test
    fun oldest_queued_job_runs_first() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        val r = runner("TEST" to JobHandler { order += it.id; JobResult(null) })
        queue(id = "b")
        queue(id = "a")
        queue(id = "c")
        repeat(3) { assertTrue(r.tick() is TickResult.Done) }
        assertEquals(listOf("b", "a", "c"), order)
        assertEquals(TickResult.Idle, r.tick())
    }

    @Test
    fun jobs_created_at_the_same_time_run_in_id_order() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        val r = runner("TEST" to JobHandler { order += it.id; JobResult(null) })
        queue(id = "z", advance = 0)
        queue(id = "m", advance = 0)
        repeat(2) { r.tick() }
        assertEquals(listOf("m", "z"), order)
    }

    @Test
    fun done_writes_the_result_and_clears_the_error() = runBlocking<Unit> {
        val r = runner("TEST" to ok)
        val id = queue()
        assertEquals(TickResult.Done(id), r.tick())
        val job = rig.job(id)
        assertEquals("DONE", job.status)
        assertEquals("""{"done":true}""", job.resultJson)
        assertNull(job.error)
        assertEquals("PENDING", job.syncState)
    }

    @Test
    fun an_exception_adds_an_attempt_and_the_third_one_fails_the_job() = runBlocking<Unit> {
        val r = runner("TEST" to JobHandler { throw IllegalStateException("boom") })
        val id = queue()
        assertEquals(TickResult.Retry(id), r.tick())
        rig.job(id).let { assertEquals("QUEUED", it.status); assertEquals(1, it.attempts); assertEquals("boom", it.error) }
        assertEquals(TickResult.Retry(id), r.tick())
        rig.job(id).let { assertEquals("QUEUED", it.status); assertEquals(2, it.attempts) }
        assertEquals(TickResult.Failed(id), r.tick())
        rig.job(id).let { assertEquals("FAILED", it.status); assertEquals(3, it.attempts); assertEquals("boom", it.error) }
        assertEquals(TickResult.Idle, r.tick())
    }

    @Test
    fun job_failed_now_fails_at_once() = runBlocking<Unit> {
        val r = runner("TEST" to JobHandler { throw JobFailedNow("Codex limit reached") })
        val id = queue()
        assertEquals(TickResult.Failed(id), r.tick())
        rig.job(id).let { assertEquals("FAILED", it.status); assertEquals(1, it.attempts); assertEquals("Codex limit reached", it.error) }
    }

    @Test
    fun an_unknown_kind_fails_with_a_fixed_text() = runBlocking<Unit> {
        val r = runner("TEST" to ok)
        val id = queue(kind = "MYSTERY")
        assertEquals(TickResult.Failed(id), r.tick())
        rig.job(id).let { assertEquals("FAILED", it.status); assertEquals("unknown job kind", it.error) }
    }

    @Test
    fun a_stale_running_job_is_reset_with_one_more_attempt_and_then_runs() = runBlocking<Unit> {
        val r = runner("TEST" to ok)
        val id = queue()
        repo.claimJob(id)
        rig.clock += JOB_STALE_MS + 1
        assertEquals(TickResult.Done(id), r.tick())
        assertEquals(1, rig.job(id).attempts)
        assertEquals("DONE", rig.job(id).status)
    }

    @Test
    fun a_stale_running_job_on_its_third_attempt_is_failed_not_run() = runBlocking<Unit> {
        var ran = false
        val r = runner("TEST" to JobHandler { ran = true; JobResult(null) })
        val id = queue()
        repo.claimJob(id)
        rig.db.ai_jobQueries.upsert(rig.job(id).copy(attempts = 2))
        rig.clock += JOB_STALE_MS + 1
        assertEquals(TickResult.Idle, r.tick())
        assertEquals("FAILED", rig.job(id).status)
        assertEquals(3, rig.job(id).attempts)
        assertTrue(!ran)
    }

    @Test
    fun a_recent_running_job_is_left_alone() = runBlocking<Unit> {
        var ran = false
        val r = runner("TEST" to JobHandler { ran = true; JobResult(null) })
        val id = queue()
        repo.claimJob(id)
        rig.clock += JOB_STALE_MS - 1000
        assertEquals(TickResult.Idle, r.tick())
        assertEquals("RUNNING", rig.job(id).status)
        assertEquals(0, rig.job(id).attempts)
        assertTrue(!ran)
    }

    @Test
    fun server_kinds_stay_queued_and_a_stale_running_one_is_not_reset() = runBlocking<Unit> {
        var ran = 0
        val counting = JobHandler { ran++; JobResult(null) }
        val r = runner("RESEARCH" to counting, "MEAL_LOOKUP" to counting, "TEST" to ok)
        val research = queue(kind = "RESEARCH")
        val meal = queue(kind = "MEAL_LOOKUP")
        val staleMeal = queue(kind = "MEAL_LOOKUP")
        repo.claimJob(staleMeal)
        rig.clock += JOB_STALE_MS + 1
        assertEquals(TickResult.Idle, r.tick())
        assertEquals(0, ran)
        assertEquals("QUEUED", rig.job(research).status)
        assertEquals("QUEUED", rig.job(meal).status)
        assertEquals("RUNNING", rig.job(staleMeal).status)
        assertEquals(0, rig.job(staleMeal).attempts)
        // a laptop job queued after them still runs
        val laptop = queue()
        assertEquals(TickResult.Done(laptop), r.tick())
        assertEquals("QUEUED", rig.job(research).status)
    }

    @Test
    fun the_model_being_offline_keeps_attempts_and_waits_sixty_seconds() = runBlocking<Unit> {
        val r = runner("TEST" to JobHandler { throw OllamaOffline() })
        val id = queue()
        val result = r.tick()
        assertEquals(TickResult.Offline(id), result)
        rig.job(id).let { assertEquals("QUEUED", it.status); assertEquals(0, it.attempts); assertNull(it.startedAt) }
        assertEquals(JOB_OFFLINE_WAIT_MS, r.delayAfter(result))
        assertEquals(60_000L, JOB_OFFLINE_WAIT_MS)
        assertEquals(JOB_TICK_MS, r.delayAfter(TickResult.Idle))
        assertEquals(JOB_TICK_MS, r.delayAfter(TickResult.Done(id)))
        // many offline rounds never use up attempts
        repeat(5) { r.tick() }
        assertEquals(0, rig.job(id).attempts)
    }

    @Test
    fun cancellation_is_rethrown_and_not_counted_as_a_failure() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val r = runner("TEST" to JobHandler { entered.complete(Unit); awaitCancellation() })
        val id = queue()
        val scope = CoroutineScope(Dispatchers.Default)
        val deferred = scope.async { r.tick() }
        withTimeout(10_000) { entered.await() }
        deferred.cancelAndJoin()
        assertTrue(deferred.isCancelled)
        rig.job(id).let { assertEquals("RUNNING", it.status); assertEquals(0, it.attempts); assertNull(it.error) }
    }

    @Test
    fun a_failing_sync_never_counts_as_a_job_failure_and_cached_jobs_still_run() = runBlocking<Unit> {
        syncFails = true
        val r = runner("TEST" to ok)
        val id = queue()
        assertEquals(TickResult.Done(id), r.tick())
        assertEquals(0, rig.job(id).attempts)
        assertEquals("DONE", rig.job(id).status)
        assertTrue(syncCalls >= 2)
    }

    @Test
    fun the_claim_is_saved_as_running_and_pending_before_the_first_sync() = runBlocking<Unit> {
        val r = runner("TEST" to ok)
        val id = queue()
        val seen = mutableListOf<Ai_job>()
        onSync = { seen += rig.job(id) }
        r.tick()
        val atFirstSync = seen.first()
        assertEquals("RUNNING", atFirstSync.status)
        assertEquals("PENDING", atFirstSync.syncState)
        assertNotNull(atFirstSync.startedAt)
        assertEquals("DONE", seen.last().status)
    }

    @Test
    fun a_completion_for_a_claim_that_is_no_longer_live_is_a_no_op() = runBlocking<Unit> {
        val id = queue()
        // the handler is slow: meanwhile the job goes stale, is reset (attempts + 1) and queued again
        val r = runner(
            "TEST" to JobHandler {
                rig.clock += JOB_STALE_MS + 1
                repo.resetStaleJobs(rig.clock - JOB_STALE_MS)
                JobResult("""{"late":true}""")
            },
        )
        r.tick()
        rig.job(id).let {
            assertEquals("QUEUED", it.status)
            assertEquals(1, it.attempts)
            assertNull(it.resultJson)
        }
    }

    @Test
    fun a_second_claim_is_not_overwritten_by_the_first_ones_late_completion() = runBlocking<Unit> {
        val id = queue()
        val first = repo.claimJob(id)!!
        rig.clock += JOB_STALE_MS + 1
        repo.resetStaleJobs(rig.clock - JOB_STALE_MS)
        val second = repo.claimJob(id)!!
        assertTrue(!repo.completeJob(first, """{"old":true}"""))
        assertEquals("RUNNING", rig.job(id).status)
        assertTrue(repo.completeJob(second, """{"new":true}"""))
        assertEquals("""{"new":true}""", rig.job(id).resultJson)
    }

    @Test
    fun an_already_done_claim_is_not_completed_twice() = runBlocking<Unit> {
        val id = queue()
        val claim = repo.claimJob(id)!!
        assertTrue(repo.completeJob(claim, "a"))
        val updatedAt = rig.job(id).updatedAt
        assertTrue(!repo.completeJob(claim, "b"))
        assertEquals("a", rig.job(id).resultJson)
        assertEquals(updatedAt, rig.job(id).updatedAt)
    }

    @Test
    fun dream_kinds_always_get_the_fixed_error_text() = runBlocking<Unit> {
        val r = runner("DREAM_INTERPRET" to JobHandler { throw IllegalStateException("ZZDREAMMARKER secret") })
        val id = queue(kind = "DREAM_INTERPRET")
        r.tick()
        assertEquals("dream analysis failed", rig.job(id).error)
    }

    @Test
    fun start_syncs_first_and_keeps_syncing_while_idle() = runBlocking<Unit> {
        val r = runner("TEST" to ok)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = r.start(scope)
        val deadline = System.currentTimeMillis() + 5_000
        while (syncCalls == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(syncCalls >= 1)
        job.cancelAndJoin()
        assertEquals(30_000L, JOB_IDLE_SYNC_MS)
    }
}
