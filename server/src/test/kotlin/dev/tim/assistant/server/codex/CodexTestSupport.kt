package dev.tim.assistant.server.codex

import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Meal
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.research.ResearchRow
import dev.tim.assistant.research.ResearchRunner
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.ai.OLLAMA_URL
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.ai.OllamaFake
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

val CODEX_T0: Long = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()

/** A scripted Codex: records every question; [handler] makes the answer (or throws). */
class FakeCodexRunner : ResearchRunner {
    val asks = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var handler: suspend (String, String) -> ResearchRow = { category, question -> nutrition(category, question) }

    override suspend fun ask(category: String, question: String): ResearchRow {
        asks += category to question
        return handler(category, question)
    }

    companion object {
        /** An answer for 100 g of food: 130 kcal, 2.7 g protein, 28 g carbs, 0.3 g fat. */
        fun nutrition(category: String, question: String, kcal: Double = 130.0): ResearchRow = ResearchRow(
            category, question, "Per 100 g: $kcal kcal",
            """{"kcal":$kcal,"proteinG":2.7,"carbsG":28.0,"fatG":0.3,"sources":["https://example.org/rice"]}""",
            listOf("https://example.org/rice"),
        )

        fun definition(question: String): ResearchRow = ResearchRow(
            "DEFINITION", question, "A definition.", """{"definition":"A definition.","sources":[]}""", emptyList(),
        )
    }
}

/** A database, the writer with a movable clock, a model fake on the HP address and the generation lock. */
class CodexRig {
    var now: Long = CODEX_T0
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb)
    val writer = ServerWriter(sdb, store) { now }
    val jobs = ServerJobs(sdb, writer)
    val lock = Mutex()
    val ollama = OllamaFake()
    val brain = Brain(null, OLLAMA_URL, "hp-model", engine = MockEngine { respond("{}", HttpStatusCode.OK) })
    val runner = FakeCodexRunner()
    val codex = ServerCodex(sdb, writer, runner, jobs) { now }
    val meals = ServerMealLookup(sdb, writer, codex.runner, brain, lock, { OllamaClient(it, ollama.engine()) }, jobs) { now }

    suspend fun seed(row: SyncRow) {
        sdb.writeLock.withLock { sdb.db.transaction { store.writeIfNewer(row) } }
    }

    suspend fun seedJob(id: String, kind: String, input: String, status: String = "QUEUED", createdAt: Long = now, attempts: Long = 0, startedAt: Long? = null) =
        seed(toSyncRow(Ai_job(id, 1000, 0, 0, "SYNCED", kind, input, status, null, null, attempts, createdAt, startedAt)))

    suspend fun seedMeal(id: String, text: String) =
        seed(toSyncRow(Meal(id, 1000, 0, 0, "SYNCED", "LUNCH", text, now, "2026-11-11", null, null, null, null, null)))

    suspend fun seedApproval(id: String, tool: String, argsJson: String, status: String = "PENDING", createdAt: Long = now) =
        seed(toSyncRow(Approval(id, 1000, 0, 0, "SYNCED", tool, argsJson, "summary", status, createdAt, null)))

    fun job(id: String): Ai_job? = sdb.db.ai_jobQueries.selectById(id).executeAsOneOrNull()
    fun approval(id: String): Approval? = sdb.db.approvalQueries.selectById(id).executeAsOneOrNull()
    fun meal(id: String): Meal? = sdb.db.mealQueries.selectById(id).executeAsOneOrNull()
    fun researchRows() = sdb.db.researchQueries.selectAll().executeAsList()

    /** The model's answer to the meal split: these foods as name to grams. */
    fun splitInto(vararg foods: Pair<String, Number>) {
        val json = foods.joinToString(",", prefix = """{"foods":[""", postfix = "]}") { (n, g) -> """{"name":"$n","grams":$g}""" }
        ollama.reply = { 200 to chatBody(json) }
    }

    fun close() {
        sdb.close()
    }
}
