package dev.tim.assistant.ai.tools

import dev.tim.assistant.ai.CHAT_MODEL
import dev.tim.assistant.ai.CHAT_NUM_CTX
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.ai.ChatMessage
import dev.tim.assistant.ai.ToolCall
import dev.tim.assistant.ai.ToolMessage
import dev.tim.assistant.ai.ToolTurn
import dev.tim.assistant.ai.trimHistory
import dev.tim.assistant.model.ActionRefDto
import dev.tim.assistant.model.ChoiceDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** At most this many tool calls are executed for one question; further ones are refused. */
const val MAX_TOOL_CALLS = 5

/** Context + all tool outputs of one question together stay within this many characters. */
const val TOOL_DATA_BUDGET_CHARS = 12_000

const val TOOL_LIMIT_REFUSAL = "Refused: limit of 5 tool calls reached - answer now"
const val TOOL_BUDGET_REFUSAL = "Refused: data limit reached - answer from what you already have"

const val RESEARCH_REASK_TEXT =
    "Do not answer this from memory. Call the research tool now (category NUTRITION for food calories or nutrition, DEFINITION for the meaning of a word) with just the food or word as item."

private val NUTRITION_WORDS = Regex(
    "(calorie|kcal|nutrition|nutritional|protein|carbohydrate|carbs|macros|\\bfat\\b.*\\b(in|of)\\b|\\bsugar\\b.*\\b(in|of)\\b|קלורי|חלבון|פחמימות|ערך תזונתי|ערכים תזונתיים)",
    RegexOption.IGNORE_CASE,
)
private val DEFINITION_WORDS = Regex(
    "(\\bdefine\\b|\\bdefinition of\\b|\\bmeaning of\\b|what does .{1,60} mean|what is the meaning|מה פירוש|מה המשמעות|מה זה .{1,30} בעברית)",
    RegexOption.IGNORE_CASE,
)
/** Questions about Tim's own log ("how many calories did I eat today") are answered from his data, not looked up. */
private val OWN_DATA_WORDS = Regex(
    "(\\b(i|my|me|mine|today|yesterday|tonight|this week|last week|this month|so far|total|did i|have i|ate|eaten)\\b|אכלתי|היום|אתמול|השבוע|שלי)",
    RegexOption.IGNORE_CASE,
)

/**
 * true when [question] asks for food nutrition or the meaning of a word in general: those are always answered through the
 * research tool, never from the model's memory. Questions about Tim's own meals are not included.
 */
fun needsResearchTool(question: String): Boolean {
    if (OWN_DATA_WORDS.containsMatchIn(question)) return false
    return NUTRITION_WORDS.containsMatchIn(question) || DEFINITION_WORDS.containsMatchIn(question)
}

const val EVENT_LENGTH_MISSING_TEXT = "Missing end or durationMinutes - ask Tim how long the event lasts"

private val EVENT_DURATION = Regex(
    "(\\d+(?:[.,]\\d+)?\\s*(min|mins|minute|minutes|h|hr|hrs|hour|hours)\\b|half an hour|half hour|an hour|one hour|a hour|two hours|an hour and a half|" +
        "שעה|שעתיים|שעות|דקות|דקה|חצי שעה|רבע שעה)",
    RegexOption.IGNORE_CASE,
)
private val EVENT_END = Regex(
    "(\\b(until|till|to|ends?|ending)\\s+\\d{1,2}([:.]\\d{2})?\\s*(am|pm)?|\\d{1,2}([:.]\\d{2})?\\s*(am|pm)?\\s*(-|–|—|to)\\s*\\d{1,2}([:.]\\d{2})?|" +
        "(?<!\\p{L})עד(?!\\p{L}))",
    RegexOption.IGNORE_CASE,
)

/** true when [text] gives an event's length (a duration) or its end (an end time or a range). */
fun statesEventLength(text: String): Boolean = EVENT_DURATION.containsMatchIn(text) || EVENT_END.containsMatchIn(text)

/** Tim's messages that can answer "how long?": the current question plus his messages after the last assistant message. */
fun timMessagesForEvent(history: List<ChatMessage>, question: String): String {
    val lastAssistant = history.indexOfLast { it.role == "assistant" }
    val recent = history.drop(lastAssistant + 1).filter { it.role == "user" }.map { it.content }
    return (recent + question).joinToString("\n")
}

