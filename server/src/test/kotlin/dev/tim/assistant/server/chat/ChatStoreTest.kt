package dev.tim.assistant.server.chat

import dev.tim.assistant.server.ai.chatBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

class ChatStoreTest {
    private val rig = ChatRig()
    private val chats get() = rig.chats

    @AfterTest
    fun close() = rig.close()

    private suspend fun chatWithMessage(id: String, text: String = "hello there") {
        chats.create(id)
        chats.addUserMessage(id, "m-$id", text, rig.now)
    }

    @Test
    fun theEleventhChatArchivesTheLeastRecentlyUsedOne() = runBlocking<Unit> {
        for (i in 1..10) {
            rig.now += 1_000
            chatWithMessage("c$i", "chat number $i")
        }
        assertEquals(10, chats.list().activeCount)
        rig.now += 1_000
        val created = chats.create("c11")
        assertEquals("chat number 1", created.archivedTitle)
        val list = chats.list()
        assertEquals(10, list.activeCount)
        assertEquals(10, list.limit)
        assertFalse(list.chats.any { it.id == "c1" })
        assertTrue(list.chats.any { it.id == "c11" })
        val note = rig.sdb.db.chatQueries.selectNoteBySourceChat("c1").executeAsOne()
        assertEquals("PENDING", note.state)
        // the chat is kept (hidden) until its summary is saved
        assertEquals(1, rig.sdb.db.chatQueries.selectMessagesByChat("c1").executeAsList().size)
    }

    @Test
    fun theSameNewChatIdAgainNeverEvictsASecondChat() = runBlocking<Unit> {
        for (i in 1..10) {
            rig.now += 1_000
            chatWithMessage("c$i")
        }
        rig.now += 1_000
        chats.create("c11")
        val again = chats.create("c11")
        assertNull(again.archivedTitle)
        assertEquals(10, chats.list().activeCount)
        assertEquals(1, rig.sdb.db.chatQueries.selectPendingNotes().executeAsList().size)
    }

    @Test
    fun aTieOnLastUsedTimeIsBrokenByIdSoTheChoiceIsStable() = runBlocking<Unit> {
        for (i in 1..10) chatWithMessage("tie-%02d".format(11 - i)) // all created at the same time, ids tie-10 .. tie-01
        chats.create("fresh")
        assertNull(chats.chat("tie-01"))
        assertNotNull(chats.chat("tie-02"))
    }

    @Test
    fun theTitleIsTheFirstSixWordsOfTheFirstMessage() = runBlocking<Unit> {
        chats.create("c1")
        chats.addUserMessage("c1", "m1", "please remind me to call the dentist tomorrow morning", rig.now)
        chats.addUserMessage("c1", "m2", "and another thing entirely", rig.now)
        assertEquals("please remind me to call the", chats.chat("c1")!!.title)
    }

    @Test
    fun theWeeklyReportChatIsPinnedNeverCountedAndCannotBeDeleted() = runBlocking<Unit> {
        val pinned = chats.ensurePinned()
        assertEquals(1L, pinned.pinned)
        assertEquals(pinned.id, chats.ensurePinned().id)
        assertEquals(0, chats.list().activeCount)
        for (i in 1..10) chatWithMessage("c$i")
        chats.create("c11")
        assertNotNull(chats.chat(pinned.id)) // never evicted
        assertEquals(DeleteChatResult.Pinned, chats.delete(pinned.id))
        assertEquals(DeleteChatResult.NotFound, chats.delete("nope"))
    }

    @Test
    fun deletingQueuesANoteAndTheChatGoesWhenItsSummaryIsSaved() = runBlocking<Unit> {
        chatWithMessage("c1", "I want to learn calculus")
        rig.sdb.db.chatQueries.insertMessage("a1", "c1", "assistant", "Sure, let us plan it.", null, null, rig.now + 1)
        assertEquals(DeleteChatResult.Deleted, chats.delete("c1"))
        assertNull(chats.chat("c1"))
        val pending = rig.sdb.db.chatQueries.selectPendingNotes().executeAsList()
        assertEquals(1, pending.size)
        assertEquals(2, chats.messages("c1").size) // kept until the summary exists

        rig.summaryFake.reply = { 200 to chatBody("Tim wants to learn calculus; Wally offered a plan.") }
        assertEquals(1, rig.summarizer.tick())
        assertEquals(0, chats.messages("c1").size)
        assertNull(rig.sdb.db.chatQueries.selectChatById("c1").executeAsOneOrNull())
        val note = rig.sdb.db.chatQueries.selectAllNotes().executeAsList().single()
        assertEquals("DONE", note.state)
        assertEquals("Tim wants to learn calculus; Wally offered a plan.", note.summary)
        assertEquals(listOf("Tim wants to learn calculus; Wally offered a plan."), chats.newestSummaries(10))
        val memory = chats.memory().notes.single()
        assertFalse(memory.pending)
    }

