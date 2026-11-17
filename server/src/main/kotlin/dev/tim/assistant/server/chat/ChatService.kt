package dev.tim.assistant.server.chat

import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChatListResponse
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.MemoryResponse
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.NewChatResponse
import dev.tim.assistant.model.PhoneResultRequest
import dev.tim.assistant.model.PhoneTurnDto
import dev.tim.assistant.model.ROUTES
import dev.tim.assistant.model.ROUTE_CODEX
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import dev.tim.assistant.model.UndoResponse
import dev.tim.assistant.server.chat.ladder.ReplyTemplates
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val MAX_MESSAGE_CHARS = 8_000
const val MAX_ID_CHARS = 100
const val INTERRUPTED_TEXT = "I was interrupted while answering. What I saved before that is kept; ask me again if something is missing."
const val BRAIN_UNAVAILABLE_TEXT = "Wally's brain is not reachable right now - try again in a minute"
const val STILL_ANSWERING_TEXT = "Wally is still answering"

/** The name in config.tokens of the phone's bearer token: the only caller that is ever handed a phone turn or may post its result. */
const val PHONE_TOKEN_NAME = "phone"

/** A phone's raw decision is a small JSON form; anything bigger is refused. */
const val PHONE_RESULT_MAX_CHARS = 20_000

sealed interface SendResult {
    data class Ok(val response: SendMessageResponse) : SendResult
    data class Conflict(val message: String) : SendResult
    data class BadInput(val message: String) : SendResult
    data class Unavailable(val message: String) : SendResult
    /** 202: the phone writes this turn's answer. */
    data class PhoneTurn(val turn: PhoneTurnDto) : SendResult
    data object NotFound : SendResult
}

/**
 * The chat behind the routes: list, create, read, send, delete, undo, memory. One Mutex per chat serializes its turns, so a
 * double tap or a retry never runs two model turns of the same chat at once. A message id seen before returns its stored
 * reply and never runs the model again.
 */
