package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.db.Dream
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.HP_NUM_CTX
import dev.tim.assistant.server.chat.PendingAction
import dev.tim.assistant.server.chat.SendResult
import dev.tim.assistant.server.chat.parseExtras
import dev.tim.assistant.server.chat.TurnResult
import dev.tim.assistant.server.chat.decisionJson
import dev.tim.assistant.server.chat.voiceText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HpLastResortTest {
    private val rig = ChatRig()
    private val marker = "ZZDREAMMARKER"

    @AfterTest
    fun close() = rig.close()

    private fun lastResort(client: OllamaClient? = rig.hpOllama, timeoutMs: Long = 5_000) = HpLastResort(
        rig.sdb, rig.chats, rig.brain, rig.lock, client, DecisionValidator(Targets(rig.repo) { rig.now }, rig.read) { rig.now },
        { rig.now }, timeoutMs,
    )

    private suspend fun run(
        text: String, screen: String? = "Today", dreams: Boolean = false, id: String = "m1", runner: HpLastResort = lastResort(),
    ): TurnResult {
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", id, text, rig.now)
        return runner.run("c1", "r:$id", id, text, screen, dreams, rig.executor("c1", "r:$id"))
    }

    private fun reply(r: TurnResult): TurnResult.Reply = assertIs<TurnResult.Reply>(r)

    private fun request(i: Int = 0): JsonObject = rig.hpFake.requestJson(i)

    private fun system(i: Int = 0) = request(i)["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content

    private fun user(i: Int = 0) = request(i)["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content

    @Test
    fun oneModelCallPerTurnAnswerIsUsedAndHasNoId() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("answer", "You have two tasks today."))
        val r = reply(run("how is it going"))
        assertEquals(1, rig.hpFake.bodies.size)
        assertEquals("You have two tasks today.", r.text)
        assertEquals("hp", r.brain)
        assertFalse(ID_LIKE.containsMatchIn(r.text))
    }

    @Test
    fun requestCarriesTemperatureZeroTheSharedWindowTheHpModelAndTheSchema() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("answer", "ok"))
        run("how is it going")
        val req = request()
        assertEquals("hp-model", req["model"]!!.jsonPrimitive.content)
        assertEquals(HP_NUM_CTX, req["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.content.toInt())
        assertEquals(8192, HP_NUM_CTX)
        assertEquals("0.0", req["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
        assertTrue("format" in req && "tools" !in req)
        assertEquals(300, HP_NUM_PREDICT)
        assertEquals(HP_NUM_PREDICT, req["options"]!!.jsonObject["num_predict"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun aLongMessageNeverReachesTheModel() = runBlocking<Unit> {
        val r = reply(run("word ".repeat(130)))
        assertEquals(ReplyTemplates.tooLong(), r.text)
        assertTrue(rig.hpFake.bodies.isEmpty())
        // exactly 600 characters is still allowed
        rig.scriptHp(decisionJson("answer", "fine"))
        assertEquals("fine", reply(run("a".repeat(600), id = "m2")).text)
        assertEquals(1, rig.hpFake.bodies.size)
    }

    @Test
    fun anAddActionIsOnlySuggestedNeverSaved() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("action", action = "create_task", args = mapOf("title" to "Call the dentist")))
        val r = reply(run("remember the dentist"))
        assertEquals(0, rig.pull().count { it.table == "task" })
        assertTrue("dentist" in r.text.lowercase(), r.text)
        assertFalse(ID_LIKE.containsMatchIn(r.text))
        assertEquals(listOf(PendingAction("create_task", mapOf("title" to "Call the dentist"))), r.options)
        assertEquals(listOf("Yes", "Something else"), r.choices.map { it.name })
        assertEquals("m1", r.sourceMessageId)
        assertEquals("2026-11-11", r.sourceDay)
    }

    private suspend fun suggestion(action: String, args: Map<String, String>, text: String = "note this please", dreams: Boolean = false): TurnResult.Reply {
        rig.scriptHp(decisionJson("action", action = action, args = args))
        return reply(run(text, dreams = dreams))
    }

    private suspend fun send(id: String, text: String, choice: ChoiceDto? = null): SendResult.Ok {
        rig.chats.create("c1")
        return assertIs<SendResult.Ok>(rig.service.send("c1", SendMessageRequest(id, text, rig.now, null, choice)))
    }

    private fun extras(id: String) = parseExtras(rig.chats.message("r:$id")!!.card_json)

    @Test
    fun yesOnAnHpSuggestionSavesWithUndoAndIsATapTurn() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("action", action = "create_task", args = mapOf("title" to "Call the dentist")))
        val q = send("m1", "the dentist matter")
        assertEquals(0, rig.pull().count { it.table == "task" })
        assertEquals("hp", extras("m1").brain)
        assertEquals("m1", extras("m1").sourceMessageId)
        val done = send("m2", "Yes", q.response.reply.choices.first { it.name == "Yes" })
        assertEquals(1, done.response.reply.actions.size)
        assertEquals("tap", extras("m2").brain)
        assertEquals(1, rig.pull().count { it.table == "task" })
        assertEquals(1, rig.hpFake.bodies.size)
    }

    @Test
    fun somethingElseOnAnHpSuggestionNeedsTheLaptopAndAsksTheHpNoMore() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("action", action = "create_task", args = mapOf("title" to "Call the dentist")))
        val q = send("m1", "the dentist matter")
        val r = send("m2", "Something else", q.response.reply.choices.first { it.name == "Something else" })
        assertEquals(ReplyTemplates.needsLaptop(), r.response.reply.text)
        assertEquals(1, rig.hpFake.bodies.size)
        assertEquals(0, rig.pull().count { it.table == "task" })
    }

    @Test
    fun anHpSuggestionOnATurnContinuedFromSomethingElseStoresTheOriginalSourceMessageId() = runBlocking<Unit> {
        val elseChip = ChoiceDto("suggest", "else", "Something else", "tok-else")
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "s1", "the dentist matter", CHAT_T0)
        rig.chats.finishReply("c1", "r:s1", "Did you mean something?") { it.copy(choices = listOf(elseChip), brain = "code", sourceMessageId = "s1") }
        rig.scriptHp(decisionJson("action", action = "create_task", args = mapOf("title" to "Call the dentist")))
        val tap = rig.service.send("c1", SendMessageRequest("m2", "Something else", rig.now, null, elseChip))
        assertIs<SendResult.Ok>(tap)
        val stored = extras("m2")
        assertEquals("s1", stored.sourceMessageId)
        assertEquals(listOf(PendingAction("create_task", mapOf("title" to "Call the dentist"))), stored.options)
        assertEquals(listOf("Yes", "Something else"), tap.response.reply.choices.map { it.name })
        assertEquals(0, rig.pull().count { it.table == "task" })
        assertTrue("dentist matter" in rig.hpFake.bodies.single())
    }

    @Test
    fun anHpSpendingForYesterdayBecomesACardNamingTheDay() = runBlocking<Unit> {
        val r = suggestion("add_spending", mapOf("amount" to "35", "category" to "Food", "date" to "2026-11-10"))
        assertEquals(ReplyTemplates.card(), r.text)
        assertTrue(r.options.isEmpty() && r.choices.isEmpty())
        assertEquals(0, rig.pull().count { it.table == "spending" })
        val card = rig.pull().filter { it.table == "approval" }
        assertEquals(1, card.size)
        assertTrue("2026-11-10" in card[0].data.toString(), card[0].data.toString())
    }

    @Test
    fun anHpSpendingDatedTomorrowOrAYearBackSaysAgainWithNoCard() = runBlocking<Unit> {
        for ((i, d) in listOf("2026-11-12", "2025-11-11").withIndex()) {
            rig.chats.create("c$i")
            rig.scriptHp(decisionJson("action", action = "add_spending", args = mapOf("amount" to "35", "category" to "Food", "date" to d)))
            rig.chats.addUserMessage("c$i", "m$i", "note this", rig.now)
            val r = reply(lastResort().run("c$i", "r:m$i", "m$i", "note this", "Today", false, rig.executor("c$i", "r:m$i")))
            assertEquals(ReplyTemplates.sayAgain(), r.text, d)
            assertTrue(r.options.isEmpty() && r.choices.isEmpty())
        }
        assertEquals(0, rig.pull().count { it.table == "approval" })
    }

    @Test
    fun anHpJournalWithAnotherDateSaysAgain() = runBlocking<Unit> {
        val r = suggestion("add_journal", mapOf("text" to "A long day", "date" to "2026-11-10"))
        assertEquals(ReplyTemplates.sayAgain(), r.text)
        assertTrue(r.options.isEmpty())
        assertEquals(0, rig.pull().count { it.table == "journal" })
    }

    @Test
    fun aTimeAndTodaysDateAreDroppedFromTheSuggestion() = runBlocking<Unit> {
        val r = suggestion("add_meal", mapOf("type" to "MEAL", "text" to "rice and beans", "date" to "2026-11-11", "time" to "13:00"))
        assertEquals(listOf(PendingAction("add_meal", mapOf("type" to "MEAL", "text" to "rice and beans"))), r.options)
        assertEquals(0, rig.pull().count { it.table == "meal" })
    }

    @Test
    fun theNoteIsInTheLabelAndTheLabelIsTheHandlersLabel() = runBlocking<Unit> {
        val r = suggestion("add_spending", mapOf("amount" to "35", "category" to "Food", "note" to "pizza night"))
        val label = SlotFill.labelFor("add_spending", mapOf("amount" to "35", "category" to "Food", "note" to "pizza night"), java.time.LocalDate.of(2026, 11, 11))
        assertEquals(ReplyTemplates.didYouMean(label!!), r.text)
        assertTrue("pizza night" in r.text)
    }

    @Test
    fun anHpAddOnADreamMessageSaysAgainWithNoOption() = runBlocking<Unit> {
        val r = suggestion("add_journal", mapOf("text" to "I felt odd after my dream"), text = "I had a dream and felt odd", dreams = true)
        assertEquals(ReplyTemplates.sayAgain(), r.text)
        assertTrue(r.options.isEmpty() && r.choices.isEmpty() && r.sourceMessageId == null)
        assertEquals(0, rig.pull().count { it.table == "journal" })
    }

    @Test
    fun anHpDreamDecisionForADreamMessageIsSavedDirectly() = runBlocking<Unit> {
        val r = suggestion("add_dream", mapOf("text" to "I was flying"), text = "I had a dream, I was flying", dreams = true)
        assertTrue(r.options.isEmpty())
        assertEquals(1, rig.pull().count { it.table == "dream" })
    }

    @Test
    fun anHpEditStillMakesACard() = runBlocking<Unit> {
        rig.task("a", "Call mom", "2026-11-12")
        rig.scriptHp(decisionJson("action", action = "update_task", target = "Call mom", args = mapOf("dueDate" to "2026-11-14")))
        val r = reply(run("move calling mom"))
        assertTrue(r.options.isEmpty())
        assertEquals(1, rig.pull().count { it.table == "approval" })
    }

    @Test
    fun aPhoneAddDecisionStillSavesAtOnce() = runBlocking<Unit> {
        rig.chats.create("c1")
        val reply = lastResort().applyDecision(decisionJson("action", action = "create_task", args = mapOf("title" to "Call the dentist")), rig.executor(), false, "m1", "phone")
        assertTrue(reply.options.isEmpty())
        assertEquals(1, rig.pull().count { it.table == "task" })
    }

    @Test
    fun aBadAnswerFromTheModelAsksAgainAndWritesNothing() = runBlocking<Unit> {
        rig.hpFake.reply = { 200 to chatBody("this is not json at all") }
        assertEquals(ReplyTemplates.sayAgain(), reply(run("hello")).text)
        rig.scriptHp(decisionJson("action", action = "create_task", args = emptyMap()))
        assertEquals(ReplyTemplates.sayAgain(), reply(run("hello again", id = "m2")).text)
        assertEquals(0, rig.pull().size)
    }

    @Test
    fun anOllamaFailureIsUnavailableAndWritesNothing() = runBlocking<Unit> {
        rig.hpFake.reply = { 500 to "boom" }
        assertEquals(TurnResult.Unavailable(wrote = false), run("hello"))
        assertEquals(0, rig.pull().size)
    }

    @Test
    fun noHpClientMeansUnavailable() = runBlocking<Unit> {
        assertEquals(TurnResult.Unavailable(wrote = false), run("hello", runner = lastResort(client = null)))
    }

    @Test
    fun aTimeoutWhileTheModelIsStuckIsUnavailableAndWritesNothing() = runBlocking<Unit> {
        rig.hpFake.hold = true
        assertEquals(TurnResult.Unavailable(wrote = false), run("hello", runner = lastResort(timeoutMs = 300)))
        assertEquals(0, rig.pull().size)
        assertFalse(rig.lock.isLocked)
    }

    @Test
    fun aTimeoutWhileWaitingForTheLockMakesNoModelCall() = runBlocking<Unit> {
        rig.lock.lock()
        try {
            assertEquals(TurnResult.Unavailable(wrote = false), run("hello", runner = lastResort(timeoutMs = 300)))
        } finally {
            rig.lock.unlock()
        }
        assertTrue(rig.hpFake.bodies.isEmpty())
    }

    @Test
    fun theStaticSystemPartIsByteIdenticalBetweenTwoCallsAMinuteApart() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("answer", "ok"), decisionJson("answer", "ok"))
        run("first", id = "m1")
        rig.now += 60_000
        run("something else entirely", id = "m2")
        assertEquals(system(0), system(1))
        assertTrue(system(0).startsWith(voiceText()))
        assertTrue(user(0) != user(1))
    }

    @Test
    fun theScreenLineIsPresentWhenAScreenIsSent() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("answer", "ok"), decisionJson("answer", "ok"))
        run("hi", screen = "Tasks", id = "m1")
        run("hi", screen = null, id = "m2")
        assertTrue("Tim is looking at: Tasks" in user(0))
        assertFalse("Tim is looking at" in user(1))
    }

    @Test
    fun noDreamTextReachesThePromptWithoutADreamWord() = runBlocking<Unit> {
        rig.seed(toSyncRow(Dream("dream:1", 1000, 0, 0, "SYNCED", "2026-11-11", "$marker flying over the sea", 4, 0, "", null, null)))
        rig.scriptHp(decisionJson("answer", "ok"), decisionJson("answer", "ok"))
        run("how was my week", dreams = false, id = "m1")
        run("tell me about my last dream", dreams = true, id = "m2")
        assertFalse(marker in rig.hpFake.bodies[0])
        assertTrue(marker in rig.hpFake.bodies[1])
        assertFalse("add_dream" in request(0)["format"].toString())
        assertTrue("add_dream" in request(1)["format"].toString())
    }

    @Test
    fun theReplyCarriesPendingChipsFromTheValidator() = runBlocking<Unit> {
        rig.task("a", "Write report")
        rig.task("b", "Report to the bank")
        rig.scriptHp(decisionJson("action", action = "update_task", target = "report", args = mapOf("done" to "true")))
        val r = reply(run("finish the report"))
        assertEquals(2, r.choices.size)
        assertEquals("update_task", r.pending?.tool)
        assertEquals(0, rig.pull().count { it.table == "approval" })
        assertNull(rig.hpFake.bodies.getOrNull(1))
    }

    @Test
    fun theTurnTimeIsTheRigClockNotTheWallClock() = runBlocking<Unit> {
        rig.scriptHp(decisionJson("answer", "ok"))
        run("what now")
        assertTrue("Time now: 12:00" in user(0), user(0))
        assertEquals(CHAT_T0, rig.now)
    }
}
