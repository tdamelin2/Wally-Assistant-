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
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Phase 8: the six new synced tables and task.goalId through the real push / pull routes. */
class NewTablesSyncTest {
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

    private fun folder(id: String, updatedAt: Long, name: String = "Plans", deleted: Boolean = false) =
        SyncRow("doc_folder", id, updatedAt, deleted, obj("name" to name))

    private fun doc(
        id: String, updatedAt: Long, title: String = "Notes", body: String = "# hi", source: String = "USER",
        folderId: String? = null, deleted: Boolean = false,
    ) = SyncRow("doc", id, updatedAt, deleted, obj("folderId" to folderId, "title" to title, "body" to body, "source" to source))

    private fun job(
        id: String, updatedAt: Long, kind: String = "GOAL_STEPS", status: String = "QUEUED", attempts: Int = 0,
        resultJson: String? = null, error: String? = null, startedAt: Long? = null,
    ) = SyncRow(
        "ai_job", id, updatedAt, false,
        obj(
            "kind" to kind, "inputJson" to """{"goalId":"g1"}""", "status" to status, "resultJson" to resultJson,
            "error" to error, "attempts" to attempts, "createdAt" to 5L, "startedAt" to startedAt,
        ),
    )

    private fun dream(id: String, updatedAt: Long, mood: Int = 4, lucid: Any = true, text: String = "I flew") = SyncRow(
        "dream", id, updatedAt, false,
        obj(
            "localDate" to "2026-11-05", "text" to text, "mood" to mood, "lucid" to lucid, "tags" to "flying,sea",
            "analysis" to null, "analysisModel" to null,
        ),
    )

    private fun pattern(date: String, updatedAt: Long, id: String = "dreampattern:$date") =
        SyncRow("dream_pattern", id, updatedAt, false, obj("localDate" to date, "text" to "themes", "model" to "m"))

    private fun goal(id: String, updatedAt: Long, status: String = "ACTIVE", title: String = "Run 10k", deadline: String? = null) =
        SyncRow("goal", id, updatedAt, false, obj("title" to title, "why" to null, "deadline" to deadline, "status" to status))

    private fun task(id: String, updatedAt: Long, goalId: Any? = "absent") = SyncRow(
        "task", id, updatedAt, false,
        JsonObject(
            buildMap {
                put("title", JsonPrimitive("do it")); put("dueDate", JsonNull); put("done", JsonPrimitive(false)); put("doneAt", JsonNull)
                when (goalId) {
                    "absent" -> Unit
                    null -> put("goalId", JsonNull)
                    is String -> put("goalId", JsonPrimitive(goalId))
                    is Int -> put("goalId", JsonPrimitive(goalId))
                    else -> error("bad test input")
                }
            },
        ),
    )

    @Test
    fun each_new_table_round_trips_push_then_pull() = testApplication {
        setup()
        val rows = listOf(
            folder("docfolder:plans", 10),
            doc("d1", 11, folderId = "docfolder:plans"),
            job("j1", 12, status = "DONE", attempts = 1, resultJson = """{"docId":"d1"}""", startedAt = 7),
            dream("dr1", 13),
            pattern("2026-11-05", 14),
            goal("g1", 15, deadline = "2026-12-31"),
        )
        val res = pushOk(rows)
        assertEquals(rows.map { it.id }, res.accepted)
        assertTrue(res.rejected.isEmpty())
        val pulled = pull(0).rows
        assertEquals(rows.sortedBy { it.id }, pulled.sortedBy { it.id })
    }

    @Test
    fun a_tombstone_is_stored_and_pulled_for_a_new_table() = testApplication {
        setup()
        pushOk(listOf(doc("d1", 10)))
        pushOk(listOf(doc("d1", 20, deleted = true)))
        val row = pull(0).rows.single()
        assertTrue(row.deleted)
        assertEquals(20, row.updatedAt)
    }

    @Test
    fun an_older_row_never_overwrites_and_an_equal_one_is_accepted_without_a_new_seq() = testApplication {
        setup()
        pushOk(listOf(goal("g1", 100, title = "first")))
        val cursor = pull(0).cursor
        assertEquals(listOf("g1"), pushOk(listOf(goal("g1", 50, title = "older"))).accepted)
        assertEquals(listOf("g1"), pushOk(listOf(goal("g1", 100, title = "equal"))).accepted)
        val p = pull(0)
        assertEquals(cursor, p.cursor)
        assertEquals("first", (p.rows.single().data["title"] as JsonPrimitive).content)
        pushOk(listOf(goal("g1", 200, title = "newer")))
        assertEquals("newer", (pull(0).rows.single().data["title"] as JsonPrimitive).content)
    }

