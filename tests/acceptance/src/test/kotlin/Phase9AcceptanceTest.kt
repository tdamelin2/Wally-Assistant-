import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Study_file
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.MoodleConfig
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.study.Extraction
import dev.tim.assistant.server.study.MoodleClient
import dev.tim.assistant.server.study.MoodleStatus
import dev.tim.assistant.server.study.MoodleTokenStore
import dev.tim.assistant.server.study.MoodleCourse
import dev.tim.assistant.server.study.StudyFiles
import dev.tim.assistant.server.study.StudyService
import dev.tim.assistant.server.study.TextExtractor
import dev.tim.assistant.server.study.filesWanted
import dev.tim.assistant.server.study.isDueForCheck
import dev.tim.assistant.server.study.parseMoodleLink
import dev.tim.assistant.study.QuizQuestion
import dev.tim.assistant.study.chunkText
import dev.tim.assistant.study.examCountdownText
import dev.tim.assistant.study.isExamPast
import dev.tim.assistant.study.moodleLaunchUrl
import dev.tim.assistant.study.parseCards
import dev.tim.assistant.study.parseQuiz
import dev.tim.assistant.study.quizScore
import dev.tim.assistant.study.sortCourses
import dev.tim.assistant.study.studyFileStatusText
import dev.tim.assistant.study.upcomingExams
import dev.tim.assistant.time.localDateOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Phase 9 acceptance tests, written from the Plan contract + Plan note 23:17 (manual/device parts live in ops/checklists/w9-study.md). */
class Phase9AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(rel: String) = File(root, rel).readText()
    private fun fixture(name: String) = File(root, "server/src/test/resources/study/$name")

    // ------------------------------------------------------------ AC-1 probe record

    @Test
    fun test_AC1_moodle_md_records_three_calls_and_a_downloaded_file_size() {
        val t = text("ops/moodle.md")
        assertTrue(t.contains("core_webservice_get_site_info"))
        assertTrue(t.contains("core_enrol_get_users_courses"))
        assertTrue(t.contains("core_course_get_contents"))
        assertTrue(Regex("\\d{4,}\\s*(bytes|B)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t), "a byte size of the downloaded file")
        assertTrue(t.contains("fileurl") && t.contains("filesize") && t.contains("filename"))
    }

    @Test
    fun test_AC1_moodle_md_holds_no_token_like_secret() {
        val t = text("ops/moodle.md")
        assertFalse(Regex("(wstoken|token)\\s*[=:]\\s*[0-9a-f]{32}", RegexOption.IGNORE_CASE).containsMatchIn(t))
        assertFalse(Regex("(moodlemobile|wally)://token=[A-Za-z0-9+/=_-]{20,}").containsMatchIn(t))
    }

    // ------------------------------------------------------------ AC-4 exam countdown

    @Test
    fun test_AC4_countdown_today_tomorrow_in_N_days() {
        assertEquals("today", examCountdownText("2026-11-10", "2026-11-10"))
        assertEquals("tomorrow", examCountdownText("2026-11-11", "2026-11-10"))
        assertEquals("in 2 days", examCountdownText("2026-11-12", "2026-11-10"))
        assertEquals("in 30 days", examCountdownText("2026-12-10", "2026-11-10"))
    }

    @Test
    fun test_AC4_countdown_across_month_and_year_boundaries() {
        assertEquals("tomorrow", examCountdownText("2027-01-01", "2026-12-31"))
        assertEquals("in 3 days", examCountdownText("2027-03-01", "2027-02-26"))
        assertEquals("in 2 days", examCountdownText("2028-03-01", "2028-02-28")) // leap year
    }

    @Test
    fun test_AC4_countdown_uses_calendar_dates_at_the_Jerusalem_midnight_boundary() {
        // Israel is UTC+2 in November: 21:59:59Z is still Nov 9, 22:00:00Z is Nov 10.
        val before = localDateOf(Instant.parse("2026-11-09T21:59:59Z"))
        val after = localDateOf(Instant.parse("2026-11-09T22:00:00Z"))
        assertEquals("2026-11-09", before)
        assertEquals("2026-11-10", after)
        assertEquals("tomorrow", examCountdownText("2026-11-10", before))
        assertEquals("today", examCountdownText("2026-11-10", after))
        // an exam late on the 10th is still "today" on the morning of the 10th, never "in 0 days"
        assertEquals("today", examCountdownText("2026-11-10", localDateOf(Instant.parse("2026-11-10T03:00:00Z"))))
    }

    private fun exam(id: String, date: String, time: String? = null, deleted: Long = 0) =
        Exam(id, 1, deleted, 0, "SYNCED", null, "Exam $id", date, time)

    @Test
    fun test_AC4_upcoming_exams_hides_past_and_deleted_orders_and_limits_to_3() {
        val exams = listOf(
            exam("e5", "2026-11-20"), exam("past", "2026-11-09"), exam("e2", "2026-11-12", "14:00"),
            exam("e1", "2026-11-12", "09:00"), exam("gone", "2026-11-11", deleted = 1), exam("e4", "2026-11-15"),
            exam("today", "2026-11-10"),
        )
        val up = upcomingExams(exams, "2026-11-10")
        assertEquals(listOf("today", "e1", "e2"), up.map { it.id })
        assertEquals(listOf("today", "e1", "e2", "e4", "e5"), upcomingExams(exams, "2026-11-10", limit = 10).map { it.id })
        assertEquals(listOf("today"), upcomingExams(exams, "2026-11-10", limit = 1).map { it.id })
        assertTrue(upcomingExams(emptyList(), "2026-11-10").isEmpty())
    }

    @Test
    fun test_AC4_same_date_same_time_orders_by_id() {
        val up = upcomingExams(listOf(exam("b", "2026-11-12", "09:00"), exam("a", "2026-11-12", "09:00")), "2026-11-10")
        assertEquals(listOf("a", "b"), up.map { it.id })
    }

    @Test
    fun test_AC4_isExamPast_by_calendar_date() {
        assertTrue(isExamPast(exam("p", "2026-11-09"), "2026-11-10"))
        assertFalse(isExamPast(exam("t", "2026-11-10"), "2026-11-10"))
        assertFalse(isExamPast(exam("f", "2026-11-11"), "2026-11-10"))
    }

    // ------------------------------------------------------------ AC-5 chunking

    @Test
    fun test_AC5_chunk_exactly_12000_is_one_piece_and_12001_is_two() {
        assertEquals(listOf("x".repeat(12_000)), chunkText("x".repeat(12_000), 12_000))
        val two = chunkText("x".repeat(12_001), 12_000)
        assertEquals(2, two.size)
        assertTrue(two.all { it.length <= 12_000 })
        assertEquals("x".repeat(12_001), two.joinToString(""))
        assertTrue(chunkText("", 12_000).isEmpty() || chunkText("", 12_000).all { it.isEmpty() })
    }

    @Test
    fun test_AC5_chunk_splits_at_paragraph_break_first() {
        val a = "a".repeat(7_000)
        val b = "b".repeat(7_000)
        val pieces = chunkText("$a\n\n$b", 12_000)
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].startsWith(a) && pieces[0].length <= 12_000)
        assertTrue(pieces[1].endsWith(b))
        assertEquals("$a\n\n$b", pieces.joinToString(""))
    }

    @Test
    fun test_AC5_chunk_falls_back_to_single_newline_then_hard_split() {
        val withNl = "a".repeat(7_000) + "\n" + "b".repeat(7_000)
        val p1 = chunkText(withNl, 12_000)
        assertEquals(2, p1.size)
        assertTrue(p1[0].trimEnd('\n') == "a".repeat(7_000), "split at the newline")
        assertEquals(withNl, p1.joinToString(""))
        val none = "z".repeat(30_000)
        val p2 = chunkText(none, 12_000)
        assertEquals(3, p2.size)
        assertTrue(p2.all { it.length <= 12_000 })
        assertEquals(none, p2.joinToString(""))
    }

    @Test
    fun test_AC5_chunk_never_splits_inside_a_surrogate_pair() {
        val emoji = "😀" // one character outside the BMP = 2 chars
        // 11 999 plain chars, then an emoji straddling the 12 000 boundary
        val s = "a".repeat(11_999) + emoji + "tail"
        val pieces = chunkText(s, 12_000)
        assertTrue(pieces.size >= 2)
        for (p in pieces) {
            assertFalse(p.isNotEmpty() && p.last().isHighSurrogate(), "piece ends inside a pair")
            assertFalse(p.isNotEmpty() && p.first().isLowSurrogate(), "piece starts inside a pair")
            assertTrue(p.length <= 12_000)
        }
        assertEquals(s, pieces.joinToString(""))
    }

    // ------------------------------------------------------------ AC-5 cards

    private fun cardsJson(n: Int) =
        """{"cards":[${(1..n).joinToString(",") { """{"front":"Q$it","back":"A$it"}""" }}]}"""

    @Test
    fun test_AC5_parseCards_keeps_9_10_40_and_cuts_41_to_40() {
        assertEquals(9, parseCards(cardsJson(9)).size) // the job (not the parser) fails below 10
        assertEquals(10, parseCards(cardsJson(10)).size)
        assertEquals(40, parseCards(cardsJson(40)).size)
        val cut = parseCards(cardsJson(41))
        assertEquals(40, cut.size)
        assertEquals("Q1", cut.first().front)
        assertEquals("Q40", cut.last().front)
    }

    @Test
    fun test_AC5_parseCards_trims_and_drops_blank_pairs() {
        val json = """{"cards":[{"front":"  Q1  ","back":" A1 "},{"front":"","back":"A"},{"front":"Q","back":"   "},{"front":"Q2","back":"A2"}]}"""
        val cards = parseCards(json)
        assertEquals(listOf("Q1" to "A1", "Q2" to "A2"), cards.map { it.front to it.back })
    }

    @Test
    fun test_AC5_parseCards_bad_json_gives_no_usable_cards() {
        val r = runCatching { parseCards("this is not json") }
        assertTrue(r.isFailure || r.getOrThrow().size < 10)
        val r2 = runCatching { parseCards("""{"cards":"nope"}""") }
        assertTrue(r2.isFailure || r2.getOrThrow().size < 10)
    }

    // ------------------------------------------------------------ AC-5 quiz

    private fun qJson(i: Int, options: List<String> = listOf("a", "b", "c", "d"), answer: Any = 1, q: String = "Question $i?") =
        """{"q":${JsonPrimitive(q)},"options":[${options.joinToString(",") { JsonPrimitive(it).toString() }}],"answerIndex":$answer}"""

    private fun quizJson(vararg items: String) = """{"questions":[${items.joinToString(",")}]}"""
    private fun ten() = (1..10).map { qJson(it) }.toTypedArray()

    @Test
    fun test_AC5_parseQuiz_accepts_exactly_10_valid_questions() {
        val qs = parseQuiz(quizJson(*ten()))
        assertNotNull(qs)
        assertEquals(10, qs.size)
        assertEquals(4, qs[0].options.size)
        assertEquals(1, qs[0].answerIndex)
    }

    @Test
    fun test_AC5_parseQuiz_rejects_9_and_11_questions() {
        assertNull(parseQuiz(quizJson(*ten().copyOf(9).requireNoNulls())))
        assertNull(parseQuiz(quizJson(*(ten().toList() + qJson(11)).toTypedArray())))
    }

    @Test
    fun test_AC5_parseQuiz_rejects_bad_question_shapes() {
        fun with(bad: String) = parseQuiz(quizJson(*(ten().toList().take(9) + bad).toTypedArray()))
        assertNotNull(with(qJson(10)), "control: the helper builds a valid quiz")
        assertNull(with(qJson(10, q = "  ")), "blank question")
        assertNull(with(qJson(10, options = listOf("a", "b", "c"))), "3 options")
        assertNull(with(qJson(10, options = listOf("a", "b", "c", "d", "e"))), "5 options")
        assertNull(with(qJson(10, options = listOf("a", "b", " ", "d"))), "blank option")
        assertNull(with(qJson(10, answer = 4)), "answerIndex 4")
        assertNull(with(qJson(10, answer = -1)), "answerIndex -1")
        assertNull(with(qJson(10, answer = "\"1\"")), "answerIndex as text")
        assertNull(with(qJson(10, answer = 1.5)), "answerIndex 1.5")
    }

    @Test
    fun test_AC5_parseQuiz_rejects_garbage() {
        assertNull(parseQuiz("not json"))
        assertNull(parseQuiz("{}"))
    }

    @Test
    fun test_AC5_quizScore_counts_correct_answers_and_skips_unanswered() {
        val qs = (1..10).map { QuizQuestion("q$it", listOf("a", "b", "c", "d"), it % 4) }
        assertEquals(10, quizScore(qs, qs.map { it.answerIndex }))
        assertEquals(0, quizScore(qs, qs.map { (it.answerIndex + 1) % 4 }))
        assertEquals(0, quizScore(qs, List(10) { null }))
        val half = qs.mapIndexed { i, q -> if (i < 5) q.answerIndex else null }
        assertEquals(5, quizScore(qs, half))
    }

    // ------------------------------------------------------------ study file status / launch URL / courses

    private fun sfile(status: String, origin: String = "MOODLE") =
        Study_file("sfile:x", 1, 0, 0, "SYNCED", "course:1", "a.pdf", origin, 10, status, null)

    @Test
    fun test_AC5_studyFileStatusText_failed_says_no_text_found_and_states_differ() {
        assertEquals("No text found (scanned?)", studyFileStatusText(sfile("FAILED")))
        val pending = studyFileStatusText(sfile("PENDING"))
        val ready = studyFileStatusText(sfile("READY"))
        assertTrue(pending.isNotBlank() && ready.isNotBlank())
        assertTrue(pending != ready && ready != studyFileStatusText(sfile("FAILED")))
    }

    @Test
    fun test_AC7_launch_url_uses_moodlemobile_scheme_and_keeps_base_path() {
        val url = moodleLaunchUrl("https://moodle.example.edu", 12345)
        assertEquals(
            "https://moodle.example.edu/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=12345&urlscheme=moodlemobile",
            url,
        )
        // a trailing slash on the base must not double up
        assertEquals(url, moodleLaunchUrl("https://moodle.example.edu/", 12345))
    }

    private fun course(id: String, name: String, enabled: Long, moodleId: Long?) = Course(id, 1, 0, 0, "SYNCED", moodleId, name, enabled)

    @Test
    fun test_AC3_sortCourses_enabled_and_manual_first_by_name_then_disabled_by_name() {
        val all = listOf(
            course("course:3", "Zoology", 0, 3), course("course:1", "Biology", 1, 1), course("course:2", "Algebra", 0, 2),
            course("course:manual:u1", "Manual Notes", 1, null), course("course:4", "Chemistry", 1, 4),
        )
        val (active, others) = sortCourses(all)
        assertEquals(listOf("Biology", "Chemistry", "Manual Notes"), active.map { it.name })
        assertEquals(listOf("Algebra", "Zoology"), others.map { it.name })
        val (a2, o2) = sortCourses(emptyList())
        assertTrue(a2.isEmpty() && o2.isEmpty())
    }

    // ------------------------------------------------------------ AC-3 server pure helpers

    private fun link(scheme: String, payload: String, pad: Boolean = true): String {
        val enc = Base64.getEncoder().let { if (pad) it else it.withoutPadding() }
        return "$scheme://token=" + enc.encodeToString(payload.toByteArray())
    }

    @Test
    fun test_AC3_parseMoodleLink_accepts_moodlemobile_and_wally_and_any_scheme() {
        val p = "signature-x:::the-token-123:::private-part"
        assertEquals("the-token-123", parseMoodleLink(link("moodlemobile", p)))
        assertEquals("the-token-123", parseMoodleLink(link("wally", p)))
        assertEquals("the-token-123", parseMoodleLink(link("my-app+v2.x", p)))
        assertEquals("the-token-123", parseMoodleLink(link("moodlemobile", "signature-x:::the-token-123")))
    }

    @Test
    fun test_AC3_parseMoodleLink_tolerates_whitespace_missing_padding_and_url_encoding() {
        val p = "signature-x:::the-token-123:::pr"
        assertEquals("the-token-123", parseMoodleLink("  " + link("moodlemobile", p) + "\n"))
        assertEquals("the-token-123", parseMoodleLink(link("moodlemobile", p + "x", pad = false)))
        val padded = link("moodlemobile", p + "xy") // base64 with '=' padding
        assertEquals("the-token-123", parseMoodleLink(padded.substringBefore("token=") + "token=" + padded.substringAfter("token=").replace("=", "%3D")))
    }

    @Test
    fun test_AC3_parseMoodleLink_rejects_bad_links() {
        assertNull(parseMoodleLink(""))
        assertNull(parseMoodleLink("hello"))
        assertNull(parseMoodleLink("https://example.com/?token=abc"))
        assertNull(parseMoodleLink("moodlemobile://token="))
        assertNull(parseMoodleLink("moodlemobile://token=!!!notbase64!!!"))
        assertNull(parseMoodleLink(link("moodlemobile", "no-separators-here")))
    }

    @Test
    fun test_AC3_isDueForCheck_waits_for_0600_Jerusalem_and_runs_once_a_day() {
        // Nov: UTC+2. 03:59Z = 05:59 local, 04:00Z = 06:00 local.
        val before = Instant.parse("2026-11-10T03:59:00Z").toEpochMilliseconds()
        val at = Instant.parse("2026-11-10T04:00:00Z").toEpochMilliseconds()
        val late = Instant.parse("2026-11-10T20:00:00Z").toEpochMilliseconds()
        assertFalse(isDueForCheck(before, null))
        assertFalse(isDueForCheck(before, "2026-11-09"))
        assertTrue(isDueForCheck(at, null))
        assertTrue(isDueForCheck(at, "2026-11-09"))
        assertFalse(isDueForCheck(at, "2026-11-10"), "already checked today")
        assertTrue(isDueForCheck(late, "2026-11-09"), "restart catch-up later in the day")
        assertFalse(isDueForCheck(late, "2026-11-10"))
        // 22:30Z on the 10th is 00:30 local on the 11th: before 06:00 so not due even though the date changed
        assertFalse(isDueForCheck(Instant.parse("2026-11-10T22:30:00Z").toEpochMilliseconds(), "2026-11-10"))
    }

    @Test
    fun test_AC3_filesWanted_only_for_enabled_visible_courses() {
        fun mc(visible: Boolean, end: Long = 0) = MoodleCourse(1, "C", visible, end)
        assertTrue(filesWanted(course("course:1", "C", 1, 1), mc(true)))
        assertFalse(filesWanted(course("course:1", "C", 0, 1), mc(true)), "switched off")
        assertFalse(filesWanted(course("course:1", "C", 1, 1), mc(false)), "hidden in Moodle")
        assertTrue(filesWanted(course("course:1", "C", 1, 1), mc(true, end = 0)), "enddate 0 is not a filter any more")
    }

    // ------------------------------------------------------------ AC-3 extraction (committed fixtures)

    @Test
    fun test_AC3_sample_pdf_extracts_text() {
        val r = TextExtractor.extract(fixture("sample.pdf"), "pdf")
        val t = (r as Extraction.Text).text
        assertTrue(t.length >= 200)
    }

    @Test
    fun test_AC3_sample_pptx_extracts_text_including_slides_and_notes() {
        val r = TextExtractor.extract(fixture("sample.pptx"), "pptx")
        val t = (r as Extraction.Text).text
        assertTrue(t.length >= 200)
    }

    @Test
    fun test_AC3_scanned_pdf_has_no_text() {
        assertEquals(Extraction.NoText, TextExtractor.extract(fixture("scanned.pdf"), "pdf"))
    }

    @Test
    fun test_AC3_text_under_200_chars_is_no_text_and_200_is_enough() {
        assertEquals(Extraction.NoText, TextExtractor.limit("a".repeat(199)))
        assertTrue(TextExtractor.limit("a".repeat(200)) is Extraction.Text)
    }

    @Test
    fun test_AC3_text_over_400000_is_cut_at_a_paragraph_break_with_a_marker_line() {
        val para = "p".repeat(999) + "\n\n"
        val big = para.repeat(500) // 501 500 chars
        val out = (TextExtractor.limit(big) as Extraction.Text).text
        assertTrue(out.endsWith("[Text cut at 400 000 characters]"))
        val body = out.removeSuffix("[Text cut at 400 000 characters]")
        assertTrue(body.length <= 400_000, "body ${body.length}")
        assertTrue(body.trimEnd().endsWith("p"), "cut falls on a paragraph boundary")
        val exact = (TextExtractor.limit("q".repeat(400_000)) as Extraction.Text).text
        assertEquals(400_000, exact.length, "exactly 400 000 is not cut")
    }

    // ------------------------------------------------------------ AC-2 sync ownership and validation

    private fun obj(vararg p: Pair<String, Any?>) = JsonObject(
        p.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v as Number)
            }
        },
    )

    private fun newStore() = SyncStore(openDb(Files.createTempDirectory("p9acc").resolve("t.db").toString()))

    private fun row(table: String, id: String, data: JsonObject, at: Long = 1000, deleted: Boolean = false) =
        SyncRow(table, id, at, deleted, data)

    private val cardData = obj("fileId" to "sfile:1", "front" to "F", "back" to "B", "lastResult" to null, "lastReviewedAt" to null)
    private val quizData = obj("courseId" to "course:1", "questionsJson" to "[]", "score" to null, "takenAt" to null)
    private val examData = obj("courseId" to null, "title" to "Final", "localDate" to "2026-12-01", "time" to null)

    @Test
    fun test_AC2_card_quiz_exam_round_trip_through_push_and_pull() = runBlocking<Unit> {
        val store = newStore()
        val r = store.push(
            listOf(row("card", "card:sfile:1:0", cardData), row("quiz", "quiz:j1", quizData), row("exam", "exam:e1", examData)),
        )
        assertEquals(3, r.accepted.size, "rejected: ${r.rejected}")
        val pulled = store.pull(0).rows
        for ((t, id) in listOf("card" to "card:sfile:1:0", "quiz" to "quiz:j1", "exam" to "exam:e1")) {
            val got = pulled.single { it.table == t && it.id == id }
            assertFalse(got.deleted)
        }
        assertEquals("Final", pulled.single { it.table == "exam" }.data["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun test_AC2_tombstones_for_card_quiz_exam_sync() = runBlocking<Unit> {
        val store = newStore()
        store.push(listOf(row("exam", "exam:e1", examData, at = 1000)))
        val r = store.push(listOf(row("exam", "exam:e1", examData, at = 2000, deleted = true)))
        assertEquals(listOf("exam:e1"), r.accepted)
        assertTrue(store.pull(0).rows.single { it.id == "exam:e1" }.deleted)
    }

    @Test
    fun test_AC2_server_written_tables_course_and_study_file_refuse_client_pushes() = runBlocking<Unit> {
        val store = newStore()
        val course = obj("moodleId" to 5, "name" to "Forged", "enabled" to true)
        val file = obj("courseId" to "course:5", "name" to "x.pdf", "origin" to "UPLOAD", "sizeBytes" to 5, "textStatus" to "READY", "studySetDocId" to null)
        val r = store.push(
            listOf(
                row("course", "course:5", course), row("course", "course:manual:abc", obj("moodleId" to null, "name" to "Mine", "enabled" to true)),
                row("study_file", "sfile:upload:1", file),
            ),
        )
        assertTrue(r.accepted.isEmpty(), "accepted: ${r.accepted}")
        assertEquals(3, r.rejected.size)
        assertTrue(store.pull(0).rows.none { it.table == "course" || it.table == "study_file" })
    }

    @Test
    fun test_AC2_client_table_validation_rules() = runBlocking<Unit> {
        val store = newStore()
        suspend fun ok(table: String, id: String, d: JsonObject) = store.push(listOf(row(table, id, d))).rejected.isEmpty()
        // card
        assertTrue(ok("card", "c1", cardData))
        assertTrue(ok("card", "c2", obj("fileId" to "f", "front" to "F", "back" to "B", "lastResult" to "KNEW", "lastReviewedAt" to 5)))
        assertTrue(ok("card", "c3", obj("fileId" to "f", "front" to "F", "back" to "B", "lastResult" to "DIDNT", "lastReviewedAt" to null)))
        assertFalse(ok("card", "c4", obj("fileId" to "f", "front" to "F", "back" to "B", "lastResult" to "MAYBE", "lastReviewedAt" to null)))
        assertFalse(ok("card", "c5", obj("fileId" to "f", "front" to "", "back" to "B", "lastResult" to null, "lastReviewedAt" to null)))
        assertFalse(ok("card", "c6", obj("fileId" to "f", "front" to "F", "back" to "x".repeat(2001), "lastResult" to null, "lastReviewedAt" to null)))
        assertTrue(ok("card", "c7", obj("fileId" to "f", "front" to "x".repeat(2000), "back" to "x".repeat(2000), "lastResult" to null, "lastReviewedAt" to null)))
        assertFalse(ok("card", "c8", obj("fileId" to "", "front" to "F", "back" to "B", "lastResult" to null, "lastReviewedAt" to null)))
        assertFalse(ok("card", "c9", obj("fileId" to "f".repeat(201), "front" to "F", "back" to "B", "lastResult" to null, "lastReviewedAt" to null)))
        // quiz
        assertTrue(ok("quiz", "q1", obj("courseId" to "c", "questionsJson" to "x".repeat(50_000), "score" to 10, "takenAt" to 7)))
        assertTrue(ok("quiz", "q2", obj("courseId" to "c", "questionsJson" to "[]", "score" to 0, "takenAt" to null)))
        assertFalse(ok("quiz", "q3", obj("courseId" to "c", "questionsJson" to "x".repeat(50_001), "score" to null, "takenAt" to null)))
        assertFalse(ok("quiz", "q4", obj("courseId" to "c", "questionsJson" to "[]", "score" to 11, "takenAt" to null)))
        assertFalse(ok("quiz", "q5", obj("courseId" to "c", "questionsJson" to "[]", "score" to -1, "takenAt" to null)))
        assertFalse(ok("quiz", "q6", obj("courseId" to "", "questionsJson" to "[]", "score" to null, "takenAt" to null)))
        // exam
        assertTrue(ok("exam", "x1", examData))
        assertTrue(ok("exam", "x2", obj("courseId" to "course:1", "title" to "T", "localDate" to "2028-02-29", "time" to "23:59")))
        assertFalse(ok("exam", "x3", obj("courseId" to null, "title" to "", "localDate" to "2026-12-01", "time" to null)))
        assertFalse(ok("exam", "x4", obj("courseId" to null, "title" to "T".repeat(201), "localDate" to "2026-12-01", "time" to null)))
        assertFalse(ok("exam", "x5", obj("courseId" to null, "title" to "T", "localDate" to "2026-13-01", "time" to null)))
        assertFalse(ok("exam", "x6", obj("courseId" to null, "title" to "T", "localDate" to "2027-02-29", "time" to null)))
        assertFalse(ok("exam", "x7", obj("courseId" to null, "title" to "T", "localDate" to "2026-12-01", "time" to "24:00")))
        assertFalse(ok("exam", "x8", obj("courseId" to null, "title" to "T", "localDate" to "2026-12-01", "time" to "9:5")))
    }

    // ------------------------------------------------------------ AC-3 HTTP: health, bad link, enabled route, upload

    private val tok = "p9-token"

    private fun cfg(moodle: Boolean = true) = Config(
        bindAddress = "127.0.0.1", dbPath = "x", tokens = mapOf("laptop" to tok),
        moodle = if (moodle) MoodleConfig("https://moodle.example.test/2027/") else null,
    )

    private class Stack(moodleOnline: Boolean = true) {
        val sdb = openDb(Files.createTempDirectory("p9http").resolve("t.db").toString())
        val store = SyncStore(sdb)
        val status = MoodleStatus()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir: File = Files.createTempDirectory("p9study").toFile()
        val tokens = MoodleTokenStore(File(dir, "moodle-token.json"))
        val http = HttpClient(
            MockEngine {
                respond(
                    """{"exception":"moodle_exception","errorcode":"invalidtoken","message":"Invalid token"}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val files = StudyFiles(File(dir, "study"))
        val service = StudyService(
            sdb, store, files, if (moodleOnline) MoodleClient("https://moodle.example.test/2027", http) else null,
            tokens, status, scope,
        )

        fun close() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); http.close(); sdb.close() }
    }

    private suspend fun io.ktor.client.HttpClient.healthJson(): JsonObject =
        Json.parseToJsonElement(get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $tok") }.bodyAsText()).jsonObject

    @Test
    fun test_AC3_health_moodleOk_null_without_token_false_after_rejection_true_when_working() = testApplication {
        val s = Stack()
        try {
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            val j0 = client.healthJson()
            assertEquals("null", j0.getValue("moodleOk").toString(), "no token file -> null")
            assertEquals("https://moodle.example.test/2027", j0.getValue("moodleBaseUrl").jsonPrimitive.content)
            s.status.set(false)
            assertEquals(false, client.healthJson().getValue("moodleOk").jsonPrimitive.booleanOrNull)
            s.status.set(true)
            assertEquals(true, client.healthJson().getValue("moodleOk").jsonPrimitive.booleanOrNull)
        } finally { s.close() }
    }

    @Test
    fun test_AC3_health_without_moodle_config_has_null_moodleOk_and_null_base_url() = testApplication {
        val s = Stack(moodleOnline = false)
        try {
            application { module(cfg(moodle = false), s.store, moodleStatus = s.status, study = s.service) }
            val j = client.healthJson()
            assertEquals("null", j.getValue("moodleOk").toString())
            assertEquals("null", j.getValue("moodleBaseUrl").toString())
            val r = client.post("/v1/study/moodle-token") {
                header(HttpHeaders.Authorization, "Bearer $tok"); header(HttpHeaders.ContentType, "application/json")
                setBody("""{"link":"x"}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertEquals("moodle not configured", Json.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
            val n = client.post("/v1/study/sync-now") { header(HttpHeaders.Authorization, "Bearer $tok") }
            assertEquals(HttpStatusCode.ServiceUnavailable, n.status)
        } finally { s.close() }
    }

    @Test
    fun test_AC3_study_routes_need_the_bearer_token() = testApplication {
        val s = Stack()
        try {
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/study/sync-now").status)
        } finally { s.close() }
    }

    @Test
    fun test_AC3_bad_link_is_400_and_rejected_token_is_502_and_stores_nothing() = testApplication {
        val s = Stack()
        try {
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            suspend fun send(link: String) = client.post("/v1/study/moodle-token") {
                header(HttpHeaders.Authorization, "Bearer $tok"); header(HttpHeaders.ContentType, "application/json")
                setBody("""{"link":${JsonPrimitive(link)}}""")
            }
            val bad = send("not a link")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("bad link", Json.parseToJsonElement(bad.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
            val rejected = send(link("moodlemobile", "sig:::some-token:::priv"))
            assertEquals(HttpStatusCode.BadGateway, rejected.status)
            assertEquals("moodle rejected token", Json.parseToJsonElement(rejected.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
            assertFalse(s.tokens.exists(), "no token file after a rejected link")
            assertFalse(rejected.bodyAsText().contains("some-token"))
        } finally { s.close() }
    }

    private fun seedCourse(s: Stack, id: String, enabled: Long) {
        s.sdb.db.courseQueries.upsert(Course(id, 1000, 0, 0, "SYNCED", 7, "Biology", enabled))
    }

    private fun enabledOf(rows: List<SyncRow>, id: String): Boolean? =
        rows.singleOrNull { it.table == "course" && it.id == id }?.data?.get("enabled")?.jsonPrimitive?.booleanOrNull

    @Test
    fun test_AC3_enable_route_switches_the_course_and_syncs_it() = testApplication {
        val s = Stack()
        try {
            seedCourse(s, "course:7", 0)
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            suspend fun put(id: String, body: String) = client.put("/v1/study/courses/$id/enabled") {
                header(HttpHeaders.Authorization, "Bearer $tok"); header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }
            // not yet synced anywhere: row exists locally with serverSeq 0, so touch it through the route first
            val on = put("course:7", """{"enabled":true}""")
            assertEquals(HttpStatusCode.OK, on.status)
            assertEquals(true, enabledOf(s.store.pull(0).rows, "course:7"))
            assertEquals(HttpStatusCode.OK, put("course:7", """{"enabled":false}""").status)
            assertEquals(false, enabledOf(s.store.pull(0).rows, "course:7"))
            assertEquals(HttpStatusCode.NotFound, put("course:nope", """{"enabled":true}""").status)
            assertEquals(HttpStatusCode.BadRequest, put("course:7", """{"enabled":"yes"}""").status)
            assertEquals(HttpStatusCode.BadRequest, put("course:7", "not json").status)
            assertEquals(HttpStatusCode.BadRequest, put("course:7", "{}").status)
        } finally { s.close() }
    }

    @Test
    fun test_AC3_enabling_a_deleted_course_is_404() = testApplication {
        val s = Stack()
        try {
            s.sdb.db.courseQueries.upsert(Course("course:9", 1000, 1, 0, "SYNCED", 9, "Gone", 0))
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            val r = client.put("/v1/study/courses/course:9/enabled") {
                header(HttpHeaders.Authorization, "Bearer $tok"); header(HttpHeaders.ContentType, "application/json")
                setBody("""{"enabled":true}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        } finally { s.close() }
    }

    private suspend fun io.ktor.client.HttpClient.upload(courseId: String, name: String, bytes: ByteArray) =
        submitFormWithBinaryData(
            "/v1/study/upload",
            formData {
                append("courseId", courseId)
                append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") })
            },
        ) { header(HttpHeaders.Authorization, "Bearer $tok") }

    @Test
    fun test_AC3_upload_pdf_is_201_text_follows_and_non_pdf_pptx_is_415() = testApplication {
        val s = Stack()
        try {
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            val bytes = fixture("sample.pdf").readBytes()
            val ok = client.upload("Biology", "lesson.pdf", bytes)
            assertEquals(HttpStatusCode.Created, ok.status)
            val fileId = Json.parseToJsonElement(ok.bodyAsText()).jsonObject["fileId"]!!.jsonPrimitive.content
            assertTrue(fileId.isNotBlank())
            var code = HttpStatusCode.Conflict
            var body = ""
            repeat(100) {
                if (code != HttpStatusCode.OK) {
                    val r = client.get("/v1/study/files/$fileId/text") { header(HttpHeaders.Authorization, "Bearer $tok") }
                    code = r.status; body = r.bodyAsText()
                    if (code != HttpStatusCode.OK) delay(100)
                }
            }
            assertEquals(HttpStatusCode.OK, code, "text should become readable")
            assertTrue(body.length >= 200)
            // the original comes back too
            val orig = client.get("/v1/study/files/$fileId/original") { header(HttpHeaders.Authorization, "Bearer $tok") }
            assertEquals(HttpStatusCode.OK, orig.status)
            // wrong type and unknown ids
            assertEquals(HttpStatusCode.UnsupportedMediaType, client.upload("Biology", "notes.txt", "hello".toByteArray()).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, client.upload("Biology", "fake.pdf", "NOT A PDF AT ALL".toByteArray()).status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/study/files/sfile:nope/text") { header(HttpHeaders.Authorization, "Bearer $tok") }.status)
        } finally { s.close() }
    }

    @Test
    fun test_AC3_scanned_pdf_upload_ends_FAILED_and_text_is_409() = testApplication {
        val s = Stack()
        try {
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            val ok = client.upload("Biology", "scan.pdf", fixture("scanned.pdf").readBytes())
            assertEquals(HttpStatusCode.Created, ok.status)
            val id = Json.parseToJsonElement(ok.bodyAsText()).jsonObject["fileId"]!!.jsonPrimitive.content
            var status = ""
            repeat(100) {
                if (status != "FAILED") {
                    status = s.sdb.db.study_fileQueries.selectById(id).executeAsOneOrNull()?.textStatus ?: ""
                    if (status != "FAILED") delay(100)
                }
            }
            assertEquals("FAILED", status)
            val r = client.get("/v1/study/files/$id/text") { header(HttpHeaders.Authorization, "Bearer $tok") }
            assertEquals(HttpStatusCode.Conflict, r.status)
        } finally { s.close() }
    }

    @Test
    fun test_AC3_study_set_pointer_404_for_unknown_file_and_delete_removes_an_upload() = testApplication {
        val s = Stack()
        try {
            application { module(cfg(), s.store, moodleStatus = s.status, study = s.service) }
            val bad = client.post("/v1/study/files/sfile:nope/study-set") {
                header(HttpHeaders.Authorization, "Bearer $tok"); header(HttpHeaders.ContentType, "application/json")
                setBody("""{"docId":"doc:studyset:x"}""")
            }
            assertEquals(HttpStatusCode.NotFound, bad.status)
            val up = client.upload("Chem", "a.pdf", fixture("sample.pdf").readBytes())
            val id = Json.parseToJsonElement(up.bodyAsText()).jsonObject["fileId"]!!.jsonPrimitive.content
            val ok = client.post("/v1/study/files/$id/study-set") {
                header(HttpHeaders.Authorization, "Bearer $tok"); header(HttpHeaders.ContentType, "application/json")
                setBody("""{"docId":"doc:studyset:$id"}""")
            }
            assertEquals(HttpStatusCode.OK, ok.status)
            val again = client.post("/v1/study/files/$id/study-set") {
                header(HttpHeaders.Authorization, "Bearer $tok"); header(HttpHeaders.ContentType, "application/json")
                setBody("""{"docId":"doc:studyset:$id"}""")
            }
            assertEquals(HttpStatusCode.OK, again.status, "same docId again is a no-op")
            // wait for any extraction to finish before deleting
            repeat(100) {
                val st = s.sdb.db.study_fileQueries.selectById(id).executeAsOneOrNull()?.textStatus
                if (st == "PENDING") delay(100)
            }
            val del = client.delete("/v1/study/files/$id") { header(HttpHeaders.Authorization, "Bearer $tok") }
            assertEquals(HttpStatusCode.OK, del.status)
            assertEquals(1L, s.sdb.db.study_fileQueries.selectById(id).executeAsOne().deleted)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/study/files/$id/text") { header(HttpHeaders.Authorization, "Bearer $tok") }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/v1/study/files/sfile:nope") { header(HttpHeaders.Authorization, "Bearer $tok") }.status)
        } finally { s.close() }
    }

    // ------------------------------------------------------------ release / pins / ops files

    @Test
    fun test_AC7_release_versions_1_7_0_and_versionCode_3() {
        assertTrue(text("desktopApp/build.gradle.kts").contains("packageVersion = \"1.18.0\""))
        val a = text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
    }

    @Test
    fun test_AC5_w9_study_checklist_has_the_manual_lines() {
        val t = text("ops/checklists/w9-study.md").lowercase()
        for (k in listOf("study set", "cards", "quiz", "exam", "upload", "scanned", "offline", "restore", "deploy")) {
            assertTrue(t.contains(k), "w9-study.md should mention '$k'")
        }
    }

    @Test
    fun test_AC2_restore_helper_covers_22_tables_including_the_five_study_ones() {
        val t = text("ops/assistant-restore-test.sh")
        for (n in listOf("course", "study_file", "card", "quiz", "exam")) {
            assertTrue(Regex("\\b$n\\b").containsMatchIn(t), "restore helper lists $n")
        }
        val tables = Regex("^TABLES=\"([^\"]*)\"", RegexOption.MULTILINE).find(t)!!.groupValues[1].trim().split(Regex("\\s+"))
        assertEquals(34, tables.size, "restore helper TABLES: $tables")
    }

    @Test
    fun test_AC2_dependencies_pdfbox_and_poi_in_catalog() {
        val t = text("gradle/libs.versions.toml")
        assertTrue(t.contains("pdfbox = \"3.0.8\""))
        assertTrue(t.contains("poi = \"5.5.1\""))
    }
}