    @Test
    fun anEmptyChatIsDroppedWithoutANote() = runBlocking<Unit> {
        chats.create("c1")
        chats.delete("c1")
        assertNull(rig.sdb.db.chatQueries.selectChatById("c1").executeAsOneOrNull())
        assertTrue(rig.sdb.db.chatQueries.selectAllNotes().executeAsList().isEmpty())
    }

    @Test
    fun aFailingModelRetriesThenEndsTheNoteAsFailed() = runBlocking<Unit> {
        chatWithMessage("c1")
        chats.delete("c1")
        rig.summaryFake.reply = { 500 to "error" }
        assertEquals(0, rig.summarizer.tick())
        assertEquals(1L, rig.sdb.db.chatQueries.selectNoteById("note:c1").executeAsOne().attempts)
        assertNotNull(rig.sdb.db.chatQueries.selectChatById("c1").executeAsOneOrNull()) // still kept
        rig.summarizer.tick()
        assertEquals(1, rig.summarizer.tick()) // third try gives up
        val note = rig.sdb.db.chatQueries.selectNoteById("note:c1").executeAsOne()
        assertEquals("FAILED", note.state)
        assertNull(note.summary)
        assertFalse(chats.memory().notes.single().pending)
    }

    @Test
    fun whenChatHoldsTheHpLockTheSummaryWaitsForTheNextTick() = runBlocking<Unit> {
        chatWithMessage("c1")
        chats.delete("c1")
        rig.summaryFake.reply = { 200 to chatBody("A summary.") }
        rig.lock.withLock {
            assertEquals(0, rig.summarizer.tick())
            assertTrue(rig.summaryFake.bodies.isEmpty())
        }
        assertEquals(1, rig.summarizer.tick())
        assertEquals("DONE", rig.sdb.db.chatQueries.selectNoteById("note:c1").executeAsOne().state)
    }

    @Test
    fun summariesLeaveDreamTurnsOut() = runBlocking<Unit> {
        chats.create("c1")
        chats.addUserMessage("c1", "m1", "I had a strange dream about flying over the sea", rig.now)
        rig.sdb.db.chatQueries.insertMessage("a1", "c1", "assistant", "Sounds vivid: flying over the sea.", null, null, rig.now + 1)
        chats.addUserMessage("c1", "m2", "what is on my calendar tomorrow", rig.now + 2)
        rig.sdb.db.chatQueries.insertMessage("a2", "c1", "assistant", "Nothing tomorrow.", null, null, rig.now + 3)
        chats.delete("c1")
        rig.summaryFake.reply = { 200 to chatBody("Tim asked about the calendar.") }
        rig.summarizer.tick()
        val sent = rig.summaryFake.bodies.single()
        assertTrue("calendar" in sent)
        assertFalse("flying" in sent)
        assertFalse("dream" in sent.lowercase())
    }

    @Test
    fun aChatThatWasOnlyDreamsGetsAPlainNote() = runBlocking<Unit> {
        chats.create("c1")
        chats.addUserMessage("c1", "m1", "I dreamt about my old school", rig.now)
        rig.sdb.db.chatQueries.insertMessage("a1", "c1", "assistant", "Tell me more about the school.", null, null, rig.now + 1)
        chats.delete("c1")
        assertEquals(1, rig.summarizer.tick())
        assertTrue(rig.summaryFake.bodies.isEmpty()) // no model call at all
        assertEquals(NOTHING_TO_REMEMBER, chats.memory().notes.single().summary)
    }

    @Test
    fun aMessageIdSeenBeforeIsIgnoredAndAMessageGetsTheServerOrder() = runBlocking<Unit> {
        chats.create("c1")
        val first = chats.addUserMessage("c1", "m1", "one", rig.now + 999_999) // phone clock far ahead
        assertTrue(first.created_at <= rig.now)
        chats.addUserMessage("c1", "m1", "one", rig.now)
        assertEquals(1, chats.messages("c1").size)
    }
}
