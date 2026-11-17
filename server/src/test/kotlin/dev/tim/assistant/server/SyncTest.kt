package dev.tim.assistant.server

import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushResponse
import dev.tim.assistant.model.SyncRow
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class SyncTest {
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

    private fun ApplicationTestBuilder.setup(beforeWrite: () -> Unit = {}): ServerDb {
        val db = openDb(tempDbPath())
        serverWith(SyncStore(db, beforeWrite = beforeWrite), testConfig("unused"))
        return db
    }

    @Test
    fun samePushedTwiceKeepsOneRowAndSeq() = testApplication {
        setup()
        val row = spendingRow("s1", 100)
        assertEquals(listOf("s1"), pushOk(listOf(row)).accepted)
        val first = pull(0)
        assertEquals(listOf("s1"), pushOk(listOf(row)).accepted)
        val second = pull(0)
        assertEquals(1, second.rows.size)
        assertEquals(first.cursor, second.cursor)
    }

    @Test
    fun olderDoesNotOverwriteNewerDoes() = testApplication {
        setup()
        pushOk(listOf(spendingRow("s1", 100, amount = 500)))
        val c1 = pull(0).cursor
        pushOk(listOf(spendingRow("s1", 50, amount = 999)))
        val afterOlder = pull(0)
        assertEquals(c1, afterOlder.cursor)
        assertEquals("500", afterOlder.rows.single().data["amountAgorot"].toString())
        pushOk(listOf(spendingRow("s1", 200, amount = 777)))
        val afterNewer = pull(0)
        assertTrue(afterNewer.cursor > c1)
        assertEquals("777", afterNewer.rows.single().data["amountAgorot"].toString())
    }

    @Test
    fun equalUpdatedAtIsAcceptedWithoutNewSeq() = testApplication {
        setup()
        pushOk(listOf(spendingRow("s1", 100, amount = 500)))
        val c1 = pull(0).cursor
        assertEquals(listOf("s1"), pushOk(listOf(spendingRow("s1", 100, amount = 600))).accepted)
        val p = pull(0)
        assertEquals(c1, p.cursor)
        assertEquals("500", p.rows.single().data["amountAgorot"].toString())
    }

    @Test
    fun tombstoneIsStoredAndPulled() = testApplication {
        setup()
        pushOk(listOf(spendingRow("s1", 100)))
        pushOk(listOf(spendingRow("s1", 200, deleted = true)))
        val row = pull(0).rows.single()
        assertTrue(row.deleted)
        assertEquals(200, row.updatedAt)
    }

    @Test
    fun readOnlyAndUnknownTablesAreRejected() = testApplication {
        setup()
        val cal = SyncRow("cal_event", "c1", 1, false, obj("title" to "x"))
        val kv = SyncRow("kv", "seqCounter", 1, false, obj("value" to "0"))
        val mail = SyncRow("mail_item", "m1", 1, false, obj())
        val brief = SyncRow("briefing", "b1", 1, false, obj())
        val res = pushOk(listOf(cal, kv, mail, brief))
        assertTrue(res.accepted.isEmpty())
        assertEquals(
            listOf("read-only table", "invalid: table", "read-only table", "read-only table"),
            res.rejected.map { it.reason },
        )
        assertTrue(pull(0).rows.isEmpty())
    }

    @Test
    fun invalidValuesAreRejected() = testApplication {
        setup()
        val bad = listOf(
            spendingRow("a", 1, amount = 0),
            spendingRow("b", 1, category = "Nope"),
            journalRow("c", 1, text = ""),
            journalRow("d", 1, text = "x".repeat(10_001)),
            checkinRow("2026-11-05", 1, mood = 6),
            SyncRow("task", "t", 1, false, obj("title" to "", "dueDate" to null, "done" to false, "doneAt" to null)),
            SyncRow("checkin", "wrong-id", 1, false, checkinRow("2026-11-05", 1).data),
        )
        val res = pushOk(bad)
        assertTrue(res.accepted.isEmpty())
        assertEquals(
            listOf(
                "invalid: amountAgorot", "invalid: category", "invalid: text", "invalid: text",
                "invalid: mood", "invalid: title", "invalid: id",
            ),
            res.rejected.map { it.reason },
        )
    }

    @Test
    fun malformedRowShapesAreRejectedNotCrashed() = testApplication {
        setup()
        val rows = listOf(
            SyncRow("spending", "a", 1, false, obj("amountAgorot" to "12", "category" to "Food", "note" to null, "localDate" to "2026-11-05")),
            SyncRow("spending", "b", 1, false, obj("category" to "Food", "note" to null, "localDate" to "2026-11-05")),
            SyncRow("spending", "c", 1, false, obj("amountAgorot" to 5, "category" to "Food", "note" to null, "localDate" to "05/11/2026")),
            SyncRow("task", "d", 1, false, obj("title" to "t", "dueDate" to null, "done" to "yes", "doneAt" to null)),
            SyncRow("spending", "e", 1, false, obj("amountAgorot" to 1.5, "category" to "Food", "note" to null, "localDate" to "2026-11-05")),
        )
        val res = pushOk(rows)
        assertTrue(res.accepted.isEmpty())
        assertEquals(
            listOf("invalid: amountAgorot", "invalid: amountAgorot", "invalid: localDate", "invalid: done", "invalid: amountAgorot"),
            res.rejected.map { it.reason },
        )
        assertTrue(pull(0).rows.isEmpty())
    }

    @Test
    fun healthAndWorkoutRules() = testApplication {
        setup()
        val ok = SyncRow("health_day", "health:2026-11-05", 1, false,
            obj("localDate" to "2026-11-05", "sleepMinutes" to 400, "steps" to null, "restingHr" to 60))
        val negative = SyncRow("health_day", "health:2026-11-06", 1, false,
            obj("localDate" to "2026-11-06", "sleepMinutes" to -1, "steps" to null, "restingHr" to null))
        val wrongId = SyncRow("workout", "workout:zzz", 1, false,
            obj("hcRecordId" to "abc", "type" to "run", "startAt" to 10, "endAt" to 20, "distanceMeters" to null))
        val backwards = SyncRow("workout", "workout:abc", 1, false,
            obj("hcRecordId" to "abc", "type" to "run", "startAt" to 30, "endAt" to 20, "distanceMeters" to null))
        val res = pushOk(listOf(ok, negative, wrongId, backwards))
        assertEquals(listOf("health:2026-11-05"), res.accepted)
        assertEquals(listOf("invalid: sleepMinutes", "invalid: id", "invalid: endAt"), res.rejected.map { it.reason })
    }

    @Test
    fun fiveHundredAndOneRowsIs413() = testApplication {
        val db = setup()
        val rows = (1..501).map { spendingRow("s$it", 100L + it) }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.push(rows).status)
        assertTrue(pull(0).rows.isEmpty())
        db.close()
    }

    @Test
    fun fiveHundredRowsIsAccepted() = testApplication {
        setup()
        val rows = (1..500).map { spendingRow("s$it", 100L + it) }
        assertEquals(500, pushOk(rows).accepted.size)
    }

    @Test
    fun pullCursorAndPagination() = testApplication {
        setup()
        // 2 pushes keep each under the 500 limit: 1200 rows in total, spread over two tables
        pushOk((1..500).map { spendingRow("s$it", 100L + it) })
        pushOk((501..1000).map { spendingRow("s$it", 100L + it) })
        pushOk((1..200).map { journalRow("j$it", 100L + it) })
        val page1 = pull(0)
        assertEquals(1000, page1.rows.size)
        val page2 = pull(page1.cursor)
        assertEquals(200, page2.rows.size)
        assertTrue(page2.cursor > page1.cursor)
        val empty = pull(page2.cursor)
        assertTrue(empty.rows.isEmpty())
        assertEquals(page2.cursor, empty.cursor)
        // cursor only returns newer rows
        pushOk(listOf(journalRow("late", 5000)))
        val newer = pull(page2.cursor)
        assertEquals(listOf("late"), newer.rows.map { it.id })
        assertEquals(1200 + 1, page1.rows.size + page2.rows.size + newer.rows.size)
    }

    @Test
    fun pullOrderIsGlobalAcrossTables() = testApplication {
        setup()
        pushOk(listOf(journalRow("j1", 1)))
        pushOk(listOf(spendingRow("s1", 1)))
        pushOk(listOf(journalRow("j2", 1)))
        assertEquals(listOf("j1", "s1", "j2"), pull(0).rows.map { it.id })
    }

    @Test
    fun badSinceIs400() = testApplication {
        setup()
        assertEquals(HttpStatusCode.BadRequest, client.pullRaw("").status)
        assertEquals(HttpStatusCode.BadRequest, client.pullRaw("?since=abc").status)
    }

    @Test
    fun fiftyRoundsOfConcurrentPushAndPullSkipNoRow() {
        val db = openDb(tempDbPath())
        val store = SyncStore(db)
        val seen = LinkedHashSet<String>()
        var cursor = 0L
        runBlocking(Dispatchers.Default) {
            for (i in 1..50) {
                val push = async { store.push(listOf(journalRow("j$i", 100L + i))) }
                val pullJob = async { store.pull(cursor) }
                push.await()
                val page = pullJob.await()
                page.rows.forEach { seen += it.id }
                cursor = page.cursor
            }
            while (true) {
                val page = store.pull(cursor)
                if (page.rows.isEmpty()) break
                page.rows.forEach { seen += it.id }
                cursor = page.cursor
            }
        }
        assertEquals((1..50).map { "j$it" }.toSet(), seen)
        db.close()
    }

    @Test
    fun sqliteFullReturns507AndRollsBack() = testApplication {
        var armed = false
        val db = setup { if (armed) throw SQLException("[SQLITE_FULL] The database is full", null, 13) }
        pushOk(listOf(spendingRow("keep", 100)))
        val before = pull(0)
        armed = true
        val r = client.push(listOf(spendingRow("x1", 100), spendingRow("x2", 100)))
        assertEquals(HttpStatusCode.InsufficientStorage, r.status)
        armed = false
        val after = pull(0)
        assertEquals(before.rows.map { it.id }, after.rows.map { it.id })
        assertEquals(before.cursor, after.cursor)
        // the counter was rolled back too: the next real write continues right after the cursor
        pushOk(listOf(spendingRow("next", 100)))
        assertEquals(before.cursor + 1, pull(0).cursor)
        db.close()
    }

    @Test
    fun otherDatabaseFailuresAreNotRowRejections() = testApplication {
        setup { throw SQLException("disk I/O error") }
        val r = client.push(listOf(spendingRow("x", 1)))
        assertEquals(HttpStatusCode.InternalServerError, r.status)
    }

    @Test
    fun validMealIsAcceptedAndPulled() = testApplication {
        setup()
        val res = pushOk(listOf(mealRow("m1", 100, text = "שניצל 🍗", kcal = 650), mealRow("m2", 100, type = "SNACK")))
        assertEquals(listOf("m1", "m2"), res.accepted)
        assertTrue(res.rejected.isEmpty())
        val rows = pull(0).rows
        assertEquals(setOf("m1", "m2"), rows.map { it.id }.toSet())
        val m1 = rows.first { it.id == "m1" }
        assertEquals("meal", m1.table)
        assertEquals("650", m1.data["kcal"].toString())
        assertEquals("\"שניצל 🍗\"", m1.data["text"].toString())
        assertEquals("null", rows.first { it.id == "m2" }.data["kcal"].toString())
    }

    @Test
    fun invalidMealsAreRejectedWithTheFieldName() = testApplication {
        setup()
        val res = pushOk(
            listOf(
                mealRow("a", 1, type = "DINNER"),
                mealRow("b", 1, text = ""),
                mealRow("c", 1, text = "x".repeat(1001)),
                mealRow("d", 1, kcal = -1),
                mealRow("e", 1, text = "x".repeat(1000)),
            ),
        )
        assertEquals(listOf("e"), res.accepted)
        assertEquals(
            listOf("a" to "invalid: type", "b" to "invalid: text", "c" to "invalid: text", "d" to "invalid: kcal"),
            res.rejected.map { it.id to it.reason },
        )
        assertEquals(listOf("e"), pull(0).rows.map { it.id })
    }

    @Test
    fun olderMealDoesNotOverwriteNewerAndTombstoneIsPulled() = testApplication {
        setup()
        pushOk(listOf(mealRow("m1", 100, text = "new")))
        pushOk(listOf(mealRow("m1", 50, text = "old")))
        assertEquals("\"new\"", pull(0).rows.single().data["text"].toString())
        pushOk(listOf(mealRow("m1", 200, text = "new", deleted = true)))
        val row = pull(0).rows.single()
        assertTrue(row.deleted)
        assertEquals(200, row.updatedAt)
    }
}