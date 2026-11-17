package dev.tim.assistant.server.chat

import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.PhoneResultRequest
import dev.tim.assistant.model.PhoneTurnDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.server.TOKEN
import dev.tim.assistant.server.module
import dev.tim.assistant.server.testConfig
import dev.tim.assistant.server.testJson
import io.ktor.client.HttpClient
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val PHONE_BEARER = "test-token-phone"

/** The server half of the phone turn (PLAN 19.3): rung 4, the PHONE_PENDING replay matrix, the result route, the expiry tick. */
class PhoneTurnTest {
    private fun req(id: String, text: String = "hello", phone: Boolean = true, choice: ChoiceDto? = null) =
        SendMessageRequest(id, text, CHAT_T0, "Today", choice, phone)

    private fun result(id: String, decision: String) = PhoneResultRequest("r:$id", id, decision)

    private val answer = decisionJson("answer", "You are fine, Tim.")
    private val spend = decisionJson("action", action = "add_spending", args = mapOf("amount" to "35", "category" to "Food", "note" to "lunch"))

    private suspend fun ChatRig.chat(id: String = "c1") = chats.create(id)

    private suspend fun ChatRig.turn(id: String = "m1", chat: String = "c1", text: String = "hello"): PhoneTurnDto {
        val r = service.send(chat, req(id, text), "phone")
        assertIs<SendResult.PhoneTurn>(r)
        return r.turn
    }

    private fun ChatRig.reply(id: String) = chats.message("r:$id")
    private fun ChatRig.count(table: String) = runBlocking { pull() }.count { it.table == table }

    // ---- rung 4 and the 202 -------------------------------------------------------------------------------