    @Test
    fun invalid_rows_of_the_new_tables_are_rejected_with_the_field_name() = testApplication {
        setup()
        val bad = listOf(
            folder("f1", 1, name = ""),
            folder("f2", 1, name = "x".repeat(101)),
            doc("d1", 1, title = ""),
            doc("d2", 1, title = "t".repeat(201)),
            doc("d3", 1, body = "b".repeat(200_001)),
            doc("d4", 1, source = "ROBOT"),
            job("j1", 1, kind = "goal_steps"),
            job("j2", 1, kind = "A".repeat(41)),
            job("j3", 1, status = "PAUSED"),
            job("j4", 1, attempts = 4),
            dream("r1", 1, mood = 6),
            dream("r2", 1, lucid = "yes"),
            dream("r3", 1, text = ""),
            dream("r4", 1, text = "t".repeat(20_001)),
            pattern("2026-11-05", 1, id = "dreampattern:2026-11-06"),
            goal("g1", 1, status = "PAUSED"),
            goal("g2", 1, title = ""),
            goal("g3", 1, deadline = "tomorrow"),
        )
        val res = pushOk(bad)
        assertTrue(res.accepted.isEmpty())
        assertEquals(
            listOf(
                "invalid: name", "invalid: name", "invalid: title", "invalid: title", "invalid: body", "invalid: source",
                "invalid: kind", "invalid: kind", "invalid: status", "invalid: attempts",
                "invalid: mood", "invalid: lucid", "invalid: text", "invalid: text", "invalid: id",
                "invalid: status", "invalid: title", "invalid: deadline",
            ),
            res.rejected.map { it.reason },
        )
        assertTrue(pull(0).rows.isEmpty())
    }

    @Test
    fun a_new_table_row_must_have_every_field_and_task_is_the_only_row_that_may_skip_goal_id() = testApplication {
        setup()
        val noBody = SyncRow("doc", "d1", 1, false, obj("folderId" to null, "title" to "t", "source" to "USER"))
        val res = pushOk(listOf(noBody, task("t-old-app", 1)))
        assertEquals(listOf("t-old-app"), res.accepted)
        assertEquals(listOf("invalid: body"), res.rejected.map { it.reason })
    }

    @Test
    fun paging_walks_through_more_than_a_thousand_new_table_rows() = testApplication {
        setup()
        val rows = (1..1200).map { doc("d%04d".format(it), it.toLong()) }
        for (chunk in rows.chunked(500)) assertEquals(chunk.size, pushOk(chunk).accepted.size)
        val first = pull(0)
        assertEquals(PULL_PAGE_SIZE, first.rows.size)
        val second = pull(first.cursor)
        assertEquals(200, second.rows.size)
        assertEquals(1200, (first.rows + second.rows).map { it.id }.toSet().size)
    }

    @Test
    fun goal_id_set_clear_and_absent_follow_the_legacy_rule() = testApplication {
        setup()
        // a brand new task from an old app has no goalId key: stored as null
        pushOk(listOf(task("t0", 1)))
        assertTrue(pull(0).rows.single { it.id == "t0" }.data["goalId"] is JsonNull)

        pushOk(listOf(task("t1", 10, goalId = "g1")))
        assertEquals("g1", (pull(0).rows.single { it.id == "t1" }.data["goalId"] as JsonPrimitive).content)

        // an old app edits the task: no goalId key -> the stored link stays
        pushOk(listOf(task("t1", 20)))
        val kept = pull(0).rows.single { it.id == "t1" }
        assertEquals(20, kept.updatedAt)
        assertEquals("g1", (kept.data["goalId"] as JsonPrimitive).content)

        // explicit null clears
        pushOk(listOf(task("t1", 30, goalId = null)))
        assertTrue(pull(0).rows.single { it.id == "t1" }.data["goalId"] is JsonNull)

        // a string sets it again
        pushOk(listOf(task("t1", 40, goalId = "g2")))
        assertEquals("g2", (pull(0).rows.single { it.id == "t1" }.data["goalId"] as JsonPrimitive).content)
    }

    @Test
    fun goal_id_of_the_wrong_type_or_length_is_rejected() = testApplication {
        setup()
        val res = pushOk(listOf(task("a", 1, goalId = 5), task("b", 1, goalId = ""), task("c", 1, goalId = "x".repeat(101))))
        assertTrue(res.accepted.isEmpty())
        assertEquals(listOf("invalid: goalId", "invalid: goalId", "invalid: goalId"), res.rejected.map { it.reason })
    }
}
