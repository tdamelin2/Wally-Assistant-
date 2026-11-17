package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.mentionsDreams
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SeedStoreTest {
    private val seeds = listOf(
        Seed(IntentName.ASK_TODAY, "s1"),
        Seed(IntentName.ASK_TODAY, "s2"),
        Seed(IntentName.ASK_TASKS, "s3"),
    )

    private fun newScope() = CoroutineScope(Dispatchers.Unconfined + Job())

    @Test
    fun publishesOnlyAfterEverySeedIsEmbedded() {
        val scope = newScope()
        val store = SeedStore({ _, _ -> floatArrayOf(1f, 0f) }, scope, seeds)
        assertFalse(store.ready)
        store.startSeeding()
        assertTrue(store.ready)
        assertEquals(3, store.snapshot()!!.vectors.size)
        assertEquals(2, store.snapshot()!!.dimension)
    }

    @Test
    fun vectorsAreStoredAtUnitLength() {
        val store = SeedStore({ _, _ -> floatArrayOf(3f, 4f) }, newScope(), seeds)
        store.startSeeding()
        val v = store.snapshot()!!.vectors.first()
        assertEquals(1.0, Math.sqrt((v[0] * v[0] + v[1] * v[1]).toDouble()), 1e-6)
    }

    @Test
    fun failedSeedingPublishesNothingAndALaterCallSucceeds() {
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = SeedStore({ text, _ -> if (text == "s3" && fail.get()) null else floatArrayOf(1f, 0f) }, newScope(), seeds)
        store.startSeeding()
        assertFalse(store.ready)
        assertNull(store.snapshot())
        fail.set(false)
        store.startSeeding()
        assertTrue(store.ready)
    }

    @Test
    fun badVectorsAreRejectedAndNothingIsPublished() {
        val bad = listOf(
            floatArrayOf(),
            floatArrayOf(0f, 0f),
            floatArrayOf(Float.NaN, 1f),
            floatArrayOf(Float.POSITIVE_INFINITY, 1f),
        )
        for (b in bad) {
            val store = SeedStore({ text, _ -> if (text == "s2") b else floatArrayOf(1f, 0f) }, newScope(), seeds)
            store.startSeeding()
            assertFalse(store.ready, "published with a bad vector")
        }
    }

    @Test
    fun aDimensionMismatchPublishesNothing() {
        val store = SeedStore({ text, _ -> if (text == "s3") floatArrayOf(1f, 0f, 0f) else floatArrayOf(1f, 0f) }, newScope(), seeds)
        store.startSeeding()
        assertFalse(store.ready)
    }

    @Test
    fun twoStartCallsRunOneCoroutine() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first = AtomicInteger()
        val store = SeedStore(
            { text, _ ->
                if (text == "s1") first.incrementAndGet()
                gate.await()
                floatArrayOf(1f, 0f)
            },
            newScope(), seeds,
        )
        store.startSeeding()
        store.startSeeding()
        assertFalse(store.ready)
        gate.complete(Unit)
        assertTrue(store.ready)
        assertEquals(1, first.get())
        store.startSeeding()
        assertEquals(1, first.get(), "a ready store seeds again")
    }

    @Test
    fun seedingIsCancelledWithItsScope() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = newScope()
        val store = SeedStore({ _, _ -> gate.await(); floatArrayOf(1f, 0f) }, scope, seeds)
        store.startSeeding()
        scope.coroutineContext[Job]!!.cancel()
        gate.complete(Unit)
        delay(50)
        assertFalse(store.ready)
    }

    @Test
    fun theShippedSeedsAreComplete() {
        val all = SeedStore.loadSeeds()
        val byIntent = all.groupBy { it.intent }
        assertNull(byIntent[IntentName.ADD_DREAM], "add_dream must have no seeds")
        for (i in IntentName.entries.filter { it != IntentName.ADD_DREAM }) {
            assertTrue((byIntent[i]?.size ?: 0) >= 12, "fewer than 12 seeds for ${i.wire}")
        }
        assertTrue(all.size >= 168)
        assertTrue(all.none { mentionsDreams(it.text) }, "a seed has a dream word")
        assertEquals(all.size, all.map { it.text.lowercase() }.toSet().size, "duplicate seeds")
    }

    // ---- Embedder over a mock Ollama ----

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun embedderSendsPrefixModelAndKeepAliveAndParsesTheVector() = runBlocking {
        var path = ""
        var sent = ""
        val engine = MockEngine { req ->
            path = req.url.encodedPath
            sent = String(req.body.toByteArray(), Charsets.UTF_8)
            ok("""{"model":"m","embeddings":[[0.5,0.25,1]]}""")
        }
        val e = Embedder("http://host:11434/", "embeddinggemma", engine)
        val v = e.embed("hello", 2_000)
        assertEquals("/api/embed", path)
        val body = Json.parseToJsonElement(sent).jsonObject
        assertEquals("embeddinggemma", body.getValue("model").jsonPrimitive.content)
        assertEquals(EMBED_PREFIX + "hello", body.getValue("input").jsonPrimitive.content)
        assertEquals("-1", body.getValue("keep_alive").jsonPrimitive.content)
        assertEquals(listOf(0.5f, 0.25f, 1f), assertNotNull(v).toList())
        e.close()
    }

    @Test
    fun embedderGivesNullOnEveryFailure() = runBlocking {
        val server500 = Embedder("http://h", "m", MockEngine { respond("no", HttpStatusCode.InternalServerError) })
        assertNull(server500.embed("x", 2_000))
        val garbage = Embedder("http://h", "m", MockEngine { ok("not json") })
        assertNull(garbage.embed("x", 2_000))
        val noKey = Embedder("http://h", "m", MockEngine { ok("""{"other":1}""") })
        assertNull(noKey.embed("x", 2_000))
        val two = Embedder("http://h", "m", MockEngine { ok("""{"embeddings":[[1],[2]]}""") })
        assertNull(two.embed("x", 2_000))
        val boom = Embedder("http://h", "m", MockEngine { throw java.io.IOException("down") })
        assertNull(boom.embed("x", 2_000))
        val slow = Embedder("http://h", "m", MockEngine { delay(5_000); ok("""{"embeddings":[[1]]}""") })
        assertNull(slow.embed("x", 100))
        listOf(server500, garbage, noKey, two, boom, slow).forEach { it.close() }
        Unit
    }

    @Test
    fun theEmbedModelConfigIsOptional() {
        val cfg = Json { ignoreUnknownKeys = true }.decodeFromString(
            dev.tim.assistant.server.OllamaConfig.serializer(),
            """{"url":"http://a","briefingModel":"m"}""",
        )
        assertNull(cfg.embedModel)
        val cfg2 = Json.decodeFromString(
            dev.tim.assistant.server.OllamaConfig.serializer(),
            """{"url":"http://a","briefingModel":"m","embedModel":"embeddinggemma"}""",
        )
        assertEquals("embeddinggemma", cfg2.embedModel)
    }
}
