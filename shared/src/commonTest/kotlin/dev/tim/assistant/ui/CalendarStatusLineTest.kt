package dev.tim.assistant.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarStatusLineTest {
    @Test
    fun connected() = assertEquals("Wally Calendar: connected", calendarStatusLine(true))

    @Test
    fun not_reachable() =
        assertEquals("Wally Calendar: not reachable - Wally keeps the saved events", calendarStatusLine(false))

    @Test
    fun not_set_up() = assertEquals("Wally Calendar: not set up", calendarStatusLine(null))
}
