package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.ModelDecision
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.PendingAction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DecisionValidatorTest {
    private val rig = ChatRig()
    private val validator = DecisionValidator(Targets(rig.repo) { rig.now }, rig.read) { rig.now }

    @AfterTest
    fun close() = rig.close()

    private suspend fun run(d: ModelDecision, dreams: Boolean = false, messageId: String = "m1"): ValidatedOutcome {
        rig.chats.create("c1")
        val out = validator.apply(d, rig.executor("c1", "r:$messageId"), dreams, messageId)
        assertFalse(ID_LIKE.containsMatchIn(out.text), out.text)
        for (c in out.choices) assertEquals("", c.token)
        return out
    }

    private fun action(name: String?, target: String? = null, vararg args: Pair<String, String>) =
        ModelDecision("action", null, name, target, args.toMap())

    private suspend fun count(table: String) = rig.pull().count { it.table == table }

    // ---- answers and refusals --------------------------------------------------------------------------

    @Test
    fun answerIsTrimmedAndCapped() = runBlocking<Unit> {
        assertEquals("You have two tasks.", run(ModelDecision("answer", "  You have two tasks.  ")).text)
        val long = run(ModelDecision("answer", "word ".repeat(300))).text
        assertTrue(long.length <= 600, long.length.toString())
        assertTrue(long.startsWith("word word"))
    }

    @Test
    fun blankNullOrIdLikeAnswerAsksAgain() = runBlocking<Unit> {
        val again = ReplyTemplates.sayAgain()
        assertEquals(again, run(ModelDecision("answer", null)).text)
        assertEquals(again, run(ModelDecision("answer", "   ")).text)
        assertEquals(again, run(ModelDecision("answer", "It is task:3f2b8c1e-aaaa on your list.")).text)
        assertEquals(again, run(ModelDecision("answer", "Use update_task for that.")).text)
        assertEquals(again, run(ModelDecision("answer", "Your id: 7 is ready.")).text)
    }

    @Test
    fun needsLaptopUnknownKindAndUnknownActionAllSayThat() = runBlocking<Unit> {
        val laptop = ReplyTemplates.needsLaptop()
        assertEquals(laptop, run(ModelDecision("needs_laptop")).text)
        assertEquals(laptop, run(ModelDecision("surprise", "hi")).text)
        assertEquals(laptop, run(action(null)).text)
        assertEquals(laptop, run(action("delete_task", "x")).text)
        assertEquals(laptop, run(action("research", "x", "item" to "rice")).text)
        assertEquals(0, count("approval") + count("task"))
    }

    @Test
    fun addDreamNeedsADreamMention() = runBlocking<Unit> {
        val d = action("add_dream", null, "text" to "I was flying")
        assertEquals(ReplyTemplates.needsLaptop(), run(d, dreams = false).text)
        assertEquals(0, count("dream"))
        val ok = run(d, dreams = true)
        assertTrue(ok.text.isNotBlank() && ok.text != ReplyTemplates.needsLaptop(), ok.text)
        assertEquals(1, count("dream"))
    }

    // ---- adds ------------------------------------------------------------------------------------------

    @Test
    fun createTaskWritesAndConfirmsWithATemplate() = runBlocking<Unit> {
        val out = run(action("create_task", null, "title" to "Call the dentist", "dueDate" to "2026-11-12"))
        assertTrue(out.text.contains("Call the dentist") && out.text.contains("tomorrow"), out.text)
        assertEquals(1, count("task"))
        assertNull(out.pending)
        assertTrue(out.choices.isEmpty())
    }

    @Test
    fun modelSuppliedIdAndGoalAreIgnoredOnAnAdd() = runBlocking<Unit> {
        val out = run(action("create_task", null, "title" to "Water plants", "id" to "task:evil", "goalId" to "goal:none"))
        assertTrue(out.text.contains("Water plants"), out.text)
        val row = rig.pull().single { it.table == "task" }
        assertFalse(row.id == "task:evil")
        assertNull(rig.str(row, "goalId"))
    }

    @Test
    fun spendingMealCheckinAndJournalAreWritten() = runBlocking<Unit> {
        assertTrue(run(action("add_spending", null, "amount" to "35", "category" to "Food", "note" to "lunch")).text.contains("35"))
        assertTrue(run(action("add_meal", null, "type" to "MEAL", "text" to "pasta")).text.contains("pasta"))
        assertTrue(run(action("add_checkin", null, "mood" to "4", "energy" to "3", "sleepQuality" to "4", "stress" to "2")).text.contains("mood 4"))
        run(action("add_journal", null, "text" to "A quiet day"))
        assertEquals(1, count("spending"))
        assertEquals(1, count("meal"))
        assertEquals(1, count("checkin"))
        assertEquals(1, count("journal"))
    }

    @Test
    fun secondCheckinForTheDayBecomesACard() = runBlocking<Unit> {
        val args = arrayOf("mood" to "2", "energy" to "2", "sleepQuality" to "2", "stress" to "4")
        run(action("add_checkin", null, *args))
        val second = run(action("add_checkin", null, *args), messageId = "m2")
        assertEquals(ReplyTemplates.card(), second.text)
        assertEquals(1, count("approval"))
        assertEquals(1, count("checkin"))
    }

    @Test
    fun missingOrInvalidArgsWriteNothing() = runBlocking<Unit> {
        val again = ReplyTemplates.sayAgain()
        assertEquals(again, run(action("add_spending", null, "category" to "Food")).text)
        assertEquals(again, run(action("add_spending", null, "amount" to "abc", "category" to "Food")).text)
        assertEquals(again, run(action("add_spending", null, "amount" to "5", "category" to "Boats")).text)
        assertEquals(again, run(action("create_task", null, "dueDate" to "2026-11-12")).text)
        assertEquals(again, run(action("add_meal", null, "type" to "MEAL", "text" to "pasta", "date" to "2026-11-10")).text)
        assertEquals(again, run(action("add_checkin", null, "mood" to "9", "energy" to "3", "sleepQuality" to "4", "stress" to "2")).text)
        assertEquals(0, count("spending") + count("task") + count("meal") + count("checkin"))
    }

    // ---- edits -----------------------------------------------------------------------------------------

    @Test
    fun editOfOneTaskMakesAPendingCardForThatTask() = runBlocking<Unit> {
        rig.task("a", "Call the dentist", "2026-11-12")
        rig.task("b", "Buy milk")
        val out = run(action("update_task", "dentist", "done" to "true", "id" to "task:b"))
        assertEquals(ReplyTemplates.card(), out.text)
        val approval = rig.pull().single { it.table == "approval" }
        val data = approval.data.toString()
        assertTrue(data.contains("task:a"), data)
        assertFalse(data.contains("task:b"), data)
        assertEquals("false", rig.str(rig.row("task", "task:a")!!, "done"))
    }

    @Test
    fun editThatMatchesSeveralGivesChipsAndAPendingAction() = runBlocking<Unit> {
        rig.task("a", "Call the dentist")
        rig.task("b", "Call mum")
        val out = run(action("update_task", "call", "dueDate" to "2026-11-20", "id" to "task:a"))
        assertEquals(ReplyTemplates.whichOne(), out.text)
        assertEquals(2, out.choices.size)
        assertEquals(PendingAction("update_task", mapOf("dueDate" to "2026-11-20")), out.pending)
        assertEquals(0, count("approval"))
    }

    @Test
    fun editOfUnknownTaskListsWhatExists() = runBlocking<Unit> {
        rig.task("a", "Call the dentist")
        val out = run(action("update_task", "swimming", "done" to "true"))
        assertEquals(ReplyTemplates.notFoundList(listOf("Call the dentist")), out.text)
        assertEquals(0, count("approval"))
    }

    @Test
    fun editWithNoChangeOrNoTargetAsksAgain() = runBlocking<Unit> {
        rig.task("a", "Call the dentist")
        val again = ReplyTemplates.sayAgain()
        assertEquals(again, run(action("update_task", "dentist")).text)
        assertEquals(again, run(action("update_task", "dentist", "id" to "task:a")).text)
        assertEquals(again, run(action("update_task", " ", "done" to "true")).text)
        assertEquals(again, run(action("update_task", "dentist", "done" to "maybe")).text)
        assertEquals(0, count("approval"))
    }

    @Test
    fun examEditsAndNotFoundList() = runBlocking<Unit> {
        rig.exam("1", "Algebra exam", "2026-12-01")
        val ok = run(action("update_exam", "algebra", "localDate" to "2026-12-03", "time" to "09:00"))
        assertEquals(ReplyTemplates.card(), ok.text)
        assertEquals(1, count("approval"))
        val none = run(action("update_exam", "chemistry", "localDate" to "2026-12-03"))
        assertEquals(ReplyTemplates.notFoundList(listOf("Algebra exam")), none.text)
        assertEquals(1, count("approval"))
    }

    @Test
    fun executorErrorWritesNothingAndAsksAgain() = runBlocking<Unit> {
        rig.exam("1", "Algebra exam", "2026-12-01")
        val out = run(action("update_exam", "algebra", "courseId" to "No such course"))
        assertEquals(ReplyTemplates.sayAgain(), out.text)
        assertEquals(0, count("approval"))
    }

    @Test
    fun logKindsWithNoEntryTodaySayNothingLogged() = runBlocking<Unit> {
        for ((tool, arg) in listOf(
            "update_meal" to ("text" to "salad"), "update_spending" to ("amount" to "10"),
            "update_checkin" to ("mood" to "3"), "update_journal" to ("text" to "new"),
        )) assertEquals(ReplyTemplates.nothingToday(), run(action(tool, "that", arg)).text, tool)
        assertEquals(0, count("approval"))
    }

    @Test
    fun logKindsWithAnEntryMakeACardForThe_newest() = runBlocking<Unit> {
        rig.meal("1", "early pasta", CHAT_T0 - 3_600_000)
        rig.meal("2", "late salad", CHAT_T0)
        rig.spend("1", 3500, "Food", "lunch")
        rig.checkin()
        rig.journal("1", "a note", CHAT_T0)
        assertEquals(ReplyTemplates.card(), run(action("update_meal", "that", "text" to "big salad")).text)
        assertTrue(rig.pull().filter { it.table == "approval" }.single().data.toString().contains("meal:2"))
        assertEquals(ReplyTemplates.card(), run(action("update_spending", "that", "amount" to "45"), messageId = "m2").text)
        assertEquals(ReplyTemplates.card(), run(action("update_checkin", "the check-in", "mood" to "5"), messageId = "m3").text)
        assertEquals(ReplyTemplates.card(), run(action("update_journal", "journal entry", "text" to "better"), messageId = "m4").text)
        assertEquals(4, count("approval"))
    }

    @Test
    fun anUnmatchedLogNameWithOneEntryGivesChipsNotACard() = runBlocking<Unit> {
        rig.spend("1", 3500, "Food", "lunch")
        val out = run(action("update_spending", "the taxi", "amount" to "45"))
        assertEquals(ReplyTemplates.whichOne(), out.text)
        assertEquals(1, out.choices.size)
        assertEquals(PendingAction("update_spending", mapOf("amount" to "45")), out.pending)
        assertEquals(0, count("approval"))
    }

    // ---- find ------------------------------------------------------------------------------------------

    @Test
    fun findOneSeveralNoneAndBadInput() = runBlocking<Unit> {
        rig.task("a", "Call the dentist")
        rig.task("b", "Call mum")
        rig.task("c", "Buy milk")
        val one = run(action("find", "milk", "kind" to "task"))
        assertEquals(ReplyTemplates.found("Buy milk"), one.text)
        assertNull(one.pending)
        val several = run(action("find", "call", "kind" to "task"))
        assertEquals(ReplyTemplates.foundSeveral(), several.text)
        assertEquals(2, several.choices.size)
        assertEquals(PendingAction("find", emptyMap()), several.pending)
        val none = run(action("find", "swimming", "kind" to "task"))
        assertTrue(none.text.startsWith("I couldn't find that. Here's what you have:") && none.text.contains("Buy milk"), none.text)
        assertEquals(ReplyTemplates.notFound("biology"), run(action("find", "biology", "kind" to "course")).text)
        assertEquals(ReplyTemplates.sayAgain(), run(action("find", "x")).text)
        assertEquals(ReplyTemplates.sayAgain(), run(action("find", "x", "kind" to "meal")).text)
        assertEquals(ReplyTemplates.sayAgain(), run(action("find", " ", "kind" to "task")).text)
        assertEquals(0, count("approval"))
    }

    @Test
    fun findWithNothingOfThatKindAtAllSaysNotFound() = runBlocking<Unit> {
        assertEquals(ReplyTemplates.notFound("dentist"), run(action("find", "dentist", "kind" to "task")).text)
    }
}
