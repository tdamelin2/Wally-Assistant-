package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.SendResult
import dev.tim.assistant.server.chat.TurnResult
import dev.tim.assistant.server.chat.parseExtras
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Smoke tests of the ladder order inside [dev.tim.assistant.server.chat.ChatRunner] (the full port is step 5b). */
class LadderWiringTest {
    private val rig = ChatRig()

    @AfterTest
    fun close() = rig.close()

    private suspend fun send(id: String, text: String, choice: dev.tim.assistant.model.ChoiceDto? = null): SendResult {
        rig.chats.create("c1")
        return rig.service.send("c1", SendMessageRequest(id, text, CHAT_T0, null, choice))
    }

    private suspend fun count(table: String) = rig.pull().count { it.table == table }

    @Test
    fun aClearCommandIsAnsweredByCodeBeforeAnyModel() = runBlocking<Unit> {
        val r = assertIs<SendResult.Ok>(send("m1", "add task call the dentist"))
        assertEquals(1, count("task"))
        assertEquals(0, rig.fake.bodies.size)
        assertEquals("code", parseExtras(rig.chats.message("r:m1")!!.card_json).brain)
        assertFalse(ID_LIKE.containsMatchIn(r.response.reply.text))
        assertEquals(1, r.response.reply.actions.size)
    }

    @Test
    fun aQuestionAboutTodayIsAnsweredByCodeAndWritesNothing() = runBlocking<Unit> {
        val r = assertIs<SendResult.Ok>(send("m1", "what's on today?"))
        assertEquals(0, rig.fake.bodies.size)
        assertEquals(0, rig.pull().size)
        assertTrue(r.response.reply.text.isNotBlank())
    }

    @Test
    fun aBlockedMessageWithNoModelIsUnavailableAndWritesNothing() = runBlocking<Unit> {
        // no laptop (HP-only rig) and the HP model failing: nothing may be saved and the client keeps the message queued
        rig.hpFake.reply = { 500 to "boom" }
        assertIs<SendResult.Unavailable>(send("m1", "I did not spend 35 on lunch"))
        assertEquals(0, count("spending"))
    }

    @Test
    fun twoMatchingTasksGiveChipsAndTheTapCompletesInCode() = runBlocking<Unit> {
        rig.task("a", "Write report")
        rig.task("b", "Report to the bank")
        val first = assertIs<SendResult.Ok>(send("m1", "mark the report task done"))
        val chips = first.response.reply.choices
        assertEquals(2, chips.size)
        assertEquals("update_task", parseExtras(rig.chats.message("r:m1")!!.card_json).pending?.tool)
        val second = assertIs<SendResult.Ok>(send("m2", chips[0].name, chips[0]))
        assertEquals(1, second.response.reply.cards.size)
        assertEquals(0, rig.fake.bodies.size)
        assertEquals("code", parseExtras(rig.chats.message("r:m2")!!.card_json).brain)
    }

    @Test
    fun aChipWithNoPendingActionAndNoLaptopSaysNeedsLaptop() = runBlocking<Unit> {
        rig.task("a", "Write report")
        rig.task("b", "Report to the bank")
        // the laptop-style "which one" chips carry no pending action: seed one by hand on a stored reply
        rig.chats.create("c1")
        val chip = dev.tim.assistant.model.ChoiceDto("task", "task:a", "Write report", "tok-1")
        rig.chats.finishReply("c1", "r:old", "Which one?") { it.copy(choices = listOf(chip)) }
        val r = assertIs<SendResult.Ok>(send("m1", "Write report", chip))
        assertEquals(ReplyTemplates.needsLaptop(), r.response.reply.text)
        assertEquals(0, rig.fake.bodies.size)
    }

    @Test
    fun theRunnerReturnsTheRungOnTheTurnResult() = runBlocking<Unit> {
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m1", "add task buy milk", CHAT_T0)
        val result = rig.runner.run("c1", "r:m1", "m1", "add task buy milk", null, null)
        val reply = assertIs<TurnResult.Reply>(result)
        assertEquals("code", reply.brain)
        assertNull(reply.pending)
        assertNotNull(reply.text)
    }
}
