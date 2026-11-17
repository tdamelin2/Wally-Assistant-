import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.charts.checkinSeries
import dev.tim.assistant.charts.monthlyTotals
import dev.tim.assistant.charts.spendingByCategory
import dev.tim.assistant.charts.tasksDonePerWeek
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.SPENDING_CATEGORIES
import dev.tim.assistant.data.prepareSchema
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.model.upsertFromSyncRow
import dev.tim.assistant.server.validateRow
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** Phase 3 acceptance tests for AC-1..AC-4 (+ file-level checks for AC-5/AC-6), written from the Plan contract. */
class Phase3AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun tmpDb(): File = Files.createTempFile("p3acc", ".db").toFile().also { it.delete(); it.deleteOnExit() }

    private fun driverFor(f: File): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${f.absolutePath}")

    private fun rows(d: SqlDriver, sql: String): List<List<String?>> = d.executeQuery(
        null, sql,
        { c ->
            val out = mutableListOf<List<String?>>()
            val n = generateSequence(0) { it + 1 }.takeWhile { i -> runCatching { c.getString(i) }.isSuccess && i < 20 }.count()
            while (c.next().value) out += (0 until n).map { c.getString(it) }
            QueryResult.Value(out)
        },
        0,
    ).value

    private fun userVersion(d: SqlDriver) = rows(d, "PRAGMA user_version")[0][0]!!.toLong()
    private fun tables(d: SqlDriver) = rows(d, "SELECT name FROM sqlite_master WHERE type='table'").map { it[0]!! }.toSet()
    private fun tableInfo(d: SqlDriver) = rows(d, "PRAGMA table_info(meal)")

    private val allTables = setOf("checkin", "spending", "journal", "task", "health_day", "workout", "cal_event", "mail_item", "briefing", "meal", "kv")

    /** The Phase 2 era tables (no meal), frozen literally so later schema changes cannot leak into the old-file fixture. */
    private val FROZEN_P2_DDL = listOf(
        "CREATE TABLE briefing (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, text TEXT NOT NULL, model TEXT NOT NULL)",
        "CREATE TABLE cal_event (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, calendarId TEXT NOT NULL, eventId TEXT NOT NULL, title TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, allDay INTEGER NOT NULL, location TEXT)",
        "CREATE TABLE checkin (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, mood INTEGER NOT NULL, energy INTEGER NOT NULL, sleepQuality INTEGER NOT NULL, stress INTEGER NOT NULL, note TEXT)",
        "CREATE TABLE health_day (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, sleepMinutes INTEGER, steps INTEGER, restingHr INTEGER)",
        "CREATE TABLE journal (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE TABLE kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE mail_item (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, gmailId TEXT NOT NULL, sender TEXT NOT NULL, subject TEXT NOT NULL, snippet TEXT NOT NULL, receivedAt INTEGER NOT NULL)",
        "CREATE TABLE spending (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, amountAgorot INTEGER NOT NULL, category TEXT NOT NULL, note TEXT, localDate TEXT NOT NULL)",
        "CREATE TABLE task (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, title TEXT NOT NULL, dueDate TEXT, done INTEGER NOT NULL, doneAt INTEGER)",
        "CREATE TABLE workout (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, hcRecordId TEXT NOT NULL, type TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, distanceMeters INTEGER)",
    )

    // ---------------------------------------------------------------- AC-1

    @Test
    fun test_AC1_new_file_gets_every_table_and_current_version() {
        val f = tmpDb()
        val d = driverFor(f)
        prepareSchema(d)
        assertEquals(8L, AssistantDb.Schema.version)
        assertTrue(tables(d).containsAll(allTables), "tables: ${tables(d)}")
        assertEquals(AssistantDb.Schema.version, userVersion(d))
        d.close()
    }

    /** Builds a Phase 2 era file: all tables except meal, user_version 0, one check-in and one spending row. */
    private fun oldFile(): File {
        val f = tmpDb()
        val d = driverFor(f)
        FROZEN_P2_DDL.forEach { d.execute(null, it, 0) }
        d.execute(null, "INSERT INTO checkin VALUES ('checkin:2026-11-01', 5, 0, 0, 'SYNCED', '2026-11-01', 4, 3, 2, 1, 'n')", 0)
        d.execute(null, "INSERT INTO spending VALUES ('s1', 6, 0, 0, 'SYNCED', 1250, 'Food', NULL, '2026-11-01')", 0)
        assertFalse("meal" in tables(d))
        assertEquals(0L, userVersion(d))
        d.close()
        return f
    }

    @Test
    fun test_AC1_old_file_gets_meal_table_and_keeps_rows() {
        val f = oldFile()
        val d = driverFor(f)
        prepareSchema(d)
        assertTrue("meal" in tables(d))
        assertEquals(AssistantDb.Schema.version, userVersion(d))
        assertEquals("1", rows(d, "SELECT count(*) FROM checkin")[0][0])
        assertEquals("1", rows(d, "SELECT count(*) FROM spending")[0][0])
        assertEquals("1250", rows(d, "SELECT amountAgorot FROM spending")[0][0])
        d.close()
    }

    @Test
    fun test_AC1_prepareSchema_twice_changes_nothing() {
        val f = oldFile()
        val d = driverFor(f)
        prepareSchema(d)
        val before = rows(d, "SELECT type, name, sql FROM sqlite_master ORDER BY name")
        val v = userVersion(d)
        prepareSchema(d)
        assertEquals(before, rows(d, "SELECT type, name, sql FROM sqlite_master ORDER BY name"))
        assertEquals(v, userVersion(d))
        assertEquals("1", rows(d, "SELECT count(*) FROM checkin")[0][0])
        d.close()
    }

    @Test
    fun test_AC1_migrated_meal_table_info_equals_new_file() {
        val migrated = driverFor(oldFile()).also { prepareSchema(it) }
        val fresh = driverFor(tmpDb()).also { prepareSchema(it) }
        val a = tableInfo(migrated)
        assertEquals(tableInfo(fresh), a)
        assertEquals(
            listOf("id", "updatedAt", "deleted", "serverSeq", "syncState", "type", "text", "eatenAt", "localDate",
                "kcal", "proteinG", "carbsG", "fatG", "estimateModel"),
            a.map { it[1] },
        )
        migrated.close(); fresh.close()
    }

    @Test
    fun test_AC1_future_user_version_is_refused_and_file_untouched() {
        val f = tmpDb()
        val d = driverFor(f)
        prepareSchema(d)
        d.execute(null, "PRAGMA user_version = 99", 0)
        val before = rows(d, "SELECT name, sql FROM sqlite_master ORDER BY name")
        assertFailsWith<Exception> { prepareSchema(d) }
        assertEquals(99L, userVersion(d))
        assertEquals(before, rows(d, "SELECT name, sql FROM sqlite_master ORDER BY name"))
        d.close()
    }

    /**
     * Env-gated: P3_MIGRATION_COPY points to a COPY of a real assistant.db. Without it this passes immediately.
     * Never point it at a live file.
     */
    @Test
    fun test_P3_migration_on_copy_of_real_db_env_gated() {
        val path = System.getenv("P3_MIGRATION_COPY")?.takeIf { it.isNotBlank() } ?: return
        val f = File(path)
        assertTrue(f.exists(), "P3_MIGRATION_COPY does not exist: $path")
        assertFalse(f.absolutePath.contains("AppData\\Local\\Assistant", ignoreCase = true) && !f.absolutePath.contains("p3-copy"),
            "refusing to run on what looks like the live database")
        val d = driverFor(f)
        val pre = listOf("checkin", "spending", "journal", "task", "health_day", "workout", "cal_event", "mail_item", "briefing", "kv")
        val existing = tables(d)
        val before = pre.filter { it in existing }.associateWith { rows(d, "SELECT count(*) FROM $it")[0][0]!!.toLong() }
        prepareSchema(d)
        assertTrue("meal" in tables(d), "meal table missing after prepareSchema")
        assertEquals(AssistantDb.Schema.version, userVersion(d))
        val after = before.keys.associateWith { rows(d, "SELECT count(*) FROM $it")[0][0]!!.toLong() }
        println("P3 migration copy counts before=$before after=$after meal=${rows(d, "SELECT count(*) FROM meal")[0][0]}")
        assertEquals(before, after)
        d.close()
    }

    // ---------------------------------------------------------------- AC-2

    private fun memDb() = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })

    private fun mealData(type: String = "MEAL", text: String = "pasta", kcal: Long? = null) = JsonObject(
        mapOf(
            "type" to JsonPrimitive(type),
            "text" to JsonPrimitive(text),
            "eatenAt" to JsonPrimitive(1_700_000_000_000L),
            "localDate" to JsonPrimitive("2023-11-15"),
            "kcal" to (kcal?.let { JsonPrimitive(it) } ?: JsonNull),
            "proteinG" to (kcal?.let { JsonPrimitive(30) } ?: JsonNull),
            "carbsG" to (kcal?.let { JsonPrimitive(70) } ?: JsonNull),
            "fatG" to (kcal?.let { JsonPrimitive(20) } ?: JsonNull),
            "estimateModel" to (kcal?.let { JsonPrimitive("m") } ?: JsonNull),
        ),
    )

    @Test
    fun test_AC2_meal_row_mapping_round_trip_keeps_hebrew_and_nutrition() {
        val db = memDb()
        val text = "חביתה וסלט"
        upsertFromSyncRow(db, SyncRow("meal", "m1", 10, false, mealData(text = text, kcal = 650)), serverSeq = 7)
        val m = db.mealQueries.selectById("m1").executeAsOne()
        assertEquals(text, m.text)
        assertEquals(650L, m.kcal)
        assertEquals(7L, m.serverSeq)
        val back = toSyncRow(m)
        assertEquals("meal", back.table)
        assertEquals(mealData(text = text, kcal = 650), back.data)
        // null nutrition is JsonNull, not absent
        upsertFromSyncRow(db, SyncRow("meal", "m2", 10, false, mealData()), serverSeq = 8)
        val d2 = toSyncRow(db.mealQueries.selectById("m2").executeAsOne()).data
        assertEquals(JsonNull, d2["kcal"])
        assertEquals(JsonNull, d2["estimateModel"])
    }

    private val eatenAt = 1_700_000_000_000L // 2023-11-15 00:13 in Asia/Jerusalem

    private fun repoWith(clock: () -> Long) = memDb().let { db -> db to Repository(LocalStore(db), now = clock, today = { "2023-11-14" }) }

    private fun givenEstimate(db: AssistantDb, id: String) {
        db.mealQueries.upsert(db.mealQueries.selectById(id).executeAsOne().copy(kcal = 650, proteinG = 30, carbsG = 70, fatG = 20, estimateModel = "m"))
    }

    private fun nutrition(db: AssistantDb, id: String) =
        db.mealQueries.selectById(id).executeAsOne().let { listOf(it.kcal, it.proteinG, it.carbsG, it.fatG, it.estimateModel) }

    @Test
    fun test_AC2_editing_text_clears_nutrition_but_time_or_type_keeps_it() = runBlocking<Unit> {
        var t = 1_000_000L
        val (db, repo) = repoWith { t }
        val id = repo.saveMeal(null, "MEAL", "pasta", eatenAt)
        assertEquals(listOf<Any?>(null, null, null, null, null), nutrition(db, id))
        assertEquals("2023-11-15", db.mealQueries.selectById(id).executeAsOne().localDate)
        givenEstimate(db, id)

        t += 10
        repo.saveMeal(id, "MEAL", "pasta", eatenAt + 3_600_000) // time only
        assertEquals(listOf<Any?>(650L, 30L, 70L, 20L, "m"), nutrition(db, id))
        t += 10
        repo.saveMeal(id, "SNACK", "pasta", eatenAt + 3_600_000) // type only
        assertEquals(listOf<Any?>(650L, 30L, 70L, 20L, "m"), nutrition(db, id))
        assertEquals("SNACK", db.mealQueries.selectById(id).executeAsOne().type)
        t += 10
        repo.saveMeal(id, "SNACK", "pizza", eatenAt + 3_600_000) // text
        assertEquals(listOf<Any?>(null, null, null, null, null), nutrition(db, id))
        assertEquals(1, repo.meals().size)
        repo.deleteMeal(id)
        assertTrue(repo.meals().isEmpty())
    }

    // ---------------------------------------------------------------- AC-3 (server validation through the public function)

    private fun verdict(data: JsonObject) = validateRow(SyncRow("meal", "m1", 1, false, data))

    @Test
    fun test_AC3_server_accepts_valid_meal_and_rejects_bad_ones() {
        assertNull(verdict(mealData()))
        assertNull(verdict(mealData(type = "SNACK", kcal = 100)))
        assertEquals("invalid: type", verdict(mealData(type = "DINNER")))
        assertEquals("invalid: text", verdict(mealData(text = "")))
        assertEquals("invalid: kcal", verdict(mealData(kcal = -1)))
    }

    // ---------------------------------------------------------------- AC-4

    private fun checkin(date: String, mood: Long = 3, deleted: Long = 0) =
        Checkin("c$date", 1, deleted, 0, "SYNCED", date, mood, 2, 4, 1, null)

    private fun spend(date: String, amt: Long, cat: String = "Food", deleted: Long = 0) =
        Spending("s$date$amt", 1, deleted, 0, "SYNCED", amt, cat, null, date)

    private fun task(id: String, doneAt: Long?, done: Long = 1, deleted: Long = 0) =
        Task(id, 1, deleted, 0, "SYNCED", id, null, done, doneAt, null)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun test_AC4_missing_days_null_and_deleted_excluded() {
        val s = checkinSeries(listOf(checkin("2026-11-08", 5), checkin("2026-11-07", 1, deleted = 1)), "2026-11-08")
        assertEquals(30, s.labels.size)
        assertEquals("2026-11-08", s.labels.last())
        assertEquals("2026-10-10", s.labels.first())
        assertEquals(5, s.mood.last())
        assertNull(s.mood[28])
        assertEquals(1, s.mood.count { it != null })
        assertEquals(30, s.energy.size); assertEquals(30, s.sleepQuality.size); assertEquals(30, s.stress.size)

        val sp = spendingByCategory(listOf(spend("2026-11-02", 1000), spend("2026-11-03", 500, deleted = 1)), "2026-11-08")
        assertEquals(SPENDING_CATEGORIES, sp.map { it.first })
        assertEquals(1000L, sp.first { it.first == "Food" }.second)
    }

    @Test
    fun test_AC4_month_boundary_spending_and_tasks() {
        val spend = listOf(spend("2026-10-31", 100), spend("2026-11-01", 200))
        val m = monthlyTotals(spend, "2026-11-08")
        assertEquals(6, m.size)
        assertEquals("2026-11", m.last().first)
        assertEquals("2026-06", m.first().first)
        assertEquals(200L, m.last().second)
        assertEquals(100L, m[4].second)
        assertEquals(200L, spendingByCategory(spend, "2026-11-08").sumOf { it.second })

        // 2026-10-31 23:30 and 2026-11-01 00:10 Asia/Jerusalem (UTC+2 in winter, DST ended 2026-10-25)
        val a = task("a", ms("2026-10-31T21:30:00Z"))
        val b = task("b", ms("2026-11-01T22:10:00Z") - 24 * 3_600_000L) // 2026-11-01 00:10 local
        val w = tasksDonePerWeek(listOf(a, b), "2026-11-08")
        assertEquals(8, w.size)
        assertEquals("2026-11-08", w.last().first) // 2026-11-08 is a Sunday
        // Oct 31 (Saturday) belongs to the week starting Sunday Oct 25; Nov 1 (Sunday) starts a new week.
        assertEquals(1, w.first { it.first == "2026-10-25" }.second)
        assertEquals(1, w.first { it.first == "2026-11-01" }.second)
    }

    @Test
    fun test_AC4_old_tasks_counted_and_deleted_ignored() {
        val old = task("old", ms("2026-09-27T10:00:00Z")) // about six weeks ago
        val gone = task("gone", ms("2026-11-04T10:00:00Z"), deleted = 1)
        val w = tasksDonePerWeek(listOf(old, gone), "2026-11-08")
        assertEquals(1, w.sumOf { it.second })
    }

    @Test
    fun test_AC4_empty_input_gives_zero_and_null_series() {
        val s = checkinSeries(emptyList(), "2026-11-08")
        assertTrue(s.mood.all { it == null } && s.energy.all { it == null } && s.sleepQuality.all { it == null } && s.stress.all { it == null })
        assertEquals(30, s.labels.size)
        assertTrue(spendingByCategory(emptyList(), "2026-11-08").all { it.second == 0L })
        assertEquals(7, spendingByCategory(emptyList(), "2026-11-08").size)
        assertTrue(monthlyTotals(emptyList(), "2026-11-08").let { it.size == 6 && it.all { p -> p.second == 0L } })
        assertTrue(tasksDonePerWeek(emptyList(), "2026-11-08").let { it.size == 8 && it.all { p -> p.second == 0 } })
    }

    @Test
    fun test_AC4_year_boundary_months() {
        val m = monthlyTotals(listOf(spend("2025-12-15", 700)), "2026-02-10")
        assertEquals(listOf("2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02"), m.map { it.first })
        assertEquals(700L, m[3].second)
    }

    // ---------------------------------------------------------------- AC-5 / AC-6 file-level

    @Test
    fun test_AC5_AC6_checklists_exist() {
        val meals = File(root, "ops/checklists/meals.md")
        val p2 = File(root, "ops/checklists/phase2.md")
        assertTrue(meals.exists() && p2.exists())
        val m = meals.readText()
        listOf("nutrition pending", "Snack", "Meal").forEach { assertTrue(m.contains(it), "meals.md lacks '$it'") }
        assertTrue(Regex("(?m)^\\s*(- \\[ \\]|\\d+[.)])").findAll(m).count() >= 5, "meals.md has too few steps")
        val c = p2.readText()
        assertTrue(c.contains("Charts"))
        assertNotNull(Regex("(?i)server").find(c))
    }

    @Test
    fun test_P3_version_and_restore_tables() {
        val gradle = File(root, "desktopApp/build.gradle.kts").readText()
        assertTrue(gradle.contains("packageVersion = \"1.18.0\""))
        val sh = File(root, "ops/assistant-restore-test.sh").readText()
        assertTrue(Regex("\\bmeal\\b").containsMatchIn(sh))
        assertFalse(sh.contains('\r'))
    }
}
