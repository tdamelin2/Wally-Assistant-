package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.SendResult
import dev.tim.assistant.server.chat.decisionJson
import dev.tim.assistant.server.chat.parseExtras
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * The ladder end to end through the chat service: the clock is Wednesday 2026-11-11 12:00 (Jerusalem), the embedder is a fake that
 * places every text on a circle, and the "model" is the HP stub that answers "From the model." to anything that reaches it.
 */
class LadderTest {
    // intents sit 60 degrees apart, three seeds each; a query is placed by hand with `place`
    private val placed = HashMap<String, Double>()
    private val queries = AtomicInteger()
    private val embedder = TextEmbedder { text, _ ->
        if (text.startsWith("seed:")) vec(text.removePrefix("seed:").toDouble())
        else {
            queries.incrementAndGet()
            placed[text]?.let { vec(it) }
        }
    }
    private val seedAngles = listOf(
        IntentName.ASK_TODAY to 0.0, IntentName.ASK_TASKS to 60.0, IntentName.ASK_EVENTS to 120.0, IntentName.ASK_SPENDING_WEEK to 180.0,
        IntentName.CREATE_TASK to 240.0, IntentName.ADD_SPENDING to 300.0,
    )
    private val seeds = seedAngles.flatMap { (intent, base) -> listOf(0.0, 3.0, 6.0).map { Seed(intent, "seed:${base + it}") } }
    private val store = SeedStore(embedder, CoroutineScope(Dispatchers.Unconfined + Job()), seeds).also { it.startSeeding() }
    private val rig = ChatRig(sorter = Sorter(store, embedder, true))

    init {
        rig.hpFake.reply = { 200 to chatBody(decisionJson("answer", "From the model.")) }
    }

    @AfterTest
    fun close() = rig.close()

    private fun vec(deg: Double): FloatArray = Math.toRadians(deg).let { floatArrayOf(cos(it).toFloat(), sin(it).toFloat()) }

    private fun place(text: String, deg: Double): String {
        placed[text] = deg
        return text
    }

    private suspend fun send(id: String, text: String, choice: ChoiceDto? = null, r: ChatRig = rig): SendResult.Ok {
        r.chats.create("c1")
        return assertIs<SendResult.Ok>(r.service.send("c1", SendMessageRequest(id, text, CHAT_T0, null, choice)))
    }

    private fun brainOf(id: String) = parseExtras(rig.chats.message("r:$id")!!.card_json).brain

    private suspend fun count(table: String) = rig.pull().count { it.table == table }

    private suspend fun writes() = rig.pull().size

    private fun assertNoId(text: String) = assertFalse(ID_LIKE.containsMatchIn(text), text)

    /** The message went to the model stub and nothing was saved. */
    private suspend fun assertPassedUp(id: String, text: String) {
        val before = rig.hpFake.bodies.size
        val saved = writes()
        val r = send(id, text)
        assertEquals(before + 1, rig.hpFake.bodies.size, "model not reached: $text")
        assertEquals("hp", brainOf(id), text)
        assertEquals("From the model.", r.response.reply.text)
        assertEquals(saved, writes(), "something was saved: $text")
        assertTrue(r.response.reply.actions.isEmpty() && r.response.reply.cards.isEmpty(), text)
    }

    // ---- every intent end to end -----------------------------------------------------------------------

    @Test
    fun everyAddIsSavedByCodeWithAnUndoAndNoModelCall() = runBlocking<Unit> {
        val adds = listOf(
            "add task call the dentist tomorrow" to "task", "I spent 35 on lunch" to "spending", "log lunch pasta salad" to "meal",
            "check-in: mood 4 energy 3 sleep 3 stress 2" to "checkin", "journal: a quiet day" to "journal", "I dreamt that I flew" to "dream",
        )
        for ((i, pair) in adds.withIndex()) {
            val (text, table) = pair
            val r = send("m$i", text)
            assertEquals(1, count(table), text)
            assertEquals(1, r.response.reply.actions.size, text)
            assertEquals("code", brainOf("m$i"), text)
            assertNoId(r.response.reply.text)
            assertTrue(r.response.reply.text.isNotBlank())
        }
        assertEquals(0, rig.hpFake.bodies.size + rig.fake.bodies.size)
        assertEquals(0, queries.get())
    }

