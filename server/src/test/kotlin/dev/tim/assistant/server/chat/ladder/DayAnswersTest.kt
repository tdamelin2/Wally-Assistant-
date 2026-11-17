package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/** The day questions of Phase 20. The clock is Wednesday 2026-11-11 12:00 Jerusalem; the fixture rows sit in October and November. */
class DayAnswersTest {
    private val rig = ChatRig()
    private val ask = AskAnswers(rig.repo) { rig.now }
    private val today = LocalDate.of(2026, 11, 11)
    private val hour = 3_600_000L
    private val day = 24 * hour

    @AfterTest
    fun close() = rig.close()

    private fun p(word: String, lean: Lean = Lean.PAST) = checkNotNull(Periods.resolve(word, today, lean)) { word }

    private fun assertNoId(vararg texts: String) {
        for (t in texts) assertFalse(ID_LIKE.containsMatchIn(t), t)
    }

    // ---- spending ----

    private suspend fun spendingRows() {
        rig.spend("o1", 4000, "Food", "pizza", "2026-10-20")
        rig.spend("n1", 3000, "Food", "lunch", "2026-11-02")
        rig.spend("n2", 2000, "Transport", null, "2026-11-02")
        rig.spend("n3", 1000, "Fun", null, "2026-11-10")
        rig.spend("n4", 500, "Shopping", null, "2026-11-11")
        rig.spend("n5", 100, "Bills", null, "2026-11-11")
    }

    @Test
    fun spendingForAMonthListsTheTopFourCategories() = runBlocking<Unit> {
        spendingRows()
        assertEquals("Last month you spent 40 ₪: food 40 ₪.", ask.spending(p("last_month")))
        assertEquals("This month you spent 66 ₪: food 30 ₪, transport 20 ₪, fun 10 ₪, shopping 5 ₪.", ask.spending(p("this_month")))
    }

    @Test
    fun spendingWithACategoryGivesThatTotal() = runBlocking<Unit> {
        spendingRows()
        assertEquals("This month you spent 30 ₪ on food.", ask.spending(p("this_month"), "Food"))
        assertEquals("No spending logged this month on health.", ask.spending(p("this_month"), "Health"))
    }

    @Test
    fun spendingForADayAndForADayWithNone() = runBlocking<Unit> {
        spendingRows()
        assertEquals("Yesterday you spent 10 ₪: fun 10 ₪.", ask.spending(p("yesterday")))
        assertEquals("No spending logged on Monday 9 November.", ask.spending(p("weekday:monday")))
    }

    // ---- events and tasks ----

    @Test
    fun eventsOfAPeriodHaveOneLinePerDayWithEvents() = runBlocking<Unit> {
        rig.event("e1", "Team lunch", CHAT_T0, CHAT_T0 + hour)
        rig.event("e2", "Dentist", CHAT_T0 + day, CHAT_T0 + day + hour)
        rig.event("e3", "Trip", CHAT_T0 + 2 * day - 12 * hour, CHAT_T0 + 2 * day + 6 * hour, allDay = true)
        val text = ask.events(p("this_week", Lean.FUTURE))
        assertEquals(
            "On your calendar this week:\nToday: 12:00-13:00 Team lunch.\nTomorrow: 12:00-13:00 Dentist.\nFriday 13 November: all day Trip.",
            text,
        )
        assertEquals("Nothing on your calendar next week.", ask.events(p("next_week", Lean.FUTURE)))
        assertNoId(text)
    }

    @Test
    fun eventsOfAPeriodShowAtMostTwelve() = runBlocking<Unit> {
        for (i in 1..14) rig.event("m$i", "Slot $i", CHAT_T0 + 3 * day + i * 60_000, CHAT_T0 + 3 * day + hour)
        val text = ask.events(p("this_week", Lean.FUTURE))
        assertEquals(12, Regex("Slot \\d+").findAll(text).count(), text)
        assertEquals(true, text.endsWith("\nAnd 2 more."), text)
    }

