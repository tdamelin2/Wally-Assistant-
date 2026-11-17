package dev.tim.assistant.server.chat.ladder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * Runs sorter-dev.jsonl through the real embedding model when WALLY_EMBED_URL is set (the base address of an Ollama
 * that has embeddinggemma); skipped otherwise. A wrong accept is any line answered with an ask intent that is not
 * the line's own. The constants in Sorter.kt are raised until there are none.
 */
class SorterCalibrationTest {
    private fun IntentName.isDayFamily() = this == IntentName.ASK_TODAY || this == IntentName.ASK_EVENTS
    private fun IntentName.isSpendingFamily() = this == IntentName.ASK_SPENDING || this == IntentName.ASK_SPENDING_WEEK
    private val OTHER_DAY_WORD = Regex("tomorrow", RegexOption.IGNORE_CASE)

    private class Line(val group: String, val text: String, val intent: String)

    private fun load(): List<Line> {
        val raw = checkNotNull(javaClass.getResourceAsStream("/sorter-dev.jsonl")) { "sorter-dev.jsonl missing" }
            .bufferedReader(Charsets.UTF_8).readText()
        return raw.lines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it).jsonObject
            Line(o.getValue("group").jsonPrimitive.content, o.getValue("text").jsonPrimitive.content, o.getValue("intent").jsonPrimitive.content)
        }
    }

    @Test
    fun devSetFileHasTheRequiredShape() {
        val lines = load()
        assertEquals(85, lines.size)
        val g = lines.groupBy { it.group }
        assertEquals(40, g.getValue("ask").size)
        assertEquals(20, g.getValue("passup").size)
        assertEquals(15, g.getValue("convo").size)
        assertEquals(10, g.getValue("lookalike").size)
        assertTrue(g.getValue("ask").all { IntentName.fromWire(it.intent)?.isAsk == true })
    }

    @Test
    fun noDevSentenceIsAcceptedWithAWrongAskIntent() = runBlocking<Unit> {
        val url = System.getenv("WALLY_EMBED_URL")
        assumeTrue("WALLY_EMBED_URL not set", !url.isNullOrBlank())
        val embedder = Embedder(url!!, "embeddinggemma")
        try {
            val store = SeedStore(embedder, CoroutineScope(Dispatchers.Default + Job()))
            // the first call may load the model; seed synchronously here
            store.startSeeding()
            var waited = 0
            while (!store.ready && waited < 600) { kotlinx.coroutines.delay(500); waited++ }
            assertTrue(store.ready, "seeds did not finish")
            val sorter = Sorter(store, embedder)
            val wrong = ArrayList<String>()
            var accepted = 0
            var askAccepted = 0
            val askLines = load().filter { it.group == "ask" }
            val snap = store.snapshot()!!
            for (l in load()) {
                val vec = embedder.embed(l.text, 5_000)?.let { normalized(it) }
                val v = vec?.let { sorter.decide(it, snap) }
                println("SORTER LINE [${l.group}] expect=${l.intent} got=${v?.intent?.wire} score=${"%.3f".format(v?.topScore ?: 0.0)} margin=${"%.3f".format(v?.margin ?: 0.0)} :: ${l.text}")
                val got = sorter.classify(l.text)
                if (got != null) {
                    accepted++
                    val want = IntentName.fromWire(l.intent)
                    val sameFamily = (got.isDayFamily() && want?.isDayFamily() == true && !OTHER_DAY_WORD.containsMatchIn(l.text)) ||
                        (got.isSpendingFamily() && want?.isSpendingFamily() == true)
                    if (got.wire != l.intent && !sameFamily) wrong += "[${l.group}] ${l.text} -> ${got.wire}" else askAccepted++
                }
            }
            println("SORTER CALIBRATION: min_score=$SORTER_MIN_SCORE min_margin=$SORTER_MIN_MARGIN accepted=$accepted of ask ${askLines.size} (correct $askAccepted), wrong=${wrong.size}")
            assertEquals(emptyList(), wrong)
        } finally {
            embedder.close()
        }
    }
}
