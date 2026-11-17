package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.PendingAction
import dev.tim.assistant.server.chat.SendResult
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * PLAN 20.6 step 11, labels: for every tool the handler can offer and five argument sets each, a tap runs the option and yields a row or card
 * whose values are exactly those the label names, and no two different actions share a label. The clock is Wednesday 2026-11-11 12:00.
 */
class SuggestLabelTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val rig = ChatRig()
    private val long = "a long free text that goes well past the cut of forty characters"
    private var n = 0

    @AfterTest
    fun close() = rig.close()

    /** Offers [f] as the one option of a question reply and taps "Yes"; returns the response and the rows the tap added. */
    private suspend fun tap(f: Filled): Pair<SendResult.Ok, List<dev.tim.assistant.model.SyncRow>> {
        rig.chats.create("c1")
        val id = "m${++n}"
        rig.chats.addUserMessage("c1", id, "the source text", rig.now)
        val chip = ChoiceDto("suggest", "0", "Yes", "tok-$id")
        rig.chats.finishReply("c1", "r:$id", ReplyTemplates.didYouMean(f.label)) {
            it.copy(choices = listOf(chip), options = listOf(PendingAction(f.tool, f.args)), sourceMessageId = id, brain = "ask",
                sourceDay = java.time.Instant.ofEpochMilli(rig.now).atZone(java.time.ZoneId.of("Asia/Jerusalem")).toLocalDate().toString(),
            )
        }
        val before = rig.pull().map { it.id }.toSet()
        val r = assertIs<SendResult.Ok>(rig.service.send("c1", SendMessageRequest("t$id", "Yes", rig.now, null, chip)))
        return r to rig.pull().filter { it.id !in before }
    }

    private fun filled(tool: String, vararg args: Pair<String, String>): Filled =
        assertNotNull(SlotFill.filled(tool, args.toMap(), today), "$tool $args")

    private fun cutOf(text: String) = SlotFill.cut(text).removeSuffix("…")

    @Test
    fun everyTaskOptionSavesTheTitleAndDayItsLabelNames() = runBlocking<Unit> {
        val sets = listOf(
            listOf("title" to "buy milk"), listOf("title" to "call the dentist", "dueDate" to "2026-11-12"), listOf("title" to long),
            listOf("title" to "pay rent", "dueDate" to "2026-11-11"), listOf("title" to long, "dueDate" to "2026-11-16"),
        )
        for (s in sets) {
            val f = filled("create_task", *s.toTypedArray())
            val (r, rows) = tap(f)
            val row = rows.single { it.table == "task" }
            val title = rig.str(row, "title")!!
            assertEquals(s.first { it.first == "title" }.second, title)
            assertTrue(f.label.startsWith("Add task \"" + cutOf(title)), f.label)
            val due = rig.str(row, "dueDate")
            if (due == null) assertTrue(", due " !in f.label, f.label) else assertTrue(f.label.endsWith(", due " + ReplyTemplates.dayWord(due, today)), f.label)
            assertEquals(1, r.response.reply.actions.size)
        }
    }

    @Test
    fun everySpendingOptionSavesTheAmountCategoryAndNoteItsLabelNames() = runBlocking<Unit> {
        val sets = listOf(
            listOf("amount" to "35", "category" to "Food"), listOf("amount" to "12.5", "category" to "Transport", "note" to "bus"),
            listOf("amount" to "100", "category" to "Fun", "note" to long), listOf("amount" to "7", "category" to "Health", "note" to "pharmacy run"),
            listOf("amount" to "250", "category" to "Bills"),
        )
        for (s in sets) {
            val f = filled("add_spending", *s.toTypedArray())
            val (_, rows) = tap(f)
            val row = rows.single { it.table == "spending" }
            val args = s.toMap()
            val agorot = rig.str(row, "amountAgorot")!!.toLong()
            assertEquals((args.getValue("amount").toBigDecimal() * 100.toBigDecimal()).toLong(), agorot)
            assertTrue(f.label.startsWith("Log spending: ${args.getValue("amount")} ₪, ${rig.str(row, "category")!!.lowercase()}"), f.label)
            val note = rig.str(row, "note")
            if (note == null) assertTrue("(" !in f.label, f.label) else assertTrue(f.label.endsWith(" (" + SlotFill.cut(note) + ")"), f.label)
        }
    }

    @Test
    fun everyMealOptionSavesTheTypeAndTextItsLabelNames() = runBlocking<Unit> {
        val sets = listOf(
            listOf("type" to "MEAL", "text" to "pasta"), listOf("type" to "SNACK", "text" to "an apple"), listOf("type" to "MEAL", "text" to long),
            listOf("type" to "SNACK", "text" to long), listOf("type" to "MEAL", "text" to "salad with chicken"),
        )
        for (s in sets) {
            val f = filled("add_meal", *s.toTypedArray())
            val (_, rows) = tap(f)
            val row = rows.single { it.table == "meal" }
            val type = rig.str(row, "type")!!
            val text = rig.str(row, "text")!!
            assertTrue(f.label.startsWith(if (type == "MEAL") "Log meal: " else "Log snack: "), f.label)
            assertTrue(f.label.removePrefix(if (type == "MEAL") "Log meal: " else "Log snack: ").removeSuffix("…").let { text.startsWith(it) }, f.label)
        }
        // a meal with no type yet is "Log food", and its tap asks first
        val noType = filled("add_meal", "text" to "a sandwich")
        assertEquals("Log food: a sandwich", noType.label)
        assertEquals("type", noType.missing)
    }

    @Test
    fun everyJournalOptionSavesTheTextItsLabelNames() = runBlocking<Unit> {
        for (t in listOf("calm day", "met Dana for tea", long, "did the thing", long + " and then some more words")) {
            val f = filled("add_journal", "text" to t)
            val (_, rows) = tap(f)
            val text = rig.str(rows.single { it.table == "journal" }, "text")!!
            assertEquals(t, text)
            assertEquals("Journal note: " + SlotFill.cut(t), f.label)
            assertTrue(text.startsWith(cutOf(t)))
        }
    }

    @Test
    fun everyCheckinOptionSavesTheScoresItsLabelNames() = runBlocking<Unit> {
        val sets = listOf(listOf(4, 3, 4, 2), listOf(1, 1, 1, 1), listOf(5, 5, 5, 5), listOf(2, 4, 3, 5), listOf(3, 3, 3, 3))
        for (s in sets) {
            val f = filled("add_checkin", "mood" to "${s[0]}", "energy" to "${s[1]}", "sleepQuality" to "${s[2]}", "stress" to "${s[3]}")
            val (_, rows) = tap(f)
            val row = rows.single { it.table == "checkin" }
            assertEquals("Check-in: mood ${rig.str(row, "mood")}, energy ${rig.str(row, "energy")}, sleep ${rig.str(row, "sleepQuality")}, stress ${rig.str(row, "stress")}", f.label)
            assertEquals(s, listOf("mood", "energy", "sleepQuality", "stress").map { rig.str(row, it)!!.toInt() })
            // one check-in a day: the next set must be a different day, so move the clock
            rig.now += 24 * 3_600_000
        }
    }

    @Test
    fun everyMarkDoneOptionOpensACardForTheTaskItsLabelNames() = runBlocking<Unit> {
        for ((i, name) in listOf("Laundry", "Write the report", "Call mom", "Water plants", "Pay the electricity bill for november").withIndex()) {
            rig.task("t$i", name)
            val f = filled("update_task", "name" to name, "done" to "true")
            assertEquals("Mark done: " + SlotFill.cut(name), f.label)
            val (r, _) = tap(f)
            assertEquals(1, r.response.reply.cards.size)
            assertTrue(r.response.reply.cards.single().summary.contains(name.take(20)), r.response.reply.cards.single().summary)
        }
    }

    @Test
    fun everyAskOptionAnswersForThePeriodItsLabelNames() = runBlocking<Unit> {
        val sets = listOf(
            listOf("intent" to "ask_tasks", "period" to "tomorrow") to "Show: tasks due tomorrow",
            listOf("intent" to "ask_events", "period" to "tomorrow") to "Show: events tomorrow",
            listOf("intent" to "ask_spending", "period" to "yesterday", "category" to "Food") to "Show: food spending yesterday",
            listOf("intent" to "ask_health", "metric" to "steps", "period" to "yesterday") to "Show: steps yesterday",
            listOf("intent" to "ask_checkin", "period" to "last_week") to "Show: check-in last week",
        )
        for ((args, label) in sets) {
            val f = filled("ask", *args.toTypedArray())
            assertEquals(label, f.label)
            val (r, rows) = tap(f)
            assertTrue(r.response.reply.text.isNotBlank())
            assertTrue(rows.isEmpty(), "an ask saves nothing")
        }
        for ((intent, label) in listOf("ask_today" to "Show: today's plan", "ask_exam" to "Show: next exams", "ask_spending_week" to "Show: spending this week")) {
            assertEquals(label, filled("ask", "intent" to intent).label)
        }
    }

    @Test
    fun noTwoDifferentActionsShareALabelInOneSet() {
        val all = listOf(
            filled("add_spending", "amount" to "35", "category" to "Food"), filled("add_spending", "amount" to "35", "category" to "Fun"),
            filled("add_spending", "amount" to "35", "category" to "Food", "note" to "lunch"), filled("add_meal", "type" to "MEAL", "text" to "lunch"),
            filled("add_meal", "type" to "SNACK", "text" to "lunch"), filled("add_meal", "text" to "lunch"),
            filled("create_task", "title" to "lunch"), filled("add_journal", "text" to "lunch"),
        )
        val labels = all.map { SlotFill.typedKey(it.label) }
        assertEquals(labels.size, labels.toSet().size, labels.toString())
        assertEquals(all.size, SlotFill.distinct(all).size)
        // two long texts equal in their first 40 characters collide after the cut: the second is dropped; so is the same action twice
        val a = filled("add_journal", "text" to long + " first")
        val b = filled("add_journal", "text" to long + " second")
        assertEquals(a.label, b.label)
        assertEquals(listOf(a), SlotFill.distinct(listOf(a, b, a)))
        // a label may not equal a fixed button name either
        assertNull(SlotFill.labelFor("add_journal", mapOf("text" to ""), today))
    }

    @Test
    fun theProjectionKeepsOnlyTheToolsOwnFields() {
        assertEquals(mapOf("amount" to "35", "category" to "Food"), SlotFill.project("add_spending", mapOf("amount" to " 35 ", "category" to "Food", "id" to "x", "note" to " ", "bogus" to "1")))
        assertEquals(mapOf("title" to "x"), SlotFill.project("create_task", mapOf("title" to "x", "goalId" to "g1")))
        assertEquals(mapOf("name" to "x", "done" to "true"), SlotFill.project("update_task", mapOf("name" to "x", "done" to "true", "id" to "t")))
    }
}
