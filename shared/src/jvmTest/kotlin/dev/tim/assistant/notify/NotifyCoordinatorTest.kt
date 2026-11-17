package dev.tim.assistant.notify

import dev.tim.assistant.ai.ChatKv
import dev.tim.assistant.data.ChatApi
import dev.tim.assistant.data.ServerConfig
import dev.tim.assistant.model.NotificationDto
import dev.tim.assistant.model.NotificationsResponse
import dev.tim.assistant.model.NotifySettingsDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NotifyTargetTest {
    @Test
    fun every_contract_target_parses() {
        assertEquals(LaunchTarget.CheckinForm, parseLaunchTarget("screen:checkin-add"))
        assertEquals(LaunchTarget.Today, parseLaunchTarget("screen:today"))
        assertEquals(LaunchTarget.Exam("e1"), parseLaunchTarget("exam:e1"))
        assertEquals(LaunchTarget.Event("ev:7"), parseLaunchTarget("event:ev:7"))
        assertEquals(LaunchTarget.Idea(42), parseLaunchTarget("idea:42"))
        assertEquals(LaunchTarget.Chat("weekly-report"), parseLaunchTarget("chat:weekly-report"))
    }

    @Test
    fun unknown_or_empty_targets_do_not_parse() {
        for (t in listOf(null, "", "screen:", "screen:nowhere", "idea:abc", "exam:", "nothing", ":x", "other:1")) assertNull(parseLaunchTarget(t), "$t")
    }

    @Test
    fun a_tap_is_queued_once_and_consumed() {
        val bus = LaunchRequests()
        assertTrue(bus.post("idea:3", "Try this"))
        val req = bus.pending.value!!
        assertEquals(LaunchRequest(LaunchTarget.Idea(3), "Try this"), req)
        bus.consume(req)
        assertNull(bus.pending.value)
        assertTrue(!bus.post("garbage", null))
    }
}

class NotifyCoordinatorTest {
    private val json = Json { encodeDefaults = true }
    private val now = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli() // 12:00 in Jerusalem

    private class MapKv(val map: MutableMap<String, String> = HashMap()) : ChatKv {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private class FakeSink : NotifySink {
        val shown = ArrayList<Long>()
        val scheduled = ArrayList<Pair<Long, Long>>()
        override fun show(row: NotificationDto) { shown += row.id }
        override fun schedule(row: NotificationDto, at: Long) { scheduled += row.id to at }
    }

    private fun row(id: Long, kind: String = "MORNING", fireAt: Long = now - 1000) =
        NotificationDto(id, kind, "Title $id", "Body $id", "screen:today", fireAt, "k$id")

    private class Rig(val c: NotifyCoordinator, val kv: MapKv, val sink: FakeSink, val sinceSeen: MutableList<String>)

    private fun rig(
        rows: () -> List<NotificationDto>,
        settings: NotifySettingsDto = NotifySettingsDto(),
        online: () -> Boolean = { true },
        done: () -> Boolean = { false },
        clock: () -> Long = { now },
        kv: MapKv = MapKv(),
    ): Rig {
        val sink = FakeSink()
        val seen = ArrayList<String>()
        val engine = MockEngine { request ->
            if (!online()) throw IOException("offline")
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/v1/notifications" -> {
                    seen += request.url.parameters["since"].orEmpty()
                    val since = request.url.parameters["since"]!!.toLong()
                    respond(json.encodeToString(NotificationsResponse(rows().filter { it.id > since })), HttpStatusCode.OK, headers)
                }
                "/v1/notify-settings" -> respond(json.encodeToString(settings), HttpStatusCode.OK, headers)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val api = ChatApi({ ServerConfig("http://hp", "tok") }, engine)
        return Rig(NotifyCoordinator(api, kv, sink, done, clock), kv, sink, seen)
    }

    @Test
    fun a_due_row_is_shown_once_and_the_cursor_moves_after_the_ledger_is_saved() = runBlocking<Unit> {
        val r = rig({ listOf(row(1)) })
        r.c.fetch()
        r.c.fetch()
        assertEquals(listOf(1L), r.sink.shown)
        assertEquals("1", r.kv.get(NOTIFY_CURSOR_KEY))
        assertEquals(listOf("0", "1"), r.sinceSeen)
        assertEquals(LEDGER_SHOWN, NotifyLedger.decode(r.kv.get(NOTIFY_LEDGER_KEY)).single().state)
    }

