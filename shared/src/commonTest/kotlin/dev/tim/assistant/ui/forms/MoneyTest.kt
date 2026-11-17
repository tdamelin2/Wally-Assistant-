package dev.tim.assistant.ui.forms

import dev.tim.assistant.ui.forms.parseAgorot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {
    @Test
    fun parses_exact_agorot() {
        assertEquals(1250L, parseAgorot("12.50"))
        assertEquals(1250L, parseAgorot("12.5"))
        assertEquals(1200L, parseAgorot("12"))
        assertEquals(5L, parseAgorot("0.05"))
        assertEquals(1999L, parseAgorot("19,99"))
    }

    @Test
    fun rejects_zero_and_garbage() {
        assertNull(parseAgorot("0"))
        assertNull(parseAgorot("0.00"))
        assertNull(parseAgorot(""))
        assertNull(parseAgorot("abc"))
        assertNull(parseAgorot("-5"))
        assertNull(parseAgorot("1.234"))
    }
}
