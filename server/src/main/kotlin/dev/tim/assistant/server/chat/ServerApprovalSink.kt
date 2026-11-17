package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.ai.tools.ApprovalExecutor
import dev.tim.assistant.ai.tools.ApprovalReadView
import dev.tim.assistant.ai.tools.ApprovalResult
import dev.tim.assistant.ai.tools.ApprovalSink
import dev.tim.assistant.ai.tools.ApprovalUnreachable
import dev.tim.assistant.ai.tools.approvalDecisionRow
import dev.tim.assistant.ai.tools.eventTimes
import dev.tim.assistant.data.APPROVAL_DONE
import dev.tim.assistant.data.APPROVAL_FAILED
import dev.tim.assistant.data.ApprovalEffectFailed
import dev.tim.assistant.data.ApproveOutcome
import dev.tim.assistant.data.CreateEventResult
import dev.tim.assistant.data.APPROVAL_PENDING
import dev.tim.assistant.data.approvalOutputId
import dev.tim.assistant.data.approvalOutputKind
import dev.tim.assistant.data.approvalReceiptKey
import dev.tim.assistant.data.approvalUsesReceipt
import dev.tim.assistant.data.isApprovalExpired
import dev.tim.assistant.db.Approval
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.google.GoogleApi
import dev.tim.assistant.server.google.GoogleAuthException
import dev.tim.assistant.server.wallycal.CreateOutcome
import dev.tim.assistant.server.wallycal.WallyCalService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Runs an approved `research_question` through Codex (claim, run or queue, finish the approval). Provided by the Codex half. */
interface ResearchApprover {
    /** [chatId] is the chat a finished answer is posted into (null: no chat). */
    suspend fun approve(id: String, args: JsonObject, chatId: String? = null): ApprovalResult

    /**
     * Same as [approve] but says what happened, so a reply can be honest. The default maps a DONE answer to [ResearchKind.QUEUED]
     * and anything else to [ResearchKind.FAILED]; the real implementation tells cached and already-working apart.
     */
    suspend fun submit(id: String, args: JsonObject, chatId: String? = null): ResearchOutcome {
        val result = approve(id, args, chatId)
        return ResearchOutcome(if (result.action == ApprovalAction.DONE) ResearchKind.QUEUED else ResearchKind.FAILED, result)
    }
}

/** What one approved research question turned into. */
enum class ResearchKind { QUEUED, CACHED, WORKING, FAILED }

/** [result].message is the saved answer for [ResearchKind.CACHED] and the job id otherwise. */
data class ResearchOutcome(val kind: ResearchKind, val result: ApprovalResult)

const val RESEARCH_NOT_SET_UP_TEXT = "Research is not set up on the server yet"

/**
 * Where the server decides approvals, over its own database. [decide] is one locked transaction through [ServerWriter]
 * (so target rows and the approval's own row get a serverSeq and reach every client at the next pull). Calendar, Gmail
 * labels and Codex calls happen before or after it, never inside.
 */
