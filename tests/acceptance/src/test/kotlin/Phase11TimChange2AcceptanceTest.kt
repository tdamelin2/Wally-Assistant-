import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Cal_event
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase11TimChange2AcceptanceTest {
    private val jlm = ZoneId.of("Asia/Jerusalem")
    private fun ms(date: String, h: Int, m: Int = 0): Long {
        val d = java.time.LocalDate.parse(date)
        return ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, m, 0, 0, jlm).toInstant().toEpochMilli()
    }

    private fun memDb() = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })

    private fun ev(db: AssistantDb, id: String, title: String, start: Long, end: Long, allDay: Long = 0) =
        db.cal_eventQueries.upsert(Cal_event("cal:primary:$id", 1000, 0, 1, "SYNCED", "primary", id, title, start, end, allDay, null))

    @Test
    fun test_AC_P11A10_prefix_has_time_in_israel_winter() {
        val ctx = ChatContext.build(memDb(), "2026-11-10", ms("2026-11-10", 14, 5))
        assertTrue(ctx.contains("Today is 2026-11-10, the time now is 14:05 (Israel time)."), ctx)
    }

    @Test
    fun test_AC_P11A10_prefix_has_time_in_israel_summer_dst() {
        val ctx = ChatContext.build(memDb(), "2026-07-10", ms("2026-07-10", 23, 40))
        assertTrue(ctx.contains("Today is 2026-07-10, the time now is 23:40 (Israel time)."), ctx)
    }

    @Test
    fun test_AC_P11A10_without_nowMs_old_prefix_kept() {
        val ctx = ChatContext.build(memDb(), "2026-11-10")
        assertTrue(ctx.startsWith("Today is 2026-11-10.\n"), ctx)
        assertFalse(ctx.contains("time now is"), ctx)
    }

    @Test
    fun test_AC_P11A10_ended_timed_event_marked_upcoming_not() {
        val db = memDb()
        ev(db, "past", "PASTEVENT", ms("2026-11-10", 9), ms("2026-11-10", 10))
        ev(db, "edge", "EDGEEVENT", ms("2026-11-10", 13), ms("2026-11-10", 14))
        ev(db, "later", "LATEREVENT", ms("2026-11-10", 16), ms("2026-11-10", 17))
        ev(db, "tomorrow", "TOMORROWEVENT", ms("2026-11-11", 9), ms("2026-11-11", 10))
        val ctx = ChatContext.build(db, "2026-11-10", ms("2026-11-10", 14))
        val lines = ctx.lines()
        assertTrue(lines.single { it.contains("PASTEVENT") }.endsWith("(already ended)"), ctx)
        assertTrue(lines.single { it.contains("EDGEEVENT") }.contains("(already ended)"), "endAt == now counts as ended: $ctx")
        assertFalse(lines.single { it.contains("LATEREVENT") }.contains("already ended"), ctx)
        assertFalse(lines.single { it.contains("TOMORROWEVENT") }.contains("already ended"), ctx)
    }

    @Test
    fun test_AC_P11A10_event_one_ms_before_end_not_marked() {
        val db = memDb()
        ev(db, "e", "ALMOSTDONE", ms("2026-11-10", 13), ms("2026-11-10", 14))
        val ctx = ChatContext.build(db, "2026-11-10", ms("2026-11-10", 14) - 1)
        assertFalse(ctx.contains("already ended"), ctx)
    }

    @Test
    fun test_AC_P11A10_all_day_event_today_not_marked() {
        val db = memDb()
        ev(db, "ad", "ALLDAYTODAY", ms("2026-11-10", 0), ms("2026-11-11", 0), allDay = 1)
        val ctx = ChatContext.build(db, "2026-11-10", ms("2026-11-10", 23, 59))
        assertFalse(ctx.lines().single { it.contains("ALLDAYTODAY") }.contains("already ended"), ctx)
    }

    @Test
    fun test_AC_P11A10_chat_screen_passes_real_clock() {
        // Phase 17: chat runs on the HP; the chat path is the server ChatRunner (clock-driven), not AppServices
        val src = File("../../server/src/main/kotlin/dev/tim/assistant/server/chat/ChatRunner.kt").let {
            if (it.exists()) it else File("server/src/main/kotlin/dev/tim/assistant/server/chat/ChatRunner.kt")
        }.readText()
        assertTrue(src.contains("clock()"), "the chat path must read the clock")
        val callLine = src.lines().single { it.contains("ChatContext.build(") }
        assertTrue(callLine.contains("now"), "build call must pass the current instant: $callLine")
        assertEquals(1, Regex("""ChatContext\.build\(""").findAll(src).count())
    }
}
