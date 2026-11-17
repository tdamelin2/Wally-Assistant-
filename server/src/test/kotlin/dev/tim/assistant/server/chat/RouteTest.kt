package dev.tim.assistant.server.chat

import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.ROUTE_CODEX
import dev.tim.assistant.model.ROUTE_MODEL
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.TOKEN
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.module
import dev.tim.assistant.server.testConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The explicit routes (PLAN 20.7 steps 1 and 2): auto, model, codex, and the "Something else" continuation. */
class RouteTest {
    private val else_ = ChoiceDto("suggest", "else", "Something else", "tok-else")

    private fun req(id: String, text: String, route: String = "auto", choice: ChoiceDto? = null) =
        SendMessageRequest(id, text, CHAT_T0, "Today", choice, false, route)

    private fun ok(r: SendResult): SendResult.Ok = assertIs<SendResult.Ok>(r)

    /** A question of Tim's with its open "Something else" button, as the input handler leaves it. */
    private suspend fun ChatRig.question(source: String = "pick up the parcel from the post office", brain: String = "code") {
        chats.create("c1")
        chats.addUserMessage("c1", "s1", source, CHAT_T0)
        chats.finishReply("c1", "r:s1", "Did you mean something?") { it.copy(choices = listOf(else_), brain = brain, sourceMessageId = "s1") }
    }

    @Test
    fun modelRouteSkipsTheCodeRungsAndAutoDoesNot() = runBlocking<Unit> {
        val rig = ChatRig(xps = true); rig.chats.create("c1")
        rig.script(chatBody("The laptop answered."), chatBody("The laptop answered again."))
        val model = ok(rig.service.send("c1", req("m1", "what is on today", ROUTE_MODEL)))
        assertEquals("The laptop answered.", model.response.reply.text)
        assertEquals("xps", parseExtras(rig.chats.message("r:m1")!!.card_json).brain)
        val auto = ok(rig.service.send("c1", req("m2", "what is on today")))
        assertEquals("code", parseExtras(rig.chats.message("r:m2")!!.card_json).brain)
        assertTrue(auto.response.reply.text != "The laptop answered again.")
        rig.close()
    }

    @Test
    fun modelRouteWritesNothingForAClearCommandAutoSavesIt() = runBlocking<Unit> {
        val rig = ChatRig(xps = true); rig.chats.create("c1")
        rig.script(chatBody("I can't tell what you mean."))
        ok(rig.service.send("c1", req("m1", "spent 35 on food", ROUTE_MODEL)))
        assertEquals(0, runBlocking { rig.pull() }.count { it.table == "spending" })
        ok(rig.service.send("c1", req("m2", "spent 35 on food")))
        assertEquals(1, runBlocking { rig.pull() }.count { it.table == "spending" })
        rig.close()
    }

    @Test
    fun anUnknownRouteAndCodexWithAChipAreRefused() = runBlocking<Unit> {
        val rig = ChatRig(xps = true); rig.chats.create("c1")
        assertEquals(SendResult.BadInput("invalid: route"), rig.service.send("c1", req("m1", "hello", "bogus")))
        assertEquals(SendResult.BadInput("invalid: route"), rig.service.send("c1", req("m2", "hello", ROUTE_CODEX, ChoiceDto("task", "task:1", "x", "t"))))
        assertEquals(null, rig.chats.message("m1"))
        rig.close()
    }

    @Test
    fun anUnknownRouteIs400OverHttp() = testApplication {
        val rig = ChatRig(xps = true)
        rig.chats.create("c1")
        application { module(testConfig("unused"), rig.store, { rig.now }, chat = rig.service, approvalSink = rig.sink) }
        val r = client.post("/v1/chat/c1/message") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"messageId":"m1","text":"hi","createdAt":$CHAT_T0,"route":"bogus"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        rig.close()
    }

    @Test
    fun somethingElseContinuesAtTheLaptopWithTheSourceTextAndNoHandlerQuestion() = runBlocking<Unit> {
        val rig = ChatRig(xps = true); rig.question()
        rig.script(chatBody("Sure, here is my answer."))
        val r = ok(rig.service.send("c1", req("m2", "Something else", choice = else_)))
        assertEquals("Sure, here is my answer.", r.response.reply.text)
        assertTrue("post office" in rig.fake.bodies.single())
        assertTrue("Something else" !in rig.fake.bodies.single().substringAfter("\"messages\""))
        val extras = parseExtras(rig.chats.message("r:m2")!!.card_json)
        assertEquals("s1", extras.sourceMessageId)
        assertEquals("xps", extras.brain)
        assertTrue(extras.options.isEmpty() && extras.choices.isEmpty())
        rig.close()
    }

    @Test
    fun somethingElseOnAnHpSuggestionWithNoLaptopNeedsTheLaptopAndNeverAsksTheHpAgain() = runBlocking<Unit> {
        val rig = ChatRig(); rig.question(brain = "hp")
        rig.scriptHp(decisionJson("answer", "should not be used"))
        val r = ok(rig.service.send("c1", req("m2", "Something else", choice = else_)))
        assertEquals("That needs the laptop to be on.", r.response.reply.text)
        assertTrue(rig.hpFake.bodies.isEmpty())
        rig.close()
    }

    @Test
    fun somethingElseOnACodeQuestionWithNoLaptopAsksTheHpOnTheSourceText() = runBlocking<Unit> {
        val rig = ChatRig(); rig.question()
        rig.scriptHp(decisionJson("answer", "Here you go."))
        val r = ok(rig.service.send("c1", req("m2", "Something else", choice = else_)))
        assertEquals("Here you go.", r.response.reply.text)
        assertTrue("post office" in rig.hpFake.bodies.single())
        rig.close()
    }
}
