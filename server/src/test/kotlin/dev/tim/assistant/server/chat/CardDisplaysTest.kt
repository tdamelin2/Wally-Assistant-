package dev.tim.assistant.server.chat

import dev.tim.assistant.model.CardDisplayDto
import dev.tim.assistant.model.CardDto
import dev.tim.assistant.server.chat.ladder.exam
import dev.tim.assistant.server.chat.ladder.task
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CardDisplaysTest {
    private val rig = ChatRig()

    @AfterTest
    fun close() = rig.close()

    private suspend fun card(tool: String, vararg args: Pair<String, String>, replyId: String = "r:m1"): CardDto {
        rig.chats.create("c1")
        val result = rig.executor("c1", replyId).handle(tool, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }), setOf(tool))
        assertEquals(1, result.cardApprovalIds.size, result.text)
        val dto = rig.chats.toDto(rig.chats.message(replyId)!!)
        return dto.cards.single()
    }

    @Test
    fun dateChangeShowsFromAndToWithoutIdsOrToolNames() = runBlocking<Unit> {
        rig.exam("a", "Algebra exam", "2026-12-01")
        val c = card("update_exam", "id" to "exam:a", "localDate" to "2026-12-03")
        assertEquals(CardDisplayDto("Move exam", "Algebra exam", "Tue, Dec 1", "Thu, Dec 3", "Yes, move it"), c.display)
        val all = listOf(c.display!!.title, c.display!!.itemName, c.display!!.before, c.display!!.after, c.display!!.yesLabel)
        for (s in all) assertEquals(false, s.contains("exam:") || s.contains("update_"), s)
    }

    @Test
    fun timeIsAddedWhenTheExamHasOrGetsOne() = runBlocking<Unit> {
        rig.exam("a", "Algebra exam", "2026-12-01", "09:00")
        val c = card("update_exam", "id" to "exam:a", "localDate" to "2026-12-03", "time" to "14:30")
        assertEquals("Tue, Dec 1 09:00", c.display?.before)
        assertEquals("Thu, Dec 3 14:30", c.display?.after)
    }

    @Test
    fun timeOnlyChangeKeepsTheSameDate() = runBlocking<Unit> {
        rig.exam("a", "Algebra exam", "2026-12-01", "09:00")
        val c = card("update_exam", "id" to "exam:a", "time" to "11:00")
        assertEquals("Tue, Dec 1 09:00", c.display?.before)
        assertEquals("Tue, Dec 1 11:00", c.display?.after)
    }

    @Test
    fun otherCardsAndDeletedExamsGetNoDisplay() = runBlocking<Unit> {
        rig.task("t", "Call the dentist")
        val other = card("update_task", "id" to "task:t", "done" to "true")
        assertNull(other.display)
        rig.exam("a", "Algebra exam", "2026-12-01")
        val move = card("update_exam", "id" to "exam:a", "localDate" to "2026-12-03", replyId = "r:m2")
        assertNotNull(move.display)
        // the live exam is gone: the generic card is shown
        val approval = rig.sdb.db.approvalQueries.selectById(move.approvalId).executeAsOne()
        rig.sdb.db.examQueries.selectById("exam:a").executeAsOne().let { }
        assertNull(CardDisplays.forExamCard(rig.sdb.db, approval.copy(argsJson = """{"id":"exam:zzz","localDate":"2026-12-03"}""")))
        assertNull(CardDisplays.forExamCard(rig.sdb.db, approval.copy(argsJson = """{"id":"exam:a"}""")))
        assertNull(CardDisplays.forExamCard(rig.sdb.db, approval.copy(argsJson = "not json")))
        assertNull(CardDisplays.forExamCard(rig.sdb.db, approval.copy(argsJson = """{"id":"exam:a","localDate":"nope"}""")))
    }

    @Test
    fun decidedCardsAreGeneric() = runBlocking<Unit> {
        rig.exam("a", "Algebra exam", "2026-12-01")
        val c = card("update_exam", "id" to "exam:a", "localDate" to "2026-12-03")
        // an approval older than seven days is shown as failed and loses its display
        rig.now += 8L * 24 * 60 * 60 * 1000
        val later = rig.chats.toDto(rig.chats.message("r:m1")!!).cards.single()
        assertEquals("FAILED", later.status)
        assertNull(later.display)
        assertEquals("PENDING", c.status)
    }
}
