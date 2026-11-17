package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.server.chat.ChatRig
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * The slot rules of Ladder.tryCode for a sorter-accepted ask intent (PLAN 20.4 step 5). A fake embedder places every text on a circle;
 * the clock is Wednesday 2026-11-11 12:00 (Jerusalem), the week began on Sunday 2026-11-08.
 */
class SorterRemapTest {
    private val placed = HashMap<String, Double>()
    private val embedder = TextEmbedder { text, _ ->
        if (text.startsWith("seed:")) vec(text.removePrefix("seed:").toDouble()) else placed[text]?.let { vec(it) }
    }
    private val angles = listOf(
        IntentName.ASK_TODAY to 0.0, IntentName.ASK_SPENDING_WEEK to 60.0, IntentName.ASK_EXAM to 120.0,
        IntentName.ASK_HEALTH to 180.0, IntentName.ASK_MEALS to 240.0, IntentName.ASK_CHECKIN to 300.0,
    )
    private val seeds = angles.flatMap { (intent, base) -> listOf(0.0, 3.0, 6.0).map { Seed(intent, "seed:${base + it}") } }
    private val store = SeedStore(embedder, CoroutineScope(Dispatchers.Unconfined + Job()), seeds).also { it.startSeeding() }
    private val rig = ChatRig(sorter = Sorter(store, embedder, true))

    @AfterTest
    fun close() = rig.close()

    private fun vec(deg: Double): FloatArray = Math.toRadians(deg).let { floatArrayOf(cos(it).toFloat(), sin(it).toFloat()) }

    private suspend fun run(text: String, deg: Double): CodeOutcome? {
        placed[text] = deg
        rig.chats.create("c1")
        val ladder = Ladder(Targets(rig.repo) { rig.now }, AskAnswers(rig.repo) { rig.now }, rig.read, rig.chats, Sorter(store, embedder, true)) { rig.now }
        return ladder.tryCode("c1", "m1", text, null, rig.executor("c1", "r:m1"))
    }

    private suspend fun answer(text: String, deg: Double): String {
        val out = assertNotNull(run(text, deg), text)
        assertEquals("sorter", out.brain, text)
        return out.text
    }

    @Test
    fun spendingWithNoPeriodAnswersThisWeekAsInPhase18() = runBlocking<Unit> {
        rig.spend("s1", 1000, "Food", null, "2026-11-09")
        rig.spend("s2", 2000, "Transport", null, "2026-11-10")
        assertEquals("Since Sunday you've spent 30 ₪: transport 20 ₪, food 10 ₪.", answer("the money I burned through lately", 61.0))
        assertEquals("Since Sunday you've spent 30 ₪: transport 20 ₪, food 10 ₪.", answer("money drain for this week", 62.0))
    }

    @Test
    fun spendingWithAnotherPeriodBecomesAskSpending() = runBlocking<Unit> {
        rig.spend("s1", 1000, "Food", null, "2026-11-10")
        assertEquals("Yesterday you spent 10 ₪: food 10 ₪.", answer("money flowing out yesterday", 61.0))
        assertEquals("No spending logged last month.", answer("money flowing out last month", 62.0))
    }

    @Test
    fun spendingWithACategoryAndNoPeriodIsThatCategoryThisWeek() = runBlocking<Unit> {
        rig.spend("s1", 1000, "Food", "lunch", "2026-11-09")
        rig.spend("s2", 2000, "Transport", null, "2026-11-10")
        assertEquals("This week you spent 10 ₪ on food.", answer("what the groceries are costing me", 61.0))
    }

    @Test
    fun todayWithAnotherDayBecomesEventsForThatDay() = runBlocking<Unit> {
        assertEquals("Nothing on your calendar tomorrow.", answer("walk me through tomorrow", 1.0))
        assertEquals("Nothing on your calendar on Friday 13 November.", answer("run me through friday", 2.0))
        assertEquals(true, answer("how is my day shaping up", 1.0).startsWith("Nothing on your calendar today."))
    }

    @Test
    fun anUnplaceableDayWordTwoPeriodsOrAMissingSlotDropTheResult() = runBlocking<Unit> {
        assertNull(run("how I slept a few days ago", 181.0))
        assertNull(run("how I slept yesterday and today", 182.0))
        assertNull(run("how was my body doing yesterday", 183.0)) // no metric word
        assertNull(run("sleep for the last month", 184.0)) // a month is not a day or a week
    }

    @Test
    fun examsByDateAreDroppedAndTheNextExamsAnswer() = runBlocking<Unit> {
        assertNull(run("which exams are coming up next week", 121.0))
        assertNull(run("the exams I have on friday", 122.0))
        assertNull(run("the exams between friday and saturday", 123.0))
        assertEquals("I don't see an exam like that.", answer("the test coming my way next", 124.0))
        assertEquals("I don't see an exam like that.", answer("what is my next exam about to be", 125.0))
    }

    @Test
    fun healthReadsTheMetricAndLastNightOnlyForSleep() = runBlocking<Unit> {
        rig.healthDay("2026-11-11", 440, 8000, 58)
        assertEquals("You slept 7 h 20 min last night.", answer("how I slept last night", 181.0))
        assertEquals("8000 steps today.", answer("steps I walked today", 182.0))
        assertNull(run("the steps I walked last night", 183.0))
    }

    @Test
    fun mealsTakeCaloriesWhenTheTextSaysSoAndOneDayOnly() = runBlocking<Unit> {
        rig.mealKcal("m1", "Oatmeal", rig.now - 24 * 3_600_000, "2026-11-10", 300)
        assertEquals("About 300 kcal yesterday (1 of 1 meals estimated).", answer("the kcal I took in yesterday", 241.0))
        assertEquals("Yesterday you ate: Oatmeal.", answer("the food I put away yesterday", 242.0))
        assertNull(run("the food I put away last week", 243.0))
        assertNull(run("the food I put away last night", 244.0))
    }

    @Test
    fun checkinTakesADayOrAWeek() = runBlocking<Unit> {
        assertEquals("No check-in this week.", answer("how my scores have gone this week", 301.0))
        assertEquals("No check-in yesterday.", answer("how my scores were yesterday", 302.0))
        assertNull(run("how my scores were this year", 303.0))
    }
}
