package dev.tim.assistant.server.chat

import dev.tim.assistant.db.Dream
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.chat.ladder.event
import dev.tim.assistant.server.chat.ladder.exam
import dev.tim.assistant.server.chat.ladder.meal
import dev.tim.assistant.server.chat.ladder.spend
import dev.tim.assistant.server.chat.ladder.task
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The two data profiles of the small models. The HP one must stay byte-for-byte what it was before the phone existed. */
class LiteContextTest {
    private val rig = ChatRig()
    private val hour = 3_600_000L
    private val day = 24 * hour

    @AfterTest
    fun close() = rig.close()

    private fun hp() = LiteContext.build(rig.sdb.db, CHAT_T0, false, 1_200)
    private fun phone(dreams: Boolean = false, max: Int = 3_000) = LiteContext.build(rig.sdb.db, CHAT_T0, dreams, max, LiteContext.Profile.PHONE)

    @Test
    fun hpOutputIsPinnedByteForByte() = runBlocking<Unit> {
        rig.task("a", "Call the dentist", "2026-11-12")
        rig.event("e1", "Lecture", CHAT_T0 + hour, CHAT_T0 + 2 * hour)
        rig.event("e2", "Next week thing", CHAT_T0 + 3 * day, CHAT_T0 + 3 * day + hour)
        rig.meal("1", "pasta", CHAT_T0)
        rig.spend("1", 3500, "Food", "lunch")
        val expected = "Today is 2026-11-11 (Israel time).\n" +
            "Events today:\n- 13:00-14:00 Lecture\n" +
            "Open tasks:\n- due 2026-11-12 Call the dentist\n" +
            "Check-in today: none yet\n" +
            "Logged today: 1 meals, 1 spending entries\n"
        assertEquals(expected, hp())
        assertEquals(hp(), LiteContext.build(rig.sdb.db, CHAT_T0, false, 1_200, LiteContext.Profile.HP))
    }

    @Test
    fun phoneListsSevenDaysTasksMealsAndSpendingWithAmounts() = runBlocking<Unit> {
        rig.event("e1", "Lecture", CHAT_T0 + hour, CHAT_T0 + 2 * hour)
        rig.event("e2", "Dentist", CHAT_T0 + 5 * day, CHAT_T0 + 5 * day + hour)
        rig.event("e3", "Too far away", CHAT_T0 + 8 * day, CHAT_T0 + 8 * day + hour)
        rig.exam("x1", "Statistics exam", "2026-11-15", "09:00")
        rig.exam("x2", "Far exam", "2026-12-20")
        rig.task("a", "Call the dentist", "2026-11-12")
        rig.task("b", "Done thing", done = true)
        rig.meal("1", "pasta", CHAT_T0)
        rig.spend("1", 3500, "Food", "lunch")
        rig.spend("2", 2050, "Transport", null, date = "2026-11-09")
        rig.spend("3", 9900, "Fun", "last week", date = "2026-11-07")
        val text = phone()
        assertTrue(text.startsWith("Today is Wednesday 2026-11-11 (Israel time).\n"), text)
        assertTrue("Events, next 7 days:" in text && "Wed 2026-11-11 13:00-14:00 Lecture" in text && "Dentist" in text, text)
        assertFalse("Too far away" in text, text)
        assertTrue("Exams, next 7 days:\n- 2026-11-15 09:00 Statistics exam" in text && "Far exam" !in text, text)
        assertTrue("- due 2026-11-12 Call the dentist" in text && "Done thing" !in text, text)
        assertTrue("Meals today (1):\n- meal pasta" in text, text)
        assertTrue("Spending today (1 entries, total 35 ₪):\n- 35 ₪ Food lunch" in text, text)
        // the week starts on Sunday 2026-11-08: Monday's 20.50 plus today's 35, not Saturday's 99
        assertTrue(text.endsWith("Spending this week (Sunday to today): 55.50 ₪\n"), text)
    }

    @Test
    fun phoneOpenTasksAreCappedAtTwelve() = runBlocking<Unit> {
        for (i in 1..20) rig.task("t$i", "Task number $i", "2026-11-%02d".format(10 + i % 9))
        val text = phone()
        assertEquals(12, text.lines().count { it.startsWith("- due ") })
    }

    @Test
    fun longNamesNeverCutTheTotalsOrTheBudget() = runBlocking<Unit> {
        for (i in 1..40) rig.task("t$i", "A task with a very long name number $i ".repeat(3), "2026-11-1${i % 10}")
        for (i in 1..30) rig.event("e$i", "An event with a very long name number $i ".repeat(3), CHAT_T0 + i * hour, CHAT_T0 + (i + 1) * hour)
        for (i in 1..20) rig.meal("m$i", "meal text that is long ".repeat(5), CHAT_T0 + i)
        for (i in 1..20) rig.spend("s$i", 100L * i, "Food", "a long note about this purchase number $i")
        val text = phone()
        assertTrue(text.length <= 3_000, text.length.toString())
        assertTrue("Spending today (20 entries, total 210 ₪):" in text, text)
        assertTrue("Meals today (20):" in text, text)
        assertTrue(text.endsWith("Spending this week (Sunday to today): 210 ₪\n"), text)
        assertTrue("Check-in today: none yet\n" in text)
    }

    @Test
    fun theDreamLineNeedsTheDreamGateAndKeepsTheBudget() = runBlocking<Unit> {
        rig.seed(toSyncRow(Dream("dream:1", 1000, 0, 0, "SYNCED", "2026-11-10", "SECRETDREAMTEXT flying", 4, 0, "", null, null)))
        assertFalse("SECRETDREAMTEXT" in phone())
        val dream = phone(dreams = true)
        assertTrue("Last dream: " in dream && "SECRETDREAMTEXT" in dream, dream)
        for (i in 1..40) rig.task("t$i", "A task with a very long name number $i ".repeat(3), "2026-11-1${i % 10}")
        val full = phone(dreams = true)
        assertTrue(full.length <= 3_000)
        assertTrue("Last dream: " in full && full.endsWith("\n"), full)
    }

    @Test
    fun theFinalCutDoesNotSplitASurrogatePair() = runBlocking<Unit> {
        rig.spend("1", 3500, "Food", "x")
        val text = phone(max = 60)
        assertTrue(text.length <= 60)
        assertFalse(text.isNotEmpty() && text.last().isHighSurrogate())
    }
}
