import dev.tim.assistant.ai.mentionsDreams
import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.Approval
import dev.tim.assistant.server.chat.ServerToolExecutor
import dev.tim.assistant.server.chat.ladder.AskAnswers
import dev.tim.assistant.server.chat.ladder.Embedder
import dev.tim.assistant.server.chat.ladder.Ladder
import dev.tim.assistant.server.chat.ladder.SeedStore
import dev.tim.assistant.server.chat.ladder.Sorter
import dev.tim.assistant.server.chat.ladder.Targets
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

/** What code did with one message: the text shown on the review line and its kind ("direct", "question" or "model"). */
data class RmOutcome(val text: String, val kind: String)

class RmResult(
    val total: Int, val direct: Int, val question: Int, val model: Int,
    val markedWrong: List<String>, val changed: List<String>, val regressed: List<String>, val printed: String,
)

/**
 * The review mechanics of Plan note 22:19 item 2, apart from how a message is classified (that is [classify]): ids, dream drop,
 * review.txt with marks, marks.json, accepted.json. It prints ids and counts only, never a message text.
 */
object RealMessagesCore {
    private val lineRx = Regex("^\\[( |x|X)\\] (r\\d+) \\| .* \\| -> (.*)$")

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    private fun readMap(f: File): Map<String, String> =
        if (!f.exists()) emptyMap() else Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun writeMap(f: File, m: Map<String, String>) {
        f.writeText(Json.encodeToString(JsonObject.serializer(), buildJsonObject { for ((k, v) in m) put(k, v) }), Charsets.UTF_8)
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun run(dir: File, messagesFile: File, accept: Boolean, classify: (String) -> RmOutcome, say: (String) -> Unit): RmResult {
        dir.mkdirs()
        val raw = messagesFile.readText(Charsets.UTF_8).removePrefix("﻿")
        val items = Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject }
        // ids r001... in created_at order (the export lists the newest first, so a tie keeps the later row last)
        fun key(o: JsonObject): Pair<Double?, String> {
            val c = (o["created_at"] as? JsonPrimitive)?.content.orEmpty()
            return c.toDoubleOrNull() to c
        }
        val ordered = items.withIndex().sortedWith(
            compareBy<IndexedValue<JsonObject>>({ key(it.value).first ?: Double.MAX_VALUE }, { key(it.value).second }, { -it.index }),
        ).map { it.value }
        val outcomes = LinkedHashMap<String, Pair<String, RmOutcome>>()
        ordered.forEachIndexed { i, o ->
            val id = "r%03d".format(i + 1)
            val text = o.getValue("text").jsonPrimitive.content
            if (mentionsDreams(text)) return@forEachIndexed
            val out = classify(text)
            outcomes[id] = oneLine(text) to out.copy(text = oneLine(out.text))
        }
        val review = File(dir, "review.txt")
        val marksFile = File(dir, "marks.json")
        val acceptedFile = File(dir, "accepted.json")

        // marks: a line Tim changed to [x]; its hash is the outcome he saw
        val shownMarks = LinkedHashMap<String, String>()
        if (review.exists()) for (l in review.readLines(Charsets.UTF_8)) {
            val m = lineRx.find(l) ?: continue
            if (m.groupValues[1] != " ") shownMarks[m.groupValues[2]] = m.groupValues[3]
        }
        val storedMarks = readMap(marksFile)
        val stillWrong = ArrayList<String>()
        val changed = ArrayList<String>()
        val newMarks = LinkedHashMap<String, String>()
        for ((id, shown) in shownMarks) {
            val cur = outcomes[id] ?: continue
            val h = storedMarks[id] ?: hash(shown)
            if (h == hash(cur.second.text)) { stillWrong += id; newMarks[id] = h } else { changed += id; say("changed: $id") }
        }
        writeMap(marksFile, newMarks)

        val oldAccepted = readMap(acceptedFile)
        val regressed = oldAccepted.filter { (id, h) ->
            id !in changed && id !in stillWrong && outcomes[id]?.let { hash(it.second.text) != h } == true
        }.keys.toList()
        for (id in regressed) say("regressed: $id")
        // a line whose marked outcome changed is back with Tim for review: it is not tracked as accepted until the next accept run
        if (oldAccepted.keys.any { it in changed }) writeMap(acceptedFile, oldAccepted - changed.toSet())

        // review.txt: code-handled lines first, then those that go to a model
        val sorted = outcomes.entries.sortedWith(compareBy({ if (it.value.second.kind == "model") 1 else 0 }, { it.key }))
        review.writeText(
            sorted.joinToString("\n", postfix = "\n") { (id, v) -> "[${if (id in stillWrong) "x" else " "}] $id | ${v.first} | -> ${v.second.text}" },
            Charsets.UTF_8,
        )
        if (accept) {
            writeMap(acceptedFile, outcomes.filter { (id, v) -> v.second.kind != "model" && id !in stillWrong }.mapValues { hash(it.value.second.text) })
        }
        val direct = outcomes.values.count { it.second.kind == "direct" }
        val question = outcomes.values.count { it.second.kind == "question" }
        val model = outcomes.values.count { it.second.kind == "model" }
        val printed = "real messages: ${outcomes.size}, by code: ${direct + question} (direct $direct, question $question), to a model: $model, marked wrong: ${stillWrong.size}"
        say(printed)
        if (stillWrong.isNotEmpty()) say("marked: " + stillWrong.joinToString(", "))
        return RmResult(outcomes.size, direct, question, model, stillWrong, changed, regressed, printed)
    }
}

