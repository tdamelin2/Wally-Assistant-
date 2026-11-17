package dev.tim.assistant.server.notify

import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Dream
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Outbox_notification
import dev.tim.assistant.db.Task
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.ai.OLLAMA_URL
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.ai.OllamaFake
import dev.tim.assistant.server.ai.checkin
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.ai.jerusalemMs
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.chat.PINNED_CHAT_ID
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val WED = "2026-11-11"
private const val SUN = "2026-11-15"

class NotifySchedulerTest {
    private var now = jerusalemMs(WED, 12)
    private val sdb: ServerDb = openDb(tempDbPath())
    private val store = SyncStore(sdb)
    private val writer = ServerWriter(sdb, store) { now }
    private val lock = Mutex()
    private val ollama = OllamaFake()
    private val brain = Brain(null, OLLAMA_URL, "hp-model", engine = MockEngine { respond("{}", HttpStatusCode.OK) })
    private val scheduler = NotifyScheduler(sdb, writer, brain, lock, { OllamaClient(it, ollama.engine()) }) { now }

    @AfterTest
    fun close() = sdb.close()

    private fun setNow(date: String, hour: Int, minute: Int = 0) {
        now = jerusalemMs(date, hour, minute)
    }

    private suspend fun seed(row: SyncRow) {
        sdb.writeLock.withLock { sdb.db.transaction { store.writeIfNewer(row) } }
    }

    private fun rows(): List<Outbox_notification> = sdb.db.chatQueries.selectNotificationsSince(0, 100).executeAsList()

    private fun only(kind: String) = rows().filter { it.kind == kind }

    private suspend fun task(id: String, title: String, due: String?, done: Boolean = false) =
        seed(toSyncRow(Task(id, 1000, 0, 0, "SYNCED", title, due, if (done) 1 else 0, null, null)))

    private suspend fun event(id: String, title: String, start: Long, allDay: Boolean = false, location: String? = null) =
        seed(toSyncRow(Cal_event("wcal:$id", 1000, 0, 0, "SYNCED", "wally", id, title, start, start + 3_600_000, if (allDay) 1 else 0, location)))

    // ---- check-in reminder ------------------------------------------------------------------------------

    @Test
    fun the_checkin_reminder_is_queued_at_least_an_hour_ahead_for_the_set_time() = runBlocking {
        setNow(WED, 19, 29)
        assertEquals(0, scheduler.tick(), "not yet inside the 90-minute lead")
        setNow(WED, 19, 30)
        assertEquals(1, scheduler.tick())
        val row = only(KIND_CHECKIN).single()
        assertEquals(jerusalemMs(WED, 21), row.fire_at)
        assertTrue(row.fire_at - now >= 60 * 60_000L, "queued at least an hour ahead")
        assertNull(row.chat_id)
        assertEquals("screen:checkin-add", targetOf(row.kind, row.id, row.chat_id))
        assertTrue(sdb.db.chatQueries.selectActiveChats().executeAsList().isEmpty(), "no chat is created for a reminder")
        assertNull(row.delivered_at)
    }

    @Test
    fun the_checkin_time_comes_from_the_stored_setting() = runBlocking {
        sdb.db.kvQueries.put(KV_CHECKIN_TIME, "20:00")
        setNow(WED, 18, 30)
        scheduler.tick()
        assertEquals(jerusalemMs(WED, 20), only(KIND_CHECKIN).single().fire_at)
    }

    @Test
    fun no_checkin_reminder_is_queued_when_todays_checkin_exists() = runBlocking {
        seed(checkin(WED, 4))
        setNow(WED, 20, 0)
        scheduler.tick()
        assertTrue(only(KIND_CHECKIN).isEmpty())
    }

    @Test
    fun the_same_reminder_is_never_queued_twice() = runBlocking {
        setNow(WED, 20, 0)
        scheduler.tick()
        setNow(WED, 20, 1)
        scheduler.tick()
        assertEquals(1, only(KIND_CHECKIN).size)
    }

    @Test
    fun two_ticks_at_once_still_queue_one_row() = runBlocking {
        setNow(WED, 20, 0)
        coroutineScope { listOf(async { scheduler.tick() }, async { scheduler.tick() }).awaitAll() }
        assertEquals(1, only(KIND_CHECKIN).size)
    }

    @Test
    fun a_reminder_inside_quiet_hours_is_not_queued() = runBlocking {
        sdb.db.kvQueries.put(KV_CHECKIN_TIME, "23:30")
        setNow(WED, 22, 30)
        scheduler.tick()
        assertTrue(only(KIND_CHECKIN).isEmpty())
    }

