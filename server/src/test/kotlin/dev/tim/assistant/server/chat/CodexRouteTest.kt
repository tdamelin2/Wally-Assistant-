package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.model.CODEX_ASKED_TEXT
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.ROUTE_CODEX
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.research.RESEARCH_JOB_KIND
import dev.tim.assistant.research.RESEARCH_OTHER
import dev.tim.assistant.research.RESEARCH_QUESTION_MAX
import dev.tim.assistant.research.researchId
import dev.tim.assistant.server.chat.ladder.ReplyTemplates
import dev.tim.assistant.server.codex.CODEX_MESSAGE_PREFIX
import dev.tim.assistant.server.codex.FakeCodexRunner
import dev.tim.assistant.server.codex.researchSyncRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Ask Codex route and the answer posted back into the chat (PLAN 20.7 steps 3 to 5 and 7). */
class CodexRouteTest {
    private val question = "What is the tallest mountain in Peru"

    private fun codexReq(id: String, text: String = question, choice: ChoiceDto? = null) =
        SendMessageRequest(id, text, CHAT_T0, "Today", choice, false, ROUTE_CODEX)

    private val fakeRunner = FakeCodexRunner().also { r -> r.handler = { _, q -> FakeCodexRunner.definition(q) } }

    private fun rigWith(runner: FakeCodexRunner = fakeRunner) = ChatRig(researchRunner = runner)

    private suspend fun ChatRig.ask(id: String, chat: String = "c1", text: String = question): SendResult.Ok {
        chats.create(chat)
        return assertIs<SendResult.Ok>(service.send(chat, codexReq(id, text)))
    }

    private suspend fun ChatRig.seedJob(id: String, kind: String, input: String, status: String, attempts: Long = 0, startedAt: Long? = null) =
        seed(toSyncRow(Ai_job(id, 1000, 0, 0, "SYNCED", kind, input, status, null, null, attempts, now, startedAt)))

    private fun ChatRig.jobs(): List<Ai_job> = sdb.db.ai_jobQueries.selectAll().executeAsList().filter { it.kind == RESEARCH_JOB_KIND }
    private fun ChatRig.approvals() = sdb.db.approvalQueries.selectAll().executeAsList()
    private fun ChatRig.posted(chat: String = "c1") = chats.messages(chat).filter { it.id.startsWith(CODEX_MESSAGE_PREFIX) }

    @Test
    fun noApproverGivesTheNotSetUpReplyAndQueuesNothing() = runBlocking<Unit> {
        val rig = ChatRig()
        val r = rig.ask("m1")
        assertEquals("Codex isn't set up on the home server.", r.response.reply.text)
        assertEquals("codex", parseExtras(rig.chats.message("r:m1")!!.card_json).brain)
        assertTrue(rig.jobs().isEmpty() && rig.approvals().isEmpty())
        rig.close()
    }

    @Test
    fun aQuestionMakesOneDoneApprovalAndOneJobWithTheChatId() = runBlocking<Unit> {
        val rig = rigWith()
        val r = rig.ask("m1")
        assertEquals(CODEX_ASKED_TEXT, r.response.reply.text)
        val extras = parseExtras(rig.chats.message("r:m1")!!.card_json)
        assertEquals("codex", extras.brain)
        val approval = rig.approvals().single()
        assertEquals("research_question", approval.tool)
        assertEquals("DONE", approval.status)
        assertEquals(listOf(approval.id), extras.cards)
        val job = rig.jobs().single()
        assertEquals("QUEUED", job.status)
        assertTrue("\"chatId\":\"c1\"" in job.inputJson, job.inputJson)
        rig.close()
    }

    @Test
    fun aSavedAnswerIsReturnedAtOnceWithNoApprovalAndNoJob() = runBlocking<Unit> {
        val rig = rigWith()
        rig.seed(researchSyncRow(RESEARCH_OTHER, question, FakeCodexRunner.definition(question), CHAT_T0))
        val r = rig.ask("m1")
        assertEquals("A definition.", r.response.reply.text)
        assertTrue(rig.jobs().isEmpty() && rig.approvals().isEmpty())
        rig.close()
    }

    @Test
    fun dreamTextAndOverLengthTextQueueNothing() = runBlocking<Unit> {
        val rig = rigWith()
        val dream = rig.ask("m1", text = "Why do I keep having the same dream about school")
        assertEquals("I can't send dream text to Codex.", dream.response.reply.text)
        val long = rig.ask("m2", text = "w".repeat(RESEARCH_QUESTION_MAX + 1))
        assertEquals("That's too long for Codex (limit 2,000 characters).", long.response.reply.text)
        assertTrue(rig.jobs().isEmpty() && rig.approvals().isEmpty())
        rig.close()
    }

