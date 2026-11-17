package dev.tim.assistant.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class MealLookupLatestTest {
    private val rig = JobRig()
    private val repo = rig.repo

    @Test
    fun latest_lookup_is_the_newest_job_of_that_meal_only() = runBlocking<Unit> {
        assertNull(repo.latestMealLookup("m1"))
        rig.clock += 1000
        val first = repo.queueJob("MEAL_LOOKUP", """{"mealId":"m1"}""")
        rig.clock += 1000
        repo.queueJob("MEAL_LOOKUP", """{"mealId":"m2"}""")
        rig.clock += 1000
        repo.queueJob("DREAM_INTERPRET", """{"mealId":"m1"}""")
        assertEquals(first, repo.latestMealLookup("m1")?.id)
        rig.clock += 1000
        val second = repo.queueJob("MEAL_LOOKUP", """{"mealId":"m1"}""")
        assertEquals(second, repo.latestMealLookup("m1")?.id)
        assertEquals("QUEUED", repo.latestMealLookup("m1")?.status)
    }

    @Test
    fun unreadable_input_is_skipped() = runBlocking<Unit> {
        rig.clock += 1000
        repo.queueJob("MEAL_LOOKUP", "not json")
        assertNull(repo.latestMealLookup("m1"))
    }
}
