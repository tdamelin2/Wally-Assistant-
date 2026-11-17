import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.model.ChatMessageDto
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.chat.SendResult
import dev.tim.assistant.server.chat.TurnResult
import dev.tim.assistant.server.chat.ladder.Embedder
import dev.tim.assistant.server.chat.ladder.SeedStore
import dev.tim.assistant.server.chat.ladder.Sorter
import dev.tim.assistant.server.chat.parseExtras
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * PLAN 20.10 step 2 (SC-10, H19): the handler held-out lines through the whole chat service with the real embedder and seeds and no
 * model. Skipped unless WALLY_EMBED_URL is set. Every option of every question is tapped (on a fresh rig) and must do what its label
 * says. Failures are reported by id, expected action and kind only, never the text.
 */
class HandlerHoldoutTest {
    private class Line(val line: HoldoutLine, val via: String)

    private class Sent(val reply: ChatMessageDto?, val brain: String?, val changed: Map<String, Any>) {
        val isQuestion: Boolean get() = reply != null && (brain == "ask" || reply.choices.any { it.kind == "suggest" || it.kind == "slot" })
    }

    private class Tap(val chip: ChoiceDto, val label: String, val sent: Sent?, val error: String?)

    private class Result(val line: Line, val outcome: Outcome, val kind: String, val mislabelled: List<String>, val why: String = "")

    private fun load(resource: String): List<Line> {
        val raw = checkNotNull(HoldoutLine::class.java.getResourceAsStream("/$resource")).bufferedReader(Charsets.UTF_8).readText()
        val vias = raw.lines().filter { it.isNotBlank() }.associate {
            val o = Json.parseToJsonElement(it).jsonObject
            o.getValue("id").jsonPrimitive.content to o.getValue("via").jsonPrimitive.content
        }
        return loadHoldout(resource).map { Line(it, vias.getValue(it.id)) }
    }

    private fun fill(s: String) = s.replace("{today}", HOLDOUT_TODAY).replace("{tomorrow}", "2026-11-13").replace("{sunday}", "2026-11-15")

    private fun HoldoutRig.sendText(text: String): Sent = runBlocking {
        chats.create("c1")
        val before = snapshot()
        sentOf(service.send("c1", SendMessageRequest("m1", text, now)), before)
    }

    private fun HoldoutRig.sentOf(r: SendResult, before: Map<String, Any>): Sent {
        val reply = (r as? SendResult.Ok)?.response?.reply
        val brain = reply?.let { parseExtras(chats.message(it.id)?.card_json).brain }
        val after = snapshot()
        return Sent(reply, brain, after.filter { (k, v) -> before[k] != v })
    }

    private fun Sent.asTurn(asCode: Boolean): TurnResult =
        if (reply == null) TurnResult.Unavailable(false) else TurnResult.Reply(reply.text, reply.choices, if (asCode) "code" else brain)

    private fun judgeCard(line: HoldoutLine, s: Sent, cards: List<Approval>): LineVerdict {
        fun v(o: Outcome, why: String) = LineVerdict(line, o, why)
        if (s.changed.size != 1 || cards.size != 1) return v(Outcome.WRONG, "wrote ${s.changed.keys.map { it.substringBefore(':') }} instead of one card")
        val row = cards.single()
        val args = Json.parseToJsonElement(row.argsJson).jsonObject
        val problems = ArrayList<String>()
        fun a(n: String) = (line.args[n] as? JsonPrimitive)?.content
        fun g(n: String) = (args[n] as? JsonPrimitive)?.content
        if (row.tool != line.expect) problems += "tool"
        if (row.status != "PENDING") problems += "status"
        a("amount")?.let { e -> if (g("amount")?.toBigDecimalOrNull()?.compareTo(e.toBigDecimal()) != 0) problems += "amount" }
        a("category")?.let { if (!it.equals(g("category"), true)) problems += "category" }
        a("date")?.let { if (g("date") != fill(it)) problems += "date" }
        a("type")?.let { if (!it.equals(g("type"), true)) problems += "type" }
        a("text")?.let { if (g("text")?.contains(it, true) != true) problems += "text" }
        return if (problems.isEmpty()) v(Outcome.HANDLED, "card ok") else v(Outcome.WRONG, "card args differ: $problems")
    }

    /** A direct outcome (or the result of a tap) against the line's expect and args. */
    private fun judgeSent(line: HoldoutLine, s: Sent, asCode: Boolean): LineVerdict {
        val cards = s.changed.values.filterIsInstance<Approval>()
        if ((line.expect == "add_spending" || line.expect == "add_meal") && cards.isNotEmpty()) return judgeCard(line, s, cards)
        return judge(line, s.asTurn(asCode), s.changed)
    }

