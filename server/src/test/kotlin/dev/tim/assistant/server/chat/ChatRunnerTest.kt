package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.CHAT_MODEL
import dev.tim.assistant.ai.CHAT_NUM_CTX
import dev.tim.assistant.db.Dream
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ai.chatBody
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatRunnerTest {
    private val rig = ChatRig()

    @AfterTest
    fun close() = rig.close()

    private suspend fun seedDream() = rig.seed(
        toSyncRow(Dream("dream:1", 1000, 0, 0, "SYNCED", "2026-11-10", "SECRETDREAMTEXT flying", 4, 0, "", null, null)),
    )

    private suspend fun turn(chatId: String, messageId: String, text: String, runner: ChatRunner = rig.runner): TurnResult {
        rig.chats.addUserMessage(chatId, messageId, text, rig.now)
        val result = runner.run(chatId, "r:$messageId", messageId, text, "Today", null)
        if (result is TurnResult.Reply) rig.chats.finishReply(chatId, "r:$messageId", result.text) { it }
        return result
    }

    /** The laptop model's system prompt of request [index]. */
    private fun system(index: Int = 0): String =
        rig.fake.requestJson(index)["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content

    private fun numCtx(json: JsonObject): Int = json["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.content.toInt()

    private fun hpRequest(index: Int = 0): JsonObject = rig.hpFake.requestJson(index)

    private fun hpUser(index: Int = 0): String = hpRequest(index)["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content

    private fun hpActions(index: Int): List<String> =
        hpRequest(index)["format"]!!.jsonObject["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun toolNames(index: Int): List<String> =
        rig.fake.requestJson(index)["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }

    /** The laptop model is "awake": the same rig, the same fakes, an XPS brain. */
    private fun xpsRunner(rig: ChatRig): ChatRunner {
        val xpsTags = MockEngine {
            respond("""{"models":[{"name":"$CHAT_MODEL"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val brain = Brain("http://xps.test:11434", "http://hp.test:11434", "hp-model", engine = xpsTags)
        return ChatRunner(
            rig.sdb, rig.store, rig.writer, rig.chats, brain, rig.lock, rig.chatClient, { rig.now },
            persona = "test persona", hpOllama = rig.hpOllama,
        )
    }

    // ---- the laptop model (rung 4) ---------------------------------------------------------------------

    @Test
    fun dreamTextIsInThePromptOnlyWhenTheMessageMentionsDreams() = runBlocking<Unit> {
        seedDream()
        rig.chats.create("c1")
        rig.script(chatBody("ok"), chatBody("ok"))
        val runner = xpsRunner(rig)
        turn("c1", "m1", "tell me something nice", runner)
        assertFalse("SECRETDREAMTEXT" in system(0))
        turn("c1", "m2", "tell me about my last dream", runner)
        assertTrue("SECRETDREAMTEXT" in system(1))
    }

    @Test
    fun dreamTaintedEarlierTurnsAreLeftOutUnlessTheCurrentMessageMentionsDreams() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.script(chatBody("It sounded like flying over the sea."), chatBody("Maybe pasta."), chatBody("ok"))
        val runner = xpsRunner(rig)
        turn("c1", "m1", "I had a dream about the sea", runner)
        turn("c1", "m2", "give me a cooking idea", runner)
        val history2 = rig.fake.requestJson(1).toString()
        assertFalse("flying over the sea" in history2)
        assertFalse("dream about the sea" in history2)
        turn("c1", "m3", "back to my dream, did it mean anything", runner)
        val history3 = rig.fake.requestJson(2).toString()
        assertTrue("flying over the sea" in history3)
    }

    @Test
    fun theTenNewestDoneNotesAndTheScreenGoIntoThePrompt() = runBlocking<Unit> {
        rig.chats.create("c1")
        for (i in 1..12) {
            rig.sdb.db.chatQueries.insertNote("n$i", "src$i", "title $i", CHAT_T0 + i)
            rig.sdb.db.chatQueries.completeNote("note text $i", "n$i")
        }
        rig.script(chatBody("ok"))
        turn("c1", "m1", "hello", xpsRunner(rig))
        val prompt = system()
        assertTrue("note text 12" in prompt)
        assertTrue("note text 3" in prompt)
        assertFalse("note text 2\n" in prompt)
        assertFalse("note text 1\n" in prompt)
        assertTrue("Tim is looking at: Today" in prompt)
        assertTrue("test persona" in prompt)
    }

    @Test
    fun theLastTwentyMessagesAreSentNewestKept() = runBlocking<Unit> {
        rig.chats.create("c1")
        for (i in 1..30) {
            rig.sdb.db.chatQueries.insertMessage("old$i", "c1", if (i % 2 == 1) "user" else "assistant", "line $i", null, null, CHAT_T0 + i)
        }
        rig.script(chatBody("ok"))
        turn("c1", "m1", "next", xpsRunner(rig))
        val roles = rig.fake.requestJson(0)["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertTrue("line 30" in roles.joinToString())
        assertTrue("line 11" in roles.joinToString())
        assertFalse("line 10" in roles.joinToString())
        assertEquals("next", roles.last())
    }

    @Test
    fun theXpsRequestStillCarriesTheFullToolListAndWindow() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.script(chatBody("ok"))
        turn("c1", "m1", "hello", xpsRunner(rig))
        assertTrue(toolNames(0).size > 30)
        assertTrue("create_goal" in toolNames(0))
        assertEquals(CHAT_NUM_CTX, numCtx(rig.fake.requestJson(0)))
        assertFalse("temperature" in rig.fake.requestJson(0)["options"]!!.jsonObject)
        assertEquals(CHAT_MODEL, rig.fake.requestJson(0)["model"]!!.jsonPrimitive.content)
        assertTrue("test persona" in system(0))
        assertTrue(rig.hpFake.bodies.isEmpty())
    }

    @Test
    fun aLaptopAnswerIsLabelledXps() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.script(chatBody("Sounds good."))
        val reply = assertIs<TurnResult.Reply>(turn("c1", "m1", "hello", xpsRunner(rig)))
        assertEquals("xps", reply.brain)
        assertEquals("Sounds good.", reply.text)
    }

    // ---- the home server's small model (rung 5) --------------------------------------------------------

    @Test
    fun anHpRunHoldsTheGenerationLockWhileTheModelAnswers() = runBlocking<Unit> {
        rig.chats.create("c1")
        var heldDuringCall = false
        rig.hpFake.reply = { heldDuringCall = rig.lock.isLocked; 200 to chatBody(decisionJson("answer", "Hello there.")) }
        val result = assertIs<TurnResult.Reply>(turn("c1", "m1", "hello"))
        assertTrue(heldDuringCall)
        assertFalse(rig.lock.isLocked)
        assertEquals("Hello there.", result.text)
        assertEquals("hp", result.brain)
        assertEquals("hp-model", hpRequest()["model"]!!.jsonPrimitive.content)
        assertTrue(rig.fake.bodies.isEmpty())
    }

    @Test
    fun theHpRequestIsOneFixedShapeCallAtTemperatureZeroInTheSharedWindow() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.scriptHp(decisionJson("answer", "ok"))
        turn("c1", "m1", "hello")
        assertEquals(1, rig.hpFake.bodies.size)
        val request = hpRequest()
        assertEquals(HP_NUM_CTX, numCtx(request))
        assertEquals("0.0", request["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
        assertFalse("tools" in request)
        assertTrue("format" in request)
        assertEquals(2, request["messages"]!!.jsonArray.size)
        val system = request["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.startsWith(voiceText()))
        assertFalse("test persona" in system)
        assertTrue(hpUser().contains("Tim is looking at: Today"))
    }

    @Test
    fun addDreamIsInTheHpSchemaOnlyWhenTheMessageMentionsDreams() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.scriptHp(decisionJson("answer", "ok"), decisionJson("answer", "ok"))
        turn("c1", "m1", "I slept badly")
        turn("c1", "m2", "I had a dream last night")
        assertFalse("add_dream" in hpActions(0))
        assertTrue("add_dream" in hpActions(1))
    }

    @Test
    fun anHpRequestKeepsFourMessagesThreeNotesAndASmallContext() = runBlocking<Unit> {
        rig.chats.create("c1")
        for (i in 1..8) {
            rig.sdb.db.chatQueries.insertMessage("old$i", "c1", if (i % 2 == 1) "user" else "assistant", "line $i " + "x".repeat(300), null, null, CHAT_T0 + i)
        }
        for (i in 1..5) {
            rig.sdb.db.chatQueries.insertNote("n$i", "src$i", "title $i", CHAT_T0 + i)
            rig.sdb.db.chatQueries.completeNote("note text $i", "n$i")
        }
        rig.scriptHp(decisionJson("answer", "ok"))
        turn("c1", "m1", "next")
        val user = hpUser()
        assertTrue("note text 5" in user && "note text 3" in user)
        assertFalse("note text 2" in user)
        assertTrue("line 8 " in user)
        assertFalse("line 4 " in user)
    }

    @Test
    fun anXpsFailureBeforeAnyToolRanContinuesOnTheHpLastResort() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m1", "hello", rig.now)
        rig.fake.reply = { 500 to "boom" }
        rig.scriptHp(decisionJson("answer", "answered on hp"))
        val result = xpsRunner(rig).run("c1", "r:m1", "m1", "hello", null, null)
        val reply = assertIs<TurnResult.Reply>(result)
        assertEquals("answered on hp", reply.text)
        assertEquals("hp", reply.brain)
        assertTrue(rig.fake.bodies.isNotEmpty())
        assertEquals("hp-model", hpRequest()["model"]!!.jsonPrimitive.content)
        assertEquals(HP_NUM_CTX, numCtx(hpRequest()))
    }

    @Test
    fun aHalfRunToolLoopIsNeverRerunOnTheHp() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m1", "could you remember to buy milk", rig.now)
        var calls = 0
        rig.fake.reply = {
            calls++
            if (calls == 1) 200 to toolCallBody("create_task", """{"title":"Buy milk"}""") else 500 to "boom" // the XPS dies after the task was saved
        }
        rig.scriptHp(decisionJson("answer", "hp should not be asked"))
        val result = xpsRunner(rig).run("c1", "r:m1", "m1", "could you remember to buy milk", null, null)
        assertEquals(TurnResult.Unavailable(wrote = true), result)
        assertTrue(rig.hpFake.bodies.isEmpty())
        assertEquals(1, rig.pull().count { it.table == "task" })
    }

    @Test
    fun theLaptopBeingOnStillLetsCodeAnswerFirst() = runBlocking<Unit> {
        rig.chats.create("c1")
        val reply = assertIs<TurnResult.Reply>(turn("c1", "m1", "add task buy milk", xpsRunner(rig)))
        assertEquals("code", reply.brain)
        assertTrue(rig.fake.bodies.isEmpty() && rig.hpFake.bodies.isEmpty())
        assertEquals(1, rig.pull().count { it.table == "task" })
    }
}
