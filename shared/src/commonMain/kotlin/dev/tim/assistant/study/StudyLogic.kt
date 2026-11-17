package dev.tim.assistant.study

import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Study_file
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Largest piece of data sent to the model in one call (characters, file name included). */
const val STUDY_CHUNK_MAX = 12_000

/** Study files are limited to this many bytes (50 MiB). */
const val STUDY_FILE_MAX_BYTES = 52_428_800L

/** One flash card as written by the model. */
data class StudyCard(val front: String, val back: String)

/** One multiple-choice question: four options, [answerIndex] 0..3 is the right one. */
data class QuizQuestion(val q: String, val options: List<String>, val answerIndex: Int)

/**
 * Splits [text] into pieces of at most [max] characters. A piece ends after the last blank line (`\n\n`)
 * that fits, else after the last newline, else it is cut hard (never inside a surrogate pair). The pieces
 * joined together give back [text].
 */
fun chunkText(text: String, max: Int = STUDY_CHUNK_MAX): List<String> {
    require(max >= 2) { "max too small" }
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        if (text.length - start <= max) {
            out += text.substring(start)
            break
        }
        val window = text.substring(start, start + max)
        var cut = window.lastIndexOf("\n\n").let { if (it > 0) it + 2 else -1 }
        if (cut < 0) cut = window.lastIndexOf('\n').let { if (it >= 0) it + 1 else -1 }
        if (cut <= 0) {
            cut = max
            if (window[max - 1].isHighSurrogate()) cut = max - 1
        }
        out += text.substring(start, start + cut)
        start += cut
    }
    return out
}

/** The cards in the model's answer: trimmed, non-blank pairs of at most 2000 characters each, at most 40. */
fun parseCards(text: String): List<StudyCard> {
    val array = try {
        val root = Json.parseToJsonElement(text)
        (root as? JsonObject)?.get("cards") as? JsonArray ?: root as? JsonArray
    } catch (e: Exception) {
        null
    } ?: return emptyList()
    val cards = ArrayList<StudyCard>()
    for (item in array) {
        val obj = item as? JsonObject ?: continue
        val front = (obj["front"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: continue
        val back = (obj["back"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: continue
        if (front.isEmpty() || back.isEmpty() || front.length > 2000 || back.length > 2000) continue
        cards += StudyCard(front, back)
        if (cards.size == 40) break
    }
    return cards
}

/**
 * The ten questions in the model's answer (an object with "questions" or a bare array), or null when anything is
 * wrong: not exactly 10, a blank question, not exactly 4 non-blank options, or an answer index outside 0..3.
 */
fun parseQuiz(text: String): List<QuizQuestion>? {
    val array = try {
        val root = Json.parseToJsonElement(text)
        (root as? JsonObject)?.get("questions") as? JsonArray ?: root as? JsonArray
    } catch (e: Exception) {
        null
    } ?: return null
    if (array.size != 10) return null
    val out = ArrayList<QuizQuestion>()
    for (item in array) {
        val obj = item as? JsonObject ?: return null
        val q = (obj["q"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        if (q.isEmpty()) return null
        val options = (obj["options"] as? JsonArray) ?: return null
        if (options.size != 4) return null
        val texts = options.map { e ->
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        }
        val index = (obj["answerIndex"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: return null
        if (index !in 0..3) return null
        out += QuizQuestion(q, texts, index)
    }
    return out
}

/** The questions as the JSON array stored in `quiz.questionsJson`. */
fun quizQuestionsJson(questions: List<QuizQuestion>): String = buildJsonArray {
    for (question in questions) {
        add(
            buildJsonObject {
                put("q", question.q)
                put("options", buildJsonArray { question.options.forEach { add(JsonPrimitive(it)) } })
                put("answerIndex", question.answerIndex)
            },
        )
    }
}.toString()

/** The questions of a stored quiz, or an empty list when the JSON is not a valid quiz. */
fun quizQuestionsFrom(questionsJson: String): List<QuizQuestion> = parseQuiz(questionsJson) ?: emptyList()

/** Number of right answers; [answers] holds the chosen option index per question (null = unanswered). */
fun quizScore(questions: List<QuizQuestion>, answers: List<Int?>): Int =
    questions.indices.count { answers.getOrNull(it) == questions[it].answerIndex }

private fun parseDate(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (e: Exception) {
    null
}

/** "today", "tomorrow" or "in N days" counting calendar days from [today]; "past" when the exam day is over. */
fun examCountdownText(examDate: LocalDate, today: LocalDate): String {
    val days = today.daysUntil(examDate)
    return when {
        days < 0 -> "past"
        days == 0 -> "today"
        days == 1 -> "tomorrow"
        else -> "in $days days"
    }
}

fun examCountdownText(examDate: String, today: String): String {
    val exam = parseDate(examDate) ?: return ""
    val now = parseDate(today) ?: return ""
    return examCountdownText(exam, now)
}

/** Exams on or after [today] (YYYY-MM-DD), ordered by date, time (no time first) and id; deleted ones are skipped. */
fun upcomingExams(exams: List<Exam>, today: String, limit: Int = 3): List<Exam> =
    exams.filter { it.deleted == 0L && it.localDate >= today }
        .sortedWith(compareBy<Exam>({ it.localDate }, { it.time ?: "" }, { it.id }))
        .take(limit)

/** True when the exam's day is before [today]. */
fun isExamPast(exam: Exam, today: String): Boolean = exam.localDate < today

/** One short line about a file's text extraction state. */
fun studyFileStatusText(file: Study_file): String = when (file.textStatus) {
    "PENDING" -> "Reading file..."
    "READY" -> if (file.studySetDocId != null) "Ready - study set made" else "Ready"
    "FAILED" -> "No text found (scanned?)"
    else -> file.textStatus
}

/** The address that opens the Moodle login and hands the app a token (the server's public address plus a random passport). */
fun moodleLaunchUrl(baseUrl: String, passport: Int): String =
    baseUrl.trim().trimEnd('/') +
        "/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=$passport&urlscheme=moodlemobile"

/** Courses to show first (checked Moodle courses and typed-in ones, by name) and the switched-off Moodle courses (by name). */
fun sortCourses(courses: List<Course>): Pair<List<Course>, List<Course>> {
    val byName = compareBy<Course>({ it.name.lowercase() }, { it.id })
    val (active, off) = courses.partition { it.moodleId == null || it.enabled != 0L }
    return active.sortedWith(byName) to off.sortedWith(byName)
}

/** True for a real YYYY-MM-DD date. */
fun isValidDate(value: String): Boolean = parseDate(value) != null

/** HH:mm with a real hour and minute. */
fun isValidTime(value: String): Boolean {
    val m = Regex("""(\d{2}):(\d{2})""").matchEntire(value) ?: return false
    return m.groupValues[1].toInt() in 0..23 && m.groupValues[2].toInt() in 0..59
}
