package dev.tim.assistant.server.codex

import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.ai.tools.ApprovalExecutor
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.research.CodexProcess
import dev.tim.assistant.research.CodexProcessResult
import dev.tim.assistant.research.CodexResearch
import dev.tim.assistant.research.RESEARCH_NUTRITION
import dev.tim.assistant.research.RESEARCH_OTHER
import dev.tim.assistant.research.researchId
import dev.tim.assistant.research.templateQuestion
import dev.tim.assistant.server.chat.ServerApprovalSink
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ServerCodexTest {
    private val rig = CodexRig()

    @AfterTest
    fun close() = rig.close()

    private fun riceInput(item: String = "rice"): String {
        val id = researchId(RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, item))
        return """{"category":"NUTRITION","item":"$item","researchId":"$id"}"""
    }

    @Test
    fun a_template_job_pushed_by_a_client_runs_once_and_both_rows_reach_a_pull() = runBlocking {
        val pushed = toSyncRow(Ai_job("job-1", rig.now, 0, 0, "PENDING", "RESEARCH", riceInput(), "QUEUED", null, null, 0, rig.now, null))
        assertTrue(rig.store.push(listOf(pushed)).rejected.isEmpty())
        assertTrue(rig.codex.tick())
        assertFalse(rig.codex.tick(), "the job runs once")
        assertEquals(1, rig.runner.asks.size)
        assertEquals(RESEARCH_NUTRITION to templateQuestion(RESEARCH_NUTRITION, "rice"), rig.runner.asks.single())
        val rows = rig.store.pull(0).rows
        val job = rows.first { it.table == "ai_job" && it.id == "job-1" }
        assertEquals("DONE", (job.data["status"] as JsonPrimitive).content)
        val research = rows.first { it.table == "research" }
        assertEquals(researchId(RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, "rice")), research.id)
        assertNull(rig.job("job-1")!!.error)
    }

    @Test
    fun a_valid_cached_row_is_reused_without_a_codex_call() = runBlocking {
        rig.seedJob("job-1", "RESEARCH", riceInput())
        rig.codex.tick()
        rig.seedJob("job-2", "RESEARCH", riceInput(), createdAt = rig.now + 1)
        rig.codex.tick()
        assertEquals(1, rig.runner.asks.size)
        assertEquals("DONE", rig.job("job-2")!!.status)
    }

    @Test
    fun an_invalid_item_fails_the_job_at_once_without_codex() = runBlocking {
        rig.seedJob("job-1", "RESEARCH", """{"category":"NUTRITION","item":"a@b.example","researchId":"x"}""")
        rig.codex.tick()
        val job = rig.job("job-1")!!
        assertEquals("FAILED", job.status)
        assertTrue(job.error!!.startsWith("Can't research"))
        assertTrue(rig.runner.asks.isEmpty())
    }

    @Test
    fun a_codex_limit_ends_the_job_with_the_limit_text() = runBlocking {
        rig.runner.handler = { _, _ -> throw JobFailedNow(CODEX_LIMIT_TEXT) }
        rig.seedJob("job-1", "RESEARCH", riceInput())
        rig.codex.tick()
        assertEquals("FAILED", rig.job("job-1")!!.status)
        assertEquals(CODEX_LIMIT_TEXT, rig.job("job-1")!!.error)
    }

    @Test
    fun a_failed_attempt_goes_back_to_queued_and_the_third_one_fails() = runBlocking {
        rig.runner.handler = { _, _ -> throw IllegalStateException("boom") }
        rig.seedJob("job-1", "RESEARCH", riceInput())
        rig.codex.tick()
        assertEquals("QUEUED", rig.job("job-1")!!.status)
        assertEquals(1L, rig.job("job-1")!!.attempts)
        rig.codex.tick()
        rig.codex.tick()
        assertEquals("FAILED", rig.job("job-1")!!.status)
        assertEquals(3L, rig.job("job-1")!!.attempts)
        assertEquals("boom", rig.job("job-1")!!.error)
    }

    @Test
    fun a_stale_running_job_is_reset_and_the_old_claim_cannot_finish_it() = runBlocking {
        rig.seedJob("job-1", "RESEARCH", riceInput(), status = "RUNNING", startedAt = rig.now - 31 * 60_000L, attempts = 0)
        // the job is claimed again after the reset; the runner resets it again behind the claim's back
        rig.runner.handler = { category, question ->
            rig.now += 31 * 60_000L
            rig.jobs.resetStale(listOf("RESEARCH"), rig.now)
            FakeCodexRunner.nutrition(category, question)
        }
        rig.codex.tick()
        val job = rig.job("job-1")!!
        assertEquals("QUEUED", job.status, "the finish of a dead claim writes nothing")
        assertEquals(2L, job.attempts)
        assertTrue(rig.researchRows().isEmpty())
    }

    @Test
    fun a_stale_job_reaching_three_attempts_fails() = runBlocking {
        rig.seedJob("job-1", "RESEARCH", riceInput(), status = "RUNNING", startedAt = rig.now - 31 * 60_000L, attempts = 2)
        assertEquals(1, rig.jobs.resetStale(listOf("RESEARCH"), rig.now))
        assertEquals("FAILED", rig.job("job-1")!!.status)
        assertEquals("interrupted too many times", rig.job("job-1")!!.error)
    }

    @Test
    fun an_approved_question_queues_the_frozen_question_and_runs_it() = runBlocking {
        val question = "Who wrote the Kalevala?"
        rig.seedApproval("ap-1", "research_question", """{"text":"$question"}""")
        val sink = ServerApprovalSink(rig.sdb, rig.store, rig.writer, null, null, rig.codex)
        val result = ApprovalExecutor(sink) { rig.now }.approve("ap-1")
        assertEquals(ApprovalAction.DONE, result.action)
        assertEquals("research_question:ap-1", result.message)
        assertEquals("DONE", rig.approval("ap-1")!!.status)
        assertEquals("QUEUED", rig.job("research_question:ap-1")!!.status)

        rig.runner.handler = { _, q -> dev.tim.assistant.research.ResearchRow("OTHER", q, "Elias Lonnrot", """{"answer":"Elias Lonnrot","sources":[]}""", emptyList()) }
        rig.codex.tick()
        assertEquals(RESEARCH_OTHER to question, rig.runner.asks.single())
        assertEquals("DONE", rig.job("research_question:ap-1")!!.status)
        assertNotNull(rig.sdb.db.researchQueries.selectById(researchId(RESEARCH_OTHER, question)).executeAsOneOrNull())

        // a second yes changes nothing
        val again = ApprovalExecutor(sink) { rig.now }.approve("ap-1")
        assertEquals(ApprovalAction.NOT_PENDING, again.action)
        assertEquals(1, rig.sdb.db.ai_jobQueries.selectAll().executeAsList().size)
    }

    @Test
    fun a_second_approval_of_the_same_question_points_at_the_running_job() = runBlocking {
        rig.seedApproval("ap-1", "research_question", """{"text":"Same question?"}""")
        rig.seedApproval("ap-2", "research_question", """{"text":"same   question?"}""")
        val first = rig.codex.approve("ap-1", JsonObject(mapOf("text" to JsonPrimitive("Same question?"))))
        val second = rig.codex.approve("ap-2", JsonObject(mapOf("text" to JsonPrimitive("same   question?"))))
        assertEquals("research_question:ap-1", first.message)
        assertEquals("research_question:ap-1", second.message)
        assertEquals(1, rig.sdb.db.ai_jobQueries.selectAll().executeAsList().size)
    }

    @Test
    fun an_expired_or_missing_approval_queues_nothing() = runBlocking {
        rig.seedApproval("old", "research_question", """{"text":"q"}""", createdAt = rig.now - 8L * 24 * 3600 * 1000)
        val args = JsonObject(mapOf("text" to JsonPrimitive("q")))
        assertEquals(ApprovalAction.EXPIRED, rig.codex.approve("old", args).action)
        assertEquals(ApprovalAction.NOT_FOUND, rig.codex.approve("nope", args).action)
        assertEquals("PENDING", rig.approval("old")!!.status)
        assertTrue(rig.sdb.db.ai_jobQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun a_job_whose_approval_holds_another_question_never_reaches_codex() = runBlocking {
        rig.seedApproval("ap-1", "research_question", """{"text":"The approved one"}""", status = "DONE")
        rig.seedJob("research_question:ap-1", "RESEARCH", """{"category":"OTHER","question":"Something else","researchId":"x"}""")
        rig.codex.tick()
        assertEquals("FAILED", rig.job("research_question:ap-1")!!.status)
        assertEquals("Question was not approved", rig.job("research_question:ap-1")!!.error)
        assertTrue(rig.runner.asks.isEmpty())
    }

    @Test
    fun a_rejected_pending_or_missing_approval_does_not_run_the_job() = runBlocking {
        rig.seedApproval("ap-rej", "research_question", """{"text":"q1"}""", status = "REJECTED")
        rig.seedJob("research_question:ap-rej", "RESEARCH", """{"category":"OTHER","question":"q1","researchId":"x"}""", createdAt = rig.now)
        rig.seedJob("research_question:ap-none", "RESEARCH", """{"category":"OTHER","question":"q2","researchId":"x"}""", createdAt = rig.now + 1)
        rig.seedJob("plain-id", "RESEARCH", """{"category":"OTHER","question":"q3","researchId":"x"}""", createdAt = rig.now + 2)
        repeat(3) { rig.codex.tick() }
        assertEquals("FAILED", rig.job("research_question:ap-rej")!!.status)
        assertEquals("FAILED", rig.job("research_question:ap-none")!!.status)
        assertEquals("FAILED", rig.job("plain-id")!!.status)
        assertTrue(rig.runner.asks.isEmpty())
    }

    @Test
    fun a_pending_approval_is_a_failed_attempt_not_a_hard_failure() = runBlocking {
        rig.seedApproval("ap-1", "research_question", """{"text":"q1"}""")
        rig.seedJob("research_question:ap-1", "RESEARCH", """{"category":"OTHER","question":"q1","researchId":"x"}""")
        rig.codex.tick()
        assertEquals("QUEUED", rig.job("research_question:ap-1")!!.status)
        assertEquals(1L, rig.job("research_question:ap-1")!!.attempts)
    }

    @Test
    fun the_laptop_job_kinds_are_not_touched_by_the_research_tick() = runBlocking {
        rig.seedJob("other", "MEAL_PLAN", "{}")
        assertFalse(rig.codex.tick())
        assertEquals("QUEUED", rig.job("other")!!.status)
    }

    @Test
    fun only_one_codex_process_runs_at_a_time() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        rig.runner.handler = { category, question ->
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(50)
            running.decrementAndGet()
            FakeCodexRunner.nutrition(category, question)
        }
        List(3) { i -> async { rig.codex.runner.ask(RESEARCH_NUTRITION, "q$i") } }.awaitAll()
        assertEquals(1, peak.get())
        assertEquals(3, rig.runner.asks.size)
    }

    @Test
    fun the_configured_codex_path_is_the_program_that_gets_started() = runBlocking {
        val started = mutableListOf<String>()
        val process = CodexProcess { executable, args, _, stdin, _ ->
            started += executable
            assertNotNull(stdin)
            val out = args[args.indexOf("-o") + 1]
            File(out).writeText("""{"kcal":130,"proteinG":2.7,"carbsG":28,"fatG":0.3,"sources":["https://example.org/a"]}""")
            CodexProcessResult(0, "", "")
        }
        val real = CodexResearch(process, findExecutable = { "/opt/wally/bin/codex" })
        val codex = ServerCodex(rig.sdb, rig.writer, real, rig.jobs) { rig.now }
        rig.seedJob("job-1", "RESEARCH", riceInput())
        codex.tick()
        assertEquals(listOf("/opt/wally/bin/codex"), started)
        assertEquals("DONE", rig.job("job-1")!!.status)
    }

    @Test
    fun a_missing_codex_program_fails_the_job_with_the_unavailable_text() = runBlocking {
        val process = CodexProcess { _, _, _, _, _ -> throw java.io.IOException("no such file") }
        val codex = ServerCodex(rig.sdb, rig.writer, CodexResearch(process, findExecutable = { "/nope/codex" }), rig.jobs) { rig.now }
        rig.seedJob("job-1", "RESEARCH", riceInput())
        codex.tick()
        assertEquals("FAILED", rig.job("job-1")!!.status)
        assertTrue(rig.job("job-1")!!.error!!.startsWith("Codex is not set up on the server"))
    }
}
