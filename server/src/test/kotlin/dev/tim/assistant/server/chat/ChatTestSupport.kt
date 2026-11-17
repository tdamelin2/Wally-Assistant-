package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.ToolCall
import dev.tim.assistant.ai.tools.ApprovalExecutor
import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.ToolBox
import dev.tim.assistant.ai.tools.ToolResult
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Goal
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.WallyCalConfig
import dev.tim.assistant.server.chat.ladder.Sorter
import dev.tim.assistant.server.ai.OLLAMA_URL
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.ai.OllamaFake
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import dev.tim.assistant.server.testJson
import dev.tim.assistant.server.wallycal.WallyCalClient
import dev.tim.assistant.server.wallycal.WallyCalPoller
import dev.tim.assistant.server.wallycal.WallyCalService
import dev.tim.assistant.server.wallycal.WallyCalStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val CAL_TOKEN = "inbound-calendar-token"
val CHAT_T0: Long = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()

/** An Ollama answer that asks for one tool call. */
fun toolCallBody(name: String, args: String): String =
    """{"model":"m","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"$name","arguments":$args}}]},"done":true}"""

/** One fixed-shape decision as the HP model would return it. */
fun decisionJson(kind: String, reply: String? = null, action: String? = null, target: String? = null, args: Map<String, String> = emptyMap()): String =
    buildJsonObject {
        put("kind", kind)
        if (reply != null) put("reply", reply)
        if (action != null) put("action", action)
        if (target != null) put("target", target)
        put("args", buildJsonObject { for ((k, v) in args) put(k, v) })
    }.toString()

/** A scripted WallyCalendar: records POST bodies. */
class FakeCalendar {
    val posts = CopyOnWriteArrayList<String>()
    @Volatile var status = 201

    fun engine() = MockEngine { request ->
        if (request.method.value == "POST") posts += String(request.body.toByteArray(), Charsets.UTF_8)
        val body = if (request.method.value == "POST") """{"id":"ev-1"}""" else """{"events":[]}"""
        respond(body, HttpStatusCode.fromValue(if (request.method.value == "POST") status else 200), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

/** Everything a chat test needs: database, scripted model, brain, store, runner, service, sink and a calendar fake. */
class ChatRig(withCalendar: Boolean = false, xps: Boolean = false, sorter: Sorter? = null, researchRunner: dev.tim.assistant.research.ResearchRunner? = null) {
    var now: Long = CHAT_T0
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb)
    val writer = ServerWriter(sdb, store) { now }
    val chats = ChatStore(sdb, writer) { now }
    val lock = Mutex()
    val fake = OllamaFake()
    val summaryFake = OllamaFake()
    /** The home server's small model (rung 5): scripted fixed-shape answers through an OllamaClient on a MockEngine. */
    val hpFake = OllamaFake()
    val hpOllama = OllamaClient(OLLAMA_URL, hpFake.engine())
    val calendar = FakeCalendar()
    val chatClient = ChatClient(fake.engine())
    /** xps = true: the XPS model is "awake" (full tool list, full persona); default is the HP alone (code rungs, then the last resort). */
    val brain = if (xps) {
        Brain(
            "http://xps.test:11434", OLLAMA_URL, "hp-model",
            engine = MockEngine {
                respond(
                    """{"models":[{"name":"${dev.tim.assistant.ai.CHAT_MODEL}"}]}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
    } else {
        Brain(null, OLLAMA_URL, "hp-model", engine = MockEngine { respond("{}", HttpStatusCode.OK) })
    }
    val repo = Repository(LocalStore(sdb.db), now = { now })
    val read = ReadTools(repo, { now })
    /** Set when [researchRunner] is given: the real ServerCodex over a scripted runner, handed to the chat runner as its research approver. */
    val codex: dev.tim.assistant.server.codex.ServerCodex? = researchRunner?.let { dev.tim.assistant.server.codex.ServerCodex(sdb, writer, it, clock = { now }) }
    val runner = ChatRunner(sdb, store, writer, chats, brain, lock, chatClient, { now }, persona = "You are Wally (test persona).", sorter = sorter, hpOllama = hpOllama, researchApprover = codex)
    val undo = UndoService(sdb, store, writer, chats)
    val service = ChatService(chats, undo, runner) { now }
    val summarizer = ChatSummarizer(sdb, writer, chats, brain, lock) { OllamaClient(it, summaryFake.engine()) }
    val wallyCal: WallyCalService? = if (withCalendar) {
        val cfg = WallyCalConfig("http://hp.example:8081", "secret")
        val client = WallyCalClient(HttpClient(calendar.engine()), cfg)
        WallyCalService(sdb, client, WallyCalPoller(sdb, store, client, WallyCalStatus()) { now })
    } else {
        null
    }
    val sink = ServerApprovalSink(sdb, store, writer, wallyCal, null)
    val approvals = ApprovalExecutor(sink) { now }

    /** The HP small model's decisions (see [decisionJson]), in order, for the next requests. */
    fun scriptHp(vararg decisions: String) {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(decisions.toList())
        hpFake.reply = { 200 to chatBody(queue.poll() ?: decisionJson("answer", "(script ran out)")) }
    }

    /** The laptop model's answers, in order, for the next requests. */
    fun script(vararg bodies: String) {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(bodies.toList())
        fake.reply = { 200 to (queue.poll() ?: chatBody("(script ran out)")) }
    }

    fun executor(chatId: String = "c1", replyId: String = "r:m1"): ServerToolExecutor =
        ServerToolExecutor(sdb, store, writer, chats, repo, read, { now }, chatId, replyId)

    /** Runs one tool call through a real ToolBox, the way the chat loop does. */
    suspend fun call(executor: ServerToolExecutor, name: String, args: String, allowed: Set<String>? = null): ToolResult {
        val box = ToolBox(read, executor, executor.research)
        return box.call(ToolCall(name, testJson.parseToJsonElement(args) as JsonObject), allowed)
    }

    suspend fun seed(row: SyncRow) {
        sdb.writeLock.withLock { sdb.db.transaction { store.writeIfNewer(row) } }
    }

    suspend fun seedCourse(id: String, name: String) = seed(toSyncRow(Course(id, 1000, 0, 0, "SYNCED", null, name, 1)))
    suspend fun seedGoal(id: String, title: String) = seed(toSyncRow(Goal(id, 1000, 0, 0, "SYNCED", title, null, null, "ACTIVE")))
    suspend fun seedMail(gmailId: String, subject: String) =
        seed(toSyncRow(Mail_item("mail:$gmailId", 1000, 0, 0, "SYNCED", gmailId, "a@b.example", subject, "snippet", 1000)))

    suspend fun pull(): List<SyncRow> = store.pull(0).rows

    fun row(table: String, id: String): SyncRow? = runBlockingPull().firstOrNull { it.table == table && it.id == id }

    private fun runBlockingPull(): List<SyncRow> = kotlinx.coroutines.runBlocking { store.pull(0).rows }

    fun str(row: SyncRow, key: String): String? = (row.data[key] as? JsonPrimitive)?.takeIf { it.content != "null" }?.content

    fun close() {
        sdb.close()
    }
}
