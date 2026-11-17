package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.ResearchToolHandler
import dev.tim.assistant.ai.tools.ToolMode
import dev.tim.assistant.ai.tools.ToolResult
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.ai.tools.WAITING_FOR_APPROVAL
import dev.tim.assistant.ai.tools.WriteHandler
import dev.tim.assistant.ai.tools.notOfferedText
import dev.tim.assistant.data.APPROVAL_PENDING
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.docBodyTooLong
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Dream
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.model.ActionRefDto
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.research.RESEARCH_OTHER
import dev.tim.assistant.research.RESEARCH_JOB_KIND
import dev.tim.assistant.research.researchId
import dev.tim.assistant.research.templateQuestion
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.chat.ladder.ReplyTemplates
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.validateRow
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.forms.parseAgorot
import dev.tim.assistant.ui.forms.parseEatenAt
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val NO_MATCH = "no match"
private val AUTO_TABLES = setOf("task", "checkin", "meal", "spending", "journal", "dream")

/** The furthest back, in days, that a past-day add may name (PLAN 20.5). */
const val OTHER_DAY_MAX_BACK = 60

/** The tools a chat turn may call that write. One instance serves one turn: [chatId] and [replyId] say where records go. */
@OptIn(ExperimentalUuidApi::class)
class ServerToolExecutor(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val writer: ServerWriter,
    private val chats: ChatStore,
    private val repo: Repository,
    private val read: ReadTools,
    private val clock: () -> Long,
    private val chatId: String,
    private val replyId: String,
) : WriteHandler {
    private val db get() = sdb.db

    /** true once this turn wrote a row or a card (a failed model call after that must not rerun the turn). */
    @Volatile
    var wrote: Boolean = false
        private set

    /** The research tools for this turn's [dev.tim.assistant.ai.tools.ToolBox]. */
    val research: ResearchToolHandler = ResearchToolHandler { name, args -> runResearch(name, args) }

    override suspend fun handle(name: String, args: JsonObject, allowed: Set<String>): ToolResult {
        if (name !in allowed) return ToolResult(notOfferedText(name))
        val mode = Tools.modeOf(name)
        if (mode != ToolMode.AUTO && mode != ToolMode.CARD) return ToolResult("Error: '$name' is not a write tool")
        // the arguments are checked here as well: this class must be safe whoever calls it
        val checked = when (val c = Tools.validate(name, args)) {
            is dev.tim.assistant.ai.tools.ToolCheck.Bad -> return ToolResult("Error: ${c.reason}")
            is dev.tim.assistant.ai.tools.ToolCheck.Ok -> c.args
        }
        val resolved = when (val r = resolveIds(name, checked)) {
            is Resolution.Failed -> return r.result
            is Resolution.Ok -> r
        }
        refusal(name, resolved.args)?.let { return ToolResult("Error: $it") }
        return if (mode == ToolMode.AUTO) auto(name, resolved.args) else card(name, resolved.args, resolved.names)
    }

    /**
     * A card (never a write) for a spending or a meal on a past day: add_spending or add_meal, valid arguments, a `date` before today
     * and at most [OTHER_DAY_MAX_BACK] days back, and for a meal also a `time`. Anything else is an error result and no card.
     */
    suspend fun proposeAdd(name: String, args: JsonObject): ToolResult {
        if (name != "add_spending" && name != "add_meal") return ToolResult("Error: '$name' cannot be proposed this way")
        val checked = when (val c = Tools.validate(name, args)) {
            is dev.tim.assistant.ai.tools.ToolCheck.Bad -> return ToolResult("Error: ${c.reason}")
            is dev.tim.assistant.ai.tools.ToolCheck.Ok -> c.args
        }
        fun s(key: String): String? = (checked[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.takeIf { it.isNotBlank() }
        val date = s("date") ?: return ToolResult("Error: a date is required")
        val day = try { java.time.LocalDate.parse(date) } catch (_: java.time.format.DateTimeParseException) { return ToolResult("Error: the date is not valid") }
        val today = java.time.LocalDate.parse(todayOf(clock()))
        if (!day.isBefore(today)) return ToolResult("Error: the date must be before today")
        if (day.isBefore(today.minusDays(OTHER_DAY_MAX_BACK.toLong()))) return ToolResult("Error: the date is too far back")
        if (name == "add_meal" && s("time") == null) return ToolResult("Error: a meal needs a time")
        return card(name, checked, emptyMap())
    }

    // ---- id checks and name resolution -------------------------------------------------------------------

    private sealed interface Resolution {
        data class Ok(val args: JsonObject, val names: Map<String, String>) : Resolution
        data class Failed(val result: ToolResult) : Resolution
    }

    private sealed interface Found {
        data class One(val id: String, val name: String) : Found
        data class Many(val choices: List<ChoiceDto>) : Found
        data object None : Found
    }

    /** An existing record's name for [kind]/[id], or null. */
    private fun nameOf(kind: String, id: String): String? = when (kind) {
        "course" -> repo.courseById(id)?.name
        "goal" -> repo.goalById(id)?.title
        "doc" -> repo.docById(id)?.title
        "task" -> repo.taskById(id)?.title
        "exam" -> repo.examById(id)?.title
        "mail" -> repo.unreadMail().firstOrNull { it.gmailId == id }?.subject
        "study_doc" -> repo.courses().firstNotNullOfOrNull { c -> repo.studyFiles(c.id).firstOrNull { it.id == id && it.deleted == 0L }?.name }
        "event" -> repo.calendarEvents().firstOrNull { it.id == id }?.title
        else -> null
    }

    private fun find(kind: String, value: String): Found {
        nameOf(kind, value)?.let { return Found.One(value, it) }
        val (_, matches) = read.find(buildJsonObject { put("kind", kind); put("query", value) })
        return when (matches.size) {
            0 -> Found.None
            1 -> Found.One(matches[0].id, matches[0].name)
            else -> Found.Many(matches)
        }
    }

    private fun article(kind: String) = kind.replace('_', ' ')

    /** Replaces names by ids in the arguments that point at another record; records are checked against the server database. */
    private fun resolveIds(name: String, a: JsonObject): Resolution {
        val fields = when (name) {
            "create_task" -> listOf(Triple("goalId", "goal", false))
            "add_exam", "update_exam" -> listOf(Triple("courseId", "course", false))
            "suggest_mail_action" -> listOf(Triple("gmailId", "mail", false))
            "draft_email" -> listOf(Triple("replyToGmailId", "mail", false))
            "create_doc" -> emptyList()
            else -> emptyList()
        }
        val out = a.toMutableMap()
        val names = HashMap<String, String>()
        for ((field, kind, _) in fields) {
            val value = (a[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            when (val f = find(kind, value)) {
                is Found.One -> { out[field] = JsonPrimitive(f.id); names[field] = f.name }
                is Found.Many -> return Resolution.Failed(manyText(kind, value, f.choices))
                Found.None -> return Resolution.Failed(ToolResult("Error: no ${article(kind)} named '${value.take(60)}' was found"))
            }
        }
        if (name == "queue_job") {
            val kindInput = (a["input"] as? JsonObject) ?: JsonObject(emptyMap())
            val input = kindInput.toMutableMap()
            for ((field, kind) in listOf("goalId" to "goal", "courseId" to "course", "fileId" to "study_doc", "gmailId" to "mail")) {
                val value = (kindInput[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                when (val f = find(kind, value)) {
                    is Found.One -> { input[field] = JsonPrimitive(f.id); names[field] = f.name }
                    is Found.Many -> return Resolution.Failed(manyText(kind, value, f.choices))
                    Found.None -> return Resolution.Failed(ToolResult("Error: no ${article(kind)} named '${value.take(60)}' was found"))
                }
            }
            val mealId = (kindInput["mealId"] as? JsonPrimitive)?.content
            if (mealId != null) {
                val meal = repo.mealById(mealId) ?: return Resolution.Failed(ToolResult("Error: no meal with that id - use get_log meals first"))
                names["mealId"] = meal.text
            }
            out["input"] = JsonObject(input)
        }
        return Resolution.Ok(JsonObject(out), names)
    }

    private fun manyText(kind: String, value: String, choices: List<ChoiceDto>): ToolResult = ToolResult(
        "${choices.size} ${article(kind)}s match '${value.take(60)}' - Tim is shown buttons to pick one, ask him which: " +
            choices.joinToString("; ") { "'${it.name.take(80)}'" },
        choices = choices,
    )

    /** Checks that need the database: the record an update changes must exist, a linked goal must exist, a note must not grow too long. */
    private fun refusal(name: String, a: JsonObject): String? {
        fun s(key: String): String? = (a[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = s("id").orEmpty()
        fun need(exists: Boolean, what: String): String? = if (exists) null else "no $what with id '${id.take(60)}'"
        return when (name) {
            "create_task" -> s("goalId")?.let { if (repo.goalById(it) == null) "no goal with id '${it.take(60)}'" else null }
            "update_task" -> need(repo.taskById(id) != null, "task")
            "update_goal" -> need(repo.goalById(id) != null, "goal")
            "update_doc" -> need(repo.docById(id) != null, "note") ?: docTooLong(id, a)
            "update_exam" -> need(repo.examById(id) != null, "exam")
            "update_meal" -> need(repo.mealById(id) != null, "meal")
            "update_spending" -> need(repo.spendingById(id) != null, "spending entry")
            "update_checkin" -> need(repo.checkinById(id) != null, "check-in")
            "update_journal" -> need(repo.journalById(id) != null, "journal entry")
            "add_exam" -> null
            else -> null
        }
    }

    private fun docTooLong(id: String, a: JsonObject): String? {
        val body = (a["body"] as? JsonPrimitive)?.content ?: return null
        val old = repo.docById(id)?.body.orEmpty()
        val result = if ((a["bodyMode"] as? JsonPrimitive)?.content == "APPEND") (if (old.isBlank()) body else old + "\n" + body) else body
        return if (docBodyTooLong(result)) "the note would be too long" else null
    }

    // ---- auto path ---------------------------------------------------------------------------------------

    private fun todayOf(t: Long): String = localDateOf(Instant.fromEpochMilliseconds(t))

    private class AutoRow(val row: SyncRow, val label: String)

    private fun buildAuto(name: String, a: JsonObject, t: Long): AutoRow? {
        fun s(key: String): String? = (a[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        val uid = Uuid.random().toString()
        return when (name) {
            "create_task" -> AutoRow(toSyncRow(Task("task:$uid", t, 0, 0, "SYNCED", s("title")!!, s("dueDate"), 0, null, s("goalId"))), "Task: ${s("title")!!.take(60)}")
            "add_journal" -> AutoRow(toSyncRow(Journal("journal:$uid", t, 0, 0, "SYNCED", s("text")!!, t)), "Journal entry")
            "add_spending" -> AutoRow(
                toSyncRow(Spending("spending:$uid", t, 0, 0, "SYNCED", parseAgorot(s("amount")!!) ?: return null, s("category")!!, s("note"), s("date") ?: todayOf(t))),
                "Spending ${s("amount")} ${s("category")}",
            )
            "add_meal" -> {
                val eatenAt = if (s("date") == null && s("time") == null) t else parseEatenAt(s("date") ?: todayOf(t), s("time") ?: "00:00") ?: return null
                AutoRow(
                    toSyncRow(Meal("meal:$uid", t, 0, 0, "SYNCED", s("type")!!, s("text")!!, eatenAt, localDateOf(Instant.fromEpochMilliseconds(eatenAt)), null, null, null, null, null)),
                    "${s("type")!!.lowercase().replaceFirstChar { it.uppercase() }}: ${s("text")!!.take(60)}",
                )
            }
            "add_checkin" -> {
                val date = s("date") ?: todayOf(t)
                AutoRow(
                    toSyncRow(
                        Checkin(
                            "checkin:$date", t, 0, 0, "SYNCED", date, s("mood")!!.toLong(), s("energy")!!.toLong(),
                            s("sleepQuality")!!.toLong(), s("stress")!!.toLong(), s("note"),
                        ),
                    ),
                    "Check-in $date",
                )
            }
            "add_dream" -> AutoRow(
                toSyncRow(Dream("dream:$uid", t, 0, 0, "SYNCED", s("date") ?: todayOf(t), s("text")!!, (s("mood") ?: "3").toLong(), if (s("lucid") == "true") 1L else 0L, "", null, null)),
                "Dream",
            )
            else -> null
        }
    }

    private suspend fun auto(name: String, a: JsonObject): ToolResult {
        val t = clock()
        if (name == "add_dream" && !dreamAllowed) return ToolResult(notOfferedText(name))
        // an existing check-in day is never overwritten by an auto write: ask for a card that changes it
        if (name == "add_checkin") {
            val date = (a["date"] as? JsonPrimitive)?.content ?: todayOf(t)
            if (repo.checkinById("checkin:$date") != null) {
                val change = buildJsonObject {
                    put("id", "checkin:$date")
                    for (k in listOf("mood", "energy", "sleepQuality", "stress", "note")) (a[k] as? JsonPrimitive)?.let { put(k, it) }
                }
                val r = card("update_checkin", change, emptyMap())
                return if (r.cardApprovalIds.isEmpty()) r
                else ToolResult("A check-in for $date already exists, so nothing was overwritten. A card to change it is shown; Tim says yes or no.", cardApprovalIds = r.cardApprovalIds)
            }
        }
        val built = buildAuto(name, a, t) ?: return ToolResult("Error: those values are not valid")
        validateRow(built.row)?.let { return ToolResult("Error: not allowed ($it)") }
        val actionId = Uuid.random().toString()
        val expiresAt = t + UNDO_WINDOW_MS
        writer.transaction { tx ->
            tx.write(built.row)
            val stamp = store.storedUpdatedAt(built.row.table, built.row.id)!!
            val stored = StoredAction(actionId, built.label, expiresAt, false, built.row.table, built.row.id, stamp, chatId, replyId)
            chats.recordOnReply(tx.db, chatId, replyId, tx.now) { it.copy(actions = it.actions + stored, dream = it.dream || name == "add_dream") }
        }
        wrote = true
        return ToolResult(
            "Saved: ${built.label}. It is already saved; Tim can undo it for 30 seconds.",
            actions = listOf(ActionRefDto(actionId, built.label, expiresAt, false)),
        )
    }

    /** add_dream is only offered when Tim's message mentions dreams; the runner sets this from the same check. */
    @Volatile
    var dreamAllowed: Boolean = false

    // ---- card path ---------------------------------------------------------------------------------------

    private suspend fun card(name: String, a: JsonObject, names: Map<String, String>): ToolResult {
        val summary = summaryOf(name, a, names)
        val approvalId = "appr:" + Uuid.random().toString()
        val row = toSyncRow(Approval(approvalId, 0, 0, 0, "SYNCED", name, a.toString(), summary, APPROVAL_PENDING, clock(), null))
        validateRow(row)?.let { return ToolResult("Error: not allowed ($it)") }
        writer.transaction { tx ->
            tx.write(row.copy(data = JsonObject(row.data + ("createdAt" to JsonPrimitive(tx.now)))))
            chats.recordOnReply(tx.db, chatId, replyId, tx.now) { it.copy(cards = it.cards + approvalId) }
        }
        wrote = true
        return ToolResult("$WAITING_FOR_APPROVAL - a card with Yes and No is shown to Tim", cardApprovalIds = listOf(approvalId))
    }

    private fun cut(text: String, max: Int): String {
        val one = text.replace('\r', ' ').replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max) + "..."
    }

    private fun recordTitle(tool: String, id: String): String = when (tool) {
        "update_task" -> repo.taskById(id)?.title
        "update_goal" -> repo.goalById(id)?.title
        "update_doc" -> repo.docById(id)?.title
        "update_meal" -> repo.mealById(id)?.text
        "update_spending" -> repo.spendingById(id)?.let { "${it.category} ${it.localDate}" }
        "update_checkin" -> repo.checkinById(id)?.localDate
        "update_journal" -> repo.journalById(id)?.text
        "update_exam" -> repo.examById(id)?.title
        else -> null
    }.orEmpty()

    /** The text of a card, built here from the checked arguments (never by the model). Record names, not ids, are shown. */
    private fun summaryOf(name: String, a: JsonObject, names: Map<String, String>): String {
        fun s(key: String): String = (a[key] as? JsonPrimitive)?.content.orEmpty()
        fun has(key: String) = (a[key] as? JsonPrimitive)?.content?.isNotEmpty() == true
        val text = when (name) {
            "create_doc" -> "Create note \"${s("title")}\" (${s("body").length} characters)"
            "create_goal" -> "Add goal \"${s("title")}\"" + (if (has("deadline")) ", deadline ${s("deadline")}" else "")
            "add_exam" -> "Add exam \"${s("title")}\" on ${s("localDate")}" + (if (has("time")) " at ${s("time")}" else "") + (names["courseId"]?.let { " ($it)" } ?: "")
            "queue_job" -> "Run AI job ${s("kind")}" + ((a["input"] as? JsonObject)?.let { input ->
                val parts = input.entries.map { (k, v) -> "${names[k] ?: (v as? JsonPrimitive)?.content.orEmpty()}" }.filter { it.isNotBlank() }
                if (parts.isEmpty()) "" else " (" + parts.joinToString(", ") { cut(it, 100) } + ")"
            } ?: "")
            "draft_email" -> "Save an email draft to ${s("to")}: \"${s("subject")}\" (nothing is sent)"
            "suggest_mail_action" -> {
                val what = if (s("action") == "ARCHIVE") "archive" else "label"
                "Gmail: $what the mail \"${cut(names["gmailId"] ?: s("gmailId"), 80)}\" - ${s("reason")}"
            }
            "set_app_rule" -> "Label ${s("matchType")} \"${s("pattern")}\" as ${s("label")} in screen time"
            "create_event" -> eventSummary(a)
            "research_question" -> "Ask Codex: \"${s("text")}\""
            "add_spending" -> "Add spending ${s("amount").removeSuffix(".00")} ₪, ${s("category").lowercase()}" +
                (if (has("note")) " (${cut(s("note"), 40)})" else "") + (if (has("date")) " for ${fullDayOf(s("date"))}" else "")
            "add_meal" -> (if (s("type").equals("snack", ignoreCase = true)) "Add snack: " else "Add meal: ") + cut(s("text"), 60) +
                (if (has("date")) " for ${fullDayOf(s("date"))}" + (if (has("time")) " at ${s("time")}" else "") else "")
            else -> if (name.startsWith("update_")) {
                val what = Tools.find(name)?.label ?: name
                val changes = a.entries.filter { it.key != "id" && it.key != "bodyMode" }
                    .joinToString("; ") { (k, v) -> "$k -> \"${cut((v as? JsonPrimitive)?.content.orEmpty(), 80)}\"" }
                "Change $what \"${cut(recordTitle(name, s("id")), 60)}\": $changes"
            } else name
        }
        return text.take(1_900)
    }

    private fun fullDayOf(date: String): String =
        ReplyTemplates.fullDay(date, java.time.LocalDate.parse(todayOf(clock())))

    private fun eventSummary(a: JsonObject): String {
        fun s(key: String): String? = (a[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        val allDay = (a["allDay"] as? JsonPrimitive)?.booleanOrNull == true
        val rule = s("repeatRule") ?: "NONE"
        val repeat = if (rule == "NONE") "" else ", repeats ${rule.lowercase()}" + (s("repeatUntil")?.let { " until $it" } ?: "")
        val place = s("location")?.let { " at $it" } ?: ""
        val range = if (allDay) "${s("start")} to ${s("end")} (all day)" else "${s("start")} to ${s("end")}"
        return "Add calendar event \"${s("title")}\" $range (Israel time)$place$repeat"
    }

    // ---- research ----------------------------------------------------------------------------------------

    /** The Ask Codex route: cards only `research_question` (never any other tool) and returns the new approval id, or null when no card was made. */
    suspend fun researchCard(question: String): String? =
        card("research_question", buildJsonObject { put("text", question.trim()) }, emptyMap()).cardApprovalIds.firstOrNull()

    private suspend fun runResearch(name: String, args: JsonObject): ToolResult {
        val t = clock()
        fun s(key: String): String = (args[key] as? JsonPrimitive)?.content.orEmpty()
        if (name == "research_question") {
            val q = s("text").trim()
            val id = researchId(RESEARCH_OTHER, q)
            repo.researchValid(id, t)?.let { return ToolResult("Saved answer: ${cut(it.answerText, 1_500)}", looked = "research memory") }
            return card("research_question", buildJsonObject { put("text", q) }, emptyMap())
        }
        if (name != "research") return ToolResult("Error: unknown research tool")
        val category = s("category")
        val item = s("item").trim()
        val question = templateQuestion(category, item)
        val id = researchId(category, question)
        repo.researchValid(id, t)?.let { return ToolResult("Saved answer: ${cut(it.answerText, 1_500)}", looked = "research") }
        repo.activeJobFor(RESEARCH_JOB_KIND, id)?.let { return ToolResult("Already looking that up with Codex; the answer will appear in research.", researchJobId = it.id) }
        val jobId = "ai_job:" + Uuid.random().toString()
        val input = buildJsonObject { put("category", category); put("item", item); put("researchId", id) }
        val row = SyncRow(
            "ai_job", jobId, t, false,
            JsonObject(
                mapOf(
                    "kind" to JsonPrimitive(RESEARCH_JOB_KIND), "inputJson" to JsonPrimitive(input.toString()), "status" to JsonPrimitive("QUEUED"),
                    "resultJson" to kotlinx.serialization.json.JsonNull, "error" to kotlinx.serialization.json.JsonNull, "attempts" to JsonPrimitive(0),
                    "createdAt" to JsonPrimitive(t), "startedAt" to kotlinx.serialization.json.JsonNull,
                ),
            ),
        )
        writer.write(row)
        wrote = true
        return ToolResult("Looking that up with Codex (it can take a minute); the answer will appear in research.", researchJobId = jobId)
    }

    @Suppress("unused")
    private fun unusedJson(e: JsonElement): Int? = (e as? JsonPrimitive)?.intOrNull
}

/** The outcome of an Undo request. */
data class UndoOutcome(val found: Boolean, val ok: Boolean, val message: String)

/**
 * Undo, decided by the server: the record must exist, the window must not be over, the row must still have the stamp the
 * auto write gave it (nobody changed it since) and must not already be removed. The row is then written again as a tombstone
 * through [ServerWriter], which always stamps it newer than the row, so it wins at sync.
 */
class UndoService(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val writer: ServerWriter,
    private val chats: ChatStore,
) {
    suspend fun undo(chatId: String, actionId: String): UndoOutcome {
        val located = chats.messages(chatId).firstNotNullOfOrNull { m ->
            parseExtras(m.card_json).actions.firstOrNull { it.actionId == actionId }?.let { m.id }
        } ?: return UndoOutcome(false, false, "No such action")
        return writer.transaction { tx ->
            val message = tx.db.chatQueries.selectMessageById(located).executeAsOneOrNull() ?: return@transaction UndoOutcome(false, false, "No such action")
            val extras = parseExtras(message.card_json)
            val action = extras.actions.firstOrNull { it.actionId == actionId } ?: return@transaction UndoOutcome(false, false, "No such action")
            if (action.undone) return@transaction UndoOutcome(true, true, "Already undone")
            if (tx.now > action.expiresAt) return@transaction UndoOutcome(true, false, "Too late to undo")
            if (action.table !in AUTO_TABLES) return@transaction UndoOutcome(true, false, "That cannot be undone")
            val row = rowOf(tx.db, action.table, action.rowId)
                ?: return@transaction UndoOutcome(true, false, "It is already gone")
            if (row.deleted) return@transaction UndoOutcome(true, false, "It was already removed")
            if (row.updatedAt != action.createdUpdatedAt) return@transaction UndoOutcome(true, false, "It changed since, so I left it alone")
            tx.write(row.copy(deleted = true))
            val updated = extras.copy(actions = extras.actions.map { if (it.actionId == actionId) it.copy(undone = true) else it })
            tx.db.chatQueries.updateMessageCard(updated.encode(), located)
            UndoOutcome(true, true, "Undone")
        }
    }

    private fun rowOf(db: dev.tim.assistant.db.AssistantDb, table: String, id: String): SyncRow? = when (table) {
        "task" -> db.taskQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        "checkin" -> db.checkinQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        "meal" -> db.mealQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        "spending" -> db.spendingQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        "journal" -> db.journalQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        "dream" -> db.dreamQueries.selectById(id).executeAsOneOrNull()?.let { toSyncRow(it) }
        else -> null
    }
}