    @Test
    fun theThreeEditsOpenACardAndTheFindAnswersWithTheName() = runBlocking<Unit> {
        rig.task("t1", "Write report")
        rig.exam("e1", "Algebra", "2026-12-02")
        rig.seedCourse("c9", "Algebra course")
        val done = send("m1", "mark the report as done")
        assertEquals(1, done.response.reply.cards.size)
        assertEquals(ReplyTemplates.card(), done.response.reply.text)
        val moved = send("m2", "move the report task to friday")
        assertEquals(1, moved.response.reply.cards.size)
        val exam = send("m3", "move the algebra exam to 2026-12-03 at 9am")
        assertEquals(1, exam.response.reply.cards.size)
        val found = send("m4", "find my algebra course")
        assertEquals(ReplyTemplates.found("Algebra course"), found.response.reply.text)
        assertEquals(0, rig.hpFake.bodies.size + rig.fake.bodies.size)
        // the cards wait: the task itself is unchanged until Tim says yes
        assertEquals(null, rig.str(rig.pull().single { it.table == "task" }, "doneAt"))
        for (id in listOf("m1", "m2", "m3", "m4")) assertEquals("code", brainOf(id))
    }

    @Test
    fun theFourReadOnlyAnswersComeFromCodeAndWriteNothing() = runBlocking<Unit> {
        rig.event("ev1", "Dentist visit", CHAT_T0 + 3_600_000, CHAT_T0 + 7_200_000)
        rig.task("t1", "Write report", "2026-11-12")
        rig.spend("s1", 3500, "Food", "lunch")
        val saved = writes()
        val today = send("m1", "what's on today")
        assertTrue("Dentist" in today.response.reply.text, today.response.reply.text)
        val tasks = send("m2", "what are my tasks")
        assertTrue("Write report" in tasks.response.reply.text, tasks.response.reply.text)
        val events = send("m3", "what's on my calendar today")
        assertTrue("Dentist" in events.response.reply.text, events.response.reply.text)
        val spending = send("m4", "how much have I spent this week")
        assertTrue("35" in spending.response.reply.text, spending.response.reply.text)
        for (r in listOf(today, tasks, events, spending)) assertNoId(r.response.reply.text)
        assertEquals(saved, writes())
        assertEquals(0, rig.hpFake.bodies.size)
    }

    // ---- Round 1: kind-less move and find ----

    @Test
    fun aMoveWithoutTheWordTaskOpensACardOnlyForOneWholeNameTask() = runBlocking<Unit> {
        rig.task("t1", "Laundry day")
        rig.task("t2", "Water the plants")
        val moved = send("m1", "move laundry day to friday")
        assertEquals(1, moved.response.reply.cards.size)
        assertEquals(ReplyTemplates.card(), moved.response.reply.text)
        assertEquals("code", brainOf("m1"))
        assertEquals(0, rig.hpFake.bodies.size)
        // a one-word part of a name is not a whole-name match
        assertPassedUp("m2", "move laundry to friday")
        // no such task
        assertPassedUp("m3", "push the dentist call to friday")
    }

    @Test
    fun aKindlessMoveGivesWayToAnExamOfTheSameNameAndToTwoTasks() = runBlocking<Unit> {
        rig.task("t1", "Algebra homework")
        rig.exam("e1", "Algebra homework", "2026-12-02")
        assertPassedUp("m1", "move algebra homework to friday")
        rig.task("t2", "Read the book")
        rig.task("t3", "Read the book")
        assertPassedUp("m2", "move read the book to friday")
    }

