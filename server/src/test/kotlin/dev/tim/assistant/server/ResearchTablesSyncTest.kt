package dev.tim.assistant.server

import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushResponse
import dev.tim.assistant.model.SyncRow
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Phase 13: approval and research (client tables) through push and pull, validation, and the calendar token. */
class ResearchTablesSyncTest {
    private suspend fun ApplicationTestBuilder.pushOk(rows: List<SyncRow>): PushResponse {
        val r = client.push(rows)
        assertEquals(HttpStatusCode.OK, r.status)
        return testJson.decodeFromString(PushResponse.serializer(), r.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.pull(since: Long): PullResponse {
        val r = client.pullRaw("?since=$since")
        assertEquals(HttpStatusCode.OK, r.status)
        return testJson.decodeFromString(PullResponse.serializer(), r.bodyAsText())
    }

    private fun ApplicationTestBuilder.setup(): ServerDb {
        val db = openDb(tempDbPath())
        serverWith(SyncStore(db), testConfig("unused"))
        return db
    }

    private val hex = "a".repeat(64)

    private fun approval(
        updatedAt: Long, id: String = "appr:1", tool: String = "create_task", argsJson: String = "{}",
        summary: String = "Make a task", status: String = "PENDING", createdAt: Long = 50,
        resultText: String? = null, deleted: Boolean = false,
    ) = SyncRow(
        "approval", id, updatedAt, deleted,
        obj("tool" to tool, "argsJson" to argsJson, "summary" to summary, "status" to status, "createdAt" to createdAt, "resultText" to resultText),
    )

    private fun research(
        updatedAt: Long, category: String = "NUTRITION", id: String = "research:$category:$hex",
        question: String = "calories in hummus", questionKey: String = "calories in hummus", answerText: String = "168 kcal",
        answerJson: String = "{}", sources: String = "[]", askedAt: Long = 50, expiresAt: Long? = null, deleted: Boolean = false,
    ) = SyncRow(
        "research", id, updatedAt, deleted,
        obj(
            "category" to category, "questionKey" to questionKey, "question" to question, "answerText" to answerText,
            "answerJson" to answerJson, "sources" to sources, "askedAt" to askedAt, "expiresAt" to expiresAt,
        ),
    )

    @Test
    fun approvalAndResearchRoundTripAndTombstones() = testApplication {
        setup()
        val rows = listOf(approval(100, resultText = "ok"), research(100, expiresAt = 999))
        assertEquals(rows.map { it.id }, pushOk(rows).accepted)
        val pulled = pull(0).rows.associateBy { it.id }
        assertEquals(2, pulled.size)
        assertEquals(JsonPrimitive("PENDING"), pulled.getValue("appr:1").data["status"])
        assertEquals(JsonPrimitive("ok"), pulled.getValue("appr:1").data["resultText"])
        assertEquals(JsonPrimitive(999L), pulled.getValue("research:NUTRITION:$hex").data["expiresAt"])
        // Phase 17: the server owns an approval once stored; a client push of changed data is fenced
        assertEquals(1, pushOk(listOf(approval(150, resultText = null), research(150, expiresAt = null))).rejected.size)
        val nulls = pull(0).rows.associateBy { it.id }
        assertEquals(JsonPrimitive("ok"), nulls.getValue("appr:1").data["resultText"])
        assertEquals(JsonNull, nulls.getValue("research:NUTRITION:$hex").data["expiresAt"])
        // the fenced push re-stamped the approval to the server clock, so the pure delete must be newer than that
        val later = System.currentTimeMillis() + 3_600_000L
        pushOk(rows.map { it.copy(updatedAt = later, deleted = true) })
        val after = pull(0).rows
        assertEquals(2, after.size)
        assertTrue(after.all { it.deleted })
    }

    @Test
    fun olderAndEqualPushesDoNotOverwrite() = testApplication {
        setup()
        pushOk(listOf(approval(100, status = "PENDING")))
        val seq = pull(0).cursor
        pushOk(listOf(approval(100, status = "REJECTED"), approval(50, status = "DONE")))
        val pulled = pull(0)
        assertEquals(JsonPrimitive("PENDING"), pulled.rows.single().data["status"])
        assertTrue(pulled.cursor >= seq) // fenced pushes re-stamp the stored row
        // a newer client decision is fenced too: approvals are decided through POST /v1/approvals
        assertEquals(1, pushOk(listOf(approval(101, status = "REJECTED"))).rejected.size)
        assertEquals(JsonPrimitive("PENDING"), pull(0).rows.single().data["status"])
    }

    @Test
    fun approvalRulesAreEnforced() = testApplication {
        val db = setup()
        val bad = listOf(
            approval(1, id = "appr:t", tool = "Create-Task") to "tool",
            approval(1, id = "appr:t2", tool = "") to "tool",
            approval(1, id = "appr:t3", tool = "a".repeat(41)) to "tool",
            approval(1, id = "appr:a", argsJson = "x".repeat(20_001)) to "argsJson",
            approval(1, id = "appr:s0", summary = "") to "summary",
            approval(1, id = "appr:s1", summary = "x".repeat(2_001)) to "summary",
            approval(1, id = "appr:st", status = "WAITING") to "status",
            approval(1, id = "appr:c", createdAt = -1) to "createdAt",
            approval(1, id = "appr:r", resultText = "x".repeat(10_001)) to "resultText",
        )
        val response = pushOk(bad.map { it.first })
        assertTrue(response.accepted.isEmpty())
        assertEquals(bad.associate { it.first.id to "invalid: ${it.second}" }, response.rejected.associate { it.id to it.reason })
        val good = listOf(
            approval(1, id = "appr:ok1", tool = "a".repeat(40), argsJson = "x".repeat(20_000), summary = "x".repeat(2_000), resultText = "x".repeat(10_000)),
            approval(1, id = "appr:ok2", status = "EXPIRED"),
        )
        assertEquals(good.map { it.id }, pushOk(good).accepted)
        assertEquals(2, db.db.approvalQueries.selectAll().executeAsList().size)
    }

    @Test
    fun researchRulesAreEnforced() = testApplication {
        setup()
        val bad = listOf(
            research(1, id = "research:NUTRITION:$hex", category = "FOOD") to "category",
            research(1, id = "research:FOOD:$hex", category = "FOOD") to "category",
            research(1, id = "research:NUTRITION:${"a".repeat(63)}") to "id",
            research(1, id = "research:NUTRITION:${"A".repeat(64)}") to "id",
            research(1, id = "research:DEFINITION:$hex") to "id",
            research(1, id = "other:NUTRITION:$hex") to "id",
            research(1, question = "") to "question",
            research(1, question = "x".repeat(4_001)) to "question",
            research(1, questionKey = "x".repeat(4_001)) to "questionKey",
            research(1, answerText = "x".repeat(20_001)) to "answerText",
            research(1, answerJson = "x".repeat(50_001)) to "answerJson",
            research(1, sources = "x".repeat(10_001)) to "sources",
            research(1, askedAt = -1) to "askedAt",
        )
        val response = pushOk(bad.map { it.first })
        assertTrue(response.accepted.isEmpty())
        assertEquals(bad.size, response.rejected.size)
        assertTrue(response.rejected.zip(bad).all { (r, b) -> r.reason == "invalid: ${b.second}" })
        val good = listOf(
            research(1, category = "OTHER", question = "x".repeat(4_000), questionKey = "x".repeat(4_000), answerText = "x".repeat(20_000)),
            research(1, category = "APP_CATEGORY", id = "research:APP_CATEGORY:${"0123456789abcdef".repeat(4)}"),
        )
        assertEquals(good.map { it.id }, pushOk(good).accepted)
    }

    @Test
    fun mistypedFieldsAreRejected() = testApplication {
        setup()
        val badStatus = approval(1).let { it.copy(data = it.toMap { put("status", JsonPrimitive(5)) }) }
        val missing = research(1).let { r -> r.copy(data = kotlinx.serialization.json.JsonObject(r.data - "answerJson")) }
        val response = pushOk(listOf(badStatus, missing))
        assertEquals(listOf("invalid: status", "invalid: answerJson"), response.rejected.map { it.reason })
    }

    private fun SyncRow.toMap(edit: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit) =
        kotlinx.serialization.json.JsonObject(data.toMutableMap().also(edit))

    @Test
    fun theCalendarTokenCannotWriteApprovalResearchOrJobRows() = runBlocking<Unit> {
        val sdb = openDb(tempDbPath())
        val store = SyncStore(sdb)
        val job = SyncRow(
            "ai_job", "job:1", 100, false,
            obj(
                "kind" to "RESEARCH", "inputJson" to "{}", "status" to "QUEUED", "resultJson" to null, "error" to null,
                "attempts" to 0, "createdAt" to 100, "startedAt" to null,
            ),
        )
        val task = SyncRow("task", "task:1", 100, false, obj("title" to "Make Wally task", "dueDate" to null, "done" to false, "doneAt" to null, "goalId" to null))
        val response = store.push(listOf(approval(100), research(100), job, task), fromCalendarToken = true)
        assertEquals(setOf("appr:1", "research:NUTRITION:$hex", "job:1"), response.rejected.map { it.id }.toSet())
        assertTrue(response.rejected.all { it.reason == "forbidden" })
        assertEquals(listOf("task:1"), response.accepted)
        assertTrue(sdb.db.approvalQueries.selectAll().executeAsList().isEmpty())
        assertTrue(sdb.db.researchQueries.selectAll().executeAsList().isEmpty())
        assertTrue(sdb.db.ai_jobQueries.selectAll().executeAsList().isEmpty())
        // the same rows with an ordinary token are accepted
        val ok = store.push(listOf(approval(100), research(100), job))
        assertEquals(3, ok.accepted.size)
        assertNull(ok.rejected.firstOrNull())
    }
}
