package dev.tim.assistant.ui.lists

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class HistoryLogicTest {
    @Test
    fun monthRange_covers_the_whole_month() {
        assertEquals(LocalDate(2026, 11, 1) to LocalDate(2026, 11, 30), monthRange(HistoryMonth(2026, 11)))
        assertEquals(LocalDate(2026, 12, 1) to LocalDate(2026, 12, 31), monthRange(HistoryMonth(2026, 12)))
    }

    @Test
    fun february_has_29_days_in_a_leap_year_only() {
        assertEquals(LocalDate(2028, 2, 29), monthRange(HistoryMonth(2028, 2)).second)
        assertEquals(LocalDate(2027, 2, 28), monthRange(HistoryMonth(2027, 2)).second)
    }

    @Test
    fun previousMonths_starts_at_the_current_month_and_rolls_over_the_year() {
        val months = previousMonths(LocalDate(2026, 2, 15), 4)
        assertEquals(listOf(HistoryMonth(2026, 2), HistoryMonth(2026, 1), HistoryMonth(2025, 12), HistoryMonth(2025, 11)), months)
        assertEquals(12, previousMonths(LocalDate(2026, 11, 11), 12).size)
        assertEquals(HistoryMonth(2025, 12), previousMonths(LocalDate(2026, 11, 11), 12).last())
    }

    @Test
    fun previousMonths_from_the_31st_does_not_skip_a_month() {
        assertEquals(
            listOf(HistoryMonth(2026, 3), HistoryMonth(2026, 2), HistoryMonth(2026, 1)),
            previousMonths(LocalDate(2026, 3, 31), 3),
        )
    }

    @Test
    fun earlyOrLate_compares_the_finish_day_with_the_due_day() {
        val due = LocalDate(2026, 11, 10)
        assertEquals("early", earlyOrLate(due, LocalDate(2026, 11, 9)))
        assertEquals("late", earlyOrLate(due, LocalDate(2026, 11, 11)))
        assertNull(earlyOrLate(due, due))
        assertNull(earlyOrLate(null, due))
    }

    @Test
    fun weekStart_is_the_sunday_before() {
        assertEquals(LocalDate(2026, 11, 8), weekStart(LocalDate(2026, 11, 8)))
        assertEquals(LocalDate(2026, 11, 8), weekStart(LocalDate(2026, 11, 14)))
        assertEquals(LocalDate(2026, 11, 8), weekStart(LocalDate(2026, 11, 11)))
        assertEquals(LocalDate(2026, 11, 15), weekStart(LocalDate(2026, 11, 15)))
    }

    @Test
    fun labels_name_the_month() {
        assertEquals("November 2026", monthLabel(HistoryMonth(2026, 11)))
        assertEquals("Nothing in February", nothingIn(HistoryMonth(2027, 2)))
    }
}