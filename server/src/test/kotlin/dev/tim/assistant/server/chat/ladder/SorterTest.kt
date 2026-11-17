package dev.tim.assistant.server.chat.ladder

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

class SorterTest {
    private fun at(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return floatArrayOf(cos(r).toFloat(), sin(r).toFloat())
    }

    /** today seeds at 0/5/10 degrees, tasks seeds at 90/95/100, create_task seeds at 180/185/190. */
    private val seeds = listOf(
        Seed(IntentName.ASK_TODAY, "t0") to 0.0, Seed(IntentName.ASK_TODAY, "t1") to 5.0, Seed(IntentName.ASK_TODAY, "t2") to 10.0,
        Seed(IntentName.ASK_TASKS, "k0") to 90.0, Seed(IntentName.ASK_TASKS, "k1") to 95.0, Seed(IntentName.ASK_TASKS, "k2") to 100.0,
        Seed(IntentName.CREATE_TASK, "c0") to 180.0, Seed(IntentName.CREATE_TASK, "c1") to 185.0, Seed(IntentName.CREATE_TASK, "c2") to 190.0,
    )
    private val snap = SeedSnapshot(seeds.map { it.first }, seeds.map { normalized(at(it.second))!! })

    private fun sorter(minScore: Double = SORTER_MIN_SCORE, minMargin: Double = SORTER_MIN_MARGIN) =
        Sorter(SeedStore({ _, _ -> null }, CoroutineScope(Job()), emptyList()), null, true, minScore, minMargin)

    private fun decide(deg: Double, s: Sorter = sorter()) = s.decide(normalized(at(deg))!!, snap)!!

    @Test
    fun acceptsWhenAllThreeNearestAgreeAndScoreAndMarginHold() {
        assertEquals(IntentName.ASK_TODAY, decide(3.0).accepted)
        assertEquals(IntentName.ASK_TASKS, decide(97.0).accepted)
    }

    @Test
    fun rejectsWhenTheThreeNearestDisagree() {
        val v = decide(52.0)
        assertNull(v.accepted)
    }

    @Test
    fun rejectsALowTopScore() {
        assertNotNull(decide(8.0).accepted)
        assertNull(decide(8.0, sorter(minScore = 0.99999)).accepted)
    }

    @Test
    fun rejectsALowMargin() {
        assertNotNull(decide(3.0).accepted)
        assertNull(decide(3.0, sorter(minMargin = 0.99)).accepted)
    }

    @Test
    fun anIntentOutsideTheReadOnlySetIsNeverAccepted() {
        val v = decide(185.0)
        assertEquals(IntentName.CREATE_TASK, v.intent)
        assertNull(v.accepted)
        assertEquals(IntentName.entries.filter { it.isAsk }.toSet(), SORTER_ACTS)
        assertEquals(
            setOf(
                IntentName.ASK_TODAY, IntentName.ASK_TASKS, IntentName.ASK_EVENTS, IntentName.ASK_SPENDING_WEEK, IntentName.ASK_SPENDING,
                IntentName.ASK_EXAM, IntentName.ASK_HEALTH, IntentName.ASK_MEALS, IntentName.ASK_CHECKIN,
            ),
            SORTER_ACTS,
        )
    }

    @Test
    fun theTwoSpendingIntentsAreOneFamily() {
        val mixed = listOf(
            Seed(IntentName.ASK_SPENDING_WEEK, "a") to 0.0, Seed(IntentName.ASK_SPENDING, "b") to 2.0, Seed(IntentName.ASK_SPENDING_WEEK, "c") to 4.0,
            Seed(IntentName.ASK_TASKS, "d") to 90.0, Seed(IntentName.ASK_TASKS, "e") to 95.0, Seed(IntentName.ASK_TASKS, "f") to 100.0,
        )
        val s = SeedSnapshot(mixed.map { it.first }, mixed.map { normalized(at(it.second))!! })
        val v = sorter().decide(normalized(at(1.9))!!, s)!!
        assertEquals(IntentName.ASK_SPENDING, v.intent)
        assertEquals(IntentName.ASK_SPENDING, v.accepted)
        // a different intent among the three nearest still blocks it
        val near = listOf(
            Seed(IntentName.ASK_SPENDING_WEEK, "a") to 0.0, Seed(IntentName.ASK_SPENDING, "b") to 2.0, Seed(IntentName.ASK_HEALTH, "d") to 3.0,
            Seed(IntentName.ASK_TASKS, "e") to 95.0,
        )
        val s2 = SeedSnapshot(near.map { it.first }, near.map { normalized(at(it.second))!! })
        assertNull(sorter().decide(normalized(at(1.0))!!, s2)!!.accepted)
    }

