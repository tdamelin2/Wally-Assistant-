import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.CODEX_ASKED_TEXT
import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.ROUTES
import dev.tim.assistant.model.ROUTE_AUTO
import dev.tim.assistant.model.ROUTE_CODEX
import dev.tim.assistant.model.ROUTE_MODEL
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.research.RESEARCH_OTHER
import dev.tim.assistant.research.ResearchRow
import dev.tim.assistant.research.ResearchRunner
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.chat.ChatRunner
import dev.tim.assistant.server.chat.ChatService
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.chat.UndoService
import dev.tim.assistant.server.codex.ServerCodex
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
import kotlin.test.assertTrue
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val P20_T0: Long = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli()
private val json20 = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val LAP20 = "lap-token"
private const val CAL20 = "cal-token"
private const val OFFBEAT20 = "tell me something encouraging about spreadsheets"
private const val QUESTION20 = "What is the tallest mountain in Peru"

private fun p20Root(): File {
    var d: File? = File("").absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return checkNotNull(d)
}

private fun hpDecision(decisionJson: String): String =
    """{"model":"m","message":{"role":"assistant","content":${JsonPrimitive(decisionJson)}},"done":true}"""

private const val HP_ADD = """{"kind":"action","action":"add_spending","args":{"amount":"35","category":"Food","note":"lunch"}}"""
private const val HP_ANSWER = """{"kind":"answer","reply":"Hello from the HP","args":{}}"""

