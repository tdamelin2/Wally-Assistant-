package dev.tim.assistant

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.tim.assistant.ai.ModelDecision
import dev.tim.assistant.ai.ReplyStream
import dev.tim.assistant.ai.decisionSchema
import dev.tim.assistant.ai.parseDecision
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs the phone model on a list of prompt packs and reports how many it got right and how fast it was (logcat tag WallyEval).
 * Packs come from files/eval/phone-eval.json in the app's external (else internal) files, or, with the instrumentation
 * argument `packs=smoke`, from the bundled smoke-packs.json (a quick check that the library accepts the schema).
 */
class PhoneBrainEvalTest {
    private class Pack(val id: String, val system: String, val user: String, val schema: JsonObject, val expect: JsonObject)

    private class Outcome(
        val right: Boolean,
        val parsed: Boolean,
        val firstWordMs: Long?,
        val totalMs: Long,
        val why: String,
        val firstWordAny: Long? = null,
        val firstChunkMs: Long? = null,
        val stats: String? = null,
    )

    @Test
    fun phoneBrainEval() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val smoke = InstrumentationRegistry.getArguments().getString("packs") == "smoke"
        val target = inst.targetContext
        val spec = InstrumentationRegistry.getArguments().getString("spec") == "on"
        val clone = InstrumentationRegistry.getArguments().getString("clone") == "on"
        val maxTok = InstrumentationRegistry.getArguments().getString("maxtok")?.toIntOrNull()
        val brain = LiteRtPhoneBrain(target, speculative = spec, clone = clone, maxNumTokens = maxTok, collectStats = true)
        assertTrue("No model file: push the .litertlm file to files/models first", brain.status().modelName != null)

        val packs = if (smoke) {
            parsePacks(inst.context.assets.open("smoke-packs.json").bufferedReader().use { it.readText() })
        } else {
            val file = listOfNotNull(target.getExternalFilesDir(null), target.filesDir)
                .map { File(File(it, "eval"), "phone-eval.json") }
                .firstOrNull { it.isFile }
            if (file == null) fail("push phone-eval.json first")
            parsePacks(file!!.readText())
        }
        assertTrue("The pack list is empty", packs.isNotEmpty())
        if (smoke) {
            val real = decisionSchema(false)
            for (p in packs) assertTrue("Pack ${p.id} does not use the real decisionSchema(false)", p.schema == real)
        }

