package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.ai.taskRow
import dev.tim.assistant.server.checkinRow
import dev.tim.assistant.server.obj
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServerToolExecutorTest {
    private val rig = ChatRig(withCalendar = true)

    @AfterTest
    fun close() = rig.close()

    private fun pulled(table: String) = runBlocking { rig.pull().filter { it.table == table } }

    @Test
    fun anAutoCreateTaskIsPulledWithAnUndoHandleOnTheReply() = runBlocking<Unit> {
        val executor = rig.executor()
        val result = rig.call(executor, "create_task", """{"title":"Buy milk","dueDate":"2026-11-12"}""")
        assertTrue("Saved" in result.text)
        val action = result.actions.single()
        val task = pulled("task").single()
        assertEquals("Buy milk", rig.str(task, "title"))
        assertEquals("2026-11-12", rig.str(task, "dueDate"))
        assertEquals(CHAT_T0 + UNDO_WINDOW_MS, action.expiresAt)
        // the Undo record is on the reply row, committed with the task
        val stored = parseExtras(rig.chats.message("r:m1")!!.card_json).actions.single()
        assertEquals(task.id, stored.rowId)
        assertEquals(task.updatedAt, stored.createdUpdatedAt)
        assertEquals(STATE_RUNNING, rig.chats.message("r:m1")!!.request_state)
        assertTrue(executor.wrote)
    }

    @Test
    fun undoWritesATombstoneThatWinsAndOnlyWhileUnchangedAndUnexpired() = runBlocking<Unit> {
        rig.chats.create("c1")
        val executor = rig.executor("c1", "r:m1")
        val action = rig.call(executor, "create_task", """{"title":"Buy milk"}""").actions.single()
        rig.now += 5_000
        val undone = rig.undo.undo("c1", action.actionId)
        assertTrue(undone.ok)
        val task = pulled("task").single()
        assertTrue(task.deleted)
        assertTrue(task.updatedAt > CHAT_T0)
        assertTrue(rig.undo.undo("c1", action.actionId).ok) // already undone stays ok

        // changed since creation: left alone
        val second = rig.call(rig.executor("c1", "r:m2"), "create_task", """{"title":"Second"}""").actions.single()
        val row = pulled("task").single { rig.str(it, "title") == "Second" }
        rig.seed(row.copy(updatedAt = row.updatedAt + 10, data = obj("title" to "Edited", "dueDate" to null, "done" to false, "doneAt" to null, "goalId" to null)))
        val changed = rig.undo.undo("c1", second.actionId)
        assertFalse(changed.ok)
        assertFalse(pulled("task").single { it.id == row.id }.deleted)

        // too late
        val third = rig.call(rig.executor("c1", "r:m3"), "create_task", """{"title":"Third"}""").actions.single()
        rig.now += UNDO_WINDOW_MS + 1
        assertFalse(rig.undo.undo("c1", third.actionId).ok)
        assertNull(rig.undo.undo("c1", "nope").takeIf { it.found })
    }

    @Test
    fun autoWritesCoverMealSpendingJournalAndCheckin() = runBlocking<Unit> {
        val executor = rig.executor()
        rig.call(executor, "add_meal", """{"type":"MEAL","text":"pasta"}""")
        rig.call(executor, "add_spending", """{"amount":"12.50","category":"Food"}""")
        rig.call(executor, "add_journal", """{"text":"good day"}""")
        rig.call(executor, "add_checkin", """{"mood":4,"energy":3,"sleepQuality":3,"stress":2,"date":"2026-11-11"}""")
        assertEquals("pasta", rig.str(pulled("meal").single(), "text"))
        assertEquals("1250", rig.str(pulled("spending").single(), "amountAgorot"))
        assertEquals("good day", rig.str(pulled("journal").single(), "text"))
        assertEquals("checkin:2026-11-11", pulled("checkin").single().id)
        assertEquals(4, executor.let { parseExtras(rig.chats.message("r:m1")!!.card_json).actions.size })
    }

    @Test
    fun anExistingCheckinDayBecomesAnUpdateCardNeverAnOverwrite() = runBlocking<Unit> {
        rig.seed(checkinRow("2026-11-11", 1000, mood = 2))
        val executor = rig.executor()
        val result = rig.call(executor, "add_checkin", """{"mood":5,"energy":5,"sleepQuality":5,"stress":1,"date":"2026-11-11"}""")
        assertTrue(result.actions.isEmpty())
        assertEquals(1, result.cardApprovalIds.size)
        assertEquals("2", rig.str(pulled("checkin").single(), "mood")) // untouched
        val approval = pulled("approval").single()
        assertEquals("update_checkin", rig.str(approval, "tool"))
        assertEquals("PENDING", rig.str(approval, "status"))
    }

    @Test
    fun aCardToolWritesAPendingApprovalAndIdsAreValidatedAgainstTheServerDb() = runBlocking<Unit> {
        rig.seed(taskRow("task:t1", "Old", null))
        val executor = rig.executor()
        val bad = rig.call(executor, "update_task", """{"id":"task:missing","title":"X"}""")
        assertTrue(bad.text.startsWith("Error"))
        assertTrue(pulled("approval").isEmpty())
        val goalBad = rig.call(executor, "create_task", """{"title":"X","goalId":"goal:none"}""")
        assertTrue("no goal" in goalBad.text.lowercase() || "no goal" in goalBad.text)
        assertTrue(pulled("task").size == 1)

        val ok = rig.call(executor, "update_task", """{"id":"task:t1","done":true}""")
        assertEquals(1, ok.cardApprovalIds.size)
        val approval = pulled("approval").single()
        assertEquals("PENDING", rig.str(approval, "status"))
        assertEquals(listOf(approval.id), parseExtras(rig.chats.message("r:m1")!!.card_json).cards)
        assertEquals("Old", rig.str(pulled("task").single(), "title")) // nothing applied yet
    }

    @Test
    fun yesAppliesTheTargetAndTheApprovalRowsTogetherAndASecondYesWritesNothing() = runBlocking<Unit> {
        rig.seed(taskRow("task:t1", "Old", null))
        val id = rig.call(rig.executor(), "update_task", """{"id":"task:t1","title":"New"}""").cardApprovalIds.single()
        val result = rig.approvals.approve(id)
        assertEquals(ApprovalAction.DONE, result.action)
        assertEquals("New", rig.str(pulled("task").single(), "title"))
        val approval = pulled("approval").single()
        assertEquals("DONE", rig.str(approval, "status"))
        val seq = rig.sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()
        assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve(id).action)
        assertEquals(seq, rig.sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull())
    }

    @Test
    fun rejectingWritesNoOutput() = runBlocking<Unit> {
        val id = rig.call(rig.executor(), "create_goal", """{"title":"Run a marathon"}""").cardApprovalIds.single()
        assertTrue(rig.approvals.reject(id))
        assertEquals("REJECTED", rig.str(pulled("approval").single(), "status"))
        assertTrue(pulled("goal").isEmpty())
    }

    @Test
    fun createEventGoesThroughTheCalendarServiceOnceKeyedByTheApprovalId() = runBlocking<Unit> {
        val id = rig.call(
            rig.executor(), "create_event",
            """{"title":"Dentist","start":"2026-11-12T09:00","durationMinutes":30}""",
        ).cardApprovalIds.single()
        val result = rig.approvals.approve(id)
        assertEquals(ApprovalAction.DONE, result.action)
        assertEquals(1, rig.calendar.posts.size)
        val body = rig.calendar.posts.single()
        assertTrue("Dentist" in body)
        assertEquals("ev-1", rig.sdb.db.kvQueries.get("calcreate:$id").executeAsOneOrNull())
        assertNull(rig.sdb.db.kvQueries.get("calattempt:$id").executeAsOneOrNull())
        assertEquals("DONE", rig.str(pulled("approval").single(), "status"))
        // a second yes does not call the calendar again
        assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve(id).action)
        assertEquals(1, rig.calendar.posts.size)
    }

    @Test
    fun aRefusedCalendarCreateEndsTheApprovalFailedAndKeepsNoFence() = runBlocking<Unit> {
        rig.calendar.status = 400
        val id = rig.call(rig.executor(), "create_event", """{"title":"X","start":"2026-11-12T09:00","durationMinutes":30}""").cardApprovalIds.single()
        assertEquals(ApprovalAction.FAILED, rig.approvals.approve(id).action)
        assertEquals("FAILED", rig.str(pulled("approval").single(), "status"))
        assertNull(rig.sdb.db.kvQueries.get("calattempt:$id").executeAsOneOrNull())
    }

    @Test
    fun anApprovedMailActionWritesApprovedAndWakesGmailAfterTheCommit() = runBlocking<Unit> {
        rig.seedMail("g1", "Invoice October")
        var woke = 0
        rig.store.gmailWake = { woke++ }
        val id = rig.call(rig.executor(), "suggest_mail_action", """{"gmailId":"g1","action":"ARCHIVE","reason":"old newsletter"}""").cardApprovalIds.single()
        assertEquals(0, woke)
        assertEquals(ApprovalAction.DONE, rig.approvals.approve(id).action)
        val action = pulled("mail_action").single()
        assertEquals("APPROVED", rig.str(action, "status"))
        assertEquals("g1", rig.str(action, "gmailId"))
        assertEquals(1, woke)
    }

    @Test
    fun aLabelActionWithoutGmailLabelsIsNotDecidedAndStaysPending() = runBlocking<Unit> {
        rig.seedMail("g1", "Invoice October")
        val id = rig.call(rig.executor(), "suggest_mail_action", """{"gmailId":"g1","action":"LABEL","labelId":"L1","reason":"bills"}""").cardApprovalIds.single()
        assertEquals(ApprovalAction.SERVER_UNREACHABLE, rig.approvals.approve(id).action)
        assertEquals("PENDING", rig.str(pulled("approval").single(), "status"))
        assertTrue(pulled("mail_action").isEmpty())
    }

    @Test
    fun aMailNameIsResolvedToItsGmailId() = runBlocking<Unit> {
        rig.seedMail("g1", "Invoice October")
        val result = rig.call(rig.executor(), "suggest_mail_action", """{"gmailId":"Invoice","action":"ARCHIVE","reason":"done"}""")
        assertEquals(1, result.cardApprovalIds.size)
        val args = rig.str(pulled("approval").single(), "argsJson")!!
        assertTrue("\"gmailId\":\"g1\"" in args)
        assertTrue("Invoice October" in rig.str(pulled("approval").single(), "summary")!!)
    }

    @Test
    fun findReturnsNoChipsForOneMatchAndChipsForSeveral() = runBlocking<Unit> {
        rig.seedCourse("course:1", "Linear Algebra")
        rig.seedCourse("course:2", "Calculus One")
        rig.seedCourse("course:3", "Calculus Two")
        val one = rig.call(rig.executor(), "find", """{"kind":"course","query":"algebra"}""")
        assertTrue(one.choices.isEmpty())
        assertTrue("Linear Algebra" in one.text)
        val many = rig.call(rig.executor(), "find", """{"kind":"course","query":"calculus"}""")
        assertEquals(2, many.choices.size)
        assertTrue(many.choices.all { it.token.isEmpty() }) // the chat service stamps tokens when it stores the reply
    }

    @Test
    fun queueJobTakesACourseNameAndAskingWithSeveralMatchesReturnsChips() = runBlocking<Unit> {
        rig.seedCourse("course:1", "Linear Algebra")
        rig.seedCourse("course:2", "Calculus One")
        rig.seedCourse("course:3", "Calculus Two")
        val ok = rig.call(rig.executor(), "queue_job", """{"kind":"STUDY_GUIDE","input":{"courseId":"Linear Algebra"}}""")
        assertEquals(1, ok.cardApprovalIds.size)
        val approval = pulled("approval").single()
        assertTrue("course:1" in rig.str(approval, "argsJson")!!)
        assertFalse("course:1" in rig.str(approval, "summary")!!)
        assertTrue("Linear Algebra" in rig.str(approval, "summary")!!)

        val many = rig.call(rig.executor(), "queue_job", """{"kind":"QUIZ","input":{"courseId":"Calculus"}}""")
        assertTrue(many.cardApprovalIds.isEmpty())
        assertEquals(2, many.choices.size)
        assertEquals(1, pulled("approval").size)
        val none = rig.call(rig.executor(), "queue_job", """{"kind":"QUIZ","input":{"courseId":"Physics"}}""")
        assertTrue(none.text.startsWith("Error"))
    }

    @Test
    fun aTemplateResearchQueuesAJobAndACachedAnswerIsReturnedWithoutOne() = runBlocking<Unit> {
        val first = rig.call(rig.executor(), "research", """{"category":"NUTRITION","item":"banana"}""")
        assertNotNull(first.researchJobId)
        val job = pulled("ai_job").single()
        assertEquals("RESEARCH", rig.str(job, "kind"))
        assertEquals("QUEUED", rig.str(job, "status"))
        // the same question again does not queue a second job
        rig.call(rig.executor(), "research", """{"category":"NUTRITION","item":"banana"}""")
        assertEquals(1, pulled("ai_job").size)
        assertTrue(pulled("approval").isEmpty())
    }

    @Test
    fun aFreeQuestionCreatesAnApprovalCardWithTheExactQuestion() = runBlocking<Unit> {
        val result = rig.call(rig.executor(), "research_question", """{"text":"Who won the 2022 world cup?"}""")
        assertEquals(1, result.cardApprovalIds.size)
        val approval = pulled("approval").single()
        assertEquals("research_question", rig.str(approval, "tool"))
        assertTrue("Who won the 2022 world cup?" in rig.str(approval, "summary")!!)
        assertTrue(pulled("ai_job").isEmpty())
        // with nothing set up for research, yes ends FAILED with a plain reason instead of hanging
        val answer = rig.approvals.approve(approval.id)
        assertEquals(ApprovalAction.FAILED, answer.action)
    }

    @Test
    fun calendarEventsStayOutOfTheWayForTheEventLookup() = runBlocking<Unit> {
        rig.seed(dev.tim.assistant.model.toSyncRow(Cal_event("cal:primary:e1", 1000, 0, 0, "SYNCED", "primary", "e1", "Team lunch", CHAT_T0, CHAT_T0 + 3_600_000, 0, null)))
        val found = rig.call(rig.executor(), "find", """{"kind":"event","query":"lunch"}""")
        assertTrue("Team lunch" in found.text)
        assertNotNull(found.looked)
        assertNull(found.cardApprovalIds.firstOrNull())
        val row: SyncRow = pulled("cal_event").single()
        assertEquals("e1", rig.str(row, "eventId"))
        assertEquals("Team lunch", row.data["title"]!!.jsonPrimitive.content)
        assertTrue(row.data.jsonObject.isNotEmpty())
    }
}
