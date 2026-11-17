package dev.tim.assistant.server.codex

import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.ai.tools.ApprovalResult
import dev.tim.assistant.ai.tools.approvalDecisionRow
import dev.tim.assistant.data.APPROVAL_APPROVED
import dev.tim.assistant.data.APPROVAL_DONE
import dev.tim.assistant.data.APPROVAL_PENDING
import dev.tim.assistant.data.isApprovalExpired
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Research
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.ai.jobs.SERVER_JOB_KINDS
import dev.tim.assistant.research.RESEARCH_ITEM_MAX
import dev.tim.assistant.research.RESEARCH_JOB_KIND
import dev.tim.assistant.research.RESEARCH_NUTRITION
import dev.tim.assistant.research.RESEARCH_DEFINITION
import dev.tim.assistant.research.RESEARCH_APP_CATEGORY
import dev.tim.assistant.research.RESEARCH_OTHER
import dev.tim.assistant.research.RESEARCH_QUESTION_JOB_PREFIX
import dev.tim.assistant.research.RESEARCH_QUESTION_MAX
import dev.tim.assistant.research.ResearchRow
import dev.tim.assistant.research.ResearchRunner
import dev.tim.assistant.research.expiresAtFor
import dev.tim.assistant.research.isValidItem
import dev.tim.assistant.research.questionKey
import dev.tim.assistant.research.researchId
import dev.tim.assistant.research.templateQuestion
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.chat.ResearchApprover
import dev.tim.assistant.server.chat.ResearchKind
import dev.tim.assistant.server.chat.ResearchOutcome
import dev.tim.assistant.server.chat.ServerWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The research and meal-lookup scheduler runs this often. */
const val CODEX_TICK_MS = 30_000L

/** Where Codex is installed on the HP when the config has no `codexPath`. */
const val DEFAULT_CODEX_PATH = "/usr/bin/codex"

/** The id prefix of the assistant message that carries a finished research answer into its chat. */
const val CODEX_MESSAGE_PREFIX = "codex-"
const val CODEX_POST_MAX_CHARS = 1_500
private const val RECONCILE_WINDOW_MS = 24L * 60 * 60 * 1000
private const val RECONCILE_MAX_PER_TICK = 20

private const val NOT_APPROVED = "Question was not approved"

private val sourcesSerializer = ListSerializer(String.serializer())

/** Encodes the source list the way the `research.sources` column stores it. */
fun encodeSources(sources: List<String>): String = Json.encodeToString(sourcesSerializer, sources)

/** The research row for [answer] (id `research:<category>:<hash>`), ready for a ServerWriter transaction. */
fun researchSyncRow(category: String, question: String, answer: ResearchRow, askedAt: Long): SyncRow =
    toSyncRow(
        Research(
            researchId(category, question), askedAt, 0, 0, "SYNCED", category, questionKey(question), question,
            answer.answerText, answer.answerJson, encodeSources(answer.sources), askedAt, expiresAtFor(category, askedAt),
        ),
    )

/**
 * Codex research on the HP. [codex] is the real runner (CodexResearch over a process, `config.codexPath ?: /usr/bin/codex`);
 * every call to it passes through [runner], which allows one Codex process at a time (its own Mutex, not the model's
 * generation lock). Synced RESEARCH ai_jobs are claimed in one writer transaction (claim identity = startedAt), run, and
 * finished in one transaction that also saves the research row. An approved `research_question` is queued with the
 * frozen question the moment Tim says yes; it runs only if that approval still holds exactly that question.
 */
