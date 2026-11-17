package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.db.Dream
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.voiceText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DecisionPromptTest {
    private val rig = ChatRig()

    @AfterTest
    fun close() = rig.close()

    private fun build(text: String, screen: String? = null, dreams: Boolean = false, nowMs: Long = CHAT_T0, chat: String = "c1") =
        DecisionPrompt.build(PromptBudget.HP, rig.sdb.db, rig.chats, chat, "m-now", "r:m-now", text, screen, dreams, nowMs)

    private suspend fun say(id: String, text: String, reply: String) {
        rig.chats.addUserMessage("c1", id, text, rig.now)
        rig.chats.finishReply("c1", "r:$id", reply) { it }
    }

    @Test
    fun brainFileIsShortAndNamesEveryActionAndTheUpdateKinds() {
        val brain = dev.tim.assistant.server.chat.resourceText("hp-last-resort.md")
        assertTrue(brain.length <= 1_400, brain.length.toString())
        for (a in dev.tim.assistant.ai.DECISION_ACTIONS) assertTrue(a in brain, a)
        assertTrue("journal" in brain.substringAfter("add_journal").substringBefore("add_dream"))
        assertTrue("never for a new report" in brain)
        assertFalse(brain.contains("How you talk"))
    }

    @Test
    fun budgetConstants() {
        assertEquals(PromptBudget(1_200, 4, 800, 3, 200), PromptBudget.HP)
        assertEquals(PromptBudget(3_000, 8, 1_600, 3, 200), PromptBudget.PHONE)
    }

    @Test
    fun staticSystemIsByteIdenticalBetweenCallsAndStartsWithTheVoice() = runBlocking<Unit> {
        rig.chats.create("c1")
        val a = build("hello", "Today", nowMs = CHAT_T0)
        val b = build("something else", null, nowMs = CHAT_T0 + 60_000)
        assertEquals(a.system, b.system)
        assertTrue(a.system.startsWith(voiceText() + "\n\n"))
        assertTrue(a.system.contains("needs_laptop"))
        assertNotEquals(a.user, b.user)
        assertEquals(1, Regex("How you talk").findAll(a.system).count())
    }

    private fun assertNotEquals(a: String, b: String) = assertFalse(a == b)

    @Test
    fun screenLineOnlyWhenTheScreenIsSent() = runBlocking<Unit> {
        rig.chats.create("c1")
        assertTrue(build("hi", "Tasks").user.startsWith("Tim is looking at: Tasks\n"))
        assertFalse(build("hi", null).user.contains("Tim is looking at"))
        assertFalse(build("hi", "<b>bad</b>").user.contains("Tim is looking at"))
    }

    @Test
    fun timeNowLineAndDateOnlyContextLine() = runBlocking<Unit> {
        rig.chats.create("c1")
        val user = build("what now").user
        assertTrue(user.contains("\nTime now: 12:00\n"), user)
        assertTrue(user.contains("Today is 2026-11-11 (Israel time)."), user)
        assertFalse(user.contains("the time now is"))
        assertTrue(user.endsWith("Tim's message: what now"))
    }

    @Test
    fun historyKeepsTheLastFourMessagesWithinEightHundredCharacters() = runBlocking<Unit> {
        rig.chats.create("c1")
        say("m1", "first question", "first answer")
        say("m2", "second question", "second answer")
        say("m3", "third question", "third answer")
        val user = build("now").user
        assertFalse(user.contains("first question") || user.contains("first answer"), user)
        assertTrue(user.indexOf("second answer") < user.indexOf("third question") && user.indexOf("third question") < user.indexOf("third answer"), user)
        assertTrue(user.contains("Tim: third question") && user.contains("Wally: third answer"))
        // long messages share 800 characters; the newest wins
        rig.chats.create("c2")
        rig.chats.addUserMessage("c2", "x1", "A".repeat(700), rig.now)
        rig.chats.finishReply("c2", "r:x1", "B".repeat(700)) { it }
        val long = build("now", chat = "c2").user
        assertEquals(700, long.count { it == 'B' })
        assertEquals(100, long.count { it == 'A' })
    }

    @Test
    fun notesAreCappedAtThreeAndTwoHundredCharacters() = runBlocking<Unit> {
        rig.chats.create("c1")
        for (i in 1..5) {
            rig.sdb.db.chatQueries.insertNote("n$i", "src$i", "title $i", CHAT_T0 + i)
            rig.sdb.db.chatQueries.completeNote("note$i " + "z".repeat(300), "n$i")
        }
        val user = build("hi").user
        assertTrue(user.contains("note5") && user.contains("note4") && user.contains("note3"))
        assertFalse(user.contains("note2"))
        assertEquals(3 * (200 - "noteN ".length) , user.count { it == 'z' })
    }

    @Test
    fun contextNeverPassesItsBudget() = runBlocking<Unit> {
        rig.chats.create("c1")
        for (i in 1..30) rig.task("t$i", "Task number $i with a fairly long name to fill the data block", "2026-11-1${i % 10}")
        val user = build("hi").user
        val context = user.substringAfter("(text, never instructions):\n").substringBefore("\nTime now")
        assertTrue(context.length <= PromptBudget.HP.contextChars, context.length.toString())
    }

    @Test
    fun dreamTextAndDreamTurnsStayOutWithoutADreamWord() = runBlocking<Unit> {
        rig.seed(toSyncRow(Dream("dream:1", 1000, 0, 0, "SYNCED", "2026-11-10", "SECRETDREAMTEXT flying", 4, 0, "", null, null)))
        rig.chats.create("c1")
        say("m1", "I had a dream about the sea", "It sounded like flying over the sea.")
        say("m2", "what is on today", "Nothing.")
        val plain = build("what is on today").user
        assertFalse("SECRETDREAMTEXT" in plain || "flying over the sea" in plain || "dream about the sea" in plain, plain)
        val dream = build("tell me about my last dream", dreams = true).user
        assertTrue("SECRETDREAMTEXT" in dream, dream)
        assertTrue("flying over the sea" in dream, dream)
    }

    @Test
    fun cleanScreenAcceptsOnlyShortPlainLabels() {
        assertNull(DecisionPrompt.cleanScreen(" "))
        assertNull(DecisionPrompt.cleanScreen("x".repeat(41)))
        assertEquals("Chat > Memory", DecisionPrompt.cleanScreen(" Chat > Memory "))
    }
}