    @Test
    fun afterTheTickTheChatHasOneNewAssistantMessageWithTheAnswer() = runBlocking<Unit> {
        val rig = rigWith()
        rig.ask("m1")
        rig.now += 5_000
        assertTrue(rig.codex!!.tick())
        val msg = rig.posted().single()
        val job = rig.jobs().single()
        assertEquals("codex-" + job.id, msg.id)
        assertEquals("assistant", msg.role)
        assertEquals("A definition.", msg.text)
        assertTrue(msg.created_at > rig.chats.message("r:m1")!!.created_at)
        // the same question again is now a saved answer: nothing new is queued
        rig.chats.create("c1")
        val again = assertIs<SendResult.Ok>(rig.service.send("c1", codexReq("m2")))
        assertEquals("A definition.", again.response.reply.text)
        assertEquals(1, rig.jobs().size)
        assertEquals(1, rig.approvals().size)
        rig.close()
    }

    @Test
    fun aJobThatFindsTheRowAlreadySavedPostsTheCachedText() = runBlocking<Unit> {
        val rig = rigWith()
        rig.ask("m1")
        rig.seed(researchSyncRow(RESEARCH_OTHER, question, FakeCodexRunner.definition(question).copy(answerText = "Saved earlier."), CHAT_T0 + 1))
        rig.codex!!.tick()
        assertEquals("Saved earlier.", rig.posted().single().text)
        assertTrue(fakeRunner.asks.isEmpty())
        rig.close()
    }

    @Test
    fun aJobThatFailsForGoodPostsItsErrorTextAfterTheThirdAttempt() = runBlocking<Unit> {
        val fake = FakeCodexRunner().also { it.handler = { _, _ -> error("the process crashed") } }
        val rig = rigWith(fake)
        rig.ask("m1")
        rig.codex!!.tick(); rig.codex.tick()
        assertTrue(rig.posted().isEmpty())
        rig.codex.tick()
        assertEquals("FAILED", rig.jobs().single().status)
        assertEquals("the process crashed", rig.posted().single().text)
        rig.close()
    }

    @Test
    fun theUsageLimitTextArrivesAsAMessage() = runBlocking<Unit> {
        val fake = FakeCodexRunner().also { it.handler = { _, _ -> error("You hit your usage limit") } }
        val rig = rigWith(fake)
        rig.ask("m1")
        repeat(3) { rig.codex!!.tick() }
        assertEquals(CODEX_LIMIT_TEXT, rig.posted().single().text)
        rig.close()
    }

    @Test
    fun aJobFailedByTheStaleResetPostsItsError() = runBlocking<Unit> {
        val rig = rigWith()
        rig.chats.create("c1")
        val input = """{"category":"OTHER","question":"q","researchId":"x","chatId":"c1"}"""
        rig.seedJob("research_question:appr:stale", RESEARCH_JOB_KIND, input, status = "RUNNING", attempts = 2, startedAt = CHAT_T0 - 3_600_000)
        rig.codex!!.tick()
        assertEquals("FAILED", rig.jobs().single().status)
        assertEquals("interrupted too many times", rig.posted().single().text)
        rig.close()
    }

    @Test
    fun aJobWhoseClaimWasLostPostsNothing() = runBlocking<Unit> {
        val fake = fakeRunner
        val rig = rigWith(fake)
        fake.handler = { _, q ->
            val job = rig.jobs().single()
            rig.writer.write(toSyncRow(job.copy(startedAt = (job.startedAt ?: 0L) + 1)))
            FakeCodexRunner.definition(q)
        }
        rig.ask("m1")
        rig.codex!!.tick()
        assertTrue(rig.posted().isEmpty())
        rig.close()
    }

    @Test
    fun researchCardOnlyEverCardsResearchQuestion() = runBlocking<Unit> {
        val rig = rigWith()
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m1", "q", CHAT_T0)
        rig.chats.finishReply("c1", "r:m1", "") { it }
        val id = rig.executor("c1", "r:m1").researchCard("  What is a tide?  ")!!
        val row = rig.approvals().single()
        assertEquals(id, row.id)
        assertEquals("research_question", row.tool)
        assertEquals("""{"text":"What is a tide?"}""", row.argsJson)
        rig.close()
    }

