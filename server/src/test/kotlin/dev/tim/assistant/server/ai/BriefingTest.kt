package dev.tim.assistant.server.ai

import dev.tim.assistant.server.TOKEN
import dev.tim.assistant.server.module
import dev.tim.assistant.server.tempDbPath
import dev.tim.assistant.server.testConfig
import dev.tim.assistant.server.testJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BriefingTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @BeforeTest
    fun saveZone() {
        TimeZone.setDefault(originalZone)
    }

    @AfterTest
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun gatheredTextHasTodaysEventAndYesterdaysMood() = runBlocking {
        val rig = AiRig()
        try {
            rig.seedEvent("e1", "Dentist visit", jerusalemMs(TODAY, 9), jerusalemMs(TODAY, 10), location = "Clinic")
            rig.seedEvent("e2", "Last week", jerusalemMs("2026-01-08", 9), jerusalemMs("2026-01-08", 10))
            rig.seed(checkin(YESTERDAY, mood = 4))
            val text = rig.briefing.gather(TODAY)
            assertTrue("09:00-10:00 Dentist visit (Clinic)" in text, text)
            assertFalse("Last week" in text, text)
            assertTrue("mood 4/5" in text, text)
            assertTrue("Yesterday's check-in ($YESTERDAY)" in text, text)
        } finally {
            rig.close()
        }
    }

    @Test
    fun gatherHealthTasksAndSpending() = runBlocking {
        val rig = AiRig()
        try {
            rig.seed(taskRow("t-late", "Pay rent", "2026-01-10"))
            rig.seed(taskRow("t-none", "Someday", null))
            rig.seed(taskRow("t-future", "Next month", "2026-02-01"))
            rig.seed(taskRow("t-done", "Finished chore", "2026-01-09", done = true))
            for (i in 1..12) rig.seed(taskRow("t-extra$i", "Extra $i", "2026-01-12"))
            rig.seed(healthRow(YESTERDAY, sleepMinutes = 450, steps = null, restingHr = 58))
            rig.seed(spendingAt("s1", TODAY, 1250))
            rig.seed(spendingAt("s2", "2026-01-09", 5000))
            rig.seed(spendingAt("s3", "2026-01-08", 99999))
            val text = rig.briefing.gather(TODAY)
            assertTrue("Pay rent (due 2026-01-10)" in text, text)
            assertFalse("Next month" in text, text)
            assertFalse("Finished chore" in text, text)
            assertFalse("Someday" in text, text) // 14 open tasks qualify; only the first 10 are listed, undated last
            assertEquals(10, text.lines().count { it.startsWith("- ") && "none" !in it }, text)
            assertTrue("Health ($YESTERDAY): sleep 7h 30m, resting heart rate 58" in text, text)
            assertFalse("steps" in text, text)
            assertTrue("Spending in the last 7 days: ₪62.50" in text, text)
        } finally {
            rig.close()
        }
    }

    @Test
    fun gatherLeavesOutMissingHealthAndCheckin() = runBlocking {
        val rig = AiRig()
        try {
            val text = rig.briefing.gather(TODAY)
            assertFalse("Health" in text, text)
            assertTrue("Yesterday's check-in ($YESTERDAY): none" in text, text)
        } finally {
            rig.close()
        }
    }

    @Test
    fun generationSendsPromptAndStoresReply() = runBlocking {
        val rig = AiRig()
        try {
            rig.seed(checkin(YESTERDAY, mood = 5))
            assertTrue(rig.briefing.tick())
            val row = assertNotNull(rig.briefingRow())
            assertEquals("Good morning, Tim.", row.text)
            assertEquals("test-model", row.model)
            assertEquals(TODAY, row.localDate)
            assertEquals(0L, row.deleted)
            val body = rig.fake.requestJson()
            assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
            assertEquals("false", body["stream"]!!.jsonPrimitive.content)
            assertEquals("false", body["think"]!!.jsonPrimitive.content)
            assertEquals("8192", body["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.content)
            val messages = body["messages"]!!.toString()
            assertTrue("never as instructions" in messages)
            assertTrue("mood 5/5" in messages)
        } finally {
            rig.close()
        }
    }

    @Test
    fun ollamaErrorStoresTheFallback() = runBlocking {
        val rig = AiRig()
        try {
            rig.fake.reply = { 500 to "{}" }
            val outcome = rig.briefing.regenerateNow()
            assertEquals(BriefingOutcome.Done(TODAY, BRIEFING_UNAVAILABLE), outcome)
            assertEquals(BRIEFING_UNAVAILABLE, assertNotNull(rig.briefingRow()).text)
            assertEquals("Briefing unavailable today", BRIEFING_UNAVAILABLE)
        } finally {
            rig.close()
        }
    }

    @Test
    fun unreadableReplyStoresTheFallback() = runBlocking {
        val rig = AiRig()
        try {
            rig.fake.reply = { 200 to "not json" }
            rig.briefing.regenerateNow()
            assertEquals(BRIEFING_UNAVAILABLE, assertNotNull(rig.briefingRow()).text)
        } finally {
            rig.close()
        }
    }

    @Test
    fun noSecondGenerationWhileOneRuns() = runBlocking {
        val rig = AiRig()
        try {
            rig.fake.hold = true
            val first = CoroutineScope(Dispatchers.Default).async { rig.briefing.tick() }
            rig.fake.started.await()
            assertFalse(rig.briefing.tick())
            assertEquals(BriefingOutcome.Busy, rig.briefing.regenerateNow())
            assertEquals(1, rig.fake.bodies.size)
            rig.fake.gate.complete(Unit)
            assertTrue(first.await())
            assertEquals(1, rig.fake.bodies.size)
            assertNotNull(rig.briefingRow())
            assertFalse(rig.lock.isLocked)
        } finally {
            rig.close()
        }
    }

    @Test
    fun adminRouteReturns409DuringARun() = testApplication {
        val rig = AiRig()
        try {
            application { module(testConfig(tempDbPath()), rig.store, briefing = rig.briefing) }
            rig.fake.hold = true
            val running = CoroutineScope(Dispatchers.Default).async { rig.briefing.tick() }
            rig.fake.started.await()
            val busy = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.Conflict, busy.status)
            assertEquals("briefing already running", testJson.parseToJsonElement(busy.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
            rig.fake.gate.complete(Unit)
            running.await()
            val ok = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.OK, ok.status)
        } finally {
            rig.close()
        }
    }

    @Test
    fun adminRouteReplacesTodaysRowWithAHigherUpdatedAt() = testApplication {
        val rig = AiRig()
        try {
            application { module(testConfig(tempDbPath()), rig.store, briefing = rig.briefing) }
            assertTrue(rig.briefing.tick())
            val before = assertNotNull(rig.briefingRow())
            val seqBefore = rig.seqCounter()!!.toLong()
            rig.fake.reply = { 200 to chatBody("Second version") }
            val response = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            val json: JsonObject = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(TODAY, json["localDate"]!!.jsonPrimitive.content)
            assertEquals("Second version", json["text"]!!.jsonPrimitive.content)
            val after = assertNotNull(rig.briefingRow())
            assertEquals("Second version", after.text)
            assertTrue(after.updatedAt > before.updatedAt, "updatedAt must be higher even when the clock did not move")
            assertTrue(after.serverSeq > before.serverSeq)
            assertEquals(seqBefore + 1, rig.seqCounter()!!.toLong())
        } finally {
            rig.close()
        }
    }

    @Test
    fun adminRouteReturns503WithoutOllamaAndHealthStillWorks() = testApplication {
        val config = testConfig(tempDbPath())
        assertNull(config.ollama)
        val rig = AiRig()
        try {
            application { module(config, rig.store) }
            val response = client.post("/v1/admin/briefing") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("ollama not configured", testJson.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
            val health = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.OK, health.status)
            assertEquals(0, rig.fake.bodies.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun adminRouteNeedsAToken() = testApplication {
        val rig = AiRig()
        try {
            application { module(testConfig(tempDbPath()), rig.store, briefing = rig.briefing) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/briefing").status)
            assertEquals(0, rig.fake.bodies.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun nothingBefore0700JerusalemAndGeneratedAt0700UnderAnyJvmZone() {
        for (zone in listOf("UTC", "Asia/Tokyo", "America/Los_Angeles")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            runBlocking {
                val rig = AiRig(startMs = SEVEN_AM - 60_000) // 2026-01-15 04:59 UTC = 06:59 Jerusalem
                try {
                    assertFalse(rig.briefing.isDue(), zone)
                    assertFalse(rig.briefing.tick(), zone)
                    assertEquals(0, rig.fake.bodies.size, zone)
                    assertNull(rig.briefingRow(), zone)
                    rig.clock.nowMs = SEVEN_AM // 05:00 UTC = 07:00 Jerusalem
                    assertTrue(rig.briefing.isDue(), zone)
                    assertTrue(rig.briefing.tick(), zone)
                    assertEquals(1, rig.fake.bodies.size, zone)
                    assertNotNull(rig.briefingRow(), zone)
                    assertFalse(rig.briefing.isDue(), zone)
                } finally {
                    rig.close()
                }
            }
        }
    }

    @Test
    fun startupAfter0700CatchesUp() = runBlocking {
        val rig = AiRig(startMs = jerusalemMs(TODAY, 13, 30))
        try {
            assertTrue(rig.briefing.isDue())
            assertTrue(rig.briefing.tick())
            assertEquals("Good morning, Tim.", assertNotNull(rig.briefingRow()).text)
        } finally {
            rig.close()
        }
    }

    @Test
    fun theDateIsTheJerusalemDateAcrossMidnightUtc() = runBlocking {
        // 2026-01-15 22:30 UTC is 00:30 on 2026-01-16 in Jerusalem: not due yet; at 05:00 UTC the 16th it is
        val rig = AiRig(startMs = SEVEN_AM + 17L * 3_600_000 + 30 * 60_000)
        try {
            assertFalse(rig.briefing.isDue())
            rig.clock.nowMs = SEVEN_AM + 24L * 3_600_000
            assertTrue(rig.briefing.tick())
            assertNotNull(rig.briefingRow("2026-01-16"))
            assertNull(rig.briefingRow(TODAY))
        } finally {
            rig.close()
        }
    }

    @Test
    fun aFallbackRowIsNotRetriedAutomatically() = runBlocking {
        val rig = AiRig()
        try {
            rig.fake.reply = { 503 to "{}" }
            assertTrue(rig.briefing.tick())
            assertEquals(BRIEFING_UNAVAILABLE, assertNotNull(rig.briefingRow()).text)
            rig.fake.reply = { 200 to chatBody("would work now") }
            assertFalse(rig.briefing.tick())
            assertEquals(1, rig.fake.bodies.size)
            assertEquals(BRIEFING_UNAVAILABLE, assertNotNull(rig.briefingRow()).text)
        } finally {
            rig.close()
        }
    }
}
