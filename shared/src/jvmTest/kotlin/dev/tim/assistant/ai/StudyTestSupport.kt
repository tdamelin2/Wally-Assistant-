package dev.tim.assistant.ai

import dev.tim.assistant.ai.jobs.StudyJobs
import dev.tim.assistant.data.ServerConfig
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Study_file
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode

/** A fake Wally server for the study endpoints (file text and the study-set pointer) plus the jobs under test. */
class StudyRig(val rig: JobRig, val model: FakeModel) {
    var text: String = "text"
    var textStatus: HttpStatusCode = HttpStatusCode.OK
    var setStatus: HttpStatusCode = HttpStatusCode.OK
    val setCalls = mutableListOf<String>()
    var config: ServerConfig? = ServerConfig("http://server:8080", "tok")

    private val client = SyncClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/text") -> respond(text, textStatus)
                path.endsWith("/study-set") -> {
                    setCalls += (request.body as io.ktor.http.content.TextContent).text
                    respond("{}", setStatus)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )

    fun jobs() = StudyJobs(rig.repo, model, client) { config }

    fun guideJob() = dev.tim.assistant.study.StudyGuideJob(rig.repo, model, client, { config }, { rig.clock })

    fun seed(fileId: String = "sfile:a", courseId: String = "course:1", fileName: String = "week1.pdf", courseName: String = "Algebra") {
        rig.db.courseQueries.upsert(Course(courseId, 1, 0, 0, "SYNCED", 7, courseName, 1))
        rig.db.study_fileQueries.upsert(Study_file(fileId, 1, 0, 0, "SYNCED", courseId, fileName, "MOODLE", 100, "READY", null))
    }
}

/** n flash cards as the model would answer them. */
fun cardsAnswer(n: Int) =
    """{"cards":[""" + (0 until n).joinToString(",") { """{"front":"front $it","back":"back $it"}""" } + "]}"

/** Ten valid quiz questions as the model would answer them. */
fun quizAnswer() =
    """{"questions":[""" + (0 until 10).joinToString(",") {
        """{"q":"Question $it?","options":["a","b","c","d"],"answerIndex":${it % 4}}"""
    } + "]}"

/** Paragraphs of 1000 characters separated by blank lines, about [chars] characters in all. */
fun paragraphs(chars: Int): String = (0 until chars / 1002).joinToString("\n\n") { "p$it".padEnd(1000, 'x') }
