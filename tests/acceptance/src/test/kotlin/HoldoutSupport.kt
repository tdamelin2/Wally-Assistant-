import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Dream
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.chat.ChatRunner
import dev.tim.assistant.server.chat.ChatService
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.UndoService
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.chat.TurnResult
import dev.tim.assistant.server.chat.ladder.Sorter
import dev.tim.assistant.server.openDb
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The fixed clock of the held-out tests: Thursday 2026-11-12, 10:00 in Jerusalem. */
val HOLDOUT_NOW: Long = Instant.parse("2026-11-12T08:00:00Z").toEpochMilli()
const val HOLDOUT_TODAY = "2026-11-12"
private fun at(hhmm: String, day: String = HOLDOUT_TODAY): Long =
    java.time.LocalDateTime.parse("${day}T$hhmm").atZone(java.time.ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()

/** One held-out line: its id, text, expected intent (or "pass_up") and the expected args. */
class HoldoutLine(val id: String, val text: String, val expect: String, val args: JsonObject)

fun loadHoldout(resource: String): List<HoldoutLine> {
    val raw = checkNotNull(HoldoutLine::class.java.getResourceAsStream("/$resource")) { "$resource missing" }
        .bufferedReader(Charsets.UTF_8).readText()
    return raw.lines().filter { it.isNotBlank() }.map {
        val o = Json.parseToJsonElement(it).jsonObject
        HoldoutLine(o.getValue("id").jsonPrimitive.content, o.getValue("text").jsonPrimitive.content, o.getValue("expect").jsonPrimitive.content, o.getValue("args").jsonObject)
    }
}

/** The fixture ids by name (6 tasks with two similar names, 3 exams). */
val FIXTURE_TASKS = mapOf(
    "physics homework" to "task:physics", "buy groceries" to "task:groceries", "email professor Levi" to "task:levi",
    "read chapter 5" to "task:ch5", "read chapter 6" to "task:ch6", "renew library books" to "task:library",
)
val FIXTURE_EXAMS = mapOf("calculus" to "exam:calc", "physics" to "exam:phys", "linear algebra" to "exam:linalg")

/**
 * The real chat runner over a temp database with the held-out fixture, a fixed clock and a brain that is the HP alone
 * (or, with [xps] true, the laptop model too). [scriptedHp] puts an [OllamaClient] on a MockEngine that answers [hpReplies] in order
 * (the HP small model's raw message content) and records every request body in [hpBodies]; the laptop model's one scripted answer
 * is [laptopText] and its request bodies are in [laptopBodies]. [realPersona] uses the real laptop persona file.
 */
class HoldoutRig(
    sorter: Sorter? = null,
    hpOllama: OllamaClient? = null,
    val withFixture: Boolean = true,
    xps: Boolean = false,
    scriptedHp: Boolean = false,
    realPersona: Boolean = false,
) {
    var now: Long = HOLDOUT_NOW
    val sdb: ServerDb = openDb(Files.createTempDirectory("holdout").resolve("t.db").toString())
    val store = SyncStore(sdb)
    val writer = ServerWriter(sdb, store) { now }
    val chats = ChatStore(sdb, writer) { now }
    val hpBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
    val laptopBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
    val hpReplies = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile var hpStatus: HttpStatusCode = HttpStatusCode.OK
    @Volatile var laptopText: String = "Laptop says hi"
    val brain = if (xps) {
        Brain(
            "http://xps.test:11434", "http://ollama.test", "hp-model",
            engine = MockEngine { respond("""{"models":[{"name":"${dev.tim.assistant.ai.CHAT_MODEL}"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
    } else {
        Brain(null, "http://ollama.test", "hp-model", engine = MockEngine { respond("{}", HttpStatusCode.OK) })
    }
    val chatClient = ChatClient(
        MockEngine { req ->
            laptopBodies += (req.body as TextContent).text
            respond("""{"model":"m","message":{"role":"assistant","content":${JsonPrimitive(laptopText)}},"done":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
    private val hp: OllamaClient? = hpOllama ?: if (scriptedHp) OllamaClient(
        "http://ollama.test",
        MockEngine { req ->
            hpBodies += (req.body as TextContent).text
            val content = hpReplies.poll() ?: """{"kind":"answer","reply":"(script ran out)","args":{}}"""
            respond("""{"model":"m","message":{"role":"assistant","content":${JsonPrimitive(content)}},"done":true}""", hpStatus, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) else null
    val runner = ChatRunner(
        sdb, store, writer, chats, brain, Mutex(), chatClient, { now },
        persona = if (realPersona) dev.tim.assistant.server.chat.laptopPersona() else "test persona", sorter = sorter, hpOllama = hp,
    )
    val service = ChatService(chats, UndoService(sdb, store, writer, chats), runner) { now }
    init {
        if (withFixture) seedFixture()
    }

    private fun seedFixture() {
        val q = sdb.db
        fun task(id: String, title: String, due: String? = null) = q.taskQueries.upsert(Task(id, 1000, 0, 0, "SYNCED", title, due, 0, null, null))
        task("task:physics", "physics homework", "2026-11-14")
        task("task:groceries", "buy groceries", HOLDOUT_TODAY)
        task("task:levi", "email professor Levi")
        task("task:ch5", "read chapter 5")
        task("task:ch6", "read chapter 6")
        task("task:library", "renew library books", "2026-11-16")
        fun exam(id: String, title: String, date: String) = q.examQueries.upsert(Exam(id, 1000, 0, 0, "SYNCED", null, title, date, null))
        exam("exam:calc", "Calculus", "2026-12-03")
        exam("exam:phys", "Physics", "2026-12-10")
        exam("exam:linalg", "Linear Algebra", "2026-12-17")
        fun ev(id: String, title: String, start: Long, end: Long) = q.cal_eventQueries.upsert(Cal_event("cal:primary:$id", 1000, 0, 0, "SYNCED", "primary", id, title, start, end, 0, null))
        ev("e1", "Physics lecture", at("12:00"), at("13:30"))
        ev("e2", "Gym", at("18:00"), at("19:00"))
        ev("e3", "Lunch with Dana", at("13:00", "2026-11-13"), at("14:00", "2026-11-13"))
        ev("e4", "Study group", at("14:00", "2026-11-14"), at("16:00", "2026-11-14"))
        fun meal(id: String, type: String, text: String, hhmm: String) =
            q.mealQueries.upsert(Meal("meal:$id", at(hhmm), 0, 0, "SYNCED", type, text, at(hhmm), HOLDOUT_TODAY, null, null, null, null, null))
        meal("m1", "MEAL", "oats", "07:30")
        meal("m2", "SNACK", "apple", "09:15")
        fun spend(id: String, agorot: Long, cat: String, note: String, date: String) =
            q.spendingQueries.upsert(Spending("spending:$id", 1000, 0, 0, "SYNCED", agorot, cat, note, date))
        spend("s1", 3500, "Food", "lunch", HOLDOUT_TODAY)
        spend("s2", 2800, "Transport", "taxi", HOLDOUT_TODAY)
        spend("s3", 4000, "Food", "pizza", "2026-11-11")
    }

    /** Every row of the tables a turn may write, keyed by "table:id". */
    fun snapshot(): Map<String, Any> {
        val q = sdb.db
        val m = LinkedHashMap<String, Any>()
        q.taskQueries.selectAll().executeAsList().forEach { m["task:${it.id}"] = it }
        q.examQueries.selectAll().executeAsList().forEach { m["exam:${it.id}"] = it }
        q.mealQueries.selectAll().executeAsList().forEach { m["meal:${it.id}"] = it }
        q.spendingQueries.selectAll().executeAsList().forEach { m["spending:${it.id}"] = it }
        q.checkinQueries.selectAll().executeAsList().forEach { m["checkin:${it.id}"] = it }
        q.journalQueries.selectAll().executeAsList().forEach { m["journal:${it.id}"] = it }
        q.dreamQueries.selectAll().executeAsList().forEach { m["dream:${it.id}"] = it }
        q.approvalQueries.selectAll().executeAsList().forEach { m["approval:${it.id}"] = it }
        q.cal_eventQueries.selectAll().executeAsList().forEach { m["cal:${it.id}"] = it }
        return m
    }

    /** Sends one message down the ladder (a fresh chat) and returns the turn result and the rows it changed. */
    fun send(text: String, messageId: String = "m1", chatId: String = "c1"): Pair<TurnResult, Map<String, Any>> = runBlocking {
        chats.create(chatId)
        chats.addUserMessage(chatId, messageId, text, now)
        val before = snapshot()
        val result = runner.run(chatId, "r:$messageId", messageId, text, null, null)
        val after = snapshot()
        result to after.filter { (k, v) -> before[k] != v }
    }

    fun close() = sdb.close()
}

enum class Outcome { HANDLED, NOT_HANDLED, WRONG }

class LineVerdict(val line: HoldoutLine, val outcome: Outcome, val why: String)

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
private fun containsCi(actual: String?, expected: String) = actual != null && actual.contains(expected, ignoreCase = true)

private fun fill(s: String): String = s.replace("{today}", HOLDOUT_TODAY).replace("{tomorrow}", "2026-11-13").replace("{sunday}", "2026-11-15")

private fun TurnResult.byCode(): Boolean = this is TurnResult.Reply && (brain == "code" || brain == "sorter" || brain == "ask" || brain == "tap")

private fun Map<String, Any>.rows(prefix: String) = filterKeys { it.startsWith("$prefix:") }.values.toList()

/** Compares what one turn did with what the line expects (the rules of PLAN 18.7 step 1). */
fun judge(line: HoldoutLine, result: TurnResult, changed: Map<String, Any>, askWithoutWriteIsNotHandled: Boolean = false): LineVerdict {
    fun v(o: Outcome, why: String) = LineVerdict(line, o, why)
    val a = line.args
    val byCode = result.byCode()
    // Tim's decision 2026-11-17 10:24 (sorter run only): a brain question that wrote nothing is "not handled" on pass_up and find lines.
    if (askWithoutWriteIsNotHandled && (line.expect == "pass_up" || line.expect == "find") && changed.isEmpty() &&
        result is TurnResult.Reply && result.brain == "ask") return v(Outcome.NOT_HANDLED, "question, nothing written")
    if (line.expect == "pass_up") {
        if (changed.isNotEmpty()) return v(Outcome.WRONG, "wrote or carded on a pass_up line: ${changed.keys.map { it.substringBefore(':') }}")
        if (byCode) return v(Outcome.WRONG, "code answered a pass_up line")
        return v(Outcome.HANDLED, "passed up")
    }
    val answer = (result as? TurnResult.Reply)?.text.orEmpty()
    val isAsk = line.expect.startsWith("ask_") || line.expect == "find"
    if (isAsk) {
        if (changed.isNotEmpty()) return v(Outcome.WRONG, "wrote on a question line")
        if (!byCode) return v(Outcome.NOT_HANDLED, "not answered by code")
        val needed = a["answerContains"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val missing = needed.filter { !answer.contains(it, ignoreCase = true) }
        return if (missing.isEmpty()) v(Outcome.HANDLED, "answered") else v(Outcome.WRONG, "answer lacks the expected value")
    }
    if (changed.isEmpty()) return v(Outcome.NOT_HANDLED, "nothing written")
    val table = when (line.expect) {
        "create_task" -> "task"; "add_meal" -> "meal"; "add_spending" -> "spending"; "add_checkin" -> "checkin"
        "add_journal" -> "journal"; "add_dream" -> "dream"
        "mark_task_done", "move_task", "move_exam" -> "approval"
        else -> error("unknown expect ${line.expect}")
    }
    val rows = changed.rows(table)
    if (changed.size != 1 || rows.size != 1) return v(Outcome.WRONG, "wrote ${changed.keys.map { it.substringBefore(':') }} instead of one $table")
    val row = rows.single()
    val problems = ArrayList<String>()
    fun eq(name: String, actual: Any?, expected: String?) { if (actual?.toString() != expected) problems += name }
    fun has(name: String, actual: String?) { a.str(name)?.let { if (!containsCi(actual, it)) problems += name } }
    when (row) {
        is Task -> { has("title", row.title); eq("dueDate", row.dueDate, a.str("dueDate")?.let(::fill)) }
        is Meal -> { eq("type", row.type, a.str("type")); has("text", row.text); eq("date", row.localDate, a.str("date")?.let(::fill)) }
        is Spending -> {
            eq("amount", row.amountAgorot, a.str("amount")?.let { (it.toBigDecimal().multiply(100.toBigDecimal())).toBigInteger().toString() })
            if (a.str("category") != null) eq("category", row.category, a.str("category")); eq("date", row.localDate, a.str("date")?.let(::fill))
        }
        is Checkin -> {
            eq("mood", row.mood, a.str("mood")); eq("energy", row.energy, a.str("energy"))
            eq("sleepQuality", row.sleepQuality, a.str("sleepQuality")); eq("stress", row.stress, a.str("stress")); eq("date", row.localDate, a.str("date")?.let(::fill))
        }
        is Journal -> has("text", row.text)
        is Dream -> { has("text", row.text); eq("date", row.localDate, a.str("date")?.let(::fill)) }
        is Approval -> {
            val args = Json.parseToJsonElement(row.argsJson).jsonObject
            val tool = if (line.expect == "move_exam") "update_exam" else "update_task"
            if (row.tool != tool) problems += "tool"
            if (row.status != "PENDING") problems += "status"
            val target = a.str("target")!!
            val id = (if (line.expect == "move_exam") FIXTURE_EXAMS.entries.firstOrNull { it.key.equals(target, true) }?.value
            else FIXTURE_TASKS.entries.firstOrNull { it.key.equals(target, true) }?.value)
            if (args.str("id") != id) problems += "target"
            if (line.expect == "mark_task_done" && (args["done"]?.jsonPrimitive?.content != "true")) problems += "done"
            if (line.expect == "move_task" && args.str("dueDate") != a.str("dueDate")?.let(::fill)) problems += "dueDate"
            if (line.expect == "move_exam") {
                if (args.str("localDate") != a.str("localDate")) problems += "localDate"
                a.str("time")?.let { if (args.str("time") != it) problems += "time" }
            }
        }
        else -> problems += "unexpected row type"
    }
    return if (problems.isEmpty()) v(Outcome.HANDLED, "ok") else v(Outcome.WRONG, "args differ: $problems")
}

/** Runs every line of [files] on fresh rigs and returns the verdicts (a line of the guards file is judged like a pass_up line). */
fun runHoldout(files: List<String>, askWithoutWriteIsNotHandled: Boolean = false, newRig: () -> HoldoutRig): List<LineVerdict> {
    val out = ArrayList<LineVerdict>()
    for (f in files) for (line in loadHoldout(f)) {
        val rig = newRig()
        try {
            val (result, changed) = rig.send(line.text)
            out += judge(line, result, changed, askWithoutWriteIsNotHandled)
        } finally {
            rig.close()
        }
    }
    return out
}

/** A one-line report that never prints a message text: id, expected intent and what went wrong. */
fun describe(v: LineVerdict) = "${v.line.id} expect=${v.line.expect} ${v.outcome} (${v.why})"
