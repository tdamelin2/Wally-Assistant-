import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.jobs.JobHandler
import dev.tim.assistant.ai.jobs.JobResult
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.SERVER_JOB_KINDS
import dev.tim.assistant.ai.jobs.TickResult
import dev.tim.assistant.ai.mentionsDreams
import dev.tim.assistant.ai.tools.ToolMode
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.ai.tools.findMatches
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.ChatBrainResponse
import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChatListResponse
import dev.tim.assistant.model.ChatMessageDto
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.MemoryResponse
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.NewChatResponse
import dev.tim.assistant.model.NotificationDto
import dev.tim.assistant.model.NotificationsResponse
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.ui.SLOW_REPLY_HINT
import dev.tim.assistant.ui.slowReplyHint
import dev.tim.assistant.notify.NotifyDecision
import dev.tim.assistant.notify.NotifyPlan
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.chat.ChatRunner
import dev.tim.assistant.server.chat.ChatService
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.ServerApprovalSink
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.chat.UndoService
import dev.tim.assistant.server.module
import dev.tim.assistant.server.notify.NotifyService
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.openDb
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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

private val P17_T0: Long = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli() // 12:00 in Jerusalem
private const val H = 3_600_000L
private val json17 = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun p17Text(rel: String): String {
    var d: File? = File("").absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return String(Files.readAllBytes(File(assertNotNull(d), rel).toPath()), Charsets.UTF_8)
}

private fun ollamaAnswer(text: String) =
    """{"model":"m","message":{"role":"assistant","content":${JsonPrimitive(text)}},"done":true}"""

/** One fixed-shape decision of kind "answer", as the HP small model returns it. */
private fun decisionAnswer(text: String): String =
    """{"kind":"answer","reply":${JsonPrimitive(text)},"args":{}}"""

