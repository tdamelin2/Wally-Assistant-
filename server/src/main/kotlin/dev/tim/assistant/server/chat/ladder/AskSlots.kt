package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.data.SPENDING_CATEGORIES
import java.time.LocalDate

/**
 * The slots of a read-only question that was found by meaning and not by a rule row (PLAN 20.4 step 5). Shared by the sorter answer in
 * [Ladder] and by the "Show: ..." suggestions of [SlotFill], so both read a message the same way.
 */
internal object AskSlots {
    private val NEXT_EXAM = Regex("(?<![\\p{L}\\p{N}])next\\s+(?:exams?|tests?|finals?)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /** The words that name a health metric in a reworded question. */
    private val METRIC_WORDS = mapOf("sleep" to "sleep", "slept" to "sleep", "steps" to "steps", "walk" to "steps", "heart" to "heart", "hr" to "heart")

    fun lean(intent: IntentName): Lean = when (intent) {
        IntentName.ASK_TODAY, IntentName.ASK_EVENTS, IntentName.ASK_TASKS -> Lean.FUTURE
        else -> Lean.PAST
    }

    private fun categoryIn(text: String): String? =
        SlotParsers.spendingCategory(text)
            ?: SPENDING_CATEGORIES.filter { c -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(c)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text) }.singleOrNull()

    /**
     * Turns an ask intent into the intent to act on and its slots. Null drops the result: two period phrases, a day word code cannot
     * place, a needed slot that cannot be read, exams by date.
     */
    fun read(accepted: IntentName, text: String, today: LocalDate): Pair<IntentName, Map<String, String>>? {
        val scan = Periods.find(text, today, lean(accepted))
        if (scan == Scan.Many) return null
        val found = (scan as? Scan.One)?.found
        // "my next exam" names no day: the next-exam wording is not a day word for the exam intent
        val dayWordText = if (accepted == IntentName.ASK_EXAM) NEXT_EXAM.replace(text, " ") else text
        if (found == null && Guards.otherDay(dayWordText)) return null
        if (found != null && Guards.otherDay(found.rest)) return null
        val period = found?.period
        val past = lean(accepted) == Lean.PAST
        if (past && period != null && period.from.isAfter(today)) return null
        if (period?.word == "last_night" && accepted != IntentName.ASK_HEALTH) return null
        fun slots(vararg p: Pair<String, String>) = p.toMap()
        return when (accepted) {
            IntentName.ASK_TODAY ->
                if (period == null || period.word == "today") IntentName.ASK_TODAY to emptyMap()
                else IntentName.ASK_EVENTS to slots("period" to period.word)
            IntentName.ASK_EVENTS -> IntentName.ASK_EVENTS to slots("period" to (period?.word ?: "today"))
            IntentName.ASK_TASKS -> IntentName.ASK_TASKS to (period?.let { slots("period" to it.word) } ?: emptyMap())
            IntentName.ASK_SPENDING_WEEK -> {
                val category = categoryIn(text)
                when {
                    period == null && category == null -> IntentName.ASK_SPENDING_WEEK to emptyMap()
                    period == null -> IntentName.ASK_SPENDING to slots("period" to "this_week", "category" to category!!)
                    period.word == "this_week" && category == null -> IntentName.ASK_SPENDING_WEEK to emptyMap()
                    else -> IntentName.ASK_SPENDING to (slots("period" to period.word) + (category?.let { slots("category" to it) } ?: emptyMap()))
                }
            }
            IntentName.ASK_SPENDING -> {
                val category = categoryIn(text)
                IntentName.ASK_SPENDING to (slots("period" to (period?.word ?: "this_week")) + (category?.let { slots("category" to it) } ?: emptyMap()))
            }
            IntentName.ASK_EXAM -> if (scan != Scan.None || Guards.otherDay(NEXT_EXAM.replace(text, " "))) null else IntentName.ASK_EXAM to emptyMap()
            IntentName.ASK_HEALTH -> {
                val metrics = METRIC_WORDS.entries.filter { (w, _) -> Regex("(?<![\\p{L}\\p{N}])$w(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text) }.map { it.value }.toSet()
                val metric = metrics.singleOrNull() ?: return null
                if (period?.word == "last_night" && metric != "sleep") return null
                if (period != null && !(period.singleDay || period.word == "this_week" || period.word == "last_week")) return null
                IntentName.ASK_HEALTH to slots("metric" to metric, "period" to (period?.word ?: "today"))
            }
            IntentName.ASK_MEALS -> {
                if (period != null && !period.singleDay) return null
                val what = if (Regex("calorie|kcal", RegexOption.IGNORE_CASE).containsMatchIn(text)) "calories" else "list"
                IntentName.ASK_MEALS to slots("what" to what, "period" to (period?.word ?: "today"))
            }
            IntentName.ASK_CHECKIN -> {
                if (period != null && !(period.singleDay || period.word == "this_week" || period.word == "last_week")) return null
                IntentName.ASK_CHECKIN to slots("period" to (period?.word ?: "today"))
            }
            else -> null
        }
    }
}