private class P20Rig {
    var now: Long = P20_T0
    val sdb: ServerDb = openDb(Files.createTempDirectory("p20").resolve("t.db").toString())
    val store = SyncStore(sdb)
    val writer = ServerWriter(sdb, store) { now }
    val chats = ChatStore(sdb, writer) { now }
    val hpBodies = CopyOnWriteArrayList<String>()
    @Volatile var hpReply = HP_ANSWER
    val asked = CopyOnWriteArrayList<String>()
    val chatClient = ChatClient(MockEngine { error("the laptop model must not be called in this rig") })
    val hp = OllamaClient(
        "http://ollama.test",
        MockEngine { req ->
            hpBodies += (req.body as TextContent).text
            respond(hpDecision(hpReply), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
    val brain = Brain(null, "http://ollama.test", "hp-model", engine = MockEngine { respond("{}", HttpStatusCode.OK) })
    val fakeRunner = ResearchRunner { category, question ->
        asked += question
        ResearchRow(category, question, "Huascaran, about 6,768 metres.", "{}", emptyList())
    }
    val codex = ServerCodex(sdb, writer, fakeRunner, clock = { now })
    val runner = ChatRunner(sdb, store, writer, chats, brain, Mutex(), chatClient, { now }, persona = "P20 persona", hpOllama = hp, researchApprover = codex)
    val service = ChatService(chats, UndoService(sdb, store, writer, chats), runner) { now }
    val config = Config("127.0.0.1", 0, "unused", mapOf("laptop" to LAP20, "calendar" to CAL20))
    val sink = dev.tim.assistant.server.chat.ServerApprovalSink(sdb, store, writer, null, null)
    val notify = dev.tim.assistant.server.notify.NotifyService(sdb, writer) { now }
    fun spendings() = sdb.db.spendingQueries.selectAll().executeAsList().filter { it.deleted == 0L }
    fun jobs() = sdb.db.ai_jobQueries.selectAll().executeAsList()
}

private fun ApplicationTestBuilder.up20(rig: P20Rig) {
    application { module(rig.config, rig.store, clock = { rig.now }, chat = rig.service, approvalSink = rig.sink, notify = rig.notify) }
}

private suspend fun ApplicationTestBuilder.call20(method: String, path: String, body: String? = null): Pair<HttpStatusCode, String> {
    val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        header(HttpHeaders.Authorization, "Bearer $LAP20")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    val r = if (method == "GET") client.get(path, block) else client.post(path, block)
    return r.status to r.bodyAsText()
}

private suspend fun ApplicationTestBuilder.newChat20(): String {
    val id = UUID.randomUUID().toString()
    val (st, b) = call20("POST", "/v1/chat", json20.encodeToString(NewChatRequest(id)))
    assertTrue(st == HttpStatusCode.OK || st == HttpStatusCode.Created, "$st $b")
    return id
}

private fun req20(text: String, route: String = ROUTE_AUTO, choice: ChoiceDto? = null, mid: String = UUID.randomUUID().toString()) =
    json20.encodeToString(SendMessageRequest(mid, text, P20_T0, "Today", choice, false, route))

private suspend fun ApplicationTestBuilder.send20(chat: String, text: String, route: String = ROUTE_AUTO, choice: ChoiceDto? = null): Pair<HttpStatusCode, String> =
    call20("POST", "/v1/chat/$chat/message", req20(text, route, choice))

private fun reply20(body: String): SendMessageResponse = json20.decodeFromString(body)

private suspend fun ApplicationTestBuilder.detail20(chat: String): ChatDetailResponse =
    json20.decodeFromString(call20("GET", "/v1/chat/$chat").second)

class Phase20AcceptanceTest {

    // ============================================================ AC-2 / AC-7: routes
    @Test
    fun test_AC7_route_constants_are_auto_model_codex() {
        assertEquals(setOf("auto", "model", "codex"), ROUTES)
        assertEquals("auto", ROUTE_AUTO)
        assertEquals("model", ROUTE_MODEL)
        assertEquals("codex", ROUTE_CODEX)
    }

    @Test
    fun test_AC7_an_invalid_route_is_400() = testApplication {
        val rig = P20Rig(); up20(rig)
        val chat = newChat20()
        val (st, b) = send20(chat, OFFBEAT20, route = "banana")
        assertEquals(HttpStatusCode.BadRequest, st, b)
        assertTrue(b.contains("invalid") && b.contains("route"), b)
        assertEquals(0, rig.hpBodies.size)
    }

    @Test
    fun test_AC7_codex_with_a_choice_is_400() = testApplication {
        val rig = P20Rig(); up20(rig)
        val chat = newChat20()
        val (st, b) = send20(chat, "Yes", route = ROUTE_CODEX, choice = ChoiceDto("suggest", "0", "Yes", "tok"))
        assertEquals(HttpStatusCode.BadRequest, st, b)
        assertTrue(rig.jobs().isEmpty())
    }

    @Test
    fun test_AC7_a_request_with_no_route_field_is_accepted_as_auto() = testApplication {
        val rig = P20Rig(); up20(rig)
        val chat = newChat20()
        val raw = """{"messageId":"${UUID.randomUUID()}","text":"$OFFBEAT20","createdAt":$P20_T0,"screen":"Today"}"""
        val (st, b) = call20("POST", "/v1/chat/$chat/message", raw)
        assertEquals(HttpStatusCode.OK, st, b)
    }

    @Test
    fun test_AC7_the_model_route_skips_the_code_rungs() = testApplication {
        val rig = P20Rig(); up20(rig)
        val chat = newChat20()
        // On auto, code handles this at once (a card or a question); on model it must go to a model rung.
        val (st, b) = send20(chat, "log 35 shekels for lunch", route = ROUTE_MODEL)
        assertEquals(HttpStatusCode.OK, st, b)
        assertTrue(rig.hpBodies.size >= 1, "the HP small model was asked")
        assertEquals(0, rig.spendings().size, "nothing saved by code on the model route")
    }

    // ============================================================ AC-7: Codex path
    @Test
    fun test_AC7_codex_route_replies_asked_then_posts_the_answer_once_into_the_chat() = testApplication {
        val rig = P20Rig(); up20(rig)
        val chat = newChat20()
        val (st, b) = send20(chat, QUESTION20, route = ROUTE_CODEX)
        assertEquals(HttpStatusCode.OK, st, b)
        assertEquals(CODEX_ASKED_TEXT, reply20(b).reply.text)
        assertEquals(1, rig.jobs().size)
        rig.now += 5_000
        assertTrue(rig.codex.tick())
        rig.now += 5_000
        rig.codex.tick()
        val msgs = detail20(chat).messages
        val answers = msgs.filter { it.role == "assistant" && it.text.contains("Huascaran") }
        assertEquals(1, answers.size, msgs.toString())
        assertEquals(1, rig.asked.size, "Codex ran once")
        assertTrue(answers.single().createdAt > msgs.first { it.text == CODEX_ASKED_TEXT }.createdAt)
        assertEquals(0, rig.hpBodies.size, "no small model call on the codex route")
    }

    @Test
    fun test_AC7_a_cached_codex_answer_comes_back_at_once() = testApplication {
        val rig = P20Rig(); up20(rig)
        val chat = newChat20()
        send20(chat, QUESTION20, route = ROUTE_CODEX)
        rig.now += 5_000
        rig.codex.tick()
        val (_, b) = send20(chat, QUESTION20, route = ROUTE_CODEX)
        val text = reply20(b).reply.text
        assertTrue(text.contains("Huascaran"), text)
        assertEquals(1, rig.jobs().size, "no second job")
        assertEquals(1, rig.asked.size)
    }

    @Test
    fun test_AC7_dream_text_and_over_length_text_are_refused_with_the_spec_sentences() = testApplication {
        val rig = P20Rig(); up20(rig)
        val chat = newChat20()
        val dream = reply20(send20(chat, "Why do I keep having the same dream about school", route = ROUTE_CODEX).second)
        assertEquals("I can't send dream text to Codex.", dream.reply.text)
        val long = reply20(send20(chat, "w".repeat(2001), route = ROUTE_CODEX).second)
        assertEquals("That's too long for Codex (limit 2,000 characters).", long.reply.text)
        assertTrue(rig.jobs().isEmpty())
        assertEquals(0, rig.asked.size)
    }

    // ============================================================ AC-8: HP suggests only
    @Test
    fun test_AC8_hp_add_is_one_suggestion_and_nothing_is_saved_until_yes() = testApplication {
        val rig = P20Rig(); up20(rig)
        rig.hpReply = HP_ADD
        val chat = newChat20()
        val (st, b) = send20(chat, OFFBEAT20)
        assertEquals(HttpStatusCode.OK, st, b)
        val r = reply20(b).reply
        assertEquals(0, rig.spendings().size, "nothing saved before Yes: ${r.text}")
        assertTrue(r.choices.size >= 2, "a Yes chip and a Something else chip: ${r.choices}")
        val yes = r.choices.first()
        val (st2, b2) = send20(chat, yes.name, choice = yes)
        assertEquals(HttpStatusCode.OK, st2, b2)
        assertEquals(1, rig.spendings().size, b2)
        assertEquals(3500L, rig.spendings().single().amountAgorot.takeIf { true } ?: 0L, "35 shekels")
    }

    @Test
    fun test_AC8_typing_the_exact_label_of_a_chip_answers_like_a_tap() = testApplication {
        val rig = P20Rig(); up20(rig)
        rig.hpReply = HP_ADD
        val chat = newChat20()
        val r = reply20(send20(chat, OFFBEAT20).second).reply
        assertEquals(0, rig.spendings().size)
        val label = r.choices.first().name
        val (st, b) = send20(chat, label)
        assertEquals(HttpStatusCode.OK, st, b)
        assertEquals(1, rig.spendings().size, b)
    }

    // ============================================================ old clients
    @Test
    fun test_AC2_a_reply_with_suggest_chips_decodes_with_the_1_17_0_shape() = testApplication {
        val rig = P20Rig(); up20(rig)
        rig.hpReply = HP_ADD
        val chat = newChat20()
        val (_, b) = send20(chat, OFFBEAT20)
        // A 1.17.0 client reads each chip as exactly kind, id, name, token (all strings) and ignores the rest.
        val chips = json20.parseToJsonElement(b).jsonObject["reply"]!!.jsonObject["choices"]!!.jsonArray
        assertTrue(chips.isNotEmpty(), b)
        for (c in chips) {
            val o = c.jsonObject
            for (k in listOf("kind", "id", "name", "token")) assertTrue(o[k]?.jsonPrimitive?.isString == true, "$k in $o")
        }
        assertTrue(chips.any { it.jsonObject["kind"]!!.jsonPrimitive.content == "suggest" }, chips.toString())
    }

    // ============================================================ AC-1: removed symbols
    @Test
    fun test_AC1_the_task_20_1_symbols_are_gone_from_shared() {
        val pat = Regex("""\bRepositoryApprovalSink\b|\bMealLookupJob\b|\bResearchJobs\b|\bclass WriteTools\b|\bcreateCalendarEvent\b""", RegexOption.IGNORE_CASE)
        val hits = File(p20Root(), "shared/src").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> pat.containsMatchIn(String(Files.readAllBytes(f.toPath()), Charsets.UTF_8)) }
            .map { it.name }.toList()
        assertTrue(hits.isEmpty(), hits.toString())
    }

    // ============================================================ schema and release
    @Test
    fun test_AC8_schema_is_still_version_8_with_34_tables() {
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
    fun test_AC9_release_is_1_18_0_versionCode_14() {
        val desk = String(Files.readAllBytes(File(p20Root(), "desktopApp/build.gradle.kts").toPath()), Charsets.UTF_8)
        val andr = String(Files.readAllBytes(File(p20Root(), "androidApp/build.gradle.kts").toPath()), Charsets.UTF_8)
        assertTrue(desk.contains("packageVersion = \"1.18.0\""))
        assertTrue(andr.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(andr))
        assertFalse(andr.contains("1.17.0\""))
    }
}
