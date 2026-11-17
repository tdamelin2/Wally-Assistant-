package dev.tim.assistant.ai

import dev.tim.assistant.ai.jobs.JOB_STALE_MS
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.TickResult
import dev.tim.assistant.data.studySetDocId
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class StudyJobsTest {
    private val rig = JobRig()
    private val repo = rig.repo
    private val model = FakeModel()
    private val study = StudyRig(rig, model)

    private fun runner() = JobRunner(repo, sync = {}, handlers = study.jobs().handlers(), clock = { rig.clock })

    private suspend fun queueSet(fileId: String = "sfile:a"): String {
        rig.clock += 1000
        return repo.queueJob("STUDY_SET", """{"fileId":"$fileId"}""")
    }

    private fun script(summary: String = "SUMMARY", cards: Int = 12) {
        model.script = { _, format ->
            if (format != null && format.toString().contains("cards")) Completion.Ok(cardsAnswer(cards)) else Completion.Ok(summary)
        }
    }

    private fun docs() = rig.db.docQueries.selectAll().executeAsList().filter { it.deleted == 0L }
    private fun cards() = rig.db.cardQueries.selectAll().executeAsList()

    @Test
    fun a_short_file_makes_one_summary_a_doc_and_cards() = runBlocking<Unit> {
        study.seed()
        study.text = "Some short lecture text. ".repeat(40)
        script(cards = 12)
        val id = queueSet()
        assertEquals(TickResult.Done(id), runner().tick())
        assertEquals(2, model.prompts.size)
        val doc = docs().single()
        assertEquals(studySetDocId("sfile:a"), doc.id)
        assertEquals("SUMMARY", doc.body)
        assertEquals("week1.pdf", doc.title)
        assertEquals("AI", doc.source)
        val folder = rig.db.doc_folderQueries.selectById(doc.folderId!!).executeAsOne()
        assertEquals("Study – Algebra", folder.name)
        assertTrue(folder.id.startsWith("docfolder:study:") && folder.id.length == "docfolder:study:".length + 16)
        assertEquals(12, cards().size)
        assertEquals(setOf("card:sfile:a:0", "card:sfile:a:11"), setOf(cards().first().id, cards().last().id))
        assertTrue(cards().all { it.lastResult == null && it.lastReviewedAt == null && it.deleted == 0L })
        assertEquals("""{"docId":"doc:studyset:sfile:a","cards":12}""", rig.job(id).resultJson)
        assertEquals(1, study.setCalls.size)
        assertTrue(study.setCalls.single().contains("doc:studyset:sfile:a"))
    }

    @Test
    fun long_text_is_chunked_and_reduced_with_every_prompt_inside_the_limit() = runBlocking<Unit> {
        study.seed()
        study.text = paragraphs(30_000)
        script(summary = "s".repeat(500), cards = 15)
        val id = queueSet()
        assertEquals(TickResult.Done(id), runner().tick())
        // 3 chunks, 1 reduction, 1 cards call
        assertEquals(5, model.prompts.size)
        for (p in model.prompts) assertTrue(p.last().content.length <= 12_000, "prompt too long: ${p.last().content.length}")
        assertTrue(model.prompts.last().last().content.length <= 12_000)
        assertEquals(15, cards().size)
    }

    @Test
    fun summaries_that_do_not_shrink_fail_with_text_too_long() = runBlocking<Unit> {
        study.seed()
        study.text = paragraphs(30_000)
        script(summary = "s".repeat(11_500))
        val id = queueSet()
        assertEquals(TickResult.Retry(id), runner().tick())
        assertEquals("text too long", rig.job(id).error)
        assertTrue(docs().isEmpty())
        assertTrue(cards().isEmpty())
        assertTrue(study.setCalls.isEmpty())
    }

    @Test
    fun card_counts_nine_ten_forty_and_forty_one() = runBlocking<Unit> {
        study.seed()
        study.text = "text ".repeat(100)
        script(cards = 9)
        val bad = queueSet()
        assertEquals(TickResult.Retry(bad), runner().tick())
        assertEquals("bad model output", rig.job(bad).error)
        assertTrue(docs().isEmpty() && cards().isEmpty() && study.setCalls.isEmpty())
        for ((given, kept) in listOf(10 to 10, 40 to 40, 41 to 40)) {
            val rig2 = JobRig()
            val model2 = FakeModel { _, format ->
                if (format != null && format.toString().contains("cards")) Completion.Ok(cardsAnswer(given)) else Completion.Ok("S")
            }
            val s = StudyRig(rig2, model2)
            s.seed()
            s.text = "text ".repeat(100)
            rig2.clock += 1000
            val id = rig2.repo.queueJob("STUDY_SET", """{"fileId":"sfile:a"}""")
            val r = JobRunner(rig2.repo, sync = {}, handlers = s.jobs().handlers(), clock = { rig2.clock })
            assertEquals(TickResult.Done(id), r.tick())
            assertEquals(kept, rig2.db.cardQueries.selectAll().executeAsList().size, "given $given")
        }
    }

    @Test
    fun no_text_and_missing_file_fail_at_once() = runBlocking<Unit> {
        study.seed()
        study.textStatus = HttpStatusCode.Conflict
        val a = queueSet()
        assertEquals(TickResult.Failed(a), runner().tick())
        assertEquals("No text found (scanned?)", rig.job(a).error)
        study.textStatus = HttpStatusCode.NotFound
        val b = queueSet()
        assertEquals(TickResult.Failed(b), runner().tick())
        assertEquals("file missing", rig.job(b).error)
        val c = queueSet("sfile:unknown")
        assertEquals(TickResult.Failed(c), runner().tick())
        assertEquals("file missing", rig.job(c).error)
        assertTrue(model.prompts.isEmpty())
    }

    @Test
    fun a_rerun_with_fewer_cards_soft_deletes_the_extras_and_resets_marks() = runBlocking<Unit> {
        study.seed()
        study.text = "text ".repeat(100)
        script(cards = 40)
        val first = queueSet()
        assertEquals(TickResult.Done(first), runner().tick())
        repo.markCard("card:sfile:a:3", "KNEW")
        assertEquals("KNEW", rig.db.cardQueries.selectById("card:sfile:a:3").executeAsOne().lastResult)
        script(cards = 10)
        val second = queueSet()
        assertEquals(TickResult.Done(second), runner().tick())
        val live = cards().filter { it.deleted == 0L }
        assertEquals(10, live.size)
        assertEquals(30, cards().count { it.deleted == 1L })
        assertNull(rig.db.cardQueries.selectById("card:sfile:a:3").executeAsOne().lastResult)
        assertNull(rig.db.cardQueries.selectById("card:sfile:a:3").executeAsOne().lastReviewedAt)
        assertEquals(10, repo.cardsForFile("sfile:a").size)
        assertEquals(1, docs().size)
    }

    @Test
    fun a_failed_pointer_call_writes_nothing_locally() = runBlocking<Unit> {
        study.seed()
        study.text = "text ".repeat(100)
        script()
        study.setStatus = HttpStatusCode.InternalServerError
        val id = queueSet()
        assertEquals(TickResult.Retry(id), runner().tick())
        assertTrue(docs().isEmpty())
        assertTrue(cards().isEmpty())
        assertEquals("QUEUED", rig.job(id).status)
        assertEquals(1, study.setCalls.size)
    }

    @Test
    fun a_stale_claim_writes_nothing() = runBlocking<Unit> {
        study.seed()
        study.text = "text ".repeat(100)
        val id = queueSet()
        model.script = { _, format ->
            if (format != null && format.toString().contains("cards")) {
                // The job is reset (as after a crash) while the model is still answering.
                rig.clock += JOB_STALE_MS + 1
                repo.resetStaleJobs(rig.clock - JOB_STALE_MS)
                Completion.Ok(cardsAnswer(12))
            } else {
                Completion.Ok("S")
            }
        }
        runner().tick()
        assertTrue(docs().isEmpty())
        assertTrue(cards().isEmpty())
        assertEquals("QUEUED", rig.job(id).status)
    }

    @Test
    fun quiz_uses_study_set_docs_and_writes_one_quiz_with_done() = runBlocking<Unit> {
        study.seed()
        study.text = "text ".repeat(100)
        script()
        assertTrue(runner().tick() is TickResult.Idle)
        val set = queueSet()
        assertEquals(TickResult.Done(set), runner().tick())
        model.prompts.clear()
        model.script = { _, _ -> Completion.Ok(quizAnswer()) }
        rig.clock += 1000
        val quiz = repo.queueJob("QUIZ", """{"courseId":"course:1"}""")
        assertEquals(TickResult.Done(quiz), runner().tick())
        assertEquals(1, model.prompts.size)
        assertTrue(model.prompts.single().last().content.contains("SUMMARY"))
        assertEquals("""{"quizId":"quiz:$quiz"}""", rig.job(quiz).resultJson)
        val row = rig.db.quizQueries.selectById("quiz:$quiz").executeAsOne()
        assertEquals("course:1", row.courseId)
        assertNull(row.score)
        assertEquals(10, dev.tim.assistant.study.quizQuestionsFrom(row.questionsJson).size)
        assertNotNull(repo.quizById("quiz:$quiz"))
    }

    @Test
    fun quiz_validation_and_missing_study_set() = runBlocking<Unit> {
        study.seed()
        rig.clock += 1000
        val none = repo.queueJob("QUIZ", """{"courseId":"course:1"}""")
        assertEquals(TickResult.Failed(none), runner().tick())
        assertEquals("Make a study set first", rig.job(none).error)

        study.text = "text ".repeat(100)
        script()
        val set = queueSet()
        assertEquals(TickResult.Done(set), runner().tick())
        model.script = { _, _ -> Completion.Ok(quizAnswer().replace("\"answerIndex\":1", "\"answerIndex\":7")) }
        rig.clock += 1000
        val bad = repo.queueJob("QUIZ", """{"courseId":"course:1"}""")
        assertEquals(TickResult.Retry(bad), runner().tick())
        assertEquals("bad model output", rig.job(bad).error)
        assertTrue(rig.db.quizQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun quiz_input_is_capped_and_newest_doc_comes_first() = runBlocking<Unit> {
        study.seed()
        study.seed("sfile:b", "course:1", "week2.pdf")
        study.text = "text ".repeat(100)
        model.script = { _, format ->
            if (format != null && format.toString().contains("cards")) Completion.Ok(cardsAnswer(10)) else Completion.Ok("OLD".repeat(6000))
        }
        assertEquals(TickResult.Done(queueSet("sfile:a")), runner().tick())
        model.script = { _, format ->
            if (format != null && format.toString().contains("cards")) Completion.Ok(cardsAnswer(10)) else Completion.Ok("NEWDOC")
        }
        assertEquals(TickResult.Done(queueSet("sfile:b")), runner().tick())
        model.prompts.clear()
        model.script = { _, _ -> Completion.Ok(quizAnswer()) }
        rig.clock += 1000
        repo.queueJob("QUIZ", """{"courseId":"course:1"}""")
        runner().tick()
        val data = model.prompts.single().last().content
        assertTrue(data.length <= 12_000)
        assertTrue(data.startsWith("NEWDOC"))
    }
}