    @Test
    fun aDeletedOrArchivedChatGetsNoMessageAndTheResearchRowIsStillSaved() = runBlocking<Unit> {
        val rig = rigWith()
        rig.ask("m1")
        rig.ask("m2", chat = "c2", text = "How tall is the Eiffel Tower")
        rig.chats.delete("c1")
        rig.writer.transaction { tx -> tx.db.chatQueries.archiveChat(CHAT_T0, "c2") }
        repeat(2) { rig.codex!!.tick() }
        assertTrue(rig.posted("c1").isEmpty() && rig.posted("c2").isEmpty())
        assertEquals(2, rig.sdb.db.researchQueries.selectAll().executeAsList().size)
        rig.close()
    }

    @Test
    fun theSameQuestionFromTwoChatsIsOneJobAndTheSecondReplyIsHonest() = runBlocking<Unit> {
        val rig = rigWith()
        rig.ask("m1")
        val second = rig.ask("m2", chat = "c2")
        assertEquals(ReplyTemplates.codexAlreadyAsked(), second.response.reply.text)
        assertEquals(1, rig.jobs().size)
        assertEquals(1, rig.approvals().size)
        rig.close()
    }

    @Test
    fun twoApprovalsForTheSameQuestionRaceToOneJob() = runBlocking<Unit> {
        val rig = rigWith()
        rig.chats.create("c1"); rig.chats.create("c2")
        for ((chat, n) in listOf("c1" to "1", "c2" to "2")) {
            rig.chats.addUserMessage(chat, "m$n", "q", CHAT_T0)
            rig.chats.finishReply(chat, "r:m$n", "") { it }
        }
        val a1 = rig.executor("c1", "r:m1").researchCard(question)!!
        val a2 = rig.executor("c2", "r:m2").researchCard(question)!!
        val args = buildJsonObject { put("text", question) }
        val outcomes = listOf("c1" to a1, "c2" to a2).map { (c, a) -> async { rig.codex!!.submit(a, args, c) } }.awaitAll()
        assertEquals(1, rig.jobs().size)
        assertEquals(setOf(ResearchKind.QUEUED, ResearchKind.WORKING), outcomes.map { it.kind }.toSet())
        assertTrue(outcomes.all { it.result.action == ApprovalAction.DONE })
        rig.close()
    }

    @Test
    fun reconciliationPostsALostAnswerOnceAndNeverRerunsCodex() = runBlocking<Unit> {
        val rig = rigWith()
        rig.ask("m1")
        rig.codex!!.tick()
        val posted = rig.posted().single()
        rig.chats.deleteMessage(posted.id) // the answer was lost between saving and posting
        assertTrue(rig.posted().isEmpty())
        rig.codex.tick()
        assertEquals("A definition.", rig.posted().single().text)
        rig.codex.tick()
        assertEquals(1, rig.posted().size)
        assertEquals(1, fakeRunner.asks.size)
        rig.close()
    }

    @Test
    fun reconciliationPostsALostErrorForAFailedJobAndIgnoresOldJobs() = runBlocking<Unit> {
        val rig = rigWith()
        rig.chats.create("c1")
        val input = """{"category":"OTHER","question":"q","researchId":"x","chatId":"c1"}"""
        rig.seedJob("research_question:appr:lost", RESEARCH_JOB_KIND, input, status = "FAILED")
        rig.sdb.db.ai_jobQueries.selectById("research_question:appr:lost").executeAsOne().let {
            rig.writer.write(toSyncRow(it.copy(error = "it broke")))
        }
        rig.codex!!.tick()
        assertEquals("it broke", rig.posted().single().text)
        // a job that ended more than a day ago is left alone
        rig.seedJob("research_question:appr:old", RESEARCH_JOB_KIND, input, status = "FAILED")
        rig.now += 2 * 86_400_000L
        rig.codex.tick()
        assertEquals(1, rig.posted().size)
        rig.close()
    }

    @Test
    fun aCodexRouteTextNeverCompletesAChip() = runBlocking<Unit> {
        val rig = rigWith()
        rig.chats.create("c1")
        val r = rig.service.send("c1", codexReq("m1", choice = ChoiceDto("task", "task:1", "x", "t")))
        assertEquals(SendResult.BadInput("invalid: route"), r)
        assertNull(rig.chats.message("m1"))
        rig.close()
    }
}
