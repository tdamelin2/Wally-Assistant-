package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Phase 20 round 2: move wordings, command-word typos, multi-item moves and "everything" day overviews. All wordings are written fresh here. */
class Round2RouterTest {
    private val today = LocalDate.of(2026, 11, 12)
    private val rig = ChatRig()
    private val hour = 3_600_000L

    @AfterTest
    fun close() = rig.close()

    private fun route(text: String) = IntentRouter.route(text, today)

    private fun assertMatch(text: String, intent: IntentName, slots: Map<String, String>) {
        assertEquals(RouteResult.Match(intent, slots), route(text), text)
    }

    private fun assertNone(vararg texts: String) {
        for (t in texts) assertEquals(RouteResult.NoMatch, route(t), t)
    }

    private fun ladder() = Ladder(Targets(rig.repo) { rig.now }, AskAnswers(rig.repo) { rig.now }, rig.read, rig.chats, null) { rig.now }

    // ---- B-28: move wordings ----

    @Test
    fun moveWordingsGiveTheSingleTargetMove() {
        assertMatch("move task invoices to friday", IntentName.MOVE_TASK, mapOf("name" to "invoices", "date" to "2026-11-13"))
        assertMatch("move the task call plumber to tomorrow", IntentName.MOVE_TASK, mapOf("name" to "call plumber", "date" to "2026-11-13"))
        assertMatch("reschedule invoices task for tomorrow", IntentName.MOVE_TASK, mapOf("name" to "invoices", "date" to "2026-11-13"))
        assertMatch("push the invoices task back to monday", IntentName.MOVE_TASK, mapOf("name" to "invoices", "date" to "2026-11-16"))
        assertMatch("reschedule the laundry for friday", IntentName.MOVE_TASK, mapOf("name" to "laundry", "date" to "2026-11-13", "loose" to "1"))
        assertMatch("push laundry back to tomorrow", IntentName.MOVE_TASK, mapOf("name" to "laundry", "date" to "2026-11-13", "loose" to "1"))
        assertMatch("please move laundry over to friday", IntentName.MOVE_TASK, mapOf("name" to "laundry", "date" to "2026-11-13", "loose" to "1"))
        assertMatch("move the laundry from monday to friday", IntentName.MOVE_TASK, mapOf("name" to "laundry", "date" to "2026-11-13", "loose" to "1"))
        assertMatch("reschedule my physics exam for friday", IntentName.MOVE_EXAM, mapOf("name" to "physics", "date" to "2026-11-13"))
    }

    @Test
    fun everyExistingMoveRefusalStays() {
        assertNone(
            "move task invoices", "move task to friday", "move it to friday", "move the laundry to the shared folder",
            "move laundry to friday at 9am", "move the task invoices to someday", "move the laundry to friday or monday",
            "could you move laundry to friday",
        )
    }

    @Test
    fun aMoveStillGivesTheCardOnlyForOneWholeNameMatch() = runBlocking<Unit> {
        rig.task("t1", "Invoices")
        rig.task("t2", "Laundry")
        rig.task("t3", "Laundry")
        val ladder = ladder()
        rig.chats.create("c1")
        val one = assertNotNull(ladder.tryCode("c1", "m1", "move task invoices to friday", null, rig.executor("c1", "r:m1")))
        assertEquals(ReplyTemplates.card(), one.text)
        val loose = assertNotNull(ladder.tryCode("c1", "m2", "reschedule invoices for friday", null, rig.executor("c1", "r:m2")))
        assertEquals(ReplyTemplates.card(), loose.text)
        // two tasks with the same name: the loose move does nothing
        assertNull(ladder.tryCode("c1", "m3", "push laundry back to friday", null, rig.executor("c1", "r:m3")))
    }

    // ---- B-29: typo tolerance for command words ----

    @Test
    fun aLeadingAdIsAdd() {
        assertMatch("ad task buy milk", IntentName.CREATE_TASK, mapOf("title" to "buy milk"))
        assertMatch("ad a task: call mom tomorrow", IntentName.CREATE_TASK, mapOf("title" to "call mom", "dueDate" to "2026-11-13"))
        assertMatch("ad meal: pasta salad", IntentName.ADD_MEAL, mapOf("type" to "MEAL", "text" to "pasta salad"))
        assertNone("ad campaign task ideas", "ad hoc meeting notes", "ad to the wall")
    }

    @Test
    fun aDoubledCommandWordIsOne() {
        assertMatch("add task task send the form", IntentName.CREATE_TASK, mapOf("title" to "send the form"))
        assertMatch("add a task task send the form", IntentName.CREATE_TASK, mapOf("title" to "send the form"))
        assertMatch("add add task send the form", IntentName.CREATE_TASK, mapOf("title" to "send the form"))
        assertMatch("create create task send the form", IntentName.CREATE_TASK, mapOf("title" to "send the form"))
        // a doubled word that is part of the title is not touched
        assertMatch("add task read task list", IntentName.CREATE_TASK, mapOf("title" to "read task list"))
    }