    @Test
    fun tasksDueInAPeriodAndNothingDue() = runBlocking<Unit> {
        rig.task("a", "Report", "2026-11-16")
        rig.task("b", "Lab sheet", "2026-11-17")
        rig.task("c", "Done thing", "2026-11-18", done = true)
        rig.task("d", "No date")
        rig.task("e", "Old", "2026-11-04")
        assertEquals("Tasks due next week: Report (due Monday 16 November); Lab sheet (due Tuesday 17 November).", ask.tasksDue(p("next_week", Lean.FUTURE)))
        assertEquals("Tasks due last week: Old (due Wednesday 4 November).", ask.tasksDue(p("last_week")))
        assertEquals("Nothing due yesterday.", ask.tasksDue(p("yesterday")))
    }

    // ---- exams ----

    private suspend fun examRows() {
        rig.exam("p", "Physics", "2026-11-20", "09:00")
        rig.exam("pl", "Physics lab", "2026-11-25")
        rig.exam("a", "Algebra", "2026-12-01", "10:30")
        rig.exam("old", "Old exam", "2026-11-01")
    }

    @Test
    fun examByNameWithOneSeveralAndNoMatch() = runBlocking<Unit> {
        examRows()
        assertEquals("Algebra is on Tuesday 1 December at 10:30.", ask.exam("algebra"))
        assertEquals("Algebra is on Tuesday 1 December at 10:30.", ask.exam("my algebra exam"))
        assertEquals("A few exams match: Physics is on Friday 20 November at 09:00; Physics lab is on Wednesday 25 November.", ask.exam("physics"))
        assertEquals("I don't see an exam like that.", ask.exam("chemistry"))
        assertEquals("I don't see an exam like that.", ask.exam("old exam"))
    }

    @Test
    fun theNextThreeExamsAndTomorrowWording() = runBlocking<Unit> {
        examRows()
        rig.exam("t", "Quiz", "2026-11-12", "08:00")
        val text = ask.exam(null)
        assertEquals("Your next exams: Quiz is tomorrow at 08:00; Physics is on Friday 20 November at 09:00; Physics lab is on Wednesday 25 November.", text)
        assertNoId(text)
    }

    @Test
    fun noExamsAtAll() {
        assertEquals("I don't see an exam like that.", ask.exam(null))
    }

    // ---- health ----

    private suspend fun healthRows() {
        rig.healthDay("2026-11-11", 440, 8000, 58)
        rig.healthDay("2026-11-10", null, 0, null)
        rig.healthDay("2026-11-09", 360, 12000, 62)
        rig.healthDay("2026-11-08", 500, null, 60)
    }

    @Test
    fun healthForADay() = runBlocking<Unit> {
        healthRows()
        assertEquals("You slept 7 h 20 min today.", ask.health("sleep", p("today")))
        assertEquals("You slept 7 h 20 min last night.", ask.health("sleep", p("last_night")))
        assertEquals("8000 steps today.", ask.health("steps", p("today")))
        assertEquals("Resting heart rate 58 today.", ask.health("heart", p("today")))
    }

    @Test
    fun aMissingMetricIsNotZeroButARealZeroIsKept() = runBlocking<Unit> {
        healthRows()
        assertEquals("0 steps yesterday.", ask.health("steps", p("yesterday")))
        assertEquals("No sleep data for yesterday.", ask.health("sleep", p("yesterday")))
        assertEquals("No heart rate data for yesterday.", ask.health("heart", p("yesterday")))
        assertEquals("No steps data for Friday 6 November.", ask.health("steps", p("weekday:friday")))
        assertEquals("No sleep data for last week.", ask.health("sleep", p("last_week")))
    }