/** The result of one tool call as the model and the screen see it. */
data class ToolResult(
    val text: String,
    /** Label for "Looked at: ..." when a read tool ran, else null. */
    val looked: String? = null,
    /** Set when a write tool created an approval card. */
    val approvalId: String? = null,
    /** Set when a research tool queued a job (its answer arrives later, from the store). */
    val researchJobId: String? = null,
    /** Undo handles of rows an auto tool wrote (the chat shows an Undo button for each). */
    val actions: List<ActionRefDto> = emptyList(),
    /** Chips to pick from when a name matched several records. */
    val choices: List<ChoiceDto> = emptyList(),
    /** Approval cards this call created (a server executor may create more than one). */
    val cardApprovalIds: List<String> = emptyList(),
)

/** Research tools (research, research_question): plugged in by the research half; null = not available here. */
fun interface ResearchToolHandler {
    suspend fun run(name: String, args: JsonObject): ToolResult
}

/**
 * Runs a validated write call. The server's executor writes auto tools
 * at once and creates cards for the rest. [allowed] is the set of tool names this request advertised: a handler must refuse any
 * other name (the model may ask for a tool it was not offered).
 */
fun interface WriteHandler {
    suspend fun handle(name: String, args: JsonObject, allowed: Set<String>): ToolResult
}

/** The text a model gets when it calls a tool that this request did not offer. */
fun notOfferedText(name: String): String = "Error: tool '${name.take(60)}' is not available here"

/** Runs one validated call: read tools answer at once, write tools go to the [WriteHandler], bad calls get an error text. */
class ToolBox(
    private val read: ReadTools,
    private val write: WriteHandler,
    private val research: ResearchToolHandler? = null,
) {
    /** [allowed] null = every registered tool; otherwise a call outside it is refused before anything runs. */
    suspend fun call(call: ToolCall, allowed: Set<String>? = null): ToolResult {
        if (allowed != null && call.name !in allowed) return ToolResult(notOfferedText(call.name))
        val spec = Tools.find(call.name) ?: return ToolResult("Error: unknown tool '${call.name.take(60)}'")
        val args = when (val c = spec.validate(call.arguments)) {
            is ToolCheck.Bad -> return ToolResult("Error: ${c.reason}")
            is ToolCheck.Ok -> c.args
        }
        return when (spec.kind) {
            ToolKind.READ ->
                if (spec.name == "find") {
                    val (text, matches) = read.find(args)
                    ToolResult(text, looked = spec.label, choices = if (matches.size > 1) matches else emptyList())
                } else {
                    ToolResult(read.run(spec.name, args), looked = spec.label)
                }
            ToolKind.WRITE -> write.handle(spec.name, args, allowed ?: Tools.allNames)
            ToolKind.RESEARCH -> research?.run(spec.name, args) ?: ToolResult("Error: research is not available on this device")
        }
    }
}

sealed interface ToolChatReply {
    data class Answer(val text: String) : ToolChatReply
    data object Offline : ToolChatReply
    data class Failed(val message: String) : ToolChatReply
}

/** One answered question: the reply, what was looked at (distinct labels, in order) and the approval cards created. */
data class ToolChatOutcome(
    val reply: ToolChatReply,
    val looked: List<String>,
    val approvalIds: List<String>,
    /** How many tool calls the model asked for, refused ones included. */
    val callsRequested: Int,
    /** Research jobs queued by this question. */
    val researchJobIds: List<String> = emptyList(),
    /** Undo handles of rows written by auto tools. */
    val actions: List<ActionRefDto> = emptyList(),
    /** Chips collected from find results (name matched several records). */
    val choices: List<ChoiceDto> = emptyList(),
    /** Approval ids of cards created by ToolResult.cardApprovalIds (already included in [approvalIds]). */
    val cardApprovalIds: List<String> = emptyList(),
)

/**
 * Answers one chat question with tools: sends the question, runs the requested calls (the first [MAX_TOOL_CALLS] only,
 * counting parallel and invalid ones), feeds results back and returns the final answer. Once a call is refused, or when
 * the data budget is used up, one last turn is sent without tools. An HTTP 500 turn is retried once.
 */
