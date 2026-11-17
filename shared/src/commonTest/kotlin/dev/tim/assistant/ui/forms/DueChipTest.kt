package dev.tim.assistant.ui.forms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class DueChipTest {
    private val today = LocalDate(2026, 11, 30)

    @Test
    fun each_chip_gives_its_date() {
        assertNull(dueFromChip(DueChip.None, today, LocalDate(2026, 12, 5)))
        assertEquals(today, dueFromChip(DueChip.Today, today, null))
        assertEquals(LocalDate(2026, 12, 1), dueFromChip(DueChip.Tomorrow, today, null))
        assertEquals(LocalDate(2026, 12, 5), dueFromChip(DueChip.Pick, today, LocalDate(2026, 12, 5)))
        assertNull(dueFromChip(DueChip.Pick, today, null))
    }

    @Test
    fun an_existing_due_date_selects_its_chip() {
        assertEquals(DueChip.None, chipForDue(null, today))
        assertEquals(DueChip.Today, chipForDue(today, today))
        assertEquals(DueChip.Tomorrow, chipForDue(LocalDate(2026, 12, 1), today))
        assertEquals(DueChip.Pick, chipForDue(LocalDate(2026, 12, 9), today))
    }
}