class ServerCodex(
    private val sdb: ServerDb,
    private val writer: ServerWriter,
    codex: ResearchRunner,
    private val jobs: ServerJobs = ServerJobs(sdb, writer),
    private val clock: () -> Long = System::currentTimeMillis,
) : ResearchApprover {
    private val db get() = sdb.db
    private val codexLock = Mutex()

    /** The Codex runner every caller shares: one process at a time. */
    val runner: ResearchRunner = ResearchRunner { category, question -> codexLock.withLock { codex.ask(category, question) } }

    /** Resets stale server jobs, then runs the oldest queued RESEARCH job. True when a job was run. */
    suspend fun tick(): Boolean {
        jobs.resetStale(SERVER_JOB_KINDS, clock(), ::postFailure)
        reconcile()
        val queued = jobs.oldestQueued(RESEARCH_JOB_KIND) ?: return false
        val claim = jobs.claim(queued.id) ?: return false
        jobs.guarded(claim, ::postFailure) { run(claim) }
        return true
    }

    private suspend fun run(claim: Ai_job) {
        val category = jobInputText(claim, "category") ?: throw JobFailedNow("bad job input")
        val question = when (category) {
            RESEARCH_NUTRITION, RESEARCH_DEFINITION, RESEARCH_APP_CATEGORY -> {
                val item = jobInputText(claim, "item") ?: throw JobFailedNow("bad job input")
                if (!isValidItem(item)) throw JobFailedNow("Can't research '${item.take(RESEARCH_ITEM_MAX)}'")
                templateQuestion(category, item)
            }
            RESEARCH_OTHER -> approvedQuestion(claim)
            else -> throw JobFailedNow("bad job input")
        }
        val id = researchId(category, question)
        // looked at again right before the process: a row saved meanwhile is reused
        val cached = db.researchQueries.selectValid(id, clock()).executeAsOneOrNull()
        if (cached != null) {
            if (jobs.complete(claim, resultFor(id))) postToChat(claim, cached.answerText)
            return
        }
        val answer = runner.ask(category, question)
        val askedAt = clock()
        val saved = writer.transaction { tx ->
            val live = jobs.liveClaim(tx.db, claim) ?: return@transaction false
            tx.write(researchSyncRow(category, question, answer, askedAt))
            tx.write(toSyncRow(live.copy(status = "DONE", resultJson = resultFor(id), error = null)))
            true
        }
        if (saved) postToChat(claim, answer.answerText)
    }

    private suspend fun postFailure(job: Ai_job, error: String) {
        if (job.kind == RESEARCH_JOB_KIND) postToChat(job, error)
    }

    /**
     * Puts [text] into the chat that asked (the job's `chatId`) as one assistant message with the id `codex-<job id>`. Nothing
     * happens when the job has no chat, the chat is gone or archived, or the message exists already. Opens its own writer
     * transaction, so it is never called from inside another one.
     */
    private suspend fun postToChat(job: Ai_job, text: String) {
        val chatId = jobInputText(job, "chatId") ?: return
        val messageId = CODEX_MESSAGE_PREFIX + job.id
        writer.transaction { tx ->
            val q = tx.db.chatQueries
            val chat = q.selectChatById(chatId).executeAsOneOrNull()
            if (chat == null || chat.archived_at != null) return@transaction
            if (q.selectMessageById(messageId).executeAsOneOrNull() != null) return@transaction
            val after = q.selectLastMessagesByChat(chatId, 1L).executeAsList().firstOrNull()?.created_at ?: 0L
            q.insertMessage(messageId, chatId, "assistant", text.take(CODEX_POST_MAX_CHARS), null, null, maxOf(tx.now, after + 1))
            q.touchChat(tx.now, chatId)
        }
    }

    /**
     * Bounded catch-up for answers that never reached their chat (a restart between saving and posting): research jobs that
     * ended DONE or FAILED in the last 24 hours with a chat and no `codex-<id>` message get their saved answer or error posted.
     * Codex is never run again here.
     */
    private suspend fun reconcile() {
        val cutoff = clock() - RECONCILE_WINDOW_MS
        val lost = db.ai_jobQueries.selectAll().executeAsList()
            .filter {
                it.deleted == 0L && it.kind == RESEARCH_JOB_KIND && (it.status == "DONE" || it.status == "FAILED") && it.updatedAt >= cutoff &&
                    jobInputText(it, "chatId") != null && db.chatQueries.selectMessageById(CODEX_MESSAGE_PREFIX + it.id).executeAsOneOrNull() == null
            }
            .sortedBy { it.updatedAt }
            .take(RECONCILE_MAX_PER_TICK)
        for (job in lost) {
            val text = if (job.status == "FAILED") {
                job.error?.takeIf { it.isNotBlank() } ?: "Codex could not answer that."
            } else {
                val rid = jobInputText(job, "researchId") ?: continue
                db.researchQueries.selectById(rid).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.answerText ?: continue
            }
            try {
                postToChat(job, text)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // left for the next pass
            }
        }
    }

    /**
     * An OTHER job runs only when its id is `research_question:<approvalId>` and that approval's frozen args hold this very
     * question. An approval still PENDING is a plain failed attempt; REJECTED, expired, missing or different is a hard failure.
     */
    private fun approvedQuestion(job: Ai_job): String {
        val question = jobInputText(job, "question")?.trim() ?: throw JobFailedNow("bad job input")
        if (question.length !in 1..RESEARCH_QUESTION_MAX) throw JobFailedNow("bad job input")
        if (!job.id.startsWith(RESEARCH_QUESTION_JOB_PREFIX)) throw JobFailedNow(NOT_APPROVED)
        val approval = db.approvalQueries.selectById(job.id.removePrefix(RESEARCH_QUESTION_JOB_PREFIX)).executeAsOneOrNull()
            ?.takeIf { it.deleted == 0L } ?: throw JobFailedNow(NOT_APPROVED)
        if (approval.tool != "research_question") throw JobFailedNow(NOT_APPROVED)
        val frozen = try {
            ((Json.parseToJsonElement(approval.argsJson) as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (e: Exception) {
            null
        }
        if (frozen == null || frozen.trim() != question) throw JobFailedNow(NOT_APPROVED)
        return when (approval.status) {
            APPROVAL_APPROVED, APPROVAL_DONE -> question
            APPROVAL_PENDING -> throw IllegalStateException("approval not finished yet")
            else -> throw JobFailedNow(NOT_APPROVED)
        }
    }

    /**
     * Tim said yes to a `research_question` card: in ONE transaction re-read the approval (PENDING, not expired), then answer
     * from the cache, point at the job already running, or queue `research_question:<approvalId>` (insert-once) with the frozen
     * question, and mark the approval DONE.
     */
    override suspend fun approve(id: String, args: JsonObject, chatId: String?): ApprovalResult = submit(id, args, chatId).result

    override suspend fun submit(id: String, args: JsonObject, chatId: String?): ResearchOutcome {
        val failed = { ResearchOutcome(ResearchKind.FAILED, ApprovalResult(ApprovalAction.FAILED, "Research could not be queued")) }
        val question = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (question == null || question.length !in 1..RESEARCH_QUESTION_MAX) {
            return failed()
        }
        val researchId = researchId(RESEARCH_OTHER, question)
        return writer.transaction { tx ->
            val row = tx.db.approvalQueries.selectById(id).executeAsOneOrNull()
            fun no(action: ApprovalAction, message: String) = ResearchOutcome(ResearchKind.FAILED, ApprovalResult(action, message))
            when {
                row == null || row.deleted != 0L -> no(ApprovalAction.NOT_FOUND, "Approval not found")
                row.status != APPROVAL_PENDING -> no(ApprovalAction.NOT_PENDING, "Already ${row.status.lowercase()}")
                isApprovalExpired(row.createdAt, tx.now) -> no(ApprovalAction.EXPIRED, "This approval expired")
                else -> {
                    val cached = tx.db.researchQueries.selectValid(researchId, tx.now).executeAsOneOrNull()
                    val working = if (cached == null) activeJob(tx.db, researchId) else null
                    val kind = when {
                        cached != null -> ResearchKind.CACHED
                        working != null -> ResearchKind.WORKING
                        else -> ResearchKind.QUEUED
                    }
                    val message = cached?.answerText ?: working?.id ?: queueApproved(tx, id, question, researchId, chatId)
                    tx.write(approvalDecisionRow(row, APPROVAL_DONE, message, tx.now))
                    ResearchOutcome(kind, ApprovalResult(ApprovalAction.DONE, message))
                }
            }
        }
    }

    private fun queueApproved(tx: ServerWriter.Tx, approvalId: String, question: String, researchId: String, chatId: String?): String {
        val jobId = RESEARCH_QUESTION_JOB_PREFIX + approvalId
        if (tx.db.ai_jobQueries.selectById(jobId).executeAsOneOrNull() == null) {
            val input = buildJsonObject {
                put("category", RESEARCH_OTHER)
                put("question", question)
                put("researchId", researchId)
                if (chatId != null) put("chatId", chatId)
            }
            tx.write(toSyncRow(Ai_job(jobId, tx.now, 0, 0, "SYNCED", RESEARCH_JOB_KIND, input.toString(), "QUEUED", null, null, 0, tx.now, null)))
        }
        return jobId
    }

    /** A QUEUED or RUNNING RESEARCH job whose input names [researchId]. */
    private fun activeJob(db: AssistantDb, researchId: String): Ai_job? =
        db.ai_jobQueries.selectAll().executeAsList().firstOrNull { job ->
            job.deleted == 0L && job.kind == RESEARCH_JOB_KIND && (job.status == "QUEUED" || job.status == "RUNNING") &&
                jobInputText(job, "researchId") == researchId
        }

    private fun resultFor(id: String): String = buildJsonObject { put("researchId", id) }.toString()
}
