package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.PendingAction
import dev.tim.assistant.server.chat.SendResult
import dev.tim.assistant.server.chat.decisionJson
import dev.tim.assistant.server.chat.parseExtras
import java.time.LocalDate
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
 * PLAN 20.6 step 11: the questions of the input handler. The clock is Wednesday 2026-11-11 12:00 (Jerusalem). A fake embedder places every
 * text on a circle (intents sit apart, three seeds each) so the sorter's ranking is exact; scripted rankings drive the unit cases.
 */
class InputHandlerTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val placed = HashMap<String, Double>()
    private val embedder = TextEmbedder { text, _ ->
        if (text.startsWith("seed:")) vec(text.removePrefix("seed:").toDouble()) else placed[text]?.let { vec(it) }
    }
    private val seedAngles = listOf(
        IntentName.ASK_TODAY to 0.0, IntentName.ASK_EXAM to 60.0, IntentName.NONE to 100.0, IntentName.MARK_TASK_DONE to 160.0,
        IntentName.ASK_SPENDING_WEEK to 200.0, IntentName.CREATE_TASK to 240.0, IntentName.ADD_MEAL to 270.0, IntentName.ADD_SPENDING to 300.0,
        IntentName.ADD_JOURNAL to 330.0,
    )
    private val seeds = seedAngles.flatMap { (intent, base) -> listOf(0.0, 3.0, 6.0).map { Seed(intent, "seed:${base + it}") } }
    private val store = SeedStore(embedder, CoroutineScope(Dispatchers.Unconfined + Job()), seeds).also { it.startSeeding() }
    private val rig = ChatRig(sorter = Sorter(store, embedder, true))
    private val handler = InputHandler(Targets(rig.repo) { rig.now })

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

    private fun ranked(vararg p: Pair<IntentName, Double>) = Ranked(p.toList().sortedByDescending { it.second }, null)

    private suspend fun send(id: String, text: String, choice: ChoiceDto? = null, createdAt: Long = rig.now): SendResult.Ok {
        rig.chats.create("c1")
        return assertIs<SendResult.Ok>(rig.service.send("c1", SendMessageRequest(id, text, createdAt, null, choice)))
    }

    private fun extras(id: String) = parseExtras(rig.chats.message("r:$id")!!.card_json)

    private suspend fun count(table: String) = rig.pull().count { it.table == table }

    private fun chip(r: SendResult.Ok, name: String): ChoiceDto = r.response.reply.choices.first { it.name == name }

    private suspend fun assertModel(id: String, text: String, deg: Double? = null) {
        val before = rig.hpFake.bodies.size
        val r = send(id, if (deg != null) place(text, deg) else text)
        assertEquals(before + 1, rig.hpFake.bodies.size, "model not reached: $text")
        assertEquals("From the model.", r.response.reply.text, text)
    }

    // ---- missing piece ------------------------------------------------------------------------------------

    @Test
    fun aSpendingWithoutACategoryAsksAndATapSavesIt() = runBlocking<Unit> {
        val q = send("m1", "spent 35")
        assertEquals(ReplyTemplates.whichCategory(), q.response.reply.text)
        assertEquals(SlotFill.valuesFor("category") + "Something else", q.response.reply.choices.map { it.name })
        assertEquals(setOf("slot", "suggest"), q.response.reply.choices.map { it.kind }.toSet())
        val e = extras("m1")
        assertEquals("ask", e.brain)
        assertEquals("m1", e.sourceMessageId)
        assertEquals("2026-11-11", e.sourceDay)
        assertEquals(PendingAction("add_spending", mapOf("amount" to "35")), e.pending)
        assertEquals(0, count("spending"))
        val done = send("m2", "Food", chip(q, "Food"))
        assertEquals(1, done.response.reply.actions.size)
        assertEquals("tap", extras("m2").brain)
        assertEquals(1, count("spending"))
        assertEquals(0, rig.hpFake.bodies.size)
    }

    @Test
    fun aTypedCategoryActsAsTheButton() = runBlocking<Unit> {
        send("m1", "paid 12 for the thing")
        val done = send("m2", "Food.")
        assertEquals(1, done.response.reply.actions.size)
        assertEquals("tap", extras("m2").brain)
        assertEquals(1, count("spending"))
        send("m3", "spent 40")
        assertEquals(1, send("m4", "  transport!  ").response.reply.actions.size)
        assertEquals(2, count("spending"))
    }

    @Test
    fun aMealWithoutATypeAsksAndTheTapSavesASnack() = runBlocking<Unit> {
        val q = send("m1", "I ate a sandwich")
        assertEquals(ReplyTemplates.mealOrSnack(), q.response.reply.text)
        assertEquals(listOf("Meal", "Snack", "Something else"), q.response.reply.choices.map { it.name })
        assertEquals(PendingAction("add_meal", mapOf("text" to "a sandwich")), extras("m1").pending)
        send("m2", "Snack", chip(q, "Snack"))
        assertEquals(1, count("meal"))
        assertEquals("SNACK", rig.str(rig.pull().first { it.table == "meal" }, "type"))
    }

    @Test
    fun missingPieceShapesThatAreNotQuestions() = runBlocking<Unit> {
        for (t in listOf("I had a rough day", "I had an argument with Dana", "I ate something weird and now my stomach hurts", "I ate too much")) {
            assertNull(handler.early(t, today), t)
        }
        // other-day, tomorrow, weekly and total wording never asks for a piece
        for (t in listOf("paid 200 for the trip tomorrow", "spent 500 on stuff in total", "spent 35 yesterday", "spent 20 this week")) {
            assertNull(handler.early(t, today), t)
        }
        assertNull(handler.early("how much did I spend 35", today)) // a question
        assertNull(handler.early("I didn't spend 35", today)) // negation
        assertNull(handler.early("spent 35 on a dream", today)) // dream
        assertNull(handler.early("הוצאה 35", today)) // Hebrew
        assertNull(handler.early("spent 35", today, typedToday = false))
        assertNull(handler.early("spent 35 on " + "x".repeat(200), today)) // over-length
        assertNotNull(handler.early("spent 35", today))
    }

    // ---- guard word ----------------------------------------------------------------------------------------

    @Test
    fun aGuardWordCommandGetsOneYesQuestion() = runBlocking<Unit> {
        val q = send("m1", "add task: buy budget milk")
        assertEquals(ReplyTemplates.didYouMean("Add task \"buy budget milk\""), q.response.reply.text)
        assertEquals(listOf("Yes", "Something else"), q.response.reply.choices.map { it.name })
        assertEquals(listOf(PendingAction("create_task", mapOf("title" to "buy budget milk"))), extras("m1").options)
        assertEquals(0, count("task"))
        val done = send("m2", "yes")
        assertEquals(1, done.response.reply.actions.size)
        assertEquals(1, count("task"))
        assertEquals("tap", extras("m2").brain)
    }

    @Test
    fun guardWordMessagesThatGetNoQuestion() = runBlocking<Unit> {
        assertNull(handler.early("spent 35 on budget lunch yesterday", today))
        assertNull(handler.early("should I spend 35 on lunch?", today)) // a question
        assertNull(handler.early("I need to call the dentist yesterday", today))
        assertNull(handler.early("I need to vent about today", today))
        assertNull(handler.suggest("I need to vent about today", today, ranked(IntentName.NONE to 0.95, IntentName.CREATE_TASK to 0.5)))
        assertNull(handler.suggest("should I spend 35 on lunch?", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        // a negation word is never asked about
        assertNull(handler.early("add task: don't buy budget milk", today))
    }

    // ---- suggestions -----------------------------------------------------------------------------------------

    @Test
    fun oneFittingActionIsAskedAsYesAndTheTapSavesWithUndo() = runBlocking<Unit> {
        val q = send("m1", place("lunch set me back 35", 300.0))
        assertEquals("Did you mean: Log spending: 35 ₪, food (lunch set back)?", q.response.reply.text)
        assertEquals(listOf("Yes", "Something else"), q.response.reply.choices.map { it.name })
        assertEquals("ask", extras("m1").brain)
        assertEquals(0, count("spending"))
        val done = send("m2", "Yes", chip(q, "Yes"))
        assertEquals(1, done.response.reply.actions.size)
        assertEquals(1, count("spending"))
        assertEquals("tap", extras("m2").brain)
        assertEquals(0, rig.hpFake.bodies.size)
    }

    @Test
    fun twoFittingActionsAreEachNamedByWhatTheyDo() = runBlocking<Unit> {
        val q = send("m1", place("lunch set me back 35", 285.0))
        assertEquals(ReplyTemplates.didYouMeanSeveral(), q.response.reply.text)
        assertEquals(
            setOf("Log meal: lunch set me back 35", "Log spending: 35 ₪, food (lunch set back)", "Something else"),
            q.response.reply.choices.map { it.name }.toSet(),
        )
        assertEquals(3, q.response.reply.choices.size)
        assertEquals(2, extras("m1").options.size)
        val spending = chip(q, "Log spending: 35 ₪, food (lunch set back)")
        val done = send("m2", spending.name, spending)
        assertEquals(1, done.response.reply.actions.size)
        assertEquals(1, count("spending"))
        assertEquals(0, count("meal"))
    }

    @Test
    fun aSuggestionWithAMissingPieceLeadsToTheMissingQuestion() = runBlocking<Unit> {
        val q = send("m1", place("coins gone, 35 to the wallet", 300.0))
        assertTrue(q.response.reply.text.startsWith("Did you mean: Log spending: 35 ₪"), q.response.reply.text)
        val next = send("m2", "Yes", chip(q, "Yes"))
        assertEquals(ReplyTemplates.whichCategory(), next.response.reply.text)
        assertEquals("m1", extras("m2").sourceMessageId)
        assertEquals(0, count("spending"))
        send("m3", "Bills", chip(next, "Bills"))
        assertEquals(1, count("spending"))
    }

    @Test
    fun anAskOptionAnswersWhenTapped() = runBlocking<Unit> {
        val q = send("m1", place("tell me about my plan", 30.0))
        assertEquals(ReplyTemplates.didYouMeanSeveral(), q.response.reply.text)
        val names = q.response.reply.choices.map { it.name }
        assertTrue("Show: today's plan" in names && "Show: next exams" in names, names.toString())
        val answer = send("m2", "Show: today's plan", chip(q, "Show: today's plan"))
        assertEquals("tap", extras("m2").brain)
        assertTrue(answer.response.reply.text.isNotBlank())
        assertEquals(0, rig.hpFake.bodies.size)
    }

    @Test
    fun anAcceptedAskIntentIsAnsweredNotAsked() = runBlocking<Unit> {
        val r = send("m1", place("run me through the schedule", 3.0))
        assertEquals("sorter", extras("m1").brain)
        assertFalse(r.response.reply.text.startsWith("Did you mean"))
    }

    @Test
    fun aMarkDoneOptionOpensTheCard() = runBlocking<Unit> {
        rig.task("t1", "Laundry")
        val q = send("m1", place("all done with laundry", 160.0))
        assertEquals(ReplyTemplates.didYouMean("Mark done: Laundry"), q.response.reply.text)
        val done = send("m2", "yes")
        assertEquals(1, done.response.reply.cards.size)
        assertEquals("tap", extras("m2").brain)
    }

    @Test
    fun suggestRules() = runBlocking<Unit> {
        // top intent none: no question, whatever the add scores are
        assertNull(handler.suggest("lunch set me back 35", today, ranked(IntentName.NONE to 0.95, IntentName.ADD_SPENDING to 0.94)))
        // below the mark
        assertNull(handler.suggest("lunch set me back 35", today, ranked(IntentName.ADD_SPENDING to SUGGEST_MIN_SCORE - 0.02)))
        // an unfillable suggestion (no amount) is dropped, and so is the question
        assertNull(handler.suggest("lunch set me back", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        // a question never gets an add suggestion
        assertNull(handler.suggest("can I log 35 on lunch?", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        // one past the band is not offered with the best
        val one = assertNotNull(handler.suggest("lunch set me back 35", today, ranked(IntentName.ADD_SPENDING to 0.99, IntentName.ADD_MEAL to 0.90)))
        assertEquals(1, one.options.size)
        // another day: no add suggestion
        assertNull(handler.suggest("lunch set me back 35 yesterday", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        // no-blank text for a total or a week
        assertNull(handler.suggest("spent 500 on stuff in total", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        assertNull(handler.suggest("I ate too much", today, ranked(IntentName.ADD_MEAL to 0.99)))
        assertNull(handler.suggest("paid 200 for the trip tomorrow", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        assertNull(handler.suggest("paid 200 today for the trip tomorrow", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        // exams by date are not offered
        assertNull(handler.suggest("which exams do I have next week", today, ranked(IntentName.ASK_EXAM to 0.99)))
        // two asks with the same label after typing are shown once
        val spending = assertNotNull(handler.suggest("what did money do this week", today, ranked(IntentName.ASK_SPENDING_WEEK to 0.99, IntentName.ASK_SPENDING to 0.98)))
        assertEquals(1, spending.options.size)
        assertEquals("Did you mean: Show: spending this week?", spending.text)
        // hebrew, negation, dream, over-length, typed on another day
        assertNull(handler.suggest("הוצאה 35 על אוכל", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        assertNull(handler.suggest("I did not pay 35 for lunch", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        assertNull(handler.suggest("I dreamt about paying 35 for lunch", today, ranked(IntentName.ADD_SPENDING to 0.99)))
        assertNull(handler.suggest("lunch cost me 35 " + "x".repeat(200), today, ranked(IntentName.ADD_SPENDING to 0.99)))
        assertNull(handler.suggest("lunch set me back 35", today, ranked(IntentName.ADD_SPENDING to 0.99), typedToday = false))
    }

    @Test
    fun conversationRankedCloseToTheBestActionGivesNoQuestion() = runBlocking<Unit> {
        // none inside the band of the best allowed score: no question, whichever intent is on top
        val line = "I had a draining day and I am so tired"
        assertNull(handler.suggest(line, today, ranked(IntentName.ADD_JOURNAL to 0.70, IntentName.NONE to 0.66, IntentName.ASK_TODAY to 0.60)))
        assertNull(handler.suggest(line, today, ranked(IntentName.ADD_MEAL to 0.72, IntentName.NONE to 0.70)))
        // a clear gap keeps the question
        assertNotNull(handler.suggest("lunch set me back 35", today, ranked(IntentName.ADD_SPENDING to 0.90, IntentName.NONE to 0.70)))
        // a journal button needs a journal word; free feelings never become one
        assertNull(handler.suggest(line, today, ranked(IntentName.ADD_JOURNAL to 0.95, IntentName.ASK_TODAY to 0.40)))
        val withCue = assertNotNull(handler.suggest("write down that I had a draining day", today, ranked(IntentName.ADD_JOURNAL to 0.95)))
        assertEquals("Did you mean: Journal note: write down that I had a draining day?", withCue.text)
    }

    @Test
    fun aConversationLineStartingWithIHadStaysAnswered() = runBlocking<Unit> {
        assertModel("m1", "I had a rough day and I feel completely drained", 100.0)
        assertEquals(0, count("meal"))
        assertEquals(0, count("journal"))
    }

    @Test
    fun aMealWithoutATypeFromTheSorterAsksMealOrSnackAtOnce() = runBlocking<Unit> {
        val q = assertNotNull(handler.suggest("had a bagel with jam", today, ranked(IntentName.ADD_MEAL to 0.90)))
        assertEquals(ReplyTemplates.mealOrSnack(), q.text)
        assertEquals(PendingAction("add_meal", mapOf("text" to "a bagel with jam")), q.pending)
        assertEquals(listOf("Meal", "Snack", "Something else"), q.choices.map { it.name })
        // "ate" without an I asks at once in the early path as well
        assertEquals(ReplyTemplates.mealOrSnack(), assertNotNull(handler.early("ate a bagel with jam", today)).text)
        // the verb snacked is a snack: nothing missing, a plain question
        val snack = assertNotNull(handler.suggest("snacked on grapes", today, ranked(IntentName.ADD_MEAL to 0.90)))
        assertEquals("Did you mean: Log snack: grapes?", snack.text)
        // "as a snack" and "for lunch" are read as the type and left out of the text
        assertEquals("Did you mean: Log snack: a bagel?", assertNotNull(handler.suggest("I had a bagel as a snack", today, ranked(IntentName.ADD_MEAL to 0.90))).text)
    }

    @Test
    fun guardWordTasksAreOfferedWithTheActionAsTheTitle() = runBlocking<Unit> {
        val lines = mapOf(
            "I want to renew my gym card tomorrow" to "Add task \"renew my gym card\", due tomorrow",
            "I'm going to buy a gift on Friday" to "Add task \"buy a gift\", due Friday 13 November",
            "I plan to clean the flat" to "Add task \"clean the flat\"",
            "I'd like to book a dentist visit" to "Add task \"book a dentist visit\"",
        )
        for ((line, label) in lines) {
            val q = assertNotNull(handler.suggest(line, today, ranked(IntentName.CREATE_TASK to 0.90)), line)
            assertEquals(ReplyTemplates.didYouMean(label), q.text, line)
        }
        // a conversation line of the same form: none on top, or close, gives nothing
        assertNull(handler.suggest("I want to be more patient with my brother", today, ranked(IntentName.NONE to 0.80, IntentName.CREATE_TASK to 0.70)))
        assertNull(handler.suggest("I want to be more patient with my brother", today, ranked(IntentName.CREATE_TASK to 0.80, IntentName.NONE to 0.78)))
    }

    @Test
    fun aPurchaseWithABareNumberAfterForIsASpendingNotAFood() = runBlocking<Unit> {
        // the meal fill refuses a "for <number>" ending, so the spending is the one offered
        val q = assertNotNull(handler.suggest("grabbed a sandwich for 22", today, ranked(IntentName.ADD_MEAL to 0.70, IntentName.ADD_SPENDING to 0.66)))
        assertEquals(listOf(PendingAction("add_spending", mapOf("amount" to "22", "category" to "Food", "note" to "grabbed a sandwich"))), q.options)
        assertNull(handler.suggest("grabbed a sandwich for 22", today, ranked(IntentName.ADD_MEAL to 0.90)))
    }

    @Test
    fun aSoftBlockedMessageGetsOnlyAddIntents() = runBlocking<Unit> {
        val q = assertNotNull(handler.suggest("I need to call the dentist tomorrow", today, ranked(IntentName.CREATE_TASK to 0.9, IntentName.ASK_TODAY to 0.89)))
        assertEquals("Did you mean: Add task \"call the dentist\", due tomorrow?", q.text)
        assertEquals(listOf(PendingAction("create_task", mapOf("title" to "call the dentist", "dueDate" to "2026-11-12"))), q.options)
    }

    @Test
    fun aRequestTypedOnAnEarlierDayGetsNoQuestion() = runBlocking<Unit> {
        send("m0", "hello there")
        val before = rig.hpFake.bodies.size
        val r = send("m1", "spent 35", createdAt = CHAT_T0 - 24 * 3_600_000)
        assertEquals(before + 1, rig.hpFake.bodies.size)
        assertEquals("From the model.", r.response.reply.text)
    }

    // ---- taps and typed answers ---------------------------------------------------------------------------------

    @Test
    fun aTapOnAnOldQuestionIsClosedAndNothingIsSaved() = runBlocking<Unit> {
        val q = send("m1", "spent 35")
        assertModel("m2", "hello again")
        val late = send("m3", "Food", chip(q, "Food"))
        assertEquals(ReplyTemplates.questionClosed(), late.response.reply.text)
        assertEquals(0, count("spending"))
    }

    @Test
    fun aTapOrTypedAnswerOnALaterDayIsClosedAndNothingIsSaved() = runBlocking<Unit> {
        val q = send("m1", "spent 35")
        rig.now += 24 * 3_600_000
        val typed = send("m2", "Food")
        assertEquals(ReplyTemplates.questionClosed(), typed.response.reply.text)
        val tap = send("m3", "Food", chip(q, "Food"))
        assertEquals(ReplyTemplates.questionClosed(), tap.response.reply.text)
        assertEquals(0, count("spending"))
        // a fresh question on the new day works again
        val q2 = send("m4", "spent 36")
        assertEquals(1, send("m5", "Food", chip(q2, "Food")).response.reply.actions.size)
        assertEquals(1, count("spending"))
    }

    @Test
    fun aSlotValueOutsideTheListIsClosed() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m0", "spent 35", rig.now)
        val bad = ChoiceDto("slot", "category=Bogus", "Bogus", "tok-1")
        rig.chats.finishReply("c1", "r:m0", "Which category?") {
            it.copy(choices = listOf(bad), pending = PendingAction("add_spending", mapOf("amount" to "35")), sourceMessageId = "m0", sourceDay = "2026-11-11", brain = "ask")
        }
        val r = send("m1", "Bogus", bad)
        assertEquals(ReplyTemplates.questionClosed(), r.response.reply.text)
        assertEquals(0, count("spending"))
    }

    @Test
    fun aTypedTextThatIsNotALabelIsANewMessage() = runBlocking<Unit> {
        send("m1", "spent 35")
        assertModel("m2", "what should I have for dinner?")
        send("m3", "spent 36")
        assertModel("m4", "forget it, I didn't mean food")
        send("m5", "spent 37")
        assertModel("m6", "I'll skip lunch")
        assertEquals(0, count("spending"))
        assertEquals(0, count("meal"))
    }

    @Test
    fun somethingElseSendsTheSourceMessageToTheModelSteps() = runBlocking<Unit> {
        val q = send("m1", place("lunch set me back 35", 300.0))
        val before = rig.hpFake.bodies.size
        val r = send("m2", "Something else", chip(q, "Something else"))
        assertEquals(before + 1, rig.hpFake.bodies.size)
        assertEquals("From the model.", r.response.reply.text)
        assertEquals("m1", extras("m2").sourceMessageId)
        assertTrue(rig.hpFake.bodies.last().contains("lunch set me back 35"))
        assertEquals(0, count("spending"))
    }

    @Test
    fun typedSomethingElseActsAsTheButton() = runBlocking<Unit> {
        send("m1", "spent 35")
        val r = send("m2", "something else")
        assertEquals("m1", extras("m2").sourceMessageId)
        assertEquals("From the model.", r.response.reply.text)
    }

    @Test
    fun theMarkerCarriesFromHpWhenTheQuestionCameFromTheHpModel() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m0", "lunch set me back 35", rig.now)
        val chip = ChoiceDto("suggest", "else", "Something else", "tok-2")
        rig.chats.finishReply("c1", "r:m0", "Did you mean: Log spending: 35 ₪?") {
            it.copy(choices = listOf(chip), options = listOf(PendingAction("add_spending", mapOf("amount" to "35"))), sourceMessageId = "m0", sourceDay = "2026-11-11", brain = "hp")
        }
        rig.chats.addUserMessage("c1", "m1", "Something else", rig.now)
        val ladder = Ladder(Targets(rig.repo) { rig.now }, AskAnswers(rig.repo) { rig.now }, rig.read, rig.chats, null) { rig.now }
        val out = assertNotNull(ladder.tryCode("c1", "m1", "Something else", chip, rig.executor("c1", "r:m1")))
        assertEquals(ToModel("m0", true), out.continueToModel)
        // a tap with an unknown token is a closed question
        val unknown = assertNotNull(ladder.tryCode("c1", "m1", "x", ChoiceDto("suggest", "0", "Yes", "never"), rig.executor("c1", "r:m1")))
        assertEquals(ReplyTemplates.questionClosed(), unknown.text)
    }

    @Test
    fun noSorterStillAsksTheMissingPiece() = runBlocking<Unit> {
        val plain = ChatRig()
        try {
            plain.chats.create("c1")
            val r = assertIs<SendResult.Ok>(plain.service.send("c1", SendMessageRequest("m1", "spent 35", CHAT_T0, null, null)))
            assertEquals(ReplyTemplates.whichCategory(), r.response.reply.text)
        } finally {
            plain.close()
        }
    }
}
