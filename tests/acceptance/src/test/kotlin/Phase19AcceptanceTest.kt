import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.NoDeviceState
import dev.tim.assistant.ai.NoPhoneBrain
import dev.tim.assistant.ai.PhoneBrainGate
import dev.tim.assistant.ai.ReplyStream
import dev.tim.assistant.ai.decisionSchema
import dev.tim.assistant.ai.mentionsDreams
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.PhoneResultRequest
import dev.tim.assistant.model.PhoneTurnDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.chat.ChatRunner
import dev.tim.assistant.server.chat.ChatService
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.INTERRUPTED_TEXT
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.chat.UndoService
import dev.tim.assistant.server.chat.ladder.DecisionPrompt
import dev.tim.assistant.server.chat.ladder.PromptBrain
import dev.tim.assistant.server.chat.ladder.PromptBudget
import dev.tim.assistant.server.chat.parseExtras
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val P19_T0: Long = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli()
private val json19 = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val PHONE_TOKEN = "phone-token"
private const val LAPTOP_TOKEN = "lap-token"
private const val CAL_TOKEN = "cal-token"

/** Not code-handled, not a command: it reaches the model rungs. */
private const val OFFBEAT = "tell me something encouraging about spreadsheets"

private fun p19Root(): File {
    var d: File? = File("").absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return checkNotNull(d)
}

private fun p19Text(rel: String): String = String(Files.readAllBytes(File(p19Root(), rel).toPath()), Charsets.UTF_8)

private fun hpAnswer(text: String): String =
    """{"model":"m","message":{"role":"assistant","content":${JsonPrimitive("""{"kind":"answer","reply":${JsonPrimitive(text)},"args":{}}""")}},"done":true}"""

/** Real server pieces over a temp database; the laptop is off, the HP small model is scripted and counts its calls. */
private class P19Rig {
    var now: Long = P19_T0
    val sdb: ServerDb = openDb(Files.createTempDirectory("p19").resolve("t.db").toString())
    val store = SyncStore(sdb)
    val writer = ServerWriter(sdb, store) { now }
    val chats = ChatStore(sdb, writer) { now }
    val hpBodies = CopyOnWriteArrayList<String>()
    @Volatile var hpReply = "Hello from the HP"
    val chatClient = ChatClient(MockEngine { error("the laptop model must not be called in this rig") })
    val hp = OllamaClient(
        "http://ollama.test",
        MockEngine { req ->
            hpBodies += (req.body as TextContent).text
            respond(hpAnswer(hpReply), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
    val brain = Brain(null, "http://ollama.test", "hp-model", engine = MockEngine { respond("{}", HttpStatusCode.OK) })
    val runner = ChatRunner(sdb, store, writer, chats, brain, Mutex(), chatClient, { now }, persona = "P19 persona", hpOllama = hp)
    val service = ChatService(chats, UndoService(sdb, store, writer, chats), runner) { now }
    val config = Config("127.0.0.1", 0, "unused", mapOf("laptop" to LAPTOP_TOKEN, "calendar" to CAL_TOKEN, "phone" to PHONE_TOKEN))
    val sink = dev.tim.assistant.server.chat.ServerApprovalSink(sdb, store, writer, null, null)
    val notify = dev.tim.assistant.server.notify.NotifyService(sdb, writer) { now }
    val repo = Repository(LocalStore(sdb.db), now = { now }, today = { "2026-11-12" })
    fun spendings() = sdb.db.spendingQueries.selectAll().executeAsList().filter { it.deleted == 0L }
}

private fun ApplicationTestBuilder.up19(rig: P19Rig) {
    application { module(rig.config, rig.store, clock = { rig.now }, chat = rig.service, approvalSink = rig.sink, notify = rig.notify) }
}

private suspend fun ApplicationTestBuilder.call19(method: String, path: String, token: String? = LAPTOP_TOKEN, body: String? = null): Pair<HttpStatusCode, String> {
    val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    val r = if (method == "GET") client.get(path, block) else client.post(path, block)
    return r.status to r.bodyAsText()
}

private suspend fun ApplicationTestBuilder.chat19(rig: P19Rig): String {
    val id = UUID.randomUUID().toString()
    val (st, b) = call19("POST", "/v1/chat", PHONE_TOKEN, json19.encodeToString(NewChatRequest(id)))
    assertTrue(st == HttpStatusCode.OK || st == HttpStatusCode.Created, "$st $b")
    return id
}

private fun send19(mid: String, text: String, phone: Boolean, at: Long = P19_T0) =
    json19.encodeToString(SendMessageRequest(mid, text, at, "Today", phoneBrain = phone))

private fun result19(turn: PhoneTurnDto, decision: String, turnId: String = turn.turnId) =
    json19.encodeToString(PhoneResultRequest(turnId, turn.messageId, decision, 900L, 21.5))

private const val ADD_35 = """{"kind":"action","action":"add_spending","args":{"amount":"35","category":"Food","note":"lunch"}}"""

class Phase19AcceptanceTest {

    // ============================================================ AC-10: the 202 flow
    @Test
    fun test_AC10_phone_token_gets_202_with_the_phone_turn_shape() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        val (st, body) = call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true))
        assertEquals(HttpStatusCode.Accepted, st, body)
        val turn = json19.decodeFromString<PhoneTurnDto>(body)
        assertEquals("r:$mid", turn.turnId)
        assertEquals(mid, turn.messageId)
        assertEquals(P19_T0 + 180_000, turn.expiresAt)
        assertTrue(turn.system.isNotBlank())
        assertTrue(turn.user.contains(OFFBEAT))
        assertEquals(decisionSchema(false), turn.schema)
        assertEquals(0, rig.hpBodies.size, "the HP small model is not called for a phone turn")
    }

