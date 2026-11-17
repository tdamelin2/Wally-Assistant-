package dev.tim.assistant.ai

import dev.tim.assistant.ai.jobs.GoalJobs
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.TickResult
import dev.tim.assistant.ai.jobs.UsageJobs
import dev.tim.assistant.ai.jobs.parseAppLabelSuggestions
import dev.tim.assistant.data.DreamBlock
import dev.tim.assistant.data.DreamWeek
import dev.tim.assistant.data.DreamWeekResult
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.usage.Label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AppLabelJobTest {
    private val rig = JobRig()
    private val repo = rig.repo
    private val model = FakeModel()
    private var week: DreamWeekResult = DreamWeekResult.Sent(DreamWeek(0, listOf(DreamBlock("b", "Gym", 1, 540, 600))))
    private val runner = JobRunner(
        repo, sync = {},
        handlers = UsageJobs(repo, model) { rig.clock }.handlers() + GoalJobs(repo, model, { rig.clock }) { week }.handlers(),
        clock = { rig.clock },
    )

    private fun seed(id: String, app: String, device: String = "LAPTOP", title: String? = null, date: String = TODAY, ms: Long = 60_000) =
        rig.db.usage_sessionQueries.upsert(Usage_session(id, 1, 0, 0, "SYNCED", device, app, title, 1_000, 1_000 + ms, date))

    private suspend fun labelsJob(): String {
        rig.clock += 1000
        return repo.queueJob("APP_LABELS", "{}")
    }

    @Test
    fun valid_suggestions_are_kept_with_their_device_and_nothing_is_applied() = runBlocking<Unit> {
        seed("a", "code.exe"); seed("b", "com.game", device = "PHONE")
        model.script = { _, _ ->
            Completion.Ok("""{"labels":[{"app":"code.exe","label":"PRODUCTIVE"},{"app":"com.game","label":"unproductive"}]}""")
        }
        val job = labelsJob()
        assertEquals(TickResult.Done(job), runner.tick())
        val got = parseAppLabelSuggestions(rig.job(job).resultJson)
        assertEquals(listOf("code.exe" to Label.PRODUCTIVE, "com.game" to Label.UNPRODUCTIVE), got.map { it.app to it.label })
        assertEquals(setOf("LAPTOP", "PHONE"), got.map { it.device }.toSet())
        assertTrue(repo.appRules().isEmpty())
    }

    @Test
    fun unknown_apps_bad_labels_and_duplicates_are_dropped() = runBlocking<Unit> {
        seed("a", "code.exe")
        model.script = { _, _ ->
            Completion.Ok(
                """{"labels":[{"app":"invented.exe","label":"PRODUCTIVE"},{"app":"code.exe","label":"UNSORTED"},""" +
                    """{"app":"code.exe","label":"NEUTRAL"},{"app":"code.exe","label":"PRODUCTIVE"}]}""",
            )
        }
        val job = labelsJob()
        runner.tick()
        val got = parseAppLabelSuggestions(rig.job(job).resultJson)
        assertEquals(1, got.size)
        assertEquals(Label.NEUTRAL, got[0].label)
    }

    @Test
    fun nothing_usable_or_bad_json_is_a_failed_attempt() = runBlocking<Unit> {
        seed("a", "code.exe")
        for (answer in listOf("""{"labels":[{"app":"x.exe","label":"NEUTRAL"}]}""", "not json", """{"labels":[]}""")) {
            model.script = { _, _ -> Completion.Ok(answer) }
            val job = labelsJob()
            assertTrue(runner.tick() is TickResult.Retry)
            assertEquals("bad model output", rig.job(job).error)
            assertNull(rig.job(job).resultJson)
            repo.failJob(repo.claimJob(job)!!, "x", failNow = true)
        }
    }

    @Test
    fun only_unsorted_apps_of_the_last_seven_days_are_candidates_and_only_names_are_sent() = runBlocking<Unit> {
        seed("a", "sorted.exe"); seed("b", "new.exe", title = "SECRETTITLE"); seed("c", "old.exe", date = "2026-10-01")
        repo.saveRule("EXE", "sorted.exe", Label.PRODUCTIVE)
        model.script = { _, _ -> Completion.Ok("""{"labels":[{"app":"new.exe","label":"NEUTRAL"}]}""") }
        val job = labelsJob()
        runner.tick()
        val text = model.allText()
        assertTrue("new.exe" in text)
        assertFalse("sorted.exe" in text)
        assertFalse("old.exe" in text)
        assertFalse("SECRETTITLE" in text)
        assertEquals(1, parseAppLabelSuggestions(rig.job(job).resultJson).size)
    }

    @Test
    fun candidates_are_capped_at_one_hundred() = runBlocking<Unit> {
        for (i in 1..130) seed("s$i", "app$i.exe", ms = 1000L * i)
        val jobs = UsageJobs(repo, model) { rig.clock }
        assertEquals(100, jobs.candidates().size)
    }

    @Test
    fun no_candidates_finishes_with_an_empty_list_without_calling_the_model() = runBlocking<Unit> {
        val job = labelsJob()
        assertEquals(TickResult.Done(job), runner.tick())
        assertTrue(model.prompts.isEmpty())
        assertTrue(parseAppLabelSuggestions(rig.job(job).resultJson).isEmpty())
    }

    // ---- IDEAL_WEEK totals ---------------------------------------------------------------------

    @Test
    fun ideal_week_prompt_has_label_totals_and_top_apps_without_titles() = runBlocking<Unit> {
        seed("a", "code.exe", title = "SECRETTITLE", ms = 30 * 60_000L)
        seed("b", "com.game", device = "PHONE", ms = 10 * 60_000L)
        repo.saveRule("EXE", "code.exe", Label.PRODUCTIVE)
        model.script = { _, _ -> Completion.Ok("- ok") }
        rig.clock += 1000
        repo.queueJob("IDEAL_WEEK", "{}")
        runner.tick()
        val text = model.allText()
        assertTrue("Laptop screen time, last 7 days (minutes): productive 30" in text)
        assertTrue("Phone screen time, last 7 days (minutes): productive 0, unproductive 0, neutral 0, unsorted 10" in text)
        assertTrue("Laptop top apps: code.exe 30" in text)
        assertFalse("SECRETTITLE" in text)
    }

    @Test
    fun the_totals_survive_oversized_dream_blocks() = runBlocking<Unit> {
        seed("a", "code.exe", ms = 30 * 60_000L)
        week = DreamWeekResult.Sent(DreamWeek(0, (1..1000).map { DreamBlock("b$it", "w".repeat(100), 1 + it % 7, 0, 60) }))
        model.script = { _, _ -> Completion.Ok("- ok") }
        rig.clock += 1000
        repo.queueJob("IDEAL_WEEK", "{}")
        runner.tick()
        val user = model.prompts.single().last().content
        assertTrue(user.length <= 12_000)
        assertTrue("Laptop screen time, last 7 days" in user)
        assertTrue("Laptop top apps: code.exe 30" in user)
    }

    @Test
    fun without_usage_the_prompt_has_no_screen_time_lines() = runBlocking<Unit> {
        model.script = { _, _ -> Completion.Ok("- ok") }
        rig.clock += 1000
        repo.queueJob("IDEAL_WEEK", "{}")
        runner.tick()
        assertFalse("screen time" in model.allText())
    }
}
