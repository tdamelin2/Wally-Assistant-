package dev.tim.assistant.server.ai

import dev.tim.assistant.db.Briefing
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.obj
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val OLLAMA_URL = "http://ollama.test"

/** 2026-01-15 05:00 UTC is 07:00 in Asia/Jerusalem (UTC+2 in winter). */
val SEVEN_AM: Long = Instant.parse("2026-01-15T05:00:00Z").toEpochMilliseconds()
const val TODAY = "2026-01-15"
const val YESTERDAY = "2026-01-14"

fun chatBody(content: String): String = buildJsonObject {
    put("model", "m")
    put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
    put("done", true)
}.toString()

/** A scripted Ollama: records request bodies; [reply] chooses the response; [hold] parks every call at [gate]. */
class OllamaFake {
    val bodies = CopyOnWriteArrayList<String>()
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    @Volatile var hold = false
    @Volatile var beforeReply: suspend (String) -> Unit = {}
    @Volatile var reply: (String) -> Pair<Int, String> = { 200 to chatBody("Good morning, Tim.") }

    fun engine() = MockEngine { request ->
        val body = String(request.body.toByteArray(), Charsets.UTF_8)
        bodies += body
        started.complete(Unit)
        if (hold) gate.await()
        beforeReply(body)
        val (status, text) = reply(body)
        respond(text, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }

    fun requestJson(index: Int = 0): JsonObject = dev.tim.assistant.server.testJson.parseToJsonElement(bodies[index]) as JsonObject
}

class MutableClock(var nowMs: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
}

/** A database, store, Ollama fake and the two AI services sharing one generationLock. */
class AiRig(startMs: Long = SEVEN_AM, val fake: OllamaFake = OllamaFake(), yieldWhen: () -> Boolean = { false }) {
    val clock = MutableClock(startMs)
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb)
    val ollama = OllamaClient(OLLAMA_URL, fake.engine())
    val lock = Mutex()
    val briefing = BriefingService(sdb, store, ollama, "test-model", lock, clock)
    val nutrition = NutritionEstimator(sdb, store, ollama, "test-model", lock, yieldWhen)

    fun briefingRow(date: String = TODAY): Briefing? = sdb.db.briefingQueries.selectById("briefing:$date").executeAsOneOrNull()
    fun seqCounter(): String? = sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()

    suspend fun seed(row: SyncRow) {
        sdb.writeLock.withLock { sdb.db.transaction { store.writeIfNewer(row) } }
    }

    suspend fun seedEvent(id: String, title: String, startAt: Long, endAt: Long, allDay: Boolean = false, location: String? = null) = seed(
        toSyncRow(Cal_event("cal:primary:$id", 1000, 0, 0, "SYNCED", "primary", id, title, startAt, endAt, if (allDay) 1 else 0, location)),
    )

    fun close() {
        ollama.close()
        sdb.close()
    }
}

fun jerusalemMs(date: String, hour: Int, minute: Int = 0): Long =
    LocalDate.parse(date).atTime(hour, minute).atZone(ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()

fun taskRow(id: String, title: String, dueDate: String?, done: Boolean = false) =
    SyncRow("task", id, 1000, false, obj("title" to title, "dueDate" to dueDate, "done" to done, "doneAt" to null))

fun healthRow(date: String, sleepMinutes: Long?, steps: Long?, restingHr: Long?) = SyncRow(
    "health_day", "health:$date", 1000, false,
    obj("localDate" to date, "sleepMinutes" to sleepMinutes, "steps" to steps, "restingHr" to restingHr),
)

fun spendingAt(id: String, date: String, agorot: Long) = SyncRow(
    "spending", id, 1000, false,
    obj("amountAgorot" to agorot, "category" to "Food", "note" to null, "localDate" to date),
)

fun checkin(date: String, mood: Int, updatedAt: Long = 1000) = SyncRow(
    "checkin", "checkin:$date", updatedAt, false,
    obj("localDate" to date, "mood" to mood, "energy" to 3, "sleepQuality" to 2, "stress" to 1, "note" to null),
)
