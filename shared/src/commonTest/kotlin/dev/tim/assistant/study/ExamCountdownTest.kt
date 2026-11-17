package dev.tim.assistant.study

import dev.tim.assistant.db.Exam
import dev.tim.assistant.time.localDateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ExamCountdownTest {
    private fun exam(id: String, date: String, time: String? = null, deleted: Long = 0) =
        Exam(id, 1, deleted, 0, "SYNCED", null, "Exam $id", date, time)

    @Test
    fun countdown_words() {
        assertEquals("today", examCountdownText("2026-11-09", "2026-11-09"))
        assertEquals("tomorrow", examCountdownText("2026-11-10", "2026-11-09"))
        assertEquals("in 2 days", examCountdownText("2026-11-11", "2026-11-09"))
        assertEquals("in 30 days", examCountdownText("2026-12-09", "2026-11-09"))
        assertEquals("in 61 days", examCountdownText("2027-01-09", "2026-11-09"))
        assertEquals("past", examCountdownText("2026-11-08", "2026-11-09"))
    }

    @Test
    fun past_exams_are_hidden_from_the_next_three_and_marked_past() {
        val exams = listOf(exam("a", "2026-11-08"), exam("b", "2026-11-09"), exam("c", "2026-11-20"))
        assertTrue(isExamPast(exams[0], "2026-11-09"))
        assertFalse(isExamPast(exams[1], "2026-11-09"))
        assertEquals(listOf("b", "c"), upcomingExams(exams, "2026-11-09").map { it.id })
    }

    @Test
    fun upcoming_is_ordered_limited_and_skips_deleted() {
        val exams = listOf(
            exam("z", "2026-12-01", "14:00"),
            exam("y", "2026-12-01", "09:00"),
            exam("x", "2026-11-15"),
            exam("w", "2026-11-12", deleted = 1),
            exam("v", "2027-01-01"),
        )
        assertEquals(listOf("x", "y", "z"), upcomingExams(exams, "2026-11-09").map { it.id })
        assertEquals(listOf("x"), upcomingExams(exams, "2026-11-09", limit = 1).map { it.id })
        assertEquals(listOf("x", "y", "z", "v"), upcomingExams(exams, "2026-11-09", limit = 10).map { it.id })
    }

    @Test
    fun the_jerusalem_midnight_moves_the_countdown() {
        // Jerusalem is UTC+2 in November: 23:59:59 local is 21:59:59Z, midnight is 22:00:00Z.
        val before = localDateOf(Instant.parse("2026-11-09T21:59:59Z"))
        val after = localDateOf(Instant.parse("2026-11-09T22:00:00Z"))
        assertEquals("2026-11-09", before)
        assertEquals("2026-11-10", after)
        assertEquals("tomorrow", examCountdownText("2026-11-10", before))
        assertEquals("today", examCountdownText("2026-11-10", after))
        assertEquals(listOf("e"), upcomingExams(listOf(exam("e", "2026-11-09")), before).map { it.id })
        assertTrue(upcomingExams(listOf(exam("e", "2026-11-09")), after).isEmpty())
    }
}
