package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.CHAT_MODEL
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.ai.AiRig
import dev.tim.assistant.server.ai.TODAY
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BrainTest {
    private val xps = "http://xps.test:11434"
    private val hp = "http://hp.test:11434"

    private fun tags(vararg names: String) = MockEngine {
        respond(
            """{"models":[${names.joinToString(",") { """{"name":"$it"}""" }}]}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun brain(engine: MockEngine, xpsUrl: String? = xps) =
        Brain(xpsUrl, hp, "qwen3:4b", probeTimeoutMs = 300, engine = engine)

    @Test
    fun xpsAnswersWithTheChatModelPicksXps() = runBlocking {
        val sel = brain(tags(CHAT_MODEL, "other")).pick()
        assertEquals(BrainSelection(xps, CHAT_MODEL, 16384), sel)
    }

    @Test
    fun xpsWithoutTheChatModelFallsBackToHp() = runBlocking {
        val sel = brain(tags("other:1b")).pick()
        assertEquals(BrainSelection(hp, "qwen3:4b", 8192), sel)
    }

    @Test
    fun noXpsUrlPicksHp() = runBlocking {
        val sel = brain(tags(CHAT_MODEL), xpsUrl = null).pick()
        assertEquals(BrainSelection(hp, "qwen3:4b", 8192), sel)
    }

    @Test
    fun xpsTimeoutFallsBackToHp() = runBlocking {
        val slow = MockEngine { delay(5_000); respond("{}", HttpStatusCode.OK) }
        assertEquals(BrainSelection(hp, "qwen3:4b", 8192), brain(slow).pick())
    }

    @Test
    fun xpsServerErrorFallsBackToHp() = runBlocking {
        val bad = MockEngine { respond("no", HttpStatusCode.InternalServerError) }
        assertEquals(hp, brain(bad).pick().baseUrl)
    }

    @Test
    fun hpRequestCarriesTheHpModelAndContext() = runBlocking {
        val slow = MockEngine { delay(5_000); respond("{}", HttpStatusCode.OK) }
        val sel = brain(slow).pick()
        val rig = AiRig()
        try {
            val client = OllamaClient(sel.baseUrl, rig.fake.engine())
            client.chat(sel.model, "system", "hello", sel.numCtx)
            val body = rig.fake.requestJson(0)
            assertEquals("qwen3:4b", body["model"]!!.jsonPrimitive.content)
            assertEquals(8192, body["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.int)
            assertTrue(body["model"]!!.jsonPrimitive.content != CHAT_MODEL)
            client.close()
        } finally {
            rig.close()
        }
    }

    @Test
    fun withChatHoldingTheLockBriefingSkipsThenRunsNextTick() = runBlocking {
        val rig = AiRig()
        try {
            val seenWhileChatRuns = rig.lock.withLock { rig.briefing.tick() }
            assertFalse(seenWhileChatRuns)
            assertEquals(0, rig.fake.bodies.size)
            assertTrue(rig.briefing.tick()) // next tick, lock free
            assertNotNull(rig.briefingRow(TODAY))
        } finally {
            rig.close()
        }
        Unit
    }
}
