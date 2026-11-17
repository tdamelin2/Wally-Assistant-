package dev.tim.assistant.usage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.data.SyncEngine
import dev.tim.assistant.db.AssistantDb
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class UsageSessionsTest {
    private val base = Instant.parse("2026-11-05T08:00:00Z").toEpochMilliseconds()

    private fun at(seconds: Long) = base + seconds * 1000

    private fun res(pkg: String, s: Long) = UsageEvent(pkg, UsageEventType.RESUMED, at(s))
    private fun pau(pkg: String, s: Long) = UsageEvent(pkg, UsageEventType.PAUSED, at(s))
    private fun off(s: Long) = UsageEvent("android", UsageEventType.SCREEN_OFF, at(s))

    @Test
    fun resumed_then_paused_makes_one_session_with_a_stable_id() {
        val r = buildPhoneSessions(listOf(res("a.b", 0), pau("a.b", 60)), at(100))
        assertEquals(1, r.sessions.size)
        val s = r.sessions.single()
        assertEquals("use:phone:a.b:${at(0)}", s.id)
        assertEquals("PHONE", s.device)
        assertEquals(at(0), s.startAt)
        assertEquals(at(60), s.endAt)
        assertEquals("2026-11-05", s.localDate)
        assertNull(r.openStartAt)
    }

    @Test
    fun an_unclosed_session_runs_to_until_and_reports_its_start() {
        val r = buildPhoneSessions(listOf(res("a.b", 10)), at(100))
        assertEquals(at(100), r.sessions.single().endAt)
        assertEquals(at(10), r.openStartAt)
    }

    @Test
    fun an_open_session_under_five_seconds_is_not_written_but_still_reported() {
        val r = buildPhoneSessions(listOf(res("a.b", 98)), at(100))
        assertTrue(r.sessions.isEmpty())
        assertEquals(at(98), r.openStartAt)
    }

    @Test
    fun closed_sessions_under_five_seconds_are_dropped() {
        val r = buildPhoneSessions(listOf(res("a.b", 0), pau("a.b", 4), res("c.d", 10), pau("c.d", 15)), at(100))
        assertEquals(listOf("c.d"), r.sessions.map { it.app })
    }

    @Test
    fun the_same_input_twice_gives_the_same_ids() {
        val events = listOf(res("a.b", 0), pau("a.b", 60), res("c.d", 70))
        val one = buildPhoneSessions(events, at(200))
        val two = buildPhoneSessions(events, at(200))
        assertEquals(one, two)
    }

    @Test
    fun events_at_or_after_until_are_ignored() {
        val r = buildPhoneSessions(listOf(res("a.b", 0), pau("a.b", 60), res("c.d", 100)), at(100))
        assertEquals(listOf("a.b"), r.sessions.map { it.app })
        assertNull(r.openStartAt)
    }

    @Test
    fun a_session_across_two_reads_keeps_its_id_and_grows() {
        val first = buildPhoneSessions(listOf(res("a.b", 0)), at(30))
        val second = buildPhoneSessions(listOf(res("a.b", 0), pau("a.b", 90)), at(120))
        assertEquals(first.sessions.single().id, second.sessions.single().id)
        assertEquals(at(30), first.sessions.single().endAt)
        assertEquals(at(90), second.sessions.single().endAt)
    }

    @Test
    fun orphan_and_foreign_paused_events_are_ignored() {
        val r = buildPhoneSessions(listOf(pau("x.y", 0), res("a.b", 5), pau("c.d", 20), pau("a.b", 40)), at(100))
        assertEquals(1, r.sessions.size)
        assertEquals(at(5), r.sessions.single().startAt)
        assertEquals(at(40), r.sessions.single().endAt)
    }

    @Test
    fun screen_off_closes_the_open_session() {
        val r = buildPhoneSessions(listOf(res("a.b", 0), off(30), res("a.b", 3000)), at(4000))
        assertEquals(listOf(at(30), at(4000)), r.sessions.map { it.endAt })
    }

    @Test
    fun a_new_app_closes_the_previous_one_at_that_time() {
        val r = buildPhoneSessions(listOf(res("a.b", 0), res("c.d", 20)), at(40))
        assertEquals(listOf("a.b", "c.d"), r.sessions.map { it.app })
        assertEquals(at(20), r.sessions[0].endAt)
    }

    @Test
    fun same_app_sessions_within_two_seconds_merge_keeping_the_first_start() {
        val r = buildPhoneSessions(listOf(res("a.b", 0), pau("a.b", 10), res("a.b", 11), pau("a.b", 30), res("a.b", 40), pau("a.b", 50)), at(100))
        assertEquals(2, r.sessions.size)
        assertEquals(at(0), r.sessions[0].startAt)
        assertEquals(at(30), r.sessions[0].endAt)
    }

    @Test
    fun events_with_equal_times_keep_input_order() {
        val r = buildPhoneSessions(listOf(res("a.b", 0), res("c.d", 20), pau("a.b", 20), pau("c.d", 50)), at(100))
        assertEquals(listOf("a.b", "c.d"), r.sessions.map { it.app })
        assertEquals(at(50), r.sessions[1].endAt)
    }

    // ---- SyncEngine import -----------------------------------------------------------------

    private var clock = at(300)
    private val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
    private val store = LocalStore(db)

    private fun engine(source: UsageSource) =
        SyncEngine(store, SyncClient(MockEngine { respond("", HttpStatusCode.NotFound) }), now = { clock }, usageSource = source)

    private class FakeSource(var next: (Long) -> List<UsageEvent>) : UsageSource {
        val froms = mutableListOf<Long>()
        override suspend fun readSince(fromMs: Long): List<UsageEvent> {
            froms += fromMs
            return next(fromMs)
        }
    }

    @Test
    fun import_works_with_no_server_and_is_not_repeated_for_unchanged_rows() = runBlocking<Unit> {
        val source = FakeSource { listOf(res("a.b", 0), pau("a.b", 60)) }
        val engine = engine(source)
        engine.sync()
        val row = db.usage_sessionQueries.selectAll().executeAsList().single()
        assertEquals("PENDING", row.syncState)
        clock += 10_000
        engine.sync()
        assertEquals(row.updatedAt, db.usage_sessionQueries.selectAll().executeAsList().single().updatedAt)
    }

    @Test
    fun a_failed_read_advances_nothing() = runBlocking<Unit> {
        val source = FakeSource { throw UsageUnavailable("no access") }
        engine(source).sync()
        assertTrue(db.usage_sessionQueries.selectAll().executeAsList().isEmpty())
        assertNull(store.setting(SettingKeys.USAGE_LAST_READ))
    }

    @Test
    fun the_next_read_starts_at_the_open_sessions_start_and_the_session_grows() = runBlocking<Unit> {
        val source = FakeSource { listOf(res("a.b", 100)) }
        val engine = engine(source)
        engine.sync()
        assertEquals(at(100).toString(), store.setting(SettingKeys.USAGE_LAST_READ))
        val first = db.usage_sessionQueries.selectAll().executeAsList().single()
        clock = at(400)
        engine.sync()
        assertEquals(at(100), source.froms.last())
        val grown = db.usage_sessionQueries.selectAll().executeAsList().single()
        assertEquals(first.id, grown.id)
        assertEquals(at(400), grown.endAt)
        assertTrue(grown.updatedAt > first.updatedAt)
    }

    @Test
    fun the_first_read_goes_back_at_most_seven_days() = runBlocking<Unit> {
        val source = FakeSource { emptyList() }
        engine(source).sync()
        assertEquals(clock - 7L * 24 * 60 * 60 * 1000, source.froms.single())
        assertEquals(clock.toString(), store.setting(SettingKeys.USAGE_LAST_READ))
    }
}