    @Test
    fun a_switched_off_type_is_not_queued() = runBlocking {
        sdb.db.kvQueries.put(KV_KINDS, "morningList,headsUp,ideas,weeklyReport")
        setNow(WED, 20, 0)
        scheduler.tick()
        assertTrue(only(KIND_CHECKIN).isEmpty())
    }

    @Test
    fun a_checkin_time_just_after_midnight_is_queued_the_evening_before() = runBlocking {
        sdb.db.kvQueries.put(KV_QUIET_START, "01:00")
        sdb.db.kvQueries.put(KV_QUIET_END, "02:00")
        sdb.db.kvQueries.put(KV_CHECKIN_TIME, "00:10")
        setNow(WED, 22, 50)
        scheduler.tick()
        assertEquals(jerusalemMs("2026-11-12", 0, 10), only(KIND_CHECKIN).single().fire_at)
    }

    // ---- morning list -------------------------------------------------------------------------------------

    @Test
    fun the_morning_list_has_the_due_and_overdue_date_only_tasks() = runBlocking {
        task("t1", "Pay rent", WED)
        task("t2", "Call dentist", "2026-11-09")
        task("t3", "Next week thing", "2026-11-20")
        task("t4", "Already done", WED, done = true)
        task("t5", "No date", null)
        setNow(WED, 6, 0)
        scheduler.tick()
        val row = only(KIND_MORNING).single()
        assertEquals(jerusalemMs(WED, 7, 30), row.fire_at)
        assertEquals("2 things due today", row.title)
        assertEquals("Call dentist; Pay rent", row.body)
        assertEquals("screen:today", targetOf(row.kind, row.id, row.chat_id))
        assertNull(row.chat_id)
    }

    @Test
    fun no_morning_row_when_nothing_is_due() = runBlocking {
        task("t3", "Next week thing", "2026-11-20")
        setNow(WED, 6, 0)
        scheduler.tick()
        assertTrue(only(KIND_MORNING).isEmpty())
    }

    @Test
    fun a_long_morning_list_shows_five_and_counts_the_rest() = runBlocking {
        for (i in 1..7) task("t$i", "Task $i", WED)
        setNow(WED, 6, 15)
        scheduler.tick()
        val row = only(KIND_MORNING).single()
        assertEquals("7 things due today", row.title)
        assertTrue(row.body.endsWith("; and 2 more"))
    }

    @Test
    fun the_morning_row_waits_for_its_lead_window() = runBlocking {
        task("t1", "Pay rent", WED)
        setNow(WED, 5, 59)
        scheduler.tick()
        assertTrue(only(KIND_MORNING).isEmpty())
    }

    // ---- heads-ups ----------------------------------------------------------------------------------------

    @Test
    fun a_timed_event_gets_a_heads_up_thirty_minutes_before() = runBlocking {
        event("ev1", "Dentist", jerusalemMs(WED, 14, 0), location = "Main St")
        setNow(WED, 12, 30)
        scheduler.tick()
        val row = only(KIND_HEADS_UP).single()
        assertEquals(jerusalemMs(WED, 13, 30), row.fire_at)
        assertEquals("Starting soon: Dentist", row.title)
        assertEquals("At 14:00 - Main St", row.body)
        assertEquals("event:wcal:ev1", row.chat_id)
        assertEquals("event:wcal:ev1", targetOf(row.kind, row.id, row.chat_id))
    }

    @Test
    fun a_timed_exam_gets_a_heads_up_and_an_untimed_one_does_not() = runBlocking {
        seed(toSyncRow(Exam("exam1", 1000, 0, 0, "SYNCED", null, "Calculus", WED, "16:00")))
        seed(toSyncRow(Exam("exam2", 1000, 0, 0, "SYNCED", null, "Physics", WED, null)))
        setNow(WED, 14, 30)
        scheduler.tick()
        val row = only(KIND_HEADS_UP).single()
        assertEquals("exam:exam1", row.chat_id)
        assertEquals(jerusalemMs(WED, 15, 30), row.fire_at)
        assertEquals("Exam soon: Calculus", row.title)
    }

    @Test
    fun all_day_past_and_far_events_get_no_heads_up() = runBlocking {
        event("allday", "Holiday", jerusalemMs(WED, 13, 0), allDay = true)
        event("past", "Earlier", jerusalemMs(WED, 11, 0))
        event("far", "Tomorrow thing", jerusalemMs("2026-11-12", 9, 0))
        setNow(WED, 12, 30)
        scheduler.tick()
        assertTrue(only(KIND_HEADS_UP).isEmpty())
    }

