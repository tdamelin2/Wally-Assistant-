package dev.tim.assistant.server.chat.ladder

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every case pins `today`: Monday 2026-11-16 unless a case says otherwise. */
class PeriodsTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    private fun one(text: String, lean: Lean, today: LocalDate = monday): Found =
        (Periods.find(text, today, lean) as? Scan.One)?.found ?: error("no single period in '$text': ${Periods.find(text, today, lean)}")

    private fun period(text: String, lean: Lean, today: LocalDate = monday) = one(text, lean, today).period

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun singleDayWords() {
        assertEquals(monday, period("today", Lean.PAST).from)
        assertEquals(d("2026-11-15"), period("yesterday", Lean.PAST).from)
        assertEquals(d("2026-11-17"), period("tomorrow", Lean.FUTURE).from)
        val p = period("how did I sleep last night", Lean.PAST)
        assertEquals("last_night", p.word)
        assertTrue(p.singleDay && p.from == monday)
        assertEquals("yesterday", period("yesterday", Lean.PAST).label)
    }

    @Test
    fun weeksRunSundayToSaturdayAndRunningOnesEndTodayWhenPast() {
        val past = period("this week", Lean.PAST)
        assertEquals(d("2026-11-15") to monday, past.from to past.to)
        val future = period("this week", Lean.FUTURE)
        assertEquals(d("2026-11-15") to d("2026-11-21"), future.from to future.to)
        val last = period("last week", Lean.PAST)
        assertEquals(d("2026-11-08") to d("2026-11-14"), last.from to last.to)
        val next = period("next week", Lean.FUTURE)
        assertEquals(d("2026-11-22") to d("2026-11-28"), next.from to next.to)
        assertEquals("this week", past.label)
    }

    @Test
    fun weekOnASundayAndOnASaturday() {
        val sunday = d("2026-11-15")
        assertEquals(sunday to sunday, period("this week", Lean.PAST, sunday).let { it.from to it.to })
        assertEquals(sunday to d("2026-11-21"), period("this week", Lean.FUTURE, sunday).let { it.from to it.to })
        val saturday = d("2026-11-21")
        assertEquals(sunday to saturday, period("this week", Lean.PAST, saturday).let { it.from to it.to })
        assertEquals(d("2026-11-22"), period("next week", Lean.FUTURE, saturday).from)
    }

    @Test
    fun monthsAndTheirEdges() {
        assertEquals(d("2026-11-01") to monday, period("this month", Lean.PAST).let { it.from to it.to })
        assertEquals(d("2026-11-01") to d("2026-11-30"), period("this month", Lean.FUTURE).let { it.from to it.to })
        assertEquals(d("2026-10-01") to d("2026-10-31"), period("last month", Lean.PAST).let { it.from to it.to })
        // the 1st of a month and the turn of the year
        val first = d("2026-12-01")
        assertEquals(d("2026-11-01") to d("2026-11-30"), period("last month", Lean.PAST, first).let { it.from to it.to })
        assertEquals(first to first, period("this month", Lean.PAST, first).let { it.from to it.to })
        val newYear = d("2027-01-01")
        assertEquals(d("2026-12-01") to d("2026-12-31"), period("last month", Lean.PAST, newYear).let { it.from to it.to })
        val dec31 = d("2026-12-31")
        assertEquals(d("2026-12-01") to dec31, period("this month", Lean.PAST, dec31).let { it.from to it.to })
        assertEquals(d("2026-12-27") to d("2027-01-02"), period("this week", Lean.FUTURE, dec31).let { it.from to it.to })
    }

    @Test
    fun aBareWeekdayLeansByDirection() {
        assertEquals(d("2026-11-13"), period("friday", Lean.PAST).from)
        assertEquals(monday, period("monday", Lean.PAST).from)
        assertEquals(d("2026-11-20"), period("friday", Lean.FUTURE).from)
        assertEquals(d("2026-11-23"), period("monday", Lean.FUTURE).from)
    }

    @Test
    fun aBareWeekdayWithFutureLeanEqualsScanDateOnEveryDayAndIsNeverToday() {
        for (offset in 0L..6L) {
            val today = monday.plusDays(offset)
            for (name in days) {
                val expected = SlotParsers.scanDate(name, today).date!!
                val got = period(name, Lean.FUTURE, today).from
                assertEquals(expected, got, "$name on $today")
                assertTrue(got.isAfter(today))
            }
        }
    }

    @Test
    fun lastWeekdayIsInThePreviousSundayToSaturdayWeek() {
        assertEquals(d("2026-11-13"), period("last friday", Lean.PAST).from)
        assertEquals(d("2026-11-08"), period("last sunday", Lean.PAST).from)
        assertEquals(d("2026-11-09"), period("last monday", Lean.PAST).from)
        assertEquals(d("2026-11-14"), period("last saturday", Lean.PAST, d("2026-11-21")).from)
        assertEquals(d("2026-11-14"), period("last saturday", Lean.PAST, d("2026-11-15")).from)
    }

    @Test
    fun nextWeekdayEqualsScanDateOnEveryDay() {
        for (offset in 0L..6L) {
            val today = monday.plusDays(offset)
            for (name in days) {
                val expected = SlotParsers.scanDate("next $name", today).date!!
                assertEquals(expected, period("next $name", Lean.FUTURE, today).from, "next $name on $today")
                assertEquals(expected, period("next $name", Lean.PAST, today).from, "next $name on $today (past)")
            }
        }
    }

    @Test
    fun aMonthNameIsTheWholeMonthAndLeansByDirection() {
        val jan = period("january", Lean.PAST)
        assertEquals(d("2026-01-01") to d("2026-01-31"), jan.from to jan.to)
        val janNext = period("january", Lean.FUTURE)
        assertEquals(d("2027-01-01") to d("2027-01-31"), janNext.from to janNext.to)
        assertEquals(d("2025-12-01"), period("december", Lean.PAST).from)
        assertEquals(d("2026-12-01"), period("december", Lean.FUTURE).from)
        assertEquals(d("2026-11-01") to d("2026-11-30"), period("november", Lean.PAST).let { it.from to it.to })
        assertEquals(d("2026-11-01"), period("in november", Lean.FUTURE).from)
    }

    @Test
    fun theWordMayIsAMonthOnlyAfterALeadWord() {
        assertEquals(Scan.None, Periods.find("what may I spend", monday, Lean.PAST))
        assertEquals(d("2026-05-01"), period("what did I spend in may", Lean.PAST).from)
    }

    @Test
    fun dayAndMonthDatesLeanByDirection() {
        assertEquals(d("2026-11-13"), period("13/11", Lean.PAST).from)
        assertEquals(d("2027-11-13"), period("13/11", Lean.FUTURE).from)
        assertEquals(d("2025-11-20"), period("20/11", Lean.PAST).from)
        assertEquals(d("2026-11-20"), period("20/11", Lean.FUTURE).from)
        assertEquals(d("2026-11-13"), period("13 november", Lean.PAST).from)
        assertEquals(d("2027-11-13"), period("13 november", Lean.FUTURE).from)
        assertEquals(d("2026-11-13"), period("november 13th", Lean.PAST).from)
        assertEquals(d("2026-11-13"), period("2026-11-13", Lean.PAST).from)
        assertEquals(d("2026-11-13"), period("2026-11-13", Lean.FUTURE).from)
        // today is reachable in both directions
        assertEquals(monday, period("16/11", Lean.PAST).from)
        assertEquals(monday, period("16/11", Lean.FUTURE).from)
    }

    @Test
    fun futureLeanDatesEqualScanDate() {
        for (text in listOf("13/11", "20/11", "13 november", "5 december", "november 20", "1/1")) {
            assertEquals(SlotParsers.scanDate(text, monday).date, period(text, Lean.FUTURE).from, text)
        }
    }

    @Test
    fun daysAgoAndAnImpossibleDate() {
        val p = period("3 days ago", Lean.PAST)
        assertEquals(d("2026-11-13"), p.from)
        assertEquals("days_ago:3", p.word)
        assertEquals(Scan.Many, Periods.find("31/2", monday, Lean.PAST))
    }

    @Test
    fun aPossessiveIsRemovedWithThePhrase() {
        assertEquals("schedule", one("friday's schedule", Lean.FUTURE).rest)
        assertEquals("events", one("today's events", Lean.FUTURE).rest)
    }

    @Test
    fun twoPhrasesAreMany() {
        assertEquals(Scan.Many, Periods.find("what did I do yesterday and friday", monday, Lean.PAST))
        assertEquals(Scan.Many, Periods.find("what's on today and tomorrow", monday, Lean.FUTURE))
    }

    @Test
    fun noPhraseIsNone() {
        assertEquals(Scan.None, Periods.find("what are the summer plans", monday, Lean.FUTURE))
        assertEquals(Scan.None, Periods.find("the summer", monday, Lean.FUTURE))
        assertEquals(Scan.None, Periods.find("what is on my calendar", monday, Lean.FUTURE))
    }

    @Test
    fun theLeadWordIsReportedAndRemoved() {
        val alone = one("friday", Lean.PAST)
        assertNull(alone.lead)
        assertEquals("", alone.rest)
        val on = one("what's on friday", Lean.FUTURE)
        assertEquals("on", on.lead)
        assertEquals("what's", on.rest)
        val forWord = one("calendar for friday", Lean.FUTURE)
        assertEquals("for", forWord.lead)
        assertEquals("calendar", forWord.rest)
        assertEquals("in", one("how much did I spend in november", Lean.PAST).lead)
        assertEquals("during", one("what did I eat during last week", Lean.PAST).lead)
        assertNull(one("how much did I spend yesterday", Lean.PAST).lead)
    }

    @Test
    fun longestPhraseWins() {
        assertEquals("last_weekday:friday", period("what did I do last friday", Lean.PAST).word)
        assertEquals("next_weekday:friday", period("next friday", Lean.FUTURE).word)
        assertEquals("date:2026-11-13", period("13 november", Lean.PAST).word)
        assertEquals("last_week", period("last week", Lean.PAST).word)
    }

    @Test
    fun resolveRebuildsEachStableWord() {
        for (text in listOf("today", "yesterday", "tomorrow", "last night", "this week", "last week", "next week", "this month", "last month", "friday", "last friday", "next friday", "november", "13/11", "3 days ago")) {
            val p = period(text, Lean.PAST)
            assertEquals(p, Periods.resolve(p.word, monday, Lean.PAST), text)
        }
        assertEquals(d("2026-11-13"), Periods.resolve("weekday:friday", monday, Lean.PAST)!!.from)
        assertEquals(d("2026-11-20"), Periods.resolve("weekday:friday", monday, Lean.FUTURE)!!.from)
        assertNull(Periods.resolve("nonsense", monday, Lean.PAST))
        assertNull(Periods.resolve("weekday:funday", monday, Lean.PAST))
    }

    @Test
    fun labelsReadInASentence() {
        assertEquals("on Friday 13 November", period("last friday", Lean.PAST).label)
        assertEquals("in November", period("november", Lean.PAST).label)
        assertEquals("in December 2025", period("december", Lean.PAST).label)
        assertEquals("on Wednesday 30 December 2026", period("30/12", Lean.PAST, d("2027-01-05")).label)
    }

    @Test
    fun findAtEdgeNeedsTheEdge() {
        assertNotNull(Periods.findAtEdge("yesterday I spent 35 on lunch", monday, Lean.PAST))
        val trailing = Periods.findAtEdge("spent 35 on lunch yesterday", monday, Lean.PAST)
        assertEquals("yesterday", trailing?.period?.word)
        assertEquals("spent 35 on lunch", trailing?.rest)
        assertEquals("on", Periods.findAtEdge("spent 35 on lunch on yesterday", monday, Lean.PAST)?.lead)
        assertNull(Periods.findAtEdge("spent 40 on friday night dinner", monday, Lean.PAST))
        assertNull(Periods.findAtEdge("spent 35 yesterday on lunch and friday", monday, Lean.PAST))
        assertNull(Periods.findAtEdge("what did I do", monday, Lean.PAST))
    }
}
