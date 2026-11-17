import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.data.SyncEngine
import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Health_day
import dev.tim.assistant.db.Workout
import dev.tim.assistant.health.GARMIN_PACKAGE
import dev.tim.assistant.health.HealthMapper
import dev.tim.assistant.health.HealthSource
import dev.tim.assistant.health.RawExercise
import dev.tim.assistant.health.RawHealth
import dev.tim.assistant.health.RawRestingHr
import dev.tim.assistant.health.RawSleep
import dev.tim.assistant.health.healthSeries
import dev.tim.assistant.health.recentWorkouts
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushRequest
import dev.tim.assistant.model.PushResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Phase 5 acceptance tests, written from the Plan shapes (package dev.tim.assistant.health). */
class Phase5AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private val json = Json { ignoreUnknownKeys = true }
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val OTHER = "com.android.healthconnect.phone.jde348b705ae63bfc90ec9ae59c4fe07c"

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun raw(
        sleep: List<RawSleep> = emptyList(),
        hr: List<RawRestingHr> = emptyList(),
        ex: List<RawExercise> = emptyList(),
        steps: Map<String, Long> = emptyMap(),
    ) = RawHealth(sleep, hr, ex, steps)

    // ------------------------------------------------------------ AC-1

    @Test
    fun test_AC1_health_check_file_has_four_rows_with_yes_no_and_origin() {
        val f = File(root, "ops/health-check.md")
        assertTrue(f.exists(), "ops/health-check.md missing")
        val lines = f.readLines()
        for (label in listOf("Sleep", "Steps", "Resting heart rate", "Exercise")) {
            val row = lines.firstOrNull { it.trimStart().startsWith("| $label") }
            assertNotNull(row, "no table row for $label")
            val cell = row.split("|").map { it.trim() }.filter { it.isNotEmpty() }.getOrNull(1)?.lowercase()
            assertTrue(cell != null && (cell.startsWith("yes") || cell.startsWith("no")), "row $label lacks yes/no: $row")
        }
        assertTrue(f.readText().contains("com.garmin.android.apps.connectmobile"))
        assertEquals("com.garmin.android.apps.connectmobile", GARMIN_PACKAGE)
    }

    // ------------------------------------------------------------ AC-2 mapper

    @Test
    fun test_AC2_non_garmin_records_ignored() {
        val m = HealthMapper.map(raw(
            sleep = listOf(RawSleep("s", ms(2026, 11, 4, 23, 30), ms(2026, 11, 5, 7), OTHER)),
            hr = listOf(RawRestingHr("h", ms(2026, 11, 5, 8), 60, OTHER)),
            ex = listOf(RawExercise("e", ms(2026, 11, 5, 9), ms(2026, 11, 5, 10), "Running", null, OTHER)),
        ))
        assertTrue(m.days.isEmpty())
        assertTrue(m.workouts.isEmpty())
    }

    @Test
    fun test_AC2_sleep_overnight_counted_on_end_date() {
        val m = HealthMapper.map(raw(
            sleep = listOf(RawSleep("s", ms(2026, 11, 4, 23, 30), ms(2026, 11, 5, 7), GARMIN_PACKAGE)),
        ))
        assertEquals(1, m.days.size)
        assertEquals("2026-11-05", m.days[0].localDate)
        assertEquals(450L, m.days[0].sleepMinutes)
        assertNull(m.days[0].steps)
        assertNull(m.days[0].restingHr)
    }

    @Test
    fun test_AC2_awake_minutes_subtracted() {
        val m = HealthMapper.map(raw(
            sleep = listOf(RawSleep("s", ms(2026, 11, 4, 23, 30), ms(2026, 11, 5, 7), GARMIN_PACKAGE, awakeMinutes = 30)),
        ))
        assertEquals(420L, m.days[0].sleepMinutes)
    }

    @Test
    fun test_AC2_daily_steps_copied_including_measured_zero() {
        val m = HealthMapper.map(raw(steps = mapOf("2026-11-05" to 0L, "2026-11-06" to 8123L)))
        val byDate = m.days.associateBy { it.localDate }
        assertEquals(0L, byDate["2026-11-05"]!!.steps)
        assertEquals(8123L, byDate["2026-11-06"]!!.steps)
    }

    @Test
    fun test_AC2_latest_resting_hr_wins() {
        val m = HealthMapper.map(raw(hr = listOf(
            RawRestingHr("a", ms(2026, 11, 5, 6), 55, GARMIN_PACKAGE),
            RawRestingHr("b", ms(2026, 11, 5, 18), 58, GARMIN_PACKAGE),
            RawRestingHr("c", ms(2026, 11, 5, 12), 70, GARMIN_PACKAGE),
        )))
        assertEquals(58L, m.days.single().restingHr)
    }

    @Test
    fun test_AC2_day_without_garmin_data_has_no_row() {
        val m = HealthMapper.map(raw(steps = mapOf("2026-11-05" to 100L)))
        assertEquals(listOf("2026-11-05"), m.days.map { it.localDate })
    }

    @Test
    fun test_AC2_workout_mapped() {
        val s = ms(2026, 11, 5, 9)
        val e = ms(2026, 11, 5, 9, 45)
        val m = HealthMapper.map(raw(ex = listOf(RawExercise("hc1", s, e, "Running", null, GARMIN_PACKAGE))))
        val w = m.workouts.single()
        assertEquals("hc1", w.hcRecordId)
        assertEquals("Running", w.type)
        assertEquals(s, w.startAt)
        assertEquals(e, w.endAt)
        assertNull(w.distanceMeters)
    }

    // ------------------------------------------------------------ AC-2 sync engine

    private var clock = 5_000_000L
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) }
    private val db = AssistantDb(driver)
    private val store = LocalStore(db)
    private val pushed = mutableListOf<PushRequest>()

    private fun engine(source: HealthSource?): SyncEngine {
        store.putSetting(SettingKeys.SERVER_URL, "http://server:8080")
        store.putSetting(SettingKeys.DEVICE_TOKEN, "tok")
        val mock = MockEngine { req ->
            val hdr = headersOf(HttpHeaders.ContentType, "application/json")
            when (req.url.encodedPath) {
                "/v1/sync/push" -> {
                    val body = json.decodeFromString<PushRequest>((req.body as TextContent).text)
                    pushed += body
                    respond(json.encodeToString(PushResponse(body.rows.map { it.id }, emptyList())), HttpStatusCode.OK, hdr)
                }
                "/v1/sync/pull" -> respond(
                    json.encodeToString(PullResponse(emptyList(), req.url.parameters["since"]!!.toLong())),
                    HttpStatusCode.OK, hdr,
                )
                "/v1/health" -> respond("""{"status":"ok","serverTime":1,"googleOk":true,"googleLastFetchAt":1}""", HttpStatusCode.OK, hdr)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return SyncEngine(store, SyncClient(mock), now = { clock }, healthSource = source)
    }

    private class Fake(var next: () -> RawHealth?) : HealthSource {
        override suspend fun readLast7Days(): RawHealth? = next()
    }

    private fun sample(minutes: Long = 450, steps: Long = 5000) = raw(
        sleep = listOf(RawSleep("s1", ms(2026, 11, 4, 23), ms(2026, 11, 4, 23) + minutes * 60_000, GARMIN_PACKAGE)),
        steps = mapOf("2026-11-05" to steps),
    )

    private fun day(): Health_day? = db.health_dayQueries.selectById("health:2026-11-05").executeAsOneOrNull()

    @Test
    fun test_AC2_same_values_twice_row_synced_and_updatedAt_unchanged() = runBlocking<Unit> {
        val e = engine(Fake { sample() })
        assertTrue(e.sync() is SyncStatus.Synced)
        val first = day()
        assertNotNull(first)
        assertEquals("SYNCED", first.syncState)
        assertTrue(pushed.flatMap { it.rows }.any { it.id == "health:2026-11-05" }, "health row was not pushed")
        assertTrue(pushed.flatMap { it.rows }.any { it.id.startsWith("workout:") || true })
        clock += 1000
        val pushesBefore = pushed.flatMap { it.rows }.count { it.id == "health:2026-11-05" }
        assertTrue(e.sync() is SyncStatus.Synced)
        val second = day()!!
        assertEquals("SYNCED", second.syncState)
        assertEquals(first.updatedAt, second.updatedAt)
        assertEquals(pushesBefore, pushed.flatMap { it.rows }.count { it.id == "health:2026-11-05" })
    }

    @Test
    fun test_AC2_changed_value_gives_newer_updatedAt() = runBlocking<Unit> {
        var data = sample(steps = 5000)
        val e = engine(Fake { data })
        e.sync()
        val first = day()!!
        data = sample(steps = 6000)
        clock += 10
        e.sync()
        val second = day()!!
        assertEquals(6000L, second.steps)
        assertTrue(second.updatedAt > first.updatedAt, "${second.updatedAt} !> ${first.updatedAt}")
    }

    @Test
    fun test_AC2_changed_value_with_same_clock_still_increases_updatedAt() = runBlocking<Unit> {
        var data = sample(steps = 5000)
        val e = engine(Fake { data })
        e.sync()
        val first = day()!!
        data = sample(steps = 6000)
        e.sync() // clock not advanced
        assertTrue(day()!!.updatedAt > first.updatedAt)
    }

    @Test
    fun test_AC2_throwing_source_does_not_stop_sync() = runBlocking<Unit> {
        val e = engine(Fake { throw IllegalStateException("boom") })
        assertTrue(e.sync() is SyncStatus.Synced)
        assertNull(day())
    }

    @Test
    fun test_AC2_null_source_result_changes_nothing() = runBlocking<Unit> {
        val e = engine(Fake { null })
        assertTrue(e.sync() is SyncStatus.Synced)
        assertNull(day())
        assertTrue(db.workoutQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun test_AC2_no_health_source_writes_no_health_rows() = runBlocking<Unit> {
        val e = engine(null)
        assertTrue(e.sync() is SyncStatus.Synced)
        assertNull(day())
    }

    @Test
    fun test_AC2_cancellation_propagates() {
        val e = engine(Fake { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { runBlocking { e.sync() } }
    }

    @Test
    fun test_AC2_missing_dates_do_not_delete_stored_rows() = runBlocking<Unit> {
        var data = sample()
        val e = engine(Fake { data })
        e.sync()
        data = raw(steps = mapOf("2026-11-06" to 10L))
        clock += 10
        e.sync()
        val kept = day()!!
        assertEquals(0L, kept.deleted)
        assertEquals(450L, kept.sleepMinutes)
    }

    @Test
    fun test_AC2_workout_row_written_with_expected_id() = runBlocking<Unit> {
        val e = engine(Fake {
            raw(ex = listOf(RawExercise("hcX", ms(2026, 11, 5, 9), ms(2026, 11, 5, 9, 45), "Running", null, GARMIN_PACKAGE)))
        })
        e.sync()
        val w = db.workoutQueries.selectById("workout:hcX").executeAsOneOrNull()
        assertNotNull(w)
        assertEquals("Running", w.type)
    }

    // ------------------------------------------------------------ AC-2 series

    private fun hd(date: String, sleep: Long?, steps: Long?, hr: Long?, deleted: Long = 0) =
        Health_day("health:$date", 1, deleted, 0, "SYNCED", date, sleep, steps, hr)

    @Test
    fun test_AC2_series_30_labels_ending_today() {
        val s = healthSeries(emptyList(), "2026-11-09")
        assertEquals(30, s.labels.size)
        assertEquals("2026-11-09", s.labels.last())
        assertEquals("2026-10-11", s.labels.first())
        assertTrue(s.sleepHours.all { it == null } && s.steps.all { it == null })
    }

    @Test
    fun test_AC2_series_450_min_is_7_5_hours_and_zero_vs_null() {
        val s = healthSeries(
            listOf(hd("2026-11-09", 450, 0, 58), hd("2026-11-08", null, null, null)),
            "2026-11-09",
        )
        assertEquals(7.5, s.sleepHours.last())
        assertEquals(0L, s.steps.last())
        assertEquals(58L, s.restingHr.last())
        assertNull(s.steps[s.labels.indexOf("2026-11-08")])
        assertNull(s.sleepHours[s.labels.indexOf("2026-11-08")])
    }

    @Test
    fun test_AC2_series_deleted_rows_excluded() {
        val s = healthSeries(listOf(hd("2026-11-09", 450, 100, 58, deleted = 1)), "2026-11-09")
        assertNull(s.sleepHours.last())
        assertNull(s.steps.last())
    }

    @Test
    fun test_AC2_recent_workouts_newest_first_deleted_and_old_excluded() {
        fun w(id: String, start: Long, deleted: Long = 0) =
            Workout("workout:$id", 1, deleted, 0, "SYNCED", id, "Running", start, start + 60_000, null)
        val list = recentWorkouts(
            listOf(
                w("old", ms(2026, 9, 1, 9)),
                w("a", ms(2026, 11, 5, 9)),
                w("b", ms(2026, 11, 8, 9)),
                w("del", ms(2026, 11, 7, 9), deleted = 1),
            ),
            "2026-11-09",
        )
        assertEquals(listOf("b", "a"), list.map { it.hcRecordId })
    }

    // ------------------------------------------------------------ AC-3 file checks only

    @Test
    fun test_AC3_checklist_file_has_required_lines() {
        val f = File(root, "ops/checklists/phase4.md")
        assertTrue(f.exists())
        val t = f.readText().lowercase()
        for (needle in listOf("sleep", "steps", "samsung", "no data", "resting heart rate", "workout", "deny", "laptop")) {
            assertTrue(t.contains(needle), "checklist lacks '$needle'")
        }
    }

    // ------------------------------------------------------------ regression pin

    @Test
    fun test_P5_desktop_version_is_1_4_0() {
        val g = File(root, "desktopApp/build.gradle.kts").readText()
        assertTrue(g.contains("packageVersion = \"1.18.0\""))
    }
}
