package dev.tim.assistant.study

import dev.tim.assistant.ai.Completion
import dev.tim.assistant.ai.FakeModel
import dev.tim.assistant.ai.JobRig
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.TickResult
import dev.tim.assistant.ai.paragraphs
import dev.tim.assistant.data.ServerConfig
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.data.studySetDocId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Doc
import dev.tim.assistant.db.Study_file
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class StudyGuideJobTest {
    private val rig = JobRig()
    private val repo = rig.repo
    private val model = FakeModel { _, _ -> Completion.Ok("# Guide v1") }
    private val textCalls = mutableListOf<String>()
    private var statuses = mutableMapOf<String, HttpStatusCode>()
    private var texts = mutableMapOf<String, String>()
    private var config: ServerConfig? = ServerConfig("http://server:8080", "tok")
    private val client = SyncClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            val id = path.substringAfter("/study/files/", "").substringBefore("/text").decodeURLPart()
            textCalls += id
            respond(texts[id] ?: "raw text of $id", statuses[id] ?: HttpStatusCode.OK)
        },
    )
    private val jobs = JobRunner(repo, sync = {}, handlers = StudyGuideJob(repo, model, client, { config }) { rig.clock }.handlers(), clock = { rig.clock })

    private fun seedCourse(courseId: String = "course:1", name: String = "Algebra", files: Int = 1, summaries: Boolean = true) {
        rig.db.courseQueries.upsert(Course(courseId, 1, 0, 0, "SYNCED", 7, name, 1))
        for (i in 0 until files) {
            val fileId = "sfile:$courseId:$i"
            rig.db.study_fileQueries.upsert(Study_file(fileId, 1, 0, 0, "SYNCED", courseId, "week$i.pdf", "MOODLE", 100, "READY", null))
            if (summaries) {
                rig.db.docQueries.upsert(Doc(studySetDocId(fileId), 1, 0, 0, "SYNCED", null, "week$i.pdf", "Summary of week $i", "AI"))
            }
        }
    }

    private suspend fun queue(courseId: String) = repo.queueJob("STUDY_GUIDE", """{"courseId":"$courseId"}""")

    private fun guideDocs() = repo.docs().filter { it.id.startsWith("doc:studyguide:") }

    @Test
    fun createsOneDocInTheStudyGuidesFolderFromTheSummaries() = runBlocking<Unit> {
        seedCourse(files = 2)
        val job = queue("course:1")
        assertEquals(TickResult.Done(job), jobs.tick())
        val doc = repo.docById("doc:studyguide:course:1")!!
        assertEquals("Study guide: Algebra", doc.title)
        assertEquals("# Guide v1", doc.body)
        assertEquals("AI", doc.source)
        assertEquals("Study guides", repo.folders().single { it.id == doc.folderId }.name)
        assertTrue(model.allText().contains("Summary of week 0") && model.allText().contains("Summary of week 1"))
        assertEquals("DONE", rig.job(job).status)
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun runningAgainReplacesTheBodyAndKeepsOneDocAndOneFolder() = runBlocking<Unit> {
        seedCourse()
        queue("course:1")
        jobs.tick()
        model.script = { _, _ -> Completion.Ok("# Guide v2") }
        rig.clock += 60_000
        queue("course:1")
        jobs.tick()
        assertEquals(listOf("# Guide v2"), guideDocs().map { it.body })
        assertEquals(1, repo.folders().count { it.name == "Study guides" })
    }

    @Test
    fun anExistingStudyGuidesFolderIsReusedAndAMovedDocStaysWhereItIs() = runBlocking<Unit> {
        seedCourse()
        val folder = repo.saveFolder(null, "Study guides")
        queue("course:1")
        jobs.tick()
        assertEquals(folder, repo.docById("doc:studyguide:course:1")!!.folderId)
        assertEquals(1, repo.folders().count { it.name == "Study guides" })
        val other = repo.saveFolder(null, "Mine")
        repo.moveDoc("doc:studyguide:course:1", other)
        rig.clock += 60_000
        queue("course:1")
        jobs.tick()
        assertEquals(other, repo.docById("doc:studyguide:course:1")!!.folderId)
    }

    @Test
    fun anUnknownCourseFailsAtOnceWithoutCallingTheModel() = runBlocking<Unit> {
        val job = queue("course:nope")
        assertEquals(TickResult.Failed(job), jobs.tick())
        assertEquals("FAILED", rig.job(job).status)
        assertEquals(0, model.prompts.size)
        assertTrue(guideDocs().isEmpty())
    }

    @Test
    fun aCourseWithoutFilesFailsAtOnce() = runBlocking<Unit> {
        seedCourse(files = 0)
        val job = queue("course:1")
        assertTrue(jobs.tick() is TickResult.Failed)
        assertEquals("FAILED", rig.job(job).status)
        assertEquals(0, model.prompts.size)
        assertTrue(guideDocs().isEmpty())
    }

    @Test
    fun aFileWithoutASummaryIsFetchedAndSummarisedFirst() = runBlocking<Unit> {
        seedCourse(files = 2, summaries = false)
        repo.saveDoc(studySetDocId("sfile:course:1:0"), null, "week0.pdf", "Existing summary 0", "AI")
        val job = queue("course:1")
        assertEquals(TickResult.Done(job), jobs.tick())
        assertEquals(listOf("sfile:course:1:1"), textCalls)
        // one summary call for file 1, one guide call
        assertEquals(2, model.prompts.size)
        val guidePrompt = model.prompts.last().last().content
        assertTrue(guidePrompt.contains("Existing summary 0"))
        assertTrue(model.prompts.first().last().content.contains("raw text of sfile:course:1:1"))
        assertEquals("# Guide v1", repo.docById("doc:studyguide:course:1")!!.body)
    }

    @Test
    fun aFileWithNoTextIsSkippedAndTheRestStillMakeTheGuide() = runBlocking<Unit> {
        seedCourse(files = 2, summaries = false)
        statuses["sfile:course:1:0"] = HttpStatusCode.Conflict
        val job = queue("course:1")
        assertEquals(TickResult.Done(job), jobs.tick())
        assertEquals(2, model.prompts.size)
        assertTrue(!model.allText().contains("week0.pdf\nraw text"))
        assertTrue(guideDocs().size == 1)
    }

    @Test
    fun allFilesWithoutTextFailAtOnce() = runBlocking<Unit> {
        seedCourse(files = 2, summaries = false)
        statuses["sfile:course:1:0"] = HttpStatusCode.Conflict
        statuses["sfile:course:1:1"] = HttpStatusCode.NotFound
        val job = queue("course:1")
        assertTrue(jobs.tick() is TickResult.Failed)
        assertEquals("FAILED", rig.job(job).status)
        assertEquals(0, model.prompts.size)
        assertTrue(guideDocs().isEmpty())
    }

    @Test
    fun noServerConfigFailsAtOnce() = runBlocking<Unit> {
        seedCourse(summaries = false)
        config = null
        val job = queue("course:1")
        assertTrue(jobs.tick() is TickResult.Failed)
        assertEquals("FAILED", rig.job(job).status)
        assertEquals(0, model.prompts.size)
        assertTrue(textCalls.isEmpty())
    }

    @Test
    fun aServerErrorRetriesInsteadOfFailing() = runBlocking<Unit> {
        seedCourse(summaries = false)
        statuses["sfile:course:1:0"] = HttpStatusCode.InternalServerError
        val job = queue("course:1")
        assertEquals(TickResult.Retry(job), jobs.tick())
        assertTrue(guideDocs().isEmpty())
    }

    @Test
    fun theModelCallBudgetStopsAHugeCourse() = runBlocking<Unit> {
        seedCourse(files = 3, summaries = false)
        for (i in 0 until 3) texts["sfile:course:1:$i"] = paragraphs(300_000)
        model.script = { _, _ -> Completion.Ok("s") }
        val job = queue("course:1")
        assertEquals(TickResult.Retry(job), jobs.tick())
        assertEquals("text too long", rig.job(job).error)
        assertTrue(model.prompts.size <= 60)
        assertTrue(guideDocs().isEmpty())
    }

    @Test
    fun badInputFails() = runBlocking<Unit> {
        val job = repo.queueJob("STUDY_GUIDE", """{"nope":1}""")
        assertTrue(jobs.tick() is TickResult.Failed)
        assertEquals("FAILED", rig.job(job).status)
    }
}