class RealMessagesTest {
    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return checkNotNull(d)
    }

    /** What the code rungs do with one message on a fresh rig (fixture, fixed clock, no model, route auto, typed today). */
    private fun ladderOutcome(sorter: Sorter?, text: String): RmOutcome {
        val rig = HoldoutRig(sorter = sorter)
        try {
            return runBlocking {
                rig.chats.create("c1")
                rig.chats.addUserMessage("c1", "m1", text, rig.now)
                val clock = { rig.now }
                val repo = Repository(LocalStore(rig.sdb.db), now = clock)
                val read = ReadTools(repo, clock)
                val ladder = Ladder(Targets(repo, clock), AskAnswers(repo, clock), read, rig.chats, sorter, clock)
                val exec = ServerToolExecutor(rig.sdb, rig.store, rig.writer, rig.chats, repo, read, clock, "c1", "r:m1")
                val before = rig.snapshot()
                val out = ladder.tryCode("c1", "m1", text, null, exec, typedToday = true)
                val changed = rig.snapshot().filter { (k, v) -> before[k] != v }
                if (out == null) RmOutcome("(goes to a model)", "model") else {
                    val cards = changed.values.filterIsInstance<Approval>().map { it.summary }
                    val buttons = out.choices.filter { it.kind == "suggest" || it.kind == "slot" }.map { it.name }
                    val sb = StringBuilder(out.text)
                    if (cards.isNotEmpty()) sb.append(" + card: ").append(cards.joinToString("; "))
                    if (buttons.isNotEmpty()) sb.append(" + buttons: ").append(buttons.joinToString(" / "))
                    RmOutcome(sb.toString(), if (out.brain == "ask" || buttons.isNotEmpty()) "question" else "direct")
                }
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC16_real_messages_review_marks_wrong_0_and_nothing_regressed() = runBlocking<Unit> {
        val dir = File(root(), "logs/real-messages")
        val messages = File(dir, "messages.json")
        assumeTrue("logs/real-messages/messages.json is missing", messages.exists())
        val url = System.getenv("WALLY_EMBED_URL")
        assumeTrue("WALLY_EMBED_URL not set", !url.isNullOrBlank())
        val embedder = Embedder(url!!, "embeddinggemma")
        try {
            val store = SeedStore(embedder, CoroutineScope(Dispatchers.Default + Job()))
            store.startSeeding()
            var waited = 0
            while (!store.ready && waited < 600) { delay(500); waited++ }
            assertTrue(store.ready, "seeds did not finish (is the embedding model reachable?)")
            val sorter = Sorter(store, embedder)
            val result = RealMessagesCore.run(dir, messages, System.getenv("WALLY_REAL_ACCEPT") == "1", { ladderOutcome(sorter, it) }, ::println)
            assertEquals(emptyList(), result.markedWrong, "lines marked wrong")
            assertEquals(emptyList(), result.regressed, "accepted lines whose outcome changed")
        } finally {
            embedder.close()
        }
    }

    // ---- the mechanics, on invented messages in a temp folder (never logs/real-messages) ----

    private fun writeMessages(f: File, vararg rows: Triple<String, String, Long>) {
        val arr = buildJsonArray {
            // the export lists the newest row first
            for ((id, text, at) in rows.sortedByDescending { it.third }) add(buildJsonObject { put("id", id); put("text", text); put("created_at", at) })
        }
        f.writeText(Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), arr), Charsets.UTF_8)
    }

    @Test
    fun test_AC16_mechanics_ids_marks_changed_accepted_regressed() {
        val tmp = Files.createTempDirectory("realmsg-mech").toFile()
        val dir = File(tmp, "out")
        val msgs = File(tmp, "messages.json")
        writeMessages(
            msgs,
            Triple("a", "invented one: spent forty", 100), Triple("b", "invented two: what is next", 200),
            Triple("c", "invented three: just chatting", 300), Triple("d", "invented four: I had a dream about a boat", 400),
            Triple("e", "invented five: another chat", 500), Triple("f", "invented six: a clear thing", 600),
        )
        val table = mutableMapOf(
            "invented one: spent forty" to RmOutcome("Logged forty", "direct"),
            "invented two: what is next" to RmOutcome("Which one? + buttons: A / B", "question"),
            "invented three: just chatting" to RmOutcome("(goes to a model)", "model"),
            "invented five: another chat" to RmOutcome("(goes to a model)", "model"),
            "invented six: a clear thing" to RmOutcome("Did the clear thing", "direct"),
        )
        val said = ArrayList<String>()
        fun run(accept: Boolean): RmResult { said.clear(); return RealMessagesCore.run(dir, msgs, accept, { table.getValue(it) }, { said += it }) }

        // run 1: ids by created_at, the dream message dropped, code-handled lines first, nothing marked
        val r1 = run(accept = true)
        assertEquals("real messages: 5, by code: 3 (direct 2, question 1), to a model: 2, marked wrong: 0", r1.printed)
        assertEquals(r1.printed, said.last())
        val lines1 = File(dir, "review.txt").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(5, lines1.size)
        assertTrue(lines1.all { it.startsWith("[ ] ") })
        // r004 is the dream message (dropped); the five kept ids are r001, r002, r003, r005, r006
        val order1 = lines1.map { Regex("^\\[.\\] (r\\d+) ").find(it)!!.groupValues[1] }
        assertEquals(listOf("r001", "r002", "r006", "r003", "r005"), order1)
        assertEquals("[ ] r001 | invented one: spent forty | -> Logged forty", lines1.first())
        val accepted1 = Json.parseToJsonElement(File(dir, "accepted.json").readText()).jsonObject
        assertEquals(setOf("r001", "r002", "r006"), accepted1.keys)
        assertFalse(said.any { it.startsWith("regressed:") || it.startsWith("changed:") })

        // Tim marks r002 as wrong: it fails, marks.json stores its hash, the mark survives another run
        val rv = File(dir, "review.txt")
        rv.writeText(rv.readText(Charsets.UTF_8).replace("[ ] r002 ", "[x] r002 "), Charsets.UTF_8)
        for (round in 1..2) {
            val r = run(accept = false)
            assertEquals(listOf("r002"), r.markedWrong, "round $round")
            assertTrue(r.printed.endsWith("marked wrong: 1"))
            assertTrue(said.contains("marked: r002"))
            assertTrue(File(dir, "review.txt").readLines().any { it.startsWith("[x] r002 | ") })
        }
        val marks = Json.parseToJsonElement(File(dir, "marks.json").readText()).jsonObject
        assertEquals(setOf("r002"), marks.keys)
        assertEquals(RealMessagesCore.hash("Which one? + buttons: A / B"), marks.getValue("r002").jsonPrimitive.content)
        // a run without WALLY_REAL_ACCEPT does not touch accepted.json
        assertEquals(setOf("r001", "r002", "r006"), Json.parseToJsonElement(File(dir, "accepted.json").readText()).jsonObject.keys)

        // its outcome changes: "changed: r002", the mark goes back to [ ], marks.json is empty, and it is not "regressed"
        table["invented two: what is next"] = RmOutcome("Better answer", "direct")
        val r3 = run(accept = false)
        assertEquals(emptyList(), r3.markedWrong)
        assertEquals(listOf("r002"), r3.changed)
        assertTrue(said.contains("changed: r002"))
        assertFalse(said.any { it.startsWith("regressed:") })
        assertTrue(File(dir, "review.txt").readLines().any { it.startsWith("[ ] r002 | ") })
        assertEquals(emptySet(), Json.parseToJsonElement(File(dir, "marks.json").readText()).jsonObject.keys)

        // an accepted, unmarked line whose outcome differs: "regressed: r001", a failing result
        table["invented one: spent forty"] = RmOutcome("Logged forty-one", "direct")
        val r4 = run(accept = false)
        assertEquals(listOf("r001"), r4.regressed)
        assertTrue(said.contains("regressed: r001"))
        // accepting again records the new outcomes; the next run is clean
        run(accept = true)
        val r6 = run(accept = false)
        assertEquals(emptyList(), r6.regressed)
        assertEquals(emptyList(), r6.markedWrong)
        // no message text is ever printed
        assertTrue(said.none { it.contains("invented") })
        tmp.deleteRecursively()
    }

    @Test
    fun test_AC16_mechanics_with_the_real_ladder_on_invented_messages() {
        val tmp = Files.createTempDirectory("realmsg-ladder").toFile()
        val msgs = File(tmp, "messages.json")
        writeMessages(
            msgs,
            Triple("a", "spent 35 on lunch", 100), Triple("b", "tell me a story about a fox", 200),
            Triple("c", "I dreamed about a whale", 300), Triple("d", "what is a good way to relax", 400),
        )
        val said = ArrayList<String>()
        val r = RealMessagesCore.run(File(tmp, "out"), msgs, accept = true, classify = { ladderOutcome(null, it) }, say = { said += it })
        assertTrue(r.total in 3..4)
        assertTrue(r.direct + r.question >= 1, "the clear spending line is handled by code")
        assertTrue(r.model >= 1, "a story request goes to a model")
        val lines = File(tmp, "out/review.txt").readLines().filter { it.isNotBlank() }
        assertTrue(lines.first().contains("spent 35 on lunch"))
        assertTrue(lines.last().endsWith("-> (goes to a model)"))
        assertTrue(said.last().startsWith("real messages: "))
        tmp.deleteRecursively()
    }
}
