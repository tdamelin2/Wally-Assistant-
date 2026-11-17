package dev.tim.assistant.ai

import dev.tim.assistant.ai.jobs.GoalJobs
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.TickResult
import dev.tim.assistant.data.DreamBlock
import dev.tim.assistant.data.DreamWeek
import dev.tim.assistant.data.DreamWeekResult
import dev.tim.assistant.data.goalStepsJson
import dev.tim.assistant.data.GoalStep
import dev.tim.assistant.data.parseGoalSteps
import dev.tim.assistant.db.Cal_event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

class GoalJobsTest {
    private val rig = JobRig()
    private val repo = rig.repo
    private val model = FakeModel()
    private var week: DreamWeekResult = DreamWeekResult.Sent(
        DreamWeek(
            Instant.parse("2026-11-08T10:00:00Z").toEpochMilliseconds(),
            listOf(DreamBlock("b1", "Gym", 1, 9 * 60, 11 * 60), DreamBlock("b2", "Study", 3, 18 * 60, 1440, "BLUE", "ignored note")),
        ),
    )
    private var fetches = 0
    private suspend fun source(): DreamWeekResult { fetches++; return week }
    private val runner = JobRunner(
        repo, sync = {},
        handlers = GoalJobs(repo, model, { rig.clock }) { source() }.handlers(),
        clock = { rig.clock },
    )

    private fun stepsJson(n: Int, due: String? = null): String =
        (1..n).joinToString(",", """{"steps":[""", "]}") { i ->
            """{"title":"Step $i","dueDate":${if (due == null) "null" else "\"$due\""}}"""
        }

    private suspend fun goal(title: String = "Run 10k"): String = repo.saveGoal(null, title, "health", "2026-12-31")

    private suspend fun stepsJob(goalId: String): String {
        rig.clock += 1000
        return repo.queueJob("GOAL_STEPS", """{"goalId":"$goalId"}""")
    }

    @Test
    fun three_to_ten_steps_are_accepted() = runBlocking<Unit> {
        val g = goal()
        for (n in listOf(3, 6, 10)) {
            model.script = { _, _ -> Completion.Ok(stepsJson(n, "2026-11-20")) }
            val job = stepsJob(g)
            assertEquals(TickResult.Done(job), runner.tick())
            val stored = rig.job(job)
            assertEquals("DONE", stored.status)
            assertEquals(n, parseGoalSteps(stored.resultJson).size)
        }
    }

    @Test
    fun two_and_eleven_steps_fail_the_attempt() = runBlocking<Unit> {
        val g = goal()
        for (n in listOf(2, 11)) {
            model.script = { _, _ -> Completion.Ok(stepsJson(n)) }
            val job = stepsJob(g)
            assertEquals(TickResult.Retry(job), runner.tick())
            val stored = rig.job(job)
            assertEquals("QUEUED", stored.status)
            assertEquals(1, stored.attempts)
            assertEquals("bad model output", stored.error)
            assertNull(stored.resultJson)
            // finish it so the next loop starts clean
            repo.failJob(repo.claimJob(job)!!, "x", failNow = true)
        }
    }

    @Test
    fun bad_titles_dates_and_shapes_fail_the_attempt() = runBlocking<Unit> {
        val g = goal()
        val bad = listOf(
            """{"steps":[{"title":"a","dueDate":null},{"title":"  ","dueDate":null},{"title":"c","dueDate":null}]}""",
            """{"steps":[{"title":"a","dueDate":"2026-13-45"},{"title":"b","dueDate":null},{"title":"c","dueDate":null}]}""",
            """{"steps":[{"title":"a","dueDate":null},{"title":"b","dueDate":null},{"title":"${"x".repeat(201)}","dueDate":null}]}""",
            """{"nope":1}""", "not json", "",
        )
        for (text in bad) {
            model.script = { _, _ -> Completion.Ok(text) }
            val job = stepsJob(g)
            assertTrue(runner.tick() is TickResult.Retry, text)
            assertEquals("bad model output", rig.job(job).error)
            repo.failJob(repo.claimJob(job)!!, "x", failNow = true)
        }
    }

