package dev.tim.assistant.research

import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.researchQuestionKey
import dev.tim.assistant.db.Research
import dev.tim.assistant.study.sha256Hex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val RESEARCH_NUTRITION = "NUTRITION"
const val RESEARCH_DEFINITION = "DEFINITION"
const val RESEARCH_APP_CATEGORY = "APP_CATEGORY"
const val RESEARCH_OTHER = "OTHER"

/** The categories that run automatically from a validated item. */
val AUTOMATIC_CATEGORIES = listOf(RESEARCH_NUTRITION, RESEARCH_DEFINITION, RESEARCH_APP_CATEGORY)
val RESEARCH_CATEGORIES = AUTOMATIC_CATEGORIES + RESEARCH_OTHER

const val RESEARCH_JOB_KIND = "RESEARCH"
const val RESEARCH_QUESTION_JOB_PREFIX = "research_question:"
const val RESEARCH_ITEM_MAX = 60
const val RESEARCH_QUESTION_MAX = 2_000
const val RESEARCH_REFRESH_MS = 180L * 24 * 60 * 60 * 1000
const val CODEX_LIMIT_TEXT = "Codex limit reached"
const val CODEX_UNAVAILABLE_TEXT = "Codex is not set up on the server"
const val CODEX_LIMIT_NOTICE = "Codex is out of usage. Research and Look up will work again when your ChatGPT allowance resets."
const val CODEX_LIMIT_ROW_TEXT = "Codex is out of usage - try again later"
const val CODEX_LIMIT_WINDOW_MS = 24L * 60 * 60 * 1000

private val CODEX_LIMIT_WORDS = listOf("usage limit", "rate limit", "quota", "too many requests")

/** True when text from Codex (stdout, stderr or its answer) says the allowance is used up. Case-insensitive. */
fun isCodexLimitText(text: String): Boolean {
    val lower = text.lowercase()
    return CODEX_LIMIT_WORDS.any { it in lower }
}

/**
 * The message for Tim when the newest RESEARCH or MEAL_LOOKUP job failed with [CODEX_LIMIT_TEXT] within the last
 * 24 hours (exactly 24 h still counts), else null.
 */
fun codexLimitNotice(lastLimitFailureAt: Long?, now: Long): String? =
    if (lastLimitFailureAt != null && now - lastLimitFailureAt <= CODEX_LIMIT_WINDOW_MS) CODEX_LIMIT_NOTICE else null

/** What a meal row shows for a Look up job's error: the friendly limit text for a limit failure, else the error as is. */
fun mealLookupErrorText(error: String?): String? =
    if (error == CODEX_LIMIT_TEXT) CODEX_LIMIT_ROW_TEXT else error

/**
 * An item for an automatic question: 1-60 characters of letters (any language), digits, space, `-`, `'` and `.`,
 * no `@`, and no run of 5 or more digits.
 */
fun isValidItem(item: String): Boolean {
    if (item.isBlank() || item.length > RESEARCH_ITEM_MAX) return false
    var digits = 0
    for (c in item) {
        if (c.isDigit()) {
            digits++
            if (digits >= 5) return false
        } else {
            digits = 0
            if (!(c.isLetter() || c == ' ' || c == '-' || c == '\'' || c == '.')) return false
        }
    }
    return true
}

/** The fixed question text for an automatic [category] (SPEC section 6); [item] must pass [isValidItem]. */
fun templateQuestion(category: String, item: String): String {
    val x = item.trim()
    return when (category) {
        RESEARCH_NUTRITION -> "Nutrition facts per 100 g of $x. Return kcal, proteinG, carbsG, fatG and source URLs."
        RESEARCH_DEFINITION -> "Define $x in one short paragraph with source URLs."
        RESEARCH_APP_CATEGORY -> "What kind of app is $x? One sentence and a category word, with source URLs."
        else -> throw IllegalArgumentException("no template for $category")
    }
}

/** Lowercase, trim, collapse runs of whitespace (the rule the research row keys use). */
fun questionKey(text: String): String = researchQuestionKey(text)

/** `research:<category>:<sha256Hex(questionKey(question))>`, the id of a research row. */
fun researchId(category: String, question: String): String = "research:$category:${sha256Hex(questionKey(question))}"

/** NUTRITION and DEFINITION never expire; the other categories expire 180 days after [askedAt]. */
fun expiresAtFor(category: String, askedAt: Long): Long? =
    if (category == RESEARCH_NUTRITION || category == RESEARCH_DEFINITION) null else askedAt + RESEARCH_REFRESH_MS

/** What [ResearchService] did with a request. */
sealed interface ResearchRequest {
    data class Cached(val row: Research) : ResearchRequest
    data class Queued(val jobId: String) : ResearchRequest

    /** The question is not automatic: show an approval card with exactly [question]. */
    data class NeedsApproval(val question: String) : ResearchRequest
}

/** What the runner (Codex) returns for one question. [sources] are http(s) URLs. */
data class ResearchRow(
    val category: String,
    val question: String,
    val answerText: String,
    val answerJson: String,
    val sources: List<String>,
)

/** Asks one research question. Throws [JobFailedNow] for "unavailable" and "limit", any other exception is a failed attempt. */
fun interface ResearchRunner {
    suspend fun ask(category: String, question: String): ResearchRow
}

internal val sourcesSerializer = ListSerializer(String.serializer())

/** Queues research and reads the research memory. [clock] returns epoch milliseconds. */
class ResearchService(private val repo: Repository, private val clock: () -> Long) {

    /**
     * Cache first (a valid row means no job), then the job already running for the same question, else a new job.
     * [item] is an item for the automatic categories and the question text for OTHER (which is never queued here).
     */
    suspend fun request(category: String, item: String): ResearchRequest {
        require(category in RESEARCH_CATEGORIES) { "unknown category" }
        if (category == RESEARCH_OTHER) {
            val question = item.trim()
            require(question.length in 1..RESEARCH_QUESTION_MAX) { "bad question" }
            repo.researchValid(researchId(category, question), clock())?.let { return ResearchRequest.Cached(it) }
            return ResearchRequest.NeedsApproval(question)
        }
        require(isValidItem(item)) { "bad item" }
        val question = templateQuestion(category, item)
        val id = researchId(category, question)
        repo.researchValid(id, clock())?.let { return ResearchRequest.Cached(it) }
        repo.activeJobFor(RESEARCH_JOB_KIND, id)?.let { return ResearchRequest.Queued(it.id) }
        val input = buildJsonObject {
            put("category", category)
            put("item", item.trim())
            put("researchId", id)
        }
        return ResearchRequest.Queued(repo.queueJob(RESEARCH_JOB_KIND, input.toString()))
    }

    /** Queues an approved OTHER [question] under the fixed job id `research_question:<approvalId>` (insert-once). */
    suspend fun queueApproved(question: String, approvalId: String): ResearchRequest {
        val q = question.trim()
        require(q.length in 1..RESEARCH_QUESTION_MAX) { "bad question" }
        val id = researchId(RESEARCH_OTHER, q)
        repo.researchValid(id, clock())?.let { return ResearchRequest.Cached(it) }
        repo.activeJobFor(RESEARCH_JOB_KIND, id)?.let { return ResearchRequest.Queued(it.id) }
        val input = buildJsonObject {
            put("category", RESEARCH_OTHER)
            put("question", q)
            put("researchId", id)
        }
        return ResearchRequest.Queued(repo.queueJobWithId(RESEARCH_QUESTION_JOB_PREFIX + approvalId, RESEARCH_JOB_KIND, input.toString()))
    }
}
