package dev.tim.assistant.ai

import dev.tim.assistant.data.ChatApi
import dev.tim.assistant.data.HP_OFFLINE_TEXT
import dev.tim.assistant.data.HpResult
import dev.tim.assistant.data.SendOutcome
import dev.tim.assistant.data.failureText
import dev.tim.assistant.model.ChatMessageDto
import dev.tim.assistant.model.ChatSummaryDto
import dev.tim.assistant.model.CODEX_ASKED_TEXT
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.MemoryNoteDto
import dev.tim.assistant.model.NewChatRequest
import dev.tim.assistant.model.PhoneResultRequest
import dev.tim.assistant.model.PhoneTurnDto
import dev.tim.assistant.model.ROUTE_AUTO
import dev.tim.assistant.model.ROUTE_CODEX
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.SendMessageResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Local setting: the ordered queue of messages not yet accepted by the server (never synced). */
const val CHAT_OUTBOX_KEY = "chat.outbox"

/** Local setting: the chat this device had open. */
const val CHAT_CURRENT_KEY = "chat.current"

/** The title the list shows for a chat that was created while offline and has no server title yet. */
const val NEW_CHAT_TITLE = "New chat"

/** The chat list shows at most this many active chats (the server's limit; the Contract's `ChatListResponse.limit`). */
const val CHAT_LIST_LIMIT = 10

/** The phone's own model may write for at most this long before the HP answers instead. */
const val PHONE_GENERATE_TIMEOUT_MS = 60_000L

/** Wait before the one retry after "Wally is still answering". */
const val BUSY_RETRY_MS = 30_000L

/** While Tim looks at a chat that asked Codex, the app re-reads it this often. */
const val CODEX_POLL_INTERVAL_MS = 20_000L

/** The app stops looking for the Codex answer after this long. */
const val CODEX_POLL_WINDOW_MS = 300_000L

/** A message the server has not accepted yet. [messageId] and [chatId] are fixed at enqueue, so a retry never duplicates. */
@Serializable
data class QueuedMessage(
    val messageId: String,
    val chatId: String,
    val text: String,
    val createdAt: Long,
    val screen: String? = null,
    val choice: ChoiceDto? = null,
    /** When the phone was handed this message's turn (the 202's expiry); while it is in the future only the foreground may send it. */
    val phoneExpiresAt: Long? = null,
    /** Phase 20: which brain Tim asked for (ROUTE_AUTO, ROUTE_MODEL, ROUTE_CODEX); a 1.17.0 outbox item has none and resends as auto. */
    val route: String = ROUTE_AUTO,
)

/** Local setting: the Settings switch for the phone brain ("false" turns it off; missing means on). */
const val PHONE_BRAIN_ENABLED_KEY = "phoneBrain.enabled"

/** The HP's 503 text while another phone turn is still being answered (the Contract's ErrorResponse). */
const val WALLY_STILL_ANSWERING_TEXT = "Wally is still answering"

/** What one send attempt came to: the server's answer, an item already settled, or an item kept for later without error. */
private sealed interface Step {
    data class Result(val res: HpResult<SendMessageResponse>) : Step
    data object Done : Step
    data object Hold : Step
}

/** The open Codex-answer polling: which chat and Codex reply it waits behind; [token] tells a newer session from an old one. */
private class PollSession(val token: Long, val chatId: String, val replyId: String, val replyCreatedAt: Long)

/** The phone turn that currently owns the streaming bubble. */
private class StreamOwner(val chatId: String, val messageId: String, val turnId: String)

