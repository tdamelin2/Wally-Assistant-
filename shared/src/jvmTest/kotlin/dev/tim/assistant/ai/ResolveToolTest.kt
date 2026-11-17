package dev.tim.assistant.ai

import dev.tim.assistant.ai.tools.FIND_KINDS
import dev.tim.assistant.ai.tools.findMatches
import dev.tim.assistant.model.ChoiceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveToolTest {
    private fun c(kind: String, id: String, name: String) = ChoiceDto(kind, id, name, "t")

    private val all = listOf(
        c("course", "course:1", "Physics 101"),
        c("course", "course:2", "Linear Algebra"),
        c("exam", "exam:1", "Physics midterm"),
        c("goal", "goal:1", "Run 10k"),
        c("task", "task:1", "Buy shoes for running"),
        c("task", "task:2", "Buy milk"),
    )

    @Test
    fun one_match_is_returned_alone() {
        assertEquals(listOf("course:2"), findMatches(all, null, "algebra").map { it.id })
    }

    @Test
    fun several_matches_are_returned_best_first() {
        val ids = findMatches(all, null, "physics").map { it.id }
        assertEquals(listOf("course:1", "exam:1"), ids)
        // exact name beats contains
        assertEquals("task:2", findMatches(all, "task", "buy milk").first().id)
    }

    @Test
    fun token_match_finds_words_in_any_order() {
        assertEquals(listOf("task:1"), findMatches(all, null, "running shoes").map { it.id })
    }

    @Test
    fun no_match_and_blank_query_return_nothing() {
        assertTrue(findMatches(all, null, "chemistry").isEmpty())
        assertTrue(findMatches(all, null, "   ").isEmpty())
    }

    @Test
    fun at_most_five_are_returned() {
        val many = (1..9).map { c("doc", "doc:$it", "Notes $it") }
        assertEquals(5, findMatches(many, null, "notes").size)
        assertEquals(2, findMatches(many, null, "notes", limit = 2).size)
    }

    @Test
    fun the_kind_filter_applies_and_duplicates_collapse() {
        assertEquals(listOf("exam:1"), findMatches(all, "exam", "physics").map { it.id })
        val dup = all + c("course", "course:1", "Physics 101")
        assertEquals(1, findMatches(dup, "course", "physics").size)
    }

    @Test
    fun the_find_kinds_are_the_contract_eight() {
        assertEquals(listOf("course", "study_doc", "goal", "task", "doc", "event", "exam", "mail"), FIND_KINDS)
    }
}
