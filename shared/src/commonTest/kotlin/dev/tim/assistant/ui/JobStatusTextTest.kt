package dev.tim.assistant.ui

import dev.tim.assistant.db.Ai_job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobStatusTextTest {
    private fun job(status: String, error: String? = null, kind: String = "K", input: String = "{}", createdAt: Long = 1) =
        Ai_job("j$createdAt", 1, 0, 0, "SYNCED", kind, input, status, null, error, 0, createdAt, null)

    @Test
    fun wording_per_status() {
        assertNull(jobStatusText(null))
        assertEquals("Queued — runs when Wally is open on the laptop", jobStatusText(job("QUEUED")))
        assertEquals("Working…", jobStatusText(job("RUNNING")))
        assertEquals("Failed: boom", jobStatusText(job("FAILED", "boom")))
        assertNull(jobStatusText(job("DONE")))
    }

    @Test
    fun latest_job_matches_kind_and_input() {
        val jobs = listOf(
            job("DONE", kind = "A", input = """{"id":"x"}""", createdAt = 3),
            job("QUEUED", kind = "A", input = """{"id":"y"}""", createdAt = 2),
            job("FAILED", kind = "A", input = """{"id":"x"}""", createdAt = 1),
        )
        assertEquals("j3", latestJob(jobs, "A", "id", "x")?.id)
        assertEquals("j2", latestJob(jobs, "A", "id", "y")?.id)
        assertNull(latestJob(jobs, "B", "id", "x"))
        assertNull(latestJob(jobs, "A", "id", "z"))
    }
}
