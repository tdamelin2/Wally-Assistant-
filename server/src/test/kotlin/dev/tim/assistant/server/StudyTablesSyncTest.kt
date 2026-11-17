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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/** Phase 9: card / quiz / exam (client tables) and course / study_file (server tables) through push and pull. */
class StudyTablesSyncTest {
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

    private fun card(
        id: String, updatedAt: Long, fileId: String = "sfile:upload:1", front: String = "Q", back: String = "A",
        lastResult: String? = null, lastReviewedAt: Long? = null, deleted: Boolean = false,
    ) = SyncRow(
        "card", id, updatedAt, deleted,
        obj("fileId" to fileId, "front" to front, "back" to back, "lastResult" to lastResult, "lastReviewedAt" to lastReviewedAt),
    )

    private fun quiz(
        id: String, updatedAt: Long, courseId: String = "course:1", questionsJson: String = "[]",
        score: Long? = null, takenAt: Long? = null, deleted: Boolean = false,
    ) = SyncRow("quiz", id, updatedAt, deleted, obj("courseId" to courseId, "questionsJson" to questionsJson, "score" to score, "takenAt" to takenAt))

    private fun exam(
        id: String, updatedAt: Long, courseId: String? = null, title: String = "Final", localDate: String = "2026-12-24",
        time: String? = null, deleted: Boolean = false,
    ) = SyncRow("exam", id, updatedAt, deleted, obj("courseId" to courseId, "title" to title, "localDate" to localDate, "time" to time))

    private fun course(id: String, updatedAt: Long) =
        SyncRow("course", id, updatedAt, false, obj("moodleId" to null, "name" to "Forged", "enabled" to true))

    private fun studyFile(id: String, updatedAt: Long) = SyncRow(
        "study_file", id, updatedAt, false,
        obj(
            "courseId" to "course:1", "name" to "a.pdf", "origin" to "UPLOAD", "sizeBytes" to 10, "textStatus" to "READY",
            "studySetDocId" to null,
        ),
    )

    @Test
    fun cardQuizAndExamRoundTripAndTombstones() = testApplication {
        setup()
        val rows = listOf(
            card("card:f:0", 100, lastResult = "KNEW", lastReviewedAt = 55),
            quiz("quiz:j1", 100, score = 7, takenAt = 99),
            exam("exam:e1", 100, courseId = "course:1", time = "09:30"),
        )
        assertEquals(listOf("card:f:0", "quiz:j1", "exam:e1"), pushOk(rows).accepted)
        val pulled = pull(0).rows.associateBy { it.id }
        assertEquals(3, pulled.size)
        assertEquals(JsonPrimitive("KNEW"), pulled.getValue("card:f:0").data["lastResult"])
        assertEquals("7", pulled.getValue("quiz:j1").data["score"].toString())
        assertEquals(JsonPrimitive("09:30"), pulled.getValue("exam:e1").data["time"])
        pushOk(rows.map { it.copy(updatedAt = 200, deleted = true) })
        val after = pull(0).rows
        assertEquals(3, after.size)
        assertTrue(after.all { it.deleted && it.updatedAt == 200L })
    }

    @Test
    fun clientTableValidationRejectsBadValues() = testApplication {
        setup()
        val bad = listOf(
            card("c1", 1, fileId = "") to "invalid: fileId",
            card("c2", 1, front = "") to "invalid: front",
            card("c3", 1, back = "x".repeat(2001)) to "invalid: back",
            card("c4", 1, lastResult = "MAYBE") to "invalid: lastResult",
            quiz("q1", 1, courseId = "") to "invalid: courseId",
            quiz("q2", 1, questionsJson = "x".repeat(50_001)) to "invalid: questionsJson",
            quiz("q3", 1, score = 11) to "invalid: score",
            quiz("q4", 1, score = -1) to "invalid: score",
            exam("e1", 1, title = "") to "invalid: title",
            exam("e2", 1, localDate = "2026-02-30") to "invalid: localDate",
            exam("e3", 1, time = "24:00") to "invalid: time",
            exam("e4", 1, time = "9:30") to "invalid: time",
            exam("e5", 1, courseId = "") to "invalid: courseId",
        )
        val response = pushOk(bad.map { it.first })
        assertTrue(response.accepted.isEmpty())
        assertEquals(bad.map { it.first.id to it.second }, response.rejected.map { it.id to it.reason })
        val ok = pushOk(
            listOf(
                card("c9", 1, front = "x".repeat(2000), back = "y".repeat(2000), lastResult = "DIDNT"),
                quiz("q9", 1, score = 0, questionsJson = "x".repeat(50_000)),
                exam("e9", 1, courseId = null, time = "23:59"),
            ),
        )
        assertEquals(listOf("c9", "q9", "e9"), ok.accepted)
    }

    @Test
    fun forgedCourseAndStudyFilePushesAreRefused() = testApplication {
        setup()
        val response = pushOk(
            listOf(
                course("course:5", 1), course("course:manual:abc", 1),
                studyFile("sfile:upload:x", 1), studyFile("sfile:abc", 1),
            ),
        )
        assertTrue(response.accepted.isEmpty())
        assertEquals(4, response.rejected.size)
        assertTrue(response.rejected.all { it.reason == REJECT_READ_ONLY })
        assertTrue(pull(0).rows.isEmpty())
    }

    @Test
    fun serverWrittenCourseAndFileAreWrittenIfNewerAndPulled() = testApplication {
        val db = setup()
        val store = SyncStore(db)
        suspend fun write(row: SyncRow) = db.writeLock.withLock { db.db.transactionWithResult { store.writeIfNewer(row) } }
        runBlocking {
            assertTrue(write(course("course:7", 100)))
            assertTrue(write(studyFile("sfile:abc", 100)))
            assertEquals(false, write(course("course:7", 100)))
            assertEquals(false, write(course("course:7", 50)))
            assertTrue(write(course("course:7", 101)))
        }
        val pulled = pull(0)
        assertEquals(setOf("course:7", "sfile:abc"), pulled.rows.map { it.id }.toSet())
        assertEquals(101L, pulled.rows.first { it.id == "course:7" }.updatedAt)
        assertEquals(JsonPrimitive(true), pulled.rows.first { it.id == "course:7" }.data["enabled"])
    }
}