class ToolChatLoop(
    private val chat: ChatClient,
    private val box: ToolBox,
) {
    /**
     * [systemPrompt] and [tools] default to the client's own; the server passes its persona and the tool list of the request
     * (a call to any tool outside [tools] is refused). [maxChars] replaces the 2,000-character history limit when the caller
     * already budgets the whole request. [model] and [numCtx] go to every turn and retry.
     */
    suspend fun ask(
        baseUrl: String,
        context: String,
        history: List<ChatMessage>,
        question: String,
        systemPrompt: String = ChatContext.toolSystemPrompt(context),
        tools: List<JsonObject> = Tools.ollamaToolList(),
        model: String = CHAT_MODEL,
        numCtx: Int = CHAT_NUM_CTX,
        maxChars: Int? = null,
        temperature: Double? = null,
    ): ToolChatOutcome {
        val messages = ArrayList<ToolMessage>()
        messages += ToolMessage("system", systemPrompt)
        for (m in (if (maxChars == null) trimHistory(history) else trimHistory(history, maxChars))) messages += ToolMessage(m.role, m.content)
        messages += ToolMessage("user", question)

        val timText = timMessagesForEvent(history, question)
        val toolsJson = JsonArray(tools)
        val allowed = tools.mapNotNull { ((it["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content }.toSet()
        val looked = LinkedHashSet<String>()
        val approvals = ArrayList<String>()
        val actions = ArrayList<ActionRefDto>()
        val choices = ArrayList<ChoiceDto>()
        val cards = ArrayList<String>()
        val queuedResearchJobIds = ArrayList<String>()
        var requested = 0
        var used = context.length
        var refusedAny = false
        var calledResearch = false
        var reasked = false

        fun outcome(reply: ToolChatReply) =
            ToolChatOutcome(reply, looked.toList(), approvals.toList(), requested, queuedResearchJobIds.toList(), actions.toList(), choices.toList(), cards.toList())

        while (true) {
            val turn = turnWithRetry(baseUrl, messages, if (refusedAny) null else toolsJson, model, numCtx, temperature)
            when (turn) {
                is ToolTurn.Answer -> {
                    // code-enforced routing: a general nutrition/definition question must go through the research tool
                    if (!calledResearch && !reasked && !refusedAny && "research" in allowed && needsResearchTool(question)) {
                        reasked = true
                        messages += ToolMessage("assistant", turn.text)
                        messages += ToolMessage("user", RESEARCH_REASK_TEXT)
                    } else {
                        return outcome(ToolChatReply.Answer(turn.text))
                    }
                }
                ToolTurn.Offline -> return outcome(ToolChatReply.Offline)
                is ToolTurn.Error -> return outcome(ToolChatReply.Failed(turn.message))
                is ToolTurn.Calls -> {
                    if (refusedAny) return outcome(ToolChatReply.Failed("The model kept asking for tools"))
                    messages += ToolMessage("assistant", "", toolCalls = turn.calls)
                    for (call in turn.calls) {
                        requested++
                        if (call.name == "research") calledResearch = true
                        var text: String
                        if (requested > MAX_TOOL_CALLS) {
                            refusedAny = true
                            text = TOOL_LIMIT_REFUSAL
                        } else {
                            val timedEvent = call.name == "create_event" &&
                                (call.arguments["allDay"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "true"
                            val result = if (timedEvent && !statesEventLength(timText)) ToolResult(EVENT_LENGTH_MISSING_TEXT) else box.call(call, allowed)
                            val isRead = result.looked != null
                            // a whole result is kept or refused, never cut; small write/error results always fit
                            if (isRead && used + result.text.length > TOOL_DATA_BUDGET_CHARS) {
                                refusedAny = true
                                text = TOOL_BUDGET_REFUSAL
                            } else {
                                text = result.text
                                used += text.length
                                result.looked?.let { looked += it }
                                result.approvalId?.let { approvals += it }
                                for (id in result.cardApprovalIds) { approvals += id; cards += id }
                                actions += result.actions
                                choices += result.choices
                                result.researchJobId?.let { queuedResearchJobIds += it }
                            }
                        }
                        messages += ToolMessage("tool", text, toolName = call.name)
                    }
                }
            }
        }
    }

    /** One turn; an HTTP 500 (Ollama failing to read the model's own tool-call text) is tried once more. */
    private suspend fun turnWithRetry(
        baseUrl: String,
        messages: List<ToolMessage>,
        tools: JsonArray?,
        model: String,
        numCtx: Int,
        temperature: Double? = null,
    ): ToolTurn {
        val first = chat.chatTurn(baseUrl, messages, tools, model, numCtx, temperature)
        if (first is ToolTurn.Error && first.message == "HTTP 500") return chat.chatTurn(baseUrl, messages, tools, model, numCtx, temperature)
        return first
    }
}
