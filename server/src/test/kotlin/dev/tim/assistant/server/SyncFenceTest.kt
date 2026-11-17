package dev.tim.assistant.server

import dev.tim.assistant.model.SyncRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** Phase 17: a stale client may not undo what the server decided or claimed. */
class SyncFenceTest {
    private val sdb = openDb(tempDbPath())
    private val store = SyncStore(sdb)

    private fun approval(id: String, status: String, updatedAt: Long, result: String? = null, deleted: Boolean = false) = SyncRow(
        "approval", id, updatedAt, deleted,
        obj("tool" to "create_task", "argsJson" to "{}", "summary" to "Add task", "status" to status, "createdAt" to 1, "resultText" to result),
    )

    private fun job(id: String, kind: String, status: String, updatedAt: Long, deleted: Boolean = false) = SyncRow(
        "ai_job", id, updatedAt, deleted,
        obj("kind" to kind, "inputJson" to "{}", "status" to status, "resultJson" to null, "error" to null, "attempts" to 0, "createdAt" to 1, "startedAt" to null),
    )

    private fun stored(table: String, id: String): SyncRow = runBlocking { store.pull(0).rows.single { it.table == table && it.id == id } }

    private fun status(row: SyncRow) = (row.data["status"] as JsonPrimitive).content

    @Test
    fun aDecidedApprovalCannotBeChangedByAStaleClientPushAndTheRowIsRestamped() = runBlocking<Unit> {
        store.push(listOf(approval("appr:1", "PENDING", 1_000)))
        // the server decides it (as POST /v1/approvals does), newer than the client's copy
        dev.tim.assistant.server.chat.ServerWriter(sdb, store) { 5_000L }.write(approval("appr:1", "DONE", 0, "Task added"))
        val before = stored("approval", "appr:1")
        assertEquals("DONE", status(before))

        val response = store.push(listOf(approval("appr:1", "PENDING", 9_000)))
        assertEquals(listOf("appr:1"), response.rejected.map { it.id })
        assertEquals(REJECT_STATE, response.rejected.single().reason)
        val after = stored("approval", "appr:1")
        assertEquals("DONE", status(after))
        assertTrue(after.updatedAt > 9_000) // re-stamped, so the client's copy is replaced at its next pull
    }

    @Test
    fun aPendingApprovalIsDecidedOnlyByTheServerButAnInitialPendingInsertIsAccepted() = runBlocking<Unit> {
        val first = store.push(listOf(approval("appr:1", "PENDING", 1_000)))
        assertEquals(listOf("appr:1"), first.accepted)
        val rejected = store.push(listOf(approval("appr:1", "REJECTED", 2_000)))
        assertEquals(1, rejected.rejected.size)
        assertEquals("PENDING", status(stored("approval", "appr:1")))
    }

    @Test
    fun aPureDeleteOfAnApprovalStillGoesThrough() = runBlocking<Unit> {
        store.push(listOf(approval("appr:1", "DONE", 1_000, "ok")))
        val response = store.push(listOf(approval("appr:1", "DONE", 2_000, "ok", deleted = true)))
        assertEquals(listOf("appr:1"), response.accepted)
        assertTrue(stored("approval", "appr:1").deleted)
    }

    @Test
    fun aServerClaimedResearchJobCannotBeMovedBackByAStaleClient() = runBlocking<Unit> {
        val inserted = store.push(listOf(job("ai_job:r1", "RESEARCH", "QUEUED", 1_000)))
        assertEquals(listOf("ai_job:r1"), inserted.accepted) // initial QUEUED insert is accepted
        // the server claims it
        dev.tim.assistant.server.chat.ServerWriter(sdb, store) { 5_000L }.write(job("ai_job:r1", "RESEARCH", "RUNNING", 0))
        assertEquals("RUNNING", status(stored("ai_job", "ai_job:r1")))

        val stale = store.push(listOf(job("ai_job:r1", "RESEARCH", "QUEUED", 9_000)))
        assertEquals(REJECT_STATE, stale.rejected.single().reason)
        val after = stored("ai_job", "ai_job:r1")
        assertEquals("RUNNING", status(after))
        assertTrue(after.updatedAt > 9_000)
    }

    @Test
    fun anOldLaptopCannotClaimAQueuedMealLookupJob() = runBlocking<Unit> {
        store.push(listOf(job("ai_job:m1", "MEAL_LOOKUP", "QUEUED", 1_000)))
        val claim = store.push(listOf(job("ai_job:m1", "MEAL_LOOKUP", "RUNNING", 2_000)))
        assertEquals(1, claim.rejected.size)
        assertEquals("QUEUED", status(stored("ai_job", "ai_job:m1")))
    }

    @Test
    fun otherJobKindsAreNotFenced() = runBlocking<Unit> {
        store.push(listOf(job("ai_job:q1", "QUIZ", "QUEUED", 1_000)))
        val claim = store.push(listOf(job("ai_job:q1", "QUIZ", "RUNNING", 2_000)))
        assertEquals(listOf("ai_job:q1"), claim.accepted)
        assertEquals("RUNNING", status(stored("ai_job", "ai_job:q1")))
    }
}