/** The real server pieces over a temp database and a scripted Ollama. */
/** Phase 20: the client createApproval is gone; an approval card is a server row, so the test seeds one the way the server writes it. */
private fun p17CreateApproval(rig: P17Rig, tool: String, argsJson: String, summary: String): String {
    val id = "approval:p17-" + java.util.UUID.randomUUID()
    rig.sdb.db.approvalQueries.upsert(dev.tim.assistant.db.Approval(id, rig.now, 0, 0, "SYNCED", tool, argsJson, summary, "PENDING", rig.now, null))
    return id
}
private class P17Rig {
    var now: Long = P17_T0
    val sdb: ServerDb = openDb(Files.createTempDirectory("p17").resolve("t.db").toString())
    val store = SyncStore(sdb)
    val writer = ServerWriter(sdb, store) { now }
    val chats = ChatStore(sdb, writer) { now }
    val bodies = CopyOnWriteArrayList<String>()
    /** The text of the scripted "answer" decision the HP small model returns (rung 5, one fixed-shape JSON). */
    @Volatile var reply = "Hello Tim"
    /** The laptop model client: unused while the laptop is off, so any call to it is a test failure. */
    val chatClient = ChatClient(MockEngine { error("the laptop model must not be called in this rig") })
    val hpOllama = OllamaClient(
        "http://ollama.test",
        MockEngine { req ->
            bodies += (req.body as TextContent).text
            respond(ollamaAnswer(decisionAnswer(reply)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
    val brain = Brain(null, "http://ollama.test", "hp-model", engine = MockEngine { respond("{}", HttpStatusCode.OK) })
    val runner = ChatRunner(sdb, store, writer, chats, brain, Mutex(), chatClient, { now }, persona = "P17 persona", hpOllama = hpOllama)
    val service = ChatService(chats, UndoService(sdb, store, writer, chats), runner) { now }
    val sink = ServerApprovalSink(sdb, store, writer, null, null)
    val notify = NotifyService(sdb, writer) { now }
    val repo = Repository(LocalStore(sdb.db), now = { now }, today = { "2026-11-10" })
    val config = Config("127.0.0.1", 0, "unused", mapOf("laptop" to "lap-token", "calendar" to "cal-token"))
}

private fun ApplicationTestBuilder.up(rig: P17Rig) {
    application { module(rig.config, rig.store, clock = { rig.now }, chat = rig.service, approvalSink = rig.sink, notify = rig.notify) }
}

private suspend fun ApplicationTestBuilder.call(method: String, path: String, token: String? = "lap-token", body: String? = null): Pair<HttpStatusCode, String> {
    val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    val r = when (method) {
        "GET" -> client.get(path, block)
        "POST" -> client.post(path, block)
        "PUT" -> client.put(path, block)
        else -> client.delete(path, block)
    }
    return r.status to r.bodyAsText()
}

class Phase17AcceptanceTest {

    // ================================================================ DTOs (Contract)
    @Test
    fun test_AC12_notify_settings_defaults_and_chat_list_limit() {
        val s = NotifySettingsDto()
        assertEquals("21:00", s.checkinTime)
        assertEquals("23:00", s.quietStart)
        assertEquals("07:00", s.quietEnd)
        assertTrue(s.checkin && s.morningList && s.headsUp && s.ideas && s.weeklyReport)
        assertEquals(s, json17.decodeFromString<NotifySettingsDto>("{}"))
        assertEquals(10, ChatListResponse(emptyList(), activeCount = 0).limit)
    }

    @Test
    fun test_AC12_dtos_round_trip_and_optional_fields_default() {
        val m = json17.decodeFromString<ChatMessageDto>("""{"id":"a","role":"user","text":"x","createdAt":1}""")
        assertTrue(m.actions.isEmpty() && m.cards.isEmpty() && m.choices.isEmpty())
        val req = SendMessageRequest("mid", "hello", 5L, "Today", ChoiceDto("course", "course:1", "Algebra", "tok"))
        assertEquals(req, json17.decodeFromString<SendMessageRequest>(json17.encodeToString(req)))
        val n = NewChatRequest("id1")
        assertEquals(null, n.firstAssistantText)
        assertEquals(null, n.notificationId)
        val note = NotificationDto(7, "HEADS_UP", "t", "b", "exam:x", 1L, "k")
        assertEquals(note, json17.decodeFromString<NotificationDto>(json17.encodeToString(note)))
    }

    // ================================================================ findMatches
    private fun c(kind: String, id: String, name: String) = ChoiceDto(kind, id, name, "")

    @Test
    fun test_AC5_findMatches_one_several_none_limit_and_kind_filter() {
        val all = listOf(
            c("course", "c1", "Linear Algebra"), c("course", "c2", "Linear Algebra II"), c("course", "c3", "History"),
            c("task", "t1", "Linear algebra homework"),
        )
        assertEquals(listOf("c3"), findMatches(all, "course", "history").map { it.id })
        assertEquals(setOf("c1", "c2"), findMatches(all, "course", "ALGEBRA").map { it.id }.toSet())
        assertTrue(findMatches(all, "course", "chemistry").isEmpty())
        assertEquals(setOf("c1", "c2", "t1"), findMatches(all, null, "algebra").map { it.id }.toSet())
        assertTrue(findMatches(all, "course", "algebra").none { it.kind != "course" })
        val many = (1..8).map { c("doc", "d$it", "Notes $it") }
        assertEquals(5, findMatches(many, "doc", "notes").size)
    }

    // ================================================================ tool modes
    @Test
    fun test_AC6_tool_modes_match_the_plan_table() {
        for (t in Tools.read) assertEquals(ToolMode.READ, Tools.modeOf(t.name), t.name)
        assertEquals(ToolMode.READ, Tools.modeOf("find"))
        for (n in listOf("create_task", "add_checkin", "add_meal", "add_spending", "add_journal", "add_dream", "research")) {
            assertEquals(ToolMode.AUTO, Tools.modeOf(n), n)
        }
        for (n in listOf(
            "update_task", "update_checkin", "update_meal", "update_spending", "update_journal", "create_goal", "update_goal",
            "add_exam", "update_exam", "create_doc", "update_doc", "create_event", "set_app_rule", "queue_job",
            "suggest_mail_action", "draft_email", "research_question",
        )) {
            assertEquals(ToolMode.CARD, Tools.modeOf(n), n)
        }
        assertTrue(Tools.write.all { Tools.modeOf(it.name) in setOf(ToolMode.AUTO, ToolMode.CARD) })
        val all = Tools.read.map { it.name } + Tools.write.map { it.name } + Tools.research.map { it.name }
        assertTrue(all.none { it.contains("delete") || it.contains("send") || it.contains("remove") }, all.toString())
    }

    @Test
    fun test_AC6_ollama_tools_38_with_dreams_37_without() {
        val with = Tools.ollamaToolList(includeDream = true)
        val without = Tools.ollamaToolList(includeDream = false)
        assertEquals(38, with.size)
        assertEquals(37, without.size)
        fun names(a: List<kotlinx.serialization.json.JsonObject>) = a.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("add_dream" in names(with))
        assertFalse("add_dream" in names(without))
    }

    // ================================================================ dream rule
    @Test
    fun test_AC7_mentionsDreams_english_words() {
        for (t in listOf("dream", "Tell me about my DREAMS", "I dreamt of the sea", "I dreamed", "a nightmare last night")) assertTrue(mentionsDreams(t), t)
        for (t in listOf("what is on today", "stream of data", "add a task")) assertFalse(mentionsDreams(t), t)
    }

    @Test
    fun test_AC7_mentionsDreams_hebrew_bare_and_prefixed() {
        val dream = "חלום"
        val dreams = "חלומות"
        assertTrue(mentionsDreams(dream))
        assertTrue(mentionsDreams(dreams))
        for (p in "הבשלמוכ") assertTrue(mentionsDreams("$p$dream"), "prefix $p")
        assertFalse(mentionsDreams("שחלמתי")) // "that I dreamt" stays out
        assertFalse(mentionsDreams("חלוק")) // a different word
    }

    // ================================================================ NotifyPlan
    private fun row(kind: String, fireAt: Long) = NotificationDto(1, kind, "t", "b", "screen:today", fireAt, "k$kind$fireAt")

    @Test
    fun test_AC10_notifyPlan_show_now_and_schedule_future() {
        val s = NotifySettingsDto()
        assertEquals(NotifyDecision.ShowNow, NotifyPlan.plan(row("MORNING", P17_T0 - 60_000), P17_T0, false, s, 0))
        val at = P17_T0 + 3 * H // 15:00 local
        assertEquals(NotifyDecision.ScheduleAt(at), NotifyPlan.plan(row("MORNING", at), P17_T0, false, s, 0))
    }

    @Test
    fun test_AC10_notifyPlan_skips() {
        val s = NotifySettingsDto()
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("CHECKIN", P17_T0 - 1000), P17_T0, true, s, 0), "done check-in")
        assertEquals(NotifyDecision.ShowNow, NotifyPlan.plan(row("CHECKIN", P17_T0 - 1000), P17_T0, false, s, 0))
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("MORNING", P17_T0 - 25 * H), P17_T0, false, s, 0), "older than 24 h")
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("HEADS_UP", P17_T0 - 1000), P17_T0, false, s.copy(headsUp = false), 0), "disabled kind")
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("IDEA", P17_T0 - 1000), P17_T0, false, s.copy(ideas = false), 0))
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("MORNING", P17_T0 - 1000), P17_T0, false, s, 6), "cap 6")
        assertEquals(NotifyDecision.ShowNow, NotifyPlan.plan(row("MORNING", P17_T0 - 1000), P17_T0, false, s, 5))
    }

    @Test
    fun test_AC10_notifyPlan_quiet_hours_cross_midnight() {
        val s = NotifySettingsDto()
        val at2330 = Instant.parse("2026-11-10T21:30:00Z").toEpochMilli() // 23:30 local
        val at0030 = Instant.parse("2026-11-10T22:30:00Z").toEpochMilli() // 00:30 local
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("MORNING", at2330), P17_T0, false, s, 0))
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("MORNING", at0030), P17_T0, false, s, 0))
        assertEquals(NotifyDecision.Skip, NotifyPlan.plan(row("MORNING", at2330 - 1000), at2330, false, s, 0), "now inside quiet hours")
        val at0700 = Instant.parse("2026-11-11T05:00:00Z").toEpochMilli() // 07:00 local, quiet ends
        assertTrue(NotifyPlan.plan(row("MORNING", at0700), P17_T0, false, s, 0) != NotifyDecision.Skip)
    }

    // ================================================================ JobRunner server kinds
    @Test
    fun test_AC8_default_JobRunner_leaves_RESEARCH_and_MEAL_LOOKUP_queued() = runBlocking<Unit> {
        assertEquals(setOf("RESEARCH", "MEAL_LOOKUP"), SERVER_JOB_KINDS.toSet())
        val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
        var t = P17_T0
        val repo = Repository(LocalStore(db), now = { t }, today = { "2026-11-10" })
        repo.queueJob("RESEARCH", "{}")
        repo.queueJob("MEAL_LOOKUP", "{}")
        var ran = 0
        val h = JobHandler { ran++; JobResult("{}") }
        val runner = JobRunner(repo, sync = {}, handlers = mapOf("RESEARCH" to h, "MEAL_LOOKUP" to h), clock = { t })
        assertEquals(TickResult.Idle, runner.tick())
        t += 2 * 60 * 60 * 1000L // stale cutoff passes: still not reset or claimed
        assertEquals(TickResult.Idle, runner.tick())
        assertEquals(0, ran)
        assertEquals(listOf("QUEUED", "QUEUED"), db.ai_jobQueries.selectAll().executeAsList().map { it.status })
    }

    // ================================================================ server routes
    private val newRoutes = listOf(
        "GET" to "/v1/chat/list", "POST" to "/v1/chat", "GET" to "/v1/chat/x", "POST" to "/v1/chat/x/message",
        "DELETE" to "/v1/chat/x", "POST" to "/v1/chat/x/undo/a", "GET" to "/v1/memory", "POST" to "/v1/approvals/x",
        "GET" to "/v1/notifications?since=0", "GET" to "/v1/notify-settings", "PUT" to "/v1/notify-settings",
    )

    @Test
    fun test_AC4_R3_brain_route_hp_when_xps_is_not_configured() = testApplication {
        val rig = P17Rig()
        up(rig)
        val (st, body) = call("GET", "/v1/chat/brain")
        assertEquals(HttpStatusCode.OK, st, body)
        assertEquals("hp", json17.decodeFromString<ChatBrainResponse>(body).brain)
    }

    @Test
    fun test_AC4_R3_brain_route_xps_when_xps_lists_the_model() = testApplication {
        val rig = P17Rig()
        val xpsTags = MockEngine {
            respond("""{"models":[{"name":"${dev.tim.assistant.ai.CHAT_MODEL}"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val xpsBrain = Brain("http://xps.test:11434", "http://hp.test:11434", "hp-model", engine = xpsTags)
        val xpsRunner = ChatRunner(rig.sdb, rig.store, rig.writer, rig.chats, xpsBrain, Mutex(), rig.chatClient, { rig.now }, persona = "P", hpOllama = rig.hpOllama)
        val service = ChatService(rig.chats, UndoService(rig.sdb, rig.store, rig.writer, rig.chats), xpsRunner) { rig.now }
        application { module(rig.config, rig.store, clock = { rig.now }, chat = service, approvalSink = rig.sink, notify = rig.notify) }
        val (st, body) = call("GET", "/v1/chat/brain")
        assertEquals(HttpStatusCode.OK, st, body)
        assertEquals("xps", json17.decodeFromString<ChatBrainResponse>(body).brain)
        assertEquals(HttpStatusCode.OK, call("GET", "/v1/chat/brain", "lap-token").first)
    }

    @Test
    fun test_AC4_R3_brain_route_401_without_token_and_403_for_calendar_token() = testApplication {
        val rig = P17Rig()
        up(rig)
        assertEquals(HttpStatusCode.Unauthorized, call("GET", "/v1/chat/brain", null).first)
        assertEquals(HttpStatusCode.Unauthorized, call("GET", "/v1/chat/brain", "nope").first)
        assertEquals(HttpStatusCode.Forbidden, call("GET", "/v1/chat/brain", "cal-token").first)
    }

    @Test
    fun test_AC12_R3_slowReplyHint_shows_after_10s_only_for_hp() {
        assertEquals(null, slowReplyHint(10_000L, true))
        assertEquals(null, slowReplyHint(0L, true))
        assertEquals(SLOW_REPLY_HINT, slowReplyHint(10_001L, true))
        assertEquals(SLOW_REPLY_HINT, slowReplyHint(120_000L, true))
        for (ms in listOf(0L, 5_000L, 10_001L, 120_000L)) assertEquals(null, slowReplyHint(ms, false), "xps $ms")
    }
    @Test
    fun test_AC4_calendar_token_is_403_and_unknown_token_401_on_every_new_route() = testApplication {
        val rig = P17Rig()
        up(rig)
        for ((m, p) in newRoutes) {
            assertEquals(HttpStatusCode.Forbidden, call(m, p, "cal-token", "{}").first, "$m $p calendar")
            assertEquals(HttpStatusCode.Unauthorized, call(m, p, "nope", "{}").first, "$m $p unknown")
        }
        assertEquals(HttpStatusCode.NotFound, call("GET", "/v1/chat/does-not-exist").first)
    }

    private suspend fun ApplicationTestBuilder.newChat(id: String): NewChatResponse {
        val (st, body) = call("POST", "/v1/chat", body = json17.encodeToString(NewChatRequest(id)))
        assertTrue(st == HttpStatusCode.OK || st == HttpStatusCode.Created, "$st $body")
        return json17.decodeFromString(body)
    }

    private suspend fun ApplicationTestBuilder.list(): ChatListResponse = json17.decodeFromString(call("GET", "/v1/chat/list").second)

    @Test
    fun test_AC4_chat_create_is_idempotent_by_id() = testApplication {
        val rig = P17Rig()
        up(rig)
        val id = UUID.randomUUID().toString()
        newChat(id)
        val before = list()
        newChat(id)
        val after = list()
        assertEquals(before.activeCount, after.activeCount)
        assertEquals(1, after.chats.count { it.id == id })
    }

    @Test
    fun test_AC4_eleventh_chat_archives_the_oldest_and_leaves_a_pending_memory_note() = testApplication {
        val rig = P17Rig()
        up(rig)
        val ids = (1..11).map { UUID.randomUUID().toString() }
        var archivedTitle: String? = null
        for ((i, id) in ids.withIndex()) {
            rig.now += 60_000
            val r = newChat(id)
            if (i == 0) { // the chat that will be archived has a conversation worth remembering
                val m = SendMessageRequest(UUID.randomUUID().toString(), "remember the dentist on Monday", rig.now, "Today")
                assertEquals(HttpStatusCode.OK, call("POST", "/v1/chat/$id/message", body = json17.encodeToString(m)).first)
            }
            if (i == 10) archivedTitle = r.archivedTitle
        }
        val l = list()
        assertEquals(10, l.activeCount)
        assertEquals(10, l.limit)
        assertTrue(l.chats.none { it.id == ids.first() }, "the least recently used chat is archived")
        assertTrue(l.chats.any { it.id == ids.last() })
        assertNotNull(archivedTitle, "the response names the archived chat")
        val memory = json17.decodeFromString<MemoryResponse>(call("GET", "/v1/memory").second)
        assertEquals(1, memory.notes.size)
        assertTrue(memory.notes.single().pending, "summary not written yet")
    }

    @Test
    fun test_AC4_pinned_weekly_report_chat_cannot_be_deleted() = testApplication {
        val rig = P17Rig()
        up(rig)
        val pinned = list().chats.single { it.pinned }
        assertEquals(HttpStatusCode.Conflict, call("DELETE", "/v1/chat/${pinned.id}").first)
        assertTrue(list().chats.any { it.id == pinned.id })
    }

    @Test
    fun test_AC4_same_messageId_same_text_replays_with_one_model_call_and_other_text_is_409() = testApplication {
        val rig = P17Rig()
        up(rig)
        val chat = UUID.randomUUID().toString()
        newChat(chat)
        val mid = UUID.randomUUID().toString()
        fun req(text: String) = json17.encodeToString(SendMessageRequest(mid, text, P17_T0, "Today"))
        val (s1, b1) = call("POST", "/v1/chat/$chat/message", body = req("hello wally"))
        assertEquals(HttpStatusCode.OK, s1, b1)
        val first = json17.decodeFromString<SendMessageResponse>(b1)
        assertEquals("Hello Tim", first.reply.text)
        rig.reply = "A different answer"
        val (s2, b2) = call("POST", "/v1/chat/$chat/message", body = req("hello wally"))
        assertEquals(HttpStatusCode.OK, s2, b2)
        assertEquals(first.reply.text, json17.decodeFromString<SendMessageResponse>(b2).reply.text)
        assertEquals(1, rig.bodies.size, "no second model run")
        assertEquals(HttpStatusCode.Conflict, call("POST", "/v1/chat/$chat/message", body = req("something else")).first)
        val detail = json17.decodeFromString<ChatDetailResponse>(call("GET", "/v1/chat/$chat").second)
        assertEquals(2, detail.messages.size, "one user message and one reply")
    }

    @Test
    fun test_AC4_second_yes_on_an_approval_is_409_and_writes_nothing() = testApplication {
        val rig = P17Rig()
        up(rig)
        val id = p17CreateApproval(rig, "create_task", """{"title":"Email lecturer"}""", "Make task Email lecturer")
        fun tasks() = rig.sdb.db.taskQueries.selectAll().executeAsList().filter { it.deleted == 0L }.size
        assertEquals(0, tasks())
        val (s1, b1) = call("POST", "/v1/approvals/$id", body = """{"decision":"yes"}""")
        assertEquals(HttpStatusCode.OK, s1, b1)
        assertEquals(1, tasks())
        assertEquals(HttpStatusCode.Conflict, call("POST", "/v1/approvals/$id", body = """{"decision":"yes"}""").first)
        assertEquals(HttpStatusCode.Conflict, call("POST", "/v1/approvals/$id", body = """{"decision":"no"}""").first)
        assertEquals(1, tasks())
        assertEquals("DONE", rig.sdb.db.approvalQueries.selectById(id).executeAsOne().status)
    }

    @Test
    fun test_AC4_stale_client_push_cannot_change_a_decided_approval() = testApplication {
        val rig = P17Rig()
        up(rig)
        val id = p17CreateApproval(rig, "create_task", """{"title":"Email lecturer"}""", "Make task Email lecturer")
        assertEquals(HttpStatusCode.OK, call("POST", "/v1/approvals/$id", body = """{"decision":"yes"}""").first)
        val decided = rig.store.pull(0).rows.single { it.table == "approval" && it.id == id }
        val stale: SyncRow = decided.copy(updatedAt = decided.updatedAt + 60_000, data = decided.data.let { d ->
            kotlinx.serialization.json.JsonObject(d + ("status" to JsonPrimitive("PENDING")))
        })
        rig.store.push(listOf(stale))
        assertEquals("DONE", rig.sdb.db.approvalQueries.selectById(id).executeAsOne().status)
    }

    @Test
    fun test_AC10_notifications_replay_by_since_and_targets_follow_the_contract() = testApplication {
        val rig = P17Rig()
        up(rig)
        val q = rig.sdb.db.chatQueries
        q.insertNotification("CHECKIN", "Check-in", "How was today?", null, P17_T0, P17_T0, "k1")
        q.insertNotification("MORNING", "Today", "3 tasks", null, P17_T0, P17_T0, "k2")
        val all = json17.decodeFromString<NotificationsResponse>(call("GET", "/v1/notifications?since=0").second).items
        assertEquals(2, all.size)
        assertEquals("screen:checkin-add", all[0].target)
        assertEquals("screen:today", all[1].target)
        val again = json17.decodeFromString<NotificationsResponse>(call("GET", "/v1/notifications?since=0").second).items
        assertEquals(all.map { it.id }, again.map { it.id }, "a replay returns every row above the cursor")
        val tail = json17.decodeFromString<NotificationsResponse>(call("GET", "/v1/notifications?since=${all[0].id}").second).items
        assertEquals(listOf(all[1].id), tail.map { it.id })
        assertTrue(json17.decodeFromString<NotificationsResponse>(call("GET", "/v1/notifications?since=${all[1].id}").second).items.isEmpty())
    }

    @Test
    fun test_AC10_notify_settings_get_put_and_bad_time_is_400() = testApplication {
        val rig = P17Rig()
        up(rig)
        assertEquals(NotifySettingsDto(), json17.decodeFromString<NotifySettingsDto>(call("GET", "/v1/notify-settings").second))
        val bad = json17.encodeToString(NotifySettingsDto(checkinTime = "25:99"))
        assertEquals(HttpStatusCode.BadRequest, call("PUT", "/v1/notify-settings", body = bad).first)
        assertEquals("21:00", json17.decodeFromString<NotifySettingsDto>(call("GET", "/v1/notify-settings").second).checkinTime)
        val good = NotifySettingsDto(checkinTime = "20:30", quietStart = "22:30", quietEnd = "06:30", ideas = false)
        val (st, b) = call("PUT", "/v1/notify-settings", body = json17.encodeToString(good))
        assertTrue(st == HttpStatusCode.OK || st == HttpStatusCode.NoContent, "$st $b")
        assertEquals(good, json17.decodeFromString<NotifySettingsDto>(call("GET", "/v1/notify-settings").second))
    }

    // ================================================================ release, schema, ops
    @Test
    fun test_AC14_release_1_16_0_versionCode_12() {
        assertTrue(p17Text("desktopApp/build.gradle.kts").contains("packageVersion = \"1.18.0\""))
        val a = p17Text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
    }

    @Test
    fun test_AC14_active_checklists_name_1_16_0() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            assertTrue(p17Text("ops/checklists/$f.md").contains("1.18.0"), "$f.md names 1.18.0")
        }
        assertTrue(p17Text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
    }

    @Test
    fun test_AC14_schema_is_version_8_with_34_tables_including_the_four_chat_tables() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(d)
        assertEquals(8L, AssistantDb.Schema.version)
        val names = ArrayList<String>()
        d.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", { c ->
            while (c.next().value) names += c.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Unit
        }, 0)
        assertEquals(34, names.size, names.toString())
        for (n in listOf("chat", "chat_message", "memory_note", "outbox_notification")) assertTrue(n in names, n)
    }

    @Test
    fun test_AC14_restore_helper_lists_34_tables_including_the_four_chat_tables() {
        val t = p17Text("ops/assistant-restore-test.sh")
        val tables = Regex("^TABLES=\"([^\"]*)\"", RegexOption.MULTILINE).find(t)!!.groupValues[1].trim().split(Regex("\\s+"))
        assertEquals(34, tables.size, tables.toString())
        assertEquals(34, tables.toSet().size)
        for (n in listOf("chat", "chat_message", "memory_note", "outbox_notification")) assertTrue(n in tables, n)
    }

    @Test
    fun test_AC15_phase17_checklist_names_SC5_to_SC12_and_the_canvases() {
        val t = p17Text("ops/checklists/phase17.md")
        for (n in 5..12) assertTrue(t.contains("SC-$n"), "SC-$n")
        for (k in listOf("ChatMain", "ChatCard", "ChatList", "ChatMemory", "LaptopChat", "BrowseSettings", "LaptopSettings")) {
            assertTrue(t.contains(k), k)
        }
    }

    // ================================================================ source guards
    @Test
    fun test_AC12_MainActivity_has_no_literal_backslash_n_in_comments_and_setContent_calls_App() {
        val src = p17Text("androidApp/src/main/kotlin/dev/tim/assistant/MainActivity.kt")
        for (line in src.lines()) {
            val i = line.indexOf("//")
            if (i >= 0) assertFalse(line.substring(i).contains("\\n"), "literal \\n inside a comment: $line")
        }
        assertTrue(Regex("setContent\\s*\\{[\\s\\S]*?\\bApp\\(").containsMatchIn(src), "setContent must still call App")
    }

    @Test
    fun test_AC12_ReminderReceiver_is_not_exported() {
        val m = p17Text("androidApp/src/main/AndroidManifest.xml")
        val receiver = assertNotNull(Regex("<receiver\\b[^>]*ReminderReceiver[^>]*>").find(m), "ReminderReceiver declared").value
        assertTrue(receiver.contains("android:exported=\"false\""), receiver)
        assertTrue(m.contains("POST_NOTIFICATIONS"))
    }

    @Test
    fun test_AC8_desktop_main_no_longer_registers_codex_jobs() {
        val m = p17Text("desktopApp/src/main/kotlin/main.kt")
        for (k in listOf("ResearchJobs", "MealLookupJob", "CodexResearch")) assertFalse(m.contains(k), "main.kt still mentions $k")
        assertFalse(File(File("").absoluteFile.let { var d: File? = it; while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile; d!! }, "desktopApp/src/main/kotlin/CodexProcessRunner.kt").exists())
    }
}
