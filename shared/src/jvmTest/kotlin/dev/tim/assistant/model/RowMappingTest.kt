package dev.tim.assistant.model

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Briefing
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Health_day
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.db.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RowMappingTest {
    private fun newDb(): AssistantDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(driver)
        return AssistantDb(driver)
    }

    /** row -> SyncRow -> JSON text -> SyncRow -> fresh DB -> row must equal the original (serverSeq 0, SYNCED). */
    private fun <R> roundTrip(row: R, toSync: (R) -> SyncRow, read: (AssistantDb) -> R?) {
        val wire = Json.encodeToString(toSync(row))
        val decoded = Json.decodeFromString<SyncRow>(wire)
        val db = newDb()
        upsertFromSyncRow(db, decoded)
        assertEquals(row, read(db))
    }

    private val extreme = Long.MAX_VALUE
    private val low = Long.MIN_VALUE

    private fun checkins() = listOf(
        Checkin("checkin:2026-03-27", 1000, 0, 0, "SYNCED", "2026-03-27", 3, 4, 5, 1, "שלום 👋"),
        Checkin("checkin:2026-03-28", 1001, 0, 0, "SYNCED", "2026-03-28", 1, 1, 1, 1, null),
        Checkin("checkin:2026-03-29", extreme, 1, 0, "SYNCED", "2026-03-29", 5, 5, 5, 5, "x"),
    )

    private fun spendings() = listOf(
        Spending("s1", 1000, 0, 0, "SYNCED", 12_50, "Food", "קפה ☕", "2026-03-27"),
        Spending("s2", 1000, 0, 0, "SYNCED", extreme, "Other", null, "2026-03-27"),
        Spending("s3", 1000, 1, 0, "SYNCED", 1, "Bills", null, "2026-03-27"),
    )

    private fun journals() = listOf(
        Journal("j1", 1000, 0, 0, "SYNCED", "שלום 👋 \"quoted\" \\ \n new line", 1000),
        Journal("j2", low, 1, 0, "SYNCED", "t", low),
        Journal("j3", 1, 0, 0, "SYNCED", "x".repeat(10_000), extreme),
    )

    private fun tasks() = listOf(
        Task("t1", 1000, 0, 0, "SYNCED", "לקנות חלב 🥛", "2026-04-01", 0, null, null),
        Task("t2", 1000, 0, 0, "SYNCED", "done one", null, 1, 5000, null),
        Task("t3", 1000, 1, 0, "SYNCED", "gone", null, 0, null, null),
    )

    private fun healthDays() = listOf(
        Health_day("health:2026-03-27", 1000, 0, 0, "SYNCED", "2026-03-27", 450, 12_000, 58),
        Health_day("health:2026-03-28", 1000, 0, 0, "SYNCED", "2026-03-28", null, null, null),
        Health_day("health:2026-03-29", 1000, 1, 0, "SYNCED", "2026-03-29", 0, extreme, 0),
    )

    private fun workouts() = listOf(
        Workout("workout:abc", 1000, 0, 0, "SYNCED", "abc", "RUNNING", 100, 200, 5000),
        Workout("workout:def", 1000, 0, 0, "SYNCED", "def", "ריצה", 0, extreme, null),
        Workout("workout:ghi", 1000, 1, 0, "SYNCED", "ghi", "WALK", 1, 2, 0),
    )

    private fun calEvents() = listOf(
        Cal_event("cal:c:e", 1000, 0, 0, "SYNCED", "c", "e", "פגישה 👋", 100, 200, 0, "Tel Aviv"),
        Cal_event("cal:c:f", 1000, 0, 0, "SYNCED", "c", "f", "all day", 100, 200, 1, null),
        Cal_event("cal:c:g", 1000, 1, 0, "SYNCED", "c", "g", "cancelled", low, extreme, 0, null),
    )

    private fun mailItems() = listOf(
        Mail_item("mail:1", 1000, 0, 0, "SYNCED", "1", "dana@example.com", "נושא 👋", "snippet", 100),
        Mail_item("mail:2", 1000, 0, 0, "SYNCED", "2", "", "", "", 0),
        Mail_item("mail:3", 1000, 1, 0, "SYNCED", "3", "a", "b", "c", extreme),
    )

    private fun briefings() = listOf(
        Briefing("briefing:2026-03-27", 1000, 0, 0, "SYNCED", "2026-03-27", "בוקר טוב 👋", "llama"),
        Briefing("briefing:2026-03-28", 1000, 1, 0, "SYNCED", "2026-03-28", "", ""),
    )

    private fun meals() = listOf(
        Meal("m1", 1000, 0, 0, "SYNCED", "MEAL", "שניצל ואורז 🍚", 1_700_000_000_000, "2023-11-14", 650, 30, 70, 20, "llama"),
        Meal("m2", 1000, 0, 0, "SYNCED", "SNACK", "apple", extreme, "2026-03-27", null, null, null, null, null),
        Meal("m3", 1000, 1, 0, "SYNCED", "MEAL", "x".repeat(1000), 0, "1970-01-01", 0, 0, 0, 0, ""),
    )

    @Test
    fun meal_roundTrip() = meals().forEach { roundTrip(it, ::toSyncRow) { db -> db.mealQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun meal_toSyncRow_hasAllNineKeysWithExplicitNulls() {
        val r = toSyncRow(meals()[1])
        assertEquals("meal", r.table)
        assertEquals(
            setOf("type", "text", "eatenAt", "localDate", "kcal", "proteinG", "carbsG", "fatG", "estimateModel"),
            r.data.keys,
        )
        assertEquals(JsonNull, r.data["kcal"])
        assertEquals(JsonNull, r.data["estimateModel"])
        assertEquals(JsonPrimitive("apple"), r.data["text"])
    }

    @Test
    fun unknownTable_throwsUnknownTableException_whichIsAnIllegalArgumentException() {
        val e = assertFailsWith<UnknownTableException> {
            upsertFromSyncRow(newDb(), SyncRow("nope", "id", 1, false, JsonObject(emptyMap())))
        }
        assertEquals("nope", e.table)
        val asIllegalArgument: IllegalArgumentException = e
        assertEquals("nope", asIllegalArgument.message!!.substringAfter("'").substringBefore("'"))
    }

    @Test
    fun malformedMeal_throwsPlainIllegalArgumentException() {
        val good = toSyncRow(meals()[0])
        val e = assertFailsWith<IllegalArgumentException> {
            upsertFromSyncRow(newDb(), good.copy(data = JsonObject(good.data + ("kcal" to JsonPrimitive("many")))))
        }
        assertTrue(e !is UnknownTableException && e.message!!.contains("kcal"))
    }

    @Test
    fun checkin_roundTrip() = checkins().forEach { roundTrip(it, ::toSyncRow) { db -> db.checkinQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun spending_roundTrip() = spendings().forEach { roundTrip(it, ::toSyncRow) { db -> db.spendingQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun journal_roundTrip() = journals().forEach { roundTrip(it, ::toSyncRow) { db -> db.journalQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun task_roundTrip() = tasks().forEach { roundTrip(it, ::toSyncRow) { db -> db.taskQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun healthDay_roundTrip() = healthDays().forEach { roundTrip(it, ::toSyncRow) { db -> db.health_dayQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun workout_roundTrip() = workouts().forEach { roundTrip(it, ::toSyncRow) { db -> db.workoutQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun calEvent_roundTrip() = calEvents().forEach { roundTrip(it, ::toSyncRow) { db -> db.cal_eventQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun mailItem_roundTrip() = mailItems().forEach { roundTrip(it, ::toSyncRow) { db -> db.mail_itemQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun briefing_roundTrip() = briefings().forEach { roundTrip(it, ::toSyncRow) { db -> db.briefingQueries.selectById(it.id).executeAsOneOrNull() } }

    @Test
    fun toSyncRow_usesExactColumnNamesTypesAndExplicitNulls() {
        val t = toSyncRow(Task("t1", 7, 1, 0, "SYNCED", "title", null, 1, 99, null))
        assertEquals("task", t.table)
        assertEquals(7L, t.updatedAt)
        assertTrue(t.deleted)
        assertEquals(setOf("title", "dueDate", "done", "doneAt", "goalId"), t.data.keys)
        assertEquals(JsonNull, t.data["dueDate"])
        assertEquals(JsonPrimitive(true), t.data["done"])
        assertEquals(JsonPrimitive(99L), t.data["doneAt"])

        val c = toSyncRow(Cal_event("cal:a:b", 1, 0, 0, "SYNCED", "a", "b", "t", 1, 2, 1, null))
        assertEquals(JsonPrimitive(true), c.data["allDay"])
        assertEquals(JsonNull, c.data["location"])
    }

    @Test
    fun upsertFromSyncRow_storesGivenServerSeqAndSyncState() {
        val db = newDb()
        upsertFromSyncRow(db, toSyncRow(journals()[0]), serverSeq = 42, syncState = "PENDING")
        val r = db.journalQueries.selectById("j1").executeAsOne()
        assertEquals(42L, r.serverSeq)
        assertEquals("PENDING", r.syncState)
    }

    @Test
    fun unknownTable_throwsNamingTheTable() {
        val e = assertFailsWith<IllegalArgumentException> {
            upsertFromSyncRow(newDb(), SyncRow("nope", "id", 1, false, JsonObject(emptyMap())))
        }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test
    fun kvTable_isNeverMapped() {
        assertFailsWith<IllegalArgumentException> {
            upsertFromSyncRow(newDb(), SyncRow("kv", "id", 1, false, JsonObject(emptyMap())))
        }
    }

    @Test
    fun missingField_throwsNamingTableAndField() {
        val good = toSyncRow(tasks()[0])
        val data = good.data.filterKeys { it != "title" }
        val e = assertFailsWith<IllegalArgumentException> {
            upsertFromSyncRow(newDb(), good.copy(data = JsonObject(data)))
        }
        assertTrue(e.message!!.contains("task") && e.message!!.contains("title"))
    }

    @Test
    fun mistypedField_throwsNamingTableAndField() {
        val good = toSyncRow(tasks()[0])
        val e = assertFailsWith<IllegalArgumentException> {
            upsertFromSyncRow(newDb(), good.copy(data = JsonObject(good.data + ("done" to JsonPrimitive("yes")))))
        }
        assertTrue(e.message!!.contains("task") && e.message!!.contains("done"))

        val e2 = assertFailsWith<IllegalArgumentException> {
            upsertFromSyncRow(newDb(), good.copy(data = JsonObject(good.data + ("title" to JsonNull))))
        }
        assertTrue(e2.message!!.contains("title"))

        val e3 = assertFailsWith<IllegalArgumentException> {
            upsertFromSyncRow(newDb(), good.copy(data = JsonObject(good.data + ("doneAt" to JsonPrimitive("5")))))
        }
        assertTrue(e3.message!!.contains("doneAt"))
    }

    @Test
    fun markSynced_changesStateOnlyWhenUpdatedAtMatches() {
        val db = newDb()
        val q = db.journalQueries
        q.upsert(Journal("j", 100, 0, 0, "PENDING", "a", 1))
        // the row is edited after the snapshot was pushed (updatedAt 100 -> 200)
        q.upsert(Journal("j", 200, 0, 0, "PENDING", "b", 1))
        q.markSynced("j", 100)
        assertEquals("PENDING", q.selectById("j").executeAsOne().syncState)
        q.markSynced("j", 200)
        assertEquals("SYNCED", q.selectById("j").executeAsOne().syncState)
    }

    @Test
    fun markRejected_changesStateOnlyWhenUpdatedAtMatches() {
        val db = newDb()
        val q = db.spendingQueries
        q.upsert(Spending("s", 100, 0, 0, "PENDING", 5, "Food", null, "2026-03-27"))
        q.upsert(Spending("s", 300, 0, 0, "PENDING", 6, "Food", null, "2026-03-27"))
        q.markRejected("s", 100)
        assertEquals("PENDING", q.selectById("s").executeAsOne().syncState)
        q.markRejected("s", 300)
        assertEquals("REJECTED", q.selectById("s").executeAsOne().syncState)
    }

    @Test
    fun selectPending_returnsOnlyPendingRows() {
        val db = newDb()
        val q = db.taskQueries
        q.upsert(Task("a", 1, 0, 0, "PENDING", "a", null, 0, null, null))
        q.upsert(Task("b", 1, 0, 0, "SYNCED", "b", null, 0, null, null))
        q.upsert(Task("c", 1, 0, 0, "REJECTED", "c", null, 0, null, null))
        assertEquals(listOf("a"), q.selectPending().executeAsList().map { it.id })
        assertEquals(3, q.selectAll().executeAsList().size)
    }

    @Test
    fun selectChangedSince_isOrderedByServerSeqAndExcludesOlder() {
        val db = newDb()
        val q = db.briefingQueries
        q.upsert(Briefing("c", 1, 0, 30, "SYNCED", "d3", "t", "m"))
        q.upsert(Briefing("a", 1, 0, 10, "SYNCED", "d1", "t", "m"))
        q.upsert(Briefing("b", 1, 0, 20, "SYNCED", "d2", "t", "m"))
        q.upsert(Briefing("z", 1, 0, 0, "SYNCED", "d0", "t", "m"))
        assertEquals(listOf("a", "b", "c"), q.selectChangedSince(0).executeAsList().map { it.id })
        assertEquals(listOf("b", "c"), q.selectChangedSince(10).executeAsList().map { it.id })
        assertEquals(emptyList(), q.selectChangedSince(30).executeAsList())
    }

    @Test
    fun kv_getAndPut() {
        val q = newDb().kvQueries
        assertNull(q.get("cursor").executeAsOneOrNull())
        q.put("cursor", "5")
        assertEquals("5", q.get("cursor").executeAsOne())
        q.put("cursor", "שלום 👋")
        assertEquals("שלום 👋", q.get("cursor").executeAsOne())
    }
}