    @Test
    fun aKindlessFindAnswersOnlyWhenExactlyOneItemMatches() = runBlocking<Unit> {
        rig.task("t1", "Physics lab report")
        rig.exam("e1", "Chemistry", "2026-12-02")
        rig.seedGoal("g1", "Chemistry revision")
        val one = send("m1", "find my physics")
        assertEquals(ReplyTemplates.found("Physics lab report"), one.response.reply.text)
        assertEquals("code", brainOf("m1"))
        // two items (an exam and a goal) match: pass up
        assertPassedUp("m2", "find my chemistry")
        // nothing matches: pass up
        assertPassedUp("m3", "open the garage door")
    }
    // ---- inputs the code rungs must refuse ---------------------------------------------------------------

    @Test
    fun blockedInputsReachTheModelWithTheSorterNeverCalledAndNothingWritten() = runBlocking<Unit> {
        val blocked = listOf(
            "I didn't spend 35 on lunch", // negation
            "I spent 35 and 40 on lunch", // two amounts
            "add task buy milk. add task call mom", // two commands
            "add task " + "very long title ".repeat(20), // long
        )
        for ((i, text) in blocked.withIndex()) assertPassedUp("m$i", text)
        assertEquals(0, queries.get(), "the sorter was called for a blocked input")
    }

    @Test
    fun anOtherDayStatementReachesTheModelWithoutTheSorterAndWritesNothing() = runBlocking<Unit> {
        // a spending for yesterday is now a card (PLAN 20.5); a meal without a time still goes up
        assertPassedUp("m1", "I ate pasta for lunch yesterday")
        assertPassedUp("m2", "check-in for Monday: mood 4 energy 3 sleep 3 stress 2")
        // a day word code cannot place (ago) drops the sorter result even though the sorter agrees
        assertPassedUp("m3", place("how much did I spend a while ago", 181.0))
    }

    @Test
    fun aDayQuestionIsAnsweredByCodeForThatDay() = runBlocking<Unit> {
        rig.spend("s1", 3500, "Food", "lunch", "2026-11-04")
        val r = send("m1", "how much did I spend last week")
        assertEquals("code", brainOf("m1"))
        assertEquals("Last week you spent 35 ₪: food 35 ₪.", r.response.reply.text)
        val r2 = send("m2", "how much did I spend on food in november")
        assertEquals("In November you spent 35 ₪ on food.", r2.response.reply.text)
        assertEquals(0, rig.hpFake.bodies.size)
    }

    @Test
    fun aQuestionIsAnsweredOnlyByAnAskRow() = runBlocking<Unit> {
        assertPassedUp("m1", "did I spend 35 on lunch?")
        assertPassedUp("m2", place("should I put milk on my list", 241.0)) // sorter says create_task: never accepted
        assertEquals(0, count("spending") + count("task"))
        // a reworded data question is answered by the sorter path
        val r = send("m3", place("run down my day for me", 1.0))
        assertEquals("sorter", brainOf("m3"))
        assertTrue(r.response.reply.text.isNotBlank())
        assertNoId(r.response.reply.text)
        assertEquals(2, rig.hpFake.bodies.size)
    }

    @Test
    fun theSorterPathNeverWritesWhateverTheEmbedderReturns() = runBlocking<Unit> {
        val before = writes()
        assertPassedUp("m1", place("I paid for lunch, thirty five bucks", 301.0)) // lands on add_spending
        // lands on create_task: Wally asks with buttons (PLAN 20.6) and still saves nothing
        val asked = send("m2", place("put milk on my list please", 242.0))
        assertEquals("ask", brainOf("m2"))
        assertTrue(asked.response.reply.text.startsWith("Did you mean"))
        assertEquals(before, writes())
        assertTrue(queries.get() >= 2)
    }

    @Test
    fun aRewordedSpendingReachesTheModelStub() = runBlocking<Unit> {
        assertPassedUp("m1", "lunch set me back 35")
        assertEquals(0, count("spending"))
    }

    @Test
    fun aJournalEntryAboutADreamIsNotSavedAsAJournalEntry() = runBlocking<Unit> {
        assertPassedUp("m1", "journal: I had a dream about flying")
        assertEquals(0, count("journal"))
    }

    @Test
    fun nearMissSpendingsWriteNothing() = runBlocking<Unit> {
        assertPassedUp("m1", "Dana paid 35 for lunch")
        assertPassedUp("m2", "I spent 35 minutes on lunch")
        assertPassedUp("m3", "I spent 35 dollars on lunch")
        assertEquals(0, count("spending"))
    }

