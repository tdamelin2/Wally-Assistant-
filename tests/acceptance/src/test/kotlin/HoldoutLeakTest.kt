import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PLAN 18.7 step 5: no held-out or guard text may equal a normalised line, string or JSON value of, or share 6 consecutive words
 * with, the development material (seeds, dev sets, prompt files and every test file of the other modules). Prints only the held-out
 * id and the file name when it fails.
 */
class HoldoutLeakTest {
    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return checkNotNull(d)
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun grams(n: String): Set<String> {
        val w = n.split(" ").filter { it.isNotEmpty() }
        return if (w.size < 6) emptySet() else (0..w.size - 6).map { w.subList(it, it + 6).joinToString(" ") }.toSet()
    }

    private fun jsonStrings(e: JsonElement, out: MutableList<String>) {
        when (e) {
            is JsonPrimitive -> if (e.isString) out += e.content
            is JsonArray -> e.forEach { jsonStrings(it, out) }
            is JsonObject -> e.values.forEach { jsonStrings(it, out) }
        }
    }

    private val literal = Regex("\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"")

    /** Every candidate string of one source file (whole lines, JSON string values, Kotlin string literals). */
    private fun candidates(f: File): List<String> {
        val text = f.readText(Charsets.UTF_8)
        val out = ArrayList<String>()
        out += text.lines()
        if (f.extension == "jsonl" || f.extension == "json") {
            for (l in text.lines().filter { it.isNotBlank() }) runCatching { jsonStrings(Json.parseToJsonElement(l), out) }
        }
        if (f.extension == "kt") {
            for (m in literal.findAll(text)) {
                val s = m.groupValues[1].ifEmpty { m.groupValues[2] }
                out += s
                out += s.lines()
            }
        }
        return out
    }

    private fun sources(): List<File> {
        val r = root()
        val files = ArrayList<File>()
        for (rel in listOf("server/src/main/resources/intent-seeds.jsonl", "server/src/main/resources/wally-voice.md", "server/src/main/resources/hp-last-resort.md", "server/src/main/resources/phone-brain.md", "server/src/main/resources/wally-persona.md")) {
            val f = File(r, rel)
            if (f.exists()) files += f
        }
        for (rel in listOf("server/src/test", "shared/src/commonTest", "shared/src/jvmTest", "androidApp/src/androidTest")) {
            val d = File(r, rel)
            if (d.exists()) files += d.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && !it.path.contains("${File.separator}build${File.separator}") }
        }
        // the dev sets wherever they live (named in the Plan)
        for (name in listOf("intent-seeds.jsonl", "router-dev.jsonl", "sorter-dev.jsonl", "suggest-dev.jsonl")) {
            files += r.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" && it.name != ".gradle" }
                .filter { it.isFile && it.name == name && !it.path.contains("${File.separator}build${File.separator}") }
        }
        return files.distinct()
    }

    @Test
    fun test_AC5_holdout_texts_do_not_leak_from_development_material() {
        val phoneMessages = checkNotNull(HoldoutLeakTest::class.java.getResourceAsStream("/phone-eval-messages.jsonl")).bufferedReader(Charsets.UTF_8).readText()
            .lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it) as JsonObject }
            .map { (it.getValue("id") as JsonPrimitive).content to (it.getValue("text") as JsonPrimitive).content }
        assertEquals(20, phoneMessages.size)
        val handler = loadHoldout("holdout-handler.jsonl")
        val talk = loadHoldout("holdout-talk.jsonl")
        assertEquals(80, handler.size)
        assertEquals(21, talk.size)
        val lines = loadHoldout("holdout-commands.jsonl") + loadHoldout("holdout-guards.jsonl") + handler + talk
        assertEquals(179, lines.size)
        val heldNorm = lines.associate { it.id to norm(it.text) } + phoneMessages.associate { "phone-${it.first}" to norm(it.second) }
        val heldGrams = heldNorm.mapValues { grams(it.value) }
        val srcs = sources()
        assertTrue(srcs.size >= 10, "the development material was not found (${srcs.size} files)")
        val leaks = ArrayList<String>()
        for (f in srcs) {
            val cands = candidates(f).map { norm(it) }.filter { it.isNotEmpty() }
            val candSet = cands.toSet()
            val candGrams = cands.flatMapTo(HashSet()) { grams(it) }
            for ((id, n) in heldNorm) {
                if (n in candSet) leaks += "$id equals a line or string in ${f.name}"
                else if (heldGrams.getValue(id).any { it in candGrams }) leaks += "$id shares 6 consecutive words with ${f.name}"
            }
        }
        assertEquals(emptyList(), leaks)
    }
}
