package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.DECISION_ACTIONS
import dev.tim.assistant.ai.ModelDecision
import dev.tim.assistant.ai.tools.FIND_KINDS
import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.ToolCheck
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.server.chat.PendingAction
import dev.tim.assistant.server.chat.ServerToolExecutor
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a validated decision turns into: the reply text (always from a template, except a plain answer), chips and a pending action. */
data class ValidatedOutcome(
    val text: String,
    val choices: List<ChoiceDto> = emptyList(),
    val pending: PendingAction? = null,
    /** The suggestion buttons' actions of a suggest-only add (the "Yes" chip runs `options[0]`). */
    val options: List<PendingAction> = emptyList(),
)

/**
 * Turns a parsed [ModelDecision] into a safe outcome (PLAN 18.6 step 4). Parsing authorises nothing: the action must be one of
 * the fixed names, the arguments pass the tool's own check, names are resolved to ids here (any `id` the model sent is dropped),
 * and the write goes through [ServerToolExecutor.handle] with that one tool allowed. A model's own text is shown only for
 * `kind = answer`.
 */
class DecisionValidator(
    private val targets: Targets,
    private val read: ReadTools,
    private val clock: () -> Long,
) {
    companion object {
        const val ANSWER_MAX = 600
        /** The item kind each `update_*` action changes (a [Targets] kind). */
        val UPDATE_KINDS: Map<String, String> = mapOf(
            "update_task" to "task", "update_exam" to "exam", "update_goal" to "goal", "update_doc" to "doc",
            "update_meal" to "meal", "update_spending" to "spending", "update_checkin" to "checkin", "update_journal" to "journal",
        )
        private val ADDS = ReplyTemplates.ADD_ACTIONS.toSet()
        private val NOT_CODE_TARGETED = setOf("task", "exam", "goal", "doc")
        private val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
    }

    suspend fun apply(decision: ModelDecision, executor: ServerToolExecutor, dreams: Boolean, messageId: String = "", suggestOnly: Boolean = false): ValidatedOutcome {
        executor.dreamAllowed = dreams
        return when (decision.kind) {
            "answer" -> answer(decision.reply)
            "action" -> action(decision, executor, dreams, messageId, suggestOnly)
            else -> ValidatedOutcome(ReplyTemplates.needsLaptop())
        }
    }

    private fun answer(reply: String?): ValidatedOutcome {
        val text = reply?.trim().orEmpty()
        if (text.isEmpty() || ID_LIKE.containsMatchIn(text)) return ValidatedOutcome(ReplyTemplates.sayAgain())
        var end = minOf(text.length, ANSWER_MAX)
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end--
        return ValidatedOutcome(text.substring(0, end).trim())
    }

    /** The text arguments a model may set for [action]: the tool's own fields without `id`, blank values dropped. */
    private fun cleanArgs(action: String, args: Map<String, String>): Map<String, String> {
        val props = (Tools.find(action)?.parameters?.get("properties") as? JsonObject)?.keys ?: emptySet()
        val banned = setOf("id") + if (action == "create_task") setOf("goalId") else emptySet()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in args) if (k in props && k !in banned && v.isNotBlank()) out[k] = v.trim()
        return out
    }

    private fun toJson(args: Map<String, String>): JsonObject = buildJsonObject { for ((k, v) in args) put(k, v) }

    private fun cardOrSay(result: dev.tim.assistant.ai.tools.ToolResult): ValidatedOutcome =
        if (result.cardApprovalIds.isNotEmpty()) ValidatedOutcome(ReplyTemplates.card()) else ValidatedOutcome(ReplyTemplates.sayAgain())

    private suspend fun action(decision: ModelDecision, executor: ServerToolExecutor, dreams: Boolean, messageId: String, suggestOnly: Boolean): ValidatedOutcome {
        val name = decision.action?.trim().orEmpty()
        if (name !in DECISION_ACTIONS) return ValidatedOutcome(ReplyTemplates.needsLaptop())
        if (name == "add_dream" && !dreams) return ValidatedOutcome(ReplyTemplates.needsLaptop())
        if (name == "find") return find(decision)
        val args = cleanArgs(name, decision.args)
        if (name in ADDS) return if (suggestOnly && name != "add_dream") suggest(name, args, decision.args, executor, dreams) else add(name, args, executor, messageId)
        val kind = UPDATE_KINDS[name] ?: return ValidatedOutcome(ReplyTemplates.needsLaptop())
        return edit(name, kind, decision.target.orEmpty(), args, executor)
    }

    /** The arguments a suggest-only add keeps: exactly the ones its label shows (a `date` and `time` are handled apart). */
    private val SUGGEST_KEEP: Map<String, Set<String>> = mapOf(
        "add_spending" to setOf("amount", "category", "note"),
        "add_meal" to setOf("type", "text"),
        "add_journal" to setOf("text"),
        "add_checkin" to setOf("mood", "energy", "sleepQuality", "stress"),
        "create_task" to setOf("title", "dueDate"),
    )

    /**
     * An add by the home server's model never saves (PLAN 20.8): it becomes one "Did you mean" button, or a card for a spending or a
     * meal on a past day, or "say it again". Labels come from [SlotFill], the same builder the handler uses.
     */
    private suspend fun suggest(name: String, args: Map<String, String>, rawArgs: Map<String, String>, executor: ServerToolExecutor, dreams: Boolean): ValidatedOutcome {
        if (dreams) return ValidatedOutcome(ReplyTemplates.sayAgain())
        val keep = SUGGEST_KEEP[name] ?: return ValidatedOutcome(ReplyTemplates.sayAgain())
        val today = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate()
        val shown = SlotFill.project(name, args).filterKeys { it in keep }
        val date = rawArgs["date"]?.trim()?.takeIf { it.isNotEmpty() }
        if (date != null && date != today.toString()) {
            if (name != "add_spending" && name != "add_meal") return ValidatedOutcome(ReplyTemplates.sayAgain())
            val withDay = LinkedHashMap(shown)
            withDay["date"] = date
            if (name == "add_meal") rawArgs["time"]?.trim()?.takeIf { it.isNotEmpty() }?.let { withDay["time"] = it }
            return cardOrSay(executor.proposeAdd(name, toJson(withDay)))
        }
        if (Tools.validate(name, toJson(shown)) is ToolCheck.Bad) return ValidatedOutcome(ReplyTemplates.sayAgain())
        val label = SlotFill.labelFor(name, shown, today) ?: return ValidatedOutcome(ReplyTemplates.sayAgain())
        return ValidatedOutcome(
            ReplyTemplates.didYouMean(label),
            listOf(ChoiceDto("suggest", "0", "Yes", ""), InputHandler.elseChip()),
            options = listOf(PendingAction(name, shown)),
        )
    }

    private suspend fun add(name: String, args: Map<String, String>, executor: ServerToolExecutor, messageId: String): ValidatedOutcome {
        if (Tools.validate(name, toJson(args)) is ToolCheck.Bad) return ValidatedOutcome(ReplyTemplates.sayAgain())
        val result = executor.handle(name, toJson(args), setOf(name))
        if (result.actions.isEmpty() && result.cardApprovalIds.isEmpty()) return ValidatedOutcome(ReplyTemplates.sayAgain())
        // the check-in rule can turn an add into a card: the card wording is used
        if (result.actions.isEmpty()) return ValidatedOutcome(ReplyTemplates.card())
        val today = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate()
        return ValidatedOutcome(ReplyTemplates.confirm(name, args, messageId, today))
    }

    private suspend fun edit(name: String, kind: String, target: String, args: Map<String, String>, executor: ServerToolExecutor): ValidatedOutcome {
        if (target.isBlank() && kind in NOT_CODE_TARGETED) return ValidatedOutcome(ReplyTemplates.sayAgain())
        // at least one changeable field must be present: the tool's own check, with a stand-in id
        if (Tools.validate(name, toJson(args + ("id" to "x"))) is ToolCheck.Bad) return ValidatedOutcome(ReplyTemplates.sayAgain())
        return when (val r = targets.resolve(kind, target)) {
            is Resolved.One -> cardOrSay(executor.handle(name, toJson(args + ("id" to r.choice.id)), setOf(name)))
            is Resolved.Many -> ValidatedOutcome(ReplyTemplates.whichOne(), r.choices, PendingAction(name, args))
            Resolved.None ->
                if (kind in NOT_CODE_TARGETED) ValidatedOutcome(ReplyTemplates.notFoundList(targets.candidates(kind).take(5).map { it.name }))
                else ValidatedOutcome(ReplyTemplates.nothingToday())
        }
    }

    private fun find(decision: ModelDecision): ValidatedOutcome {
        val kind = decision.args["kind"]?.trim().orEmpty()
        val query = decision.target?.trim().orEmpty()
        if (kind !in FIND_KINDS || query.isEmpty()) return ValidatedOutcome(ReplyTemplates.sayAgain())
        val (_, matches) = read.find(buildJsonObject { put("kind", kind); put("query", query) })
        return when {
            matches.size == 1 -> ValidatedOutcome(ReplyTemplates.found(matches[0].name))
            matches.size > 1 -> ValidatedOutcome(ReplyTemplates.foundSeveral(), matches, PendingAction("find", emptyMap()))
            kind in Targets.KINDS -> {
                val names = targets.candidates(kind).take(5).map { it.name }
                ValidatedOutcome(if (names.isEmpty()) ReplyTemplates.notFound(query) else ReplyTemplates.notFoundList(names))
            }
            else -> ValidatedOutcome(ReplyTemplates.notFound(query))
        }
    }
}
