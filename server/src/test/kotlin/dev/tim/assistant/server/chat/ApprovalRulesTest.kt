package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.ai.tools.ApprovalExecutor
import dev.tim.assistant.ai.tools.ApprovalSink
import dev.tim.assistant.ai.tools.ApprovalUnreachable
import dev.tim.assistant.ai.tools.CALENDAR_NOT_CONFIGURED_TEXT
import dev.tim.assistant.ai.tools.CALENDAR_UNREACHABLE_TEXT
import dev.tim.assistant.ai.tools.eventTimes
import dev.tim.assistant.data.APPROVAL_VALID_MS
import dev.tim.assistant.data.approvalReceiptKey
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Doc
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Meal
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ai.taskRow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The approval rules that only a deleted shared ApprovalTest proved (PLAN 20.7 step 8), driven through the real [ApprovalExecutor] and
 * [ServerApprovalSink] over an in-memory database. Rules that ServerToolExecutorTest already proves are named in the Phase 20 Report.
 */
class ApprovalRulesTest {
    private val rig = ChatRig(withCalendar = true)

    @AfterTest
    fun close() = rig.close()

    private suspend fun forge(id: String, tool: String, args: String, status: String = "PENDING", createdAt: Long = rig.now) =
        rig.seed(toSyncRow(Approval(id, 1000, 0, 0, "SYNCED", tool, args, "summary", status, createdAt, null)))

    private suspend fun rows(table: String) = rig.pull().filter { it.table == table }
    private suspend fun row(table: String, id: String): SyncRow? = rows(table).firstOrNull { it.id == id }
    private suspend fun status(id: String) = rig.str(row("approval", id)!!, "status")

    // ---- 1 to 4: validity, reject, exactly-once, tombstones ---------------------------------------------

