package dev.tim.assistant.research

import dev.tim.assistant.ai.JobRig
import dev.tim.assistant.ai.seedResearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A runner that counts calls and answers from [script]. */
class CountingRunner(var script: suspend (String, String) -> ResearchRow = { c, q -> ResearchRow(c, q, "168 kcal", """{"kcal":168}""", listOf("https://example.org/a")) }) : ResearchRunner {
    val calls = mutableListOf<Pair<String, String>>()
    override suspend fun ask(category: String, question: String): ResearchRow {
        calls += category to question
        return script(category, question)
    }
}

/** The research rules that stay in shared code (the server runs the jobs and the approvals). */
class ResearchTest {
    private val rig = JobRig()
    private val repo = rig.repo
    private val service = ResearchService(repo) { rig.clock }

    @Test
    fun itemRules() {
        assertTrue(isValidItem("a".repeat(60)))
        assertFalse(isValidItem("a".repeat(61)))
        assertFalse(isValidItem(""))
        assertFalse(isValidItem("   "))
        assertFalse(isValidItem("me@example.com"))
        assertFalse(isValidItem("call 12345"))
        assertFalse(isValidItem("1234 5678 123456"))
        assertTrue(isValidItem("route 1234 and 5678"))
        assertTrue(isValidItem("חומוס"))
        assertTrue(isValidItem("Mom's co-op bread."))
        assertFalse(isValidItem("a/b"))
        assertFalse(isValidItem("what? now"))
        assertFalse(isValidItem("x\ny"))
    }

    @Test
    fun templatesAreExact() {
        assertEquals(
            "Nutrition facts per 100 g of hummus. Return kcal, proteinG, carbsG, fatG and source URLs.",
            templateQuestion(RESEARCH_NUTRITION, "hummus"),
        )
        assertEquals("Define entropy in one short paragraph with source URLs.", templateQuestion(RESEARCH_DEFINITION, "entropy"))
        assertEquals(
            "What kind of app is Notion? One sentence and a category word, with source URLs.",
            templateQuestion(RESEARCH_APP_CATEGORY, " Notion "),
        )
    }

    @Test
    fun sameQuestionIsOneId() = runBlocking<Unit> {
        assertEquals("what is  A  Dog?  ".let { questionKey(it) }, "what is a dog?")
        val a = researchId(RESEARCH_OTHER, "What is a Dog?")
        val b = researchId(RESEARCH_OTHER, "  what is   a dog? ")
        assertEquals(a, b)
        assertEquals(a, rig.db.seedResearch(RESEARCH_OTHER, "What is a Dog?", "an animal", "{}", "[]", rig.clock, null))
        assertEquals(a, rig.db.seedResearch(RESEARCH_OTHER, "  what is   a dog? ", "an animal", "{}", "[]", rig.clock, null))
        assertEquals(1, repo.researchList().size)
        assertTrue(a.matches(Regex("research:OTHER:[0-9a-f]{64}")))
        assertTrue(researchId(RESEARCH_NUTRITION, "x") != researchId(RESEARCH_DEFINITION, "x"))
    }

    @Test
    fun expiry() {
        assertNull(expiresAtFor(RESEARCH_NUTRITION, 1000))
        assertNull(expiresAtFor(RESEARCH_DEFINITION, 1000))
        assertEquals(1000 + 180L * 86_400_000, expiresAtFor(RESEARCH_APP_CATEGORY, 1000))
        assertEquals(1000 + 180L * 86_400_000, expiresAtFor(RESEARCH_OTHER, 1000))
    }

    @Test
    fun aCachedAnswerMeansNoJob() = runBlocking<Unit> {
        rig.db.seedResearch(RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, "hummus"), "168 kcal", "{}", "[]", rig.clock, null)
        assertTrue(service.request(RESEARCH_NUTRITION, "hummus") is ResearchRequest.Cached)
        assertTrue(repo.jobs().isEmpty())
    }

    @Test
    fun aMissingAnswerQueuesOneJobAndIsReusedWhileActive() = runBlocking<Unit> {
        val first = service.request(RESEARCH_NUTRITION, "hummus") as ResearchRequest.Queued
        val second = service.request(RESEARCH_NUTRITION, " hummus ") as ResearchRequest.Queued
        assertEquals(first.jobId, second.jobId)
        assertEquals(1, repo.jobs().size)
    }

    @Test
    fun aRowThatExpiresExactlyNowIsAskedAgain() = runBlocking<Unit> {
        val question = templateQuestion(RESEARCH_APP_CATEGORY, "Notion")
        rig.db.seedResearch(RESEARCH_APP_CATEGORY, question, "old", "{}", "[]", rig.clock - 1000, rig.clock)
        assertTrue(service.request(RESEARCH_APP_CATEGORY, "Notion") is ResearchRequest.Queued)
    }

    @Test
    fun aFreeQuestionNeedsApproval() = runBlocking<Unit> {
        val question = "Who won the 1998 world cup?"
        assertEquals(ResearchRequest.NeedsApproval(question), service.request(RESEARCH_OTHER, question))
    }

    @Test
    fun anApprovedQuestionIsQueuedUnderTheApprovalIdAndCachedAfterwards() = runBlocking<Unit> {
        val question = "Who won the 1998 world cup?"
        val queued = service.queueApproved(question, "appr:1") as ResearchRequest.Queued
        assertEquals("research_question:appr:1", queued.jobId)
        rig.db.seedResearch(RESEARCH_OTHER, question, "France", "{}", "[]", rig.clock, rig.clock + 180L * 86_400_000)
        assertTrue(service.queueApproved(question, "appr:1") is ResearchRequest.Cached)
    }

    @Test
    fun everyLimitSubstringIsDetectedInAnyCase() {
        for (text in listOf("Usage Limit reached", "RATE LIMIT hit", "Quota exceeded", "429 Too Many Requests")) {
            assertTrue(isCodexLimitText(text), text)
        }
        assertFalse(isCodexLimitText("network error"))
        assertFalse(isCodexLimitText(""))
    }

    @Test
    fun theLimitNoticeShowsForTwentyFourHoursOnly() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        assertNull(codexLimitNotice(null, now))
        assertEquals(CODEX_LIMIT_NOTICE, codexLimitNotice(now - 1, now))
        assertEquals(CODEX_LIMIT_NOTICE, codexLimitNotice(now - day, now))
        assertNull(codexLimitNotice(now - day - 1, now))
        assertEquals(
            "Codex is out of usage. Research and Look up will work again when your ChatGPT allowance resets.",
            CODEX_LIMIT_NOTICE,
        )
    }

    @Test
    fun badInputToTheServiceIsRefused() = runBlocking<Unit> {
        for (bad in listOf("", "a@b.c", "12345")) {
            val ex = runCatching { service.request(RESEARCH_NUTRITION, bad) }.exceptionOrNull()
            assertTrue(ex is IllegalArgumentException, "item '$bad'")
        }
        assertTrue(runCatching { service.request("WEATHER", "rain") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { service.request(RESEARCH_OTHER, "  ") }.exceptionOrNull() is IllegalArgumentException)
    }
}
