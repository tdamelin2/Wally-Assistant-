package dev.tim.assistant.ai

import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.ToolBox
import dev.tim.assistant.ai.tools.ToolResult
import dev.tim.assistant.ai.tools.WAITING_FOR_APPROVAL
import dev.tim.assistant.ai.tools.notOfferedText
import dev.tim.assistant.ai.tools.WriteHandler
import dev.tim.assistant.data.PENDING
import dev.tim.assistant.data.researchQuestionKey
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Research
import dev.tim.assistant.study.sha256Hex
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds a tool-call argument object from plain values. */
fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

/** An in-memory rig with the read/write tools wired on the shared repository and clock. */
class ToolRig {
    val rig = JobRig()
    val repo get() = rig.repo
    val db get() = rig.db
    val read = ReadTools(rig.repo) { rig.clock }
    /** Write tools are run by the server now: a validated call that reaches this handler is answered as a card was made (no row is written). */
    val write = object : WriteHandler {
        private var made = 0
        override suspend fun handle(name: String, args: JsonObject, allowed: Set<String>): ToolResult =
            if (name !in allowed) ToolResult(notOfferedText(name)) else ToolResult(WAITING_FOR_APPROVAL, approvalId = "appr:stub-${++made}")
    }
    val box = ToolBox(read, write)

    /** Row counts of every table a tool could write (live and deleted). */
    fun counts(): Map<String, Int> = mapOf(
        "task" to db.taskQueries.selectAll().executeAsList().size,
        "doc" to db.docQueries.selectAll().executeAsList().size,
        "goal" to db.goalQueries.selectAll().executeAsList().size,
        "exam" to db.examQueries.selectAll().executeAsList().size,
        "ai_job" to db.ai_jobQueries.selectAll().executeAsList().size,
        "mail_draft" to db.mail_draftQueries.selectAll().executeAsList().size,
        "mail_action" to db.mail_actionQueries.selectAll().executeAsList().size,
        "app_rule" to db.app_ruleQueries.selectAll().executeAsList().size,
        "cal_event" to db.cal_eventQueries.selectAll().executeAsList().size,
        "research" to db.researchQueries.selectAll().executeAsList().size,
        "meal" to db.mealQueries.selectAll().executeAsList().size,
        "spending" to db.spendingQueries.selectAll().executeAsList().size,
        "checkin" to db.checkinQueries.selectAll().executeAsList().size,
        "journal" to db.journalQueries.selectAll().executeAsList().size,
    )
}

/** Seeds one research row the way the saved-research list holds it; returns its id. */
fun AssistantDb.seedResearch(category: String, question: String, answerText: String, answerJson: String, sources: String, askedAt: Long, expiresAt: Long?): String {
    val key = researchQuestionKey(question)
    val id = "research:$category:${sha256Hex(key)}"
    researchQueries.upsert(Research(id, 1, 0, 0, PENDING, category, key, question, answerText, answerJson, sources, askedAt, expiresAt))
    return id
}

/** A scripted Ollama: answers the n-th request with the n-th response (the last one repeats) and records bodies. */
class ScriptedOllama(private val responses: List<Pair<HttpStatusCode, String>>) {
    val bodies = mutableListOf<String>()
    val engine = MockEngine { request: HttpRequestData ->
        bodies += (request.body as TextContent).text
        val (status, text) = responses[minOf(bodies.size - 1, responses.lastIndex)]
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val client get() = ChatClient(engine)

    /** True when request [n] (0-based) carried a `tools` array. */
    fun hadTools(n: Int): Boolean = "\"tools\"" in bodies[n]
}

fun answerBody(text: String): Pair<HttpStatusCode, String> =
    HttpStatusCode.OK to buildJsonObject {
        put("message", buildJsonObject { put("role", "assistant"); put("content", text) })
        put("done", true)
    }.toString()

fun callsBody(vararg calls: Pair<String, JsonObject>): Pair<HttpStatusCode, String> =
    HttpStatusCode.OK to buildJsonObject {
        put("message", buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("tool_calls", buildJsonArray {
                for ((name, a) in calls) add(buildJsonObject { put("function", buildJsonObject { put("name", name); put("arguments", a) }) })
            })
        })
        put("done", true)
    }.toString()