    @Test
    fun healthWeekGivesTheAverageAndTheBestAndWorstDay() = runBlocking<Unit> {
        healthRows()
        assertEquals(
            "Sleep this week: average 7 h 13 min over 3 days, best Sunday 8 November 8 h 20 min, worst Monday 9 November 6 h 0 min.",
            ask.health("sleep", p("this_week")),
        )
        assertEquals(
            "Steps this week: average 6667 a day over 3 days, best Monday 9 November 12000, worst Tuesday 10 November 0.",
            ask.health("steps", p("this_week")),
        )
        // for the heart the lowest value is the best day
        assertEquals(
            "Resting heart rate this week: average 60 over 3 days, best Wednesday 11 November 58, worst Monday 9 November 62.",
            ask.health("heart", p("this_week")),
        )
    }

    @Test
    fun aTieGoesToTheEarlierDay() = runBlocking<Unit> {
        rig.healthDay("2026-11-09", 400, null, null)
        rig.healthDay("2026-11-10", 400, null, null)
        val text = ask.health("sleep", p("this_week"))
        assertEquals("Sleep this week: average 6 h 40 min over 2 days, best Monday 9 November 6 h 40 min, worst Monday 9 November 6 h 40 min.", text)
    }

    // ---- meals ----

    private suspend fun mealRows() {
        rig.mealKcal("m1", "Oatmeal", CHAT_T0 - day, "2026-11-10", 300)
        rig.mealKcal("m2", "Salad", CHAT_T0 - day + hour, "2026-11-10", null)
        rig.mealKcal("m3", "Apple", CHAT_T0 - day + 2 * hour, "2026-11-10", 80, "SNACK")
        rig.mealKcal("m4", "Toast", CHAT_T0 - 3 * day, "2026-11-08", null)
    }

    @Test
    fun mealsListAndCalories() = runBlocking<Unit> {
        mealRows()
        assertEquals("Yesterday you ate: Oatmeal; Salad; Apple.", ask.meals("list", p("yesterday")))
        assertEquals("About 380 kcal yesterday (2 of 3 meals estimated).", ask.meals("calories", p("yesterday")))
    }

    @Test
    fun mealsWithNoRowsOrNoEstimate() = runBlocking<Unit> {
        mealRows()
        assertEquals("No meals logged on Monday 9 November.", ask.meals("list", p("weekday:monday")))
        assertEquals("No meals logged today.", ask.meals("calories", p("today")))
        assertEquals("About 0 kcal on Sunday 8 November (0 of 1 meals estimated).", ask.meals("calories", p("weekday:sunday")))
        assertEquals("On Sunday 8 November you ate: Toast.", ask.meals("list", p("weekday:sunday")))
    }

    // ---- check-in ----

    @Test
    fun checkinForADayAndAWeekAndNone() = runBlocking<Unit> {
        rig.checkinScores("2026-11-10", 4, 3, 4, 2)
        rig.checkinScores("2026-11-09", 2, 5, 3, 4)
        assertEquals("Check-in yesterday: mood 4, energy 3, sleep 4, stress 2.", ask.checkin(p("yesterday")))
        assertEquals("Check-in averages this week (2 check-ins): mood 3.0, energy 4.0, sleep 3.5, stress 3.0.", ask.checkin(p("this_week")))
        assertEquals("No check-in last week.", ask.checkin(p("last_week")))
        assertEquals("No check-in today.", ask.checkin(p("today")))
    }

    @Test
    fun noDayAnswerShowsAnId() = runBlocking<Unit> {
        spendingRows()
        examRows()
        healthRows()
        mealRows()
        rig.event("e1", "Team lunch", CHAT_T0, CHAT_T0 + hour)
        rig.task("a", "Report", "2026-11-16")
        rig.checkinScores("2026-11-10", 4, 3, 4, 2)
        assertNoId(
            ask.spending(p("this_month")), ask.spending(p("this_month"), "Food"), ask.events(p("this_week", Lean.FUTURE)),
            ask.tasksDue(p("next_week", Lean.FUTURE)), ask.exam(null), ask.exam("physics"), ask.health("sleep", p("this_week")),
            ask.health("steps", p("today")), ask.meals("list", p("yesterday")), ask.meals("calories", p("yesterday")), ask.checkin(p("yesterday")),
        )
    }
}
