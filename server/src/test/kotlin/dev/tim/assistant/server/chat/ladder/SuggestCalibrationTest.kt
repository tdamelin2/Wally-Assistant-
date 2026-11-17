package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.server.chat.ChatRig
import java.time.LocalDate
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
 * Runs suggest-dev.jsonl through the real embedding model when WALLY_EMBED_URL is set (the base address of an Ollama that has
 * embeddinggemma); skipped otherwise. SUGGEST_MIN_SCORE is raised in 0.02 steps until no conversation line gets a question from
 * `InputHandler.suggest` (or `early`), and at least 28 of the 40 commands must still get a question that holds the right option.
 */
class SuggestCalibrationTest {
    private companion object {
        /** The sweep starts well below the planned 0.80 so the printed table shows where the real embedder's scores sit. */
        const val SWEEP_START = 0.50
    }

    private class Line(val group: String, val text: String, val tool: String?, val intent: String?)

    private fun load(): List<Line> {
        val raw = checkNotNull(javaClass.getResourceAsStream("/suggest-dev.jsonl")) { "suggest-dev.jsonl missing" }.bufferedReader(Charsets.UTF_8).readText()
        return raw.lines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it).jsonObject
            Line(
                o.getValue("group").jsonPrimitive.content, o.getValue("text").jsonPrimitive.content,
                o["tool"]?.jsonPrimitive?.content, o["intent"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun devSetFileHasTheRequiredShape() {
        val lines = load()
        assertTrue(lines.size >= 80)
        assertTrue(lines.count { it.group == "cmd" } >= 40)
        assertTrue(lines.count { it.group == "convo" } >= 40)
        val guard = lines.filter { it.group == "convo" && Guards.softBlocked(it.text) }
        assertTrue(guard.size >= 10, guard.map { it.text }.toString())
        assertTrue(lines.filter { it.group == "cmd" }.all { it.tool != null })
    }

    private fun rightOption(out: CodeOutcome?, l: Line): Boolean =
        out != null && out.options.any { it.tool == l.tool && (l.intent == null || it.args["intent"] == l.intent) }

    @Test
    fun noConversationLineGetsAQuestionAndMostCommandsDo() = runBlocking<Unit> {
        val url = System.getenv("WALLY_EMBED_URL")
        assumeTrue("WALLY_EMBED_URL not set", !url.isNullOrBlank())
        val embedder = Embedder(url!!, "embeddinggemma")
        val rig = ChatRig()
        try {
            val store = SeedStore(embedder, CoroutineScope(Dispatchers.Default + Job()))
            store.startSeeding()
            var waited = 0
            while (!store.ready && waited < 600) { kotlinx.coroutines.delay(500); waited++ }
            assertTrue(store.ready, "seeds did not finish")
            val sorter = Sorter(store, embedder)
            val today = LocalDate.of(2026, 11, 11)
            rig.task("t1", "Laundry")
            rig.task("t2", "Math homework")
            rig.task("t3", "Buy gift")
            val handler = InputHandler(Targets(rig.repo) { rig.now })
            val lines = load()
            val ranked = HashMap<String, Ranked?>()
            for (l in lines) {
                val r = sorter.rank(l.text)
                ranked[l.text] = r
                println("SUGGEST LINE [${l.group}] want=${l.tool}/${l.intent} top=${r?.scores?.take(3)?.joinToString { "${it.first.wire}:${"%.3f".format(it.second)}" }} :: ${l.text}")
            }
            fun outcome(l: Line, min: Double): CodeOutcome? =
                handler.early(l.text, today) ?: ranked[l.text]?.let { handler.suggest(l.text, today, it, minScore = min) }
            var chosen = -1.0
            var t = SWEEP_START
            while (t <= 1.0001) {
                val bad = lines.filter { it.group == "convo" && outcome(it, t) != null }
                val good = lines.count { it.group == "cmd" && rightOption(outcome(it, t), it) }
                println("SUGGEST SWEEP min_score=${"%.2f".format(t)} convo_questions=${bad.size} commands_with_right_option=$good of 40")
                if (bad.isEmpty() && chosen < 0) chosen = t
                t += 0.02
            }
            println("SUGGEST CALIBRATION: first min_score with no conversation question = ${"%.2f".format(chosen)}; constant = $SUGGEST_MIN_SCORE")
            val bad = lines.filter { it.group == "convo" && outcome(it, SUGGEST_MIN_SCORE) != null }.map { it.text }
            assertEquals(emptyList(), bad)
            val good = lines.count { it.group == "cmd" && rightOption(outcome(it, SUGGEST_MIN_SCORE), it) }
            println("SUGGEST RESULT: min_score=$SUGGEST_MIN_SCORE commands_with_right_option=$good of 40")
            assertTrue(good >= 28, "only $good of 40 commands get the right option at $SUGGEST_MIN_SCORE")
        } finally {
            rig.close()
            embedder.close()
        }
    }
}