    @Test
    fun a_heads_up_for_the_same_event_time_is_queued_once_but_a_moved_event_gets_a_new_one() = runBlocking {
        event("ev1", "Dentist", jerusalemMs(WED, 14, 0))
        setNow(WED, 12, 30)
        scheduler.tick()
        scheduler.tick()
        assertEquals(1, only(KIND_HEADS_UP).size)
        // moved to 14:30: a new key, a new heads-up
        sdb.writeLock.withLock {
            sdb.db.transaction {
                store.writeIfNewer(toSyncRow(Cal_event("wcal:ev1", 2000, 0, 0, "SYNCED", "wally", "ev1", "Dentist", jerusalemMs(WED, 14, 30), jerusalemMs(WED, 15, 30), 0, null)))
            }
        }
        scheduler.tick()
        assertEquals(2, only(KIND_HEADS_UP).size)
    }

    // ---- cap and cleanup ----------------------------------------------------------------------------------

    @Test
    fun the_sixth_reminder_of_a_day_is_the_last() = runBlocking {
        // five rows already fire on Wednesday
        for (i in 1..5) sdb.db.chatQueries.insertNotification(KIND_IDEA, "t", "b", null, jerusalemMs(WED, 8 + i), now, "seed:$i")
        // a heads-up for a 22:00 event would be the seventh
        event("late", "Late meeting", jerusalemMs(WED, 22, 0))
        setNow(WED, 20, 0)
        assertEquals(1, scheduler.tick(), "the check-in takes the sixth slot")
        assertEquals(1, only(KIND_CHECKIN).size)
        assertTrue(only(KIND_HEADS_UP).isEmpty(), "the day is full")
        assertEquals(6, rows().size)
    }

    @Test
    fun the_cap_is_counted_for_the_fire_day_of_each_row() = runBlocking {
        for (i in 1..6) sdb.db.chatQueries.insertNotification(KIND_IDEA, "t", "b", null, jerusalemMs(WED, 8 + i), now, "seed:$i")
        setNow(WED, 20, 0)
        assertEquals(0, scheduler.tick(), "Wednesday is full")
        // a check-in set for just after midnight belongs to Thursday and still fits
        sdb.db.kvQueries.put(KV_QUIET_START, "01:00")
        sdb.db.kvQueries.put(KV_QUIET_END, "02:00")
        sdb.db.kvQueries.put(KV_CHECKIN_TIME, "00:30")
        setNow(WED, 23, 30)
        assertEquals(1, scheduler.tick())
        assertEquals(jerusalemMs("2026-11-12", 0, 30), only(KIND_CHECKIN).single().fire_at)
    }

    @Test
    fun rows_older_than_seven_days_are_removed() = runBlocking {
        sdb.db.chatQueries.insertNotification(KIND_IDEA, "old", "b", null, now - 8 * 86_400_000L, now - 8 * 86_400_000L, "old:1")
        sdb.db.chatQueries.insertNotification(KIND_IDEA, "recent", "b", null, now - 2 * 86_400_000L, now - 2 * 86_400_000L, "recent:1")
        setNow(WED, 12, 0)
        scheduler.tick()
        val titles = rows().map { it.title }
        assertFalse("old" in titles)
        assertTrue("recent" in titles)
    }

    // ---- model ideas --------------------------------------------------------------------------------------

    @Test
    fun an_idea_is_written_by_the_model_for_noon_and_for_five() = runBlocking {
        ollama.reply = { 200 to chatBody("Try a short walk after lunch.") }
        setNow(WED, 10, 30)
        assertEquals(1, scheduler.tick())
        val noon = only(KIND_IDEA).single()
        assertEquals(jerusalemMs(WED, 12), noon.fire_at)
        assertEquals("Try a short walk after lunch.", noon.body)
        assertEquals("idea:${noon.id}", targetOf(noon.kind, noon.id, noon.chat_id))
        assertNull(noon.chat_id, "an idea chat is created when Tim taps it, not now")
        assertTrue(sdb.db.chatQueries.selectActiveChats().executeAsList().isEmpty())
        setNow(WED, 15, 30)
        scheduler.tick()
        assertEquals(listOf(jerusalemMs(WED, 12), jerusalemMs(WED, 17)), only(KIND_IDEA).map { it.fire_at })
        setNow(WED, 16, 0)
        scheduler.tick()
        assertEquals(2, only(KIND_IDEA).size, "two ideas a day at most")
    }

    @Test
    fun the_idea_prompt_asks_about_an_unsorted_app_and_holds_no_dream_text() = runBlocking {
        seed(toSyncRow(Usage_session("us1", 1000, 0, 0, "SYNCED", "laptop", "tiktok.exe", null, jerusalemMs(WED, 9), jerusalemMs(WED, 10), WED)))
        seed(toSyncRow(Dream("dream:d1", 1000, 0, 0, "SYNCED", WED, "I dreamt of a purple harbour SECRETDREAMTEXT", 3, 0, "", null, null)))
        ollama.reply = { 200 to chatBody("Is tiktok.exe productive?") }
        setNow(WED, 10, 30)
        scheduler.tick()
        val body = ollama.bodies.single()
        assertTrue(body.contains("tiktok.exe"))
        assertTrue(body.contains("productive, unproductive or neutral"))
        assertFalse(body.contains("SECRETDREAMTEXT"), "dream text never reaches the model")
        assertFalse(body.lowercase().contains("purple harbour"))
        // the model's request used the HP model
        assertTrue(body.contains("hp-model"))
    }