    private fun tap(sorter: Sorter, text: String, chip: ChoiceDto, label: String): Tap {
        val rig = HoldoutRig(sorter = sorter)
        try {
            val first = rig.sendText(text)
            val live = first.reply?.choices?.firstOrNull { it.kind == chip.kind && it.id == chip.id } ?: return Tap(chip, label, null, "chip missing on rerun")
            val before = rig.snapshot()
            val r = try {
                runBlocking { rig.service.send("c1", SendMessageRequest("m2", live.name, rig.now, choice = live)) }
            } catch (e: Exception) {
                return Tap(chip, label, null, "threw ${e::class.simpleName}")
            }
            return Tap(chip, label, rig.sentOf(r, before), null)
        } finally {
            rig.close()
        }
    }

    private fun resolveDay(word: String): List<String>? {
        val w = word.trim().lowercase()
        val today = LocalDate.parse(HOLDOUT_TODAY)
        return when (w) {
            "today" -> listOf(today.toString())
            "tomorrow" -> listOf(today.plusDays(1).toString())
            else -> DayOfWeek.entries.firstOrNull { it.name.lowercase() == w }?.let { dow ->
                (1L..7L).map { today.plusDays(it) }.filter { it.dayOfWeek == dow }.map { it.toString() }
            }
        }
    }

    private fun strip(s: String) = s.trimEnd('.', '…').trim()

    /** What the tap did against what the button said it would do (SPEC section 6, "Suggestion labels"). */
    private fun problemsOf(tap: Tap): List<String> {
        val t = tap.sent ?: return listOf(tap.error ?: "no result")
        val p = ArrayList<String>()
        val rows = t.changed
        fun kinds() = rows.keys.map { it.substringBefore(':') }
        fun rowsOf(prefix: String): List<Any> = rows.filterKeys { it.startsWith("$prefix:") }.values.toList()
        fun one(prefix: String): Any? {
            if (rows.size != 1 || rowsOf(prefix).size != 1) { p += "wrote ${kinds()} instead of one $prefix"; return null }
            return rowsOf(prefix).single()
        }
        fun nothingWritten() { if (rows.isNotEmpty()) p += "wrote ${kinds()} but the button only asks or shows" }
        val hasSlot = t.reply?.choices?.any { it.kind == "slot" } == true
        if (tap.chip.kind == "slot") {
            val arg = tap.chip.id.substringBefore('=')
            val value = tap.chip.id.substringAfter('=')
            when (arg) {
                "category" -> (one("spending") as? Spending)?.let { if (!it.category.equals(value, true)) p += "category" }
                "type" -> (one("meal") as? Meal)?.let { if (!it.type.equals(value, true)) p += "type" }
                else -> p += "unparsed slot"
            }
            return p
        }
        val label = tap.label.trim()
        when {
            label == "Something else" -> nothingWritten()
            label.startsWith("Log spending: ") -> {
                val m = Regex("^Log spending: ([0-9][0-9.,]*) ₪(?:, ([^(]+?))?(?: \\((.*)\\))?$").find(label)
                if (m == null) p += "unparsed label" else {
                    val cat = m.groupValues[2].ifEmpty { null }
                    if (cat == null) {
                        nothingWritten(); if (!hasSlot) p += "no category question"
                    } else (one("spending") as? Spending)?.let { s ->
                        if (s.amountAgorot != (m.groupValues[1].replace(",", "").toBigDecimal() * 100.toBigDecimal()).toLong()) p += "amount"
                        if (!s.category.equals(cat.trim(), true)) p += "category"
                        val note = m.groupValues[3]
                        if (note.isNotEmpty() && s.note?.contains(strip(note), true) != true) p += "note"
                        if (s.localDate != HOLDOUT_TODAY) p += "date"
                    }
                }
            }
            label.startsWith("Log meal: ") || label.startsWith("Log snack: ") -> {
                val type = if (label.startsWith("Log meal: ")) "MEAL" else "SNACK"
                (one("meal") as? Meal)?.let { m ->
                    if (m.type != type) p += "type"
                    if (!m.text.contains(strip(label.substringAfter(": ")), true)) p += "text"
                    if (m.localDate != HOLDOUT_TODAY) p += "date"
                }
            }
            label.startsWith("Log food: ") -> { nothingWritten(); if (!hasSlot) p += "no meal-or-snack question" }
            label.startsWith("Add task ") -> {
                val m = Regex("^Add task \"(.*)\"(?:, due (.+))?$").find(label)
                if (m == null) p += "unparsed label" else (one("task") as? Task)?.let { t2 ->
                    if (!t2.title.contains(strip(m.groupValues[1]), true)) p += "title"
                    val due = m.groupValues[2].ifEmpty { null }
                    if (due == null) { if (t2.dueDate != null) p += "dueDate not in label" }
                    else {
                        val allowed = resolveDay(due)
                        if (t2.dueDate == null || (allowed != null && t2.dueDate !in allowed)) p += "dueDate"
                    }
                }
            }
            label.startsWith("Journal note: ") -> (one("journal") as? Journal)?.let {
                if (!it.text.contains(strip(label.substringAfter(": ")), true)) p += "text"
            }
            label.startsWith("Check-in: ") -> {
                val m = Regex("^Check-in: mood (\\d), energy (\\d), sleep (\\d), stress (\\d)$").find(label)
                if (m == null) p += "unparsed label" else (one("checkin") as? Checkin)?.let { c ->
                    val g = m.groupValues.drop(1).map { it.toLong() }
                    if (listOf(c.mood, c.energy, c.sleepQuality, c.stress) != g) p += "scores"
                }
            }
            label.startsWith("Mark done: ") -> (one("approval") as? Approval)?.let { a ->
                val name = label.substringAfter(": ")
                val id = FIXTURE_TASKS.entries.firstOrNull { it.key.equals(name, true) }?.value
                val args = Json.parseToJsonElement(a.argsJson).jsonObject
                if (a.tool != "update_task" || args["id"]?.jsonPrimitive?.content != id || args["done"]?.jsonPrimitive?.content != "true") p += "mark-done card"
            }
            label.startsWith("Show: ") -> { nothingWritten(); if (t.reply?.text.isNullOrBlank()) p += "no answer" }
            else -> p += "unparsed label"
        }
        return p
    }