    @Test
    fun the_prompt_has_the_goal_and_open_tasks_and_asks_for_a_json_schema() = runBlocking<Unit> {
        val g = goal("Learn guitar")
        repo.saveTask(null, "buy strings", null, false)
        model.script = { _, _ -> Completion.Ok(stepsJson(3)) }
        stepsJob(g)
        runner.tick()
        val text = model.allText()
        assertTrue("Learn guitar" in text && "buy strings" in text)
        val format = model.formats.single()
        assertNotNull(format)
        assertTrue("steps" in format["properties"].toString())
    }

    @Test
    fun offline_model_keeps_the_goal_job_queued_without_an_attempt() = runBlocking<Unit> {
        val g = goal()
        model.script = { _, _ -> Completion.Offline }
        val job = stepsJob(g)
        assertEquals(TickResult.Offline(job), runner.tick())
        assertEquals(0, rig.job(job).attempts)
        assertEquals("QUEUED", rig.job(job).status)
    }

    @Test
    fun a_goal_deleted_while_the_model_thinks_fails_the_job_at_once() = runBlocking<Unit> {
        val g = goal()
        model.script = { _, _ -> repo.deleteGoal(g); Completion.Ok(stepsJson(4)) }
        val job = stepsJob(g)
        assertEquals(TickResult.Failed(job), runner.tick())
        assertEquals("FAILED", rig.job(job).status)
        assertNull(rig.job(job).resultJson)
    }

    // ---- Add selected as tasks -----------------------------------------------------------------

    @Test
    fun task_ids_are_deterministic_and_repeat_adds_are_no_ops() = runBlocking<Unit> {
        val g = goal()
        val steps = parseGoalSteps(stepsJson(4, "2026-11-20"))
        assertEquals(2, repo.addGoalStepTasks("job1", g, steps, setOf(0, 2)))
        assertEquals(0, repo.addGoalStepTasks("job1", g, steps, setOf(0, 2)))
        assertEquals(1, repo.addGoalStepTasks("job1", g, steps, setOf(0, 1, 2)))
        val ids = rig.db.taskQueries.selectAll().executeAsList().map { it.id }.sorted()
        assertEquals(listOf("task:goalstep:job1:0", "task:goalstep:job1:1", "task:goalstep:job1:2"), ids)
        val t = rig.db.taskQueries.selectById("task:goalstep:job1:2").executeAsOne()
        assertEquals(g, t.goalId)
        assertEquals("Step 3", t.title)
        assertEquals("2026-11-20", t.dueDate)
        assertEquals("PENDING", t.syncState)
    }

    @Test
    fun an_edited_done_or_deleted_task_is_never_reset_by_a_repeat_add() = runBlocking<Unit> {
        val g = goal()
        val steps = parseGoalSteps(stepsJson(3))
        repo.addGoalStepTasks("job1", g, steps, setOf(0, 1, 2))
        repo.saveTask("task:goalstep:job1:0", "my own title", "2026-12-01", false)
        repo.saveTask("task:goalstep:job1:1", "Step 2", null, true)
        repo.deleteTask("task:goalstep:job1:2")
        assertEquals(0, repo.addGoalStepTasks("job1", g, steps, setOf(0, 1, 2)))
        val q = rig.db.taskQueries
        assertEquals("my own title", q.selectById("task:goalstep:job1:0").executeAsOne().title)
        assertEquals(g, q.selectById("task:goalstep:job1:0").executeAsOne().goalId)
        assertEquals(1L, q.selectById("task:goalstep:job1:1").executeAsOne().done)
        assertEquals(1L, q.selectById("task:goalstep:job1:2").executeAsOne().deleted)
    }

    @Test
    fun the_index_is_the_original_array_index() = runBlocking<Unit> {
        val g = goal()
        val steps = parseGoalSteps(stepsJson(5))
        repo.addGoalStepTasks("j", g, steps, setOf(4))
        assertEquals(listOf("task:goalstep:j:4"), rig.db.taskQueries.selectAll().executeAsList().map { it.id })
    }

    @Test
    fun adding_to_a_deleted_goal_creates_nothing() = runBlocking<Unit> {
        val g = goal()
        repo.deleteGoal(g)
        assertEquals(0, repo.addGoalStepTasks("j", g, parseGoalSteps(stepsJson(3)), setOf(0, 1)))
    }

