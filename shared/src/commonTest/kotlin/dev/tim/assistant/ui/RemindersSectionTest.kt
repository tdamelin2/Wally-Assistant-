package dev.tim.assistant.ui

import dev.tim.assistant.model.NotifySettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemindersSectionTest {
    @Test
    fun times_must_be_24_hour_hh_mm() {
        assertTrue(validReminderTime("21:00"))
        assertTrue(validReminderTime(" 07:05 "))
        assertFalse(validReminderTime("24:00"))
        assertFalse(validReminderTime("9:00"))
        assertFalse(validReminderTime("21:60"))
        assertFalse(validReminderTime("evening"))
    }

    @Test
    fun defaults_are_valid_and_bad_fields_name_the_problem() {
        assertNull(reminderSettingsError(NotifySettingsDto()))
        assertEquals("Check-in time must look like 21:00.", reminderSettingsError(NotifySettingsDto(checkinTime = "x")))
        assertEquals("Quiet hours start must look like 23:00.", reminderSettingsError(NotifySettingsDto(quietStart = "25:00")))
        assertEquals("Quiet hours end must look like 07:00.", reminderSettingsError(NotifySettingsDto(quietEnd = "7")))
        assertEquals("Quiet hours start and end must differ.", reminderSettingsError(NotifySettingsDto(quietStart = "22:00", quietEnd = "22:00")))
    }

    @Test
    fun normalizing_trims_the_times_and_keeps_the_switches() {
        val s = normalizedReminderSettings(NotifySettingsDto(checkinTime = " 20:30 ", ideas = false))
        assertEquals("20:30", s.checkinTime)
        assertFalse(s.ideas)
    }
}