class ServerApprovalSink(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val writer: ServerWriter,
    private val wallyCal: WallyCalService?,
    private val googleApi: GoogleApi?,
    private val researchApprover: ResearchApprover? = null,
) : ApprovalSink {
    private val db get() = sdb.db

    override suspend fun approvalRow(id: String): Approval? =
        db.approvalQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }

    override suspend fun decide(id: String, now: Long, effect: (ApprovalReadView) -> List<SyncRow>): ApproveOutcome =
        writer.transaction { tx ->
            val row = tx.db.approvalQueries.selectById(id).executeAsOneOrNull()
            when {
                row == null || row.deleted != 0L -> ApproveOutcome.NOT_FOUND
                row.status != APPROVAL_PENDING -> ApproveOutcome.NOT_PENDING
                isApprovalExpired(row.createdAt, now) -> ApproveOutcome.EXPIRED
                else -> {
                    val receiptKey = if (approvalUsesReceipt(row.tool)) approvalReceiptKey(id) else null
                    val exists = if (receiptKey != null) tx.db.kvQueries.get(receiptKey).executeAsOneOrNull() != null else outputExists(row)
                    if (exists) {
                        tx.write(approvalDecisionRow(row, APPROVAL_DONE, row.resultText ?: "Already done", now))
                        ApproveOutcome.DONE_EXISTING
                    } else {
                        val rows = try {
                            effect(ApprovalReadView(row, tx.db))
                        } catch (e: ApprovalEffectFailed) {
                            tx.write(approvalDecisionRow(row, APPROVAL_FAILED, e.message, now))
                            return@transaction ApproveOutcome.FAILED
                        }
                        for (r in rows) tx.write(r)
                        val decided = rows.firstOrNull { it.table == "approval" && it.id == id }?.data?.get("status")?.jsonPrimitive?.content
                        if (decided == APPROVAL_FAILED) {
                            ApproveOutcome.FAILED
                        } else {
                            if (receiptKey != null) tx.db.kvQueries.put(receiptKey, "1")
                            ApproveOutcome.DONE
                        }
                    }
                }
            }
        }

    /** Physical check (tombstones count): does the row this approval would write already exist? */
    private fun outputExists(row: Approval): Boolean {
        val kind = approvalOutputKind(row.tool) ?: return false
        val outputId = approvalOutputId(row.tool, row.id) ?: return false
        return store.storedUpdatedAt(kind, outputId) != null
    }

    override suspend fun createEvent(clientKey: String, body: JsonObject): CreateEventResult {
        val service = wallyCal ?: return CreateEventResult.CalendarNotConfigured
        fun s(key: String): String? = (body[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val allDay = (body["allDay"] as? JsonPrimitive)?.booleanOrNull == true
        val (startAt, endAt) = eventTimes(s("start")!!, s("end")!!, allDay)
        val forward = JsonObject(
            mapOf(
                "title" to JsonPrimitive(s("title")!!),
                "startAt" to JsonPrimitive(startAt),
                "endAt" to JsonPrimitive(endAt),
                "allDay" to JsonPrimitive(allDay),
                "location" to (s("location")?.let { JsonPrimitive(it) } ?: JsonNull),
                "note" to (s("note")?.let { JsonPrimitive(it) } ?: JsonNull),
                "color" to JsonPrimitive(s("color") ?: "BLUE"),
                "repeatRule" to JsonPrimitive(s("repeatRule") ?: "NONE"),
                "repeatUntil" to (s("repeatUntil")?.let { JsonPrimitive(it) } ?: JsonNull),
                "reminderMinutes" to JsonNull,
            ),
        )
        return when (val outcome = service.createEvent(clientKey, forward)) {
            is CreateOutcome.Created -> CreateEventResult.Created(outcome.id, outcome.fresh)
            is CreateOutcome.PassThrough ->
                if (outcome.status == 400) CreateEventResult.Rejected(outcome.body) else CreateEventResult.Other(outcome.status)
            CreateOutcome.Unreachable -> CreateEventResult.CalendarUnreachable
        }
    }

    override fun wakeGmail() {
        store.gmailWake?.invoke()
    }

    override suspend fun labelName(labelId: String): String? {
        val api = googleApi ?: throw ApprovalUnreachable()
        val result = try {
            api.userLabels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleAuthException) {
            api.invalidateAuth()
            throw ApprovalUnreachable()
        }
        if (result.failure != null || !result.complete) throw ApprovalUnreachable()
        return result.items.firstOrNull { it.id == labelId }?.name
    }

    override suspend fun approveResearch(id: String, a: JsonObject): ApprovalResult =
        researchApprover?.approve(id, a) ?: ApprovalResult(ApprovalAction.FAILED, RESEARCH_NOT_SET_UP_TEXT)
}

/** The answer of POST /v1/approvals/{id}: the HTTP status to send and the body text. */
data class ApprovalAnswer(val httpStatus: Int, val status: String, val message: String)

/** Runs "yes" or "no" for one approval through the executor and maps the result to the route's answer. */
suspend fun decideApproval(executor: ApprovalExecutor, sink: ApprovalSink, id: String, yes: Boolean, now: Long): ApprovalAnswer {
    if (!yes) {
        if (executor.reject(id)) return ApprovalAnswer(200, "REJECTED", "Rejected")
        // not decidable: say why, without changing anything
        val row = sink.approvalRow(id) ?: return ApprovalAnswer(404, "NOT_FOUND", "Approval not found")
        val why = if (row.status == APPROVAL_PENDING && isApprovalExpired(row.createdAt, now)) "This approval expired" else "Already ${row.status.lowercase()}"
        return ApprovalAnswer(409, "CONFLICT", why)
    }
    val r = executor.approve(id)
    return when (r.action) {
        ApprovalAction.DONE, ApprovalAction.ALREADY_DONE -> ApprovalAnswer(200, "DONE", r.message)
        ApprovalAction.FAILED -> ApprovalAnswer(200, "FAILED", r.message)
        ApprovalAction.NOT_PENDING, ApprovalAction.EXPIRED -> ApprovalAnswer(409, "CONFLICT", r.message)
        ApprovalAction.NOT_FOUND -> ApprovalAnswer(404, "NOT_FOUND", r.message)
        ApprovalAction.SERVER_UNREACHABLE -> ApprovalAnswer(503, "UNREACHABLE", r.message)
    }
}