    @Test
    fun progress_counts_every_non_deleted_linked_task_even_old_finished_ones() = runBlocking<Unit> {
        val g = goal()
        val other = goal("Other")
        repo.saveTask("a", "a", null, false, g)
        repo.saveTask("b", "b", null, true, g)
        repo.saveTask("c", "c", null, true, g)
        repo.saveTask("d", "d", null, true, g)
        repo.saveTask("e", "e", null, true, other)
        repo.deleteTask("d")
        // "c" was finished more than 7 days ago, so the Log list hides it, but progress still counts it
        rig.clock += 30L * 24 * 3600 * 1000
        assertTrue(repo.tasksForLog().none { it.id == "c" })
        val p = repo.goalProgress(g)
        assertEquals(2, p.done)
        assertEquals(3, p.total)
        assertEquals(1, repo.goalProgress(other).total)
    }

    // ---- IDEAL_WEEK ----------------------------------------------------------------------------

    private suspend fun weekJob(input: String = "{}"): String {
        rig.clock += 1000
        return repo.queueJob("IDEAL_WEEK", input)
    }

    private fun event(id: String, title: String, startIso: String, endIso: String) = rig.db.cal_eventQueries.upsert(
        Cal_event(
            id, 1, 0, 0, "SYNCED", "primary", id, title,
            Instant.parse(startIso).toEpochMilliseconds(), Instant.parse(endIso).toEpochMilliseconds(), 0, null,
        ),
    )

    @Test
    fun ideal_week_saves_a_doc_in_a_new_plans_folder_and_the_job_points_at_it() = runBlocking<Unit> {
        model.script = { _, _ -> Completion.Ok("## Plan\n- do the thing") }
        val job = weekJob()
        assertEquals(TickResult.Done(job), runner.tick())
        val doc = rig.db.docQueries.selectById("doc:idealweek:$job").executeAsOne()
        assertEquals("Dream week coaching $TODAY", doc.title)
        assertEquals("AI", doc.source)
        assertEquals("## Plan\n- do the thing", doc.body)
        assertEquals("docfolder:plans", doc.folderId)
        assertEquals("Plans", rig.db.doc_folderQueries.selectById("docfolder:plans").executeAsOne().name)
        assertEquals("""{"docId":"doc:idealweek:$job"}""", rig.job(job).resultJson)
    }

    @Test
    fun ideal_week_uses_an_existing_live_plans_folder() = runBlocking<Unit> {
        val mine = repo.saveFolder(null, "Plans")
        model.script = { _, _ -> Completion.Ok("text") }
        val job = weekJob()
        runner.tick()
        assertEquals(mine, rig.db.docQueries.selectById("doc:idealweek:$job").executeAsOne().folderId)
        assertNull(rig.db.doc_folderQueries.selectById("docfolder:plans").executeAsOneOrNull())
    }

    @Test
    fun ideal_week_ignores_a_deleted_plans_folder() = runBlocking<Unit> {
        val old = repo.saveFolder(null, "Plans")
        repo.deleteFolder(old)
        model.script = { _, _ -> Completion.Ok("text") }
        val job = weekJob()
        runner.tick()
        val folder = rig.db.docQueries.selectById("doc:idealweek:$job").executeAsOne().folderId
        assertEquals("docfolder:plans", folder)
        assertEquals(0L, rig.db.doc_folderQueries.selectById("docfolder:plans").executeAsOne().deleted)
    }

    @Test
    fun a_repeated_completion_never_duplicates_or_resets_the_doc() = runBlocking<Unit> {
        model.script = { _, _ -> Completion.Ok("first") }
        val job = weekJob()
        runner.tick()
        repo.saveDoc("doc:idealweek:$job", "docfolder:plans", "My title", "edited by Tim")
        // the same job id runs again (e.g. it was reset and re-queued): the doc must stay as Tim left it
        rig.db.ai_jobQueries.upsert(rig.job(job).copy(status = "QUEUED", resultJson = null, startedAt = null))
        model.script = { _, _ -> Completion.Ok("second") }
        runner.tick()
        val doc = rig.db.docQueries.selectById("doc:idealweek:$job").executeAsOne()
        assertEquals("edited by Tim", doc.body)
        assertEquals("My title", doc.title)
        assertEquals(1, rig.db.docQueries.selectAll().executeAsList().size)
    }

