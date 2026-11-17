package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.SendResult
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Logging for another day (PLAN 20.5). Fixed clocks: Monday 2026-11-16 and Tuesday 2027-01-05, noon Jerusalem time. */
class OtherDayLogTest {
    private val rig = ChatRig().also { it.now = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli() }
    private val today = LocalDate.parse("2026-11-16")
    private var n = 0

    @AfterTest
    fun close() = rig.close()

    private fun rows(table: String) = runBlocking { rig.pull().filter { it.table == table && !it.deleted } }

    private suspend fun send(text: String): SendResult.Ok {
        val id = "m${++n}"
        rig.chats.create("c$n")
        return assertIs<SendResult.Ok>(rig.service.send("c$n", SendMessageRequest(id, text, rig.now, null, null)))
    }

    private suspend fun cardFor(text: String): String? = send(text).response.reply.cards.singleOrNull()?.approvalId

    private fun match(text: String, day: LocalDate = today, typedToday: Boolean = true) = IntentRouter.route(text, day, typedToday)

    private fun assertOnCard(text: String, table: String, date: String) = runBlocking<Unit> {
        val id = assertNotNull(cardFor(text), text)
        assertTrue(rows(table).isEmpty(), "nothing is written before the Yes: $text")
        assertEquals(ApprovalAction.DONE, rig.approvals.approve(id).action)
        assertEquals(date, rig.str(rows(table).single(), "localDate"), text)
        assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve(id).action)
        assertEquals(1, rows(table).size, "approving twice gives one row: $text")
        rows(table).forEach { r -> rig.seed(r.copy(deleted = true, updatedAt = r.updatedAt + 1)) }
    }

    private fun assertNoCard(text: String) = runBlocking<Unit> {
        val reply = send(text).response.reply
        assertTrue(reply.cards.isEmpty() && reply.actions.isEmpty(), text)
        assertTrue(rows("spending").isEmpty() && rows("meal").isEmpty(), text)
    }

    @Test
    fun aSpendingForAPastDayAtTheEndOrTheStartIsACard() {
        for (text in listOf(
            "spent 35 on lunch yesterday", "yesterday spent 35 on lunch", "spent 35 on lunch on yesterday",
        )) assertOnCard(text, "spending", "2026-11-15")
        for (text in listOf("spent 35 on lunch last friday", "last friday I spent 35 on lunch")) assertOnCard(text, "spending", "2026-11-13")
        for (text in listOf("spent 35 on lunch 13/11", "13/11 spent 35 on lunch", "spent 35 on lunch on 13/11")) assertOnCard(text, "spending", "2026-11-13")
    }

    @Test
    fun aMealWithATimeForAPastDayIsACard() {
        for (text in listOf("log lunch: salad yesterday at 13:00", "yesterday at 13:00 I ate salad for lunch")) assertOnCard(text, "meal", "2026-11-15")
        for (text in listOf("log lunch: salad last friday at 13:00", "last friday at 13:00 I ate salad for lunch")) assertOnCard(text, "meal", "2026-11-13")
        for (text in listOf("log lunch: salad 13/11 at 13:00", "13/11 log lunch: salad at 13:00")) assertOnCard(text, "meal", "2026-11-13")
    }

    @Test
    fun aHadMealWithATypeAndATimeForAPastDayIsACard() {
        for (text in listOf("had a bagel as a snack yesterday at 16:30", "yesterday at 16:30 I had a bagel as a snack")) assertOnCard(text, "meal", "2026-11-15")
        for (text in listOf("had pasta for lunch last friday at 13:00", "ate pasta for lunch 13/11 at 13:00")) assertOnCard(text, "meal", "2026-11-13")
        assertNoCard("I had a meeting for lunch yesterday at 13:00")
    }

    @Test
    fun theCardNamesTheFullDate() = runBlocking<Unit> {
        val id = assertNotNull(cardFor("spent 35 on lunch yesterday"))
        val summary = rig.str(rows("approval").single { it.id == id }, "summary")
        assertEquals("Add spending 35 ₪, food (lunch) for Sunday 15 November", summary)
        val meal = assertNotNull(cardFor("log lunch: salad yesterday at 13:00"))
        assertEquals("Add meal: salad for Sunday 15 November at 13:00", rig.str(rows("approval").single { it.id == meal }, "summary"))
    }

    @Test
    fun theYearIsShownWhenTheDayIsInAnotherYear() = runBlocking<Unit> {
        rig.now = Instant.parse("2027-01-05T10:00:00Z").toEpochMilli()
        val id = assertNotNull(cardFor("spent 35 on lunch 30/12"))
        val summary = rig.str(rows("approval").single { it.id == id }, "summary")
        assertTrue(summary!!.endsWith("Wednesday 30 December 2026"), summary)
    }

    @Test
    fun nothingMatchesForTheseWordings() = runBlocking<Unit> {
        for (text in listOf(
            "log lunch: salad yesterday",             // a meal without a time
            "spent 35 on lunch tomorrow",              // a future day
            "spent 35 on lunch last week",             // not a single day
            "spent 40 on friday night dinner",         // a day word in the middle
            "spent 35 on a gift for friday",           // a bare weekday
            "paid 120 for the concert on saturday",
            "paid 200 for the concert on 20/11",       // this date is not yet reached this year
            "spent 35 on lunch 17/11",
            "spent 35 on lunch 3 days ago",
            "spent 35 on lunch on yesterday dreamed",
        )) assertNoCard(text)
        assertEquals(RouteResult.NoMatch, match("note to self: call the bank on monday"))
        assertEquals(RouteResult.NoMatch, match("check-in: mood 4 energy 3 sleep 4 stress 2 yesterday"))
        assertEquals(RouteResult.NoMatch, match("journal: it was a good day yesterday"))
        val dream = match("last night I dreamed that I spent 35 on lunch yesterday")
        assertTrue(dream !is RouteResult.Match || (dream.intent == IntentName.ADD_DREAM && "date" !in dream.slots))
    }

    @Test
    fun theSixtyDayLimitIsTheLastDayThatMatches() = runBlocking<Unit> {
        val sixty = today.minusDays(60)
        val over = today.minusDays(61)
        val ok = match("spent 35 on lunch on ${sixty}")
        assertIs<RouteResult.Match>(ok)
        assertEquals(sixty.toString(), ok.slots["date"])
        assertTrue(match("spent 35 on lunch on $over") !is RouteResult.Match)
    }

    @Test
    fun typedOnAnEarlierDayHandlesYesterdayAsToday() = runBlocking<Unit> {
        assertTrue(match("spent 35 on lunch yesterday", typedToday = false) !is RouteResult.Match)
        val ladder = Ladder(Targets(rig.repo) { rig.now }, AskAnswers(rig.repo) { rig.now }, rig.read, rig.chats, null) { rig.now }
        rig.chats.create("cx")
        assertNull(ladder.tryCode("cx", "mx", "spent 35 on lunch yesterday", null, rig.executor("cx", "r:mx"), typedToday = false))
        assertTrue(rows("approval").isEmpty() && rows("spending").isEmpty())
    }

    @Test
    fun proposeAddRefusesBadDays() = runBlocking<Unit> {
        val executor = rig.executor()
        fun args(date: String?, time: String? = null) = buildJsonObject {
            put("amount", "35"); put("category", "Food")
            if (date != null) put("date", date)
            if (time != null) put("time", time)
        }
        for (date in listOf("2026-11-17", "2026-11-16", today.minusDays(61).toString(), null)) {
            val r = executor.proposeAdd("add_spending", args(date))
            assertTrue(r.text.startsWith("Error"), "$date: ${r.text}")
            assertTrue(r.cardApprovalIds.isEmpty())
        }
        assertEquals(1, executor.proposeAdd("add_spending", args(today.minusDays(60).toString())).cardApprovalIds.size)
        val meal = buildJsonObject { put("type", "MEAL"); put("text", "salad"); put("date", "2026-11-15") }
        assertTrue(executor.proposeAdd("add_meal", meal).text.startsWith("Error"))
        assertTrue(executor.proposeAdd("create_task", buildJsonObject { put("title", "x") }).text.startsWith("Error"))
        assertEquals(1, rows("approval").size)
    }
}
