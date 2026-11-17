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

/** Phase 10: usage_session / app_rule (client tables) and listen (server table) through push and pull. */
class ScreentimeTablesSyncTest {
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

    private val hour = 3_600_000L

    private fun session(
        id: String, updatedAt: Long, device: String = "PHONE", app: String = "com.example.app", title: String? = null,
        startAt: Long = 1_700_000_000_000L, endAt: Long = 1_700_000_000_000L + hour, localDate: String = "2023-11-14",
        deleted: Boolean = false,
    ) = SyncRow(
        "usage_session", id, updatedAt, deleted,
        obj("device" to device, "app" to app, "title" to title, "startAt" to startAt, "endAt" to endAt, "localDate" to localDate),
    )

    private fun rule(
        updatedAt: Long, matchType: String = "EXE", pattern: String = "code.exe", label: String = "PRODUCTIVE",
        id: String = "rule:$matchType:$pattern", deleted: Boolean = false,
    ) = SyncRow("app_rule", id, updatedAt, deleted, obj("matchType" to matchType, "pattern" to pattern, "label" to label))

    private fun listen(id: String, updatedAt: Long, name: String = "Song") = SyncRow(
        "listen", id, updatedAt, false,
        obj("trackName" to name, "artist" to "Band", "durationMs" to 200_000, "playedAt" to 1_700_000_000_000L, "localDate" to "2023-11-14"),
    )

    @Test
    fun sessionAndRuleRoundTripAndTombstones() = testApplication {
        setup()
        val rows = listOf(
            session("use:phone:com.example.app:1700000000000", 100, title = "Inbox"),
            session("use:laptop:1700000000000", 100, device = "LAPTOP", app = "code.exe"),
            rule(100),
        )
        assertEquals(rows.map { it.id }, pushOk(rows).accepted)
        val pulled = pull(0).rows.associateBy { it.id }
        assertEquals(3, pulled.size)
        assertEquals(JsonPrimitive("Inbox"), pulled.getValue("use:phone:com.example.app:1700000000000").data["title"])
        assertEquals(JsonPrimitive("PRODUCTIVE"), pulled.getValue("rule:EXE:code.exe").data["label"])
        pushOk(rows.map { it.copy(updatedAt = 200, deleted = true) })
        val after = pull(0).rows
        assertEquals(3, after.size)
        assertTrue(after.all { it.deleted && it.updatedAt == 200L })
    }

    @Test
    fun olderAndEqualPushesDoNotOverwrite() = testApplication {
        setup()
        pushOk(listOf(rule(100, label = "PRODUCTIVE")))
        val seq = pull(0).cursor
        pushOk(listOf(rule(100, label = "NEUTRAL"), rule(50, label = "UNPRODUCTIVE")))
        val pulled = pull(0)
        assertEquals(JsonPrimitive("PRODUCTIVE"), pulled.rows.single().data["label"])
        assertEquals(seq, pulled.cursor)
        pushOk(listOf(rule(101, label = "NEUTRAL")))
        assertEquals(JsonPrimitive("NEUTRAL"), pull(0).rows.single().data["label"])
    }

    @Test
    fun usageSessionValidationRejectsBadValues() = testApplication {
        setup()
        val p = "use:phone:a:1"
        val bad = listOf(
            session(p, 1, device = "TABLET") to "invalid: device",
            session(p + "a", 1, app = "") to "invalid: app",
            session(p + "b", 1, app = "x".repeat(301)) to "invalid: app",
            session(p + "c", 1, title = "x".repeat(201)) to "invalid: title",
            session(p + "d", 1, startAt = 2000, endAt = 1999) to "invalid: endAt",
            session(p + "e", 1, startAt = 0, endAt = 2 * 24 * hour + 1) to "invalid: endAt",
            session(p + "f", 1, localDate = "2026-02-30") to "invalid: localDate",
            session(p + "g", 1, localDate = "x") to "invalid: localDate",
            session("use:laptop:1", 1, device = "PHONE") to "invalid: id",
            session("use:phone:1", 1, device = "LAPTOP") to "invalid: id",
            session("other", 1) to "invalid: id",
        )
        val response = pushOk(bad.map { it.first })
        assertTrue(response.accepted.isEmpty())
        assertEquals(bad.map { it.first.id to it.second }, response.rejected.map { it.id to it.reason })
        val ok = pushOk(
            listOf(
                session("use:phone:ok1", 1, app = "x".repeat(300), title = "y".repeat(200)),
                session("use:laptop:ok2", 1, device = "LAPTOP", startAt = 5, endAt = 5),
                session("use:phone:ok3", 1, startAt = 0, endAt = 2 * 24 * hour),
            ),
        )
        assertEquals(listOf("use:phone:ok1", "use:laptop:ok2", "use:phone:ok3"), ok.accepted)
    }

    @Test
    fun appRuleValidationRejectsBadValues() = testApplication {
        setup()
        val bad = listOf(
            rule(1, matchType = "REGEX", pattern = "a", id = "rule:REGEX:a") to "invalid: matchType",
            rule(1, pattern = "") to "invalid: pattern",
            rule(1, pattern = "a".repeat(201)) to "invalid: pattern",
            rule(1, pattern = "Code.exe") to "invalid: pattern",
            rule(1, pattern = " code.exe") to "invalid: pattern",
            rule(1, label = "UNSORTED") to "invalid: label",
            rule(1, id = "rule:EXE:other.exe") to "invalid: id",
            rule(1, matchType = "PACKAGE", id = "rule:EXE:code.exe") to "invalid: id",
        )
        val response = pushOk(bad.map { it.first })
        assertTrue(response.accepted.isEmpty())
        assertEquals(bad.map { it.second }, response.rejected.map { it.reason })
        val ok = pushOk(
            listOf(
                rule(1, matchType = "TITLE_CONTAINS", pattern = "a".repeat(200), label = "NEUTRAL"),
                rule(1, matchType = "PACKAGE", pattern = "com.example.app", label = "UNPRODUCTIVE"),
            ),
        )
        assertEquals(2, ok.accepted.size)
    }

    @Test
    fun forgedListenPushIsRefused() = testApplication {
        setup()
        val response = pushOk(listOf(listen("listen:1700000000000:t1", 1)))
        assertTrue(response.accepted.isEmpty())
        assertEquals(REJECT_READ_ONLY, response.rejected.single().reason)
        assertTrue(pull(0).rows.isEmpty())
    }

    @Test
    fun listenIsWrittenIfNewerAndPulled() = testApplication {
        val db = setup()
        val store = SyncStore(db)
        suspend fun write(row: SyncRow) = db.writeLock.withLock { db.db.transactionWithResult { store.writeIfNewer(row) } }
        runBlocking {
            assertTrue(write(listen("listen:1:t1", 100)))
            assertEquals(false, write(listen("listen:1:t1", 100, name = "Changed")))
            assertEquals(false, write(listen("listen:1:t1", 50)))
            assertTrue(write(listen("listen:1:t1", 101, name = "Renamed")))
        }
        val pulled = pull(0)
        assertEquals(1, pulled.rows.size)
        assertEquals(101L, pulled.rows.single().updatedAt)
        assertEquals(JsonPrimitive("Renamed"), pulled.rows.single().data["trackName"])
    }
}