    @Test
    fun aWeekAheadQuestionIsNotAnsweredWithTodaysEvents() = runBlocking<Unit> {
        rig.event("ev1", "Dentist visit", CHAT_T0 + 3_600_000, CHAT_T0 + 7_200_000)
        val r = send("m1", place("what's on next week", 2.0))
        assertEquals("code", brainOf("m1"))
        assertFalse("Dentist" in r.response.reply.text)
        assertEquals("Nothing on your calendar next week.", r.response.reply.text)
        assertEquals(0, rig.hpFake.bodies.size)
    }

    @Test
    fun aTomorrowQuestionIsNeverAnsweredAsToday() = runBlocking<Unit> {
        rig.event("ev1", "Dentist visit", CHAT_T0 + 3_600_000, CHAT_T0 + 7_200_000)
        val r = send("m1", place("walk me through tomorrow's day", 3.0)) // sorter: ask_today, the text says tomorrow: events of tomorrow
        assertEquals("sorter", brainOf("m1"))
        assertFalse("Dentist" in r.response.reply.text)
        assertEquals("Nothing on your calendar tomorrow.", r.response.reply.text)
    }

    // ---- chips -----------------------------------------------------------------------------------------

    @Test
    fun changeTheTaxiToFortyFiveWithOneLunchGivesChipsNotACard() = runBlocking<Unit> {
        rig.spend("s1", 3500, "Food", "lunch")
        rig.scriptHp(decisionJson("action", action = "update_spending", target = "the taxi", args = mapOf("amount" to "45")))
        val r = send("m1", "change the taxi to 45")
        assertEquals(1, r.response.reply.choices.size)
        assertTrue(r.response.reply.cards.isEmpty())
        assertEquals("update_spending", parseExtras(rig.chats.message("r:m1")!!.card_json).pending?.tool)
        assertEquals(0, count("approval"))
        assertNoId(r.response.reply.text)
    }

    @Test
    fun aFindChipTapRepliesWithTheFoundNameAndCallsNoModel() = runBlocking<Unit> {
        rig.event("a", "Dentist visit", CHAT_T0 + 3_600_000, CHAT_T0 + 7_200_000)
        rig.event("b", "Dentist check-up", CHAT_T0 + 10_000_000, CHAT_T0 + 11_000_000)
        val first = send("m1", "find the dentist event")
        assertEquals(2, first.response.reply.choices.size)
        assertEquals("find", parseExtras(rig.chats.message("r:m1")!!.card_json).pending?.tool)
        val chip = first.response.reply.choices[0]
        val second = send("m2", chip.name, chip)
        assertEquals(ReplyTemplates.found(chip.name), second.response.reply.text)
        assertEquals("code", brainOf("m2"))
        assertEquals(0, rig.hpFake.bodies.size)
    }

    @Test
    fun twoMatchingTasksGiveChipsWithAPendingActionAndTheTapCompletesInCode() = runBlocking<Unit> {
        rig.task("a", "Write report")
        rig.task("b", "Report to the bank")
        val first = send("m1", "mark the report task done")
        assertEquals(2, first.response.reply.choices.size)
        assertEquals(ReplyTemplates.whichOne(), first.response.reply.text)
        val pending = parseExtras(rig.chats.message("r:m1")!!.card_json).pending
        assertEquals("update_task", pending?.tool)
        assertEquals(mapOf("done" to "true"), pending?.args)
        val second = send("m2", "the first one", first.response.reply.choices[0])
        assertEquals(1, second.response.reply.cards.size)
        assertEquals("code", brainOf("m2"))
        assertEquals(0, rig.hpFake.bodies.size + rig.fake.bodies.size)
    }

    @Test
    fun theLaptopBeingOnStillUsesCodeFirst() = runBlocking<Unit> {
        val xps = ChatRig(xps = true)
        try {
            val r = send("m1", "add task buy milk", r = xps)
            assertEquals(1, xps.pull().count { it.table == "task" })
            assertEquals(1, r.response.reply.actions.size)
            assertTrue(xps.fake.bodies.isEmpty() && xps.hpFake.bodies.isEmpty())
            assertEquals("code", parseExtras(xps.chats.message("r:m1")!!.card_json).brain)
        } finally {
            xps.close()
        }
    }