    @Test
    fun aFirstWordButBeforeAnObjectIsBuy() {
        assertMatch("add task but milk", IntentName.CREATE_TASK, mapOf("title" to "buy milk"))
        assertMatch("add task but a birthday gift tomorrow", IntentName.CREATE_TASK, mapOf("title" to "buy a birthday gift", "dueDate" to "2026-11-13"))
        assertMatch("remind me to but new shoes", IntentName.CREATE_TASK, mapOf("title" to "buy new shoes"))
        // a clause stays as it is
        assertMatch("add task but I think it is fine", IntentName.CREATE_TASK, mapOf("title" to "but I think it is fine"))
        assertMatch("add task but they are late", IntentName.CREATE_TASK, mapOf("title" to "but they are late"))
        assertMatch("add task but the room is small", IntentName.CREATE_TASK, mapOf("title" to "but the room is small"))
    }

    @Test
    fun typoFixesNeverTouchFreeText() {
        assertMatch("journal: ad task task but milk", IntentName.ADD_JOURNAL, mapOf("text" to "ad task task but milk"))
        assertMatch("I dreamt that ad task task", IntentName.ADD_DREAM, mapOf("text" to "I dreamt that ad task task"))
        // the leading ad is fixed, the clause after but is not
        assertMatch("ad task but I think", IntentName.CREATE_TASK, mapOf("title" to "but I think"))
    }

    // ---- B-30: several items or a pronoun ----

    @Test
    fun aMoveOfSeveralItemsOrAPronounIsNeverAMatch() {
        assertNone(
            "move them to friday", "move all my tasks to friday", "move these to tomorrow", "move those to monday", "move everything to friday",
            "move both to friday", "reschedule my tasks for tomorrow", "push each one to monday", "move the three tasks to friday",
            "could you move them to friday", "move them",
        )
        for (t in listOf("move them to friday", "Move all my tasks to Friday.", "please push these to tomorrow", "can you move everything to monday")) {
            assertTrue(IntentRouter.multiMove(t), t)
        }
        for (t in listOf("move the report task to friday", "move laundry to friday", "move it to friday")) assertFalse(IntentRouter.multiMove(t), t)
    }

    @Test
    fun ladderDoesNothingForAMultiMoveEvenWithATaskNamedLikeIt() = runBlocking<Unit> {
        rig.task("t1", "Them")
        rig.task("t2", "All my tasks")
        val ladder = ladder()
        rig.chats.create("c1")
        for ((i, text) in listOf("move them to tomorrow", "move all my tasks to friday", "move these to monday", "move them").withIndex()) {
            assertNull(ladder.tryCode("c1", "m$i", text, null, rig.executor("c1", "r:m$i")), text)
        }
        assertTrue(rig.pull().isEmpty() || rig.pull().none { it.table == "approval" })
    }

    // ---- B-31: everything ----

    @Test
    fun everythingTodayIsTheTodayOverview() {
        for (t in listOf("everything today", "show me everything for today", "what's everything today", "all I have today", "show me all I have on today", "Everything today?")) {
            assertMatch(t, IntentName.ASK_TODAY, emptyMap())
        }
    }

    @Test
    fun everythingForAnotherSingleDayCarriesTheDay() {
        assertMatch("what's everything tomorrow", IntentName.ASK_TODAY, mapOf("day" to "2026-11-13"))
        assertMatch("all I have tomorrow", IntentName.ASK_TODAY, mapOf("day" to "2026-11-13"))
        assertMatch("show me everything for tomorrow", IntentName.ASK_TODAY, mapOf("day" to "2026-11-13"))
        assertMatch("everything I have got on monday", IntentName.ASK_TODAY, mapOf("day" to "2026-11-16"))
    }

    @Test
    fun everythingNeedsOneFutureDayAndNeverAnswersBare() {
        assertNone(
            "everything", "show me everything", "all I have", "what's everything", "everything yesterday", "everything last week",
            "everything today and tomorrow", "everything this week", "all today",
        )
    }

    @Test
    fun dayOverviewShowsThatDaysEventsAndDueTasks() = runBlocking<Unit> {
        rig.event("e1", "Team lunch", CHAT_T0 + 24 * hour, CHAT_T0 + 25 * hour)
        rig.event("e2", "Today thing", CHAT_T0, CHAT_T0 + hour)
        rig.task("a", "Pay rent", "2026-11-12")
        rig.task("b", "Due today only", "2026-11-11")
        val ask = AskAnswers(rig.repo) { rig.now }
        val t = ask.dayOverview("2026-11-12")
        assertTrue(t.startsWith("Tomorrow: ") && t.contains("Team lunch"), t)
        assertTrue(t.contains("Tasks due tomorrow: Pay rent."), t)
        assertFalse(t.contains("Today thing") || t.contains("Due today only"), t)
        val empty = ask.dayOverview("2026-11-20")
        assertTrue(empty.startsWith("Nothing on your calendar") && empty.contains("No tasks due"), empty)
        assertEquals(ask.todayOverview(), ask.dayOverview("2026-11-11"))
    }

    @Test
    fun ladderAnswersEverythingTomorrowWithoutAModel() = runBlocking<Unit> {
        rig.task("a", "Pay rent", "2026-11-12")
        val ladder = ladder()
        rig.chats.create("c1")
        val out = assertNotNull(ladder.tryCode("c1", "m1", "what's everything tomorrow", null, rig.executor("c1", "r:m1")))
        assertTrue(out.text.contains("Pay rent"), out.text)
        assertEquals("code", out.brain)
    }
}