    @Test
    fun test_AC10_laptop_token_never_gets_a_202_and_calendar_token_is_403() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val (st, body) = call19("POST", "/v1/chat/$chat/message", LAPTOP_TOKEN, send19(UUID.randomUUID().toString(), OFFBEAT, true))
        assertEquals(HttpStatusCode.OK, st, body)
        assertEquals(HttpStatusCode.Forbidden, call19("POST", "/v1/chat/$chat/message", CAL_TOKEN, send19(UUID.randomUUID().toString(), OFFBEAT, true)).first)
    }

    @Test
    fun test_AC10_old_client_without_phoneBrain_never_gets_202() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val (st, _) = call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(UUID.randomUUID().toString(), OFFBEAT, false))
        assertEquals(HttpStatusCode.OK, st)
        // an old client's JSON has no phoneBrain field at all
        val raw = """{"messageId":"${UUID.randomUUID()}","text":"$OFFBEAT","createdAt":$P19_T0,"screen":"Today"}"""
        assertEquals(HttpStatusCode.OK, call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, raw).first)
    }

    @Test
    fun test_AC10_phone_result_is_403_for_laptop_and_calendar_tokens_and_401_for_unknown_or_none() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        val turn = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).second)
        val body = result19(turn, ADD_35)
        assertEquals(HttpStatusCode.Forbidden, call19("POST", "/v1/chat/$chat/phone-result", LAPTOP_TOKEN, body).first)
        assertEquals(HttpStatusCode.Forbidden, call19("POST", "/v1/chat/$chat/phone-result", CAL_TOKEN, body).first)
        assertEquals(HttpStatusCode.Unauthorized, call19("POST", "/v1/chat/$chat/phone-result", "nope", body).first)
        assertEquals(HttpStatusCode.Unauthorized, call19("POST", "/v1/chat/$chat/phone-result", null, body).first)
        assertEquals(0, rig.spendings().size, "refused calls write nothing")
    }

    @Test
    fun test_AC10_same_id_resend_true_returns_the_same_turn() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        val a = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).second)
        rig.now += 30_000 // 150 s left: still above the 60 s margin
        val (st, b) = call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true))
        assertEquals(HttpStatusCode.Accepted, st, b)
        val again = json19.decodeFromString<PhoneTurnDto>(b)
        assertEquals(a.turnId, again.turnId)
        assertEquals(a.expiresAt, again.expiresAt)
        assertEquals(0, rig.hpBodies.size)
    }

    @Test
    fun test_AC10_same_id_resend_false_goes_to_the_home_server_once() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.Accepted, call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).first)
        val (st, b) = call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, false))
        assertEquals(HttpStatusCode.OK, st, b)
        assertEquals("Hello from the HP", json19.decodeFromString<SendMessageResponse>(b).reply.text)
        assertEquals(1, rig.hpBodies.size)
        // a third send of the same id is a replay: no second model call, same reply
        val (st2, b2) = call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, false))
        assertEquals(HttpStatusCode.OK, st2, b2)
        assertEquals(1, rig.hpBodies.size)
        // and a late phone result for the finished turn is refused
        val turn = PhoneTurnDto("r:$mid", mid, "", "", decisionSchema(false), 0)
        assertEquals(HttpStatusCode.Conflict, call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, result19(turn, ADD_35)).first)
        assertEquals(0, rig.spendings().size)
    }

    @Test
    fun test_AC10_another_message_while_a_phone_turn_is_pending_is_503_still_answering() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        assertEquals(HttpStatusCode.Accepted, call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(UUID.randomUUID().toString(), OFFBEAT, true)).first)
        val (st, b) = call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(UUID.randomUUID().toString(), "another thing entirely", true))
        assertEquals(HttpStatusCode.ServiceUnavailable, st, b)
        assertTrue(b.contains("Wally is still answering"), b)
        assertEquals(0, rig.hpBodies.size)
    }

    @Test
    fun test_AC10_the_tick_closes_an_expired_turn_with_the_interrupted_text() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.Accepted, call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).first)
        rig.now += 179_000
        assertEquals(0, rig.service.closeExpiredPhoneTurns(), "not expired yet")
        rig.now += 2_000
        assertEquals(1, rig.service.closeExpiredPhoneTurns())
        val detail = json19.decodeFromString<ChatDetailResponse>(call19("GET", "/v1/chat/$chat", PHONE_TOKEN).second)
        val reply = detail.messages.single { it.role == "assistant" }
        assertEquals(INTERRUPTED_TEXT, reply.text)
        // the same message sent again returns the stored interrupted reply, nothing new is computed
        val (st, b) = call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, false))
        assertEquals(HttpStatusCode.OK, st, b)
        assertEquals(INTERRUPTED_TEXT, json19.decodeFromString<SendMessageResponse>(b).reply.text)
        assertEquals(0, rig.hpBodies.size)
        // the tick does nothing twice
        assertEquals(0, rig.service.closeExpiredPhoneTurns())
    }

    @Test
    fun test_AC10_pending_phone_reply_is_hidden_from_chat_detail() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        assertEquals(HttpStatusCode.Accepted, call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(UUID.randomUUID().toString(), OFFBEAT, true)).first)
        val detail = json19.decodeFromString<ChatDetailResponse>(call19("GET", "/v1/chat/$chat", PHONE_TOKEN).second)
        assertTrue(detail.messages.none { it.role == "assistant" && it.text.isBlank() }, "no blank assistant row")
        assertEquals(1, detail.messages.count { it.role == "user" })
        assertTrue(detail.messages.none { it.role == "assistant" })
    }

    // ============================================================ AC-10: phone-result validation
    @Test
    fun test_AC10_a_valid_phone_action_writes_one_row_with_brain_phone_and_a_second_result_is_409() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        val turn = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, "paid 35 for lunch yesterday-ish", true)).second)
        val (st, b) = call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, result19(turn, ADD_35))
        assertEquals(HttpStatusCode.OK, st, b)
        val resp = json19.decodeFromString<SendMessageResponse>(b)
        assertEquals("assistant", resp.reply.role)
        assertTrue(resp.reply.text.isNotBlank())
        val rows = rig.spendings()
        assertEquals(1, rows.size)
        assertEquals(3500L, rows.single().amountAgorot)
        val reply = rig.chats.messages(chat).single { it.role == "assistant" }
        assertEquals("phone", parseExtras(reply.card_json).brain)
        // the same result again writes nothing more
        assertEquals(HttpStatusCode.Conflict, call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, result19(turn, ADD_35)).first)
        assertEquals(1, rig.spendings().size)
        // the finished reply now shows in the detail
        val detail = json19.decodeFromString<ChatDetailResponse>(call19("GET", "/v1/chat/$chat", PHONE_TOKEN).second)
        assertEquals(1, detail.messages.count { it.role == "assistant" })
    }

    @Test
    fun test_AC10_invalid_phone_decisions_write_nothing() = testApplication {
        val rig = P19Rig(); up19(rig)
        val bad = listOf(
            "this is not json at all",
            """{"kind":"action","action":"delete_everything","args":{}}""",
            """{"kind":"action","action":"add_spending","args":{"amount":"lots","category":"Food"}}""",
            """{"kind":"action","action":"add_dream","args":{"text":"a dream"}}""",
        )
        for (decision in bad) {
            val chat = chat19(rig)
            val mid = UUID.randomUUID().toString()
            val turn = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).second)
            val (st, b) = call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, result19(turn, decision))
            assertEquals(HttpStatusCode.OK, st, "$decision -> $b")
            assertTrue(json19.decodeFromString<SendMessageResponse>(b).reply.text.isNotBlank())
        }
        assertEquals(0, rig.spendings().size)
        assertEquals(0, rig.sdb.db.dreamQueries.selectAll().executeAsList().size, "add_dream is refused without a dream word")
    }

    @Test
    fun test_AC10_a_turnId_that_is_not_r_plus_messageId_is_400_and_a_missing_chat_is_404() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        val turn = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).second)
        assertEquals(HttpStatusCode.BadRequest, call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, result19(turn, ADD_35, turnId = "r:other")).first)
        assertEquals(HttpStatusCode.BadRequest, call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, "{not json").first)
        assertEquals(HttpStatusCode.NotFound, call19("POST", "/v1/chat/no-such-chat/phone-result", PHONE_TOKEN, result19(turn, ADD_35)).first)
        assertEquals(0, rig.spendings().size)
    }

    @Test
    fun test_AC10_an_expired_turn_result_is_409_and_writes_nothing() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        val turn = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).second)
        rig.now += 181_000
        assertEquals(HttpStatusCode.Conflict, call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, result19(turn, ADD_35)).first)
        assertEquals(0, rig.spendings().size)
        val detail = json19.decodeFromString<ChatDetailResponse>(call19("GET", "/v1/chat/$chat", PHONE_TOKEN).second)
        assertEquals(INTERRUPTED_TEXT, detail.messages.single { it.role == "assistant" }.text)
    }

    @Test
    fun test_AC10_phone_answer_kind_is_shown_as_the_reply() = testApplication {
        val rig = P19Rig(); up19(rig)
        val chat = chat19(rig)
        val mid = UUID.randomUUID().toString()
        val turn = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat/message", PHONE_TOKEN, send19(mid, OFFBEAT, true)).second)
        val (st, b) = call19("POST", "/v1/chat/$chat/phone-result", PHONE_TOKEN, result19(turn, """{"kind":"answer","reply":"Spreadsheets love you back.","args":{}}"""))
        assertEquals(HttpStatusCode.OK, st, b)
        assertEquals("Spreadsheets love you back.", json19.decodeFromString<SendMessageResponse>(b).reply.text)
    }

    // ============================================================ AC-4 / AC-8: gate and stream
    @Test
    fun test_AC4_phone_brain_gate_19_false_20_true_power_save_false() {
        assertFalse(PhoneBrainGate.allowed(true, true, 19, false))
        assertTrue(PhoneBrainGate.allowed(true, true, 20, false))
        assertTrue(PhoneBrainGate.allowed(true, true, 100, false))
        assertFalse(PhoneBrainGate.allowed(true, true, 80, true), "power saving")
        assertFalse(PhoneBrainGate.allowed(false, true, 80, false), "switch off")
        assertFalse(PhoneBrainGate.allowed(true, false, 80, false), "no model file")
        assertEquals(0, NoDeviceState.batteryPercent())
        assertFalse(NoDeviceState.powerSave())
        assertFalse(NoDeviceState.foreground())
        assertEquals(null, NoPhoneBrain.status().modelName)
    }

    @Test
    fun test_AC4_reply_stream_shows_only_the_answer_reply() {
        assertEquals("", ReplyStream.extract(""))
        assertEquals("", ReplyStream.extract("""{"kind":"answer","reply":"""))
        assertEquals("Hel", ReplyStream.extract("""{"kind":"answer","reply":"Hel"""))
        assertEquals("Hello there", ReplyStream.extract("""{"kind":"answer","reply":"Hello there","args":{}}"""))
        // an action never streams its reply text
        assertEquals("", ReplyStream.extract("""{"kind":"action","reply":"Saving that","action":"add_spending"}"""))
        // a reply that comes before the kind waits for it
        assertEquals("", ReplyStream.extract("""{"reply":"early words","kind":"""))
        assertEquals("early words", ReplyStream.extract("""{"reply":"early words","kind":"answer","args":{}}"""))
        // nested or quoted look-alikes never match
        assertEquals("", ReplyStream.extract("""{"kind":"action","args":{"kind":"answer","reply":"nope"}}"""))
        assertEquals("", ReplyStream.extract("""{"kind":"action","action":"find","target":"\"kind\":\"answer\",\"reply\":\"x"}"""))
        // escapes are decoded, also across a split \u escape
        assertEquals("a\nb \"q\"", ReplyStream.extract("""{"kind":"answer","reply":"a\nb \"q\""}"""))
        assertEquals("café", ReplyStream.extract("""{"kind":"answer","reply":"café"}"""))
        assertEquals("caf", ReplyStream.extract("""{"kind":"answer","reply":"caf\u00e"""))
        // growing partials never lose text already shown
        val full = """{"kind":"answer","reply":"Good evening, Tim.","args":{}}"""
        var last = ""
        for (i in 0..full.length) {
            val now = ReplyStream.extract(full.substring(0, i))
            assertTrue(now.startsWith(last), "partial $i went backwards: '$last' -> '$now'")
            last = now
        }
        assertEquals("Good evening, Tim.", last)
    }

    // ============================================================ one voice, dream gate
    @Test
    fun test_AC5_phone_prompt_is_one_voice_and_dream_text_needs_a_dream_word() = testApplication {
        val rig = P19Rig(); up19(rig)
        rig.repo.saveDream(null, "2026-11-10", "ZZ19_DREAM_MARK flying over a lake", 3, false, "")
        val voice = checkNotNull(Phase19AcceptanceTest::class.java.getResourceAsStream("/wally-voice.md")).bufferedReader(Charsets.UTF_8).readText().trim()
        val sys = DecisionPrompt.system(PromptBrain.PHONE)
        assertTrue(sys.startsWith(voice), "the phone system text starts with the one Wally voice")
        assertEquals(1, Regex(Regex.escape(voice.lines().first())).findAll(sys).count(), "the voice appears once")
        assertTrue(sys.length - voice.length <= 1_600, "the phone rules are short")
        assertFalse(sys.contains("ZZ19_DREAM_MARK"))

        // no dream word: no dream text anywhere in the 202 prompt
        val chat1 = chat19(rig)
        val t1 = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat1/message", PHONE_TOKEN, send19(UUID.randomUUID().toString(), OFFBEAT, true)).second)
        assertFalse(mentionsDreams(OFFBEAT))
        assertFalse(t1.user.contains("ZZ19_DREAM_MARK"))
        assertFalse(t1.system.contains("ZZ19_DREAM_MARK"))
        assertFalse(t1.schema.toString().contains("add_dream"))

        // a dream word: the dream text may appear and add_dream is allowed
        val dreamText = "I had a dream last night about my exam"
        assertTrue(mentionsDreams(dreamText))
        val chat2 = chat19(rig)
        val t2 = json19.decodeFromString<PhoneTurnDto>(call19("POST", "/v1/chat/$chat2/message", PHONE_TOKEN, send19(UUID.randomUUID().toString(), dreamText, true)).second)
        assertTrue(t2.user.contains("ZZ19_DREAM_MARK"))
        assertTrue(t2.schema.toString().contains("add_dream"))
        // the same builder directly, for the same two texts
        val store = rig.chats
        store.create("direct"); store.addUserMessage("direct", "u1", OFFBEAT, rig.now)
        val p = DecisionPrompt.build(PromptBudget.PHONE, rig.sdb.db, store, "direct", "u1", "r:u1", OFFBEAT, null, mentionsDreams(OFFBEAT), rig.now, PromptBrain.PHONE)
        assertFalse(p.user.contains("ZZ19_DREAM_MARK"))
        assertEquals(sys, p.system)
    }

    @Test
    fun test_AC5_phone_context_date_line_names_the_weekday() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(d)
        val db = AssistantDb(d)
        // 2026-11-16 10:00 Israel time is a Monday; 2026-11-21 is a Saturday
        val mon = java.time.ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, java.time.ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()
        val sat = java.time.ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, java.time.ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()
        val a = dev.tim.assistant.server.chat.LiteContext.build(db, mon, false, 4000, dev.tim.assistant.server.chat.LiteContext.Profile.PHONE)
        val b = dev.tim.assistant.server.chat.LiteContext.build(db, sat, false, 4000, dev.tim.assistant.server.chat.LiteContext.Profile.PHONE)
        assertTrue(a.lines().first().contains("Monday") && a.lines().first().contains("2026-11-16"), a.lines().first())
        assertTrue(b.lines().first().contains("Saturday") && b.lines().first().contains("2026-11-21"), b.lines().first())
    }

    // ============================================================ release, schema, permissions
    @Test
    fun test_AC11_schema_is_still_version_8_with_34_tables() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(d)
        assertEquals(8L, AssistantDb.Schema.version)
        val names = ArrayList<String>()
        d.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", { c ->
            while (c.next().value) names += c.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Unit
        }, 0)
        assertEquals(34, names.size, names.toString())
    }

    @Test
    fun test_AC11_release_is_1_17_0_versionCode_13() {
        assertTrue(p19Text("desktopApp/build.gradle.kts").contains("packageVersion = \"1.18.0\""))
        val a = p19Text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            assertTrue(p19Text("ops/checklists/$f.md").contains("1.18.0"), "$f.md names 1.18.0")
        }
        assertTrue(p19Text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
        assertTrue(File(p19Root(), "ops/phone-model.md").isFile)
        assertTrue(File(p19Root(), "ops/deploy-phase19.md").isFile)
        assertTrue(File(p19Root(), "ops/checklists/phase19.md").isFile)
        assertTrue(File(p19Root(), "reports/phase-19-measure.md").isFile)
    }

    private fun permissionsBefore(): Set<String> {
        val text = p19Text("reports/phase-19-measure.md")
        val block = text.substringAfter("## Permissions before").substringAfter("```").substringBefore("```")
        return block.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    @Test
    fun test_AC6_the_app_manifest_adds_no_permission_beyond_the_twelve_before() {
        val before = permissionsBefore()
        assertEquals(12, before.size, before.toString())
        val names = Regex("<uses-permission[^>]*?android:name=\"([^\"]+)\"").findAll(p19Text("androidApp/src/main/AndroidManifest.xml")).map { it.groupValues[1] }.toSet()
        assertTrue(before.containsAll(names), "source manifest has permissions not in the list: ${names - before}")
        for (bad in listOf("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA", "RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION")) {
            assertFalse(names.any { it.contains(bad) }, bad)
        }
        // when a debug build has produced the merged manifest, it must list exactly the twelve
        val merged = File(p19Root(), "androidApp/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        if (merged.isFile) {
            val m = Regex("<uses-permission[^>]*?android:name=\"([^\"]+)\"").findAll(merged.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toSet()
            assertEquals(before, m, "merged manifest permissions")
        }
    }

    @Test
    fun test_AC3_litertlm_0_19_1_is_in_the_catalog_and_the_app() {
        val cat = p19Text("gradle/libs.versions.toml")
        assertTrue(cat.contains("litertlm") && cat.contains("0.19.1"), "catalog names litertlm 0.19.1")
        val app = p19Text("androidApp/build.gradle.kts")
        assertTrue(Regex("android-minSdk\\s*=\\s*\"26\"").containsMatchIn(cat), "minSdk 26 in the catalog")
        assertTrue(app.contains("libs.litertlm.android"))
        assertTrue(app.contains("arm64-v8a"))
    }
}
