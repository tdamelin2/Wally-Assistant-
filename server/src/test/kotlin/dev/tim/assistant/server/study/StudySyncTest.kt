package dev.tim.assistant.server.study

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import dev.tim.assistant.server.TOKEN
import dev.tim.assistant.server.module
import dev.tim.assistant.server.testJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Phase 9 AC-3: Moodle listing, extraction, uploads and the study routes, with a scripted Moodle (MockEngine). */
class StudySyncTest {
    private val pdf = resourceBytes("sample.pdf")
    private val pptx = resourceBytes("sample.pptx")
    private val scanned = resourceBytes("scanned.pdf")

    private fun twoCourses() = listOf(
        FakeCourse(
            100, "Biology",
            files = listOf(
                FakeFile("Lecture 1.pdf", pdf.size.toLong(), pdf),
                FakeFile("Slides.PPTX ", pptx.size.toLong(), pptx, moduleType = "folder"),
                FakeFile("notes.docx", 10, ByteArray(10)),
                FakeFile("Scan.pdf", scanned.size.toLong(), scanned),
                FakeFile("Huge.pdf", MAX_STUDY_FILE_BYTES + 1, null),
            ),
        ),
        FakeCourse(200, "Chemistry", files = listOf(FakeFile("Chem.pdf", pdf.size.toLong(), pdf))),
    )