    @Test
    fun a_future_row_is_scheduled_and_shown_by_the_alarm() = runBlocking<Unit> {
        var t = now
        val r = rig({ listOf(row(5, fireAt = now + 3_600_000)) }, clock = { t })
        r.c.fetch()
        assertTrue(r.sink.shown.isEmpty())
        assertEquals(listOf(5L to now + 3_600_000), r.sink.scheduled.distinct())
        t = now + 3_600_000
        r.c.onAlarm(5)
        assertEquals(listOf(5L), r.sink.shown)
        r.c.onAlarm(5) // a second alarm for a shown row does nothing
        assertEquals(listOf(5L), r.sink.shown)
    }

    @Test
    fun a_checkin_done_by_the_time_the_alarm_fires_is_skipped() = runBlocking<Unit> {
        var done = false
        var t = now
        val r = rig({ listOf(row(7, "CHECKIN", fireAt = now + 600_000)) }, done = { done }, clock = { t })
        r.c.fetch()
        done = true
        t = now + 600_000
        r.c.onAlarm(7)
        assertTrue(r.sink.shown.isEmpty())
        assertEquals(LEDGER_SKIPPED, NotifyLedger.decode(r.kv.get(NOTIFY_LEDGER_KEY)).single().state)
    }

    @Test
    fun offline_changes_nothing_and_the_next_fetch_catches_up() = runBlocking<Unit> {
        var online = false
        val r = rig({ listOf(row(1), row(2)) }, online = { online })
        r.c.fetch()
        assertNull(r.kv.get(NOTIFY_CURSOR_KEY))
        assertTrue(r.sink.shown.isEmpty())
        online = true
        r.c.fetch()
        assertEquals(listOf(1L, 2L), r.sink.shown)
        assertEquals("2", r.kv.get(NOTIFY_CURSOR_KEY))
    }

    @Test
    fun a_row_already_in_the_ledger_is_not_shown_again_even_with_a_reset_cursor() = runBlocking<Unit> {
        val kv = MapKv()
        rig({ listOf(row(1)) }, kv = kv).c.fetch()
        kv.map.remove(NOTIFY_CURSOR_KEY)
        val second = rig({ listOf(row(1)) }, kv = kv)
        second.c.fetch()
        assertTrue(second.sink.shown.isEmpty())
    }

    @Test
    fun the_daily_cap_and_disabled_kinds_skip_rows() = runBlocking<Unit> {
        val rows = (1L..8L).map { row(it) }
        val r = rig({ rows })
        r.c.fetch()
        assertEquals(NOTIFY_DAILY_CAP, r.sink.shown.size)
        val off = rig({ listOf(row(1)) }, settings = NotifySettingsDto(morningList = false))
        off.c.fetch()
        assertTrue(off.sink.shown.isEmpty())
        assertEquals("1", off.kv.get(NOTIFY_CURSOR_KEY))
    }

    @Test
    fun rescheduleAll_asks_the_clock_again_for_waiting_rows_only() = runBlocking<Unit> {
        val kv = MapKv()
        rig({ listOf(row(3, fireAt = now + 7_200_000), row(4)) }, kv = kv).c.fetch()
        val after = rig({ emptyList() }, kv = kv)
        after.c.rescheduleAll()
        assertEquals(listOf(3L to now + 7_200_000), after.sink.scheduled)
        assertTrue(after.sink.shown.isEmpty())
    }

    @Test
    fun old_ledger_entries_are_pruned() {
        val old = LedgerEntry(row(1, fireAt = now - 5 * 86_400_000L), LEDGER_SHOWN, now - 5 * 86_400_000L)
        val fresh = LedgerEntry(row(2), LEDGER_SHOWN, now)
        assertEquals(listOf(fresh), NotifyLedger.prune(listOf(old, fresh), now))
        assertEquals(1, NotifyLedger.shownToday(listOf(old, fresh), now))
    }
}
