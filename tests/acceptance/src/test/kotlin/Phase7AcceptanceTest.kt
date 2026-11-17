import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.CHAT_HISTORY_MAX_CHARS
import dev.tim.assistant.ai.CHAT_MODEL
import dev.tim.assistant.ai.CHAT_NUM_CTX
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.ai.ChatEvent
import dev.tim.assistant.ai.ChatMessage
import dev.tim.assistant.ai.XPS_OFFLINE_TEXT
import dev.tim.assistant.ai.chatErrorText
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.ai.BRIEFING_UNAVAILABLE
import dev.tim.assistant.server.ai.BriefingOutcome
import dev.tim.assistant.server.ai.BriefingService
import dev.tim.assistant.server.ai.Estimate
import dev.tim.assistant.server.ai.NUTRITION_MAX_FAILURES
import dev.tim.assistant.server.ai.NUTRITION_PER_RUN
import dev.tim.assistant.server.ai.NutritionEstimator
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.ai.OllamaException
import dev.tim.assistant.server.ai.parseEstimate
import dev.tim.assistant.server.google.GoogleStatus
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val P7_TODAY = "2026-01-15"
private const val P7_YESTERDAY = "2026-01-14"
private val P7_SEVEN_AM: Long = Instant.parse("2026-01-15T05:00:00Z").toEpochMilliseconds()
private val P7_SIX_59: Long = Instant.parse("2026-01-15T04:59:00Z").toEpochMilliseconds()
private const val P7_TOKEN = "tok-laptop"

private fun p7Obj(vararg pairs: Pair<String, Any?>) = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> error("bad")
        }
    },
)

private fun p7ChatBody(content: String) = buildJsonObject {
    put("model", "m")
    put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
    put("done", true)
}.toString()

private const val GOOD = """{"kcal":520,"proteinG":30,"carbsG":60,"fatG":15}"""

private class P7Clock(var nowMs: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
}

