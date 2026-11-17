package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.decisionSchema
import dev.tim.assistant.ai.parseDecision
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.ai.OllamaClient
import dev.tim.assistant.server.ai.OllamaException
import dev.tim.assistant.server.chat.Brain
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.ServerToolExecutor
import dev.tim.assistant.server.chat.TurnResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A message longer than this never reaches the small model (it needs the laptop). */
const val HP_LAST_RESORT_MAX_CHARS = 600

/**
 * Generation cap (Ollama num_predict) for the fixed-shape form. The longest valid form is a four-sentence answer or a journal text
 * of up to 600 characters (about 150 tokens) plus the JSON frame, so 300 tokens fits; a runaway generation stops here and fails
 * parsing (a "say it again" reply) instead of waiting for the timeout.
 */
const val HP_NUM_PREDICT = 300

private val JERUSALEM_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Jerusalem")

/** The wait for the generation lock plus one model call. */
const val HP_LAST_RESORT_TIMEOUT_MS = 170_000L

/**
 * Rung 5 (PLAN 18.6 step 5): the home server's small model fills one fixed form that [DecisionValidator] checks before anything is
 * saved. One model call per turn, temperature 0, the fixed 8192 window. A timeout or an Ollama failure is "no brain": nothing was
 * written and the client keeps the message queued. With no [hpOllama] this rung is always unavailable.
 */
class HpLastResort(
    private val sdb: ServerDb,
    private val chats: ChatStore,
    private val brain: Brain,
    private val generationLock: Mutex,
    private val hpOllama: OllamaClient?,
    private val validator: DecisionValidator,
    private val clock: () -> Long,
    private val timeoutMs: Long = HP_LAST_RESORT_TIMEOUT_MS,
) {
    suspend fun run(
        chatId: String,
        replyId: String,
        userMessageId: String,
        text: String,
        screen: String?,
        dreams: Boolean,
        executor: ServerToolExecutor,
        skipId: String? = null,
    ): TurnResult {
        if (text.length > HP_LAST_RESORT_MAX_CHARS) return TurnResult.Reply(ReplyTemplates.tooLong(), emptyList(), brain = "hp")
        val ollama = hpOllama ?: return TurnResult.Unavailable(wrote = false)
        val sel = brain.hpSelection()
        val prompt = DecisionPrompt.build(PromptBudget.HP, sdb.db, chats, chatId, userMessageId, replyId, text, screen, dreams, clock(), skipId = skipId)
        val raw = try {
            withTimeoutOrNull(timeoutMs) {
                generationLock.withLock {
                    ollama.chat(sel.model, prompt.system, prompt.user, sel.numCtx, decisionSchema(dreams), temperature = 0.0, numPredict = HP_NUM_PREDICT)
                }
            }
        } catch (_: OllamaException) {
            null
        } ?: return TurnResult.Unavailable(wrote = false)
        return applyDecision(raw, executor, dreams, userMessageId, "hp", suggestOnly = true)
    }

    /**
     * The one apply path for a small model's raw output (the home server's and the phone's): parse, validate, write through the
     * executor. Unparsable output is the "say it again" reply; nothing is written then. [brainName] is stored on the reply.
     */
    suspend fun applyDecision(raw: String, executor: ServerToolExecutor, dreams: Boolean, userMessageId: String, brainName: String, suggestOnly: Boolean = false): TurnResult.Reply {
        val decision = parseDecision(raw) ?: return TurnResult.Reply(ReplyTemplates.sayAgain(), emptyList(), brain = brainName)
        val outcome = validator.apply(decision, executor, dreams, userMessageId, suggestOnly)
        if (outcome.options.isEmpty()) return TurnResult.Reply(outcome.text, outcome.choices, brain = brainName, pending = outcome.pending)
        // the source message is the one the suggestion is about (userMessageId is the stored source id on a "Something else" turn)
        val sourceDay = chats.message(userMessageId)?.created_at?.let { java.time.Instant.ofEpochMilli(it).atZone(JERUSALEM_ZONE).toLocalDate().toString() }
            ?: java.time.Instant.ofEpochMilli(clock()).atZone(JERUSALEM_ZONE).toLocalDate().toString()
        return TurnResult.Reply(
            outcome.text, outcome.choices, brain = brainName, pending = outcome.pending,
            options = outcome.options, sourceMessageId = userMessageId, sourceDay = sourceDay,
        )
    }
}