    @Test
    fun phoneTokenGetsA202TurnAndTheReplyRowWaitsPendingAndHidden() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        val t = rig.turn()
        assertEquals("r:m1", t.turnId)
        assertEquals("m1", t.messageId)
        assertEquals(CHAT_T0 + 180_000, t.expiresAt)
        assertTrue("Tim's message: hello" in t.user)
        assertTrue("kind" in t.schema.toString())
        assertTrue(t.system.isNotBlank())
        val row = rig.reply("m1")!!
        assertEquals(STATE_PHONE_PENDING, row.request_state)
        assertEquals(CHAT_T0 + 180_000, parseExtras(row.card_json).expiresAt)
        assertTrue(rig.hpFake.bodies.isEmpty())
        // the pending reply is not in GET /v1/chat/{id}; the user message is
        val detail = rig.service.detail("c1")!!
        assertEquals(listOf("user"), detail.messages.map { it.role })
        rig.close()
    }

    @Test
    fun laptopTokenAndOldClientsNeverGet202() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.scriptHp(answer, answer, answer)
        assertIs<SendResult.Ok>(rig.service.send("c1", req("m1"), "laptop"))
        assertIs<SendResult.Ok>(rig.service.send("c1", req("m2", phone = false), "phone"))
        assertIs<SendResult.Ok>(rig.service.send("c1", req("m3"), null))
        assertEquals(3, rig.hpFake.bodies.size)
        rig.close()
    }

    @Test
    fun aMessageOver2000CharactersGoesToRung5() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        val r = rig.service.send("c1", req("m1", text = "w".repeat(2_001)), "phone")
        assertIs<SendResult.Ok>(r)
        assertTrue(rig.hpFake.bodies.isEmpty()) // rung 5 answers "too long" itself above 600
        assertEquals("hp", parseExtras(rig.reply("m1")!!.card_json).brain)
        rig.close()
    }

    @Test
    fun aMessageOf2000CharactersIsStillAPhoneTurn() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        assertIs<SendResult.PhoneTurn>(rig.service.send("c1", req("m1", text = "w".repeat(2_000)), "phone"))
        rig.close()
    }

    @Test
    fun aChipTapWithoutAPendingActionNeverGets202() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        val chip = ChoiceDto("task", "task:1", "Dentist", "tok-1")
        rig.chats.addUserMessage("c1", "seed", "find the dentist", CHAT_T0)
        rig.chats.finishReply("c1", "r:seed", "Which one?") { it.copy(choices = listOf(chip)) }
        rig.scriptHp(answer)
        val r = rig.service.send("c1", req("m1", text = "Dentist", choice = chip), "phone")
        assertFalse(r is SendResult.PhoneTurn, r.toString())
        rig.close()
    }

    @Test
    fun theLaptopFailingBeforeAnyToolLetsThePhoneAnswer() = runBlocking<Unit> {
        val rig = ChatRig(xps = true); rig.chat()
        rig.fake.reply = { 500 to "boom" }
        assertIs<SendResult.PhoneTurn>(rig.service.send("c1", req("m1"), "phone"))
        rig.close()
    }

    @Test
    fun aLaptopThatAlreadySavedSomethingNeverHandsOverToThePhone() = runBlocking<Unit> {
        val rig = ChatRig(xps = true); rig.chat()
        var calls = 0
        rig.fake.reply = { if (++calls == 1) 200 to toolCallBody("create_task", """{"title":"Buy milk"}""") else 500 to "boom" }
        val r = rig.service.send("c1", req("m1"), "phone")
        assertIs<SendResult.Ok>(r)
        assertEquals(INTERRUPTED_TEXT, r.response.reply.text)
        assertEquals(1, rig.count("task"))
        rig.close()
    }

    @Test
    fun theDreamActionIsInTheSchemaOnlyWithADreamWord() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat(); rig.chat("c2")
        val plain = rig.turn("m1", text = "hello")
        val dream = rig.turn("m2", "c2", text = "I had a weird dream about school")
        assertFalse("add_dream" in plain.schema.toString())
        assertTrue("add_dream" in dream.schema.toString())
        rig.close()
    }

    // ---- replays ------------------------------------------------------------------------------------------

    @Test
    fun theSameMessageWithPhoneBrainAgainGetsTheSameTurnWithAFreshPrompt() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        val first = rig.turn()
        rig.now += 60_000
        val again = rig.service.send("c1", req("m1"), "phone")
        assertIs<SendResult.PhoneTurn>(again)
        assertEquals(first.turnId, again.turn.turnId)
        assertEquals(first.expiresAt, again.turn.expiresAt)
        assertTrue("Tim's message: hello" in again.turn.user)
        assertEquals(STATE_PHONE_PENDING, rig.reply("m1")!!.request_state)
        rig.close()
    }

    @Test
    fun exactly60SecondsLeftIsStillAPhoneTurnAndOneMillisecondLessGoesToRung5() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        rig.now = CHAT_T0 + 120_000 // 60 s left
        assertIs<SendResult.PhoneTurn>(rig.service.send("c1", req("m1"), "phone"))
        rig.scriptHp(answer)
        rig.now = CHAT_T0 + 120_001 // 59.999 s left
        val r = rig.service.send("c1", req("m1"), "phone")
        assertIs<SendResult.Ok>(r)
        assertEquals("You are fine, Tim.", r.response.reply.text)
        assertEquals(1, rig.hpFake.bodies.size)
        rig.close()
    }

    @Test
    fun resendWithPhoneBrainFalseRunsRung5ExactlyOnce() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        rig.scriptHp(answer, decisionJson("answer", "SECOND"))
        val r = rig.service.send("c1", req("m1", phone = false), "phone")
        assertIs<SendResult.Ok>(r)
        assertEquals("You are fine, Tim.", r.response.reply.text)
        val again = rig.service.send("c1", req("m1", phone = false), "phone")
        assertIs<SendResult.Ok>(again)
        assertEquals("You are fine, Tim.", again.response.reply.text)
        assertEquals(1, rig.hpFake.bodies.size)
        assertNull(rig.reply("m1")!!.request_state)
        rig.close()
    }

    @Test
    fun aRung5TimeoutAfterAPhoneTurnLeavesNoReplyRowAndTheRetryIsAnsweredNormally() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        rig.hpFake.reply = { 500 to "boom" }
        val r = rig.service.send("c1", req("m1", phone = false), "phone")
        assertIs<SendResult.Unavailable>(r)
        assertNull(rig.reply("m1"))
        assertNotNull(rig.chats.message("m1")) // the user message stays
        rig.scriptHp(answer)
        val retry = rig.service.send("c1", req("m1", phone = false), "phone")
        assertIs<SendResult.Ok>(retry)
        assertEquals("You are fine, Tim.", retry.response.reply.text)
        assertEquals(1, rig.chats.messages("c1").count { it.role == "user" })
        rig.close()
    }

    @Test
    fun aResendAfterAResultThatWasInterruptedAnswersInterruptedAndSavesNothingMore() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        assertTrue(rig.chats.claimPhoneReply("c1", "r:m1")) // the result's claim happened, then the server stopped
        val r = rig.service.send("c1", req("m1", phone = false), "phone")
        assertIs<SendResult.Ok>(r)
        assertEquals(INTERRUPTED_TEXT, r.response.reply.text)
        assertTrue(rig.hpFake.bodies.isEmpty())
        assertIs<SendResult.Conflict>(rig.service.phoneResult("c1", result("m1", spend)))
        assertEquals(0, rig.count("spending"))
        rig.close()
    }

    @Test
    fun aDifferentMessageWhilePendingIsRefusedAndAfterExpiryItClosesTheOldTurnAndIsAnswered() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        val busy = rig.service.send("c1", req("m2", phone = false), "phone")
        assertIs<SendResult.Unavailable>(busy)
        assertEquals("Wally is still answering", busy.message)
        assertNull(rig.reply("m2"))
        rig.now = CHAT_T0 + 180_000
        rig.scriptHp(answer)
        val next = rig.service.send("c1", req("m2", phone = false), "phone")
        assertIs<SendResult.Ok>(next)
        assertEquals("You are fine, Tim.", next.response.reply.text)
        assertEquals(INTERRUPTED_TEXT, rig.reply("m1")!!.text)
        rig.close()
    }

    // ---- the result ---------------------------------------------------------------------------------------

    @Test
    fun anActionResultWritesOneRowIsAcceptedOnceAndTheSecondIs409() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        val r = rig.service.phoneResult("c1", result("m1", spend))
        assertIs<SendResult.Ok>(r)
        assertTrue("35" in r.response.reply.text, r.response.reply.text)
        assertEquals(1, rig.count("spending"))
        val stored = rig.reply("m1")!!
        assertNull(stored.request_state)
        assertEquals("phone", parseExtras(stored.card_json).brain)
        assertNull(parseExtras(stored.card_json).expiresAt)
        assertNull(rig.chats.message("m1")!!.request_state)
        assertIs<SendResult.Conflict>(rig.service.phoneResult("c1", result("m1", spend)))
        assertEquals(1, rig.count("spending"))
        rig.close()
    }

    @Test
    fun anAnswerResultIsStoredAsTheReplyWithBrainPhone() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        val r = rig.service.phoneResult("c1", result("m1", answer))
        assertIs<SendResult.Ok>(r)
        assertEquals("You are fine, Tim.", r.response.reply.text)
        assertEquals(listOf("user", "assistant"), rig.service.detail("c1")!!.messages.map { it.role })
        rig.close()
    }

    @Test
    fun anInvalidDecisionWritesNothingAndSaysAgain() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        val r = rig.service.phoneResult("c1", result("m1", "this is not json"))
        assertIs<SendResult.Ok>(r)
        assertEquals(dev.tim.assistant.server.chat.ladder.ReplyTemplates.sayAgain(), r.response.reply.text)
        assertEquals(0, rig.count("spending"))
        rig.close()
    }

    @Test
    fun theDreamPermissionComesFromTheStoredMessageNotTheClient() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn() // stored text "hello": no dream word
        val dreamAction = decisionJson("action", action = "add_dream", args = mapOf("text" to "I flew over the sea"))
        val r = rig.service.phoneResult("c1", result("m1", dreamAction))
        assertIs<SendResult.Ok>(r)
        assertEquals(0, rig.count("dream"))
        rig.close()
    }

    @Test
    fun aResultAtTheLastMillisecondIsAcceptedAndAtExactly180SecondsItClosesTheTurn() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn("m1")
        rig.now = CHAT_T0 + 179_999
        assertIs<SendResult.Ok>(rig.service.phoneResult("c1", result("m1", answer)))
        rig.turn("m2")
        rig.now = CHAT_T0 + 179_999 + 180_000
        assertIs<SendResult.Conflict>(rig.service.phoneResult("c1", result("m2", answer)))
        assertEquals(INTERRUPTED_TEXT, rig.reply("m2")!!.text)
        assertNull(rig.reply("m2")!!.request_state)
        rig.close()
    }

    @Test
    fun resultsForMissingGoneOrForeignTurns() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat(); rig.chat("c2")
        rig.turn()
        assertIs<SendResult.Conflict>(rig.service.phoneResult("c1", result("nope", answer))) // no reply row
        assertEquals(SendResult.NotFound, rig.service.phoneResult("c2", result("m1", answer))) // another chat's turn
        assertEquals(SendResult.NotFound, rig.service.phoneResult("ghost", result("m1", answer)))
        assertIs<SendResult.BadInput>(rig.service.phoneResult("c1", PhoneResultRequest("r:other", "m1", answer)))
        rig.service.delete("c1")
        assertEquals(SendResult.NotFound, rig.service.phoneResult("c1", result("m1", spend)))
        assertEquals(0, rig.count("spending"))
        rig.close()
    }

    // ---- the expiry tick ----------------------------------------------------------------------------------

    @Test
    fun theTickClosesOnlyExpiredTurnsAndAResultAfterItIs409WithTheReplyWrittenOnce() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        assertEquals(0, rig.service.closeExpiredPhoneTurns())
        assertEquals(STATE_PHONE_PENDING, rig.reply("m1")!!.request_state)
        rig.now = CHAT_T0 + 180_000
        assertEquals(1, rig.service.closeExpiredPhoneTurns())
        assertEquals(INTERRUPTED_TEXT, rig.reply("m1")!!.text)
        assertIs<SendResult.Conflict>(rig.service.phoneResult("c1", result("m1", spend)))
        assertEquals(0, rig.count("spending"))
        assertEquals(1, rig.chats.messages("c1").count { it.role == "assistant" })
        // the same message again after the tick: the stored interrupted reply, no model run
        val again = rig.service.send("c1", req("m1", phone = false), "phone")
        assertIs<SendResult.Ok>(again)
        assertEquals(INTERRUPTED_TEXT, again.response.reply.text)
        assertTrue(rig.hpFake.bodies.isEmpty())
        rig.close()
    }

    @Test
    fun aResultThatGotThereFirstIsNeverOverwrittenByTheTick() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        assertIs<SendResult.Ok>(rig.service.phoneResult("c1", result("m1", answer)))
        rig.now = CHAT_T0 + 999_000
        assertEquals(0, rig.service.closeExpiredPhoneTurns())
        assertEquals("You are fine, Tim.", rig.reply("m1")!!.text)
        rig.close()
    }

    @Test
    fun twoChatsPendingAtOnceAndABusyChatIsSkippedWithoutDelayingTheOther() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat(); rig.chat("c2")
        rig.turn("m1", "c1")
        rig.turn("m2", "c2")
        rig.now = CHAT_T0 + 180_000
        val held = rig.service.turnLocks["c1"]!!
        held.lock()
        assertEquals(1, rig.service.closeExpiredPhoneTurns()) // c2 closed, c1 skipped
        assertEquals(STATE_PHONE_PENDING, rig.reply("m1")!!.request_state)
        assertEquals(INTERRUPTED_TEXT, rig.reply("m2")!!.text)
        held.unlock()
        assertEquals(1, rig.service.closeExpiredPhoneTurns())
        assertEquals(INTERRUPTED_TEXT, rig.reply("m1")!!.text)
        rig.close()
    }

    @Test
    fun theTickRunsWithoutAnySorterOrEmbedModel() = runBlocking<Unit> {
        val rig = ChatRig(sorter = null); rig.chat()
        rig.turn()
        rig.now = CHAT_T0 + 200_000
        assertEquals(1, rig.service.closeExpiredPhoneTurns())
        rig.close()
    }

    // ---- delete and eviction while pending ----------------------------------------------------------------

    @Test
    fun deletingAChatWhileItsReplyIsPendingKeepsItsRowsUntilTheTurnIsClosed() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.summarizer.busy = rig.service::chatBusy
        rig.turn()
        assertEquals(DeleteChatResult.Deleted, rig.service.delete("c1"))
        assertEquals(0, rig.summarizer.tick())
        assertEquals(2, rig.chats.messages("c1").size) // user + pending reply still there
        assertEquals(SendResult.NotFound, rig.service.phoneResult("c1", result("m1", spend)))
        rig.now = CHAT_T0 + 180_000
        rig.service.closeExpiredPhoneTurns()
        assertEquals(1, rig.summarizer.tick()) // now the note is written and the rows go
        assertTrue(rig.chats.messages("c1").isEmpty())
        assertEquals(0, rig.count("spending"))
        rig.close()
    }

    @Test
    fun theEleventhChatEvictingAPendingOneDoesNotLetTheResultLandOnIt() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.summarizer.busy = rig.service::chatBusy
        rig.turn()
        for (i in 2..11) {
            rig.now += 1_000
            rig.chat("c$i")
        }
        assertNull(rig.chats.chat("c1")) // c1 was the least recently used and is archived
        assertEquals(SendResult.NotFound, rig.service.phoneResult("c1", result("m1", spend)))
        assertEquals(0, rig.summarizer.tick())
        assertEquals(2, rig.chats.messages("c1").size)
        rig.close()
    }

    // ---- routes -------------------------------------------------------------------------------------------

    private fun ApplicationTestBuilder.start(rig: ChatRig) {
        val config = testConfig("unused").let { it.copy(tokens = it.tokens + ("calendar" to CAL_TOKEN)) }
        application { module(config, rig.store, { rig.now }, chat = rig.service, approvalSink = rig.sink) }
    }

    private suspend fun HttpClient.api(path: String, body: String? = null, token: String? = PHONE_BEARER, method: String = "POST"): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return if (method == "GET") get(path, build) else post(path, build)
    }

    private fun sendBody(id: String, phone: Boolean) = testJson.encodeToString(SendMessageRequest.serializer(), req(id, phone = phone))
    private fun resultBody(id: String, decision: String) = testJson.encodeToString(PhoneResultRequest.serializer(), result(id, decision))

    @Test
    fun routePhoneTokenGets202AndLaptopTokenGets200() = testApplication {
        val rig = ChatRig(); runBlocking { rig.chat() }
        start(rig)
        val accepted = client.api("/v1/chat/c1/message", sendBody("m1", true))
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        val turn = testJson.decodeFromString(PhoneTurnDto.serializer(), accepted.bodyAsText())
        assertEquals("r:m1", turn.turnId)
        rig.scriptHp(answer)
        val laptop = client.api("/v1/chat/c1/message", sendBody("m2", true), token = TOKEN)
        assertEquals(HttpStatusCode.ServiceUnavailable, laptop.status) // m1 is still with the phone: "still answering"
        val detail = testJson.decodeFromString(ChatDetailResponse.serializer(), client.api("/v1/chat/c1", method = "GET").bodyAsText())
        assertEquals(listOf("user"), detail.messages.map { it.role })
        rig.close()
    }

    @Test
    fun routeLaptopTokenWithPhoneBrainTrueIsAnswered200() = testApplication {
        val rig = ChatRig(); runBlocking { rig.chat() }
        start(rig)
        rig.scriptHp(answer)
        val r = client.api("/v1/chat/c1/message", sendBody("m1", true), token = TOKEN)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("You are fine, Tim.", testJson.decodeFromString(SendMessageResponse.serializer(), r.bodyAsText()).reply.text)
        rig.close()
    }

    @Test
    fun routePhoneResultAuthAndInputRules() = testApplication {
        val rig = ChatRig(); runBlocking { rig.chat(); rig.turn() }
        start(rig)
        val path = "/v1/chat/c1/phone-result"
        assertEquals(HttpStatusCode.Unauthorized, client.api(path, resultBody("m1", answer), token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.api(path, resultBody("m1", answer), token = "nope").status)
        assertEquals(HttpStatusCode.Forbidden, client.api(path, resultBody("m1", answer), token = TOKEN).status) // laptop
        assertEquals(HttpStatusCode.Forbidden, client.api(path, resultBody("m1", answer), token = CAL_TOKEN).status) // calendar
        assertEquals(HttpStatusCode.BadRequest, client.api(path, "{not json").status)
        assertEquals(HttpStatusCode.BadRequest, client.api(path, testJson.encodeToString(PhoneResultRequest.serializer(), PhoneResultRequest("r:zzz", "m1", answer))).status)
        assertEquals(HttpStatusCode.NotFound, client.api("/v1/chat/ghost/phone-result", resultBody("m1", answer)).status)
        assertEquals(HttpStatusCode.Conflict, client.api(path, resultBody("m9", answer)).status)
        val ok = client.api(path, resultBody("m1", answer))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("You are fine, Tim.", testJson.decodeFromString(SendMessageResponse.serializer(), ok.bodyAsText()).reply.text)
        assertEquals(HttpStatusCode.Conflict, client.api(path, resultBody("m1", answer)).status)
        rig.close()
    }

    @Test
    fun routeAnExpiredTurnGets409AndTheSpeedFieldsAreOnlyLogged() = testApplication {
        val rig = ChatRig(); runBlocking { rig.chat(); rig.turn() }
        start(rig)
        rig.now = CHAT_T0 + 180_000
        assertEquals(HttpStatusCode.Conflict, client.api("/v1/chat/c1/phone-result", resultBody("m1", answer)).status)
        assertEquals(INTERRUPTED_TEXT, rig.reply("m1")!!.text)
        rig.close()
    }

    @Test
    fun theHpAnswerAfterAPhoneFallbackStillUsesTheSharedFinishingPath() = runBlocking<Unit> {
        val rig = ChatRig(); rig.chat()
        rig.turn()
        rig.scriptHp(decisionJson("answer", "Fallback answer"))
        val r = rig.service.send("c1", req("m1", phone = false), "phone")
        assertIs<SendResult.Ok>(r)
        assertEquals("hp", parseExtras(rig.reply("m1")!!.card_json).brain)
        assertEquals("user", r.response.user.role)
        rig.close()
    }

    // ---- "Something else" on the phone path (PLAN 20.7 step 2) --------------------------------------------

    private val elseChip = ChoiceDto("suggest", "else", "Something else", "tok-else")

    private suspend fun ChatRig.question(source: String, brain: String = "code") {
        chat()
        chats.addUserMessage("c1", "s1", source, CHAT_T0)
        chats.finishReply("c1", "r:s1", "Did you mean something?") { it.copy(choices = listOf(elseChip), brain = brain, sourceMessageId = "s1") }
    }

    private fun tap(id: String, phone: Boolean = true) = SendMessageRequest(id, "Something else", CHAT_T0, "Today", elseChip, phone)

    @Test
    fun aSomethingElseTurnHandedToThePhoneCarriesTheSourceTextAndItsReplayAndFallbackUseIt() = runBlocking<Unit> {
        val rig = ChatRig(); rig.question("pick up the parcel from the post office")
        val first = rig.service.send("c1", tap("m2"), "phone")
        assertIs<SendResult.PhoneTurn>(first)
        assertTrue("post office" in first.turn.user, first.turn.user)
        assertFalse("Something else" in first.turn.user.substringAfter("Tim's message"), first.turn.user)
        val extras = parseExtras(rig.reply("m2")!!.card_json)
        assertEquals("s1", extras.sourceMessageId)
        assertFalse(extras.fromHp)
        // the replay rebuilds the pack from the source message
        rig.now += 30_000
        val again = rig.service.send("c1", tap("m2"), "phone")
        assertIs<SendResult.PhoneTurn>(again)
        assertTrue("post office" in again.turn.user)
        // the rung-5 fallback asks the HP about the source text
        rig.scriptHp(answer)
        val fallback = rig.service.send("c1", tap("m2", phone = false), "phone")
        assertIs<SendResult.Ok>(fallback)
        assertEquals("You are fine, Tim.", fallback.response.reply.text)
        assertTrue("post office" in rig.hpFake.bodies.single())
        assertEquals("s1", parseExtras(rig.reply("m2")!!.card_json).sourceMessageId)
        rig.close()
    }

    @Test
    fun theResultOfASomethingElseTurnKeepsTheSourceIdAndTakesDreamPermissionFromTheSourceText() = runBlocking<Unit> {
        val rig = ChatRig(); rig.question("I had a dream about the sea")
        val turn = rig.service.send("c1", tap("m2"), "phone")
        assertIs<SendResult.PhoneTurn>(turn)
        assertTrue("add_dream" in turn.turn.schema.toString())
        val r = rig.service.phoneResult("c1", result("m2", answer))
        assertIs<SendResult.Ok>(r)
        val stored = parseExtras(rig.reply("m2")!!.card_json)
        assertEquals("s1", stored.sourceMessageId)
        assertTrue(stored.dream)
        assertNull(rig.chats.message("m2")!!.request_state)
        rig.close()
    }

    @Test
    fun aSomethingElseTurnFromAnHpSuggestionNeverAsksTheHpAgainWhenThePhoneTurnEnds() = runBlocking<Unit> {
        val rig = ChatRig(); rig.question("pick up the parcel from the post office", brain = "hp")
        val first = rig.service.send("c1", tap("m2"), "phone")
        assertIs<SendResult.PhoneTurn>(first)
        assertTrue(parseExtras(rig.reply("m2")!!.card_json).fromHp)
        rig.scriptHp(answer)
        val fallback = rig.service.send("c1", tap("m2", phone = false), "phone")
        assertIs<SendResult.Ok>(fallback)
        assertEquals("That needs the laptop to be on.", fallback.response.reply.text)
        assertTrue(rig.hpFake.bodies.isEmpty())
        rig.close()
    }}
