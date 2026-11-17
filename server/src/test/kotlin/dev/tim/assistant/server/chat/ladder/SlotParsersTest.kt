package dev.tim.assistant.server.chat.ladder

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlotParsersTest {
    private val thursday = LocalDate.of(2026, 11, 12)
    private val friday = LocalDate.of(2026, 11, 13)

    @Test
    fun amountAcceptsOneNumberAlone() {
        assertEquals("35", SlotParsers.amount("35"))
        assertEquals("35", SlotParsers.amount("35 shekels"))
        assertEquals("35", SlotParsers.amount("35 shekel"))
        assertEquals("35", SlotParsers.amount("35 nis"))
        assertEquals("35", SlotParsers.amount("₪35"))
        assertEquals("35", SlotParsers.amount("35₪"))
        assertEquals("18.5", SlotParsers.amount("18.5"))
        assertEquals("1200", SlotParsers.amount("1,200"))
        assertEquals("35", SlotParsers.amount("35 on lunch"))
    }

    @Test
    fun amountRejectsUnitsCurrenciesAndMoreThanOneNumber() {
        for (t in listOf("35 minutes", "35 hours", "35 km", "35 dollars", "$35", "35$", "35 euros", "€35", "35%", "0", "", "abc", "35 and 40", "2 3")) {
            assertNull(SlotParsers.amount(t), t)
        }
    }

    @Test
    fun amountIgnoresDatesAndTimes() {
        assertEquals("35", SlotParsers.amount("35 for lunch at 13:30"))
        assertEquals("35", SlotParsers.amount("35 on lunch on 15/03"))
    }

    @Test
    fun dateWords() {
        assertEquals(thursday, SlotParsers.date("today", thursday))
        assertEquals(thursday, SlotParsers.date("tonight", thursday))
        assertEquals(friday, SlotParsers.date("tomorrow", thursday))
        assertEquals(LocalDate.of(2026, 11, 15), SlotParsers.date("in 3 days", thursday))
        assertEquals(LocalDate.of(2026, 11, 19), SlotParsers.date("next week", thursday))
    }

    @Test
    fun weekdayIsTheNextOccurrenceAfterToday() {
        assertEquals(friday, SlotParsers.date("friday", thursday))
        assertEquals(LocalDate.of(2026, 11, 19), SlotParsers.date("thursday", thursday))
        assertEquals(LocalDate.of(2026, 11, 20), SlotParsers.date("Friday", friday))
        assertEquals(LocalDate.of(2026, 11, 16), SlotParsers.date("monday", thursday))
    }

    @Test
    fun nextWeekdayIsInTheFollowingWeek() {
        assertEquals(LocalDate.of(2026, 11, 20), SlotParsers.date("next friday", thursday))
        assertEquals(LocalDate.of(2026, 11, 16), SlotParsers.date("next monday", thursday))
        assertEquals(LocalDate.of(2026, 11, 22), SlotParsers.date("next sunday", thursday))
    }

    @Test
    fun isoDateSlashDateAndMonthName() {
        assertEquals(LocalDate.of(2026, 12, 1), SlotParsers.date("2026-12-01", thursday))
        assertNull(SlotParsers.date("2026-11-11", thursday))
        assertNull(SlotParsers.date("2026-02-30", thursday))
        assertEquals(LocalDate.of(2027, 3, 15), SlotParsers.date("15/03", thursday))
        assertEquals(LocalDate.of(2026, 12, 15), SlotParsers.date("15/12", thursday))
        assertEquals(thursday, SlotParsers.date("12/11", thursday))
        assertNull(SlotParsers.date("31/02", thursday))
        assertEquals(LocalDate.of(2026, 12, 3), SlotParsers.date("december 3", thursday))
        assertEquals(LocalDate.of(2027, 3, 20), SlotParsers.date("March 20th", thursday))
    }

    @Test
    fun dateScanRemovesThePhraseAndFlagsTheUnparsed() {
        val ok = SlotParsers.scanDate("call the dentist by friday", thursday)
        assertTrue(ok.found)
        assertEquals(friday, ok.date)
        assertEquals("call the dentist", ok.rest)
        val none = SlotParsers.scanDate("buy milk", thursday)
        assertFalse(none.found)
        assertTrue(none.ok)
        assertEquals("buy milk", none.rest)
        val bad = SlotParsers.scanDate("buy milk yesterday", thursday)
        assertTrue(bad.found)
        assertNull(bad.date)
        assertFalse(bad.ok)
        val twice = SlotParsers.scanDate("buy milk tomorrow or friday", thursday)
        assertFalse(twice.ok)
        assertFalse(SlotParsers.scanDate("read last week's notes", thursday).ok)
    }

    @Test
    fun timeFormats() {
        assertEquals(LocalTime.of(13, 30), SlotParsers.time("at 13:30"))
        assertEquals(LocalTime.of(9, 0), SlotParsers.time("9am"))
        assertEquals(LocalTime.of(18, 30), SlotParsers.time("6:30pm"))
        assertEquals(LocalTime.of(0, 15), SlotParsers.time("12:15 am"))
        assertEquals(LocalTime.of(12, 0), SlotParsers.time("12pm"))
        assertNull(SlotParsers.time("25:00"))
        assertNull(SlotParsers.time("9am or 10am"))
        assertNull(SlotParsers.time("no time here"))
        assertTrue(SlotParsers.hasTime("lunch at 1pm"))
        assertFalse(SlotParsers.hasTime("buy 2 tickets"))
        assertEquals("18:30", SlotParsers.formatTime(LocalTime.of(18, 30)))
    }

    @Test
    fun dayDefaultsToToday() {
        assertEquals(thursday, SlotParsers.day("what's on", thursday))
        assertEquals(thursday, SlotParsers.day("today", thursday))
        assertEquals(friday, SlotParsers.day("tomorrow", thursday))
    }

    @Test
    fun mealTypes() {
        assertEquals("SNACK", SlotParsers.mealType("snack"))
        for (w in listOf("meal", "breakfast", "lunch", "dinner")) assertEquals("MEAL", SlotParsers.mealType(w), w)
        assertNull(SlotParsers.mealType("brunch"))
        assertNull(SlotParsers.mealType("snack and lunch"))
    }

    @Test
    fun categoriesFollowTheKeywordTable() {
        val table = mapOf(
            "Food" to listOf("lunch", "dinner", "breakfast", "coffee", "snack", "groceries", "supermarket", "restaurant", "food", "pizza", "takeaway"),
            "Transport" to listOf("bus", "train", "taxi", "fuel", "petrol", "parking"),
            "Shopping" to listOf("clothes", "shoes", "gift"),
            "Bills" to listOf("rent", "electricity", "internet", "phone bill"),
            "Fun" to listOf("movie", "cinema", "concert", "beer"),
            "Health" to listOf("pharmacy", "doctor", "dentist", "medicine", "gym"),
        )
        for ((cat, words) in table) for (w in words) assertEquals(cat, SlotParsers.spendingCategory("the $w"), w)
    }

    @Test
    fun widenedItemNounsAreSingleCategory() {
        val more = mapOf(
            "Food" to listOf("sushi", "burger", "sandwich", "salad", "falafel", "juice", "cake", "ice cream", "brunch", "cafe", "bakery"),
            "Transport" to listOf("uber", "cab", "metro", "tram", "toll"),
            "Shopping" to listOf("jacket", "jeans", "sneakers", "hoodie", "socks", "present", "clothing"),
            "Bills" to listOf("water bill", "gas bill", "utilities", "insurance", "arnona"),
            "Fun" to listOf("museum", "theatre", "bowling", "karaoke", "movies"),
            "Health" to listOf("vitamins", "therapy", "physio", "clinic", "prescription"),
        )
        for ((cat, words) in more) for (w in words) assertEquals(cat, SlotParsers.spendingCategory("a $w"), w)
        assertNull(SlotParsers.spendingCategory("a burger and a jacket"))
    }

    @Test
    fun dayBeforeMonthDates() {
        assertEquals(LocalDate.of(2026, 12, 5), SlotParsers.date("5 December", thursday))
        assertEquals(LocalDate.of(2027, 1, 3), SlotParsers.date("3rd of January", thursday))
        assertEquals(LocalDate.of(2026, 11, 20), SlotParsers.date("on 20 november", thursday))
        assertEquals(LocalDate.of(2027, 3, 6), SlotParsers.date("by 6th March", thursday))
        assertEquals(thursday, SlotParsers.date("12 November", thursday))
        assertEquals(LocalDate.of(2027, 11, 5), SlotParsers.date("5 November", thursday))
        assertNull(SlotParsers.date("31 February", thursday))
        val scan = SlotParsers.scanDate("call mom 5 December", thursday)
        assertEquals("call mom", scan.rest)
        assertEquals("35", SlotParsers.amount("5 december 35"))
    }

    @Test
    fun categoriesNeedWholeWordsAndOneCategory() {
        assertNull(SlotParsers.spendingCategory("something"))
        assertNull(SlotParsers.spendingCategory("the busy road"))
        assertNull(SlotParsers.spendingCategory("taxi to the restaurant"))
        assertNull(SlotParsers.spendingCategory("my phone"))
        assertEquals("Bills", SlotParsers.spendingCategory("the phone bill"))
        assertEquals("Food", SlotParsers.spendingCategory("Lunch"))
    }

    @Test
    fun checkinScoresNeedAllFour() {
        assertEquals(
            mapOf("mood" to "4", "energy" to "3", "sleepQuality" to "3", "stress" to "2"),
            SlotParsers.checkinScores("check-in: mood 4 energy 3 sleep 3 stress 2"),
        )
        assertEquals(
            mapOf("mood" to "2", "energy" to "2", "sleepQuality" to "1", "stress" to "5"),
            SlotParsers.checkinScores("check-in mood 2, energy 2, sleep quality 1, stress 5"),
        )
        assertNull(SlotParsers.checkinScores("check-in: mood 4 energy 3 sleep 3"))
        assertNull(SlotParsers.checkinScores("check-in: mood 6 energy 3 sleep 3 stress 2"))
        assertNull(SlotParsers.checkinScores("check-in: mood 4 energy 3 sleep 3 stress 2 7"))
        assertNull(SlotParsers.checkinScores("check-in: mood 4 mood 3 sleep 3 stress 2"))
    }
}
