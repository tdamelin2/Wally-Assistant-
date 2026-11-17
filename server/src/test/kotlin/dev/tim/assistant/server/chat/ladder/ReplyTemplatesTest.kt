package dev.tim.assistant.server.chat.ladder

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyTemplatesTest {
    private val slots = mapOf(
        "title" to "call the dentist", "dueDate" to "2026-11-12", "amount" to "35", "category" to "Food", "note" to "lunch",
        "text" to "pasta", "type" to "MEAL", "mood" to "4", "energy" to "3", "sleepQuality" to "4", "stress" to "2",
    )
    private val today = LocalDate.of(2026, 11, 11)

    /** Message ids whose hash gives each of the three wordings. */
    private val ids: List<String> = run {
        val byVariant = HashMap<Int, String>()
        var i = 0
        while (byVariant.size < 3) { val id = "msg-$i"; byVariant.putIfAbsent(Math.floorMod(id.hashCode(), 3), id); i++ }
        byVariant.toSortedMap().values.toList()
    }

    private fun allTexts(): List<String> = buildList {
        for (a in ReplyTemplates.ADD_ACTIONS) for (id in ids) add(ReplyTemplates.confirm(a, slots, id, today))
        add(ReplyTemplates.card()); add(ReplyTemplates.whichOne()); add(ReplyTemplates.found("Algebra exam")); add(ReplyTemplates.foundSeveral())
        add(ReplyTemplates.notFound("dentist")); add(ReplyTemplates.notFoundList(listOf("Algebra exam", "Physics exam")))
        add(ReplyTemplates.nothingToday()); add(ReplyTemplates.needsLaptop()); add(ReplyTemplates.sayAgain()); add(ReplyTemplates.tooLong())
    }

    @Test
    fun noTemplateShowsAnIdOrToolName() {
        for (t in allTexts()) {
            assertFalse(ID_LIKE.containsMatchIn(t), t)
            assertFalse(t.contains("create_task") || t.contains("add_") || t.contains("update_") || t.contains("find_") || t.contains("get_"), t)
        }
    }

    @Test
    fun theThreeWordingsOfEveryAddDiffer() {
        for (a in ReplyTemplates.ADD_ACTIONS) {
            val texts = ids.map { ReplyTemplates.confirm(a, slots, it, today) }
            assertEquals(3, texts.toSet().size, a)
        }
    }

    @Test
    fun sameMessageIdGivesTheSameWording() {
        assertEquals(ReplyTemplates.confirm("create_task", slots, "m1", today), ReplyTemplates.confirm("create_task", slots, "m1", today))
    }

    @Test
    fun taskConfirmationNamesTheTitleAndDay() {
        val texts = ids.map { ReplyTemplates.confirm("create_task", slots, it, today) }
        for (t in texts) { assertTrue(t.contains("call the dentist")); assertTrue(t.contains("tomorrow"), t) }
        val noDate = ReplyTemplates.confirm("create_task", mapOf("title" to "call the dentist"), ids[0], today)
        assertFalse(noDate.contains("due"), noDate)
    }

    @Test
    fun spendingMealAndCheckinTextsCarryTheirValues() {
        val spending = ReplyTemplates.confirm("add_spending", slots, ids[0], today)
        assertTrue(spending.contains("35") && spending.contains("food"), spending)
        assertTrue(ReplyTemplates.confirm("add_meal", slots, ids[1], today).contains("pasta"))
        assertTrue(ReplyTemplates.confirm("add_checkin", slots, ids[2], today).contains("mood 4"))
    }

    @Test
    fun dayWords() {
        assertEquals("today", ReplyTemplates.dayWord("2026-11-11", today))
        assertEquals("tomorrow", ReplyTemplates.dayWord("2026-11-12", today))
        assertEquals("Monday 16 November", ReplyTemplates.dayWord("2026-11-16", today))
        assertEquals("2026-11-16", ReplyTemplates.dayWord("2026-11-16", null))
    }

    @Test
    fun anIdLikeNameIsNeverSaid() {
        val id = "3f2b8c1e-aaaa-bbbb"
        assertEquals("I couldn't find that.", ReplyTemplates.notFound(id))
        assertFalse(ReplyTemplates.found(id).contains(id))
        assertFalse(ReplyTemplates.notFoundList(listOf(id, "create_task")).contains("create_task"))
        assertEquals("I couldn't find that.", ReplyTemplates.notFoundList(listOf(id)))
    }

    @Test
    fun notFoundListShowsAtMostFiveNames() {
        val text = ReplyTemplates.notFoundList((1..8).map { "Name $it" })
        assertTrue(text.contains("Name 5") && !text.contains("Name 6"), text)
    }

    @Test
    fun fixedWordings() {
        assertEquals("I've put that on a card. Tap Yes to confirm.", ReplyTemplates.card())
        assertEquals("Which one do you mean?", ReplyTemplates.whichOne())
        assertEquals("Found it: Algebra exam.", ReplyTemplates.found("Algebra exam"))
        assertEquals("I couldn't find anything called dentist.", ReplyTemplates.notFound("dentist"))
        assertEquals("That needs the laptop to be on.", ReplyTemplates.needsLaptop())
        assertEquals("Nothing logged today yet.", ReplyTemplates.nothingToday())
    }
}
