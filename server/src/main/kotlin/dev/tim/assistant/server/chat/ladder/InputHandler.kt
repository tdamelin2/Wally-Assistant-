package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.mentionsDreams
import dev.tim.assistant.data.SPENDING_CATEGORIES
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.server.chat.PendingAction
import java.time.LocalDate

/** The lowest sorter score for which a suggestion button is offered. Set by SuggestCalibrationTest (raised in 0.02 steps). */
const val SUGGEST_MIN_SCORE = 0.64

/** Only intents within this of the best allowed score are offered together. */
const val SUGGEST_BAND = 0.05

/** At most this many suggestions in one question. */
const val SUGGEST_MAX = 3

/** The longest message that can get a question. */
const val HANDLER_MAX_CHARS = 200

/**
 * The input handler (PLAN 20.6): when Tim's message is close to a command but code cannot act on it, Wally asks one question with
 * buttons instead of guessing. It never saves anything itself: a question only carries the actions a later tap or exact typed answer
 * runs. Both functions return null (no question) for Hebrew, a message that mentions dreams, one over 200 characters, one with a
 * negation word, and one typed on an earlier day than now ([typedToday] false).
 */
class InputHandler(private val targets: Targets) {
    companion object {
        private val ADD_INTENTS = setOf(
            IntentName.CREATE_TASK, IntentName.ADD_SPENDING, IntentName.ADD_MEAL, IntentName.ADD_CHECKIN, IntentName.ADD_JOURNAL,
        )

        /** The button that sends the original message on to a model. */
        fun elseChip(): ChoiceDto = ChoiceDto("suggest", "else", SlotFill.SOMETHING_ELSE, "")

        /** The question for an add that still lacks one argument; the buttons are `slot` chips and the pending action holds what is known. */
        fun missingQuestion(tool: String, args: Map<String, String>, missing: String): CodeOutcome? = when (missing) {
            "category" -> CodeOutcome(
                ReplyTemplates.whichCategory(),
                SPENDING_CATEGORIES.map { ChoiceDto("slot", "category=$it", it, "") } + elseChip(),
                PendingAction(tool, args), brain = "ask",
            )
            "type" -> CodeOutcome(
                ReplyTemplates.mealOrSnack(),
                listOf(ChoiceDto("slot", "type=MEAL", "Meal", ""), ChoiceDto("slot", "type=SNACK", "Snack", ""), elseChip()),
                PendingAction(tool, args), brain = "ask",
            )
            else -> null
        }

        /** The words that mark a message as meant for the journal (a sorter journal suggestion needs one). */
        private val JOURNAL_CUE = Regex(
            "(?<![\\p{L}\\p{N}])(?:journal|diary|notes?|write\\s+down|written\\s+down|jot(?:\\s+down)?|record)(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

        private fun single(f: Filled): CodeOutcome = CodeOutcome(
            ReplyTemplates.didYouMean(f.label),
            listOf(ChoiceDto("suggest", "0", "Yes", ""), elseChip()),
            brain = "ask", options = listOf(PendingAction(f.tool, f.args)),
        )

        private fun several(list: List<Filled>): CodeOutcome = CodeOutcome(
            ReplyTemplates.didYouMeanSeveral(),
            list.mapIndexed { i, f -> ChoiceDto("suggest", "$i", f.label, "") } + elseChip(),
            brain = "ask", options = list.map { PendingAction(it.tool, it.args) },
        )

        /** The tool an add intent of the router runs. */
        private fun toolOf(intent: IntentName): String? = when (intent) {
            IntentName.CREATE_TASK -> "create_task"
            IntentName.ADD_SPENDING -> "add_spending"
            IntentName.ADD_MEAL -> "add_meal"
            IntentName.ADD_CHECKIN -> "add_checkin"
            IntentName.ADD_JOURNAL -> "add_journal"
            else -> null
        }
    }

    private fun eligible(text: String, typedToday: Boolean): Boolean =
        typedToday && text.trim().length <= HANDLER_MAX_CHARS && !Guards.hasHebrew(text) && !mentionsDreams(text) && !Guards.negated(text)

    /**
     * The questions that need no sorter: a missing piece ("Which category?", "Was that a meal or a snack?") and a guard-word suggestion
     * ("Did you mean: ...?") for a command that only a "not a real event" word held back.
     */
    fun early(text: String, today: LocalDate, typedToday: Boolean = true): CodeOutcome? {
        if (!eligible(text, typedToday)) return null
        IntentRouter.partial(text, today)?.let { f -> return f.missing?.let { missingQuestion(f.tool, f.args, it) } }
        if (Guards.softBlocked(text) && !Guards.isQuestion(text)) {
            val match = IntentRouter.routeUnguarded(text, today) as? RouteResult.Match ?: return null
            val tool = toolOf(match.intent) ?: return null
            val f = SlotFill.filled(tool, match.slots, today) ?: return null
            if (f.missing != null) return null
            return single(f)
        }
        return null
    }

    /**
     * Up to [SUGGEST_MAX] suggestions from the sorter's ranking. Runs when the message is not blocked, or blocked only by a "not a real
     * event" word and not a question. A top-ranked `none` gives no question. A question only gets ask intents, a soft-blocked message
     * only add intents.
     */
    fun suggest(
        text: String, today: LocalDate, ranked: Ranked, typedToday: Boolean = true,
        minScore: Double = SUGGEST_MIN_SCORE, band: Double = SUGGEST_BAND,
    ): CodeOutcome? {
        if (!eligible(text, typedToday)) return null
        val question = Guards.isQuestion(text)
        val soft = Guards.softBlocked(text)
        if (Guards.blocked(text) && !(soft && !question)) return null
        val top = ranked.scores.firstOrNull() ?: return null
        if (top.first == IntentName.NONE) return null
        val allowed = ranked.scores.filter { (intent, _) ->
            when {
                question -> intent.isAsk
                soft -> intent in ADD_INTENTS
                else -> intent != IntentName.NONE && intent != IntentName.ADD_DREAM
            }
        }
        // a journal button needs a journal word: free feelings never become one
        val journalCue = JOURNAL_CUE.containsMatchIn(text)
        // the band is chosen among intents whose arguments can be read from the text
        val usable = allowed.mapNotNull { (intent, score) ->
            if (intent == IntentName.ADD_JOURNAL && !journalCue) return@mapNotNull null
            SlotFill.forIntent(intent, text, today, targets)?.let { Triple(intent, score, it) }
        }
        val best = usable.firstOrNull()?.second ?: return null
        // conversation ranked close to the best action: no question
        val none = ranked.scores.firstOrNull { it.first == IntentName.NONE }?.second
        if (none != null && none >= best - band) return null
        val picked = usable.filter { it.second >= minScore && it.second >= best - band }.take(SUGGEST_MAX)
        val fills = SlotFill.distinct(picked.map { it.third })
        return when (fills.size) {
            0 -> null
            1 -> fills[0].let { f ->
                // a meal without its type asks "meal or snack" at once instead of a "did you mean" first
                if (f.tool == "add_meal" && f.missing == "type") missingQuestion(f.tool, f.args, "type") else single(f)
            }
            else -> several(fills)
        }
    }
}