    @Test
    fun todayAndEventsAreOneFamilyForTheTopThreeAndTheMargin() {
        val mixed = listOf(
            Seed(IntentName.ASK_TODAY, "a") to 0.0, Seed(IntentName.ASK_EVENTS, "b") to 2.0, Seed(IntentName.ASK_TODAY, "c") to 4.0,
            Seed(IntentName.ASK_TASKS, "d") to 90.0, Seed(IntentName.ASK_TASKS, "e") to 95.0, Seed(IntentName.ASK_TASKS, "f") to 100.0,
        )
        val s = SeedSnapshot(mixed.map { it.first }, mixed.map { normalized(at(it.second))!! })
        val v = sorter().decide(normalized(at(1.9))!!, s)!!
        assertEquals(IntentName.ASK_EVENTS, v.intent)
        assertEquals(IntentName.ASK_EVENTS, v.accepted)
        // a tasks seed in the top three still blocks it
        val near = listOf(
            Seed(IntentName.ASK_TODAY, "a") to 0.0, Seed(IntentName.ASK_EVENTS, "b") to 2.0, Seed(IntentName.ASK_TASKS, "d") to 3.0,
            Seed(IntentName.ASK_TASKS, "e") to 95.0,
        )
        val s2 = SeedSnapshot(near.map { it.first }, near.map { normalized(at(it.second))!! })
        assertNull(sorter().decide(normalized(at(1.0))!!, s2)!!.accepted)
    }

    // ---- classify with a fake embedder ----

    private class Rig(hebrew: Boolean = true, val queryVec: FloatArray? = floatArrayOf(1f, 0.05f), seeded: Boolean = true, off: Boolean = false) {
        val calls = AtomicInteger()
        val embedder = TextEmbedder { text, _ ->
            if (text == "seed4") floatArrayOf(0f, 1f) else if (text.startsWith("seed")) floatArrayOf(1f, 0f) else {
                calls.incrementAndGet()
                queryVec
            }
        }
        val seedList = listOf(
            Seed(IntentName.ASK_TODAY, "seed1"), Seed(IntentName.ASK_TODAY, "seed2"), Seed(IntentName.ASK_TODAY, "seed3"),
            Seed(IntentName.ASK_TASKS, "seed4"),
        )
        val store = SeedStore(embedder, CoroutineScope(Dispatchers.Unconfined + Job()), seedList).also { if (seeded) it.startSeeding() }
        val sorter = Sorter(store, if (off) null else embedder, hebrew)
    }

    @Test
    fun classifyAnswersAndCallsTheEmbedderOnce() = runBlocking {
        val r = Rig()
        assertEquals(IntentName.ASK_TODAY, r.sorter.classify("what is up"))
        assertEquals(1, r.calls.get())
    }

    @Test
    fun notReadyOrOffGivesNullWithoutAnEmbeddingCall() = runBlocking {
        val notReady = Rig(seeded = false)
        assertNull(notReady.sorter.classify("what is up"))
        assertEquals(0, notReady.calls.get())
        val off = Rig(off = true)
        assertNull(off.sorter.classify("what is up"))
        assertEquals(0, off.calls.get())
    }

    @Test
    fun dreamTextNeverReachesTheEmbedder() = runBlocking {
        val r = Rig()
        assertNull(r.sorter.classify("what did I dream about last night"))
        assertEquals(0, r.calls.get())
    }

    @Test
    fun theHebrewSwitchBlocksHebrewText() = runBlocking {
        val off = Rig(hebrew = false)
        assertNull(off.sorter.classify("מה יש לי היום"))
        assertEquals(0, off.calls.get())
        val on = Rig(hebrew = true)
        assertEquals(IntentName.ASK_TODAY, on.sorter.classify("מה יש לי היום"))
        assertEquals(1, on.calls.get())
    }

    @Test
    fun aMissingOrBadQueryVectorGivesNull() = runBlocking {
        assertNull(Rig(queryVec = null).sorter.classify("what is up"))
        assertNull(Rig(queryVec = floatArrayOf(0f, 0f)).sorter.classify("what is up"))
        assertNull(Rig(queryVec = floatArrayOf(1f, 0f, 0f)).sorter.classify("what is up"))
    }

    @Test
    fun theShippedConstantsAreInRange() {
        assertEquals(true, SORTER_MIN_SCORE >= 0.80)
        assertEquals(true, SORTER_MIN_MARGIN >= 0.08)
    }
}