@OptIn(ExperimentalUuidApi::class)
class ChatService(
    private val store: ChatStore,
    private val undoService: UndoService,
    private val runner: ChatRunner?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One lock per chat; internal so a test can hold one to prove the expiry tick skips a busy chat. */
    internal val turnLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun list(): ChatListResponse {
        store.ensurePinned()
        return store.list()
    }

    /** null = invalid id. */
    suspend fun create(request: NewChatRequest): NewChatResponse? {
        if (request.id.isBlank() || request.id.length > MAX_ID_CHARS) return null
        val first = request.firstAssistantText
        if (first != null && first.length > MAX_MESSAGE_CHARS) return null
        val created = store.create(request.id, first)
        return NewChatResponse(store.summary(created.chat), created.archivedTitle)
    }

    fun detail(id: String): ChatDetailResponse? = store.detail(id)

    suspend fun delete(id: String): DeleteChatResult = store.delete(id)

    fun memory(): MemoryResponse = store.memory()

    /** The brain that would answer now; no model configured counts as "hp". */
    suspend fun brainName(): String = runner?.brainName() ?: "hp"

    suspend fun undo(chatId: String, actionId: String): UndoResponse? {
        if (store.chat(chatId) == null) return null
        val outcome = undoService.undo(chatId, actionId)
        if (!outcome.found) return null
        return UndoResponse(outcome.ok, outcome.message)
    }

    /** True while [chatId] is mid-turn (its turn lock is held) or its reply waits for the phone: nothing may remove the chat then. */
    fun chatBusy(chatId: String): Boolean = turnLocks[chatId]?.isLocked == true || store.hasPhonePending(chatId)

    /** [tokenName] is the name of the caller's bearer token in config.tokens; only "phone" may be handed a phone turn. */
    suspend fun send(chatId: String, request: SendMessageRequest, tokenName: String? = null): SendResult {
        val text = request.text.trim()
        if (request.messageId.isBlank() || request.messageId.length > MAX_ID_CHARS) return SendResult.BadInput("invalid: messageId")
        if (text.isEmpty() || text.length > MAX_MESSAGE_CHARS) return SendResult.BadInput("invalid: text")
        if (request.createdAt < 0) return SendResult.BadInput("invalid: createdAt")
        if (request.route !in ROUTES || (request.route == ROUTE_CODEX && request.choice != null)) return SendResult.BadInput("invalid: route")
        if (store.chat(chatId) == null) return SendResult.NotFound
        val brain = runner ?: return SendResult.Unavailable(BRAIN_UNAVAILABLE_TEXT)
        val phone = request.phoneBrain && tokenName == PHONE_TOKEN_NAME
        return turnLocks.computeIfAbsent(chatId) { Mutex() }.withLock { turn(brain, chatId, request, text, phone) }
    }

    private suspend fun turn(runner: ChatRunner, chatId: String, request: SendMessageRequest, text: String, phone: Boolean): SendResult {
        val replyId = "r:" + request.messageId
        val existing = store.message(request.messageId)
        if (existing != null) {
            if (existing.chat_id != chatId || existing.role != "user") return SendResult.Conflict("That message id belongs to something else")
            if (existing.text.trim() != text) return SendResult.Conflict("That message id was already used with a different text")
            val storedReply = store.message(replyId)
            if (storedReply != null) {
                if (storedReply.request_state == STATE_PHONE_PENDING) return replayPending(runner, chatId, replyId, request, text, existing, storedReply, phone)
                // a reply left RUNNING by an interrupted turn is closed with a plain note; the turn is not run again
                val reply = if (storedReply.request_state == STATE_RUNNING) store.finishReply(chatId, replyId, INTERRUPTED_TEXT) { it } else storedReply
                return SendResult.Ok(SendMessageResponse(store.toDto(existing), store.toDto(reply)))
            }
        }
        // another message of this chat is still with the phone: wait for it, or close it when its turn is over
        for (other in store.messages(chatId)) {
            if (other.role != "assistant" || other.request_state != STATE_PHONE_PENDING || other.id == replyId) continue
            if (clock() < (parseExtras(other.card_json).expiresAt ?: 0L)) return SendResult.Unavailable(STILL_ANSWERING_TEXT)
            closeInterrupted(chatId, other.id)
        }
        val choice = request.choice
        if (choice != null && !knownChoice(chatId, choice)) return SendResult.BadInput("invalid: choice")
        val user = existing ?: store.addUserMessage(chatId, request.messageId, text, request.createdAt)
        val result = runner.run(chatId, replyId, request.messageId, text, request.screen, choice, phoneEligible = phone, route = request.route, createdAt = request.createdAt)
        if (result is TurnResult.PhoneTurn) {
            val expiresAt = clock() + PHONE_TURN_MS
            store.startPhoneReply(chatId, replyId, expiresAt, result.sourceMessageId, result.fromHp)
            return SendResult.PhoneTurn(PhoneTurnDto(replyId, request.messageId, result.system, result.user, result.schema, expiresAt))
        }
        return settle(chatId, replyId, request.messageId, text, result, user, deleteReplyWhenNothingDone = false)
    }

    /** Same message id again while its reply waits for the phone (PLAN 19.3 step 4). Runs under the chat's turn lock. */
    private suspend fun replayPending(
        runner: ChatRunner, chatId: String, replyId: String, request: SendMessageRequest, text: String,
        user: dev.tim.assistant.db.Chat_message, pending: dev.tim.assistant.db.Chat_message, phone: Boolean,
    ): SendResult {
        val extras = parseExtras(pending.card_json)
        val expiresAt = extras.expiresAt ?: 0L
        // a "Something else" turn answers the SOURCE message, never the words of the tap
        val sourceId = extras.sourceMessageId
        val source = sourceId?.let { store.message(it) }?.takeIf { it.chat_id == chatId && it.role == "user" }
        val effectiveText = if (sourceId != null) source?.text ?: text else text
        val effectiveId = if (sourceId != null) sourceId else request.messageId
        val skipId = if (sourceId != null) request.messageId else null
        if (phone && (sourceId == null || source != null) && expiresAt - clock() >= PHONE_MIN_LEFT_MS) {
            val pack = runner.phonePack(chatId, replyId, effectiveId, effectiveText, request.screen, skipId, sourceId, extras.fromHp)
            return SendResult.PhoneTurn(PhoneTurnDto(replyId, request.messageId, pack.system, pack.user, pack.schema, expiresAt))
        }
        // phoneBrain = false, or the turn is nearly/fully over: nothing of the phone's may land any more, then rung 5 runs once
        if (!store.claimPhoneReply(chatId, replyId)) return SendResult.NotFound
        val result: TurnResult = when {
            sourceId != null && source == null -> TurnResult.Reply(ReplyTemplates.questionClosed(), emptyList(), brain = "tap")
            extras.fromHp -> TurnResult.Reply(ReplyTemplates.needsLaptop(), emptyList(), brain = "code", sourceMessageId = sourceId)
            else -> runner.runRung5(chatId, replyId, effectiveId, effectiveText, request.screen, skipId).let {
                if (sourceId != null && it is TurnResult.Reply) it.copy(sourceMessageId = sourceId) else it
            }
        }
        return settle(chatId, replyId, request.messageId, effectiveText, result, user, deleteReplyWhenNothingDone = true)
    }

    /** One finishing path for every rung's result (code, laptop, home server, phone): chips get tokens, dream flag, pending, brain. */
    private suspend fun settle(
        chatId: String, replyId: String, messageId: String, userText: String, result: TurnResult,
        user: dev.tim.assistant.db.Chat_message, deleteReplyWhenNothingDone: Boolean,
    ): SendResult = when (result) {
        is TurnResult.Unavailable ->
            if (result.wrote) {
                val reply = store.finishReply(chatId, replyId, INTERRUPTED_TEXT) { it }
                store.markUserMessageDone(messageId)
                SendResult.Ok(SendMessageResponse(store.toDto(store.message(messageId)!!), store.toDto(reply)))
            } else {
                // nothing was saved: drop the empty reply row so a retry is a fresh turn, not an "interrupted" replay
                if (deleteReplyWhenNothingDone) store.deleteMessage(replyId)
                SendResult.Unavailable(BRAIN_UNAVAILABLE_TEXT)
            }
        is TurnResult.Reply -> {
            val tokenized = result.choices.map { it.copy(token = Uuid.random().toString()) }
            val dream = mentionsDreamsOf(userText)
            val reply = store.finishReply(chatId, replyId, result.text) { it.copy(choices = tokenized, dream = it.dream || dream, brain = result.brain ?: it.brain, pending = result.pending, options = result.options, sourceMessageId = result.sourceMessageId, sourceDay = result.sourceDay, fromHp = result.fromHp) }
            store.markUserMessageDone(messageId)
            SendResult.Ok(SendMessageResponse(store.toDto(store.message(messageId) ?: user), store.toDto(reply)))
        }
        // a second phone turn is never started from a settled result; the caller handles PhoneTurn before settling
        is TurnResult.PhoneTurn -> SendResult.Unavailable(BRAIN_UNAVAILABLE_TEXT)
    }

    private suspend fun closeInterrupted(chatId: String, replyId: String) {
        store.finishReply(chatId, replyId, INTERRUPTED_TEXT) { it }
        store.markUserMessageDone(replyId.removePrefix("r:"))
    }

    /**
     * The phone's answer to a 202 (PLAN 19.3 step 5). The request's ids were already checked by the route (turnId = "r:" +
     * messageId). Under the chat's turn lock: the reply must be this chat's PHONE_PENDING row and not expired; it moves to RUNNING
     * first, inside a check that the chat still exists, then the decision is validated and applied.
     */
    suspend fun phoneResult(chatId: String, request: PhoneResultRequest): SendResult {
        if (request.messageId.isBlank() || request.messageId.length > MAX_ID_CHARS || request.turnId != "r:" + request.messageId) {
            return SendResult.BadInput("invalid: turnId")
        }
        if (request.decision.isBlank() || request.decision.length > PHONE_RESULT_MAX_CHARS) return SendResult.BadInput("invalid: decision")
        if (store.chat(chatId) == null) return SendResult.NotFound
        val brain = runner ?: return SendResult.Unavailable(BRAIN_UNAVAILABLE_TEXT)
        return turnLocks.computeIfAbsent(chatId) { Mutex() }.withLock {
            val replyId = request.turnId
            val reply = store.message(replyId)
            val user = store.message(request.messageId)
            if ((reply != null && reply.chat_id != chatId) || (user != null && user.chat_id != chatId)) return@withLock SendResult.NotFound
            if (store.chat(chatId) == null) return@withLock SendResult.NotFound
            if (reply == null || user == null || reply.request_state != STATE_PHONE_PENDING) {
                return@withLock SendResult.Conflict("No phone turn is waiting for that message")
            }
            val expiresAt = parseExtras(reply.card_json).expiresAt ?: 0L
            if (clock() >= expiresAt) {
                closeInterrupted(chatId, replyId)
                return@withLock SendResult.Conflict("The phone turn expired")
            }
            if (!store.claimPhoneReply(chatId, replyId)) {
                return@withLock if (store.chat(chatId) == null) SendResult.NotFound else SendResult.Conflict("No phone turn is waiting for that message")
            }
            val sourceId = parseExtras(reply.card_json).sourceMessageId
            val source = sourceId?.let { store.message(it) }?.takeIf { it.chat_id == chatId && it.role == "user" }
            val effective = source ?: user
            val result = if (sourceId != null && source == null) {
                TurnResult.Reply(ReplyTemplates.questionClosed(), emptyList(), brain = "tap")
            } else {
                brain.applyPhoneResult(
                    chatId, replyId, effective.id, effective.text, request.decision, expiresAt - PHONE_TURN_MS, request.firstTokenMs, request.tokensPerSecond, sourceId,
                )
            }
            settle(chatId, replyId, request.messageId, effective.text, result, user, deleteReplyWhenNothingDone = false)
        }
    }

    /**
     * Closes every phone turn whose time is up with the plain "interrupted" reply. A chat whose turn lock is busy is skipped until
     * the next tick; the state is re-read under the lock, so a result that got there first is never overwritten. Returns how many
     * replies were closed.
     */
    suspend fun closeExpiredPhoneTurns(): Int {
        var closed = 0
        for (row in store.phonePendingReplies()) {
            if (clock() < (parseExtras(row.card_json).expiresAt ?: 0L)) continue
            val lock = turnLocks.computeIfAbsent(row.chat_id) { Mutex() }
            if (!lock.tryLock()) continue
            try {
                val fresh = store.message(row.id)
                if (fresh == null || fresh.request_state != STATE_PHONE_PENDING) continue
                if (clock() < (parseExtras(fresh.card_json).expiresAt ?: 0L)) continue
                closeInterrupted(fresh.chat_id, fresh.id)
                closed++
            } finally {
                lock.unlock()
            }
        }
        return closed
    }

    private fun mentionsDreamsOf(text: String): Boolean = dev.tim.assistant.ai.mentionsDreams(text)

    /** A chip a client sends back must be one this chat's assistant messages really offered. */
    private fun knownChoice(chatId: String, choice: ChoiceDto): Boolean =
        choice.token.isNotBlank() && store.messages(chatId).any { m ->
            m.role == "assistant" && parseExtras(m.card_json).choices.any { it.token == choice.token && it.id == choice.id && it.kind == choice.kind }
        }
}
