package dev.tim.assistant.server.codex

import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.research.RESEARCH_NUTRITION
import dev.tim.assistant.research.researchId
import dev.tim.assistant.research.templateQuestion
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class ServerMealLookupTest {
    private val rig = CodexRig()

    @AfterTest
    fun close() = rig.close()

    private suspend fun queue(mealId: String = "meal:m1", text: String = "rice and chicken") {
        rig.seedMeal(mealId, text)
        rig.seedJob("job-1", "MEAL_LOOKUP", """{"mealId":"$mealId"}""")
    }

    @Test
    fun one_food_is_split_researched_totalled_and_written_with_the_job_done() = runBlocking {
        queue()
        rig.splitInto("rice" to 200)
        assertTrue(rig.meals.tick())
        val meal = rig.meal("meal:m1")!!
        // 200 g at 130 kcal, 2.7 g protein, 28 g carbs, 0.3 g fat per 100 g, rounded once
        assertEquals(listOf(260L, 5L, 56L, 1L), listOf(meal.kcal, meal.proteinG, meal.carbsG, meal.fatG))
        assertEquals("research", meal.estimateModel)
        val job = rig.job("job-1")!!
        assertEquals("DONE", job.status)
        assertTrue(job.resultJson!!.contains("\"kcal\":260"))
        assertEquals(1, rig.runner.asks.size)
        assertEquals(1, rig.researchRows().size)
        // the meal and the job both reach a client pull
        val rows = rig.store.pull(0).rows
        assertEquals(260L, rows.first { it.table == "meal" }.data["kcal"].let { (it as JsonPrimitive).content.toLong() })
        assertEquals("DONE", (rows.first { it.table == "ai_job" }.data["status"] as JsonPrimitive).content)
        // the model request used the HP model and its context size
        val body = rig.ollama.requestJson()
        assertEquals("hp-model", (body["model"] as JsonPrimitive).content)
    }

    @Test
    fun every_food_is_checked_before_codex_is_called() = runBlocking {
        queue()
        rig.splitInto("rice" to 100, "mail me@x.example" to 50)
        rig.meals.tick()
        val job = rig.job("job-1")!!
        assertEquals("FAILED", job.status)
        assertTrue(job.error!!.startsWith("Can't look up"))
        assertTrue(rig.runner.asks.isEmpty())
        assertNull(rig.meal("meal:m1")!!.kcal)
    }

    @Test
    fun a_food_with_a_valid_research_row_costs_no_codex_call() = runBlocking {
        queue()
        rig.splitInto("rice" to 100, "beans" to 100)
        val rice = FakeCodexRunner.nutrition(RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, "rice"))
        rig.writer.write(researchSyncRow(RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, "rice"), rice, rig.now))
        rig.meals.tick()
        assertEquals("DONE", rig.job("job-1")!!.status)
        assertEquals(listOf(RESEARCH_NUTRITION to templateQuestion(RESEARCH_NUTRITION, "beans")), rig.runner.asks.toList())
        assertEquals(260L, rig.meal("meal:m1")!!.kcal)
    }

    @Test
    fun the_meal_text_is_checked_again_so_an_edit_during_the_run_writes_nothing() = runBlocking {
        queue()
        rig.splitInto("rice" to 100)
        rig.runner.handler = { category, question ->
            rig.sdb.db.mealQueries.upsert(rig.meal("meal:m1")!!.copy(text = "something else now"))
            FakeCodexRunner.nutrition(category, question)
        }
        rig.meals.tick()
        val job = rig.job("job-1")!!
        assertEquals("FAILED", job.status)
        assertEquals("Meal changed or was deleted - look it up again", job.error)
        assertNull(rig.meal("meal:m1")!!.kcal)
        // the research row for the answered food stays
        assertEquals(1, rig.researchRows().size)
    }

    @Test
    fun a_deleted_meal_fails_the_job() = runBlocking {
        rig.seedJob("job-1", "MEAL_LOOKUP", """{"mealId":"meal:gone"}""")
        rig.meals.tick()
        assertEquals("Meal not found", rig.job("job-1")!!.error)
        assertEquals("FAILED", rig.job("job-1")!!.status)
    }

    @Test
    fun the_hp_model_lock_is_held_only_while_the_meal_is_split() = runBlocking {
        queue()
        rig.splitInto("rice" to 100)
        var lockedDuringSplit = false
        var lockedDuringCodex = true
        rig.ollama.beforeReply = { lockedDuringSplit = rig.lock.isLocked }
        rig.runner.handler = { category, question ->
            lockedDuringCodex = rig.lock.isLocked
            FakeCodexRunner.nutrition(category, question)
        }
        rig.meals.tick()
        assertTrue(lockedDuringSplit)
        assertFalse(lockedDuringCodex)
        assertFalse(rig.lock.isLocked, "the lock is free when the tick ends")
        assertEquals("DONE", rig.job("job-1")!!.status)
    }

    @Test
    fun a_busy_hp_model_leaves_the_job_queued_and_unclaimed() = runBlocking {
        queue()
        rig.splitInto("rice" to 100)
        rig.lock.lock()
        assertFalse(rig.meals.tick())
        rig.lock.unlock()
        val job = rig.job("job-1")!!
        assertEquals("QUEUED", job.status)
        assertEquals(0L, job.attempts)
        assertTrue(rig.ollama.bodies.isEmpty())
        // the next tick runs it
        assertTrue(rig.meals.tick())
        assertEquals("DONE", rig.job("job-1")!!.status)
    }

    @Test
    fun an_unreachable_model_puts_the_job_back_with_the_attempts_unchanged() = runBlocking {
        queue()
        rig.ollama.reply = { 500 to "{}" }
        rig.meals.tick()
        val job = rig.job("job-1")!!
        assertEquals("QUEUED", job.status)
        assertEquals(0L, job.attempts)
        assertNull(job.startedAt)
        assertFalse(rig.lock.isLocked)
    }

    @Test
    fun a_codex_limit_fails_the_job_with_the_limit_text() = runBlocking {
        queue()
        rig.splitInto("rice" to 100)
        rig.runner.handler = { _, _ -> throw JobFailedNow(CODEX_LIMIT_TEXT) }
        rig.meals.tick()
        assertEquals("FAILED", rig.job("job-1")!!.status)
        assertEquals(CODEX_LIMIT_TEXT, rig.job("job-1")!!.error)
    }

    @Test
    fun an_error_message_about_the_usage_limit_becomes_the_limit_text() = runBlocking {
        queue()
        rig.splitInto("rice" to 100)
        rig.runner.handler = { _, _ -> throw IllegalStateException("429 Too Many Requests") }
        rig.meals.tick()
        assertEquals(CODEX_LIMIT_TEXT, rig.job("job-1")!!.error)
    }

    @Test
    fun the_whole_attempt_is_bounded_to_25_minutes() = runBlocking {
        queue()
        rig.splitInto("rice" to 100, "beans" to 100)
        rig.runner.handler = { category, question ->
            rig.now += 26 * 60_000L
            FakeCodexRunner.nutrition(category, question)
        }
        rig.meals.tick()
        val job = rig.job("job-1")!!
        assertEquals("QUEUED", job.status, "a first failed attempt goes back to the queue")
        assertEquals("Meal look up took too long", job.error)
        assertEquals(1, rig.runner.asks.size, "no second Codex call after the time ran out")
        assertNull(rig.meal("meal:m1")!!.kcal)
    }

    @Test
    fun a_bad_model_answer_is_a_failed_attempt() = runBlocking {
        queue()
        rig.ollama.reply = { 200 to dev.tim.assistant.server.ai.chatBody("not json") }
        rig.meals.tick()
        assertEquals("QUEUED", rig.job("job-1")!!.status)
        assertEquals(1L, rig.job("job-1")!!.attempts)
        assertTrue(rig.runner.asks.isEmpty())
    }

    @Test
    fun research_rows_use_the_same_id_the_client_computes() = runBlocking {
        queue()
        rig.splitInto("rice" to 100)
        rig.meals.tick()
        val id = researchId(RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, "rice"))
        assertEquals(id, rig.researchRows().single().id)
    }
}
