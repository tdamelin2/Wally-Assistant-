package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.mentionsDreams
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Chat
import dev.tim.assistant.db.Chat_message
import dev.tim.assistant.model.CardDto
import dev.tim.assistant.model.ActionRefDto
import dev.tim.assistant.model.ChatDetailResponse
import dev.tim.assistant.model.ChatListResponse
import dev.tim.assistant.model.ChatMessageDto
import dev.tim.assistant.model.ChatSummaryDto
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.MemoryNoteDto
import dev.tim.assistant.model.MemoryResponse
import dev.tim.assistant.server.ServerDb

/** What a chat the caller asked for looks like after [ChatStore.create]. */
data class CreatedChat(val chat: Chat, val archivedTitle: String?)

sealed interface DeleteChatResult {
    data object Deleted : DeleteChatResult
    data object NotFound : DeleteChatResult
    data object Pinned : DeleteChatResult
}

/**
 * The private chat tables (chat, chat_message, memory_note). Every change goes through [ServerWriter] (one lock, one
 * transaction). At most [MAX_ACTIVE_CHATS] unpinned chats are active; making an 11th archives the least recently used one
 * in the same transaction. An archived or deleted chat is kept, hidden, until its memory note has its summary.
 */
class ChatStore(
    private val sdb: ServerDb,
    private val writer: ServerWriter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = sdb.db

    // ---- chats -----------------------------------------------------------------------------------------

    /**
     * Creates chat [id] (or returns the existing one: a repeated id never evicts a second chat). With [firstAssistantText] the
     * chat starts with that text as Wally's first message and takes its title from it.
     */
    suspend fun create(id: String, firstAssistantText: String? = null): CreatedChat = writer.transaction { tx ->
        val q = tx.db.chatQueries
        q.selectChatById(id).executeAsOneOrNull()?.let { return@transaction CreatedChat(it, null) }
        var archived: String? = null
        if (q.countActiveUnpinnedChats().executeAsOne() >= MAX_ACTIVE_CHATS) {
            val oldest = q.selectOldestActiveUnpinnedChat().executeAsOne()
            archived = oldest.title
            closeChat(tx.db, oldest, tx.now)
        }
        val text = firstAssistantText?.trim()?.takeIf { it.isNotEmpty() }
        q.insertChat(id, if (text != null) titleFrom(text) else DEFAULT_CHAT_TITLE, tx.now, tx.now, 0)
        if (text != null) q.insertMessage("a:$id:first", id, "assistant", text, null, null, tx.now)
        CreatedChat(q.selectChatById(id).executeAsOne(), archived)
    }

    /** The pinned "Weekly report" chat, created when missing. */
    suspend fun ensurePinned(): Chat = writer.transaction { tx ->
        tx.db.chatQueries.selectPinnedChat().executeAsOneOrNull()?.let { return@transaction it }
        tx.db.chatQueries.insertChat(PINNED_CHAT_ID, PINNED_CHAT_TITLE, tx.now, tx.now, 1)
        tx.db.chatQueries.selectChatById(PINNED_CHAT_ID).executeAsOne()
    }

    fun chat(id: String): Chat? = db.chatQueries.selectChatById(id).executeAsOneOrNull()?.takeIf { it.archived_at == null }

    fun summary(c: Chat): ChatSummaryDto = ChatSummaryDto(
        c.id, c.title, db.chatQueries.selectLastMessageText(c.id).executeAsOneOrNull()?.let { cut(it, 120) },
        c.last_used_at, c.pinned != 0L,
    )

    fun list(): ChatListResponse {
        val chats = db.chatQueries.selectActiveChats().executeAsList()
        return ChatListResponse(chats.map { summary(it) }, MAX_ACTIVE_CHATS, db.chatQueries.countActiveUnpinnedChats().executeAsOne().toInt())
    }

    fun detail(id: String): ChatDetailResponse? {
        val c = chat(id) ?: return null
        return ChatDetailResponse(summary(c), db.chatQueries.selectMessagesByChat(id).executeAsList().filterNot { hiddenUnfinished(it) }.map { toDto(it) })
    }

    /** An assistant row still being written (not final) with no text, action or card yet: no device shows an empty Wally bubble. */
    private fun hiddenUnfinished(m: Chat_message): Boolean {
        if (m.role != "assistant" || m.request_state == null || m.text.isNotBlank()) return false
        val extras = parseExtras(m.card_json)
        return extras.actions.isEmpty() && extras.cards.isEmpty()
    }

    /** Deleting hides the chat at once; a memory note is queued and the chat is dropped when its summary is saved. */
    suspend fun delete(id: String): DeleteChatResult = writer.transaction { tx ->
        val c = tx.db.chatQueries.selectChatById(id).executeAsOneOrNull()?.takeIf { it.archived_at == null }
            ?: return@transaction DeleteChatResult.NotFound
        if (c.pinned != 0L) return@transaction DeleteChatResult.Pinned
        closeChat(tx.db, c, tx.now)
        DeleteChatResult.Deleted
    }

    private fun closeChat(db: AssistantDb, chat: Chat, now: Long) {
        val q = db.chatQueries
        if (q.selectLastMessageText(chat.id).executeAsOneOrNull() == null) {
            q.deleteChat(chat.id)
        } else {
            q.archiveChat(now, chat.id)
            q.insertNote("note:${chat.id}", chat.id, chat.title, now)
        }
    }

    // ---- messages --------------------------------------------------------------------------------------

    fun message(id: String): Chat_message? = db.chatQueries.selectMessageById(id).executeAsOneOrNull()

    fun messages(chatId: String): List<Chat_message> = db.chatQueries.selectMessagesByChat(chatId).executeAsList()

    /**
     * Stores Tim's message (id = the client's messageId; a repeat is ignored), touches the chat and gives a new chat its
     * title. The stored time never goes before the chat's newest message and never after now, so the order stays right
     * even when the phone clock is off.
     */
    suspend fun addUserMessage(chatId: String, messageId: String, text: String, createdAt: Long): Chat_message = writer.transaction { tx ->
        val q = tx.db.chatQueries
        val newest = q.selectLastMessagesByChat(chatId, 1L).executeAsList().firstOrNull()?.created_at ?: 0L
        val stamp = createdAt.coerceIn(newest, maxOf(newest, tx.now))
        q.insertMessage(messageId, chatId, "user", text, null, STATE_RUNNING, stamp)
        q.touchChat(maxOf(tx.now, stamp), chatId)
        val c = q.selectChatById(chatId).executeAsOne()
        if (c.title == DEFAULT_CHAT_TITLE && c.pinned == 0L) q.renameChat(titleFrom(text), chatId)
        q.selectMessageById(messageId).executeAsOne()
    }

    /**
     * Adds [edit] to the reply's extras inside the caller's transaction (creating the reply row, still RUNNING, when it is the
     * first thing the turn wrote). Auto writes and cards use it so the row and its Undo/card record commit together.
     */
    fun recordOnReply(db: AssistantDb, chatId: String, replyId: String, now: Long, edit: (MessageExtras) -> MessageExtras) {
        val q = db.chatQueries
        val existing = q.selectMessageById(replyId).executeAsOneOrNull()
        val extras = edit(parseExtras(existing?.card_json))
        if (existing == null) {
            val after = q.selectLastMessagesByChat(chatId, 1L).executeAsList().firstOrNull()?.created_at ?: 0L
            q.insertMessage(replyId, chatId, "assistant", "", extras.encode(), STATE_RUNNING, maxOf(now, after + 1))
        } else {
            q.updateMessageCard(extras.encode(), replyId)
        }
    }

    /** Writes the final reply text (and the extras) and marks it done. */
    suspend fun finishReply(chatId: String, replyId: String, text: String, extrasEdit: (MessageExtras) -> MessageExtras): Chat_message =
        writer.transaction { tx ->
            recordOnReply(tx.db, chatId, replyId, tx.now) { extrasEdit(it).copy(expiresAt = null) }
            setMessageText(replyId, text)
            tx.db.chatQueries.updateMessageRequestState(null, replyId)
            tx.db.chatQueries.touchChat(tx.now, chatId)
            tx.db.chatQueries.selectMessageById(replyId).executeAsOne()
        }

    // ---- phone turns (PLAN 19.3) -----------------------------------------------------------------------

    /** Creates Wally's reply row for a turn the phone writes: blank text, PHONE_PENDING, the turn's deadline in the extras. */
    suspend fun startPhoneReply(
        chatId: String, replyId: String, expiresAt: Long, sourceMessageId: String? = null, fromHp: Boolean = false,
    ): Chat_message = writer.transaction { tx ->
        val q = tx.db.chatQueries
        val after = q.selectLastMessagesByChat(chatId, 1L).executeAsList().firstOrNull()?.created_at ?: 0L
        q.insertMessage(replyId, chatId, "assistant", "", MessageExtras(expiresAt = expiresAt, sourceMessageId = sourceMessageId, fromHp = fromHp).encode(), STATE_PHONE_PENDING, maxOf(tx.now, after + 1))
        q.touchChat(tx.now, chatId)
        q.selectMessageById(replyId).executeAsOne()
    }

    /**
     * Moves a PHONE_PENDING reply to RUNNING, only when [chatId] is still an active chat and the row is that chat's own reply.
     * After this no tick and no second result can touch the reply. False = nothing changed.
     */
    suspend fun claimPhoneReply(chatId: String, replyId: String): Boolean = writer.transaction { tx ->
        val q = tx.db.chatQueries
        val chat = q.selectChatById(chatId).executeAsOneOrNull()
        val row = q.selectMessageById(replyId).executeAsOneOrNull()
        if (chat == null || chat.archived_at != null || row == null || row.chat_id != chatId || row.request_state != STATE_PHONE_PENDING) {
            return@transaction false
        }
        q.updateMessageRequestState(STATE_RUNNING, replyId)
        true
    }

    /** Removes one message row (a reply that never produced anything, so a retry starts clean). */
    suspend fun deleteMessage(id: String) = writer.transaction { tx -> tx.db.chatQueries.deleteMessageById(id) }

    /** Every reply whose phone turn is still open (any chat). */
    fun phonePendingReplies(): List<Chat_message> = db.chatQueries.selectPhonePendingReplies().executeAsList()

    /** True while [chatId] holds a reply the phone is still writing. */
    fun hasPhonePending(chatId: String): Boolean = messages(chatId).any { it.role == "assistant" && it.request_state == STATE_PHONE_PENDING }

    /** chat.sq has no text update (the shared schema is Part A's); the reply text is the one column changed with plain SQL. */
    private fun setMessageText(id: String, text: String) {
        sdb.driver.execute(null, "UPDATE chat_message SET text = ? WHERE id = ?", 2) {
            bindString(0, text)
            bindString(1, id)
        }
    }

    suspend fun markUserMessageDone(messageId: String) = writer.transaction { tx ->
        tx.db.chatQueries.updateMessageRequestState(null, messageId)
    }

    fun toDto(m: Chat_message): ChatMessageDto {
        val extras = parseExtras(m.card_json)
        return ChatMessageDto(
            m.id, m.role, m.text, m.created_at,
            actions = extras.actions.map { ActionRefDto(it.actionId, it.label, it.expiresAt, it.undone) },
            cards = extras.cards.mapNotNull { cardOf(it) },
            choices = extras.choices,
        )
    }

    private fun cardOf(approvalId: String): CardDto? {
        val a = db.approvalQueries.selectById(approvalId).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return null
        val status = when {
            a.status == "DONE" || a.status == "REJECTED" || a.status == "FAILED" -> a.status
            a.status == "EXPIRED" -> "FAILED"
            a.status == "PENDING" && clock() - a.createdAt > APPROVAL_VALID_MS -> "FAILED"
            else -> "PENDING"
        }
        val display = if (status == "PENDING" && a.tool == "update_exam") CardDisplays.forExamCard(db, a) else null
        return CardDto(a.id, a.tool, Tools.find(a.tool)?.label ?: a.tool, a.summary, status, display)
    }

    /**
     * The assistant message of [chatId] that offered the chip [token] for this item (same token, id and kind), with the chip as
     * stored: its name and id are the server's, never the client's. Null when this chat never offered it.
     */
    fun chipIssuer(chatId: String, token: String, id: String, kind: String): Pair<Chat_message, ChoiceDto>? {
        if (token.isBlank()) return null
        for (m in messages(chatId)) {
            if (m.role != "assistant") continue
            val chip = parseExtras(m.card_json).choices.firstOrNull { it.token == token && it.id == id && it.kind == kind } ?: continue
            return m to chip
        }
        return null
    }

    // ---- dream rule ------------------------------------------------------------------------------------

    /**
     * Ids of the messages that handled a dream: a message of Tim's that mentions dreams, the reply after it, and any reply
     * flagged [MessageExtras.dream]. They are left out of later prompts and of every summary unless Tim's current message
     * mentions dreams again.
     */
    fun dreamTainted(messages: List<Chat_message>): Set<String> {
        val out = HashSet<String>()
        var taintReplies = false
        for (m in messages) {
            if (m.role == "user") {
                taintReplies = mentionsDreams(m.text)
                if (taintReplies) out += m.id
            } else if (taintReplies || parseExtras(m.card_json).dream) {
                out += m.id
            }
        }
        return out
    }

    // ---- memory ----------------------------------------------------------------------------------------

    fun memory(): MemoryResponse = MemoryResponse(
        db.chatQueries.selectAllNotes().executeAsList().map {
            MemoryNoteDto(it.id, it.source_chat_title, it.summary, it.created_at, it.state == "PENDING")
        },
    )

    /** The newest [limit] finished notes, newest first. */
    fun newestSummaries(limit: Int): List<String> =
        db.chatQueries.selectNewestDoneNotes(limit.toLong()).executeAsList().mapNotNull { it.summary?.takeIf { s -> s.isNotBlank() } }

    private fun cut(text: String, max: Int): String {
        val one = text.replace('\r', ' ').replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max)
    }
}

/** An approval is valid for 7 days (the same rule as the shared approval logic). */
private const val APPROVAL_VALID_MS = 7L * 24 * 60 * 60 * 1000