    @Test
    fun anApprovalIsValidForExactlySevenDaysAndExpiredStaysPending() = runBlocking<Unit> {
        forge("ok", "create_task", """{"title":"A"}""")
        forge("ok2", "create_task", """{"title":"B"}""")
        forge("late", "create_task", """{"title":"C"}""")
        rig.now += APPROVAL_VALID_MS - 1
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("ok").action)
        rig.now += 1
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("ok2").action)
        rig.now += 1
        assertEquals(ApprovalAction.EXPIRED, rig.approvals.approve("late").action)
        assertEquals("PENDING", status("late"))
        assertNull(row("task", "task:late"))
    }

    @Test
    fun rejectCreatesNoOutputASecondRejectIsFalseAndARejectedOneCannotBeApproved() = runBlocking<Unit> {
        forge("a1", "create_task", """{"title":"A"}""")
        assertTrue(rig.approvals.reject("a1"))
        assertFalse(rig.approvals.reject("a1"))
        assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve("a1").action)
        assertTrue(rows("task").isEmpty())
        assertEquals("REJECTED", status("a1"))
    }

    @Test
    fun approvingTwiceWritesOneRowWithTheApprovalsIdAndTheSecondIsNotPending() = runBlocking<Unit> {
        forge("a2", "create_task", """{"title":"Once"}""")
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("a2").action)
        assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve("a2").action)
        assertEquals(listOf("task:a2"), rows("task").map { it.id })
        assertEquals("DONE", status("a2"))
    }

    @Test
    fun aTombstonedOutputRowCountsAsExistingAndIsNotRewritten() = runBlocking<Unit> {
        val tomb = taskRow("task:a3", "Gone", null).copy(deleted = true, updatedAt = 5_000)
        rig.seed(tomb)
        forge("a3", "create_task", """{"title":"Gone"}""")
        assertEquals(ApprovalAction.ALREADY_DONE, rig.approvals.approve("a3").action)
        val after = row("task", "task:a3")!!
        assertTrue(after.deleted)
        assertEquals(tomb.updatedAt, after.updatedAt)
        assertEquals("DONE", status("a3"))
    }

    // ---- 5 and 6: output ids of every create tool ------------------------------------------------------

    @Test
    fun eachCreateToolWritesItsRowUnderTheApprovalId() = runBlocking<Unit> {
        rig.seedGoal("goal:g1", "Run")
        rig.seedCourse("course:c1", "Algebra")
        forge("t1", "create_task", """{"title":"Task","goalId":"goal:g1"}""")
        forge("d1", "create_doc", """{"title":"Note","body":"Hello"}""")
        forge("g2", "create_goal", """{"title":"Read more"}""")
        forge("e1", "add_exam", """{"title":"Final","localDate":"2026-12-01"}""")
        forge("j1", "queue_job", """{"kind":"STUDY_GUIDE","input":{"courseId":"course:c1"}}""")
        for (id in listOf("t1", "d1", "g2", "e1", "j1")) assertEquals(ApprovalAction.DONE, rig.approvals.approve(id).action, id)
        assertEquals("goal:g1", rig.str(row("task", "task:t1")!!, "goalId"))
        assertEquals("AI", rig.str(row("doc", "doc:d1")!!, "source"))
        assertNotNull(row("goal", "goal:g2"))
        assertNotNull(row("exam", "exam:e1"))
        assertEquals("QUEUED", rig.str(row("ai_job", "ai_job:j1")!!, "status"))
        for (id in listOf("t1", "d1", "g2", "e1", "j1")) assertEquals("DONE", status(id))
    }

    @Test
    fun eachLogToolWritesExactlyOneRowAndNothingBeforeApproval() = runBlocking<Unit> {
        forge("m1", "add_meal", """{"type":"MEAL","text":"pasta"}""")
        forge("s1", "add_spending", """{"amount":"12.50","category":"Food"}""")
        forge("c1", "add_checkin", """{"mood":4,"energy":3,"sleepQuality":3,"stress":2}""")
        forge("jn", "add_journal", """{"text":"good day"}""")
        assertTrue(rows("meal").isEmpty() && rows("spending").isEmpty() && rows("checkin").isEmpty() && rows("journal").isEmpty())
        for (id in listOf("m1", "s1", "c1", "jn")) assertEquals(ApprovalAction.DONE, rig.approvals.approve(id).action, id)
        assertEquals(listOf("meal:m1"), rows("meal").map { it.id })
        assertNull(rig.str(row("meal", "meal:m1")!!, "kcal"))
        assertEquals("1250", rig.str(row("spending", "spending:s1")!!, "amountAgorot"))
        assertEquals(listOf("checkin:c1"), rows("checkin").map { it.id })
        assertEquals(listOf("journal:jn"), rows("journal").map { it.id })
        for (id in listOf("m1", "s1", "c1", "jn")) assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve(id).action)
        assertEquals(1, rows("spending").size)
    }

    // ---- 7 to 9: updates -------------------------------------------------------------------------------

    @Test
    fun updateTaskAppliesOnlyOnApprovalKeepsOtherFieldsAndWritesAReceipt() = runBlocking<Unit> {
        rig.seed(taskRow("task:u1", "Keep me", "2026-11-12"))
        forge("u1", "update_task", """{"id":"task:u1","done":true}""")
        assertEquals("false", rig.str(row("task", "task:u1")!!, "done"))
        rig.now += 1_000
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("u1").action)
        val task = row("task", "task:u1")!!
        assertEquals("Keep me", rig.str(task, "title"))
        assertEquals("2026-11-12", rig.str(task, "dueDate"))
        assertEquals("true", rig.str(task, "done"))
        assertNotNull(rig.str(task, "doneAt"))
        assertNotNull(rig.sdb.db.kvQueries.get(approvalReceiptKey("u1")).executeAsOneOrNull())
        assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve("u1").action)
        assertEquals(task.updatedAt, row("task", "task:u1")!!.updatedAt)
    }

    @Test
    fun anUpdateWhoseTargetWasDeletedFailsWithNoWriteAndNoReceipt() = runBlocking<Unit> {
        rig.seed(taskRow("task:u2", "Soon gone", null).copy(deleted = true, updatedAt = 4_000))
        forge("u2", "update_task", """{"id":"task:u2","title":"New"}""")
        val result = rig.approvals.approve("u2")
        assertEquals(ApprovalAction.FAILED, result.action)
        assertTrue("no longer exists" in result.message, result.message)
        assertTrue("no longer exists" in rig.str(row("approval", "u2")!!, "resultText")!!)
        assertEquals("FAILED", status("u2"))
        assertEquals(4_000L, row("task", "task:u2")!!.updatedAt)
        assertNull(rig.sdb.db.kvQueries.get(approvalReceiptKey("u2")).executeAsOneOrNull())
    }

    @Test
    fun docMealAndJournalUpdatesFollowTheirRules() = runBlocking<Unit> {
        rig.seed(toSyncRow(Doc("doc:d1", 1000, 0, 0, "SYNCED", null, "Plan", "Old text", "USER")))
        rig.seed(toSyncRow(Doc("doc:d2", 1000, 0, 0, "SYNCED", null, "Other", "Body", "USER")))
        rig.seed(toSyncRow(Meal("meal:m1", 1000, 0, 0, "SYNCED", "MEAL", "rice", CHAT_T0, "2026-11-11", 500L, 10L, 80L, 5L, "model")))
        rig.seed(toSyncRow(Journal("journal:j1", 1000, 0, 0, "SYNCED", "before", 1000)))
        forge("ud1", "update_doc", """{"id":"doc:d1","body":"New line","bodyMode":"APPEND"}""")
        forge("ud2", "update_doc", """{"id":"doc:d2","title":"Renamed","body":"Fresh"}""")
        forge("um1", "update_meal", """{"id":"meal:m1","type":"SNACK"}""")
        forge("um2", "update_meal", """{"id":"meal:m1","text":"noodles","time":"20:30"}""")
        forge("uj", "update_journal", """{"id":"journal:j1","text":"after"}""")
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("ud1").action)
        assertEquals("Old text\nNew line", rig.str(row("doc", "doc:d1")!!, "body"))
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("ud2").action)
        assertEquals("Fresh", rig.str(row("doc", "doc:d2")!!, "body"))
        assertEquals("Renamed", rig.str(row("doc", "doc:d2")!!, "title"))
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("um1").action)
        val kept = row("meal", "meal:m1")!!
        assertEquals("SNACK", rig.str(kept, "type"))
        assertEquals("500", rig.str(kept, "kcal"))
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("um2").action)
        val changed = row("meal", "meal:m1")!!
        assertEquals("noodles", rig.str(changed, "text"))
        assertNull(rig.str(changed, "kcal"))
        assertNull(rig.str(changed, "proteinG"))
        assertNull(rig.str(changed, "carbsG"))
        assertNull(rig.str(changed, "fatG"))
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("uj").action)
        assertEquals("after", rig.str(row("journal", "journal:j1")!!, "text"))
    }

    // ---- 10 to 13: mail, rules, forged approvals -------------------------------------------------------

    @Test
    fun aDraftIsSavedUnsentAndAMailActionIsApprovedWithNothingSentOverHttp() = runBlocking<Unit> {
        rig.seedMail("g1", "Invoice")
        forge("dr", "draft_email", """{"to":"a@b.com","subject":"Hi","body":"Text","replyToGmailId":"g1"}""")
        forge("ma", "suggest_mail_action", """{"gmailId":"g1","action":"ARCHIVE","reason":"old"}""")
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("dr").action)
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("ma").action)
        val draft = row("mail_draft", "mail_draft:dr")!!
        assertEquals("DRAFT", rig.str(draft, "status"))
        assertNull(rig.str(draft, "sentAt"))
        assertEquals("g1", rig.str(draft, "replyToGmailId"))
        assertEquals("APPROVED", rig.str(row("mail_action", "mail_action:ma")!!, "status"))
        assertTrue(rig.calendar.posts.isEmpty())
    }

    @Test
    fun anAppRuleUsesItsCanonicalIdAndASecondApprovalLeavesOneRow() = runBlocking<Unit> {
        val args = """{"matchType":"PACKAGE","pattern":" Com.Example.App ","label":"PRODUCTIVE"}"""
        forge("r1", "set_app_rule", args)
        forge("r2", "set_app_rule", args)
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("r1").action)
        assertEquals("rule:PACKAGE:com.example.app", rows("app_rule").single().id)
        assertNotNull(rig.sdb.db.kvQueries.get(approvalReceiptKey("r1")).executeAsOneOrNull())
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("r2").action)
        assertEquals(1, rows("app_rule").size)
        // a receipt that already exists ends the approval ALREADY_DONE without writing
        forge("r3", "set_app_rule", """{"matchType":"PACKAGE","pattern":"com.other.app","label":"PRODUCTIVE"}""")
        rig.sdb.db.kvQueries.put(approvalReceiptKey("r3"), "1")
        assertEquals(ApprovalAction.ALREADY_DONE, rig.approvals.approve("r3").action)
        assertEquals(1, rows("app_rule").size)
    }

    @Test
    fun aForgedQueueJobOfAServerOnlyKindFailsAndCreatesNoJob() = runBlocking<Unit> {
        forge("q1", "queue_job", """{"kind":"DREAM_PATTERNS","input":{}}""")
        forge("q2", "queue_job", """{"kind":"RESEARCH","input":{}}""")
        assertEquals(ApprovalAction.FAILED, rig.approvals.approve("q1").action)
        assertEquals(ApprovalAction.FAILED, rig.approvals.approve("q2").action)
        assertTrue(rows("ai_job").isEmpty())
        assertEquals("FAILED", status("q1"))
    }

    @Test
    fun aForgedUnknownToolOrInvalidArgsFailAndWriteNothing() = runBlocking<Unit> {
        forge("f1", "delete_everything", "{}")
        forge("f2", "create_task", """{"title":""}""")
        assertEquals(ApprovalAction.FAILED, rig.approvals.approve("f1").action)
        assertEquals(ApprovalAction.FAILED, rig.approvals.approve("f2").action)
        assertTrue(rows("task").isEmpty())
        assertEquals("FAILED", status("f2"))
    }

    // ---- 14: label names from the live Gmail labels ----------------------------------------------------

    private fun executorWithLabels(labels: Map<String, String>?): ApprovalExecutor {
        val real: ApprovalSink = rig.sink
        val sink = object : ApprovalSink by real {
            override suspend fun labelName(labelId: String): String? {
                if (labels == null) throw ApprovalUnreachable()
                return labels[labelId]
            }
        }
        return ApprovalExecutor(sink) { rig.now }
    }

    @Test
    fun aLabelActionTakesItsNameFromTheLiveLabelsAndAnUnknownIdFails() = runBlocking<Unit> {
        rig.seedMail("g1", "Invoice")
        val exec = executorWithLabels(mapOf("L1" to "Work"))
        forge("l1", "suggest_mail_action", """{"gmailId":"g1","action":"LABEL","labelId":"L1","reason":"work mail"}""")
        forge("l2", "suggest_mail_action", """{"gmailId":"g1","action":"LABEL","labelId":"NOPE","reason":"work mail"}""")
        assertEquals(ApprovalAction.DONE, exec.approve("l1").action)
        assertEquals("Work", rig.str(row("mail_action", "mail_action:l1")!!, "labelName"))
        assertEquals(ApprovalAction.FAILED, exec.approve("l2").action)
        assertNull(row("mail_action", "mail_action:l2"))
        forge("l3", "suggest_mail_action", """{"gmailId":"g1","action":"LABEL","labelId":"L1","reason":"work mail"}""")
        assertEquals(ApprovalAction.SERVER_UNREACHABLE, executorWithLabels(null).approve("l3").action)
        assertEquals("PENDING", status("l3"))
    }

    // ---- 15: create_event ------------------------------------------------------------------------------

    private val eventArgs =
        """{"title":"Dentist","start":"2026-11-12T09:00","end":"2026-11-12T10:00","repeatRule":"WEEKLY","repeatUntil":"2026-12-31","color":"RED","note":"bring card"}"""

    @Test
    fun anEventIsPostedOnceWithJerusalemTimesAndTheReturnedIdIsTheResult() = runBlocking<Unit> {
        forge("ev", "create_event", eventArgs)
        assertTrue(rig.calendar.posts.isEmpty())
        val result = rig.approvals.approve("ev")
        assertEquals(ApprovalAction.DONE, result.action)
        assertEquals("ev-1", result.message)
        assertEquals("ev-1", rig.str(row("approval", "ev")!!, "resultText"))
        assertEquals(1, rig.calendar.posts.size)
        val body = Json.parseToJsonElement(rig.calendar.posts.single()).jsonObject
        val (start, end) = eventTimes("2026-11-12T09:00", "2026-11-12T10:00", false)
        assertEquals(start, body["startAt"]!!.jsonPrimitive.content.toLong())
        assertEquals(end, body["endAt"]!!.jsonPrimitive.content.toLong())
        assertEquals("WEEKLY", body["repeatRule"]!!.jsonPrimitive.content)
        assertEquals("2026-12-31", body["repeatUntil"]!!.jsonPrimitive.content)
        assertEquals("RED", body["color"]!!.jsonPrimitive.content)
        assertEquals("bring card", body["note"]!!.jsonPrimitive.content)
        assertEquals("false", body["allDay"]!!.jsonPrimitive.content)
        assertTrue(rows("cal_event").isEmpty())
        assertEquals(ApprovalAction.NOT_PENDING, rig.approvals.approve("ev").action)
        assertEquals(1, rig.calendar.posts.size)
    }

    @Test
    fun anAllDayEventSendsMidnightToMidnightWithNoRepeat() = runBlocking<Unit> {
        forge("ad", "create_event", """{"title":"Trip","start":"2026-11-12","end":"2026-11-13","allDay":true,"repeatRule":"WEEKLY"}""")
        assertEquals(ApprovalAction.DONE, rig.approvals.approve("ad").action)
        val body = Json.parseToJsonElement(rig.calendar.posts.single()).jsonObject
        val (start, end) = eventTimes("2026-11-12", "2026-11-13", true)
        assertEquals(start, body["startAt"]!!.jsonPrimitive.content.toLong())
        assertEquals(end, body["endAt"]!!.jsonPrimitive.content.toLong())
        assertEquals("true", body["allDay"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefusedOrUnavailableCalendarEndsTheApprovalWithTheRightText() = runBlocking<Unit> {
        forge("e502", "create_event", eventArgs)
        rig.calendar.status = 502
        val bad = rig.approvals.approve("e502")
        assertEquals(ApprovalAction.FAILED, bad.action)
        assertEquals(CALENDAR_UNREACHABLE_TEXT, bad.message)
        forge("e503", "create_event", eventArgs)
        rig.calendar.status = 503
        assertEquals(CALENDAR_UNREACHABLE_TEXT, rig.approvals.approve("e503").message)
        forge("e400", "create_event", eventArgs)
        rig.calendar.status = 400
        assertEquals(ApprovalAction.FAILED, rig.approvals.approve("e400").action)
        assertEquals("FAILED", status("e400"))
    }

    @Test
    fun anExpiredEventApprovalSendsNothing() = runBlocking<Unit> {
        forge("ex", "create_event", eventArgs)
        rig.now += APPROVAL_VALID_MS + 1
        assertEquals(ApprovalAction.EXPIRED, rig.approvals.approve("ex").action)
        assertTrue(rig.calendar.posts.isEmpty())
    }

    @Test
    fun withoutACalendarConfigNoCallIsMadeAndTheApprovalStaysPending() = runBlocking<Unit> {
        val bare = ChatRig()
        try {
            bare.seed(toSyncRow(Approval("nc", 1000, 0, 0, "SYNCED", "create_event", eventArgs, "summary", "PENDING", bare.now, null)))
            val result = bare.approvals.approve("nc")
            assertEquals(ApprovalAction.FAILED, result.action)
            assertEquals(CALENDAR_NOT_CONFIGURED_TEXT, result.message)
            assertTrue(bare.calendar.posts.isEmpty())
        } finally {
            bare.close()
        }
    }
}
