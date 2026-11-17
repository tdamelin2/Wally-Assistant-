package dev.tim.assistant.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class DateFormatTest {
    @Test
    fun parsesAndRoundTripsLaptopDates() {
        val date = parseLaptopDate("11-10-2026")
        assertEquals(LocalDate(2026, 11, 10), date)
        assertEquals("11-10-2026", formatDate(date!!, laptop = true))
    }

    @Test
    fun rejectsBadLaptopDates() {
        for (bad in listOf("13-01-2026", "02-30-2026", "2026-11-10", "1-10-2026", "", "11-10-2026x", "11/10/2026")) {
            assertNull(parseLaptopDate(bad), bad)
        }
    }

    @Test
    fun laptopIsTheJvmDefault() {
        assertEquals(true, isLaptop)
        assertEquals("01-05-2026", formatDate(LocalDate(2026, 1, 5)))
    }

    @Test
    fun pickerMillisAreUtcMidnight() {
        assertEquals(LocalDate(2026, 11, 10), pickerMillisToDate(1_794_268_800_000L))
        assertEquals(LocalDate(1970, 1, 1), pickerMillisToDate(0L))
    }

    @Test
    fun formatsTwentyFourHourTime() {
        assertEquals("07:05", formatTime(7, 5))
        assertEquals("23:59", formatTime(23, 59))
    }

    @Test
    fun isoTextIsFormattedOrLeftAlone() {
        assertEquals("11-10-2026", formatIsoDate("2026-11-10", laptop = true))
        assertEquals("soon", formatIsoDate("soon", laptop = true))
    }
}