/** Where the controller keeps its small local state (the app's local settings). */
interface ChatKv {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * What the Chat screens show. [messages] are the server's messages of [currentId]; [queued] are Tim's messages for that chat
 * that wait for the server (shown greyed with a clock). [typing] = a message of the current chat is being answered.
 * [offline] = the last call got no answer. [notice] is a one-line message (an archive, an error) shown once.
 */
data class ChatUiState(
    val chats: List<ChatSummaryDto> = emptyList(),
    val activeCount: Int = 0,
    val limit: Int = CHAT_LIST_LIMIT,
    val currentId: String? = null,
    val messages: List<ChatMessageDto> = emptyList(),
    val queued: List<QueuedMessage> = emptyList(),
    val typing: Boolean = false,
    /** True while [typing] and the server said the slow HP brain answers: only then the typing row may add the slow-mode text. */
    val slowBrain: Boolean = false,
    /** The words the phone has written so far for the open chat ("" = started, nothing yet); null when the phone is not writing here. */
    val streamingText: String? = null,
    val offline: Boolean = false,
    val notice: String? = null,
    val memory: List<MemoryNoteDto> = emptyList(),
    val memoryLoaded: Boolean = false,
    val listHidden: Boolean = false,
)

/** The answer to an approval decision: [ok] true when the server applied it; [message] is for Tim. */
data class DecisionOutcome(val ok: Boolean, val message: String)

/**
 * The Wally chat, owned by the app (not by a screen) so a message keeps going when Tim leaves the tab. The conversation
 * lives on the HP; this class talks to it through [api], keeps the ordered outbox (`chat.outbox` in [kv]) and the open
 * chat, and shows what the server last said. Every message carries a client-made id, so a resend after a lost answer
 * returns the stored reply. [screen] gives the screen Tim was on when he sent a message.
 */
@OptIn(ExperimentalUuidApi::class)
class ChatController(
    private val scope: CoroutineScope,
    private val api: ChatApi,
    private val kv: ChatKv,
    private val clock: () -> Long,
    private val screen: () -> String? = { null },
    private val newId: () -> String = { Uuid.random().toString() },
    private val phoneBrain: PhoneBrain = NoPhoneBrain,
    private val deviceState: DeviceState = NoDeviceState,
    private val phoneTimeoutMs: Long = PHONE_GENERATE_TIMEOUT_MS,
    private val busyRetryMs: Long = BUSY_RETRY_MS,
    private val pollIntervalMs: Long = CODEX_POLL_INTERVAL_MS,
    private val pollWindowMs: Long = CODEX_POLL_WINDOW_MS,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val outboxSerializer = ListSerializer(QueuedMessage.serializer())
    private val drainLock = Mutex()
    private val knownChats = HashSet<String>()

    private var outbox: List<QueuedMessage> = loadOutbox()
    private val _state = MutableStateFlow(ChatUiState(currentId = kv.get(CHAT_CURRENT_KEY)))
    val state: StateFlow<ChatUiState> = _state

    @Volatile private var streamOwner: StreamOwner? = null
    @Volatile private var streamText: String = ""
    @Volatile private var genJob: Job? = null
    @Volatile private var foregroundLost = false
    @Volatile private var probeJob: Job? = null
    @Volatile private var busyRetry: Job? = null
    @Volatile private var chatVisible = false
    @Volatile private var pollSession: PollSession? = null
    @Volatile private var pollJob: Job? = null
    private var pollSeq = 0L

    init {
        publishQueued()
        deviceState.setForegroundListener { onForeground(it) }
    }

    private fun loadOutbox(): List<QueuedMessage> {
        val text = kv.get(CHAT_OUTBOX_KEY)
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(outboxSerializer, text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveOutbox(items: List<QueuedMessage>) {
        outbox = items
        kv.put(CHAT_OUTBOX_KEY, json.encodeToString(outboxSerializer, items))
        publishQueued()
    }

    private fun publishQueued() {
        _state.update { s -> s.copy(queued = outbox.filter { it.chatId == s.currentId }) }
    }

    /** True while at least one message waits for the server. */
    val hasQueued: Boolean get() = outbox.isNotEmpty()

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun notice(text: String) {
        _state.update { it.copy(notice = text) }
    }

    private fun setCurrent(id: String?) {
        if (id != null) kv.put(CHAT_CURRENT_KEY, id)
        if (pollSession?.chatId != id) stopPolling()
        _state.update { it.copy(currentId = id, messages = if (it.currentId == id) it.messages else emptyList(), typing = false) }
        publishQueued()
        publishStream()
    }

    private fun ordered(chats: List<ChatSummaryDto>) = chats.sortedWith(compareByDescending<ChatSummaryDto> { it.pinned }.thenByDescending { it.lastUsedAt })

    // ---- reading ----

    /** Loads the chat list and the open chat (the most recent personal chat when none is open), then sends anything queued. */
    suspend fun refresh() {
        when (val res = api.chatList()) {
            is HpResult.Ok -> {
                knownChats.clear()
                res.value.chats.forEach { knownChats += it.id }
                val chats = ordered(res.value.chats)
                _state.update { it.copy(chats = chats, activeCount = res.value.activeCount, limit = res.value.limit, offline = false) }
                val current = _state.value.currentId
                val keep = current != null && (current in knownChats || outbox.any { it.chatId == current })
                if (!keep) setCurrent(chats.firstOrNull { !it.pinned }?.id ?: chats.firstOrNull()?.id)
                _state.value.currentId?.let { if (it in knownChats) loadCurrent() }
            }
            is HpResult.Offline -> _state.update { it.copy(offline = true) }
            is HpResult.Http -> notice(res.message)
        }
        drain()
    }

    private suspend fun loadCurrent() {
        val id = _state.value.currentId ?: return
        when (val res = api.chat(id)) {
            is HpResult.Ok -> if (_state.value.currentId == id) _state.update { it.copy(messages = res.value.messages, offline = false) }
            is HpResult.Offline -> _state.update { it.copy(offline = true) }
            is HpResult.Http -> if (res.status == 404) { knownChats -= id } else notice(res.message)
        }
    }

    /** Opens [id]: shows it at once, then loads its messages. */
    suspend fun select(id: String) {
        setCurrent(id)
        if (id in knownChats || _state.value.chats.any { it.id == id }) loadCurrent()
    }

    fun selectInBackground(id: String) {
        scope.launch { select(id) }
    }

    suspend fun loadMemory() {
        when (val res = api.memory()) {
            is HpResult.Ok -> _state.update { it.copy(memory = res.value.notes, memoryLoaded = true, offline = false) }
            is HpResult.Offline -> _state.update { it.copy(offline = true, memoryLoaded = true) }
            is HpResult.Http -> { notice(res.message); _state.update { it.copy(memoryLoaded = true) } }
        }
    }

    // ---- chats ----

    /**
     * Starts a chat: [id] repeats the same chat (never a second eviction). A reminder idea passes [firstAssistantText] and
     * [notificationId]. Offline, the chat exists only here until the first message is sent. Returns the chat id.
     */
    suspend fun newChat(id: String = newId(), firstAssistantText: String? = null, notificationId: Long? = null): String {
        when (val res = api.newChat(NewChatRequest(id, firstAssistantText, notificationId))) {
            is HpResult.Ok -> {
                knownChats += res.value.chat.id
                _state.update { s ->
                    val chats = ordered(s.chats.filter { it.id != res.value.chat.id } + res.value.chat)
                    s.copy(chats = chats, activeCount = chats.count { !it.pinned }, offline = false)
                }
                setCurrent(res.value.chat.id)
                res.value.archivedTitle?.let { notice("\"$it\" was archived and saved to Memory.") }
                loadCurrent()
            }
            is HpResult.Offline -> {
                val local = ChatSummaryDto(id, NEW_CHAT_TITLE, null, clock(), pinned = false)
                _state.update { s -> s.copy(chats = ordered(s.chats.filter { it.id != id } + local), offline = true) }
                setCurrent(id)
            }
            is HpResult.Http -> notice(res.message)
        }
        return id
    }

    fun newChatInBackground() {
        scope.launch { newChat() }
    }

    /** Deletes (archives) a chat; the server keeps a summary in Memory. The pinned report cannot be deleted. */
    suspend fun delete(id: String) {
        when (val res = api.deleteChat(id)) {
            is HpResult.Ok -> {
                knownChats -= id
                if (pollSession?.chatId == id) stopPolling()
                saveOutbox(outbox.filter { it.chatId != id })
                val wasCurrent = _state.value.currentId == id
                _state.update { s -> s.copy(chats = s.chats.filter { it.id != id }, activeCount = s.chats.count { !it.pinned && it.id != id }) }
                if (wasCurrent) setCurrent(_state.value.chats.firstOrNull { !it.pinned }?.id ?: _state.value.chats.firstOrNull()?.id)
                if (wasCurrent) _state.value.currentId?.let { loadCurrent() }
                refreshListOnly()
            }
            is HpResult.Offline -> { _state.update { it.copy(offline = true) }; notice(res.message) }
            is HpResult.Http -> notice(res.message)
        }
    }

    private suspend fun refreshListOnly() {
        val res = api.chatList() as? HpResult.Ok ?: return
        knownChats.clear()
        res.value.chats.forEach { knownChats += it.id }
        _state.update { it.copy(chats = ordered(res.value.chats), activeCount = res.value.activeCount, limit = res.value.limit) }
    }

    // ---- sending ----

    /** Queues [text] for the open chat (a new chat is started when none is open) and tries to send it. False when blank. */
    suspend fun sendNow(text: String, choice: ChoiceDto? = null, route: String = ROUTE_AUTO): Boolean {
        if (!enqueue(text, choice, route)) return false
        drain()
        return true
    }

    fun send(text: String, choice: ChoiceDto? = null, route: String = ROUTE_AUTO): Boolean {
        if (text.isBlank()) return false
        scope.launch { sendNow(text, choice, route) }
        return true
    }

    /** A choice chip: sends its name as Tim's message together with the typed choice (Contract `SendMessageRequest.choice`). */
    fun choose(choice: ChoiceDto) {
        send(choice.name, choice)
    }

    private fun enqueue(text: String, choice: ChoiceDto?, route: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        var chatId = _state.value.currentId
        if (chatId == null) {
            chatId = newId()
            val local = ChatSummaryDto(chatId, NEW_CHAT_TITLE, null, clock(), pinned = false)
            _state.update { s -> s.copy(chats = ordered(s.chats + local)) }
            setCurrent(chatId)
        }
        saveOutbox(outbox + QueuedMessage(newId(), chatId, t, clock(), screen(), choice, route = route))
        return true
    }

    /**
     * Sends the queue in order, one at a time; stops at the first message that gets no answer (it stays queued, in order).
     * Runs once at a time, so a refresh, a send and the after-sync call never overlap.
     */
    suspend fun drain() {
        if (!drainLock.tryLock()) return
        var finished = 0
        try {
            while (true) {
                val head = outbox.firstOrNull() ?: break
                if (!deliver(head)) break
                finished++
            }
        } finally {
            drainLock.unlock()
        }
        if (finished > 0) refreshListOnly()
    }

    /** Sends one queued message; true when it is finished (accepted or dropped) and the next may go. */
    private suspend fun deliver(item: QueuedMessage, retried: Boolean = false): Boolean {
        if (item.chatId !in knownChats) {
            when (val created = api.newChat(NewChatRequest(item.chatId))) {
                is HpResult.Ok -> {
                    knownChats += item.chatId
                    created.value.archivedTitle?.let { notice("\"$it\" was archived and saved to Memory.") }
                }
                is HpResult.Offline -> { _state.update { it.copy(offline = true) }; return false }
                is HpResult.Http -> { drop(item, created.message); return true }
            }
        }
        // A Codex ask never goes to the phone's model.
        val phone = item.route != ROUTE_CODEX && phoneAllowed()
        // A phone turn that is still open belongs to the foreground; any other drain leaves the item for later.
        if (!phone && phoneTurnOpen(item)) return false
        val showTyping = _state.value.currentId == item.chatId
        if (showTyping) _state.update { it.copy(typing = true, slowBrain = false) }
        val step: Step = try {
            attempt(item, phone)
        } finally {
            probeJob?.cancel()
            probeJob = null
            _state.update { it.copy(typing = false, slowBrain = false) }
        }
        return when (step) {
            is Step.Done -> true
            is Step.Hold -> false
            is Step.Result -> finish(item, step.res, retried)
        }
    }

    /** True when the phone may write a reply now: the four-input gate plus the foreground. */
    private fun phoneAllowed(): Boolean =
        PhoneBrainGate.allowed(
            enabled = kv.get(PHONE_BRAIN_ENABLED_KEY) != "false",
            modelPresent = phoneBrain.status().modelName != null,
            batteryPercent = deviceState.batteryPercent(),
            powerSave = deviceState.powerSave(),
        ) && deviceState.foreground()

    /** On foreground entry: when the phone may write and the laptop is off (the HP would write), load the phone's engine now. */
    private suspend fun prewarmIfLaptopOff() {
        if (!phoneAllowed()) return
        val answer = (api.brain() as? HpResult.Ok)?.value?.brain
        if (answer == "hp" && deviceState.foreground()) phoneBrain.prewarm()
    }

    private fun phoneTurnOpen(item: QueuedMessage): Boolean = item.phoneExpiresAt?.let { it > clock() } == true

    /** Which brain answers is asked in parallel (never delays the send); any failure leaves slowBrain false. Only for sends the HP writes. */
    private fun startProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            val answer = (api.brain() as? HpResult.Ok)?.value?.brain
            if (answer == "hp") _state.update { it.copy(slowBrain = true) }
        }
    }

    /** One send of [item] with [phone] as the flag; a 202 hands the turn to the phone's own model. */
    private suspend fun attempt(item: QueuedMessage, phone: Boolean): Step {
        if (!phone && item.route != ROUTE_CODEX) startProbe()
        val request = SendMessageRequest(item.messageId, item.text, item.createdAt, item.screen, item.choice, phoneBrain = phone, route = item.route)
        return when (val first = api.sendMessage(item.chatId, request)) {
            is HpResult.Ok -> when (val outcome = first.value) {
                is SendOutcome.Reply -> Step.Result(HpResult.Ok(outcome.response))
                is SendOutcome.PhoneTurn -> if (phone) runPhoneTurn(item, outcome.turn) else Step.Result(HpResult.Offline(HP_OFFLINE_TEXT))
            }
            is HpResult.Offline -> Step.Result(first)
            is HpResult.Http -> Step.Result(first)
        }
    }

    /** The phone writes the answer to [turn] and hands the raw decision to the HP; every failure ends in one resend with phoneBrain = false. */
    private suspend fun runPhoneTurn(item: QueuedMessage, turn: PhoneTurnDto): Step {
        saveOutbox(outbox.map { if (it.messageId == item.messageId) it.copy(phoneExpiresAt = turn.expiresAt) else it })
        if (!phoneAllowed()) return fallback(item)
        val owner = StreamOwner(item.chatId, item.messageId, turn.turnId)
        foregroundLost = false
        streamText = ""
        streamOwner = owner
        publishStream()
        val gen = scope.async {
            withTimeoutOrNull(phoneTimeoutMs) {
                phoneBrain.generate(turn.system, turn.user, turn.schema) { partial ->
                    if (streamOwner === owner) {
                        streamText = ReplyStream.extract(partial)
                        publishStream()
                    }
                }
            }
        }
        genJob = gen
        try {
            val out: PhoneBrainResult? = try {
                gen.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (e: Exception) {
                null
            }
            if (foregroundLost) return Step.Hold
            if (out == null) return fallback(item)
            val raw = out.raw
            if (raw.isNullOrBlank()) return fallback(item)
            val res = api.phoneResult(item.chatId, PhoneResultRequest(turn.turnId, turn.messageId, raw, out.firstTokenMs, out.tokensPerSecond))
            return when (res) {
                is HpResult.Ok -> { accept(item, res.value); Step.Done }
                is HpResult.Offline -> fallback(item)
                is HpResult.Http -> if (res.status == 409 || res.status == 404) reconcile(item) else fallback(item)
            }
        } finally {
            gen.cancel()
            if (genJob === gen) genJob = null
            if (streamOwner === owner) {
                streamOwner = null
                streamText = ""
                publishStream()
            }
        }
    }

    /** The phone could not finish the turn: the same message id goes again with phoneBrain = false (the HP answers). */
    private suspend fun fallback(item: QueuedMessage): Step {
        saveOutbox(outbox.map { if (it.messageId == item.messageId) it.copy(phoneExpiresAt = null) else it })
        streamOwner = null
        streamText = ""
        publishStream()
        return attempt(item, phone = false)
    }

    /** The result was refused (409/404): look at the chat the message belongs to. A stored reply or a missing chat ends the item. */
    private suspend fun reconcile(item: QueuedMessage): Step =
        when (val d = api.chat(item.chatId)) {
            is HpResult.Ok -> {
                val msgs = d.value.messages
                val at = msgs.indexOfFirst { it.id == item.messageId }
                if (at >= 0 && msgs.drop(at + 1).any { it.role == "assistant" }) {
                    saveOutbox(outbox.filter { it.messageId != item.messageId })
                    _state.update { s -> if (s.currentId == item.chatId) s.copy(messages = msgs, offline = false) else s.copy(offline = false) }
                    Step.Done
                } else fallback(item)
            }
            is HpResult.Offline -> Step.Result(d)
            is HpResult.Http -> if (d.status == 404) {
                knownChats -= item.chatId
                saveOutbox(outbox.filter { it.messageId != item.messageId })
                Step.Done
            } else fallback(item)
        }

    private fun accept(item: QueuedMessage, response: SendMessageResponse) {
        saveOutbox(outbox.filter { it.messageId != item.messageId })
        _state.update { s ->
            if (s.currentId != item.chatId) s.copy(offline = false)
            else s.copy(messages = (s.messages.filter { it.id != response.user.id && it.id != response.reply.id } + response.user + response.reply), offline = false)
        }
        response.archivedTitle?.let { notice("\"$it\" was archived and saved to Memory.") }
        if (item.route == ROUTE_CODEX && response.reply.text == CODEX_ASKED_TEXT) startPolling(item.chatId, response.reply)
    }

    private suspend fun finish(item: QueuedMessage, res: HpResult<SendMessageResponse>, retried: Boolean): Boolean =
        when (res) {
            is HpResult.Ok -> { accept(item, res.value); true }
            is HpResult.Offline -> { _state.update { it.copy(offline = true) }; false }
            is HpResult.Http -> when {
                res.status == 503 && res.message == WALLY_STILL_ANSWERING_TEXT -> { _state.update { it.copy(offline = false) }; scheduleBusyRetry(); false }
                res.status == 404 && !retried -> { knownChats -= item.chatId; deliver(item, retried = true) }
                res.status in 400..499 -> { drop(item, res.message); true }
                else -> { _state.update { it.copy(offline = true) }; false }
            }
        }

    /** One coalesced retry a little later (the HP is still finishing another phone turn). */
    private fun scheduleBusyRetry() {
        if (busyRetry?.isActive == true) return
        busyRetry = scope.launch {
            delay(busyRetryMs)
            busyRetry = null
            drain()
        }
    }

    private fun onForeground(foreground: Boolean) {
        if (foreground) {
            scope.launch { drain() }
            scope.launch { prewarmIfLaptopOff() }
        } else {
            foregroundLost = true
            genJob?.cancel()
        }
    }

    private fun publishStream() {
        _state.update { s ->
            val owner = streamOwner
            s.copy(streamingText = if (owner != null && owner.chatId == s.currentId) streamText else null)
        }
    }

    private fun drop(item: QueuedMessage, reason: String) {
        saveOutbox(outbox.filter { it.messageId != item.messageId })
        notice(reason)
    }

    // ---- Codex answer polling ----

    /** The Chat screen tells the controller when it is on screen; polling for a Codex answer runs only then. */
    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (!visible) stopPolling()
    }

    private fun stopPolling() {
        pollSession = null
        pollJob?.cancel()
        pollJob = null
    }

    private fun pollStillMine(session: PollSession) = pollSession === session && chatVisible && _state.value.currentId == session.chatId

    /** Re-reads [chatId] every [pollIntervalMs] for at most [pollWindowMs] until an assistant message newer than [reply] shows up. */
    private fun startPolling(chatId: String, reply: ChatMessageDto) {
        if (!chatVisible || _state.value.currentId != chatId) return
        stopPolling()
        val session = PollSession(++pollSeq, chatId, reply.id, reply.createdAt)
        pollSession = session
        pollJob = scope.launch {
            repeat(maxOf(1L, pollWindowMs / pollIntervalMs).toInt()) {
                delay(pollIntervalMs)
                if (!pollStillMine(session)) return@launch
                val res = api.chat(chatId)
                if (!pollStillMine(session)) return@launch
                when (res) {
                    is HpResult.Ok -> {
                        val server = res.value.messages
                        _state.update { s ->
                            val ids = server.map { it.id }.toSet()
                            val lastAt = server.maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE
                            s.copy(messages = server + s.messages.filter { it.id !in ids && it.createdAt > lastAt }, offline = false)
                        }
                        if (server.any { it.role == "assistant" && it.id != session.replyId && it.createdAt > session.replyCreatedAt }) {
                            if (pollSession === session) pollSession = null
                            return@launch
                        }
                    }
                    is HpResult.Http -> if (res.status == 404) { knownChats -= chatId; return@launch }
                    is HpResult.Offline -> Unit
                }
            }
            if (pollSession === session) pollSession = null
        }
    }

    // ---- Undo and cards ----

    /** Undo of an auto-written row; the server decides (it may have expired). Reloads the chat so the button state is true. */
    suspend fun undo(chatId: String, actionId: String) {
        when (val res = api.undo(chatId, actionId)) {
            is HpResult.Ok -> {
                notice(res.value.message)
                if (_state.value.currentId == chatId) loadCurrent()
            }
            is HpResult.Offline -> { _state.update { it.copy(offline = true) }; notice(res.message) }
            is HpResult.Http -> notice(res.message)
        }
    }

    fun undoInBackground(chatId: String, actionId: String) {
        scope.launch { undo(chatId, actionId) }
    }

    /**
     * Yes or No on an approval (a chat card or Files > Approvals). Nothing is written on this device: offline or a 409
     * only returns the server's message. After an answer from the server the open chat is reloaded.
     */
    suspend fun decideApproval(approvalId: String, yes: Boolean): DecisionOutcome {
        val res = api.decideApproval(approvalId, yes)
        val outcome = when (res) {
            is HpResult.Ok -> DecisionOutcome(true, res.value.message)
            is HpResult.Offline -> { _state.update { it.copy(offline = true) }; DecisionOutcome(false, res.message) }
            is HpResult.Http -> DecisionOutcome(false, res.message)
        }
        if (res !is HpResult.Offline && _state.value.currentId != null) loadCurrent()
        return outcome
    }

    fun decideInBackground(approvalId: String, yes: Boolean) {
        scope.launch { notice(decideApproval(approvalId, yes).message) }
    }

    // ---- laptop list column ----

    fun toggleListHidden() {
        _state.update { it.copy(listHidden = !it.listHidden) }
    }
}