private class P7Fake {
    val bodies = CopyOnWriteArrayList<String>()
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    @Volatile var hold = false
    @Volatile var reply: () -> Pair<Int, String> = { 200 to p7ChatBody("Good morning, Tim.") }
    fun engine() = MockEngine { request ->
        bodies += String(request.body.toByteArray(), Charsets.UTF_8)
        started.complete(Unit)
        if (hold) gate.await()
        val (code, text) = reply()
        respond(text, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

private class P7Rig(startMs: Long = P7_SEVEN_AM, val fake: P7Fake = P7Fake(), yieldWhen: () -> Boolean = { false }) {
    val clock = P7Clock(startMs)
    val sdb: ServerDb = openDb(Files.createTempDirectory("p7acc").resolve("t.db").toString())
    val store = SyncStore(sdb)
    val ollama = OllamaClient("http://ollama.test", fake.engine())
    val lock = Mutex()
    val briefing = BriefingService(sdb, store, ollama, "test-model", lock, clock)
    val nutrition = NutritionEstimator(sdb, store, ollama, "test-model", lock, yieldWhen)

    fun briefingText(date: String = P7_TODAY): String? =
        sdb.db.briefingQueries.selectById("briefing:$date").executeAsOneOrNull()?.text

    fun meal(id: String) = assertNotNull(sdb.db.mealQueries.selectById(id).executeAsOneOrNull())
    fun seq(): String? = sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()
    fun close() { ollama.close(); sdb.close() }
}

private fun p7Meal(id: String, updatedAt: Long = 100, eatenAt: Long = 1_700_000_000_000L, text: String = "pasta", kcal: Long? = null, deleted: Boolean = false) =
    SyncRow(
        "meal", id, updatedAt, deleted,
        p7Obj(
            "type" to "MEAL", "text" to text, "eatenAt" to eatenAt, "localDate" to "2023-11-15",
            "kcal" to kcal, "proteinG" to null, "carbsG" to null, "fatG" to null, "estimateModel" to null,
        ),
    )

private fun jlm(date: String, h: Int, m: Int = 0) =
    LocalDate.parse(date).atTime(h, m).atZone(ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()

private fun p7Event(id: String, title: String, start: Long, end: Long) = SyncRow(
    "cal_event", "cal:primary:$id", 1000, false,
    p7Obj("calendarId" to "primary", "eventId" to id, "title" to title, "startAt" to start, "endAt" to end, "allDay" to false, "location" to null),
)

private fun p7Checkin(date: String, mood: Int) = SyncRow(
    "checkin", "checkin:$date", 1000, false,
    p7Obj("localDate" to date, "mood" to mood, "energy" to 3, "sleepQuality" to 2, "stress" to 1, "note" to null),
)

class Phase7AcceptanceTest {
    // ================= AC-1: briefing =================

    @Test
    fun test_AC1_gathered_text_has_todays_event_and_yesterdays_mood() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.sdb.db.cal_eventQueries.upsert(dev.tim.assistant.db.Cal_event("cal:primary:e1", 1000, 0, 1, "SYNCED", "primary", "e1", "Dentist Appointment", jlm(P7_TODAY, 9), jlm(P7_TODAY, 10), 0, null))
            rig.store.push(listOf(p7Checkin(P7_YESTERDAY, 5)))
            val text = rig.briefing.gather(P7_TODAY)
            assertTrue(text.contains("Dentist Appointment"), text)
            assertTrue(Regex("(?i)mood\\D{0,4}5").containsMatchIn(text), text)
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_ollama_error_stores_fallback_text() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.reply = { 500 to "{}" }
            val out = rig.briefing.regenerateNow()
            assertTrue(out is BriefingOutcome.Done)
            assertEquals(BRIEFING_UNAVAILABLE, (out as BriefingOutcome.Done).text)
            assertEquals("Briefing unavailable today", rig.briefingText())
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_no_second_generation_while_one_runs() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.hold = true
            val first = async(kotlinx.coroutines.Dispatchers.Default) { rig.briefing.regenerateNow() }
            withTimeout(10_000) { rig.fake.started.await() }
            assertEquals(BriefingOutcome.Busy, rig.briefing.regenerateNow())
            assertFalse(rig.briefing.tick())
            assertEquals(1, rig.fake.bodies.size)
            rig.fake.gate.complete(Unit)
            assertTrue(withTimeout(10_000) { first.await() } is BriefingOutcome.Done)
            assertEquals(1, rig.fake.bodies.size)
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_fixed_clock_0459_utc_nothing_0500_utc_generated_in_any_jvm_zone() {
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Pacific/Kiritimati", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                runBlocking {
                    val rig = P7Rig(startMs = P7_SIX_59)
                    try {
                        assertFalse(rig.briefing.isDue(), zone)
                        assertFalse(rig.briefing.tick(), zone)
                        assertNull(rig.briefingText(), zone)
                        assertEquals(0, rig.fake.bodies.size)
                        rig.clock.nowMs = P7_SEVEN_AM
                        assertTrue(rig.briefing.isDue(), zone)
                        assertTrue(rig.briefing.tick(), zone)
                        assertEquals("Good morning, Tim.", rig.briefingText(), zone)
                        assertFalse(rig.briefing.isDue(), zone)
                        assertFalse(rig.briefing.tick(), zone)
                    } finally { rig.close() }
                }
            }
        } finally { TimeZone.setDefault(saved) }
    }

    @Test
    fun test_AC1_startup_after_0700_catches_up_and_fallback_is_not_retried() = runBlocking<Unit> {
        val rig = P7Rig(startMs = Instant.parse("2026-01-15T11:00:00Z").toEpochMilliseconds())
        try {
            rig.fake.reply = { 500 to "{}" }
            assertTrue(rig.briefing.tick())
            assertEquals(BRIEFING_UNAVAILABLE, rig.briefingText())
            rig.fake.reply = { 200 to p7ChatBody("fine") }
            assertFalse(rig.briefing.tick())
            assertEquals(1, rig.fake.bodies.size)
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_regenerate_replaces_todays_row_with_higher_updatedAt() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.briefing.regenerateNow()
            val first = rig.sdb.db.briefingQueries.selectById("briefing:$P7_TODAY").executeAsOne()
            rig.fake.reply = { 200 to p7ChatBody("second") }
            rig.briefing.regenerateNow() // same clock millisecond on purpose
            val second = rig.sdb.db.briefingQueries.selectById("briefing:$P7_TODAY").executeAsOne()
            assertEquals("second", second.text)
            assertTrue(second.updatedAt > first.updatedAt)
            assertTrue(second.serverSeq > first.serverSeq)
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_ollama_client_returns_content_and_throws_on_non_2xx() = runBlocking<Unit> {
        val fake = P7Fake()
        OllamaClient("http://ollama.test", fake.engine()).use { c ->
            assertEquals("Good morning, Tim.", c.chat("m", "sys", "user", 4096))
            val req = Json.parseToJsonElement(fake.bodies[0]).jsonObject
            assertEquals("m", req["model"]!!.jsonPrimitive.content)
            assertEquals("false", req["stream"]!!.jsonPrimitive.content)
            assertEquals("false", req["think"]!!.jsonPrimitive.content)
            assertEquals("4096", req["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.content)
            fake.reply = { 503 to "{}" }
            val failed = runCatching { c.chat("m", "s", "u", 2048) }.exceptionOrNull()
            assertTrue(failed is OllamaException, "got $failed")
            fake.reply = { 200 to "not json" }
            assertTrue(runCatching { c.chat("m", "s", "u", 2048) }.exceptionOrNull() is OllamaException)
        }
    }

    // ---- admin route (AC-1 409, AC-3 shape) ----

    private fun cfg(withOllama: Boolean) = Config(
        bindAddress = "127.0.0.1", dbPath = "x", tokens = mapOf("laptop" to P7_TOKEN),
        ollama = if (withOllama) dev.tim.assistant.server.OllamaConfig("http://ollama.test", "m") else null,
    )

    @Test
    fun test_AC3_admin_briefing_200_json_with_text() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            testApplication {
                application { module(cfg(true), rig.store, { rig.clock.nowMs }, GoogleStatus(), rig.briefing) }
                val r = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $P7_TOKEN") }
                assertEquals(HttpStatusCode.OK, r.status)
                val j = Json.parseToJsonElement(r.bodyAsText()).jsonObject
                assertEquals("Good morning, Tim.", j["text"]!!.jsonPrimitive.content)
                assertEquals(P7_TODAY, j["localDate"]!!.jsonPrimitive.content)
            }
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_admin_briefing_409_during_a_run() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.hold = true
            val running = async(kotlinx.coroutines.Dispatchers.Default) { rig.briefing.regenerateNow() }
            withTimeout(10_000) { rig.fake.started.await() }
            testApplication {
                application { module(cfg(true), rig.store, { rig.clock.nowMs }, GoogleStatus(), rig.briefing) }
                val r = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $P7_TOKEN") }
                assertEquals(HttpStatusCode.Conflict, r.status)
                assertEquals("briefing already running", Json.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
            }
            rig.fake.gate.complete(Unit)
            running.await()
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_admin_briefing_409_while_a_nutrition_run_holds_the_lock() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.store.push(listOf(p7Meal("m1")))
            rig.fake.hold = true
            rig.fake.reply = { 200 to p7ChatBody(GOOD) }
            val running = async(kotlinx.coroutines.Dispatchers.Default) { rig.nutrition.run() }
            withTimeout(10_000) { rig.fake.started.await() }
            testApplication {
                application { module(cfg(true), rig.store, { rig.clock.nowMs }, GoogleStatus(), rig.briefing) }
                val r = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $P7_TOKEN") }
                assertEquals(HttpStatusCode.Conflict, r.status)
            }
            rig.fake.gate.complete(Unit)
            assertEquals(1, running.await())
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_admin_briefing_503_without_ollama_and_health_still_works() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            testApplication {
                application { module(cfg(false), rig.store, { rig.clock.nowMs }, GoogleStatus(), null) }
                val r = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $P7_TOKEN") }
                assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
                assertEquals("ollama not configured", Json.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
                val h = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $P7_TOKEN") }
                assertEquals(HttpStatusCode.OK, h.status)
            }
        } finally { rig.close() }
    }

    @Test
    fun test_AC1_admin_briefing_requires_bearer_auth() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            testApplication {
                application { module(cfg(true), rig.store, { rig.clock.nowMs }, GoogleStatus(), rig.briefing) }
                assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/briefing").status)
            }
            assertEquals(0, rig.fake.bodies.size)
        } finally { rig.close() }
    }

    // ================= AC-8: nutrition =================

    @Test
    fun test_AC8_valid_reply_fills_four_fields_and_model_keeps_updatedAt_bumps_seq() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.reply = { 200 to p7ChatBody(GOOD) }
            rig.store.push(listOf(p7Meal("m1", text = "two eggs and toast")))
            val before = rig.meal("m1")
            assertEquals(1, rig.nutrition.run())
            val m = rig.meal("m1")
            assertEquals(520L, m.kcal); assertEquals(30L, m.proteinG); assertEquals(60L, m.carbsG); assertEquals(15L, m.fatG)
            assertEquals("test-model", m.estimateModel)
            assertEquals(before.updatedAt, m.updatedAt)
            assertEquals("two eggs and toast", m.text)
            assertTrue(m.serverSeq > before.serverSeq)
            assertTrue(rig.fake.bodies[0].contains("two eggs and toast"))
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_invalid_replies_leave_meal_without_estimate() = runBlocking<Unit> {
        val bad = listOf(
            "not json",
            """{"kcal":-5,"proteinG":1,"carbsG":1,"fatG":1}""",
            """{"kcal":6000,"proteinG":1,"carbsG":1,"fatG":1}""",
            """{"kcal":"500","proteinG":1,"carbsG":1,"fatG":1}""",
            """{"kcal":500,"proteinG":1,"carbsG":1}""",
        )
        for (b in bad) {
            val rig = P7Rig()
            try {
                rig.fake.reply = { 200 to p7ChatBody(b) }
                rig.store.push(listOf(p7Meal("m1")))
                val seq = rig.seq()
                assertEquals(0, rig.nutrition.run(), b)
                assertNull(rig.meal("m1").kcal, b)
                assertEquals(seq, rig.seq(), b)
            } finally { rig.close() }
        }
    }

    @Test
    fun test_AC8_parseEstimate_accepts_valid_and_rejects_invalid() {
        assertEquals(Estimate(520, 30, 60, 15), parseEstimate(GOOD))
        assertEquals(Estimate(5000, 0, 0, 0), parseEstimate("""{"kcal":5000,"proteinG":0,"carbsG":0,"fatG":0}"""))
        assertNull(parseEstimate("""{"kcal":5001,"proteinG":0,"carbsG":0,"fatG":0}"""))
        assertNull(parseEstimate("""{"kcal":-1,"proteinG":0,"carbsG":0,"fatG":0}"""))
        assertNull(parseEstimate("hello"))
    }

    @Test
    fun test_AC8_meal_edited_during_the_call_is_not_overwritten() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.reply = { 200 to p7ChatBody(GOOD) }
            rig.store.push(listOf(p7Meal("m1", updatedAt = 100, text = "old")))
            rig.fake.hold = true
            val run = async(kotlinx.coroutines.Dispatchers.Default) { rig.nutrition.run() }
            withTimeout(10_000) { rig.fake.started.await() }
            rig.store.push(listOf(p7Meal("m1", updatedAt = 200, text = "new text")))
            val seqAfterEdit = rig.seq()
            rig.fake.gate.complete(Unit)
            assertEquals(0, run.await())
            val m = rig.meal("m1")
            assertNull(m.kcal)
            assertEquals("new text", m.text)
            assertEquals(200L, m.updatedAt)
            assertEquals(seqAfterEdit, rig.seq())
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_later_push_wins_and_null_nutrition_is_estimated_again() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.reply = { 200 to p7ChatBody(GOOD) }
            rig.store.push(listOf(p7Meal("m1", updatedAt = 100)))
            rig.nutrition.run()
            assertEquals(520L, rig.meal("m1").kcal)
            rig.store.push(listOf(p7Meal("m1", updatedAt = 200, text = "salad")))
            assertNull(rig.meal("m1").kcal)
            rig.fake.reply = { 200 to p7ChatBody("""{"kcal":150,"proteinG":3,"carbsG":10,"fatG":9}""") }
            assertEquals(1, rig.nutrition.run())
            assertEquals(150L, rig.meal("m1").kcal)
            assertEquals(200L, rig.meal("m1").updatedAt)
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_same_updatedAt_repush_is_accepted_without_a_write_estimate_kept() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.reply = { 200 to p7ChatBody(GOOD) }
            rig.store.push(listOf(p7Meal("m1", updatedAt = 100)))
            rig.nutrition.run()
            val estimated = rig.meal("m1")
            val seq = rig.seq()
            val res = rig.store.push(listOf(p7Meal("m1", updatedAt = 100)))
            assertEquals(listOf("m1"), res.accepted)
            val after = rig.meal("m1")
            assertEquals(520L, after.kcal)
            assertEquals(estimated.serverSeq, after.serverSeq)
            assertEquals(seq, rig.seq())
            val pulled = rig.store.pull(0).rows.single { it.id == "m1" }
            assertEquals("520", pulled.data["kcal"]!!.jsonPrimitive.content)
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_max_five_per_run() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.fake.reply = { 200 to p7ChatBody(GOOD) }
            assertEquals(5, NUTRITION_PER_RUN)
            rig.store.push((1..8).map { p7Meal("m$it", eatenAt = 1_700_000_000_000L + it) })
            assertEquals(5, rig.nutrition.run())
            assertEquals(5, rig.fake.bodies.size)
            val left = (1..8).count { rig.meal("m$it").kcal == null }
            assertEquals(3, left)
            assertEquals(3, rig.nutrition.run())
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_nothing_happens_while_generation_lock_is_held() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.store.push(listOf(p7Meal("m1")))
            rig.lock.withLock {
                assertEquals(0, rig.nutrition.run())
            }
            assertEquals(0, rig.fake.bodies.size)
            assertNull(rig.meal("m1").kcal)
            assertFalse(rig.lock.isLocked)
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_meal_failed_three_times_is_skipped_and_sixth_meal_is_estimated() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            assertEquals(3, NUTRITION_MAX_FAILURES)
            rig.fake.reply = { 200 to p7ChatBody("garbage") }
            rig.store.push((1..5).map { p7Meal("f$it", eatenAt = 1_000L + it) })
            repeat(NUTRITION_MAX_FAILURES) { assertEquals(0, rig.nutrition.run()) }
            assertEquals(15, rig.fake.bodies.size)
            rig.store.push(listOf(p7Meal("sixth", eatenAt = 9_000L)))
            rig.fake.reply = { 200 to p7ChatBody(GOOD) }
            assertEquals(1, rig.nutrition.run())
            assertEquals(520L, rig.meal("sixth").kcal)
            for (i in 1..5) assertNull(rig.meal("f$i").kcal)
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_deleted_meal_is_not_estimated() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.store.push(listOf(p7Meal("gone", deleted = true)))
            assertEquals(0, rig.nutrition.run())
            assertEquals(0, rig.fake.bodies.size)
        } finally { rig.close() }
    }

    @Test
    fun test_AC8_writeEstimate_guards_and_counter() = runBlocking<Unit> {
        val rig = P7Rig()
        try {
            rig.store.push(listOf(p7Meal("m1", updatedAt = 100)))
            val e = Estimate(100, 1, 2, 3)
            val seq0 = rig.seq()
            val stale = rig.sdb.writeLock.withLock { rig.sdb.db.transactionWithResult { rig.store.writeEstimate("m1", 99, e, "x") } }
            val missing = rig.sdb.writeLock.withLock { rig.sdb.db.transactionWithResult { rig.store.writeEstimate("nope", 100, e, "x") } }
            assertFalse(stale); assertFalse(missing)
            assertEquals(seq0, rig.seq())
            val ok = rig.sdb.writeLock.withLock { rig.sdb.db.transactionWithResult { rig.store.writeEstimate("m1", 100, e, "x") } }
            assertTrue(ok)
            assertNotEquals(seq0, rig.seq())
            val seq1 = rig.seq()
            val again = rig.sdb.writeLock.withLock { rig.sdb.db.transactionWithResult { rig.store.writeEstimate("m1", 100, e, "x") } }
            assertFalse(again)
            assertEquals(seq1, rig.seq())
        } finally { rig.close() }
    }

    // ================= AC-2: chat =================

    private fun memDb() = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })

    private fun journal(db: AssistantDb, id: String, text: String, createdAt: Long, deleted: Long = 0) =
        db.journalQueries.upsert(dev.tim.assistant.db.Journal(id, 1, deleted, 0, "SYNCED", text, createdAt))

    @Test
    fun test_AC2_chat_context_respects_the_length_cap() {
        val db = memDb()
        val base = 1_768_000_000_000L // 2026-01-09
        for (i in 0 until 200) journal(db, "j$i", "entry $i " + "x".repeat(400), base + i * 1000L)
        for (i in 0 until 100) {
            db.spendingQueries.upsert(
                dev.tim.assistant.db.Spending("s$i", 1, 0, 0, "SYNCED", 1250, "Food", "note ".repeat(30), "2026-01-%02d".format((i % 14) + 1)),
            )
        }
        val ctx = ChatContext.build(db, P7_TODAY)
        assertTrue(ctx.length <= ChatContext.MAX_CHARS, "len ${ctx.length}")
        assertEquals(12_000, ChatContext.MAX_CHARS)
        assertTrue(ctx.isNotEmpty())
        assertTrue(ChatContext.systemPrompt(ctx).contains(ctx))
    }

    @Test
    fun test_AC2_chat_context_excludes_deleted_rows_and_keeps_hebrew() {
        val db = memDb()
        val now = 1_768_400_000_000L
        journal(db, "j1", "שלום עולם, היום היה יום טוב", now)
        journal(db, "j2", "SECRETDELETEDTEXT", now, deleted = 1)
        db.checkinQueries.upsert(dev.tim.assistant.db.Checkin("checkin:$P7_TODAY", 1, 0, 0, "SYNCED", P7_TODAY, 4, 3, 2, 1, null))
        db.checkinQueries.upsert(dev.tim.assistant.db.Checkin("checkin:2026-01-10", 1, 1, 0, "SYNCED", "2026-01-10", 1, 1, 1, 1, "DELETEDNOTE"))
        db.taskQueries.upsert(dev.tim.assistant.db.Task("t1", 1, 0, 0, "SYNCED", "לקנות חלב", null, 0, null, null))
        db.taskQueries.upsert(dev.tim.assistant.db.Task("t2", 1, 1, 0, "SYNCED", "DELETEDTASK", null, 0, null, null))
        val ctx = ChatContext.build(db, P7_TODAY)
        assertTrue(ctx.contains("שלום עולם"), ctx)
        assertTrue(ctx.contains("לקנות חלב"), ctx)
        assertFalse(ctx.contains("SECRETDELETEDTEXT"))
        assertFalse(ctx.contains("DELETEDNOTE"))
        assertFalse(ctx.contains("DELETEDTASK"))
    }

    @Test
    fun test_AC2_chat_context_lists_meals_with_estimate() {
        val db = memDb()
        db.mealQueries.upsert(
            dev.tim.assistant.db.Meal("mm1", 1, 0, 0, "SYNCED", "MEAL", "grilled chicken", 1_768_470_000_000L, P7_TODAY, 520, 30, 60, 15, "m"),
        )
        val ctx = ChatContext.build(db, P7_TODAY)
        assertTrue(ctx.contains("grilled chicken"), ctx)
        assertTrue(ctx.contains("≈"), ctx)
        assertTrue(ctx.contains("520"), ctx)
    }

    private fun ndjson(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    private fun chat(engine: MockEngine) = runBlocking { ChatClient(engine).send("http://xps:11434", listOf(ChatMessage("user", "hi"))).toList() }

    private fun okEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK, sink: MutableList<String>? = null) = MockEngine { req ->
        sink?.add(req.url.toString() + "\n" + String(req.body.toByteArray(), Charsets.UTF_8))
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
    }

    @Test
    fun test_AC2_streaming_chunks_joined_and_done_carries_prompt_eval_count() {
        val sink = ArrayList<String>()
        val events = chat(
            okEngine(
                ndjson(
                    """{"message":{"role":"assistant","content":"Hel"},"done":false}""",
                    """{"message":{"role":"assistant","content":"lo "},"done":false}""",
                    """{"message":{"role":"assistant","content":"שלום"},"done":false}""",
                    """{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":321}""",
                ),
                sink = sink,
            ),
        )
        assertEquals("Hello שלום", events.filterIsInstance<ChatEvent.Chunk>().joinToString("") { it.text })
        assertEquals(ChatEvent.Done(321), events.last())
        assertEquals(1, events.filterIsInstance<ChatEvent.Done>().size)
        assertTrue(sink[0].startsWith("http://xps:11434/api/chat"), sink[0])
        val req = Json.parseToJsonElement(sink[0].substringAfter("\n")).jsonObject
        assertEquals(CHAT_MODEL, req["model"]!!.jsonPrimitive.content)
        assertEquals("true", req["stream"]!!.jsonPrimitive.content)
        assertEquals("false", req["think"]!!.jsonPrimitive.content)
        assertEquals(CHAT_NUM_CTX.toString(), req["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.content)
    }

    @Test
    fun test_AC2_final_chunk_content_is_not_lost() {
        val events = chat(
            okEngine(ndjson("""{"message":{"role":"assistant","content":"end"},"done":true,"prompt_eval_count":5}""")),
        )
        assertEquals(listOf<ChatEvent>(ChatEvent.Chunk("end"), ChatEvent.Done(5)), events)
    }

    @Test
    fun test_AC2_connect_failure_gives_xps_offline() {
        val events = chat(MockEngine { throw ConnectException("refused") })
        assertEquals(listOf<ChatEvent>(ChatEvent.XpsOffline), events)
    }

    @Test
    fun test_AC2_http_404_gives_chat_error_404() {
        val events = chat(okEngine("""{"error":"model not found"}""", HttpStatusCode.NotFound))
        assertEquals(listOf<ChatEvent>(ChatEvent.ChatError(404)), events)
    }

    @Test
    fun test_AC2_eof_before_done_and_error_chunk_and_bad_json_give_error_0() {
        val eof = chat(okEngine(ndjson("""{"message":{"role":"assistant","content":"par"},"done":false}""")))
        assertEquals(ChatEvent.ChatError(0), eof.last())
        assertFalse(eof.any { it is ChatEvent.Done })
        val errChunk = chat(okEngine(ndjson("""{"error":"boom"}""")))
        assertEquals(ChatEvent.ChatError(0), errChunk.last())
        val bad = chat(okEngine(ndjson("this is not json")))
        assertEquals(ChatEvent.ChatError(0), bad.last())
    }

    @Test
    fun test_AC2_texts_and_constants() {
        assertEquals("Chat error: 404", chatErrorText(404))
        assertEquals("XPS is offline — turn it on to chat", XPS_OFFLINE_TEXT)
        assertEquals("qwen3.5:9b", CHAT_MODEL)
        assertEquals(16384, CHAT_NUM_CTX)
        assertEquals(2_000, CHAT_HISTORY_MAX_CHARS)
    }

    @Test
    fun test_AC2_xpsOllamaUrl_defaults_and_trims() {
        val d = "http://xxxxxxxx:11434"
        assertEquals(d, dev.tim.assistant.ai.DEFAULT_XPS_OLLAMA_URL)
        assertEquals(d, dev.tim.assistant.ai.xpsOllamaUrl(null))
        assertEquals(d, dev.tim.assistant.ai.xpsOllamaUrl(""))
        assertEquals(d, dev.tim.assistant.ai.xpsOllamaUrl("  "))
        assertEquals("http://x:11434", dev.tim.assistant.ai.xpsOllamaUrl("http://x:11434 "))
    }

    @Test
    fun test_P7B17_docs_wording() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "ops/checklists/phase6.md").exists() }
        val p6 = File(root, "ops/checklists/phase6.md").readText()
        assertFalse(p6.contains("Empty the field", ignoreCase = true))
        assertTrue(p6.contains("http://xxxxxxxx:11434"))
        val guide = File(root, "ops/ollama-xps.md").readText()
        assertTrue(guide.contains("automatically", ignoreCase = true))
    }

    @Test
    fun test_AC2_briefingFor_returns_row_and_hides_missing_or_deleted() {
        val db = memDb()
        val repo = Repository(LocalStore(db), now = { 1_000L }, today = { P7_TODAY })
        assertNull(repo.briefingFor(P7_TODAY))
        db.briefingQueries.upsert(dev.tim.assistant.db.Briefing("briefing:$P7_TODAY", 1, 0, 1, "SYNCED", P7_TODAY, "Good morning", "m"))
        assertEquals("Good morning", repo.briefingFor(P7_TODAY)?.text)
        db.briefingQueries.upsert(dev.tim.assistant.db.Briefing("briefing:2026-01-14", 1, 1, 2, "SYNCED", "2026-01-14", "old", "m"))
        assertNull(repo.briefingFor("2026-01-14"))
    }

    // ================= AC-4..AC-7, AC-9: guides and checklists exist =================

    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return assertNotNull(d)
    }

    private fun text(rel: String): String {
        val f = File(root(), rel)
        assertTrue(f.exists(), "$rel missing")
        return f.readText()
    }

    @Test
    fun test_AC4_hp_guide_has_results_fields() {
        val t = text("ops/ollama-hp.md")
        assertTrue(t.contains("Results"))
        for (k in listOf("version", "memory", "tokens")) assertTrue(t.contains(k, ignoreCase = true), k)
        assertTrue(t.contains("300"))
        assertTrue(t.contains("admin/briefing"))
    }

    @Test
    fun test_AC5_xps_guide_covers_host_firewall_and_checks() {
        val t = text("ops/ollama-xps.md")
        assertTrue(t.contains("OLLAMA_HOST"))
        assertTrue(t.contains("11434"))
        assertTrue(t.contains("firewall", ignoreCase = true))
        assertTrue(t.contains("Ollama is running"))
    }

    @Test
    fun test_AC6_phase6_checklist_lines() {
        val t = text("ops/checklists/phase6.md")
        assertTrue(t.contains("16384") || t.contains("16 384"))
        assertTrue(t.contains("prompt tokens", ignoreCase = true) || t.contains("prompt_eval_count"))
        assertTrue(t.contains("5 s") || t.contains("5 seconds") || t.contains("5s"))
        assertTrue(t.contains("7:30"))
    }

    @Test
    fun test_AC7_phase1_e2e_names_1_5_0() {
        val t = text("ops/checklists/phase1-e2e.md")
        assertTrue(t.contains("Wally-1.18.0.msi"))
        assertFalse(t.contains("Wally-1.4.0"))
        assertFalse(text("ops/checklists/phase4.md").contains("Wally-1.4.0"))
    }

    @Test
    fun test_AC9_nutrition_checklist_lines() {
        val t = text("ops/checklists/nutrition.md")
        assertTrue(t.contains("≈"))
        assertTrue(t.contains("20"))
        assertTrue(t.contains("30 min"))
        assertTrue(t.contains("yesterday", ignoreCase = true))
    }

    // ================= AC-10: still named Wally =================

    @Test
    fun test_AC10_app_is_still_named_wally_version_1_5_0() {
        val g = text("desktopApp/build.gradle.kts")
        assertTrue(g.contains("packageName = \"Wally\""))
        assertTrue(g.contains("packageVersion = \"1.18.0\""))
        assertTrue(g.contains("upgradeUuid"))
        val strings = File(root(), "androidApp").walkTopDown().filter { it.name == "strings.xml" && !it.path.contains("build") }.toList()
        assertTrue(strings.isNotEmpty())
        assertTrue(strings.any { it.readText().contains("<string name=\"app_name\">Wally</string>") })
    }

    @Test
    fun test_AC10_no_wallylogger_anywhere() {
        for (dir in listOf("shared", "server", "desktopApp", "androidApp", "ops")) {
            File(root(), dir).walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && it.extension in setOf("kt", "kts", "xml", "md", "ps1", "json", "properties", "sq") }
                .forEach { assertFalse(it.readText().contains("WallyLogger"), "WallyLogger in ${it.path}") }
        }
    }

    @Test
    fun test_P7A08_add_target_exists_only_on_today_and_the_log_chips() {
        // Phase 15: addTargetFor became addActionFor(chip) (null = no + button); Goals, Files, Study and Mail got their own actions.
        val withTarget = setOf(
            dev.tim.assistant.ui.NavTab.Today, dev.tim.assistant.ui.NavTab.Tasks, dev.tim.assistant.ui.NavTab.Checkins,
            dev.tim.assistant.ui.NavTab.Spending, dev.tim.assistant.ui.NavTab.Journal, dev.tim.assistant.ui.NavTab.Meals,
            dev.tim.assistant.ui.NavTab.Dreams, dev.tim.assistant.ui.NavTab.Goals, dev.tim.assistant.ui.NavTab.Files,
            dev.tim.assistant.ui.NavTab.Study, dev.tim.assistant.ui.NavTab.Mail,
        )
        for (tab in dev.tim.assistant.ui.NavTab.entries) {
            assertEquals(tab in withTarget, dev.tim.assistant.ui.addActionFor(tab) != null, "tab $tab")
        }
        assertNull(dev.tim.assistant.ui.addActionFor(dev.tim.assistant.ui.NavTab.Chat))
    }

    @Test
    fun test_AC4_hp_results_filled() {
        val t = text("ops/ollama-hp.md")
        assertTrue(t.contains("0.40.14"))
        assertTrue(t.contains("1.4Gi"))
        assertTrue(t.contains("13.8"))
    }

    @Test
    fun test_AC5_xps_has_tailscale_access_rule_step_and_results() {
        val t = text("ops/ollama-xps.md")
        assertTrue(t.contains("autogroup:member"))
        assertTrue(t.contains("xxxxxxxx"))
        assertTrue(t.contains("tcp:11434"))
        assertTrue(t.contains("17.44"))
        assertTrue(t.contains("0.40.15"))
    }

    @Test
    fun test_AC6_phase6_checklist_has_plus_button_send_line() {
        val t = text("ops/checklists/phase6.md")
        assertTrue(t.contains("+ button") && t.contains("Send"))
        assertTrue(t.contains("does not cover Send"))
    }
}
