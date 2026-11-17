package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.tools.FIND_KINDS
import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.ToolResult
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.PendingAction
import dev.tim.assistant.server.chat.ServerToolExecutor
import dev.tim.assistant.server.chat.parseExtras
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** "Something else": the turn goes on to the model steps with the source message's own text (nothing else is said). */
data class ToModel(val sourceMessageId: String, val fromHp: Boolean)

/**
 * What a code rung answered: the reply text, chips and the pending action they complete, and which rung it was ("code", "sorter",
 * "ask" for a question, "tap" for a finished suggestion or slot tap). A question also carries its [options] (one per suggestion
 * button), the [sourceMessageId] it is about and the Jerusalem day that message was typed ([sourceDay]). [continueToModel] is the
 * typed marker of the "Something else" tap: no reply is written for it, the runner goes on to the model steps.
 */
data class CodeOutcome(
    val text: String,
    val choices: List<ChoiceDto> = emptyList(),
    val pending: PendingAction? = null,
    val brain: String = "code",
    val options: List<PendingAction> = emptyList(),
    val sourceMessageId: String? = null,
    val sourceDay: String? = null,
    val continueToModel: ToModel? = null,
) {
    companion object {
        /** The marker for the "Something else" tap; [fromHp] is true when the question was an HP model's suggestion. */
        fun toModel(sourceMessageId: String, fromHp: Boolean): CodeOutcome =
            CodeOutcome("", sourceMessageId = sourceMessageId, continueToModel = ToModel(sourceMessageId, fromHp))
    }
}

/**
 * Rungs 1 and 2 of the brain ladder (PLAN 18.5 steps 1 and 3): guards, the rule router, then the read-only sorter. It never
 * answers with a model, and a reply is always built from [ReplyTemplates] or [AskAnswers] (never from the executor's result text,
 * which is written for a model and may contain ids). Anything it cannot do safely returns null and the message goes up the ladder.
 */
