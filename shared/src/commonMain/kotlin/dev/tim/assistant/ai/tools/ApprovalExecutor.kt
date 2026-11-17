package dev.tim.assistant.ai.tools

import dev.tim.assistant.data.APPROVAL_DONE
import dev.tim.assistant.data.APPROVAL_FAILED
import dev.tim.assistant.data.APPROVAL_PENDING
import dev.tim.assistant.data.APPROVAL_REJECTED
import dev.tim.assistant.data.ApprovalEffectFailed
import dev.tim.assistant.data.ApproveOutcome
import dev.tim.assistant.data.CreateEventResult
import dev.tim.assistant.data.PENDING
import dev.tim.assistant.data.docBodyTooLong
import dev.tim.assistant.data.isApprovalExpired
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Doc
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Goal
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Mail_action
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.forms.parseAgorot
import dev.tim.assistant.ui.forms.parseEatenAt
import dev.tim.assistant.usage.ruleId
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class ApprovalAction { DONE, ALREADY_DONE, NOT_PENDING, EXPIRED, NOT_FOUND, FAILED, SERVER_UNREACHABLE }

/** What tapping Approve did. [message] is shown to Tim. */
data class ApprovalResult(val action: ApprovalAction, val message: String)

const val SERVER_NOT_REACHABLE_TEXT = "Server not reachable - try again"
const val CALENDAR_UNREACHABLE_TEXT = "calendar-unreachable - check Wally Calendar before asking again"
const val CALENDAR_NOT_CONFIGURED_TEXT = "calendar-not-configured"

/** Text of an approved mail action; the label/archive itself happens in Gmail. */
const val MAIL_ACTION_APPROVED_TEXT = "Approved - the label/archive now happens in Gmail"

/** startAt/endAt (epoch ms) of a create_event call. All-day: Jerusalem midnight of the start date to midnight after the end date. */
fun eventTimes(start: String, end: String, allDay: Boolean): Pair<Long, Long> =
    if (allDay) {
        LocalDate.parse(start).atStartOfDayIn(JERUSALEM).toEpochMilliseconds() to
            LocalDate.parse(end).plus(DatePeriod(days = 1)).atStartOfDayIn(JERUSALEM).toEpochMilliseconds()
    } else {
        LocalDateTime.parse(start).toInstant(JERUSALEM).toEpochMilliseconds() to
            LocalDateTime.parse(end).toInstant(JERUSALEM).toEpochMilliseconds()
    }

/** The re-read, PENDING approval row plus read access to the same database, handed to an effect inside [ApprovalSink.decide]. */
class ApprovalReadView(val approval: Approval, val db: AssistantDb)

/** Thrown by [ApprovalSink.labelName] when the Gmail labels could not be fetched at all (nothing is decided). */
class ApprovalUnreachable(message: String = SERVER_NOT_REACHABLE_TEXT) : Exception(message)

/** The approval row as it will be written: [status] DONE or FAILED with [text], stamped strictly newer than the stored row. */
fun approvalDecisionRow(approval: Approval, status: String, text: String?, now: Long): SyncRow =
    toSyncRow(approval.copy(updatedAt = maxOf(now, approval.updatedAt + 1), syncState = PENDING, status = status, resultText = text))

/**
 * Where approvals are decided. The server implements it over its own database (clients never decide locally).
 * [decide] is ONE locked transaction: it re-reads the approval (NOT_FOUND, not PENDING -> NOT_PENDING, past 7 days -> EXPIRED),
 * runs [effect] with the fresh row, then writes every returned row (target rows and the approval's own DONE/FAILED row built
 * with [approvalDecisionRow]). An [ApprovalEffectFailed] thrown by the effect writes nothing but the FAILED approval row. The
 * outcome is FAILED when the approval row of the list says FAILED, else DONE. Network work (calendar, labels, Codex) happens
 * outside that transaction, through the other methods.
 */
interface ApprovalSink {
    /** The live (not deleted) approval row, or null. */
    suspend fun approvalRow(id: String): Approval?