    // ---- passing up ------------------------------------------------------------------------------------

    private fun ladder(sorter: Sorter? = null) =
        Ladder(Targets(rig.repo) { rig.now }, AskAnswers(rig.repo) { rig.now }, rig.read, rig.chats, sorter) { rig.now }

    @Test
    fun aChipWithoutAPendingActionReturnsNull() = runBlocking<Unit> {
        rig.chats.create("c1")
        val chip = ChoiceDto("task", "task:a", "Write report", "tok-1")
        rig.chats.finishReply("c1", "r:old", "Which one?") { it.copy(choices = listOf(chip)) }
        assertNull(ladder().tryCode("c1", "m1", "Write report", chip, rig.executor("c1", "r:m1")))
        assertNull(ladder().tryCode("c1", "m1", "Write report", ChoiceDto("task", "task:a", "Write report", "never-issued"), rig.executor("c1", "r:m1")))
        assertEquals(0, writes())
    }

    @Test
    fun noMatchingTaskPassesUpWithNothingWritten() = runBlocking<Unit> {
        rig.task("a", "Write report")
        rig.chats.create("c1")
        assertNull(ladder().tryCode("c1", "m1", "mark the laundry task done", null, rig.executor("c1", "r:m1")))
        assertPassedUp("m2", "mark the laundry task done")
    }

    @Test
    fun aSorterIntentOutsideTheReadOnlySetPassesUp() = runBlocking<Unit> {
        rig.chats.create("c1")
        val text = place("get milk on my todo", 241.0)
        val sorter = Sorter(store, embedder, true)
        assertEquals(IntentName.CREATE_TASK, sorter.decide(normalized(vec(241.0))!!, store.snapshot()!!)!!.intent)
        // the sorter never answers an add itself; the handler only asks, and nothing is saved
        val out = assertNotNull(ladder(sorter).tryCode("c1", "m1", text, null, rig.executor("c1", "r:m1")))
        assertEquals("ask", out.brain)
        assertEquals(0, writes())
    }

    @Test
    fun anExecutorErrorPassesUpWithNothingWritten() = runBlocking<Unit> {
        rig.chats.create("c1")
        // the dream tool is refused when the executor was not given dream permission
        val executor = rig.executor("c1", "r:m1")
        executor.dreamAllowed = false
        assertNull(ladder().tryCode("c1", "m1", "I dreamt that I flew", null, executor))
        assertEquals(0, count("dream"))
    }

    // ---- no ids, and the brain label ---------------------------------------------------------------------

    @Test
    fun noCodeMadeReplyShowsAnIdOrAToolName() = runBlocking<Unit> {
        rig.task("a", "Write report")
        rig.task("b", "Report to the bank")
        rig.exam("e1", "Algebra", "2026-12-02")
        rig.event("ev", "Dentist visit", CHAT_T0 + 3_600_000, CHAT_T0 + 7_200_000)
        val texts = listOf(
            "add task buy milk", "I spent 12 on parking", "log a snack: carrots", "journal: calm day", "mark the report task done",
            "move the algebra exam to friday", "find the dentist event", "find the keys event", "what's on today", "what are my tasks",
            "how much have I spent this week", "what's on my calendar tomorrow",
        )
        for ((i, text) in texts.withIndex()) {
            val reply = send("m$i", text).response.reply
            assertNoId(reply.text)
            for (c in reply.choices) assertNoId(c.name)
        }
        assertEquals(0, rig.hpFake.bodies.size)
    }

    @Test
    fun aPlainAnswerStoresItsBrain() = runBlocking<Unit> {
        send("m1", "hello there")
        val extras = parseExtras(rig.chats.message("r:m1")!!.card_json)
        assertEquals("hp", extras.brain)
        assertNotNull(rig.chats.message("r:m1"))
        assertNull(extras.pending)
    }
}