    private fun StudyRig.app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, store, moodleStatus = status, study = study) }
        try {
            block()
        } finally {
            close()
        }
    }

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse =
        client.get("/v1/study$path") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String): HttpResponse =
        client.post("/v1/study$path") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.putJson(path: String, body: String): HttpResponse =
        client.put("/v1/study$path") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.upload(course: String?, name: String, bytes: ByteArray): HttpResponse =
        client.submitFormWithBinaryData(
            "/v1/study/upload",
            formData {
                if (course != null) append("courseId", course)
                append(
                    "file", bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                    },
                )
            },
        ) { header(HttpHeaders.Authorization, "Bearer $TOKEN") }

    private suspend fun ApplicationTestBuilder.health(): String =
        client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.bodyAsText()

    private fun idFrom(body: String): String = (testJson.parseToJsonElement(body) as JsonObject)["fileId"]!!.let { (it as JsonPrimitive).content }

    private fun enable(rig: StudyRig, moodleId: Long) = runBlocking { rig.study.setCourseEnabled("course:$moodleId", true) }

    // ---- listing -----------------------------------------------------------------------------------

    @Test
    fun checkListsOnlyEnabledCoursesPdfAndPptxOnceAndSkipsHugeAndWrongTypes() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            val first = rig.study.check() as CheckOutcome.Done
            assertEquals(2, first.courses)
            assertEquals(0, first.newFiles) // both courses start off
            assertTrue(rig.fileRows().isEmpty())
            assertEquals(listOf(false, false), rig.courseRows().map { it.enabled != 0L })
            rig.study.setCourseEnabled("course:100", true)
            rig.study.idle()
            val names = rig.fileRows().map { it.name }.sorted()
            assertEquals(listOf("Huge.pdf", "Lecture 1.pdf", "Scan.pdf", "Slides.PPTX "), names)
            val again = rig.study.check() as CheckOutcome.Done
            rig.study.idle()
            assertEquals(0, again.newFiles)
            assertEquals(4, rig.fileRows().size)
            assertTrue(rig.fileRows().none { it.courseId == "course:200" })
            assertTrue(rig.fileRows().none { it.name.endsWith(".docx") })
        }
        rig.close()
    }

    @Test
    fun extractionResultsPerFileAndHugeFileFailsWithoutDownload() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            rig.study.check()
            rig.study.setCourseEnabled("course:100", true)
            rig.study.idle()
            val byName = rig.fileRows().associateBy { it.name }
            assertEquals("READY", byName.getValue("Lecture 1.pdf").textStatus)
            assertEquals("READY", byName.getValue("Slides.PPTX ").textStatus)
            assertEquals("FAILED", byName.getValue("Scan.pdf").textStatus)
            assertEquals("FAILED", byName.getValue("Huge.pdf").textStatus)
            assertEquals(3, moodle.downloadCalls.get()) // Huge.pdf is never downloaded
            val text = rig.files.text(byName.getValue("Lecture 1.pdf").id).readText()
            assertTrue(text.contains("Photosynthesis") && text.contains("Mitochondria"))
            val slides = rig.files.text(byName.getValue("Slides.PPTX ").id).readText()
            assertTrue(slides.contains("osmosis"))
            assertTrue(slides.contains("Groupword"), "text in a group shape")
            assertTrue(slides.contains("Tableword"), "text in a table")
            assertTrue(slides.contains("Notesword"), "text only in the notes")
            assertTrue(byName.values.all { it.origin == "MOODLE" })
        }
        rig.close()
    }

    @Test
    fun enablingStartsACheckDisablingKeepsFilesAndACheckKeepsEnabled() = rigApp(twoCourses()) { rig ->
        rig.study.check()
        assertEquals(HttpStatusCode.NotFound, putJson("/courses/course:999/enabled", """{"enabled":true}""").status)
        assertEquals(HttpStatusCode.BadRequest, putJson("/courses/course:100/enabled", """{"enabled":"yes"}""").status)
        assertEquals(HttpStatusCode.OK, putJson("/courses/course:100/enabled", """{"enabled":true}""").status)
        rig.study.idle()
        assertEquals(4, rig.fileRows().size)
        assertEquals(HttpStatusCode.OK, putJson("/courses/course:100/enabled", """{"enabled":false}""").status)
        rig.study.idle()
        assertEquals(4, rig.fileRows().size, "disabling keeps the listed files")
        rig.study.check()
        rig.study.idle()
        val enabled = rig.courseRows().associate { it.id to (it.enabled != 0L) }
        assertEquals(mapOf("course:100" to false, "course:200" to false), enabled)
        putJson("/courses/course:200/enabled", """{"enabled":true}""")
        rig.study.idle()
        rig.study.check()
        rig.study.idle()
        assertEquals(mapOf("course:100" to false, "course:200" to true), rig.courseRows().associate { it.id to (it.enabled != 0L) })
        assertTrue(rig.fileRows().any { it.courseId == "course:200" })
    }

    private fun rigApp(courses: List<FakeCourse>, block: suspend ApplicationTestBuilder.(StudyRig) -> Unit) {
        val rig = StudyRig(FakeMoodle().also { it.courses = courses }, withToken = true)
        rig.app { block(rig) }
    }

    @Test
    fun hiddenCoursesGetNoFilesEvenWhenSwitchedOn() {
        val moodle = FakeMoodle().also { it.courses = listOf(FakeCourse(5, "Old", visible = 0, files = listOf(FakeFile("a.pdf", 5, pdf)))) }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            rig.study.check()
            rig.study.setCourseEnabled("course:5", true)
            rig.study.idle()
            assertEquals(1, rig.courseRows().size)
            assertTrue(rig.fileRows().isEmpty())
        }
        rig.close()
    }

    @Test
    fun unchangedCourseAndFileRowsAreNotRewritten() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            rig.study.check()
            rig.study.setCourseEnabled("course:100", true)
            rig.study.idle()
            val seqs = (rig.courseRows().map { it.id to it.serverSeq } + rig.fileRows().map { it.id to it.serverSeq }).toMap()
            rig.study.check()
            rig.study.idle()
            val after = (rig.courseRows().map { it.id to it.serverSeq } + rig.fileRows().map { it.id to it.serverSeq }).toMap()
            assertEquals(seqs, after)
        }
        rig.close()
    }

    // ---- Moodle connection --------------------------------------------------------------------------

    @Test
    fun badLinkIs400AndNothingIsWritten() {
        val rig = StudyRig(FakeMoodle())
        rig.app {
            val r = postJson("/moodle-token", """{"link":"hello"}""")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals("""{"error":"bad link"}""", r.bodyAsText())
            assertFalse(rig.tokenStore.exists())
            assertEquals(HttpStatusCode.BadRequest, postJson("/moodle-token", """{"nope":1}""").status)
        }
    }

    @Test
    fun goodLinkStoresTokenReturnsCourseCountAndMarksMoodleOk() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle)
        rig.app {
            assertTrue(health().contains("\"moodleOk\":null"))
            val r = postJson("/moodle-token", """{"link":"${moodleLink(scheme = "wally")}"}""")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("""{"courses":2}""", r.bodyAsText())
            assertEquals(GOOD_TOKEN, rig.tokenStore.read())
            rig.study.idle()
            assertTrue(health().contains("\"moodleOk\":true"))
            assertTrue(health().contains("\"moodleBaseUrl\":\"$MOODLE_BASE\""), "trailing slash trimmed, no token")
            assertFalse(health().contains(GOOD_TOKEN))
            assertEquals(2, rig.courseRows().size, "the post-login check ran")
        }
    }

    @Test
    fun rejectedTokenIs502AndNoFileIsWritten() {
        val moodle = FakeMoodle().also { it.validToken = "another" }
        val rig = StudyRig(moodle)
        rig.app {
            val r = postJson("/moodle-token", """{"link":"${moodleLink()}"}""")
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertEquals("""{"error":"moodle rejected token"}""", r.bodyAsText())
            assertFalse(rig.tokenStore.exists())
        }
    }

    @Test
    fun invalidTokenDuringACheckSetsMoodleOkFalseAndPausesTheDailyCheck() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true)
        rig.app {
            assertEquals(CheckOutcome.Done(2, 0), rig.study.check())
            assertTrue(health().contains("\"moodleOk\":true"))
            moodle.validToken = "changed"
            assertEquals(CheckOutcome.Rejected, rig.study.check())
            assertTrue(health().contains("\"moodleOk\":false"))
            val calls = moodle.siteInfoCalls.get()
            rig.study.tick() // paused while false
            assertEquals(calls, moodle.siteInfoCalls.get())
        }
    }

    @Test
    fun noTokenFileMeansMoodleOkNullAndStartupWithATokenSetsIt() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val none = StudyRig(moodle)
        none.app {
            none.study.start()
            none.study.idle()
            assertTrue(health().contains("\"moodleOk\":null"))
        }
        val with = StudyRig(moodle, withToken = true)
        with.app {
            with.study.start()
            with.study.idle()
            assertTrue(health().contains("\"moodleOk\":true"))
        }
        val rejected = StudyRig(FakeMoodle().also { it.validToken = "no" }, withToken = true)
        rejected.app {
            rejected.study.start()
            rejected.study.idle()
            assertTrue(health().contains("\"moodleOk\":false"))
        }
    }

    @Test
    fun withoutMoodleConfigTokenAndSyncNowAre503ButUploadStillWorks() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false)
        rig.app {
            val t = postJson("/moodle-token", """{"link":"${moodleLink()}"}""")
            assertEquals(HttpStatusCode.ServiceUnavailable, t.status)
            assertEquals("""{"error":"moodle not configured"}""", t.bodyAsText())
            assertEquals(HttpStatusCode.ServiceUnavailable, postJson("/sync-now", "{}").status)
            val h = health()
            assertTrue(h.contains("\"moodleOk\":null") && h.contains("\"moodleBaseUrl\":null"))
            assertEquals(HttpStatusCode.Created, upload("Notes", "a.pdf", pdf).status)
        }
    }

    @Test
    fun syncNowReturnsNewFilesAndIs409WhileACheckRuns() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true)
        rig.app {
            runBlocking { rig.study.check(); rig.study.setCourseEnabled("course:200", true) }
            rig.study.idle()
            val gate = CompletableDeferred<Unit>()
            moodle.siteInfoGate = gate
            val before = moodle.siteInfoCalls.get()
            val running = rig.scope.launch { rig.study.check() }
            while (moodle.siteInfoCalls.get() <= before) kotlinx.coroutines.delay(10)
            val busy = postJson("/sync-now", "{}")
            assertEquals(HttpStatusCode.Conflict, busy.status)
            gate.complete(Unit)
            running.join()
            moodle.siteInfoGate = null
            rig.study.setCourseEnabled("course:100", true)
            rig.study.idle()
            val r = postJson("/sync-now", "{}")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("""{"newFiles":0}""", r.bodyAsText())
        }
    }

    // ---- uploads ------------------------------------------------------------------------------------

    @Test
    fun uploadCreatesPendingRowThenReadyWithTextAndOriginal() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false)
        rig.app {
            val r = upload("Biology notes", "My Lecture.pdf", pdf)
            assertEquals(HttpStatusCode.Created, r.status)
            val id = idFrom(r.bodyAsText())
            assertTrue(id.startsWith("sfile:upload:"))
            rig.study.idle()
            val row = rig.fileRows().single()
            assertEquals("READY", row.textStatus)
            assertEquals("UPLOAD", row.origin)
            assertEquals("My Lecture.pdf", row.name)
            assertEquals(pdf.size.toLong(), row.sizeBytes)
            val text = get("/files/$id/text")
            assertEquals(HttpStatusCode.OK, text.status)
            assertTrue(text.contentType().toString().startsWith("text/plain"))
            assertTrue(text.bodyAsText().contains("Photosynthesis"))
            val original = get("/files/$id/original")
            assertEquals(HttpStatusCode.OK, original.status)
            assertEquals("application/pdf", original.contentType().toString())
            assertContentEquals(pdf, original.readRawBytes())
            assertTrue(original.headers[HttpHeaders.ContentDisposition]!!.contains("My Lecture.pdf"))
        }
    }

    @Test
    fun uploadedPptxIsExtracted() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false)
        rig.app {
            val id = idFrom(upload("Deck", "deck.pptx", pptx).bodyAsText())
            rig.study.idle()
            val original = get("/files/$id/original")
            assertTrue(original.contentType().toString().contains("presentationml"))
            assertTrue(get("/files/$id/text").bodyAsText().contains("Notesword"))
        }
    }

    @Test
    fun scannedPdfIsFailedAndItsTextIs409() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false)
        rig.app {
            val id = idFrom(upload("Scans", "scan.pdf", scanned).bodyAsText())
            rig.study.idle()
            assertEquals("FAILED", rig.fileRows().single().textStatus)
            assertEquals(HttpStatusCode.Conflict, get("/files/$id/text").status)
        }
    }

    @Test
    fun uploadRefusesWrongTypeAndWrongMagicBytes() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false)
        rig.app {
            assertEquals(HttpStatusCode.UnsupportedMediaType, upload("C", "notes.docx", pdf).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, upload("C", "fake.pdf", "just text".toByteArray()).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, upload("C", "fake.pptx", pdf).status)
            assertEquals(HttpStatusCode.BadRequest, upload(null, "a.pdf", pdf).status)
            assertEquals(HttpStatusCode.BadRequest, upload("  ", "a.pdf", pdf).status)
            assertTrue(rig.fileRows().isEmpty())
            assertTrue(rig.courseRows().isEmpty())
        }
    }

    @Test
    fun uploadCapIsExactlyFiftyMiBAndOneByteMoreIs413() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false, extract = { _, _ -> Extraction.NoText })
        rig.app {
            val exact = ByteArray(MAX_STUDY_FILE_BYTES.toInt()).also { "%PDF-".toByteArray().copyInto(it) }
            assertEquals(HttpStatusCode.Created, upload("Big", "exact.pdf", exact).status)
            rig.study.idle()
            val over = ByteArray(MAX_STUDY_FILE_BYTES.toInt() + 1).also { "%PDF-".toByteArray().copyInto(it) }
            assertEquals(HttpStatusCode.PayloadTooLarge, upload("Big", "over.pdf", over).status)
            assertEquals(1, rig.fileRows().size)
            assertTrue(File(rig.dir, "study").listFiles()!!.none { it.name.startsWith("tmp-") }, "no temp file left")
        }
    }

    @Test
    fun manualCourseIsReusedByNameIgnoringCaseAndExistingIdsAreUsed() {
        val rig = StudyRig(FakeMoodle().also { it.courses = twoCourses() }, withToken = true)
        rig.app {
            val a = idFrom(upload("My Notes", "a.pdf", pdf).bodyAsText())
            val b = idFrom(upload("my notes", "b.pdf", pdf).bodyAsText())
            rig.study.idle()
            val manual = rig.courseRows().filter { it.moodleId == null }
            assertEquals(1, manual.size)
            assertTrue(manual.single().id.startsWith("course:manual:"))
            assertEquals(1L, manual.single().enabled)
            assertEquals(manual.single().id, rig.fileRows().first { it.id == a }.courseId)
            assertEquals(manual.single().id, rig.fileRows().first { it.id == b }.courseId)
            rig.study.check()
            val c = idFrom(upload("course:100", "c.pdf", pdf).bodyAsText())
            rig.study.idle()
            assertEquals("course:100", rig.fileRows().first { it.id == c }.courseId)
            assertEquals(1, rig.courseRows().count { it.moodleId == null })
        }
    }

    // ---- text / original / study-set / delete --------------------------------------------------------

    @Test
    fun unknownFilesAre404() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false)
        rig.app {
            assertEquals(HttpStatusCode.NotFound, get("/files/sfile:nope/text").status)
            assertEquals(HttpStatusCode.NotFound, get("/files/sfile:nope/original").status)
            assertEquals(HttpStatusCode.NotFound, postJson("/files/sfile:nope/study-set", """{"docId":"doc:x"}""").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/v1/study/files/sfile:nope") {
                header(HttpHeaders.Authorization, "Bearer $TOKEN")
            }.status)
        }
    }

    @Test
    fun studySetPointerIsStoredAndTheSameDocIdAgainIsANoOp() {
        val rig = StudyRig(FakeMoodle(), moodleConfigured = false)
        rig.app {
            val id = idFrom(upload("C", "a.pdf", pdf).bodyAsText())
            rig.study.idle()
            assertEquals(HttpStatusCode.BadRequest, postJson("/files/$id/study-set", """{"docId":""}""").status)
            assertEquals(HttpStatusCode.OK, postJson("/files/$id/study-set", """{"docId":"doc:studyset:1"}""").status)
            val first = rig.fileRows().single()
            assertEquals("doc:studyset:1", first.studySetDocId)
            assertEquals(HttpStatusCode.OK, postJson("/files/$id/study-set", """{"docId":"doc:studyset:1"}""").status)
            val second = rig.fileRows().single()
            assertEquals(first.serverSeq, second.serverSeq)
            assertEquals(first.updatedAt, second.updatedAt)
            assertEquals(HttpStatusCode.OK, postJson("/files/$id/study-set", """{"docId":"doc:studyset:2"}""").status)
            assertTrue(rig.fileRows().single().serverSeq > first.serverSeq)
        }
    }

    @Test
    fun deleteRemovesAnUploadAndBothDiskFilesButNotAMoodleFile() {
        val rig = StudyRig(FakeMoodle().also { it.courses = twoCourses() }, withToken = true)
        rig.app {
            val id = idFrom(upload("C", "a.pdf", pdf).bodyAsText())
            rig.study.idle()
            assertTrue(rig.files.hasAll(id))
            val del = client.delete("/v1/study/files/$id") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.OK, del.status)
            val row = rig.fileRows().single { it.id == id }
            assertEquals(1L, row.deleted)
            assertFalse(rig.files.original(id).exists() || rig.files.text(id).exists())
            assertEquals(HttpStatusCode.NotFound, get("/files/$id/text").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/v1/study/files/$id") {
                header(HttpHeaders.Authorization, "Bearer $TOKEN")
            }.status)
            rig.study.check()
            rig.study.setCourseEnabled("course:200", true)
            rig.study.idle()
            val moodleId = rig.fileRows().first { it.origin == "MOODLE" }.id
            assertEquals(HttpStatusCode.Conflict, client.delete("/v1/study/files/$moodleId") {
                header(HttpHeaders.Authorization, "Bearer $TOKEN")
            }.status)
            assertEquals(0L, rig.fileRows().first { it.id == moodleId }.deleted)
        }
    }

    @Test
    fun aDeletedMoodleRowIsNeverRelisted() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            rig.study.check()
            rig.study.setCourseEnabled("course:200", true)
            rig.study.idle()
            val id = rig.fileRows().single().id
            rig.sdb.writeLock.withLock {
                rig.sdb.db.study_fileQueries.selectById(id).executeAsOne().let {
                    rig.sdb.db.study_fileQueries.upsert(it.copy(deleted = 1L, updatedAt = it.updatedAt + 1))
                }
            }
            val outcome = rig.study.check() as CheckOutcome.Done
            rig.study.idle()
            assertEquals(0, outcome.newFiles)
            assertEquals(1L, rig.fileRows().single().deleted)
        }
        rig.close()
    }

    // ---- recovery and policies ----------------------------------------------------------------------

    @Test
    fun readyMoodleFileMissingOnDiskIsDownloadedAgain() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            rig.study.check()
            rig.study.setCourseEnabled("course:200", true)
            rig.study.idle()
            val id = rig.fileRows().single().id
            assertEquals("READY", rig.fileRows().single().textStatus)
            rig.files.delete(id)
            val downloads = moodle.downloadCalls.get()
            rig.study.check()
            rig.study.idle()
            assertEquals("READY", rig.fileRows().single().textStatus)
            assertTrue(rig.files.hasAll(id))
            assertEquals(downloads + 1, moodle.downloadCalls.get())
        }
        rig.close()
    }

    @Test
    fun pendingMoodleFileIsResumedAfterAFailedDownload() {
        val moodle = FakeMoodle().also { it.courses = twoCourses(); it.downloadFails = true }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            rig.study.check()
            rig.study.setCourseEnabled("course:200", true)
            rig.study.idle()
            assertEquals("PENDING", rig.fileRows().single().textStatus)
            moodle.downloadFails = false
            rig.study.start() // a restart: the unfinished file is found and a check re-lists it
            rig.study.idle()
            assertEquals("READY", rig.fileRows().single().textStatus)
        }
        rig.close()
    }

    @Test
    fun theDownloadSizeIsCountedOnTheStreamNotTheDeclaredSize() {
        val big = ByteArray((MAX_STUDY_FILE_BYTES + 1).toInt())
        val moodle = FakeMoodle().also {
            it.courses = listOf(FakeCourse(9, "Lying", files = listOf(FakeFile("liar.pdf", 100, big))))
        }
        val rig = StudyRig(moodle, withToken = true)
        runBlocking {
            rig.study.check()
            rig.study.setCourseEnabled("course:9", true)
            rig.study.idle()
            assertEquals("FAILED", rig.fileRows().single().textStatus)
            assertFalse(rig.files.original(rig.fileRows().single().id).exists())
        }
        rig.close()
    }

    @Test
    fun tokenIsSentOnlyToTheConfiguredSite() {
        val moodle = FakeMoodle().also {
            it.courses = listOf(FakeCourse(9, "X", files = listOf(FakeFile("a.pdf", 5, pdf))))
        }
        val engineless = StudyRig(moodle, withToken = true)
        runBlocking {
            val foreign = MoodleClient(MOODLE_BASE, engineless.http)
            val tmp = File.createTempFile("download", ".bin")
            try {
                foreign.download(GOOD_TOKEN, "https://evil.example.test/x.pdf", tmp)
                error("should have refused")
            } catch (e: MoodleException) {
                assertEquals("foreignurl", e.errorCode)
            } finally {
                tmp.delete()
            }
        }
        engineless.close()
    }

    @Test
    fun theDailyTickRunsOnceAfterSixJerusalemTimeAndCatchesUpAfterARestart() {
        // 2026-11-10 05:30 and 06:30 Jerusalem (UTC+2): 03:30 and 04:30 UTC
        var now = java.time.Instant.parse("2026-11-10T03:30:00Z").toEpochMilli()
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val rig = StudyRig(moodle, withToken = true, clock = { now })
        runBlocking {
            rig.study.tick()
            assertEquals(0, moodle.siteInfoCalls.get(), "before 06:00 nothing runs")
            now = java.time.Instant.parse("2026-11-10T04:30:00Z").toEpochMilli()
            rig.study.tick()
            rig.study.idle()
            assertEquals(1, moodle.siteInfoCalls.get())
            rig.study.tick()
            assertEquals(1, moodle.siteInfoCalls.get(), "not again the same day")
            now = java.time.Instant.parse("2026-11-11T04:30:00Z").toEpochMilli()
            rig.study.tick()
            rig.study.idle()
            assertEquals(2, moodle.siteInfoCalls.get())
        }
        rig.close()
    }

    @Test
    fun aFailedCheckDoesNotMarkTheDayAsCheckedButASuccessfulOneDoes() {
        val moodle = FakeMoodle().also { it.courses = twoCourses() }
        val now = java.time.Instant.parse("2026-11-10T08:00:00Z").toEpochMilli()
        val rig = StudyRig(moodle, withToken = true, clock = { now })
        runBlocking {
            moodle.validToken = "changed"
            assertEquals(CheckOutcome.Rejected, rig.study.check())
            assertNull(rig.sdb.db.kvQueries.get(CHECK_DATE_KEY).executeAsOneOrNull())
            moodle.validToken = GOOD_TOKEN
            assertTrue(rig.study.check() is CheckOutcome.Done)
            assertEquals("2026-11-10", rig.sdb.db.kvQueries.get(CHECK_DATE_KEY).executeAsOne())
        }
        rig.close()
    }
    // ---- pure helpers ----------------------------------------------------------------------------------

    @Test
    fun parseMoodleLinkAcceptsAnySchemeEncodingAndMissingPadding() {
        val raw = java.util.Base64.getEncoder().encodeToString("sig:::TOKEN-1:::priv".toByteArray())
        assertEquals("TOKEN-1", parseMoodleLink("moodlemobile://token=$raw"))
        assertEquals("TOKEN-1", parseMoodleLink("  wally://token=$raw \n"))
        assertEquals("TOKEN-1", parseMoodleLink("custom+scheme.v2://token=" + raw.trimEnd('=')))
        assertEquals("TOKEN-1", parseMoodleLink("moodlemobile://token=" + java.net.URLEncoder.encode(raw, "UTF-8")))
        val two = java.util.Base64.getEncoder().encodeToString("sig:::ONLY".toByteArray())
        assertEquals("ONLY", parseMoodleLink("x://token=$two"))
        assertNull(parseMoodleLink("hello"))
        assertNull(parseMoodleLink("moodlemobile://token="))
        assertNull(parseMoodleLink("moodlemobile://token=!!!!"))
        assertNull(parseMoodleLink("moodlemobile://other=$raw"))
        assertNull(parseMoodleLink("moodlemobile://token=" + java.util.Base64.getEncoder().encodeToString("nocolons".toByteArray())))
    }

    @Test
    fun isDueForCheckUsesJerusalemSixOClock() {
        fun at(utc: String) = java.time.Instant.parse(utc).toEpochMilli()
        assertFalse(isDueForCheck(at("2026-11-10T03:59:00Z"), null)) // 05:59
        assertTrue(isDueForCheck(at("2026-11-10T04:00:00Z"), null)) // 06:00
        assertTrue(isDueForCheck(at("2026-11-10T04:00:00Z"), "2026-11-09"))
        assertFalse(isDueForCheck(at("2026-11-10T04:00:00Z"), "2026-11-10"))
        assertFalse(isDueForCheck(at("2026-11-10T20:00:00Z"), "2026-11-11")) // 22:00 and already checked "tomorrow"
    }

    @Test
    fun textLimitsCutAtAParagraphBreakAndKeepShortTextOut() {
        assertEquals(Extraction.NoText, TextExtractor.limit("x".repeat(199)))
        assertTrue(TextExtractor.limit("x".repeat(200)) is Extraction.Text)
        val paragraph = "word ".repeat(19).trim() // 94 chars
        val long = (1..5000).joinToString("\n\n") { paragraph } // far more than 400 000
        val cut = (TextExtractor.limit(long) as Extraction.Text).text
        assertTrue(cut.endsWith(CUT_LINE))
        val body = cut.removeSuffix("\n\n$CUT_LINE")
        assertTrue(body.length <= MAX_TEXT_CHARS)
        assertTrue(body.endsWith(paragraph), "cut at a paragraph break, not mid-paragraph")
        val exact = "a".repeat(MAX_TEXT_CHARS)
        assertEquals(exact, (TextExtractor.limit(exact) as Extraction.Text).text)
    }

    @Test
    fun configMoodleBlockIsOptionalAndTheBaseUrlLosesItsTrailingSlash() {
        val base = """"bindAddress":"1.2.3.4","dbPath":"/x.db","tokens":{"a":"b"}"""
        assertNull(dev.tim.assistant.server.parseConfig("{$base}").moodle)
        val c = dev.tim.assistant.server.parseConfig("""{$base,"moodle":{"baseUrl":" https://m.example.test/2027/ "}}""")
        assertEquals("https://m.example.test/2027", c.moodle!!.baseUrl)
    }

    @Test
    fun moodleTokenFileIsPrivateAtomicAndHoldsOnlyTokenAndTime() {
        val dir = java.nio.file.Files.createTempDirectory("tok").toFile()
        val store = MoodleTokenStore(File(dir, "moodle-token.json"))
        assertNull(store.read())
        store.write("secret-1", 5L)
        store.write("secret-2", 6L)
        assertEquals("secret-2", store.read())
        assertEquals(1, dir.listFiles()!!.size, "no temp file left behind")
        val json = testJson.parseToJsonElement(File(dir, "moodle-token.json").readText()).jsonObject
        assertEquals(setOf("token", "obtainedAt"), json.keys)
        assertFalse(MoodleTokenFile("secret", 1).toString().contains("secret"))
    }
}