        runBlocking {
            // One warm-up generation: loads the engine and is not counted.
            val warm = packs.first()
            withTimeout(GEN_TIMEOUT_MS) { brain.generate(warm.system, warm.user, warm.schema) { } }

            val outcomes = packs.map { runPack(brain, it) }
            for ((p, o) in packs.zip(outcomes)) Log.i(TAG, "${p.id}: ${if (o.right) "ok" else "WRONG"} ${o.why} (${o.totalMs} ms) firstWord=${o.firstWordAny ?: "n/a"} firstChunk=${o.firstChunkMs ?: "n/a"} ${o.stats ?: ""}")
            val right = outcomes.count { it.right }
            val firstWords = outcomes.mapNotNull { it.firstWordMs }
            val answerTotals = packs.zip(outcomes).filter { it.first.expect.str("kind") == "answer" }.map { it.second.totalMs }
            val firstMedian = median(firstWords)
            val answerMedian = median(answerTotals)
            Log.i(
                TAG,
                "right: $right/${packs.size}, first word median: ${firstMedian ?: "n/a"} ms, answer median: ${answerMedian ?: "n/a"} ms" +
                    " (backend ${brain.backend}, spec=${if (spec) "on" else "off"}, clone=${if (clone) "on" else "off"}, maxtok=${maxTok ?: "default"})",
            )

            if (smoke) {
                val unparsed = packs.zip(outcomes).filter { !it.second.parsed }.map { it.first.id }
                if (unparsed.isNotEmpty()) fail("Output did not parse for: $unparsed")
                val cancelOk = cancelCheck(brain, packs.first())
                brain.unloadAndWait()
                if (!cancelOk) fail("cancel check failed: the generation after a cancelled one did not complete")
            } else {
                if (right * 5 < packs.size * 4) fail("Only $right/${packs.size} right (need 80%)")
                if (firstMedian != null && firstMedian > FIRST_WORD_LIMIT_MS) fail("First word median $firstMedian ms is over $FIRST_WORD_LIMIT_MS")
                if (answerMedian != null && answerMedian > ANSWER_LIMIT_MS) fail("Answer median $answerMedian ms is over $ANSWER_LIMIT_MS")
                brain.unloadAndWait()
            }
        }
    }

    /** Starts a generation, cancels it after its first chunk, then runs a normal one: it must complete and parse. */
    private suspend fun cancelCheck(brain: LiteRtPhoneBrain, pack: Pack): Boolean {
        val firstChunk = CompletableDeferred<Unit>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            brain.generate(pack.system, pack.user, pack.schema) { firstChunk.complete(Unit) }
        }
        val reached = try {
            withTimeout(GEN_TIMEOUT_MS) { firstChunk.await() }
            true
        } catch (e: Exception) {
            false
        }
        job.cancelAndJoin()
        val next = try {
            withTimeout(GEN_TIMEOUT_MS) { brain.generate(pack.system, pack.user, pack.schema) { } }
        } catch (e: Exception) {
            null
        }
        val ok = reached && next?.raw != null && parseDecision(next.raw!!) != null
        Log.i(TAG, "cancel: ${if (ok) "ok" else "fail"}")
        return ok
    }

    private suspend fun runPack(brain: LiteRtPhoneBrain, p: Pack): Outcome {
        val start = System.nanoTime()
        var firstWord: Long? = null
        val result = withTimeout(GEN_TIMEOUT_MS) {
            brain.generate(p.system, p.user, p.schema) { partial ->
                if (firstWord == null && ReplyStream.extract(partial).isNotEmpty()) firstWord = (System.nanoTime() - start) / 1_000_000
            }
        }
        val total = (System.nanoTime() - start) / 1_000_000
        val raw = result.raw ?: return Outcome(false, false, null, total, "no output", firstWord, result.firstTokenMs, brain.lastStats)
        val decision = parseDecision(raw) ?: return Outcome(false, false, firstWord, total, "does not parse: ${raw.take(120)}", firstWord, result.firstTokenMs, brain.lastStats)
        val problem = check(decision, p.expect)
        val isAnswer = p.expect.str("kind") == "answer"
        return Outcome(problem == null, true, if (isAnswer) firstWord else null, total, problem ?: "", firstWord, result.firstTokenMs, brain.lastStats)
    }

    /** Null when the decision matches [expect], else a short reason. */
    private fun check(d: ModelDecision, expect: JsonObject): String? {
        expect.str("kind")?.let { if (d.kind != it) return "kind ${d.kind} != $it" }
        expect.str("action")?.let { if (d.action != it) return "action ${d.action} != $it" }
        expect.str("target")?.let { if (d.target?.contains(it, ignoreCase = true) != true) return "target ${d.target} lacks $it" }
        (expect["args"] as? JsonObject)?.forEach { (key, want) ->
            val got = d.args[key] ?: return "arg $key missing"
            val w = want as? JsonPrimitive ?: return "bad expectation for $key"
            if (w.isString) {
                if (!got.contains(w.content, ignoreCase = true)) return "arg $key=$got lacks ${w.content}"
            } else {
                val a = got.trim().toDoubleOrNull()
                val b = w.content.toDoubleOrNull()
                if (a == null || b == null || a != b) return "arg $key=$got != ${w.content}"
            }
        }
        expect.str("dataValue")?.let {
            if (d.reply?.contains(it, ignoreCase = true) != true) return "reply lacks $it"
        }
        return null
    }

    private fun parsePacks(text: String): List<Pack> =
        (Json.parseToJsonElement(text) as JsonArray).map { e ->
            val o = e.jsonObject
            Pack(
                id = o.getValue("id").jsonPrimitive.content,
                system = o.getValue("system").jsonPrimitive.content,
                user = o.getValue("user").jsonPrimitive.content,
                schema = o.getValue("schema").jsonObject,
                expect = o.getValue("expect").jsonObject,
            )
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private companion object {
        const val TAG = "WallyEval"
        const val GEN_TIMEOUT_MS = 120_000L
        const val FIRST_WORD_LIMIT_MS = 3_000L
        const val ANSWER_LIMIT_MS = 10_000L
    }
}
