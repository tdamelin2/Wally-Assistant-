package dev.tim.assistant.ai

import dev.tim.assistant.data.ChatApi
import dev.tim.assistant.data.ServerConfig
import dev.tim.assistant.model.ApprovalDecisionResponse
import dev.tim.assistant.model.CODEX_ASKED_TEXT
import dev.tim.assistant.model.ChatBrainResponse
import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChatListResponse
import dev.tim.assistant.model.ChatMessageDto
import dev.tim.assistant.model.ChatSummaryDto
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.NewChatResponse
import dev.tim.assistant.model.PhoneResultRequest
import dev.tim.assistant.model.PhoneTurnDto
import dev.tim.assistant.model.ROUTE_AUTO
import dev.tim.assistant.model.ROUTE_CODEX
import dev.tim.assistant.model.ROUTE_MODEL
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.model.UndoResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The HP chat client: send, replay after a lost answer, the ordered offline queue, Undo, card decisions, archive notices. */
class ChatControllerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class MapKv(val map: MutableMap<String, String> = HashMap()) : ChatKv {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    /** A stand-in for the HP: stores chats and replies by messageId, can go offline or lose one answer. */
    private inner class FakeHp {
        var online = true
        var loseNextMessageAnswer = false
        var modelRuns = 0
        var archivedTitle: String? = null
        var undoAnswer = UndoResponse(true, "Removed.")
        var approvalStatus = HttpStatusCode.OK
        var sendFailure: HttpStatusCode? = null
        /** The /v1/chat/brain answer: "xps", "hp", or null = the route fails (503). */
        var brainAnswer: String? = "xps"
        /** When set, a message send waits for it (so a test can look at the typing state). */
        var sendGate: CompletableDeferred<Unit>? = null
        val received = ArrayList<String>()
        /** The phoneBrain flag of every message request, in order. */
        val receivedPhoneBrain = ArrayList<Boolean>()
        /** The route of every message request, in order. */
        val receivedRoutes = ArrayList<String>()
        /** When true, every message request with phoneBrain = true is answered 202 with a phone turn. */
        var phoneTurns = false
        /** When set, the next phone-result answers this status (else 200 with a stored reply). */
        var phoneResultStatus: HttpStatusCode? = null
        /** With a 409 on phone-result: the server had already stored the reply. */
        var storeReplyOnRefusal = false
        /** The next message request answers 503 "Wally is still answering". */
        var busyOnce = false
        var brainCalls = 0
        /** How many times a chat was read (GET /v1/chat/{id}). */
        var chatReads = 0
        val phoneResults = ArrayList<PhoneResultRequest>()
        private val sent = HashMap<String, Pair<String, Long>>()
        val chats = LinkedHashMap<String, ChatSummaryDto>()
        val messages = HashMap<String, MutableList<ChatMessageDto>>()
        private val replies = HashMap<String, SendMessageResponse>()
        val engine = MockEngine { request -> handle(request) }

        private fun store(chatId: String, messageId: String, text: String, at: Long, replyText: String): SendMessageResponse =
            replies.getOrPut(messageId) {
                modelRuns++
                val user = ChatMessageDto(messageId, "user", text, at)
                val reply = ChatMessageDto("r-$messageId", "assistant", replyText, at + 1)
                messages.getValue(chatId) += user
                messages.getValue(chatId) += reply
                SendMessageResponse(user, reply, null)
            }

        private fun body(request: HttpRequestData): String = (request.body as TextContent).text

        private fun MockRequestHandleScope.ok(text: String) = respond(text, HttpStatusCode.OK, jsonHeaders)

        private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData = run {
            if (!online) throw IOException("offline")
            val path = request.url.encodedPath
            val parts = path.removePrefix("/v1/").split('/')
            when {
                path == "/v1/chat/brain" -> {
                    brainCalls++
                    val answer = brainAnswer ?: return@run respond("""{"error":"down"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                    ok(json.encodeToString(ChatBrainResponse(answer)))
                }
                path == "/v1/chat/list" -> ok(json.encodeToString(ChatListResponse(chats.values.toList(), 10, chats.size)))
                path == "/v1/chat" && request.method.value == "POST" -> {
                    val req = json.decodeFromString<NewChatRequest>(body(request))
                    val chat = chats.getOrPut(req.id) { ChatSummaryDto(req.id, "New chat", null, 1, false) }
                    messages.getOrPut(req.id) { ArrayList() }
                    ok(json.encodeToString(NewChatResponse(chat, archivedTitle)))
                }
                parts.size == 3 && parts[2] == "message" -> {
                    val chatId = parts[1]
                    if (chatId !in chats) return@run respond("""{"error":"no such chat"}""", HttpStatusCode.NotFound, jsonHeaders)
                    val req = json.decodeFromString<SendMessageRequest>(body(request))
                    sendGate?.await()
                    if (sendFailure != null) return@run respond("""{"error":"no model is reachable"}""", sendFailure!!, jsonHeaders)
                    receivedPhoneBrain += req.phoneBrain
                    receivedRoutes += req.route
                    sent[req.messageId] = req.text to req.createdAt
                    if (busyOnce) {
                        busyOnce = false
                        return@run respond("""{"error":"Wally is still answering"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                    }
                    if (phoneTurns && req.phoneBrain) {
                        return@run respond(
                            json.encodeToString(PhoneTurnDto("r:${req.messageId}", req.messageId, "sys", "usr", JsonObject(emptyMap()), 181_000L)),
                            HttpStatusCode.Accepted,
                            jsonHeaders,
                        )
                    }
                    received += req.text
                    val stored = replies.getOrPut(req.messageId) {
                        modelRuns++
                        val user = ChatMessageDto(req.messageId, "user", req.text, req.createdAt)
                        val reply = ChatMessageDto("r-${req.messageId}", "assistant", if (req.route == ROUTE_CODEX) CODEX_ASKED_TEXT else "Reply to ${req.text}", req.createdAt + 1)
                        messages.getValue(chatId) += user
                        messages.getValue(chatId) += reply
                        SendMessageResponse(user, reply, null)
                    }
                    if (loseNextMessageAnswer) {
                        loseNextMessageAnswer = false
                        throw IOException("lost answer")
                    }
                    ok(json.encodeToString(stored))
                }
                parts.size == 3 && parts[2] == "phone-result" -> {
                    val chatId = parts[1]
                    if (chatId !in chats) return@run respond("""{"error":"no such chat"}""", HttpStatusCode.NotFound, jsonHeaders)
                    val req = json.decodeFromString<PhoneResultRequest>(body(request))
                    phoneResults += req
                    val (text, at) = sent.getValue(req.messageId)
                    val status = phoneResultStatus
                    if (status != null) {
                        if (status == HttpStatusCode.NotFound) { chats.remove(chatId); messages.remove(chatId) }
                        if (storeReplyOnRefusal) store(chatId, req.messageId, text, at, "Server answered")
                        return@run respond("""{"error":"refused"}""", status, jsonHeaders)
                    }
                    ok(json.encodeToString(store(chatId, req.messageId, text, at, "Phone answered")))
                }
                parts.size == 2 && parts[0] == "chat" && request.method.value == "GET" -> {
                    chatReads++
                    val chat = chats[parts[1]] ?: return@run respond("""{"error":"no such chat"}""", HttpStatusCode.NotFound, jsonHeaders)
                    ok(json.encodeToString(ChatDetailResponse(chat, messages[parts[1]].orEmpty())))
                }
                parts.size == 2 && parts[0] == "chat" && request.method.value == "DELETE" -> {
                    chats.remove(parts[1]); messages.remove(parts[1])
                    respond("", HttpStatusCode.NoContent)
                }
                parts.size == 4 && parts[2] == "undo" -> ok(json.encodeToString(undoAnswer))
                parts.size == 2 && parts[0] == "approvals" ->
                    if (approvalStatus == HttpStatusCode.OK) ok(json.encodeToString(ApprovalDecisionResponse("DONE", "Done.")))
                    else respond("""{"error":"This card was already decided."}""", approvalStatus, jsonHeaders)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    /** A stand-in for the phone's model: scripted partials, a result, an optional gate or hang. */
    private class FakeBrain(var model: String? = "gemma") : PhoneBrain {
        var raw: String? = """{"kind":"answer","reply":"Hi there"}"""
        var partials: List<String> = listOf("""{"kind":"answer","reply":"Hi""", """{"kind":"answer","reply":"Hi there""")
        var gate: CompletableDeferred<Unit>? = null
        var hang = false
        var calls = 0
        var cancelled = false
        var prewarms = 0
        override fun prewarm() { prewarms++ }
        override fun status() = PhoneBrainStatus(model, model?.let { 1L })
        override suspend fun generate(system: String, user: String, schema: JsonObject, onPartial: (String) -> Unit): PhoneBrainResult {
            calls++
            partials.forEach(onPartial)
            try {
                gate?.await()
                if (hang) delay(60_000)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            return PhoneBrainResult(raw, 120, 20.0)
        }
        override fun unload() {}
    }

    private class FakeDevice(var battery: Int = 80, var save: Boolean = false, var fg: Boolean = true) : DeviceState {
        var listener: ((Boolean) -> Unit)? = null
        override fun batteryPercent() = battery
        override fun powerSave() = save
        override fun foreground() = fg
        override fun setForegroundListener(listener: ((Boolean) -> Unit)?) { this.listener = listener }
        fun setForeground(value: Boolean) { fg = value; listener?.invoke(value) }
    }

    private fun controller(
        hp: FakeHp,
        kv: MapKv = MapKv(),
        screen: String? = "Tasks",
        brain: PhoneBrain = NoPhoneBrain,
        device: DeviceState = NoDeviceState,
        timeoutMs: Long = 60_000,
        busyMs: Long = 30_000,
        pollMs: Long = 40,
        pollWindowMs: Long = 400,
    ): ChatController {
        val api = ChatApi({ ServerConfig("http://hp", "tok") }, hp.engine)
        var n = 0
        return ChatController(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), api, kv, { 1_000L }, { screen }, { "id-${++n}" }, brain, device, timeoutMs, busyMs, pollMs, pollWindowMs)
    }

    private suspend fun waitFor(what: String, limitMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + limitMs
        while (!cond() && System.currentTimeMillis() < end) delay(10)
        assertTrue(cond(), "timed out waiting for $what")
    }

    // ---- Phase 20: routes and the Codex answer polling ----

    private fun hpOpenChat(hp: FakeHp, c: ChatController) = runBlocking {
        c.refresh()
        c.newChat()
        c.setChatVisible(true)
    }

    /** The answer text of Codex arriving later in [chatId]: an assistant message after the "Asked Codex" reply. */
    private fun FakeHp.codexAnswers(chatId: String, at: Long = 5_000L) {
        messages.getValue(chatId) += ChatMessageDto("codex-1", "assistant", "Codex says hi", at)
    }

    @Test
    fun each_route_is_sent_as_asked_and_codex_is_never_a_phone_turn() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val c = controller(hp, brain = FakeBrain(), device = FakeDevice())
        c.sendNow("one", route = ROUTE_AUTO)
        c.sendNow("two", route = ROUTE_MODEL)
        c.sendNow("three", route = ROUTE_CODEX)
        assertEquals(listOf(ROUTE_AUTO, ROUTE_MODEL, ROUTE_CODEX), hp.receivedRoutes)
        assertEquals(false, hp.receivedPhoneBrain.last(), "codex never asks for the phone's brain")
        assertEquals(CODEX_ASKED_TEXT, c.state.value.messages.last().text)
    }

    @Test
    fun a_codex_message_queued_offline_is_resent_as_codex_after_reconnecting() = runBlocking<Unit> {
        val hp = FakeHp()
        val kv = MapKv()
        val c = controller(hp, kv)
        c.refresh()
        c.newChat()
        hp.online = false
        c.sendNow("later", route = ROUTE_CODEX)
        assertEquals(1, c.state.value.queued.size)
        assertEquals(ROUTE_CODEX, c.state.value.queued.single().route)
        // A new controller over the same storage still resends it as codex.
        hp.online = true
        val again = controller(hp, kv)
        again.refresh()
        assertEquals(listOf(ROUTE_CODEX), hp.receivedRoutes)
        assertTrue(again.state.value.queued.isEmpty())
    }

    @Test
    fun an_outbox_item_from_the_previous_version_resends_as_auto() = runBlocking<Unit> {
        val hp = FakeHp()
        val kv = MapKv()
        val c = controller(hp, kv)
        c.refresh()
        val chatId = c.newChat()
        hp.online = false
        c.sendNow("old", route = ROUTE_MODEL)
        // Strip the route field, as a 1.17.0 outbox would have it.
        val stripped = kv.map.getValue(CHAT_OUTBOX_KEY).replace(Regex(",\"route\":\"[a-z]+\""), "")
        assertFalse(stripped.contains("route"))
        kv.map[CHAT_OUTBOX_KEY] = stripped
        hp.online = true
        val again = controller(hp, kv)
        again.refresh()
        assertEquals(listOf(ROUTE_AUTO), hp.receivedRoutes)
        assertNotNull(chatId)
    }

    @Test
    fun polling_stops_when_a_newer_assistant_message_arrives() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        hpOpenChat(hp, c)
        val chatId = c.state.value.currentId!!
        c.sendNow("ask", route = ROUTE_CODEX)
        hp.codexAnswers(chatId)
        waitFor("the Codex answer") { c.state.value.messages.any { it.text == "Codex says hi" } }
        val calls = hp.chatReads
        delay(200)
        assertEquals(calls, hp.chatReads, "no more reads once the answer is there")
    }

    @Test
    fun polling_stops_at_the_deadline() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp, pollMs = 40, pollWindowMs = 200)
        hpOpenChat(hp, c)
        c.sendNow("ask", route = ROUTE_CODEX)
        delay(500)
        val calls = hp.chatReads
        delay(200)
        assertEquals(calls, hp.chatReads)
        assertTrue(calls >= 1)
    }