    @Test
    fun the_week_prompt_has_blocks_events_tasks_goals_and_no_dream_journal_marker() = runBlocking<Unit> {
        // today is Mon 2026-11-09 in Jerusalem; the window is 11-09 00:00 .. 11-16 00:00 local
        event("in", "Lecture in window", "2026-11-12T08:00:00Z", "2026-11-12T09:00:00Z")
        event("edge", "Overlaps the start", "2026-11-08T21:00:00Z", "2026-11-08T23:00:00Z")
        event("out", "Next week event", "2026-11-16T08:00:00Z", "2026-11-16T09:00:00Z")
        event("past", "Yesterday event", "2026-11-07T08:00:00Z", "2026-11-07T09:00:00Z")
        repo.saveTask(null, "open task title", "2026-11-10", false)
        repo.saveTask(null, "finished task title", null, true)
        repo.saveGoal(null, "active goal title", null, null)
        repo.saveGoal(null, "dropped goal title", null, null, "DROPPED")
        model.script = { _, _ -> Completion.Ok("ok") }
        weekJob()
        runner.tick()
        val text = model.allText()
        assertTrue("- Mon 09:00-11:00 Gym" in text)
        assertTrue("- Wed 18:00-24:00 Study" in text)
        assertTrue("Dream week (last sent 2026-11-08 12:00)" in text)
        assertTrue("ignored note" !in text && "BLUE" !in text)
        assertTrue("Lecture in window" in text && "Overlaps the start" in text)
        assertTrue("Next week event" !in text && "Yesterday event" !in text)
        assertTrue("open task title" in text && "active goal title" in text)
        assertTrue("finished task title" !in text && "dropped goal title" !in text)
        assertTrue("dream" !in text.replace("Dream week", "").lowercase())
        assertEquals(1, fetches)
    }

    @Test
    fun oversized_blocks_and_tasks_still_leave_every_section() = runBlocking<Unit> {
        week = DreamWeekResult.Sent(
            DreamWeek(0, (1..400).map { DreamBlock("b$it", "Block number $it " + "x".repeat(90), 1 + it % 7, it, it + 30) }),
        )
        event("in", "Lecture in window", "2026-11-12T08:00:00Z", "2026-11-12T09:00:00Z")
        for (i in 1..400) repo.saveTask(null, "task $i " + "y".repeat(150), null, false)
        for (i in 1..200) repo.saveGoal(null, "goal $i " + "z".repeat(150), null, null)
        model.script = { _, _ -> Completion.Ok("ok") }
        weekJob()
        runner.tick()
        val user = model.prompts.single().last().content
        assertTrue(user.length <= 12_000)
        for (h in listOf("Dream week (last sent", "Calendar events, next 7 days:", "Open tasks:", "Active goals:")) {
            assertTrue(h in user, h)
        }
        assertTrue("Lecture in window" in user)
        assertTrue(Regex("- \\(\\d+ more\\)").containsMatchIn(user))
        assertTrue(!user.last().isSurrogate())
    }

    @Test
    fun not_sent_fails_at_once_with_the_message_and_no_model_call_or_doc() = runBlocking<Unit> {
        week = DreamWeekResult.NotSent
        val job = weekJob()
        assertEquals(TickResult.Failed(job), runner.tick())
        assertEquals("Send your Dream week from Wally Calendar first", rig.job(job).error)
        assertTrue(model.prompts.isEmpty())
        assertEquals(0, rig.db.docQueries.selectAll().executeAsList().size)
    }

    @Test
    fun unavailable_and_unauthorized_are_ordinary_failed_attempts() = runBlocking<Unit> {
        week = DreamWeekResult.Unavailable("HTTP 502")
        val job = weekJob()
        assertEquals(TickResult.Retry(job), runner.tick())
        week = DreamWeekResult.Unauthorized
        assertEquals(TickResult.Retry(job), runner.tick())
        val stored = rig.job(job)
        assertEquals("QUEUED", stored.status)
        assertEquals(2, stored.attempts)
        assertTrue(model.prompts.isEmpty())
    }
    @Test
    fun a_legacy_description_is_ignored() = runBlocking<Unit> {
        model.script = { _, _ -> Completion.Ok("ok") }
        val job = weekJob("""{"description":"my legacy typed week"}""")
        assertEquals(TickResult.Done(job), runner.tick())
        assertTrue("my legacy typed week" !in model.allText())
    }
    @Test
    fun steps_json_round_trips_through_the_parser() {
        val steps = listOf(GoalStep("a", "2026-11-20"), GoalStep("b", null), GoalStep("c", null))
        assertEquals(steps, parseGoalSteps(goalStepsJson(steps)))
    }
}
