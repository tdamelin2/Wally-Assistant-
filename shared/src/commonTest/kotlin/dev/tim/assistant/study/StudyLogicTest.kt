package dev.tim.assistant.study

import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Study_file
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyLogicTest {
    @Test
    fun sha256_matches_known_vectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun chunk_boundaries_12000_and_12001() {
        assertEquals(emptyList(), chunkText(""))
        assertEquals(1, chunkText("a".repeat(12_000)).size)
        val two = chunkText("a".repeat(12_001))
        assertEquals(listOf(12_000, 1), two.map { it.length })
    }

    @Test
    fun chunks_prefer_paragraph_then_line_then_hard_split() {
        val para = "a".repeat(7_000) + "\n\n" + "b".repeat(7_000)
        assertEquals(listOf("a".repeat(7_000) + "\n\n", "b".repeat(7_000)), chunkText(para))
        val line = "a".repeat(7_000) + "\n" + "b".repeat(7_000)
        assertEquals(listOf("a".repeat(7_000) + "\n", "b".repeat(7_000)), chunkText(line))
        val none = chunkText("x".repeat(30_000))
        assertEquals(listOf(12_000, 12_000, 6_000), none.map { it.length })
        for (text in listOf(para, line, "x".repeat(30_000), "q\n\n".repeat(9_000))) {
            val parts = chunkText(text)
            assertEquals(text, parts.joinToString(""))
            assertTrue(parts.all { it.length <= 12_000 })
        }
    }

    @Test
    fun a_hard_split_never_cuts_a_surrogate_pair() {
        val pair = "😀"
        val text = "a".repeat(11_999) + pair + "b".repeat(100)
        val parts = chunkText(text)
        assertEquals(text, parts.joinToString(""))
        assertEquals(11_999, parts[0].length)
        assertTrue(parts[1].startsWith(pair))
        for (p in parts) assertTrue(p.isEmpty() || !p.last().isHighSurrogate())
    }

    private fun cardsJson(n: Int, front: (Int) -> String = { "q$it" }, back: (Int) -> String = { "a$it" }) =
        """{"cards":[""" + (0 until n).joinToString(",") { """{"front":"${front(it)}","back":"${back(it)}"}""" } + "]}"

    @Test
    fun parse_cards_keeps_valid_pairs_and_caps_at_40() {
        assertEquals(9, parseCards(cardsJson(9)).size)
        assertEquals(10, parseCards(cardsJson(10)).size)
        assertEquals(40, parseCards(cardsJson(40)).size)
        assertEquals(40, parseCards(cardsJson(41)).size)
        assertEquals("q0", parseCards(cardsJson(41)).first().front)
    }

    @Test
    fun parse_cards_trims_and_drops_blank_or_long_or_bad_entries() {
        val json = """{"cards":[{"front":"  hi ","back":" there "},{"front":"","back":"x"},{"front":"x","back":"  "},{"front":"x"},""" +
            """{"front":"${"a".repeat(2001)}","back":"x"},{"front":"${"a".repeat(2000)}","back":"${"b".repeat(2000)}"},"junk"]}"""
        val cards = parseCards(json)
        assertEquals(2, cards.size)
        assertEquals(StudyCard("hi", "there"), cards[0])
        assertEquals(emptyList(), parseCards("not json"))
        assertEquals(emptyList(), parseCards("""{"other":1}"""))
    }

    private fun quizJson(n: Int = 10, options: Int = 4, answer: Int = 1, q: String = "Q?") =
        """{"questions":[""" + (0 until n).joinToString(",") {
            """{"q":"$q $it","options":[""" + (0 until options).joinToString(",") { o -> "\"opt$o\"" } + """],"answerIndex":$answer}"""
        } + "]}"

    @Test
    fun parse_quiz_needs_exactly_ten_valid_questions() {
        assertEquals(10, parseQuiz(quizJson())?.size)
        assertNull(parseQuiz(quizJson(n = 9)))
        assertNull(parseQuiz(quizJson(n = 11)))
        assertNull(parseQuiz(quizJson(options = 3)))
        assertNull(parseQuiz(quizJson(options = 5)))
        assertNull(parseQuiz(quizJson(answer = 4)))
        assertNull(parseQuiz(quizJson(answer = -1)))
        assertNull(parseQuiz(quizJson().replace("Q? 0", "  ")))
        assertNull(parseQuiz("nope"))
        assertNull(parseQuiz("""{"questions":[{"q":"a","options":["1","2","3",""],"answerIndex":0}]}"""))
    }

    @Test
    fun stored_quiz_json_round_trips_and_scores() {
        val questions = parseQuiz(quizJson())!!
        val stored = quizQuestionsJson(questions)
        val again = quizQuestionsFrom(stored)
        assertEquals(questions, again)
        assertEquals(10, quizScore(again, List(10) { 1 }))
        assertEquals(0, quizScore(again, List(10) { 2 }))
        assertEquals(3, quizScore(again, List(10) { if (it < 3) 1 else null }))
        assertEquals(emptyList(), quizQuestionsFrom("garbage"))
    }

    private fun file(status: String, doc: String? = null) = Study_file("sfile:a", 1, 0, 0, "SYNCED", "course:1", "a.pdf", "MOODLE", 10, status, doc)

    @Test
    fun status_text_and_launch_url() {
        assertEquals("Reading file...", studyFileStatusText(file("PENDING")))
        assertEquals("Ready", studyFileStatusText(file("READY")))
        assertEquals("Ready - study set made", studyFileStatusText(file("READY", "doc:x")))
        assertEquals("No text found (scanned?)", studyFileStatusText(file("FAILED")))
        assertEquals(
            "https://moodle.example.edu/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=42&urlscheme=moodlemobile",
            moodleLaunchUrl("https://moodle.example.edu/", 42),
        )
    }

    private fun course(id: String, name: String, moodleId: Long?, enabled: Long) = Course(id, 1, 0, 0, "SYNCED", moodleId, name, enabled)

    @Test
    fun sort_courses_puts_enabled_and_manual_first() {
        val (first, rest) = sortCourses(
            listOf(
                course("c1", "Zoology", 1, 1), course("c2", "algebra", 2, 0), course("c3", "Biology", 3, 1),
                course("course:manual:x", "Notes", null, 1), course("c5", "Chemistry", 5, 0),
            ),
        )
        assertEquals(listOf("Biology", "Notes", "Zoology"), first.map { it.name })
        assertEquals(listOf("algebra", "Chemistry"), rest.map { it.name })
        assertNotNull(first.firstOrNull())
    }
}
