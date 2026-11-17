package dev.tim.assistant.ai

import dev.tim.assistant.ai.tools.researchJobReport
import dev.tim.assistant.research.CODEX_LIMIT_NOTICE
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.research.RESEARCH_NUTRITION
import dev.tim.assistant.research.templateQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** What an approval card shows for a research job: kept from the Phase 13 research tests because the card still uses it. */
class ResearchReportTest {
    private val rig = JobRig()
    private val repo = rig.repo

    private suspend fun queuedJob(): String = repo.queueJob("RESEARCH", """{"category":"NUTRITION","item":"hummus"}""")

    @Test
    fun a_job_that_is_still_queued_has_no_report_yet() = runBlocking<Unit> {
        val id = queuedJob()
        assertNull(researchJobReport(repo, repo.jobById(id)!!))
    }

    @Test
    fun a_done_job_reports_the_answer_with_its_sources() = runBlocking<Unit> {
        val rowId = rig.db.seedResearch(
            RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, "hummus"), "Per 100 g: 166 kcal", "{}", """["https://example.org/h"]""", rig.clock, null,
        )
        val id = queuedJob()
        val claim = repo.claimJob(id)!!
        repo.completeJob(claim, """{"researchId":"$rowId"}""")
        assertEquals("Per 100 g: 166 kcal\nSources: https://example.org/h", researchJobReport(repo, repo.jobById(id)!!))
    }

    @Test
    fun a_codex_limit_failure_reports_the_plain_out_of_usage_message_and_is_found_by_lastJobFailureAt() = runBlocking<Unit> {
        val id = queuedJob()
        assertNull(repo.lastJobFailureAt(listOf("RESEARCH", "MEAL_LOOKUP"), CODEX_LIMIT_TEXT))
        repo.failJob(repo.claimJob(id)!!, CODEX_LIMIT_TEXT, failNow = true)
        val job = repo.jobById(id)!!
        assertEquals("FAILED", job.status)
        assertEquals(CODEX_LIMIT_NOTICE, researchJobReport(repo, job))
        assertEquals(job.updatedAt, repo.lastJobFailureAt(listOf("RESEARCH", "MEAL_LOOKUP"), CODEX_LIMIT_TEXT))
        assertNull(repo.lastJobFailureAt(listOf("MEAL_LOOKUP"), CODEX_LIMIT_TEXT))
    }

    @Test
    fun any_other_failure_reports_its_error() = runBlocking<Unit> {
        val id = queuedJob()
        repo.failJob(repo.claimJob(id)!!, "bad output", failNow = true)
        assertEquals("Research failed: bad output", researchJobReport(repo, repo.jobById(id)!!))
    }
}