    @Test
    fun a_busy_hp_model_skips_the_idea_and_a_later_tick_writes_it() = runBlocking {
        ollama.reply = { 200 to chatBody("A later idea.") }
        setNow(WED, 10, 30)
        lock.lock()
        assertEquals(0, scheduler.tick())
        lock.unlock()
        assertTrue(ollama.bodies.isEmpty())
        setNow(WED, 10, 31)
        assertEquals(1, scheduler.tick())
        assertFalse(lock.isLocked)
    }

    @Test
    fun an_unreachable_model_queues_no_idea() = runBlocking {
        ollama.reply = { 500 to "{}" }
        setNow(WED, 10, 30)
        assertEquals(0, scheduler.tick())
        assertTrue(only(KIND_IDEA).isEmpty())
        assertFalse(lock.isLocked)
    }

    @Test
    fun an_idea_inside_quiet_hours_is_never_asked_for() = runBlocking {
        sdb.db.kvQueries.put(KV_QUIET_START, "11:00")
        sdb.db.kvQueries.put(KV_QUIET_END, "13:00")
        setNow(WED, 10, 30)
        scheduler.tick()
        assertTrue(ollama.bodies.isEmpty(), "no model call for a reminder that could not be shown")
        assertTrue(only(KIND_IDEA).isEmpty())
    }

    // ---- weekly report ------------------------------------------------------------------------------------

    @Test
    fun the_sunday_report_goes_into_the_pinned_chat_and_the_reminder_points_at_it() = runBlocking {
        ollama.reply = { 200 to chatBody("Week summary line one.\nLine two with more words.\nSuggestion: sleep earlier.") }
        seed(toSyncRow(Dream("dream:d1", 1000, 0, 0, "SYNCED", SUN, "a dream with SECRETDREAMTEXT in it", 3, 0, "", null, null)))
        setNow(SUN, 17, 30)
        assertTrue(scheduler.tick() >= 1)
        val row = only(KIND_WEEKLY).single()
        assertEquals(jerusalemMs(SUN, 19), row.fire_at)
        assertEquals(PINNED_CHAT_ID, row.chat_id)
        assertEquals("chat:$PINNED_CHAT_ID", targetOf(row.kind, row.id, row.chat_id))
        val message = sdb.db.chatQueries.selectMessageById("weekly:$SUN").executeAsOneOrNull()
        assertEquals("assistant", message!!.role)
        assertTrue(message.text.contains("Suggestion: sleep earlier."))
        assertEquals(PINNED_CHAT_ID, message.chat_id)
        assertTrue(ollama.bodies.none { it.contains("SECRETDREAMTEXT") }, "the report prompt is dream-free")
        // a second tick adds nothing
        scheduler.tick()
        assertEquals(1, only(KIND_WEEKLY).size)
        assertEquals(1, sdb.db.chatQueries.selectMessagesByChat(PINNED_CHAT_ID).executeAsList().size)
    }

    @Test
    fun no_report_on_a_weekday() = runBlocking {
        ollama.reply = { 200 to chatBody("report") }
        setNow(WED, 17, 30)
        scheduler.tick()
        assertTrue(only(KIND_WEEKLY).isEmpty())
        assertTrue(sdb.db.chatQueries.selectMessagesByChat(PINNED_CHAT_ID).executeAsList().isEmpty())
    }

    @Test
    fun a_switched_off_report_writes_no_message() = runBlocking {
        sdb.db.kvQueries.put(KV_KINDS, "checkin,morningList,headsUp,ideas")
        ollama.reply = { 200 to chatBody("report") }
        setNow(SUN, 17, 30)
        scheduler.tick()
        assertTrue(only(KIND_WEEKLY).isEmpty())
        assertTrue(sdb.db.chatQueries.selectMessagesByChat(PINNED_CHAT_ID).executeAsList().isEmpty())
    }

    @Test
    fun without_a_model_the_scheduler_still_queues_the_other_reminders() = runBlocking {
        val noModel = NotifyScheduler(sdb, writer, null, lock, { OllamaClient(it, ollama.engine()) }) { now }
        setNow(WED, 20, 0)
        assertEquals(1, noModel.tick())
        assertEquals(1, only(KIND_CHECKIN).size)
        assertTrue(ollama.bodies.isEmpty())
    }
}
