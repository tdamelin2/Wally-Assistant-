package dev.tim.assistant.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.health.GARMIN_PACKAGE
import dev.tim.assistant.health.HealthSource
import dev.tim.assistant.health.RawExercise
import dev.tim.assistant.health.RawHealth
import dev.tim.assistant.health.RawSleep
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushRequest
import dev.tim.assistant.model.PushResponse
import dev.tim.assistant.model.Rejected
import dev.tim.assistant.model.SyncRow
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SyncEngineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var clock = 1_000_000L
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) }
    private val db = AssistantDb(driver)
    private val store = LocalStore(db)
    private val repo = Repository(store, now = { clock }, today = { "2026-11-05" })

    private val pushed = mutableListOf<PushRequest>()
    private var requests = 0

    // Server behaviour, replaced per test.
    private var onPush: suspend (PushRequest) -> HttpResponseData = { scope.acceptAll(it) }
    private var onPull: suspend (Long) -> HttpResponseData = { since -> scope.pullJson(PullResponse(emptyList(), since)) }
    private var onHealth: suspend () -> HttpResponseData = { scope.healthJson() }

    private lateinit var scope: MockRequestHandleScope

    private fun engine() = MockEngine { request ->
        scope = this
        requests++
        when (request.url.encodedPath) {
            "/v1/sync/push" -> {
                val body = json.decodeFromString<PushRequest>((request.body as TextContent).text)
                pushed += body
                onPush(body)
            }
            "/v1/sync/pull" -> onPull(request.url.parameters["since"]!!.toLong())
            "/v1/health" -> onHealth()
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.jsonResponse(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.acceptAll(req: PushRequest) =
        jsonResponse(json.encodeToString(PushResponse(req.rows.map { it.id }, emptyList())))

    private fun MockRequestHandleScope.pullJson(p: PullResponse) = jsonResponse(json.encodeToString(p))

    private fun MockRequestHandleScope.healthJson(googleOk: Boolean? = true, at: Long? = 42L) =
        jsonResponse("""{"status":"ok","serverTime":1,"googleOk":${googleOk},"googleLastFetchAt":${at},"extra":1}""")

    private fun engineWithSettings(
        url: String? = "http://server:8080",
        token: String? = "tok",
        healthSource: HealthSource? = null,
    ): SyncEngine {
        if (url != null) store.putSetting(SettingKeys.SERVER_URL, url)
        if (token != null) store.putSetting(SettingKeys.DEVICE_TOKEN, token)
        return SyncEngine(store, SyncClient(engine()), now = { clock }, healthSource = healthSource)
    }

    private class FakeHealth(var next: () -> RawHealth?) : HealthSource {
        override suspend fun readLast7Days(): RawHealth? = next()
    }

    private fun rawHealth(sleepMinutes: Long, steps: Long) = RawHealth(
        // 2026-11-04 23:00 .. 07:00 on 11-05 Jerusalem (UTC+2): 21:00Z .. 05:00Z
        sleep = listOf(RawSleep("s1", 1_793_826_000_000L, 1_793_826_000_000L + sleepMinutes * 60_000, GARMIN_PACKAGE)),
        restingHr = emptyList(),
        exercise = listOf(RawExercise("w1", 1_793_860_000_000L, 1_793_862_700_000L, "Running", null, GARMIN_PACKAGE)),
        dailySteps = mapOf("2026-11-05" to steps),
    )

    private fun journalRow(id: String, updatedAt: Long, text: String = "remote", deleted: Boolean = false) =
        SyncRow("journal", id, updatedAt, deleted, buildJsonObject {
            put("text", text)
            put("createdAt", 5L)
        })

    private fun checkinRow(id: String, updatedAt: Long, mood: Int) =
        SyncRow("checkin", id, updatedAt, false, buildJsonObject {
            put("localDate", "2026-11-05")
            put("mood", mood)
            put("energy", 3)
            put("sleepQuality", 3)
            put("stress", 3)
            put("note", JsonPrimitive(null as String?))
        })

    private fun journalState(id: String) = db.journalQueries.selectById(id).executeAsOne()

    @Test
    fun offline_keeps_rows_pending_and_returns_offline_n() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onPush = { throw IOException("no route") }
        repo.saveJournal(null, "a")
        repo.saveJournal(null, "b")
        assertEquals(SyncStatus.Offline(2), engine.sync())
        assertEquals(2, repo.pendingCount())
        assertTrue(db.journalQueries.selectAll().executeAsList().all { it.syncState == PENDING })
    }

    @Test
    fun accepted_rows_become_synced_and_last_sync_is_saved() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveJournal(null, "hello")
        clock += 50
        assertEquals(SyncStatus.Synced(clock), engine.sync())
        assertEquals(SYNCED, journalState(id).syncState)
        assertEquals(clock.toString(), store.setting(SettingKeys.LAST_SYNC_AT))
        assertEquals("true", store.setting(SettingKeys.GOOGLE_OK))
        assertEquals("42", store.setting(SettingKeys.GOOGLE_LAST_FETCH_AT))
    }

    @Test
    fun row_edited_while_push_is_in_flight_stays_pending() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveJournal(null, "v1")
        onPush = { req ->
            clock += 10
            repo.saveJournal(id, "v2")
            scope.acceptAll(req)
        }
        engine.sync()
        val row = journalState(id)
        assertEquals("v2", row.text)
        assertEquals(PENDING, row.syncState)
        // the next sync pushes the edited version
        onPush = { req -> scope.acceptAll(req) }
        engine.sync()
        assertEquals(SYNCED, journalState(id).syncState)
        assertEquals("v2", pushed.last().rows.single().data["text"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun rejected_rows_become_rejected() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveJournal(null, "x")
        onPush = { scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), listOf(Rejected(id, "invalid: text"))))) }
        engine.sync()
        assertEquals(REJECTED, journalState(id).syncState)
    }

    @Test
    fun pulled_newer_row_replaces_older_synced_local() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveJournal(null, "old")
        engine.sync()
        onPull = { scope.pullJson(PullResponse(listOf(journalRow(id, clock + 1000, "new")), 7)) }
        engine.sync()
        assertEquals("new", journalState(id).text)
        assertEquals("7", store.setting(SettingKeys.CURSOR))
    }

    @Test
    fun pulled_older_row_does_not_replace_newer_pending_edit() = runBlocking<Unit> {
        val engine = engineWithSettings()
        // the server neither accepts nor rejects, so the local edit stays PENDING
        onPush = { scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), emptyList()))) }
        val id = repo.saveJournal(null, "mine")
        onPull = { scope.pullJson(PullResponse(listOf(journalRow(id, clock - 500, "theirs")), 3)) }
        engine.sync()
        val row = journalState(id)
        assertEquals("mine", row.text)
        assertEquals(PENDING, row.syncState)
    }

    @Test
    fun pulled_deleted_row_hides_it_from_lists() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveJournal(null, "gone soon")
        engine.sync()
        assertEquals(1, repo.journal().size)
        onPull = { scope.pullJson(PullResponse(listOf(journalRow(id, clock + 100, "gone soon", deleted = true)), 2)) }
        engine.sync()
        assertEquals(0, repo.journal().size)
    }

    @Test
    fun http_401_gives_wrong_token() = runBlocking<Unit> {
        val engine = engineWithSettings()
        repo.saveJournal(null, "a")
        onPush = { scope.respond("", HttpStatusCode.Unauthorized) }
        assertEquals(SyncStatus.WrongToken, engine.sync())
        assertEquals(1, repo.pendingCount())
    }

    @Test
    fun http_507_gives_offline_with_rows_pending() = runBlocking<Unit> {
        val engine = engineWithSettings()
        repo.saveJournal(null, "a")
        onPush = { scope.respond("", HttpStatusCode.InsufficientStorage) }
        assertEquals(SyncStatus.Offline(1), engine.sync())
        assertEquals(PENDING, db.journalQueries.selectAll().executeAsList().single().syncState)
    }

    @Test
    fun empty_settings_give_not_configured_and_send_nothing() = runBlocking<Unit> {
        val engine = engineWithSettings(url = null, token = null)
        repo.saveJournal(null, "a")
        assertEquals(SyncStatus.NotConfigured, engine.sync())
        assertEquals(0, requests)
    }

    @Test
    fun same_day_checkin_from_two_devices_is_one_row() = runBlocking<Unit> {
        val engine = engineWithSettings()
        repo.saveCheckin(2, 2, 2, 2, null)
        val remote = checkinRow("checkin:2026-11-05", clock + 5000, 5)
        onPull = { scope.pullJson(PullResponse(listOf(remote), 9)) }
        engine.sync()
        val rows = db.checkinQueries.selectAll().executeAsList()
        assertEquals(1, rows.size)
        assertEquals(5, rows.single().mood.toInt())
        assertEquals(1, repo.checkins().size)
        // today's check-in is found for editing
        assertNotNull(repo.checkinForToday())
    }

    @Test
    fun two_concurrent_syncs_do_not_push_the_same_row_in_parallel() = runBlocking<Unit> {
        val engine = engineWithSettings()
        repo.saveJournal(null, "once")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        onPush = { req ->
            active++
            maxActive = maxOf(maxActive, active)
            entered.complete(Unit)
            release.await()
            active--
            scope.acceptAll(req)
        }
        val first = async { engine.sync() }
        entered.await()
        val second = async { engine.sync() }
        release.complete(Unit)
        awaitAll(first, second)
        assertEquals(1, maxActive)
        assertEquals(1, pushed.size)
        assertEquals(1, pushed.sumOf { it.rows.size })
    }

    @Test
    fun editing_a_pulled_row_ahead_of_local_clock_gets_a_higher_updatedAt() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val ahead = clock + 3_600_000
        onPull = { scope.pullJson(PullResponse(listOf(journalRow("j1", ahead, "from phone")), 1)) }
        engine.sync()
        repo.saveJournal("j1", "edited")
        val row = journalState("j1")
        assertTrue(row.updatedAt > ahead, "edit must beat the pulled version")
        onPull = { since -> scope.pullJson(PullResponse(emptyList(), since)) }
        engine.sync()
        assertEquals(row.updatedAt, pushed.last().rows.single().updatedAt)
        assertEquals(SYNCED, journalState("j1").syncState)
    }

    @Test
    fun task_done_sets_doneAt_and_undone_clears_it() = runBlocking<Unit> {
        val id = repo.saveTask(null, "pay bill", null, false)
        assertNull(db.taskQueries.selectById(id).executeAsOne().doneAt)
        clock += 100
        repo.saveTask(id, "pay bill", null, true)
        assertEquals(clock, db.taskQueries.selectById(id).executeAsOne().doneAt)
        clock += 100
        repo.saveTask(id, "pay bill", null, false)
        assertNull(db.taskQueries.selectById(id).executeAsOne().doneAt)
    }

    @Test
    fun pull_pages_until_fewer_than_1000_rows() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val calls = mutableListOf<Long>()
        onPull = { since ->
            calls += since
            if (since == 0L) scope.pullJson(PullResponse((1..1000).map { journalRow("a$it", 10) }, 1000))
            else scope.pullJson(PullResponse(listOf(journalRow("last", 10)), 1001))
        }
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(listOf(0L, 1000L), calls)
        assertEquals(1001, db.journalQueries.selectAll().executeAsList().size)
        assertEquals("1001", store.setting(SettingKeys.CURSOR))
    }

    @Test
    fun failed_page_does_not_advance_cursor() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val bad = SyncRow("journal", "bad", 10, false, buildJsonObject { put("text", 5) })
        onPull = { scope.pullJson(PullResponse(listOf(journalRow("good", 10), bad), 5)) }
        assertTrue(engine.sync() is SyncStatus.Offline)
        // the cursor stays at its upgrade reset value; the bad page moved nothing
        assertEquals("0", store.setting(SettingKeys.CURSOR))
        assertEquals(0, db.journalQueries.selectAll().executeAsList().size)
        assertNull(store.setting(SettingKeys.LAST_SYNC_AT))
    }

    @Test
    fun deleting_creates_a_pending_tombstone_hidden_from_lists() = runBlocking<Unit> {
        val id = repo.saveJournal(null, "bye")
        repo.deleteJournal(id)
        assertEquals(0, repo.journal().size)
        val row = journalState(id)
        assertEquals(1L, row.deleted)
        assertEquals(PENDING, row.syncState)
    }

    @Test
    fun done_tasks_are_hidden_seven_days_after_doneAt() = runBlocking<Unit> {
        val id = repo.saveTask(null, "old", null, true)
        assertEquals(1, repo.tasksForLog().size)
        clock += 8L * 24 * 3600 * 1000
        assertEquals(0, repo.tasksForLog().size)
        assertEquals(0, repo.openTasks().size)
        assertNotNull(db.taskQueries.selectById(id).executeAsOneOrNull())
    }

    @Test
    fun test_connection_reports_connected_wrong_token_and_unreachable() = runBlocking<Unit> {
        val engine = engineWithSettings()
        assertEquals(ConnectionResult.Connected, engine.testConnection("http://server:8080", "tok"))
        onHealth = { scope.respond("", HttpStatusCode.Unauthorized) }
        assertEquals(ConnectionResult.WrongToken, engine.testConnection("http://server:8080", "bad"))
        onHealth = { throw IOException("down") }
        assertEquals(ConnectionResult.Unreachable, engine.testConnection("http://server:8080", "tok"))
    }

    private fun mealRow(id: String, updatedAt: Long, text: String = "שווארמה") =
        SyncRow("meal", id, updatedAt, false, buildJsonObject {
            put("type", "MEAL")
            put("text", text)
            put("eatenAt", 1_700_000_000_000L)
            put("localDate", "2023-11-14")
            put("kcal", JsonPrimitive(null as Long?))
            put("proteinG", JsonPrimitive(null as Long?))
            put("carbsG", JsonPrimitive(null as Long?))
            put("fatG", JsonPrimitive(null as Long?))
            put("estimateModel", JsonPrimitive(null as String?))
        })

    @Test
    fun pushed_meal_ends_synced() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveMeal(null, "MEAL", "pasta", 1_700_000_000_000L)
        assertEquals(1, repo.pendingCount())
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals("meal", pushed.single().rows.single().table)
        assertEquals(SYNCED, db.mealQueries.selectById(id).executeAsOne().syncState)
        assertEquals(0, repo.pendingCount())
    }

    @Test
    fun rejected_meal_becomes_rejected() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveMeal(null, "MEAL", "pasta", 1_700_000_000_000L)
        onPush = { scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), listOf(Rejected(id, "invalid: text"))))) }
        engine.sync()
        assertEquals(REJECTED, db.mealQueries.selectById(id).executeAsOne().syncState)
    }

    @Test
    fun pulled_meal_is_stored_and_a_stale_pending_edit_is_kept() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onPull = { scope.pullJson(PullResponse(listOf(mealRow("m1", clock + 10, "שווארמה")), 4)) }
        engine.sync()
        assertEquals("שווארמה", db.mealQueries.selectById("m1").executeAsOne().text)
        // a local unpushed edit wins over an older server copy
        onPush = { scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), emptyList()))) }
        clock += 100
        repo.saveMeal("m1", "MEAL", "mine", 1_700_000_000_000L)
        onPull = { scope.pullJson(PullResponse(listOf(mealRow("m1", clock - 50, "theirs")), 5)) }
        engine.sync()
        assertEquals("mine", db.mealQueries.selectById("m1").executeAsOne().text)
    }

    private fun estimatedMealRow(id: String, updatedAt: Long) =
        SyncRow("meal", id, updatedAt, false, buildJsonObject {
            put("type", "MEAL")
            put("text", "pasta")
            put("eatenAt", 1_700_000_000_000L)
            put("localDate", "2023-11-14")
            put("kcal", 640L)
            put("proteinG", 20L)
            put("carbsG", 90L)
            put("fatG", 18L)
            put("estimateModel", "qwen3:1.7b")
        })

    @Test
    fun a_pulled_estimate_with_the_same_updatedAt_does_not_replace_a_pending_meal() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onPush = { scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), emptyList()))) }
        val id = repo.saveMeal(null, "MEAL", "pasta", 1_700_000_000_000L)
        val updatedAt = db.mealQueries.selectById(id).executeAsOne().updatedAt
        onPull = { scope.pullJson(PullResponse(listOf(estimatedMealRow(id, updatedAt)), 6)) }
        engine.sync()
        val row = db.mealQueries.selectById(id).executeAsOne()
        assertEquals(PENDING, row.syncState)
        assertNull(row.kcal)
    }

    @Test
    fun a_pulled_estimate_with_the_same_updatedAt_replaces_a_synced_meal() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val id = repo.saveMeal(null, "MEAL", "pasta", 1_700_000_000_000L)
        engine.sync()
        val synced = db.mealQueries.selectById(id).executeAsOne()
        assertEquals(SYNCED, synced.syncState)
        onPull = { scope.pullJson(PullResponse(listOf(estimatedMealRow(id, synced.updatedAt)), 6)) }
        engine.sync()
        val row = db.mealQueries.selectById(id).executeAsOne()
        assertEquals(640L, row.kcal)
        assertEquals("qwen3:1.7b", row.estimateModel)
        assertEquals(SYNCED, row.syncState)
    }

    @Test
    fun pulled_row_of_an_unknown_table_is_skipped_and_the_rest_of_the_page_is_applied() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val unknown = SyncRow("hologram", "h1", 10, false, buildJsonObject { put("x", 1) })
        onPull = { scope.pullJson(PullResponse(listOf(journalRow("a", 10), unknown, journalRow("b", 10)), 8)) }
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(setOf("a", "b"), db.journalQueries.selectAll().executeAsList().map { it.id }.toSet())
        assertEquals("8", store.setting(SettingKeys.CURSOR))
    }

    @Test
    fun a_malformed_meal_still_aborts_and_rolls_back_the_page() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val bad = SyncRow("meal", "bad", 10, false, buildJsonObject { put("type", "MEAL") })
        onPull = { scope.pullJson(PullResponse(listOf(journalRow("good", 10), bad), 5)) }
        assertTrue(engine.sync() is SyncStatus.Offline)
        assertEquals(0, db.journalQueries.selectAll().executeAsList().size)
        assertEquals("0", store.setting(SettingKeys.CURSOR))
    }

    @Test
    fun a_stale_or_absent_schema_marker_makes_the_next_pull_start_at_zero() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val sinces = mutableListOf<Long>()
        onPull = { since -> sinces += since; scope.pullJson(PullResponse(emptyList(), since + 3)) }
        store.putSetting(SettingKeys.CURSOR, "77")
        // marker absent
        engine.sync()
        assertEquals(listOf(0L), sinces)
        assertEquals(AssistantDb.Schema.version.toString(), store.setting(SettingKeys.CURSOR_SCHEMA_VERSION))
        // marker current: the cursor is honoured
        store.putSetting(SettingKeys.CURSOR, "50")
        engine.sync()
        assertEquals(listOf(0L, 50L), sinces)
        // marker stale (older app version)
        store.putSetting(SettingKeys.CURSOR_SCHEMA_VERSION, "1")
        store.putSetting(SettingKeys.CURSOR, "60")
        engine.sync()
        assertEquals(listOf(0L, 50L, 0L), sinces)
    }

    @Test
    fun google_health_change_false_to_true_signals_a_reload_without_row_changes() = runBlocking<Unit> {
        val engine = engineWithSettings()
        store.putSetting(SettingKeys.GOOGLE_OK, "false")
        store.putSetting(SettingKeys.GOOGLE_LAST_FETCH_AT, "42")
        onHealth = { scope.healthJson(googleOk = true, at = 42L) }
        val before = store.changes.value
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(before + 1, store.changes.value)
        assertEquals(true, repo.googleOk())
        assertEquals(42L, repo.googleLastFetchAt())
        assertTrue(db.cal_eventQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun google_health_change_from_unknown_to_false_signals_a_reload() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onHealth = { scope.healthJson(googleOk = false, at = null) }
        assertNull(repo.googleOk())
        val before = store.changes.value
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(before + 1, store.changes.value)
        assertEquals(false, repo.googleOk())
        assertNull(repo.googleLastFetchAt())
    }

    private fun studyRow(table: String, id: String, updatedAt: Long, vararg fields: Pair<String, Any?>) =
        SyncRow(table, id, updatedAt, false, buildJsonObject {
            for ((k, v) in fields) when (v) {
                null -> put(k, JsonPrimitive(null as String?))
                is Long -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        })

    private fun courseRow(id: String, updatedAt: Long) = studyRow("course", id, updatedAt, "moodleId" to 7L, "name" to "אלגברה", "enabled" to true)

    private fun fileRow(id: String, updatedAt: Long) = studyRow(
        "study_file", id, updatedAt,
        "courseId" to "course:7", "name" to "week1.pdf", "origin" to "MOODLE", "sizeBytes" to 1234L,
        "textStatus" to "READY", "studySetDocId" to null,
    )

    @Test
    fun pulled_course_and_study_file_are_stored_synced_and_never_pushed() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onPull = { scope.pullJson(PullResponse(listOf(courseRow("course:7", 10), fileRow("sfile:a", 11)), 2)) }
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(SYNCED, db.courseQueries.selectById("course:7").executeAsOne().syncState)
        assertEquals("אלגברה", db.courseQueries.selectById("course:7").executeAsOne().name)
        assertEquals("READY", db.study_fileQueries.selectById("sfile:a").executeAsOne().textStatus)
        assertEquals(1, repo.studyFiles("course:7").size)
        assertEquals(0, repo.pendingCount())
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun a_server_written_row_never_appears_in_pending_even_if_marked_pending_in_the_db() = runBlocking<Unit> {
        val engine = engineWithSettings()
        // Pull-only tables have no pending branch: only card, quiz and exam are pushed.
        db.courseQueries.upsert(dev.tim.assistant.db.Course("course:manual:x", clock, 0, 0, PENDING, null, "Forged", 1))
        db.study_fileQueries.upsert(dev.tim.assistant.db.Study_file("sfile:x", clock, 0, 0, PENDING, "course:7", "x.pdf", "UPLOAD", 1, "READY", null))
        assertEquals(0, repo.pendingCount())
        engine.sync()
        assertTrue(pushed.flatMap { it.rows }.none { it.table == "course" || it.table == "study_file" })
    }

    @Test
    fun card_quiz_and_exam_are_pushed_and_end_synced() = runBlocking<Unit> {
        val engine = engineWithSettings()
        db.cardQueries.upsert(dev.tim.assistant.db.Card("card:f:0", clock, 0, 0, PENDING, "sfile:a", "q", "a", null, null))
        db.quizQueries.upsert(dev.tim.assistant.db.Quiz("quiz:j1", clock, 0, 0, PENDING, "course:7", "[]", null, null))
        db.examQueries.upsert(dev.tim.assistant.db.Exam("exam:1", clock, 0, 0, PENDING, null, "Final", "2026-12-01", null))
        assertEquals(3, repo.pendingCount())
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(setOf("card", "quiz", "exam"), pushed.flatMap { it.rows }.map { it.table }.toSet())
        assertEquals(0, repo.pendingCount())
        assertEquals(SYNCED, db.cardQueries.selectById("card:f:0").executeAsOne().syncState)
        assertEquals(SYNCED, db.quizQueries.selectById("quiz:j1").executeAsOne().syncState)
        assertEquals(SYNCED, db.examQueries.selectById("exam:1").executeAsOne().syncState)
    }

    @Test
    fun rejected_card_quiz_and_exam_become_rejected() = runBlocking<Unit> {
        val engine = engineWithSettings()
        db.cardQueries.upsert(dev.tim.assistant.db.Card("card:f:0", clock, 0, 0, PENDING, "sfile:a", "q", "a", null, null))
        db.quizQueries.upsert(dev.tim.assistant.db.Quiz("quiz:j1", clock, 0, 0, PENDING, "course:7", "[]", null, null))
        db.examQueries.upsert(dev.tim.assistant.db.Exam("exam:1", clock, 0, 0, PENDING, null, "Final", "2026-12-01", null))
        onPush = { req -> scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), req.rows.map { Rejected(it.id, "invalid") }))) }
        engine.sync()
        assertEquals(REJECTED, db.cardQueries.selectById("card:f:0").executeAsOne().syncState)
        assertEquals(REJECTED, db.quizQueries.selectById("quiz:j1").executeAsOne().syncState)
        assertEquals(REJECTED, db.examQueries.selectById("exam:1").executeAsOne().syncState)
    }

    @Test
    fun pulled_card_does_not_replace_a_newer_pending_mark_but_replaces_an_older_synced_one() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onPush = { scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), emptyList()))) }
        db.cardQueries.upsert(dev.tim.assistant.db.Card("card:f:0", clock + 100, 0, 0, PENDING, "sfile:a", "q", "a", "KNEW", 5L))
        val theirs = studyRow("card", "card:f:0", clock + 50, "fileId" to "sfile:a", "front" to "q", "back" to "a", "lastResult" to "DIDNT", "lastReviewedAt" to 3L)
        onPull = { scope.pullJson(PullResponse(listOf(theirs), 3)) }
        engine.sync()
        assertEquals("KNEW", db.cardQueries.selectById("card:f:0").executeAsOne().lastResult)
        val newer = studyRow("card", "card:f:0", clock + 500, "fileId" to "sfile:a", "front" to "q", "back" to "a", "lastResult" to "DIDNT", "lastReviewedAt" to 9L)
        onPull = { scope.pullJson(PullResponse(listOf(newer), 4)) }
        engine.sync()
        val row = db.cardQueries.selectById("card:f:0").executeAsOne()
        assertEquals("DIDNT", row.lastResult)
        assertEquals(9L, row.lastReviewedAt)
        assertEquals(SYNCED, row.syncState)
    }

    private fun usageRow(id: String, updatedAt: Long, endAt: Long = 20_000L) = studyRow(
        "usage_session", id, updatedAt,
        "device" to "LAPTOP", "app" to "code.exe", "title" to null, "startAt" to 10_000L, "endAt" to endAt, "localDate" to "2026-11-05",
    )

    private fun ruleRow(id: String, updatedAt: Long, label: String = "PRODUCTIVE") = studyRow(
        "app_rule", id, updatedAt, "matchType" to "EXE", "pattern" to "code.exe", "label" to label,
    )

    private fun listenRow(id: String, updatedAt: Long) = studyRow(
        "listen", id, updatedAt,
        "trackName" to "Song", "artist" to "A, B", "durationMs" to 180_000L, "playedAt" to 5_000L, "localDate" to "2026-11-05",
    )

    @Test
    fun usage_session_and_app_rule_are_pushed_ack_and_end_synced() = runBlocking<Unit> {
        val engine = engineWithSettings()
        db.usage_sessionQueries.upsert(dev.tim.assistant.db.Usage_session("use:laptop:10000", clock, 0, 0, PENDING, "LAPTOP", "code.exe", null, 10_000, 20_000, "2026-11-05"))
        db.app_ruleQueries.upsert(dev.tim.assistant.db.App_rule("rule:EXE:code.exe", clock, 0, 0, PENDING, "EXE", "code.exe", "PRODUCTIVE"))
        assertEquals(2, repo.pendingCount())
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(setOf("usage_session", "app_rule"), pushed.flatMap { it.rows }.map { it.table }.toSet())
        assertEquals(0, repo.pendingCount())
        assertEquals(SYNCED, db.usage_sessionQueries.selectById("use:laptop:10000").executeAsOne().syncState)
        assertEquals(SYNCED, db.app_ruleQueries.selectById("rule:EXE:code.exe").executeAsOne().syncState)
    }

    @Test
    fun rejected_usage_session_and_app_rule_become_rejected() = runBlocking<Unit> {
        val engine = engineWithSettings()
        db.usage_sessionQueries.upsert(dev.tim.assistant.db.Usage_session("use:laptop:10000", clock, 0, 0, PENDING, "LAPTOP", "code.exe", null, 10_000, 20_000, "2026-11-05"))
        db.app_ruleQueries.upsert(dev.tim.assistant.db.App_rule("rule:EXE:code.exe", clock, 0, 0, PENDING, "EXE", "code.exe", "PRODUCTIVE"))
        onPush = { req -> scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), req.rows.map { Rejected(it.id, "invalid") }))) }
        engine.sync()
        assertEquals(REJECTED, db.usage_sessionQueries.selectById("use:laptop:10000").executeAsOne().syncState)
        assertEquals(REJECTED, db.app_ruleQueries.selectById("rule:EXE:code.exe").executeAsOne().syncState)
    }

    @Test
    fun pulled_usage_session_and_app_rule_round_trip_with_the_strict_equal_timestamp_rule() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onPull = { scope.pullJson(PullResponse(listOf(usageRow("use:laptop:10000", 10), ruleRow("rule:EXE:code.exe", 11)), 2)) }
        assertTrue(engine.sync() is SyncStatus.Synced)
        val use = db.usage_sessionQueries.selectById("use:laptop:10000").executeAsOne()
        assertEquals(SYNCED, use.syncState)
        assertEquals("code.exe", use.app)
        assertNull(use.title)
        assertEquals("PRODUCTIVE", db.app_ruleQueries.selectById("rule:EXE:code.exe").executeAsOne().label)
        // A local PENDING row needs a strictly newer incoming row; an equal timestamp keeps the local row.
        db.app_ruleQueries.upsert(dev.tim.assistant.db.App_rule("rule:EXE:code.exe", 500, 0, 0, PENDING, "EXE", "code.exe", "NEUTRAL"))
        onPush = { scope.jsonResponse(json.encodeToString(PushResponse(emptyList(), emptyList()))) }
        onPull = { scope.pullJson(PullResponse(listOf(ruleRow("rule:EXE:code.exe", 500, "UNPRODUCTIVE")), 3)) }
        engine.sync()
        assertEquals("NEUTRAL", db.app_ruleQueries.selectById("rule:EXE:code.exe").executeAsOne().label)
        onPull = { scope.pullJson(PullResponse(listOf(ruleRow("rule:EXE:code.exe", 501, "UNPRODUCTIVE")), 4)) }
        engine.sync()
        val rule = db.app_ruleQueries.selectById("rule:EXE:code.exe").executeAsOne()
        assertEquals("UNPRODUCTIVE", rule.label)
        assertEquals(SYNCED, rule.syncState)
    }

    @Test
    fun listen_is_pulled_and_never_pushed_even_if_marked_pending() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onPull = { scope.pullJson(PullResponse(listOf(listenRow("listen:5000:t1", 10)), 1)) }
        assertTrue(engine.sync() is SyncStatus.Synced)
        val row = db.listenQueries.selectById("listen:5000:t1").executeAsOne()
        assertEquals(SYNCED, row.syncState)
        assertEquals("A, B", row.artist)
        assertEquals(listOf("listen:5000:t1"), db.listenQueries.selectRecent(50).executeAsList().map { it.id })
        db.listenQueries.upsert(dev.tim.assistant.db.Listen("listen:forged:x", clock, 0, 0, PENDING, "Forged", "X", 1, 1, "2026-11-05"))
        assertEquals(0, repo.pendingCount())
        pushed.clear()
        engine.sync()
        assertTrue(pushed.flatMap { it.rows }.none { it.table == "listen" })
    }

    @Test
    fun spotify_health_value_is_saved_and_signals_only_when_it_changes() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onHealth = { scope.jsonResponse("""{"status":"ok","serverTime":1,"googleOk":true,"googleLastFetchAt":42,"spotifyOk":true}""") }
        assertNull(repo.spotifyOk())
        engine.sync()
        assertEquals(true, repo.spotifyOk())
        val before = store.changes.value
        engine.sync()
        assertEquals(before, store.changes.value)
        onHealth = { scope.jsonResponse("""{"status":"ok","serverTime":1,"googleOk":true,"googleLastFetchAt":42,"spotifyOk":false}""") }
        engine.sync()
        assertEquals(false, repo.spotifyOk())
        // an old server without the field clears it back to unknown
        onHealth = { scope.healthJson() }
        engine.sync()
        assertNull(repo.spotifyOk())
    }

    @Test
    fun calendar_health_value_is_saved_and_signals_only_when_it_changes() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onHealth = { scope.jsonResponse("""{"status":"ok","serverTime":1,"googleOk":true,"googleLastFetchAt":42,"calendarOk":true}""") }
        assertNull(repo.calendarOk())
        engine.sync()
        assertEquals(true, repo.calendarOk())
        val before = store.changes.value
        engine.sync()
        assertEquals(before, store.changes.value)
        onHealth = { scope.jsonResponse("""{"status":"ok","serverTime":1,"googleOk":true,"googleLastFetchAt":42,"calendarOk":false}""") }
        engine.sync()
        assertEquals(false, repo.calendarOk())
        // an old server without the field clears it back to unknown
        onHealth = { scope.healthJson() }
        engine.sync()
        assertNull(repo.calendarOk())
    }

    @Test
    fun pulled_exam_and_quiz_tombstones_hide_them_from_lists() = runBlocking<Unit> {
        val engine = engineWithSettings()
        val exam = studyRow("exam", "exam:1", 10, "courseId" to null, "title" to "Final", "localDate" to "2026-12-01", "time" to "09:30")
        onPull = { scope.pullJson(PullResponse(listOf(exam), 1)) }
        engine.sync()
        assertEquals(listOf("exam:1"), repo.exams().map { it.id })
        onPull = { scope.pullJson(PullResponse(listOf(exam.copy(updatedAt = 20, deleted = true)), 2)) }
        engine.sync()
        assertTrue(repo.exams().isEmpty())
    }

    @Test
    fun moodle_health_values_are_saved_and_signal_only_when_they_change() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onHealth = {
            scope.jsonResponse("""{"status":"ok","serverTime":1,"googleOk":true,"googleLastFetchAt":42,"moodleOk":false,"moodleBaseUrl":"https://moodle.example/2027"}""")
        }
        assertNull(repo.moodleOk())
        assertNull(repo.moodleBaseUrl())
        engine.sync()
        assertEquals(false, repo.moodleOk())
        assertEquals("https://moodle.example/2027", repo.moodleBaseUrl())
        val before = store.changes.value
        engine.sync()
        assertEquals(before, store.changes.value)
        // an old server without the fields clears them back to unknown
        onHealth = { scope.healthJson() }
        engine.sync()
        assertNull(repo.moodleOk())
        assertNull(repo.moodleBaseUrl())
    }
    @Test
    fun unchanged_google_health_does_not_signal_a_reload() = runBlocking<Unit> {
        val engine = engineWithSettings()
        onHealth = { scope.healthJson(googleOk = true, at = 42L) }
        engine.sync()
        val before = store.changes.value
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(before, store.changes.value)
    }

    @Test
    fun an_interrupted_replay_restarts_from_the_saved_cursor_not_the_old_one() = runBlocking<Unit> {
        val engine = engineWithSettings()
        store.putSetting(SettingKeys.CURSOR, "900")
        store.putSetting(SettingKeys.CURSOR_SCHEMA_VERSION, "1")
        val sinces = mutableListOf<Long>()
        var failSecondPage = true
        onPull = { since ->
            sinces += since
            when {
                since == 0L -> scope.pullJson(PullResponse((1..1000).map { journalRow("a$it", 10) }, 1000))
                failSecondPage -> throw IOException("connection lost")
                else -> scope.pullJson(PullResponse(listOf(journalRow("last", 10)), 1001))
            }
        }
        assertTrue(engine.sync() is SyncStatus.Offline)
        assertEquals("1000", store.setting(SettingKeys.CURSOR))
        failSecondPage = false
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(listOf(0L, 1000L, 1000L), sinces)
        assertEquals(1001, db.journalQueries.selectAll().executeAsList().size)
    }

    // ---- Health Connect import ------------------------------------------------------------------

    private fun healthRow() = db.health_dayQueries.selectById("health:2026-11-05").executeAsOneOrNull()

    @Test
    fun health_same_values_are_pending_once_then_stay_synced() = runBlocking<Unit> {
        val source = FakeHealth { rawHealth(450, 8000) }
        val engine = engineWithSettings(healthSource = source)
        clock += 10
        assertTrue(engine.sync() is SyncStatus.Synced)
        val first = healthRow()
        assertNotNull(first)
        assertEquals(450L, first.sleepMinutes)
        assertEquals(8000L, first.steps)
        assertNull(first.restingHr)
        assertEquals(SYNCED, first.syncState) // pushed and acknowledged in the same sync
        assertEquals(1, db.workoutQueries.selectAll().executeAsList().size)
        assertEquals(1, pushed.sumOf { p -> p.rows.count { it.table == "health_day" } })

        clock += 10
        val signalBefore = store.changes.value
        assertTrue(engine.sync() is SyncStatus.Synced)
        val second = healthRow()!!
        assertEquals(SYNCED, second.syncState)
        assertEquals(first.updatedAt, second.updatedAt)
        assertEquals(1, pushed.sumOf { p -> p.rows.count { it.table == "health_day" } })
        assertEquals(signalBefore, store.changes.value)
    }

    @Test
    fun health_changed_value_makes_one_new_pending_version() = runBlocking<Unit> {
        var steps = 8000L
        val engine = engineWithSettings(healthSource = FakeHealth { rawHealth(450, steps) })
        onPush = { throw IOException("offline") }
        engine.sync()
        val first = healthRow()!!
        assertEquals(PENDING, first.syncState)
        steps = 9000L
        clock += 5
        engine.sync()
        val second = healthRow()!!
        assertEquals(9000L, second.steps)
        assertEquals(PENDING, second.syncState)
        assertTrue(second.updatedAt > first.updatedAt)
    }

    @Test
    fun health_throwing_source_does_not_stop_journal_sync() = runBlocking<Unit> {
        val engine = engineWithSettings(healthSource = FakeHealth { throw IllegalStateException("boom") })
        val id = repo.saveJournal(null, "hello")
        clock += 10
        assertTrue(engine.sync() is SyncStatus.Synced)
        assertEquals(SYNCED, journalState(id).syncState)
        assertNull(healthRow())
    }

    @Test
    fun health_null_source_or_null_read_writes_nothing_and_does_not_signal() = runBlocking<Unit> {
        val plain = engineWithSettings()
        assertTrue(plain.sync() is SyncStatus.Synced) // first sync stores the Google health settings (signals once)
        val before = store.changes.value
        assertTrue(plain.sync() is SyncStatus.Synced)
        assertEquals(before, store.changes.value)
        assertTrue(db.health_dayQueries.selectAll().executeAsList().isEmpty())

        val nullRead = SyncEngine(store, SyncClient(engine()), now = { clock }, healthSource = FakeHealth { null })
        assertTrue(nullRead.sync() is SyncStatus.Synced)
        assertEquals(before, store.changes.value)
        assertTrue(db.health_dayQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun health_cancellation_propagates() = runBlocking<Unit> {
        val engine = engineWithSettings(healthSource = FakeHealth { throw kotlinx.coroutines.CancellationException("stop") })
        var cancelled = false
        try {
            engine.sync()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}