class Ladder(
    private val targets: Targets,
    private val asks: AskAnswers,
    private val read: ReadTools,
    private val chats: ChatStore,
    private val sorter: Sorter?,
    private val clock: () -> Long,
) {
    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
        val ANY_KINDS = listOf("task", "exam", "goal", "doc")
    }

    private val handler = InputHandler(targets)

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate()

    private fun toJson(args: Map<String, String>): JsonObject = buildJsonObject { for ((k, v) in args) put(k, v) }

    private fun closed(): CodeOutcome = CodeOutcome(ReplyTemplates.questionClosed(), brain = "tap")

    /** A question names the message it is about and the day that message was typed (today: a message typed on an earlier day never gets one). */
    private fun CodeOutcome.about(messageId: String): CodeOutcome = copy(sourceMessageId = messageId, sourceDay = today().toString())

    /**
     * Runs the code rungs for one message (PLAN 20.6 step 7). [choice] is a chip tap (already known to this chat's messages); [executor]
     * must have its dream permission set. [typedToday] is false when the request's own time is not on today's Jerusalem day: such a
     * message never gets a handler question. Null = nothing safe to do here, pass the message up.
     */
    suspend fun tryCode(chatId: String, messageId: String, text: String, choice: ChoiceDto?, executor: ServerToolExecutor, typedToday: Boolean = true): CodeOutcome? {
        if (choice != null) {
            return if (choice.kind == "suggest" || choice.kind == "slot") handlerChip(chatId, messageId, choice, executor) else chip(chatId, choice, executor)
        }
        typedAnswer(chatId, messageId, text, executor)?.let { return it }
        // a move of several items or a pronoun target is never done or asked about by code: a model makes the cards
        if (IntentRouter.multiMove(text)) return null
        val today = today()
        val blocked = Guards.blocked(text)
        val question = Guards.isQuestion(text)
        if (!blocked && !Guards.hasHebrew(text)) {
            val route = IntentRouter.route(text, today, typedToday)
            if (route is RouteResult.Match && (!question || route.intent.isAsk)) {
                val out = act(route.intent, route.slots, messageId, executor, "code")
                // a kind-less find or move that found nothing safe is not a command: let the sorter and the models see the message
                if (out != null || !(route.slots["kind"] == "any" || route.slots["loose"] == "1")) return out
            }
        }
        handler.early(text, today, typedToday)?.let { return it.about(messageId) }
        if (blocked && !Guards.softBlocked(text)) return null
        val ranked = sorter?.rank(text) ?: return null
        val accepted = ranked.verdict?.accepted
        if (!blocked && accepted != null && accepted.isAsk) {
            AskSlots.read(accepted, text, today)?.let { (intent, slots) -> act(intent, slots, messageId, executor, "sorter")?.let { return it } }
        }
        return handler.suggest(text, today, ranked, typedToday)?.about(messageId)
    }

    // ---- taps and typed answers on a question (PLAN 20.6 steps 8 and 9) ----

    /**
     * A question is open only while it is the newest assistant message of the chat and no later message of Tim's exists besides the
     * current turn's own.
     */
    private fun isOpen(chatId: String, issuerId: String, currentMessageId: String): Boolean {
        val all = chats.messages(chatId)
        val newest = all.lastOrNull { it.role == "assistant" && it.id != "r:$currentMessageId" } ?: return false
        if (newest.id != issuerId) return false
        val index = all.indexOfFirst { it.id == issuerId }
        return all.drop(index + 1).none { it.role == "user" && it.id != currentMessageId }
    }

    /** A tap on a `suggest` or `slot` chip: honoured only on the open question that issued it (and, for an add, on the message's own day). */
    private suspend fun handlerChip(chatId: String, messageId: String, choice: ChoiceDto, executor: ServerToolExecutor): CodeOutcome {
        val (message, stored) = chats.chipIssuer(chatId, choice.token, choice.id, choice.kind) ?: return closed()
        if (!isOpen(chatId, message.id, messageId)) return closed()
        return runChip(message.card_json, stored, messageId, executor) ?: closed()
    }

    /** A typed text that equals one chip name of the open question, exactly (spaces, case and one closing `.` or `!` aside), is that chip. */
    private suspend fun typedAnswer(chatId: String, messageId: String, text: String, executor: ServerToolExecutor): CodeOutcome? {
        if (Guards.negated(text)) return null
        val all = chats.messages(chatId)
        val newest = all.lastOrNull { it.role == "assistant" && it.id != "r:$messageId" } ?: return null
        val index = all.indexOfFirst { it.id == newest.id }
        if (all.drop(index + 1).any { it.role == "user" && it.id != messageId }) return null
        val extras = parseExtras(newest.card_json)
        if (extras.options.isEmpty() && extras.pending == null) return null
        val key = SlotFill.typedKey(text)
        val hit = extras.choices.firstOrNull { (it.kind == "suggest" || it.kind == "slot") && SlotFill.typedKey(it.name) == key } ?: return null
        return runChip(newest.card_json, hit, messageId, executor) ?: closed()
    }

    private suspend fun runChip(cardJson: String?, chip: ChoiceDto, messageId: String, executor: ServerToolExecutor): CodeOutcome? {
        val extras = parseExtras(cardJson)
        val sameDay = extras.sourceDay == today().toString()
        val out = when (chip.kind) {
            "suggest" ->
                if (chip.id == "else") {
                    val source = extras.sourceMessageId ?: return null
                    return CodeOutcome.toModel(source, fromHp = extras.brain == "hp")
                } else {
                    val option = chip.id.toIntOrNull()?.let { extras.options.getOrNull(it) } ?: return null
                    runOption(option, extras, sameDay, messageId, executor)
                }
            "slot" -> {
                val pending = extras.pending ?: return null
                val arg = chip.id.substringBefore('=')
                val value = chip.id.substringAfter('=', "")
                if (!sameDay || SlotFill.missingOf(pending.tool, pending.args) != arg || value !in SlotFill.valuesFor(arg)) return null
                add(pending.tool, pending.args + (arg to value), messageId, executor)
            }
            else -> null
        }
        // a follow-up question (an option that still lacks a piece) keeps its "ask" brain; every finished tap is "tap"
        return out?.let { if (it.brain == "ask") it else it.copy(brain = "tap") }
    }

    private suspend fun runOption(option: PendingAction, extras: dev.tim.assistant.server.chat.MessageExtras, sameDay: Boolean, messageId: String, executor: ServerToolExecutor): CodeOutcome? {
        val tool = option.tool
        return when {
            tool == "ask" -> {
                val intent = IntentName.fromWire(option.args["intent"].orEmpty())?.takeIf { it.isAsk } ?: return null
                act(intent, option.args - "intent", messageId, executor, "tap")
            }
            tool == "update_task" -> {
                val r = targets.resolve("task", option.args["name"].orEmpty()) as? Resolved.One ?: return null
                card(executor.handle("update_task", toJson(mapOf("done" to "true", "id" to r.choice.id)), setOf("update_task")))
            }
            SlotFill.isAdd(tool) -> {
                if (!sameDay) return null
                val missing = SlotFill.missingOf(tool, option.args)
                if (missing != null) {
                    InputHandler.missingQuestion(tool, option.args, missing)?.copy(sourceMessageId = extras.sourceMessageId, sourceDay = extras.sourceDay)
                } else {
                    add(tool, option.args, messageId, executor)
                }
            }
            else -> null
        }
    }

    private suspend fun act(intent: IntentName, slots: Map<String, String>, messageId: String, executor: ServerToolExecutor, brain: String): CodeOutcome? =
        when (intent) {
            IntentName.CREATE_TASK -> add("create_task", slots, messageId, executor)
            IntentName.ADD_SPENDING -> add("add_spending", slots, messageId, executor)
            IntentName.ADD_MEAL -> add("add_meal", slots, messageId, executor)
            IntentName.ADD_CHECKIN -> add("add_checkin", slots, messageId, executor)
            IntentName.ADD_JOURNAL -> add("add_journal", slots, messageId, executor)
            IntentName.ADD_DREAM -> add("add_dream", slots, messageId, executor)
            IntentName.MARK_TASK_DONE -> edit("task", "update_task", slots["name"].orEmpty(), mapOf("done" to "true"), executor)
            IntentName.MOVE_TASK -> if (slots["loose"] == "1") moveLoose(slots["name"].orEmpty(), slots["date"].orEmpty(), executor) else edit("task", "update_task", slots["name"].orEmpty(), mapOf("dueDate" to slots["date"].orEmpty()), executor)
            IntentName.MOVE_EXAM -> edit(
                "exam", "update_exam", slots["name"].orEmpty(),
                buildMap<String, String> { put("localDate", slots["date"].orEmpty()); slots["time"]?.let { put("time", it) } }, executor,
            )
            IntentName.FIND -> if (slots["kind"] == "any") findAny(slots["name"].orEmpty()) else find(slots["kind"].orEmpty(), slots["name"].orEmpty())
            IntentName.ASK_TODAY -> CodeOutcome(slots["day"]?.let { asks.dayOverview(it) } ?: asks.todayOverview(), brain = brain)
            IntentName.ASK_TASKS -> askTasks(slots, brain)
            IntentName.ASK_EVENTS -> askEvents(slots, brain)
            IntentName.ASK_SPENDING_WEEK -> CodeOutcome(asks.spendingWeek(), brain = brain)
            IntentName.ASK_SPENDING -> period(slots, Lean.PAST)?.let { CodeOutcome(asks.spending(it, slots["category"]), brain = brain) }
            IntentName.ASK_EXAM -> CodeOutcome(asks.exam(slots["name"]), brain = brain)
            IntentName.ASK_HEALTH -> period(slots, Lean.PAST)?.let { p -> slots["metric"]?.let { CodeOutcome(asks.health(it, p), brain = brain) } }
            IntentName.ASK_MEALS -> period(slots, Lean.PAST)?.let { CodeOutcome(asks.meals(slots["what"] ?: "list", it), brain = brain) }
            IntentName.ASK_CHECKIN -> period(slots, Lean.PAST)?.let { CodeOutcome(asks.checkin(it), brain = brain) }
            IntentName.NONE -> null
        }

    /** The stored `period` word, rebuilt for the day this runs; null when it does not resolve. */
    private fun period(slots: Map<String, String>, lean: Lean): Period? =
        Periods.resolve(slots["period"] ?: "today", today(), lean)

    private fun askEvents(slots: Map<String, String>, brain: String): CodeOutcome? {
        slots["day"]?.let { return CodeOutcome(asks.events(it), brain = brain) }
        val p = period(slots, Lean.FUTURE) ?: return null
        return CodeOutcome(if (p.singleDay) asks.events(SlotParsers.isoDate(p.from)) else asks.events(p), brain = brain)
    }

    private fun askTasks(slots: Map<String, String>, brain: String): CodeOutcome? {
        slots["when"]?.let { return CodeOutcome(asks.tasks(it), brain = brain) }
        if (slots["period"] == null) return CodeOutcome(asks.tasks(null), brain = brain)
        val p = period(slots, Lean.FUTURE) ?: return null
        val whenWord = when (p.word) { "today" -> "today"; "tomorrow" -> "tomorrow"; "this_week" -> "week"; else -> null }
        return CodeOutcome(if (whenWord != null) asks.tasks(whenWord) else asks.tasksDue(p), brain = brain)
    }

    private fun wrote(result: ToolResult): Boolean = result.actions.isNotEmpty() || result.cardApprovalIds.isNotEmpty()

    private suspend fun add(tool: String, slots: Map<String, String>, messageId: String, executor: ServerToolExecutor): CodeOutcome? {
        val args = slots.filterValues { it.isNotBlank() }
        // a past day (date slot): a card only, naming the full date
        if (args["date"] != null) return card(executor.proposeAdd(tool, toJson(args)))
        val result = executor.handle(tool, toJson(args), setOf(tool))
        if (!wrote(result)) return null
        // the check-in rule can turn an add into a card: the card wording is used
        if (result.actions.isEmpty()) return CodeOutcome(ReplyTemplates.card())
        return CodeOutcome(ReplyTemplates.confirm(tool, args, messageId, today()))
    }

    private suspend fun edit(kind: String, tool: String, name: String, change: Map<String, String>, executor: ServerToolExecutor): CodeOutcome? {
        if (name.isBlank() || change.values.any { it.isBlank() }) return null
        return when (val r = targets.resolve(kind, name)) {
            Resolved.None -> null
            is Resolved.One -> card(executor.handle(tool, toJson(change + ("id" to r.choice.id)), setOf(tool)))
            is Resolved.Many -> CodeOutcome(ReplyTemplates.whichOne(), r.choices, PendingAction(tool, change))
        }
    }

    private fun card(result: ToolResult): CodeOutcome? =
        if (result.cardApprovalIds.isNotEmpty()) CodeOutcome(ReplyTemplates.card()) else null

    private fun findMatches(kind: String, query: String): List<ChoiceDto> =
        read.find(buildJsonObject { put("kind", kind); put("query", query) }).second

    /** A find with no kind word: answers only when exactly one task, exam, goal or doc matches, else null (the message goes up). */
    private fun findAny(query: String): CodeOutcome? {
        if (query.isBlank()) return null
        val all = ANY_KINDS.flatMap { findMatches(it, query) }
        return if (all.size == 1) CodeOutcome(ReplyTemplates.found(all[0].name)) else null
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Move without the word "task": one open task, a whole-name match, and no exam that matches; else null. */
    private suspend fun moveLoose(name: String, date: String, executor: ServerToolExecutor): CodeOutcome? {
        if (name.isBlank() || date.isBlank()) return null
        if (findMatches("exam", name).isNotEmpty() || targets.candidates("exam").any { norm(it.name) == norm(name) }) return null
        val tasks = targets.candidates("task")
        val exact = tasks.filter { norm(it.name) == norm(name) }
        val hit = when {
            exact.size == 1 -> exact[0]
            exact.isEmpty() && name.trim().split(Regex("\\s+")).size >= 2 -> findMatches("task", name).singleOrNull()
            else -> null
        } ?: return null
        if (tasks.count { norm(it.name) == norm(hit.name) } != 1) return null
        return card(executor.handle("update_task", toJson(mapOf("dueDate" to date, "id" to hit.id)), setOf("update_task")))
    }

    private fun find(kind: String, query: String): CodeOutcome? {
        if (kind !in FIND_KINDS || query.isBlank()) return null
        val matches = findMatches(kind, query)
        return when {
            matches.size == 1 -> CodeOutcome(ReplyTemplates.found(matches[0].name))
            matches.size > 1 -> CodeOutcome(ReplyTemplates.foundSeveral(), matches, PendingAction("find", emptyMap()))
            kind in Targets.KINDS -> {
                val names = targets.candidates(kind).take(5).map { it.name }
                CodeOutcome(if (names.isEmpty()) ReplyTemplates.notFound(query) else ReplyTemplates.notFoundList(names))
            }
            else -> CodeOutcome(ReplyTemplates.notFound(query))
        }
    }

    /**
     * A chip tap completes only the pending action stored on the message that offered this chip (the stored chip's id and name, not
     * the client's), with the tool paired to the chip's kind and the live target checked again. No pending action: null.
     */
    private suspend fun chip(chatId: String, choice: ChoiceDto, executor: ServerToolExecutor): CodeOutcome? {
        val (message, stored) = chats.chipIssuer(chatId, choice.token, choice.id, choice.kind) ?: return null
        val pending = parseExtras(message.card_json).pending ?: return null
        if (pending.tool == "find") {
            val live = if (stored.kind in Targets.KINDS) targets.candidates(stored.kind).any { it.id == stored.id }
            else findMatches(stored.kind, stored.name).any { it.id == stored.id }
            return CodeOutcome(if (live) ReplyTemplates.found(stored.name) else ReplyTemplates.notFound(stored.name))
        }
        val kind = DecisionValidator.UPDATE_KINDS[pending.tool] ?: return null
        if (stored.kind != kind) return null
        val args = pending.args.filterKeys { it != "id" } + ("id" to stored.id)
        return card(executor.handle(pending.tool, toJson(args), setOf(pending.tool)))
    }
}
