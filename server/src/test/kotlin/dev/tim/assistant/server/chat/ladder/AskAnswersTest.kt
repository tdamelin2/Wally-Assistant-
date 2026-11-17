package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AskAnswersTest {
    private val rig = ChatRig()
    private val ask = AskAnswers(rig.repo) { rig.now }
    private val hour = 3_600_000L

    @AfterTest
    fun close() = rig.close()

    @Test
    fun emptyDayAnswers() {
        val t = ask.todayOverview()
        assertTrue(t.contains("Nothing on your calendar today"), t)
        assertTrue(t.contains("no open tasks"), t)
        assertTrue(t.contains("haven't done today's check-in"), t)
    }

    @Test
    fun todayListsEventsTimesTasksAndCheckin() = runBlocking<Unit> {
        rig.event("e1", "Team lunch", CHAT_T0, CHAT_T0 + hour)
        rig.event("e2", "Holiday", CHAT_T0 - 20 * hour, CHAT_T0 + 8 * hour, allDay = true)
        rig.event("e3", "Tomorrow thing", CHAT_T0 + 24 * hour, CHAT_T0 + 25 * hour)
        rig.task("a", "Call the dentist", "2026-11-13")
        rig.task("b", "Buy milk")
        rig.checkin()
        val t = ask.todayOverview()
        assertTrue(t.contains("12:00-13:00 Team lunch"), t)
        assertTrue(t.contains("all day Holiday"), t)
        assertFalse(t.contains("Tomorrow thing"), t)
        assertTrue(t.contains("2 open tasks") && t.contains("Call the dentist"), t)
        assertTrue(t.contains("check-in is done"), t)
    }

    @Test
    fun tasksByDueDateAndWindows() = runBlocking<Unit> {
        rig.task("a", "Due today", "2026-11-11")
        rig.task("b", "Due tomorrow", "2026-11-12")
        rig.task("c", "Due saturday", "2026-11-14")
        rig.task("d", "Due next week", "2026-11-16")
        rig.task("e", "No date")
        rig.task("f", "Finished", "2026-11-11", done = true)
        val all = ask.tasks(null)
        assertTrue(all.indexOf("Due today") < all.indexOf("Due tomorrow") && all.contains("No date"), all)
        assertFalse(all.contains("Finished"), all)
        assertEquals("Tasks due today: Due today (due today).", ask.tasks("today"))
        assertTrue(ask.tasks("tomorrow").contains("Due tomorrow") && !ask.tasks("tomorrow").contains("Due today"))
        val week = ask.tasks("week")
        assertTrue(week.contains("Due today") && week.contains("Due saturday"), week)
        assertFalse(week.contains("Due next week") || week.contains("No date"), week)
    }

    @Test
    fun tasksShowAtMostEightAndNoTasksText() = runBlocking<Unit> {
        assertEquals("You have no open tasks.", ask.tasks(null))
        assertEquals("You have no tasks due today.", ask.tasks("today"))
        for (i in 1..10) rig.task("t$i", "Task $i", "2026-11-2$i".take(10))
        val t = ask.tasks(null)
        assertTrue(t.contains("And 2 more."), t)
    }

    @Test
    fun eventsOfADayIncludeSpanningAndAllDay() = runBlocking<Unit> {
        rig.event("e1", "Late night", CHAT_T0 + 11 * hour, CHAT_T0 + 14 * hour)
        rig.event("e2", "Trip", CHAT_T0 - 30 * hour, CHAT_T0 + 30 * hour, allDay = true)
        val tomorrow = ask.events("2026-11-12")
        assertTrue(tomorrow.startsWith("Tomorrow: "), tomorrow)
        assertTrue(tomorrow.contains("Trip") && tomorrow.contains("Late night"), tomorrow)
        assertEquals("Nothing on your calendar on Monday 16 November.", ask.events("2026-11-16"))
    }

    @Test
    fun spendingSinceSundayInShekels() = runBlocking<Unit> {
        rig.spend("1", 3500, "Food", null, "2026-11-11")
        rig.spend("2", 1250, "Food", null, "2026-11-08")
        rig.spend("3", 2000, "Transport", null, "2026-11-09")
        rig.spend("4", 9900, "Fun", null, "2026-11-07")
        val t = ask.spendingWeek()
        assertTrue(t.contains("67.50 ₪"), t)
        assertTrue(t.contains("food 47.50 ₪") && t.contains("transport 20 ₪"), t)
        assertFalse(t.contains("fun"), t)
    }

    @Test
    fun noSpendingText() {
        assertEquals("You haven't spent anything since Sunday.", ask.spendingWeek())
    }

    @Test
    fun noAnswerShowsAnId() = runBlocking<Unit> {
        rig.event("e1", "Team lunch", CHAT_T0, CHAT_T0 + hour)
        rig.task("a", "Call the dentist", "2026-11-13")
        rig.spend("1", 3500, "Food", "lunch")
        for (t in listOf(ask.todayOverview(), ask.tasks(null), ask.events(TODAY), ask.spendingWeek())) assertFalse(ID_LIKE.containsMatchIn(t), t)
    }
}