    suspend fun decide(id: String, now: Long, effect: (ApprovalReadView) -> List<SyncRow>): ApproveOutcome

    /** Duplicate-safe calendar create keyed by [clientKey] (the approval id); [body] is the validated create_event args. */
    suspend fun createEvent(clientKey: String, body: JsonObject): CreateEventResult

    /** Wakes the Gmail worker after an approved mail action committed. */
    fun wakeGmail()

    /** The Gmail label's name, or null when no label has that id. Throws [ApprovalUnreachable] when labels can't be fetched. */
    suspend fun labelName(labelId: String): String?

    /** Runs an approved research_question (claim, queue/run through Codex, finish the approval). */
    suspend fun approveResearch(id: String, a: JsonObject): ApprovalResult
}

/**
 * Turns one approval into rows and hands them to an [ApprovalSink]. Local tools build their rows inside [ApprovalSink.decide]
 * (output ids `<tool>:<approvalId>`); create_event, label lookup and research call the sink's network methods first,
 * with no database transaction open.
 */
class ApprovalExecutor(
    private val sink: ApprovalSink,
    private val now: () -> Long,
) {
    /** PENDING -> REJECTED through the sink; creates no output. False when the approval is missing, decided or expired. */
    suspend fun reject(id: String): Boolean =
        sink.decide(id, now()) { v -> listOf(approvalDecisionRow(v.approval, APPROVAL_REJECTED, null, now())) } == ApproveOutcome.DONE

    suspend fun approve(id: String): ApprovalResult {
        val row = sink.approvalRow(id) ?: return ApprovalResult(ApprovalAction.NOT_FOUND, "Approval not found")
        if (row.status != APPROVAL_PENDING) return ApprovalResult(ApprovalAction.NOT_PENDING, "Already ${row.status.lowercase()}")
        val t = now()
        if (isApprovalExpired(row.createdAt, t)) return ApprovalResult(ApprovalAction.EXPIRED, "This approval expired")
        val spec = Tools.find(row.tool)
        if (spec == null || !(spec.kind == ToolKind.WRITE || row.tool == "research_question")) {
            return ApprovalResult(ApprovalAction.FAILED, "This kind of approval cannot be run here")
        }
        val args = when (val c = spec.validate(parseArgs(row.argsJson))) {
            is ToolCheck.Bad -> return fail(id, t, "Not allowed: ${c.reason}")
            is ToolCheck.Ok -> c.args
        }
        return when (row.tool) {
            "create_event" -> approveEvent(id, args, t)
            "research_question" -> sink.approveResearch(id, args)
            "suggest_mail_action" -> approveMailAction(id, args, t)
            else -> run(id, t) { v -> apply(v, row.tool, id, args, t, null) }
        }
    }

    private suspend fun fail(id: String, t: Long, text: String): ApprovalResult {
        val outcome = sink.decide(id, t) { v -> listOf(approvalDecisionRow(v.approval, APPROVAL_FAILED, text, t)) }
        return if (outcome == ApproveOutcome.FAILED) ApprovalResult(ApprovalAction.FAILED, text) else mapOutcome(id, outcome)
    }

    private suspend fun run(id: String, t: Long, effect: (ApprovalReadView) -> List<SyncRow>): ApprovalResult =
        mapOutcome(id, sink.decide(id, t, effect))

    private suspend fun mapOutcome(id: String, outcome: ApproveOutcome): ApprovalResult = when (outcome) {
        ApproveOutcome.DONE -> ApprovalResult(ApprovalAction.DONE, sink.approvalRow(id)?.resultText ?: "Done")
        ApproveOutcome.DONE_EXISTING -> ApprovalResult(ApprovalAction.ALREADY_DONE, sink.approvalRow(id)?.resultText ?: "Already done")
        ApproveOutcome.NOT_PENDING -> ApprovalResult(ApprovalAction.NOT_PENDING, "Already decided")
        ApproveOutcome.EXPIRED -> ApprovalResult(ApprovalAction.EXPIRED, "This approval expired")
        ApproveOutcome.NOT_FOUND -> ApprovalResult(ApprovalAction.NOT_FOUND, "Approval not found")
        ApproveOutcome.FAILED -> ApprovalResult(ApprovalAction.FAILED, sink.approvalRow(id)?.resultText ?: "Failed")
    }

    // ---- local effects (run inside the one decide transaction; they only build rows) ---------------

    private fun rows(v: ApprovalReadView, t: Long, text: String, vararg target: SyncRow): List<SyncRow> =
        target.toList() + approvalDecisionRow(v.approval, APPROVAL_DONE, text, t)

    private fun apply(v: ApprovalReadView, tool: String, approvalId: String, a: JsonObject, t: Long, labelName: String?): List<SyncRow> {
        val db = v.db
        fun s(key: String): String? = a[key]?.jsonPrimitive?.content
        fun n(key: String): Long = a[key]!!.jsonPrimitive.content.toLong()
        return when (tool) {
            "create_task" ->
                rows(v, t, "Task added", toSyncRow(Task("task:$approvalId", t, 0, 0, PENDING, s("title")!!, s("dueDate"), 0, null, s("goalId"))))
            "create_doc" ->
                rows(v, t, "Note created", toSyncRow(Doc("doc:$approvalId", t, 0, 0, PENDING, s("folderId"), s("title")!!, s("body")!!, "AI")))
            "create_goal" ->
                rows(v, t, "Goal added", toSyncRow(Goal("goal:$approvalId", t, 0, 0, PENDING, s("title")!!, s("why"), s("deadline"), "ACTIVE")))
            "add_exam" ->
                rows(v, t, "Exam added", toSyncRow(Exam("exam:$approvalId", t, 0, 0, PENDING, s("courseId"), s("title")!!, s("localDate")!!, s("time"))))
            "queue_job" -> rows(
                v, t, "Job queued",
                toSyncRow(
                    Ai_job(
                        "ai_job:$approvalId", t, 0, 0, PENDING, s("kind")!!, (a["input"] as JsonObject).toString(),
                        "QUEUED", null, null, 0, t, null,
                    ),
                ),
            )
            "draft_email" -> rows(
                v, t, "Draft saved (not sent)",
                toSyncRow(
                    Mail_draft(
                        "mail_draft:$approvalId", t, 0, 0, PENDING, s("to")!!, s("subject")!!, s("body")!!, s("replyToGmailId"),
                        null, "DRAFT", null, null,
                    ),
                ),
            )
            "suggest_mail_action" -> rows(
                v, t, MAIL_ACTION_APPROVED_TEXT,
                toSyncRow(
                    Mail_action(
                        "mail_action:$approvalId", t, 0, 0, PENDING, s("gmailId")!!, s("action")!!, s("labelId"), labelName,
                        s("reason")!!, "APPROVED",
                    ),
                ),
            )
            "set_app_rule" -> {
                val type = s("matchType")!!
                val pattern = s("pattern")!!
                val label = s("label")!!
                val id = ruleId(type, pattern)
                val prev = db.app_ruleQueries.selectById(id).executeAsOneOrNull()
                if (prev == null || prev.deleted != 0L || prev.label != label) {
                    rows(
                        v, t, "Rule saved",
                        toSyncRow(App_rule(id, maxOf(t, (prev?.updatedAt ?: Long.MIN_VALUE) + 1), 0, prev?.serverSeq ?: 0, PENDING, type, pattern, label)),
                    )
                } else {
                    rows(v, t, "Rule saved")
                }
            }
            "add_meal" -> {
                val eatenAt = if (s("date") == null && s("time") == null) t
                else parseEatenAt(s("date") ?: todayOf(t), s("time") ?: "00:00") ?: throw ApprovalEffectFailed("Not a real date or time")
                rows(
                    v, t, "Meal added",
                    toSyncRow(
                        Meal(
                            "meal:$approvalId", t, 0, 0, PENDING, s("type")!!, s("text")!!, eatenAt,
                            localDateOf(Instant.fromEpochMilliseconds(eatenAt)), null, null, null, null, null,
                        ),
                    ),
                )
            }
            "add_spending" -> rows(
                v, t, "Spending added",
                toSyncRow(
                    Spending(
                        "spending:$approvalId", t, 0, 0, PENDING, parseAgorot(s("amount")!!)!!, s("category")!!, s("note"),
                        s("date") ?: todayOf(t),
                    ),
                ),
            )
            "add_checkin" -> rows(
                v, t, "Check-in added",
                toSyncRow(
                    Checkin(
                        "checkin:$approvalId", t, 0, 0, PENDING, s("date") ?: todayOf(t), n("mood"), n("energy"), n("sleepQuality"),
                        n("stress"), s("note"),
                    ),
                ),
            )
            "add_journal" ->
                rows(v, t, "Journal entry added", toSyncRow(Journal("journal:$approvalId", t, 0, 0, PENDING, s("text")!!, t)))
            else -> if (tool.startsWith("update_")) applyUpdate(v, tool, a, t) else error("no local effect for $tool")
        }
    }

    private fun todayOf(t: Long): String = localDateOf(Instant.fromEpochMilliseconds(t))

    private fun gone(what: String): Nothing = throw ApprovalEffectFailed("This $what no longer exists")

    /** Changes one existing row: given fields replace the old values, the others stay. Throws before building rows when the row is gone. */
    private fun applyUpdate(v: ApprovalReadView, tool: String, a: JsonObject, t: Long): List<SyncRow> {
        val db = v.db
        fun s(key: String): String? = a[key]?.jsonPrimitive?.content
        fun n(key: String): Long? = s(key)?.toLong()
        val id = s("id")!!
        fun next(prev: Long) = maxOf(t, prev + 1)
        return when (tool) {
            "update_task" -> {
                val p = db.taskQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("task")
                val done = a["done"]?.jsonPrimitive?.booleanOrNull
                val wasDone = p.done != 0L
                val doneAt = when {
                    done == null -> p.doneAt
                    !done -> null
                    wasDone && p.doneAt != null -> p.doneAt
                    else -> t
                }
                rows(
                    v, t, "Task updated",
                    toSyncRow(
                        p.copy(
                            updatedAt = next(p.updatedAt), syncState = PENDING, title = s("title") ?: p.title, dueDate = s("dueDate") ?: p.dueDate,
                            done = if (done == null) p.done else if (done) 1L else 0L, doneAt = doneAt,
                        ),
                    ),
                )
            }
            "update_goal" -> {
                val p = db.goalQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("goal")
                rows(
                    v, t, "Goal updated",
                    toSyncRow(
                        p.copy(
                            updatedAt = next(p.updatedAt), syncState = PENDING, title = s("title") ?: p.title, why = s("why") ?: p.why,
                            deadline = s("deadline") ?: p.deadline, status = s("status") ?: p.status,
                        ),
                    ),
                )
            }
            "update_doc" -> {
                val p = db.docQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("note")
                val body = s("body")?.let { if (s("bodyMode") == "APPEND") appendedBody(p.body, it) else it } ?: p.body
                if (docBodyTooLong(body)) throw ApprovalEffectFailed("The note would be too long")
                rows(v, t, "Note updated", toSyncRow(p.copy(updatedAt = next(p.updatedAt), syncState = PENDING, title = s("title") ?: p.title, body = body)))
            }
            "update_meal" -> {
                val p = db.mealQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("meal")
                val local = Instant.fromEpochMilliseconds(p.eatenAt).toLocalDateTime(JERUSALEM)
                val eatenAt = if (s("date") == null && s("time") == null) p.eatenAt
                else parseEatenAt(s("date") ?: local.date.toString(), s("time") ?: local.time.toString().take(5))
                    ?: throw ApprovalEffectFailed("Not a real date or time")
                val text = s("text") ?: p.text
                val keep = text.trim() == p.text.trim()
                rows(
                    v, t, "Meal updated",
                    toSyncRow(
                        p.copy(
                            updatedAt = next(p.updatedAt), syncState = PENDING, type = s("type") ?: p.type, text = text.trim(), eatenAt = eatenAt,
                            localDate = localDateOf(Instant.fromEpochMilliseconds(eatenAt)),
                            kcal = if (keep) p.kcal else null, proteinG = if (keep) p.proteinG else null, carbsG = if (keep) p.carbsG else null,
                            fatG = if (keep) p.fatG else null, estimateModel = if (keep) p.estimateModel else null,
                        ),
                    ),
                )
            }
            "update_spending" -> {
                val p = db.spendingQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("spending entry")
                rows(
                    v, t, "Spending updated",
                    toSyncRow(
                        p.copy(
                            updatedAt = next(p.updatedAt), syncState = PENDING, amountAgorot = s("amount")?.let { parseAgorot(it)!! } ?: p.amountAgorot,
                            category = s("category") ?: p.category, note = s("note") ?: p.note, localDate = s("date") ?: p.localDate,
                        ),
                    ),
                )
            }
            "update_checkin" -> {
                val p = db.checkinQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("check-in")
                rows(
                    v, t, "Check-in updated",
                    toSyncRow(
                        p.copy(
                            updatedAt = next(p.updatedAt), syncState = PENDING, mood = n("mood") ?: p.mood, energy = n("energy") ?: p.energy,
                            sleepQuality = n("sleepQuality") ?: p.sleepQuality, stress = n("stress") ?: p.stress, note = s("note") ?: p.note,
                        ),
                    ),
                )
            }
            "update_journal" -> {
                val p = db.journalQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("journal entry")
                rows(v, t, "Journal entry updated", toSyncRow(p.copy(updatedAt = next(p.updatedAt), syncState = PENDING, text = s("text")!!)))
            }
            "update_exam" -> {
                val p = db.examQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: gone("exam")
                rows(
                    v, t, "Exam updated",
                    toSyncRow(
                        p.copy(
                            updatedAt = next(p.updatedAt), syncState = PENDING, title = s("title") ?: p.title, localDate = s("localDate") ?: p.localDate,
                            time = s("time") ?: p.time, courseId = s("courseId") ?: p.courseId,
                        ),
                    ),
                )
            }
            else -> error("no update effect for $tool")
        }
    }

    // ---- suggest_mail_action: the label name comes from the live labels, outside any transaction -----

    private suspend fun approveMailAction(id: String, a: JsonObject, t: Long): ApprovalResult {
        var labelName: String? = null
        if (a["action"]?.jsonPrimitive?.content == "LABEL") {
            val wanted = a["labelId"]?.jsonPrimitive?.content.orEmpty()
            labelName = try {
                sink.labelName(wanted)
            } catch (e: ApprovalUnreachable) {
                return ApprovalResult(ApprovalAction.SERVER_UNREACHABLE, SERVER_NOT_REACHABLE_TEXT)
            } ?: return fail(id, t, "Unknown label - no Gmail label with that id")
        }
        val name = labelName
        val result = run(id, t) { v -> apply(v, "suggest_mail_action", id, a, t, name) }
        if (result.action == ApprovalAction.DONE) sink.wakeGmail()
        return result
    }

    // ---- create_event: the calendar call happens first, outside any transaction; clientKey = approval id makes it duplicate-safe ----

    private suspend fun approveEvent(id: String, a: JsonObject, t: Long): ApprovalResult =
        when (val result = sink.createEvent(id, a)) {
            is CreateEventResult.Created -> run(id, t) { v -> rows(v, t, result.id) }
            is CreateEventResult.Rejected -> fail(id, t, result.body.ifBlank { "The calendar refused the event" })
            CreateEventResult.CalendarUnreachable -> fail(id, t, CALENDAR_UNREACHABLE_TEXT)
            CreateEventResult.CalendarNotConfigured -> fail(id, t, CALENDAR_NOT_CONFIGURED_TEXT)
            is CreateEventResult.Other -> fail(id, t, "Unexpected answer (HTTP ${result.status}) - check Wally Calendar before asking again")
            CreateEventResult.ServerUnreachable -> ApprovalResult(ApprovalAction.SERVER_UNREACHABLE, SERVER_NOT_REACHABLE_TEXT)
        }
}
