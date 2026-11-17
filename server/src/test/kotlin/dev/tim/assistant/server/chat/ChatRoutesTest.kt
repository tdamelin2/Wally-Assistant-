package dev.tim.assistant.server.chat

import dev.tim.assistant.model.ChatBrainResponse
import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChatListResponse
import dev.tim.assistant.model.MemoryResponse
import dev.tim.assistant.model.NewChatResponse
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.model.UndoResponse
import dev.tim.assistant.server.TOKEN
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.module
import dev.tim.assistant.server.testConfig
import dev.tim.assistant.server.testJson
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatRoutesTest {
    private fun ApplicationTestBuilder.start(rig: ChatRig) {
        val config = testConfig("unused").let { it.copy(tokens = it.tokens + ("calendar" to CAL_TOKEN)) }
        application { module(config, rig.store, { rig.now }, chat = rig.service, approvalSink = rig.sink) }
    }

    private suspend fun HttpClient.api(method: String, path: String, body: String? = null, token: String? = TOKEN): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(path, build)
            "DELETE" -> delete(path, build)
            else -> post(path, build)
        }
    }

    private fun send(id: String, text: String, createdAt: Long = CHAT_T0) =
        """{"messageId":"$id","text":"$text","createdAt":$createdAt,"screen":"Today"}"""

    @Test
    fun listCreateReadSendDeleteAndMemoryWork() = testApplication {
        val rig = ChatRig(xps = true)
        start(rig)
        val list = testJson.decodeFromString(ChatListResponse.serializer(), client.api("GET", "/v1/chat/list").bodyAsText())
        assertEquals(1, list.chats.size) // the pinned weekly report chat
        assertTrue(list.chats[0].pinned)
        assertEquals(10, list.limit)

        val created = testJson.decodeFromString(
            NewChatResponse.serializer(), client.api("POST", "/v1/chat", """{"id":"c1"}""").bodyAsText(),
        )
        assertEquals("c1", created.chat.id)

        rig.script(chatBody("Hi Tim, you have nothing on today."))
        val sent = client.api("POST", "/v1/chat/c1/message", send("m1", "how is my week going"))
        assertEquals(HttpStatusCode.OK, sent.status)
        val reply = testJson.decodeFromString(SendMessageResponse.serializer(), sent.bodyAsText())
        assertEquals("Hi Tim, you have nothing on today.", reply.reply.text)
        assertEquals("assistant", reply.reply.role)
        assertEquals("user", reply.user.role)

        val detail = testJson.decodeFromString(ChatDetailResponse.serializer(), client.api("GET", "/v1/chat/c1").bodyAsText())
        assertEquals(listOf("user", "assistant"), detail.messages.map { it.role })
        assertEquals("how is my week going", detail.chat.title)

        val request = rig.fake.requestJson(0).toString()
        assertTrue("Tim is looking at: Today" in request)
        assertTrue("test persona" in request)

        assertEquals(HttpStatusCode.NoContent, client.api("DELETE", "/v1/chat/c1").status)
        assertEquals(HttpStatusCode.NotFound, client.api("GET", "/v1/chat/c1").status)
        val memory = testJson.decodeFromString(MemoryResponse.serializer(), client.api("GET", "/v1/memory").bodyAsText())
        assertTrue(memory.notes.single().pending)
        assertEquals("how is my week going", memory.notes.single().sourceChatTitle)
        rig.close()
    }

    @Test
    fun theSameMessageIdAgainGivesTheStoredReplyWithoutANewModelRun() = testApplication {
        val rig = ChatRig()
        start(rig)
        client.api("POST", "/v1/chat", """{"id":"c1"}""")
        rig.scriptHp(decisionJson("answer", "First answer"), decisionJson("answer", "SECOND answer"))
        val first = client.api("POST", "/v1/chat/c1/message", send("m1", "hello")).bodyAsText()
        val again = client.api("POST", "/v1/chat/c1/message", send("m1", "hello")).bodyAsText()
        assertEquals(1, rig.hpFake.bodies.size)
        val a = testJson.decodeFromString(SendMessageResponse.serializer(), first)
        val b = testJson.decodeFromString(SendMessageResponse.serializer(), again)
        assertEquals(a.reply.id, b.reply.id)
        assertEquals("First answer", b.reply.text)
        assertEquals(2, rig.chats.messages("c1").size)
        // the same id with another text is refused and nothing runs
        val clash = client.api("POST", "/v1/chat/c1/message", send("m1", "something else"))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertEquals(1, rig.hpFake.bodies.size)
        rig.close()
    }

    @Test
    fun badInputAndMissingThingsGetTheRightStatus() = testApplication {
        val rig = ChatRig()
        start(rig)
        assertEquals(HttpStatusCode.BadRequest, client.api("POST", "/v1/chat", "not json").status)
        assertEquals(HttpStatusCode.BadRequest, client.api("POST", "/v1/chat", """{"id":""}""").status)
        assertEquals(HttpStatusCode.NotFound, client.api("POST", "/v1/chat/nope/message", send("m1", "hi")).status)
        client.api("POST", "/v1/chat", """{"id":"c1"}""")
        assertEquals(HttpStatusCode.BadRequest, client.api("POST", "/v1/chat/c1/message", send("m1", "   ")).status)
        assertEquals(HttpStatusCode.BadRequest, client.api("POST", "/v1/chat/c1/message", """{"text":"x"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.api("POST", "/v1/chat/c1/undo/none").status)
        assertEquals(HttpStatusCode.NotFound, client.api("DELETE", "/v1/chat/nope").status)
        assertEquals(HttpStatusCode.BadRequest, client.api("POST", "/v1/approvals/x", """{"decision":"maybe"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.api("POST", "/v1/approvals/x", """{"decision":"yes"}""").status)
        rig.close()
    }

    @Test
    fun theWeeklyReportChatCannotBeDeleted() = testApplication {
        val rig = ChatRig()
        start(rig)
        client.api("GET", "/v1/chat/list")
        assertEquals(HttpStatusCode.Conflict, client.api("DELETE", "/v1/chat/$PINNED_CHAT_ID").status)
        rig.close()
    }

    @Test
    fun brainRouteSaysXpsWhenTheLaptopModelIsAwake() = testApplication {
        val rig = ChatRig(xps = true)
        start(rig)
        val response = client.api("GET", "/v1/chat/brain")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("xps", testJson.decodeFromString<ChatBrainResponse>(response.bodyAsText()).brain)
        rig.close()
    }

    @Test
    fun brainRouteSaysHpWhenTheLaptopIsOff() = testApplication {
        val rig = ChatRig()
        start(rig)
        val response = client.api("GET", "/v1/chat/brain")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hp", testJson.decodeFromString<ChatBrainResponse>(response.bodyAsText()).brain)
        rig.close()
    }

    @Test
    fun unknownTokenIs401AndTheCalendarTokenIs403OnEveryNewRoute() = testApplication {
        val rig = ChatRig()
        start(rig)
        val routes = listOf(
            "GET" to "/v1/chat/list", "POST" to "/v1/chat", "GET" to "/v1/chat/c1", "POST" to "/v1/chat/c1/message",
            "GET" to "/v1/chat/brain", "DELETE" to "/v1/chat/c1", "POST" to "/v1/chat/c1/undo/a1", "GET" to "/v1/memory", "POST" to "/v1/approvals/a1",
        )
        for ((method, path) in routes) {
            assertEquals(HttpStatusCode.Unauthorized, client.api(method, path, """{"id":"c1"}""", token = "wrong").status, "$method $path")
            assertEquals(HttpStatusCode.Unauthorized, client.api(method, path, """{"id":"c1"}""", token = null).status, "$method $path")
            assertEquals(HttpStatusCode.Forbidden, client.api(method, path, """{"id":"c1"}""", token = CAL_TOKEN).status, "$method $path")
        }
        rig.close()
    }

    @Test
    fun anAutoWriteThroughTheRouteShowsUndoAndUndoRemovesTheRow() = testApplication {
        val rig = ChatRig(xps = true)
        start(rig)
        client.api("POST", "/v1/chat", """{"id":"c1"}""")
        rig.script(
            dev.tim.assistant.server.chat.toolCallBody("create_task", """{"title":"Buy milk"}"""),
            chatBody("Added Buy milk to your tasks."),
        )
        val reply = testJson.decodeFromString(
            SendMessageResponse.serializer(), client.api("POST", "/v1/chat/c1/message", send("m1", "could you remember to buy milk")).bodyAsText(),
        )
        val action = reply.reply.actions.single()
        assertFalse(action.undone)
        assertEquals(CHAT_T0 + UNDO_WINDOW_MS, action.expiresAt)
        assertEquals("Added Buy milk to your tasks.", reply.reply.text)
        val task = rig.pull().single { it.table == "task" }
        assertEquals("Buy milk", rig.str(task, "title"))
        assertFalse(task.deleted)

        val undone = testJson.decodeFromString(
            UndoResponse.serializer(), client.api("POST", "/v1/chat/c1/undo/${action.actionId}").bodyAsText(),
        )
        assertTrue(undone.ok)
        assertTrue(rig.pull().single { it.table == "task" }.deleted)
        val detail = testJson.decodeFromString(ChatDetailResponse.serializer(), client.api("GET", "/v1/chat/c1").bodyAsText())
        assertTrue(detail.messages.last().actions.single().undone)

        // too late once the window is over
        rig.script(
            dev.tim.assistant.server.chat.toolCallBody("create_task", """{"title":"Second"}"""),
            chatBody("Added."),
        )
        val second = testJson.decodeFromString(
            SendMessageResponse.serializer(), client.api("POST", "/v1/chat/c1/message", send("m2", "could you remember the second thing")).bodyAsText(),
        )
        rig.now += UNDO_WINDOW_MS + 1
        val late = testJson.decodeFromString(
            UndoResponse.serializer(), client.api("POST", "/v1/chat/c1/undo/${second.reply.actions.single().actionId}").bodyAsText(),
        )
        assertFalse(late.ok)
        assertNotNull(rig.pull().single { it.table == "task" && rig.str(it, "title") == "Second" })
        rig.close()
    }

    @Test
    fun aCardAnswersYesOnceAndTheSecondYesIs409WritingNothing() = testApplication {
        val rig = ChatRig(xps = true)
        start(rig)
        rig.seed(dev.tim.assistant.server.ai.taskRow("task:t1", "Old title", null))
        client.api("POST", "/v1/chat", """{"id":"c1"}""")
        rig.script(
            toolCallBody("update_task", """{"id":"task:t1","title":"New title"}"""),
            chatBody("A card is waiting for you."),
        )
        val reply = testJson.decodeFromString(
            SendMessageResponse.serializer(), client.api("POST", "/v1/chat/c1/message", send("m1", "rename the task")).bodyAsText(),
        )
        val card = reply.reply.cards.single()
        assertEquals("PENDING", card.status)
        assertTrue("New title" in card.summary)
        assertFalse("task:t1" in card.summary) // no raw ids on the card

        val yes = client.api("POST", "/v1/approvals/${card.approvalId}", """{"decision":"yes"}""")
        assertEquals(HttpStatusCode.OK, yes.status)
        assertEquals("New title", rig.str(rig.pull().single { it.table == "task" }, "title"))
        assertEquals("DONE", rig.str(rig.pull().single { it.table == "approval" }, "status"))
        val seqAfter = rig.sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()

        val second = client.api("POST", "/v1/approvals/${card.approvalId}", """{"decision":"yes"}""")
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals(seqAfter, rig.sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull())
        assertEquals(HttpStatusCode.Conflict, client.api("POST", "/v1/approvals/${card.approvalId}", """{"decision":"no"}""").status)

        val detail = testJson.decodeFromString(ChatDetailResponse.serializer(), client.api("GET", "/v1/chat/c1").bodyAsText())
        assertEquals("DONE", detail.messages.last().cards.single().status)
        rig.close()
    }

    @Test
    fun whenNoModelAnswersAndNothingWasSavedTheMessageIsKeptForARetry() = testApplication {
        val rig = ChatRig()
        start(rig)
        client.api("POST", "/v1/chat", """{"id":"c1"}""")
        rig.hpFake.reply = { 500 to "no" }
        val failed = client.api("POST", "/v1/chat/c1/message", send("m1", "hello"))
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        rig.scriptHp(decisionJson("answer", "Back again"))
        val retry = testJson.decodeFromString(SendMessageResponse.serializer(), client.api("POST", "/v1/chat/c1/message", send("m1", "hello")).bodyAsText())
        assertEquals("Back again", retry.reply.text)
        assertEquals(2, rig.chats.messages("c1").size)
        rig.close()
    }

    @Test
    fun aCodeWriteThroughTheRouteHasNoModelCallUndoWorksAndNoIdIsShown() = testApplication {
        val rig = ChatRig()
        start(rig)
        client.api("POST", "/v1/chat", """{"id":"c1"}""")
        val reply = testJson.decodeFromString(
            SendMessageResponse.serializer(), client.api("POST", "/v1/chat/c1/message", send("m1", "add task call the dentist")).bodyAsText(),
        )
        assertTrue(rig.fake.bodies.isEmpty() && rig.hpFake.bodies.isEmpty())
        assertTrue("dentist" in reply.reply.text)
        assertFalse(dev.tim.assistant.server.chat.ladder.ID_LIKE.containsMatchIn(reply.reply.text))
        val action = reply.reply.actions.single()
        val undone = testJson.decodeFromString(
            UndoResponse.serializer(), client.api("POST", "/v1/chat/c1/undo/${action.actionId}").bodyAsText(),
        )
        assertTrue(undone.ok)
        assertTrue(rig.pull().single { it.table == "task" }.deleted)
        rig.close()
    }

    @Test
    fun aMessageNoRungCanAnswerIs503AndWritesNothingWhenThereIsNoModelAtAll() = testApplication {
        val rig = ChatRig()
        start(rig)
        client.api("POST", "/v1/chat", """{"id":"c1"}""")
        rig.hpFake.reply = { 500 to "no" }
        val failed = client.api("POST", "/v1/chat/c1/message", send("m1", "I did not spend 35 on lunch"))
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        assertTrue(rig.pull().none { it.table == "spending" })
        rig.close()
    }
}