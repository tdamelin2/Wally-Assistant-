package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TargetsTest {
    private val rig = ChatRig()
    private val targets = Targets(rig.repo) { rig.now }

    @AfterTest
    fun close() = rig.close()

    private fun names(kind: String) = targets.candidates(kind).map { it.name }

    @Test
    fun openTasksOnlyAndEmptyTokens() = runBlocking<Unit> {
        rig.task("a", "Call the dentist", "2026-11-12")
        rig.task("b", "Old thing", done = true)
        assertEquals(listOf("Call the dentist"), names("task"))
        assertEquals("", targets.candidates("task").single().token)
    }

    @Test
    fun examsAreUpcomingOnlyInDateOrder() = runBlocking<Unit> {
        rig.exam("1", "Past exam", "2026-11-10")
        rig.exam("2", "Later exam", "2026-12-01")
        rig.exam("3", "Today exam", TODAY, "14:00")
        assertEquals(listOf("Today exam", "Later exam"), names("exam"))
    }

    @Test
    fun mealsAreTodayOnlyNewestFirstAndCutAtSixty() = runBlocking<Unit> {
        rig.meal("1", "early pasta", CHAT_T0 - 3_600_000)
        rig.meal("2", "x".repeat(80), CHAT_T0)
        rig.meal("3", "yesterday soup", CHAT_T0 - 86_400_000, "2026-11-10")
        val list = names("meal")
        assertEquals(2, list.size)
        assertEquals("x".repeat(60), list[0])
        assertEquals("early pasta", list[1])
    }

    @Test
    fun spendingNameHasCategoryAmountAndNote() = runBlocking<Unit> {
        rig.spend("1", 3500, "Food", "lunch with a very long note that goes well past forty characters in total")
        rig.spend("2", 1000, "Transport", null, updatedAt = 2000)
        rig.spend("3", 999, "Fun", "old", date = "2026-11-10")
        val list = names("spending")
        assertEquals(listOf("Transport 10.00 ₪", "Food 35.00 ₪ lunch with a very long note that goes we"), list)
    }

    @Test
    fun checkinAndJournalOfToday() = runBlocking<Unit> {
        rig.checkin()
        rig.checkin("2026-11-10")
        rig.journal("1", "first entry", CHAT_T0 - 1000)
        rig.journal("2", "second entry", CHAT_T0)
        rig.journal("3", "yesterday entry", CHAT_T0 - 86_400_000)
        assertEquals(listOf("Check-in $TODAY"), names("checkin"))
        assertEquals(listOf("second entry", "first entry"), names("journal"))
        assertTrue(targets.candidates("nonsense").isEmpty())
    }

    @Test
    fun taskResolveOneManyNone() = runBlocking<Unit> {
        rig.task("a", "Call the dentist")
        rig.task("b", "Call mum")
        rig.task("c", "Buy milk")
        assertEquals("Buy milk", (targets.resolve("task", "milk") as Resolved.One).choice.name)
        assertEquals(2, (targets.resolve("task", "call") as Resolved.Many).choices.size)
        assertEquals(Resolved.None, targets.resolve("task", "swimming"))
        assertEquals(Resolved.None, targets.resolve("task", " "))
    }

    @Test
    fun manyIsCappedAtFive() = runBlocking<Unit> {
        for (i in 1..7) rig.task("t$i", "Task number $i")
        assertEquals(5, (targets.resolve("task", "task") as Resolved.Many).choices.size)
    }

    @Test
    fun logKindWithNoEntriesIsNone() {
        for (k in listOf("meal", "spending", "checkin", "journal")) assertEquals(Resolved.None, targets.resolve(k, "that"), k)
    }

    @Test
    fun genericQueryPicksTheNewestLogEntry() = runBlocking<Unit> {
        rig.meal("1", "early pasta", CHAT_T0 - 3_600_000)
        rig.meal("2", "late salad", CHAT_T0)
        for (q in listOf("that", "it", "the last one", "meal", "")) {
            assertEquals("late salad", (targets.resolve("meal", q) as Resolved.One).choice.name, q)
        }
        rig.spend("1", 500, "Food", null)
        assertEquals("Food 5.00 ₪", (targets.resolve("spending", "spending") as Resolved.One).choice.name)
        rig.checkin()
        assertEquals("Check-in $TODAY", (targets.resolve("checkin", "check-in") as Resolved.One).choice.name)
        rig.journal("1", "a note", CHAT_T0)
        assertEquals("a note", (targets.resolve("journal", "journal entry") as Resolved.One).choice.name)
    }

    @Test
    fun nameMatchIsUsedForLogKinds() = runBlocking<Unit> {
        rig.meal("1", "pasta with cheese", CHAT_T0 - 3_600_000)
        rig.meal("2", "late salad", CHAT_T0)
        assertEquals("pasta with cheese", (targets.resolve("meal", "pasta") as Resolved.One).choice.name)
    }

    @Test
    fun unmatchedNameWithOneCandidateStillGivesChips() = runBlocking<Unit> {
        rig.meal("1", "lunch", CHAT_T0)
        val r = targets.resolve("spending", "the taxi")
        assertEquals(Resolved.None, r)
        rig.spend("1", 3500, "Food", "lunch")
        val many = targets.resolve("spending", "the taxi") as Resolved.Many
        assertEquals(1, many.choices.size)
    }
}