    @Test
    fun polling_does_not_start_off_screen_and_stops_when_the_screen_is_left() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        c.refresh()
        c.newChat()
        c.sendNow("not visible", route = ROUTE_CODEX)
        val before = hp.chatReads
        delay(200)
        assertEquals(before, hp.chatReads, "never visible: no polling")

        c.setChatVisible(true)
        c.sendNow("visible", route = ROUTE_CODEX)
        waitFor("a poll read") { hp.chatReads > before }
        c.setChatVisible(false)
        delay(60)
        val after = hp.chatReads
        delay(200)
        assertEquals(after, hp.chatReads)
    }

    @Test
    fun polling_stops_when_another_chat_is_selected() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        hpOpenChat(hp, c)
        val first = c.state.value.currentId!!
        c.sendNow("ask", route = ROUTE_CODEX)
        val other = c.newChat()
        c.select(other)
        delay(100)
        val after = hp.chatReads
        delay(200)
        assertEquals(after, hp.chatReads)
        assertEquals(other, c.state.value.currentId)
        assertTrue(first != other)
    }
    @Test
    fun without_a_phone_brain_or_device_the_flag_is_never_true() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val c = controller(hp)
        assertTrue(c.sendNow("hello"))
        assertEquals(listOf(false), hp.receivedPhoneBrain)
        assertEquals(0, hp.phoneResults.size)
    }

    @Test
    fun a_202_is_generated_on_the_phone_and_the_result_replaces_the_bubble() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val brain = FakeBrain()
        brain.gate = CompletableDeferred()
        val c = controller(hp, brain = brain, device = FakeDevice())
        c.send("hello there")
        waitFor("streaming text") { c.state.value.streamingText == "Hi there" }
        assertEquals(1, c.state.value.queued.size)
        brain.gate!!.complete(Unit)
        waitFor("the reply") { c.state.value.messages.any { it.text == "Phone answered" } }
        val s = c.state.value
        assertEquals(listOf(true), hp.receivedPhoneBrain)
        assertTrue(s.queued.isEmpty())
        assertNull(s.streamingText)
        assertFalse(s.typing)
        val result = hp.phoneResults.single()
        assertEquals("""{"kind":"answer","reply":"Hi there"}""", result.decision)
        assertEquals(120L, result.firstTokenMs)
        assertEquals("r:" + result.messageId, result.turnId)
        assertEquals(0, hp.brainCalls, "no brain probe while the phone writes")
    }

    @Test
    fun a_null_result_resends_once_with_phone_brain_false() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val brain = FakeBrain().also { it.raw = null }
        val c = controller(hp, brain = brain, device = FakeDevice())
        c.sendNow("hello there")
        assertEquals(listOf(true, false), hp.receivedPhoneBrain)
        assertTrue(hp.phoneResults.isEmpty())
        assertEquals(listOf("hello there", "Reply to hello there"), c.state.value.messages.map { it.text })
        assertTrue(c.state.value.queued.isEmpty())
        assertNull(c.state.value.streamingText)
    }

    @Test
    fun a_generation_that_takes_too_long_falls_back_to_the_hp() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val brain = FakeBrain().also { it.hang = true }
        val c = controller(hp, brain = brain, device = FakeDevice(), timeoutMs = 150)
        c.sendNow("hello there")
        assertEquals(listOf(true, false), hp.receivedPhoneBrain)
        assertTrue(brain.cancelled)
        assertTrue(c.state.value.queued.isEmpty())
    }

    @Test
    fun a_server_error_or_offline_on_the_result_falls_back_once() = runBlocking<Unit> {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadRequest, HttpStatusCode.Forbidden)) {
            val hp = FakeHp()
            hp.phoneTurns = true
            hp.phoneResultStatus = status
            val c = controller(hp, brain = FakeBrain(), device = FakeDevice())
            c.sendNow("hello there")
            assertEquals(listOf(true, false), hp.receivedPhoneBrain, "status $status")
            assertTrue(c.state.value.queued.isEmpty(), "status $status")
        }
    }

    @Test
    fun a_409_with_a_stored_reply_reloads_that_chat_and_empties_the_outbox() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        hp.phoneResultStatus = HttpStatusCode.Conflict
        hp.storeReplyOnRefusal = true
        val c = controller(hp, brain = FakeBrain(), device = FakeDevice())
        c.sendNow("hello there")
        assertEquals(listOf(true), hp.receivedPhoneBrain)
        assertTrue(c.state.value.queued.isEmpty())
        assertEquals(listOf("hello there", "Server answered"), c.state.value.messages.map { it.text })
    }

    @Test
    fun a_409_without_a_stored_reply_resends_with_phone_brain_false() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        hp.phoneResultStatus = HttpStatusCode.Conflict
        val c = controller(hp, brain = FakeBrain(), device = FakeDevice())
        c.sendNow("hello there")
        assertEquals(listOf(true, false), hp.receivedPhoneBrain)
        assertTrue(c.state.value.queued.isEmpty())
    }

    @Test
    fun a_404_on_the_result_for_a_gone_chat_removes_the_item() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        hp.phoneResultStatus = HttpStatusCode.NotFound
        val c = controller(hp, brain = FakeBrain(), device = FakeDevice())
        c.sendNow("hello there")
        assertEquals(listOf(true), hp.receivedPhoneBrain)
        assertTrue(c.state.value.queued.isEmpty())
    }

    @Test
    fun still_answering_keeps_the_message_queued_online_and_retries_once() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.busyOnce = true
        val c = controller(hp, brain = FakeBrain(), device = FakeDevice(), busyMs = 100)
        c.sendNow("hello there")
        assertEquals(1, c.state.value.queued.size)
        assertFalse(c.state.value.offline)
        waitFor("the retry") { c.state.value.queued.isEmpty() }
        assertEquals(2, hp.receivedPhoneBrain.size)
        assertFalse(c.state.value.offline)
    }

    @Test
    fun a_restart_with_an_open_phone_item_sends_it_again_from_the_foreground() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val kv = MapKv()
        hp.chats["c1"] = ChatSummaryDto("c1", "Chat", null, 1, false)
        hp.messages["c1"] = ArrayList()
        kv.put(CHAT_CURRENT_KEY, "c1")
        kv.put(CHAT_OUTBOX_KEY, """[{"messageId":"m1","chatId":"c1","text":"hello","createdAt":900,"phoneExpiresAt":5000}]""")
        val c = controller(hp, kv, brain = FakeBrain(), device = FakeDevice())
        c.refresh()
        assertEquals(listOf(true), hp.receivedPhoneBrain)
        assertTrue(c.state.value.queued.isEmpty())
        assertEquals(1, hp.phoneResults.size)
    }

    @Test
    fun the_background_never_sends_true_and_skips_an_open_phone_item() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val kv = MapKv()
        hp.chats["c1"] = ChatSummaryDto("c1", "Chat", null, 1, false)
        hp.messages["c1"] = ArrayList()
        kv.put(CHAT_CURRENT_KEY, "c1")
        kv.put(CHAT_OUTBOX_KEY, """[{"messageId":"m1","chatId":"c1","text":"hello","createdAt":900,"phoneExpiresAt":5000}]""")
        val device = FakeDevice(fg = false)
        val c = controller(hp, kv, brain = FakeBrain(), device = device)
        c.refresh()
        assertTrue(hp.receivedPhoneBrain.isEmpty(), "an open phone turn is not resent from the background")
        assertEquals(1, c.state.value.queued.size)
        // after the turn expired it is sent normally, never with true while in the background
        kv.put(CHAT_OUTBOX_KEY, """[{"messageId":"m1","chatId":"c1","text":"hello","createdAt":900,"phoneExpiresAt":500}]""")
        val later = controller(hp, kv, brain = FakeBrain(), device = device)
        later.drain()
        assertEquals(listOf(false), hp.receivedPhoneBrain)
        assertTrue(later.state.value.queued.isEmpty())
    }

    @Test
    fun a_low_battery_or_power_saving_or_a_switch_turned_off_sends_false() = runBlocking<Unit> {
        for (case in 0..2) {
            val hp = FakeHp()
            hp.phoneTurns = true
            val kv = MapKv()
            val device = FakeDevice(battery = if (case == 0) 19 else 80, save = case == 1)
            if (case == 2) kv.put(PHONE_BRAIN_ENABLED_KEY, "false")
            val c = controller(hp, kv, brain = FakeBrain(), device = device)
            c.sendNow("hello there")
            assertEquals(listOf(false), hp.receivedPhoneBrain, "case $case")
        }
    }

    private suspend fun prewarmsAfterForeground(brainAnswer: String?, device: FakeDevice, model: String? = "gemma", enterForeground: Boolean = true): Int {
        val hp = FakeHp()
        hp.brainAnswer = brainAnswer
        val brain = FakeBrain(model)
        controller(hp, brain = brain, device = device)
        if (enterForeground) device.setForeground(true) else device.setForeground(false)
        delay(300)
        return brain.prewarms
    }

    @Test
    fun entering_the_foreground_with_the_laptop_off_prewarms_the_phone_brain() = runBlocking<Unit> {
        assertEquals(1, prewarmsAfterForeground("hp", FakeDevice()))
    }

    @Test
    fun the_phone_brain_is_not_prewarmed_with_xps_a_closed_gate_or_in_the_background() = runBlocking<Unit> {
        assertEquals(0, prewarmsAfterForeground("xps", FakeDevice()), "laptop awake")
        assertEquals(0, prewarmsAfterForeground(null, FakeDevice()), "brain route failed")
        assertEquals(0, prewarmsAfterForeground("hp", FakeDevice(battery = 19)), "low battery")
        assertEquals(0, prewarmsAfterForeground("hp", FakeDevice(save = true)), "power saving")
        assertEquals(0, prewarmsAfterForeground("hp", FakeDevice(), model = null), "no model file")
        assertEquals(0, prewarmsAfterForeground("hp", FakeDevice(), enterForeground = false), "background")
    }

    @Test
    fun losing_the_foreground_cancels_the_generation_without_a_resend_and_keeps_the_item() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val brain = FakeBrain().also { it.gate = CompletableDeferred() }
        val device = FakeDevice()
        val c = controller(hp, brain = brain, device = device)
        c.send("hello there")
        waitFor("streaming") { c.state.value.streamingText != null }
        device.setForeground(false)
        waitFor("the stop") { brain.cancelled && c.state.value.streamingText == null }
        assertEquals(listOf(true), hp.receivedPhoneBrain)
        assertEquals(1, c.state.value.queued.size)
        assertTrue(hp.phoneResults.isEmpty())
        assertFalse(c.state.value.typing)
    }

    @Test
    fun the_streaming_text_is_shown_only_in_its_own_chat() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.phoneTurns = true
        val brain = FakeBrain().also { it.gate = CompletableDeferred() }
        val c = controller(hp, brain = brain, device = FakeDevice())
        c.refresh()
        val other = c.newChat()
        val mine = c.newChat()
        c.send("hello there")
        waitFor("streaming") { c.state.value.streamingText == "Hi there" }
        c.select(other)
        assertNull(c.state.value.streamingText)
        c.select(mine)
        assertEquals("Hi there", c.state.value.streamingText)
        brain.gate!!.complete(Unit)
        waitFor("the reply") { c.state.value.queued.isEmpty() }
    }

    @Test
    fun a_message_creates_a_chat_and_shows_the_reply() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        assertTrue(c.sendNow("add task buy milk"))
        val s = c.state.value
        assertEquals(listOf("add task buy milk", "Reply to add task buy milk"), s.messages.map { it.text })
        assertTrue(s.queued.isEmpty())
        assertFalse(s.offline)
        assertFalse(s.typing)
        assertEquals(1, hp.chats.size)
    }

    /** Sends one message with the HP answering slowly; returns whether slowBrain was seen true while it was typing. */
    private suspend fun slowBrainSeenWhileTyping(brain: String?): Boolean {
        val hp = FakeHp()
        hp.brainAnswer = brain
        hp.sendGate = CompletableDeferred()
        val c = controller(hp)
        c.send("hello")
        var seen = false
        val end = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < end) {
            if (c.state.value.typing && c.state.value.slowBrain) { seen = true; break }
            delay(20)
        }
        assertTrue(c.state.value.typing, "the send is still waiting, so typing stays on")
        hp.sendGate!!.complete(Unit)
        val done = System.currentTimeMillis() + 3_000
        while (c.state.value.typing && System.currentTimeMillis() < done) delay(20)
        assertFalse(c.state.value.typing)
        assertFalse(c.state.value.slowBrain)
        return seen
    }

    @Test
    fun the_hp_brain_turns_the_slow_mode_flag_on_while_typing() = runBlocking<Unit> {
        assertTrue(slowBrainSeenWhileTyping("hp"))
    }

    @Test
    fun the_xps_brain_never_turns_the_slow_mode_flag_on() = runBlocking<Unit> {
        assertFalse(slowBrainSeenWhileTyping("xps"))
    }

    @Test
    fun a_failed_brain_call_never_turns_the_slow_mode_flag_on() = runBlocking<Unit> {
        assertFalse(slowBrainSeenWhileTyping(null))
    }

    @Test
    fun blank_text_is_not_sent()= runBlocking<Unit> {
        val c = controller(FakeHp())
        assertFalse(c.sendNow("   "))
        assertFalse(c.send(""))
    }

    @Test
    fun a_lost_answer_is_resent_with_the_same_id_and_the_model_runs_once() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        c.refresh()
        c.newChat()
        hp.loseNextMessageAnswer = true
        c.sendNow("how much did I spend?")
        assertTrue(c.state.value.offline)
        assertEquals(1, c.state.value.queued.size)
        c.drain()
        assertEquals(1, hp.modelRuns)
        assertEquals(2, hp.received.size)
        val s = c.state.value
        assertTrue(s.queued.isEmpty())
        assertEquals(listOf("how much did I spend?", "Reply to how much did I spend?"), s.messages.map { it.text })
    }

    @Test
    fun an_offline_queue_keeps_its_order_and_is_sent_after_reconnect() = runBlocking<Unit> {
        val hp = FakeHp()
        val kv = MapKv()
        val c = controller(hp, kv)
        c.newChat()
        hp.online = false
        c.sendNow("one")
        c.sendNow("two")
        assertEquals(listOf("one", "two"), c.state.value.queued.map { it.text })
        assertTrue(c.state.value.offline)
        // Every queued message kept the screen captured at enqueue.
        assertTrue(c.state.value.queued.all { it.screen == "Tasks" })
        hp.online = true
        c.drain()
        assertEquals(listOf("one", "two"), hp.received)
        assertTrue(c.state.value.queued.isEmpty())
        assertFalse(c.state.value.offline)
        assertEquals("[]", kv.get(CHAT_OUTBOX_KEY))
    }

    @Test
    fun the_queue_survives_a_restart_with_the_same_ids() = runBlocking<Unit> {
        val hp = FakeHp()
        val kv = MapKv()
        val first = controller(hp, kv)
        hp.online = false
        first.sendNow("later")
        val id = first.state.value.queued.single().messageId
        val reopened = controller(hp, kv)
        assertEquals(listOf(id), reopened.state.value.queued.map { it.messageId })
        hp.online = true
        reopened.drain()
        assertEquals(listOf("later"), hp.received)
        assertEquals(1, hp.modelRuns)
    }

    @Test
    fun a_choice_is_sent_as_the_typed_choice() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        c.newChat()
        c.sendNow("Calculus II", ChoiceDto("course", "c2", "Calculus II", "tok"))
        assertEquals(listOf("Calculus II"), hp.received)
    }

    @Test
    fun undo_shows_the_servers_answer_in_both_cases() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        c.sendNow("add task x")
        val chatId = c.state.value.currentId!!
        c.undo(chatId, "a1")
        assertEquals("Removed.", c.state.value.notice)
        hp.undoAnswer = UndoResponse(false, "Too late to undo this one.")
        c.undo(chatId, "a1")
        assertEquals("Too late to undo this one.", c.state.value.notice)
    }

    @Test
    fun a_409_card_decision_returns_the_server_message_and_keeps_the_messages() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        c.sendNow("move my exam")
        val before = c.state.value.messages
        hp.approvalStatus = HttpStatusCode.Conflict
        val out = c.decideApproval("ap1", true)
        assertFalse(out.ok)
        assertEquals("This card was already decided.", out.message)
        assertEquals(before, c.state.value.messages)
        hp.approvalStatus = HttpStatusCode.OK
        assertTrue(c.decideApproval("ap1", false).ok)
    }

    @Test
    fun a_503_or_404_on_a_card_decision_shows_the_server_text_and_writes_nothing() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        hp.approvalStatus = HttpStatusCode.ServiceUnavailable
        val busy = c.decideApproval("ap1", true)
        assertFalse(busy.ok)
        assertEquals("This card was already decided.", busy.message)
        hp.approvalStatus = HttpStatusCode.NotFound
        assertFalse(c.decideApproval("ap1", true).ok)
        assertTrue(c.state.value.messages.isEmpty())
    }

    @Test
    fun a_503_on_send_keeps_the_message_queued_and_the_same_id_retries() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        c.newChat()
        hp.sendFailure = HttpStatusCode.ServiceUnavailable
        c.sendNow("hello")
        val id = c.state.value.queued.single().messageId
        hp.sendFailure = null
        c.drain()
        assertTrue(c.state.value.queued.isEmpty())
        assertEquals(1, hp.modelRuns)
        assertEquals(id, c.state.value.messages.first().id)
    }

    @Test
    fun deleting_a_chat_accepts_a_204_and_drops_it_from_the_list() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        val id = c.newChat()
        c.delete(id)
        assertTrue(c.state.value.chats.none { it.id == id })
        assertTrue(hp.chats.isEmpty())
    }

    @Test
    fun a_decision_while_offline_says_so_and_goes_nowhere()= runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        hp.online = false
        val out = c.decideApproval("ap1", true)
        assertFalse(out.ok)
        assertTrue(c.state.value.offline)
    }

    @Test
    fun the_title_of_an_archived_chat_is_surfaced() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.archivedTitle = "Old plans"
        val c = controller(hp)
        c.newChat()
        val notice = c.state.value.notice
        assertNotNull(notice)
        assertTrue("Old plans" in notice)
        c.clearNotice()
        assertNull(c.state.value.notice)
    }

    @Test
    fun a_new_chat_made_offline_exists_locally_and_is_created_by_the_first_send() = runBlocking<Unit> {
        val hp = FakeHp()
        val c = controller(hp)
        hp.online = false
        val id = c.newChat()
        assertEquals(id, c.state.value.currentId)
        assertTrue(hp.chats.isEmpty())
        c.sendNow("hello")
        hp.online = true
        c.drain()
        assertTrue(id in hp.chats)
        assertEquals(1, hp.modelRuns)
    }

    @Test
    fun the_list_sorts_pinned_first_then_newest_and_opens_the_newest_personal_chat() = runBlocking<Unit> {
        val hp = FakeHp()
        hp.chats["w"] = ChatSummaryDto("w", "Weekly report", null, 5, true)
        hp.chats["a"] = ChatSummaryDto("a", "Old", null, 10, false)
        hp.chats["b"] = ChatSummaryDto("b", "Newer", null, 20, false)
        val c = controller(hp)
        c.refresh()
        assertEquals(listOf("w", "b", "a"), c.state.value.chats.map { it.id })
        assertEquals("b", c.state.value.currentId)
    }
}