    /** The label a chip stands for: its own name, or (for "Yes") the one suggestion named in the question text. */
    private fun labelOf(chip: ChoiceDto, questionText: String): String {
        if (chip.kind == "suggest" && chip.name.equals("Yes", true)) {
            Regex("Did you mean: (.*)\\?\\s*$", RegexOption.DOT_MATCHES_ALL).find(questionText)?.let { return it.groupValues[1].trim() }
        }
        return chip.name
    }

    private fun taps(sorter: Sorter, text: String, s: Sent): List<Tap> =
        s.reply!!.choices.filter { it.kind == "suggest" || it.kind == "slot" }.map { tap(sorter, text, it, labelOf(it, s.reply.text)) }

    private fun runLine(sorter: Sorter, l: Line): Result {
        val rig = HoldoutRig(sorter = sorter)
        val s = try { rig.sendText(l.line.text) } finally { rig.close() }
        if (s.reply == null) return Result(l, Outcome.NOT_HANDLED, "no code outcome", emptyList())
        if (!s.isQuestion) {
            val v = judgeSent(l.line, s, asCode = false)
            return Result(l, v.outcome, "direct", emptyList(), v.why)
        }
        val all = taps(sorter, l.line.text, s)
        val bad = all.filter { problemsOf(it).isNotEmpty() }.map { "${l.line.id}:${it.chip.kind}:${it.chip.id}" }
        val outcome = if (l.via == "direct") Outcome.NOT_HANDLED
        else if (all.any { it.chip.id != "else" && it.sent != null && judgeSent(l.line, it.sent, asCode = true).outcome == Outcome.HANDLED }) Outcome.HANDLED
        else Outcome.NOT_HANDLED
        return Result(l, outcome, "question", bad)
    }

    @Test
    fun test_AC10_handler_holdout_finished_without_a_model_at_least_64_of_80() = runBlocking<Unit> {
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
            val lines = load("holdout-handler.jsonl")
            val talk = load("holdout-talk.jsonl")
            assertEquals(80, lines.size)
            assertEquals(21, talk.size)
            val results = lines.map { runLine(sorter, it) }
            val handled = results.count { it.outcome == Outcome.HANDLED }
            val wrong = results.filter { it.outcome == Outcome.WRONG }.map { "${it.line.line.id} expect=${it.line.line.expect} wrong (${it.kind}: ${it.why})" }.toMutableList()
            val mislabelled = results.flatMap { it.mislabelled }.toMutableList()
            val talkQuestions = ArrayList<String>()
            for (t in talk) {
                val rig = HoldoutRig(sorter = sorter)
                val s = try { rig.sendText(t.line.text) } finally { rig.close() }
                if (s.reply != null && s.changed.isNotEmpty()) wrong += "${t.line.id} expect=talk wrote ${s.changed.keys.map { it.substringBefore(':') }}"
                if (s.reply != null && s.isQuestion) {
                    talkQuestions += "${t.line.id} talk question"
                    mislabelled += taps(sorter, t.line.text, s).filter { problemsOf(it).isNotEmpty() }.map { "${t.line.id}:${it.chip.kind}:${it.chip.id}" }
                }
            }
            println("not handled (id, expected action, via): " + results.filter { it.outcome == Outcome.NOT_HANDLED }.joinToString { "${it.line.line.id}:${it.line.line.expect}:${it.line.via}:${it.kind}" })
            println("wrong: $wrong")
            println("mislabelled (line:chip kind:chip id): $mislabelled")
            println("talk questions: $talkQuestions")
            println("finished without a model: $handled/80, wrong: ${wrong.size}, mislabelled: ${mislabelled.size}, talk questions: ${talkQuestions.size}")
            assertEquals(emptyList(), wrong, "wrong action")
            assertEquals(emptyList(), mislabelled, "mislabelled button")
            assertEquals(emptyList(), talkQuestions, "talk messages that got a question")
            assertTrue(handled >= 64, "finished without a model: $handled/80, needs at least 64")
        } finally {
            embedder.close()
        }
    }
}
