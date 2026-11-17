package dev.tim.assistant.ai.tools

import dev.tim.assistant.data.SPENDING_CATEGORIES
import dev.tim.assistant.time.JERUSALEM
import dev.tim.assistant.ui.forms.formatAgorot
import dev.tim.assistant.ui.forms.parseAgorot
import kotlin.time.Instant
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.MATCH_EXE
import dev.tim.assistant.usage.MATCH_PACKAGE
import dev.tim.assistant.usage.MATCH_TITLE
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import dev.tim.assistant.research.isValidItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one typed registry of the chat's tools (Phase 13): the schema the model sees, the argument validator and the
 * label shown as "Looked at: ...". The chat, the probe and the dream-wall test all read this list.
 * Executors are separate: read tools run at once, write tools only ever create a PENDING approval.
 */
enum class ToolKind { READ, WRITE, RESEARCH }

/**
 * How a tool reaches Tim's data on the server (Phase 17): READ answers from data, AUTO writes at once (Tim can undo), CARD only
 * creates an approval card he must say yes to.
 */
enum class ToolMode { READ, AUTO, CARD }

/** The outcome of checking a call's arguments. [Ok.args] is trimmed and has defaults filled in. */
sealed interface ToolCheck {
    data class Ok(val args: JsonObject) : ToolCheck
    data class Bad(val reason: String) : ToolCheck
}

class ToolSpec(
    val name: String,
    val kind: ToolKind,
    /** Short noun phrase for "Looked at: <labels>" (read tools) and approval cards. */
    val label: String,
    val description: String,
    /** JSON-schema `parameters` object. */
    val parameters: JsonObject,
    private val check: (JsonObject) -> ToolCheck,
) {
    fun validate(args: JsonObject): ToolCheck = check(args)

    /** The Ollama tool definition. */
    fun definition(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }
}

object Tools {
    const val MAX_DAYS = 30
    const val DEFAULT_DAYS = 7
    const val TOOL_OUTPUT_MAX_CHARS = 4_000

    val LOG_KINDS = listOf("checkins", "spending", "meals", "journal", "health")
    val JOB_KINDS = listOf("GOAL_STEPS", "IDEAL_WEEK", "STUDY_SET", "STUDY_GUIDE", "QUIZ", "APP_LABELS", "MAIL_DRAFT", "MAIL_TRIAGE", "HW_EXPLAIN", "MEAL_LOOKUP")
    val MEAL_TYPES = listOf("MEAL", "SNACK")
    val GOAL_STATUSES = listOf("ACTIVE", "DONE", "DROPPED")
    val DOC_MODES = listOf("REPLACE", "APPEND")
    const val MEAL_MAX = 1_000
    const val JOURNAL_MAX = 10_000
    val MATCH_TYPES = listOf(MATCH_PACKAGE, MATCH_EXE, MATCH_TITLE)
    val APP_LABELS = Label.entries.filter { it != Label.UNSORTED }.map { it.name }
    val REPEAT_RULES = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY")
    val EVENT_COLORS = listOf("BLUE", "GREEN", "RED", "ORANGE", "PURPLE", "GRAY")
    val RESEARCH_CATEGORIES = listOf("NUTRITION", "DEFINITION", "APP_CATEGORY")
    val MAIL_ACTIONS = listOf("LABEL", "ARCHIVE")

    val read: List<ToolSpec> = buildReadTools()
    val write: List<ToolSpec> = buildWriteTools()
    val research: List<ToolSpec> = buildResearchTools()
    val all: List<ToolSpec> = read + write + research

    private val byName = all.associateBy { it.name }

    /** Every registered tool name. */
    val allNames: Set<String> = byName.keys

    /** Write and research tools that run at once (the chat shows Undo); every other write tool makes an approval card. */
    private val autoTools = setOf("create_task", "add_checkin", "add_meal", "add_spending", "add_journal", "add_dream", "research")

    /** The name of the dream tool, offered only when Tim's message mentions dreams. */
    const val DREAM_TOOL = "add_dream"

    fun find(name: String): ToolSpec? = byName[name]

    /** READ, AUTO or CARD for the registered tool [name]; null for an unknown name. */
    fun modeOf(name: String): ToolMode? {
        val spec = byName[name] ?: return null
        return when {
            spec.kind == ToolKind.READ -> ToolMode.READ
            name in autoTools -> ToolMode.AUTO
            else -> ToolMode.CARD
        }
    }

    /** The same definitions as a list. */
    fun ollamaToolList(includeDream: Boolean = true): List<JsonObject> =
        all.filter { includeDream || it.name != DREAM_TOOL }.map { it.definition() }

    /** Validates a call by name; an unknown tool is Bad. */
    fun validate(name: String, args: JsonObject): ToolCheck =
        find(name)?.validate(args) ?: ToolCheck.Bad("unknown tool '$name'")

    // ---- read tools -------------------------------------------------------------------------------

    private fun buildReadTools(): List<ToolSpec> = listOf(
        tool("get_tasks", ToolKind.READ, "tasks", "List Tim's open tasks and recently finished ones.", schema()) { Args(it).done() },
        tool("get_goals", ToolKind.READ, "goals", "List Tim's goals with status, deadline and task progress.", schema()) { Args(it).done() },
        tool(
            "search_docs", ToolKind.READ, "docs",
            "Search Tim's notes and documents by words. Returns titles and short excerpts; use read_doc for the full text.",
            schema("query" to str("Words to search for"), required = listOf("query")),
        ) { a -> Args(a).run { val q = text("query", 1, 200); done("query" to q) } },
        tool(
            "read_doc", ToolKind.READ, "doc",
            "Read the full text of one document by its id (ids come from search_docs).",
            schema("id" to str("The document id"), required = listOf("id")),
        ) { a -> Args(a).run { val id = text("id", 1, 200); done("id" to id) } },
        tool(
            "get_study", ToolKind.READ, "study",
            "List study courses, files, flashcard counts and quiz scores. Optionally for one course.",
            schema("courseId" to str("Course id (optional)")),
        ) { a -> Args(a).run { val c = optText("courseId", 200); done("courseId" to c) } },
        tool("get_exams", ToolKind.READ, "exams", "List upcoming exams with dates.", schema()) { Args(it).done() },
        tool(
            "get_screentime", ToolKind.READ, "screen time",
            "Screen time per app and per label (productive/unproductive/neutral) for the last days, phone and laptop.",
            schema("days" to int("How many days back, 1 to 30 (default 7)", 1, MAX_DAYS)),
        ) { a -> Args(a).run { val d = days(); done("days" to d) } },
        tool(
            "get_listening", ToolKind.READ, "music",
            "Music Tim listened to: top tracks and artists for the last days.",
            schema("days" to int("How many days back, 1 to 30 (default 7)", 1, MAX_DAYS)),
        ) { a -> Args(a).run { val d = days(); done("days" to d) } },
        tool("get_unread_mail", ToolKind.READ, "mail", "List unread emails (sender, subject, short snippet).", schema()) { Args(it).done() },
        tool("get_hw_checks", ToolKind.READ, "hardware checks", "List the saved laptop hardware health checks.", schema()) { Args(it).done() },
        tool(
            "find", ToolKind.READ, "search",
            "Find one of Tim's things by its name: a course, study file, goal, task, note, calendar event, exam or unread mail. " +
                "Use it whenever Tim names something instead of giving an id. Returns up to 5 matches; when several match, Tim picks one.",
            schema(
                "kind" to enumProp("What to look for (optional)", FIND_KINDS), "query" to str("Part of the name"),
                required = listOf("query"),
            ),
        ) { a -> Args(a).run { val k = optOneOf("kind", FIND_KINDS); val q = text("query", 1, 200); done("kind" to k, "query" to q) } },
        tool(
            "get_research", ToolKind.READ, "research memory",
            "Search the saved research answers (earlier lookups such as calories or definitions).",
            schema("query" to str("Words to search for"), required = listOf("query")),
        ) { a -> Args(a).run { val q = text("query", 1, 200); done("query" to q) } },
        tool(
            "get_log", ToolKind.READ, "log",
            "Read Tim's own log entries: check-ins, spending, meals, journal or health days.",
            schema(
                "kind" to enumProp("Which log", LOG_KINDS),
                "days" to int("How many days back, 1 to 30 (default 7)", 1, MAX_DAYS),
                required = listOf("kind"),
            ),
        ) { a -> Args(a).run { val k = oneOf("kind", LOG_KINDS); val d = days(); done("kind" to k, "days" to d) } },
        tool(
            "get_events", ToolKind.READ, "calendar",
            "List calendar events from today for the next days (use for 'what is on my calendar').",
            schema("days" to int("How many days ahead, 1 to 30 (default 7)", 1, MAX_DAYS)),
        ) { a -> Args(a).run { val d = days(); done("days" to d) } },
    )

    // ---- write tools (each only proposes: one PENDING approval) -----------------------------------

    private fun buildWriteTools(): List<ToolSpec> = listOf(
        tool(
            "create_task", ToolKind.WRITE, "task",
            "Add a new task for Tim. It is saved at once and he can undo it.",
            schema(
                "title" to str("Task title"), "dueDate" to str("Due date yyyy-MM-dd (optional)"),
                "goalId" to str("Goal id to link (optional)"),
                required = listOf("title"),
            ),
        ) { a ->
            Args(a).run {
                val t = text("title", 1, 200); val d = optDate("dueDate"); val g = optText("goalId", 200)
                done("title" to t, "dueDate" to d, "goalId" to g)
            }
        },
        tool(
            "create_doc", ToolKind.WRITE, "doc",
            "Propose a new note/document. Tim must approve it before it is saved.",
            schema(
                "title" to str("Document title"), "body" to str("Document text (markdown)"),
                "folderId" to str("Folder id (optional)"),
                required = listOf("title", "body"),
            ),
        ) { a ->
            Args(a).run {
                val t = text("title", 1, 200); val b = text("body", 1, 15_000); val f = optText("folderId", 200)
                done("title" to t, "body" to b, "folderId" to f)
            }
        },
        tool(
            "create_goal", ToolKind.WRITE, "goal",
            "Propose a new goal. Tim must approve it before it is saved.",
            schema(
                "title" to str("Goal title"), "why" to str("Why it matters (optional)"),
                "deadline" to str("Deadline yyyy-MM-dd (optional)"),
                required = listOf("title"),
            ),
        ) { a ->
            Args(a).run {
                val t = text("title", 1, 200); val w = optText("why", 1_000); val d = optDate("deadline")
                done("title" to t, "why" to w, "deadline" to d)
            }
        },
        tool(
            "add_exam", ToolKind.WRITE, "exam",
            "Propose adding an exam. Tim must approve it before it is saved.",
            schema(
                "title" to str("Exam title"), "localDate" to str("Date yyyy-MM-dd"),
                "time" to str("Start time HH:mm (optional)"), "courseId" to str("Course id (optional)"),
                required = listOf("title", "localDate"),
            ),
        ) { a ->
            Args(a).run {
                val t = text("title", 1, 200); val d = date("localDate"); val tm = optTime("time"); val c = optText("courseId", 200)
                done("title" to t, "localDate" to d, "time" to tm, "courseId" to c)
            }
        },
        tool(
            "queue_job", ToolKind.WRITE, "AI job",
            "Propose running one of Wally's background AI jobs. Tim must approve it first. Kinds and their input: " +
                "GOAL_STEPS {goalId}, IDEAL_WEEK {}, STUDY_SET {fileId}, STUDY_GUIDE {courseId}, QUIZ {courseId}, APP_LABELS {}, " +
                "MAIL_DRAFT {gmailId or null, instruction}, MAIL_TRIAGE {}, HW_EXPLAIN {checkId, problem}, MEAL_LOOKUP {mealId}. " +
                "goalId, courseId, fileId and mealId may be given as the item's name (or an id from find): the server resolves it.",
            schema(
                "kind" to enumProp("Job kind", JOB_KINDS),
                "input" to objProp("Input object for that kind"),
                required = listOf("kind", "input"),
            ),
        ) { a ->
            Args(a).run {
                val k = oneOf("kind", JOB_KINDS)
                val input = obj("input")
                done("kind" to k, "input" to checkJobInput(k, input))
            }
        },
        tool(
            "draft_email", ToolKind.WRITE, "email draft",
            "Propose an email draft. It is only saved as a draft for Tim to edit and send himself; nothing is sent.",
            schema(
                "to" to str("Recipient address"), "subject" to str("Subject"), "body" to str("Email text"),
                "replyToGmailId" to str("Gmail id of the mail being answered (optional)"),
                required = listOf("to", "subject", "body"),
            ),
        ) { a ->
            Args(a).run {
                val to = email("to"); val s = text("subject", 1, 300); val b = text("body", 1, 15_000); val r = optText("replyToGmailId", 200)
                done("to" to to, "subject" to s, "body" to b, "replyToGmailId" to r)
            }
        },
        tool(
            "suggest_mail_action", ToolKind.WRITE, "mail suggestion",
            "Propose labelling or archiving one email (gmailId from find or get_unread_mail). Tim approves on the card; the label/archive then happens in Gmail.",
            schema(
                "gmailId" to str("Gmail id of the mail"), "action" to enumProp("What to do", MAIL_ACTIONS),
                "labelId" to str("Label id (needed for LABEL)"), "reason" to str("Why"),
                required = listOf("gmailId", "action", "reason"),
            ),
        ) { a ->
            Args(a).run {
                val g = text("gmailId", 1, 200); val act = oneOf("action", MAIL_ACTIONS)
                val l = optText("labelId", 200); val r = text("reason", 1, 500)
                if (act == "LABEL" && l == null) fail("labelId is required for LABEL")
                done("gmailId" to g, "action" to act, "labelId" to (if (act == "LABEL") l else null), "reason" to r)
            }
        },
        tool(
            "set_app_rule", ToolKind.WRITE, "app rule",
            "Propose a rule that labels an app as productive, unproductive or neutral in screen time. PACKAGE for phone apps, " +
                "EXE for laptop programs, TITLE_CONTAINS for window titles.",
            schema(
                "matchType" to enumProp("Match type", MATCH_TYPES), "pattern" to str("App package, program name or title text"),
                "label" to enumProp("Label", APP_LABELS),
                required = listOf("matchType", "pattern", "label"),
            ),
        ) { a ->
            Args(a).run {
                val m = oneOf("matchType", MATCH_TYPES); val p = text("pattern", 1, 200); val l = oneOf("label", APP_LABELS)
                done("matchType" to m, "pattern" to p.lowercase(), "label" to l)
            }
        },
        tool(
            "create_event", ToolKind.WRITE, "calendar event",
            "Propose a calendar event (Jerusalem time). start and end are yyyy-MM-ddTHH:mm, or yyyy-MM-dd when allDay is true. " +
                "A timed event needs an end or durationMinutes: if Tim did not say how long it lasts, ask him; never assume a length. " +
                "Tim must approve it before it is created.",
            schema(
                "title" to str("Event title"), "start" to str("Start, e.g. 2026-11-12T09:00"), "end" to str("End, same format (or give durationMinutes)"),
                "durationMinutes" to int("Length in minutes, instead of end (1 to 1440)", 1, 1_440),
                "allDay" to boolProp("true for an all-day event"),
                "repeatRule" to enumProp("Repeat (default NONE)", REPEAT_RULES), "repeatUntil" to str("Last repeat date yyyy-MM-dd (optional)"),
                "note" to str("Note (optional)"), "location" to str("Location (optional)"), "color" to enumProp("Colour (optional)", EVENT_COLORS),
                required = listOf("title", "start"),
            ),
        ) { a -> Args(a).run { checkEvent() } },
    ) + buildLogTools() + buildUpdateTools()

    // ---- add_* tools for Tim's own logs (same rules as the forms) ----------------------------------

    private fun buildLogTools(): List<ToolSpec> = listOf(
        tool(
            "add_meal", ToolKind.WRITE, "meal",
            "Add a meal or snack to Tim's food log. It is saved at once and he can undo it. type, text are required; date and time " +
                "(Jerusalem) default to now only when Tim did not mention them.",
            schema(
                "type" to enumProp("MEAL or SNACK", MEAL_TYPES), "text" to str("What he ate"),
                "date" to str("yyyy-MM-dd (optional)"), "time" to str("HH:mm (optional)"),
                required = listOf("type", "text"),
            ),
        ) { a ->
            Args(a).run {
                val ty = oneOf("type", MEAL_TYPES); val tx = text("text", 1, MEAL_MAX); val d = optDate("date"); val tm = optTime("time")
                if (d != null && tm == null) missing("time")
                done("type" to ty, "text" to tx, "date" to d, "time" to tm)
            }
        },
        tool(
            "add_spending", ToolKind.WRITE, "spending",
            "Add a spending entry. It is saved at once and Tim can undo it. amount is in shekels like 12.50; category is one of " +
                SPENDING_CATEGORIES.joinToString() + ". date defaults to today.",
            schema(
                "amount" to str("Amount in shekels, e.g. 12.50"), "category" to enumProp("Category", SPENDING_CATEGORIES),
                "note" to str("Note (optional)"), "date" to str("yyyy-MM-dd (optional)"),
                required = listOf("amount", "category"),
            ),
        ) { a ->
            Args(a).run {
                val am = amount("amount"); val c = oneOf("category", SPENDING_CATEGORIES); val n = optText("note", 1_000); val d = optDate("date")
                done("amount" to am, "category" to c, "note" to n, "date" to d)
            }
        },
        tool(
            "add_checkin", ToolKind.WRITE, "check-in",
            "Add a check-in: mood, energy, sleepQuality and stress, each 1 (bad) to 5 (good; for stress 5 is very stressed). " +
                "It is saved at once and Tim can undo it. date defaults to today.",
            schema(
                "mood" to int("1 to 5", 1, 5), "energy" to int("1 to 5", 1, 5), "sleepQuality" to int("1 to 5", 1, 5),
                "stress" to int("1 to 5", 1, 5), "note" to str("Note (optional)"), "date" to str("yyyy-MM-dd (optional)"),
                required = listOf("mood", "energy", "sleepQuality", "stress"),
            ),
        ) { a ->
            Args(a).run {
                val m = intIn("mood", 1, 5); val e = intIn("energy", 1, 5); val s = intIn("sleepQuality", 1, 5); val st = intIn("stress", 1, 5)
                val n = optText("note", 1_000); val d = optDate("date")
                done("mood" to m, "energy" to e, "sleepQuality" to s, "stress" to st, "note" to n, "date" to d)
            }
        },
        tool(
            "add_journal", ToolKind.WRITE, "journal entry",
            "Add a journal entry. It is saved at once and Tim can undo it.",
            schema("text" to str("The entry"), required = listOf("text")),
        ) { a -> Args(a).run { val t = text("text", 1, JOURNAL_MAX); done("text" to t) } },
        tool(
            "add_dream", ToolKind.WRITE, "dream",
            "Save a dream Tim just told you (only when he is talking about a dream). It is saved at once and he can undo it. " +
                "mood is 1 (bad) to 5 (good), default 3; lucid is true when he knew he was dreaming.",
            schema(
                "text" to str("The dream"), "mood" to int("1 to 5 (optional)", 1, 5), "lucid" to boolProp("true if lucid (optional)"),
                "date" to str("yyyy-MM-dd (optional, default today)"),
                required = listOf("text"),
            ),
        ) { a ->
            Args(a).run {
                val tx = text("text", 1, JOURNAL_MAX); val m = optIntIn("mood", 1, 5); val l = optBool("lucid"); val d = optDate("date")
                done("text" to tx, "mood" to m, "lucid" to l, "date" to d)
            }
        },
    )

    // ---- update_* tools: change one existing record by id; given fields replace, the others stay ----

    private fun buildUpdateTools(): List<ToolSpec> = listOf(
        tool(
            "update_task", ToolKind.WRITE, "task change",
            "Propose changing an existing task (id from get_tasks). Only the fields you give change. Tim must approve it first.",
            schema(
                "id" to str("Task id"), "title" to str("New title"), "dueDate" to str("New due date yyyy-MM-dd"),
                "done" to boolProp("true to mark done, false to reopen"),
                required = listOf("id"),
            ),
        ) { a ->
            Args(a).run {
                val id = text("id", 1, 200); val t = optText("title", 200); val d = optDate("dueDate"); val dn = optBool("done")
                requireChange(t, d, dn)
                done("id" to id, "title" to t, "dueDate" to d, "done" to dn)
            }
        },
        tool(
            "update_goal", ToolKind.WRITE, "goal change",
            "Propose changing an existing goal (id from get_goals). Only the fields you give change. Tim must approve it first.",
            schema(
                "id" to str("Goal id"), "title" to str("New title"), "why" to str("New why"),
                "deadline" to str("New deadline yyyy-MM-dd"), "status" to enumProp("New status", GOAL_STATUSES),
                required = listOf("id"),
            ),
        ) { a ->
            Args(a).run {
                val id = text("id", 1, 200); val t = optText("title", 200); val w = optText("why", 1_000); val d = optDate("deadline")
                val s = optOneOf("status", GOAL_STATUSES)
                requireChange(t, w, d, s)
                done("id" to id, "title" to t, "why" to w, "deadline" to d, "status" to s)
            }
        },
        tool(
            "update_doc", ToolKind.WRITE, "note change",
            "Propose changing an existing note (id from search_docs). Give a new title and/or body; bodyMode APPEND adds the body " +
                "after the old text, REPLACE (default) replaces it. Tim must approve it first.",
            schema(
                "id" to str("Document id"), "title" to str("New title"), "body" to str("New text or text to append"),
                "bodyMode" to enumProp("REPLACE or APPEND", DOC_MODES),
                required = listOf("id"),
            ),
        ) { a ->
            Args(a).run {
                val id = text("id", 1, 200); val t = optText("title", 200); val b = optText("body", 15_000)
                requireChange(t, b)
                val mode = if (b == null) null else oneOfOr("bodyMode", DOC_MODES, "REPLACE")
                done("id" to id, "title" to t, "body" to b, "bodyMode" to mode)
            }
        },
        tool(
            "update_meal", ToolKind.WRITE, "meal change",
            "Propose changing a logged meal or snack (id from get_log meals). Only the fields you give change. Tim must approve it first.",
            schema(
                "id" to str("Meal id"), "type" to enumProp("MEAL or SNACK", MEAL_TYPES), "text" to str("New text"),
                "date" to str("New date yyyy-MM-dd"), "time" to str("New time HH:mm"),
                required = listOf("id"),
            ),
        ) { a ->
            Args(a).run {
                val id = text("id", 1, 200); val ty = optOneOf("type", MEAL_TYPES); val tx = optText("text", MEAL_MAX)
                val d = optDate("date"); val tm = optTime("time")
                requireChange(ty, tx, d, tm)
                done("id" to id, "type" to ty, "text" to tx, "date" to d, "time" to tm)
            }
        },
        tool(
            "update_spending", ToolKind.WRITE, "spending change",
            "Propose changing a spending entry (id from get_log spending). Only the fields you give change. Tim must approve it first.",
            schema(
                "id" to str("Spending id"), "amount" to str("New amount in shekels, e.g. 12.50"),
                "category" to enumProp("New category", SPENDING_CATEGORIES), "note" to str("New note"),
                "date" to str("New date yyyy-MM-dd"),
                required = listOf("id"),
            ),
        ) { a ->
            Args(a).run {
                val id = text("id", 1, 200); val am = optAmount("amount"); val c = optOneOf("category", SPENDING_CATEGORIES)
                val n = optText("note", 1_000); val d = optDate("date")
                requireChange(am, c, n, d)
                done("id" to id, "amount" to am, "category" to c, "note" to n, "date" to d)
            }
        },
        tool(
            "update_checkin", ToolKind.WRITE, "check-in change",
            "Propose changing a check-in (id from get_log checkins). Only the fields you give change. Tim must approve it first.",
            schema(
                "id" to str("Check-in id"), "mood" to int("1 to 5", 1, 5), "energy" to int("1 to 5", 1, 5),
                "sleepQuality" to int("1 to 5", 1, 5), "stress" to int("1 to 5", 1, 5), "note" to str("New note"),
                required = listOf("id"),
            ),
        ) { a ->
            Args(a).run {
                val id = text("id", 1, 200); val m = optIntIn("mood", 1, 5); val e = optIntIn("energy", 1, 5)
                val s = optIntIn("sleepQuality", 1, 5); val st = optIntIn("stress", 1, 5); val n = optText("note", 1_000)
                requireChange(m, e, s, st, n)
                done("id" to id, "mood" to m, "energy" to e, "sleepQuality" to s, "stress" to st, "note" to n)
            }
        },
        tool(
            "update_journal", ToolKind.WRITE, "journal change",
            "Propose replacing the text of a journal entry (id from get_log journal). Tim must approve it first.",
            schema("id" to str("Journal id"), "text" to str("New text"), required = listOf("id", "text")),
        ) { a -> Args(a).run { val id = text("id", 1, 200); val t = text("text", 1, JOURNAL_MAX); done("id" to id, "text" to t) } },
        tool(
            "update_exam", ToolKind.WRITE, "exam change",
            "Propose changing an exam (id from get_exams). Only the fields you give change. Tim must approve it first.",
            schema(
                "id" to str("Exam id"), "title" to str("New title"), "localDate" to str("New date yyyy-MM-dd"),
                "time" to str("New start time HH:mm"), "courseId" to str("New course id"),
                required = listOf("id"),
            ),
        ) { a ->
            Args(a).run {
                val id = text("id", 1, 200); val t = optText("title", 200); val d = optDate("localDate")
                val tm = optTime("time"); val c = optText("courseId", 200)
                requireChange(t, d, tm, c)
                done("id" to id, "title" to t, "localDate" to d, "time" to tm, "courseId" to c)
            }
        },
    )

    // ---- research tools ---------------------------------------------------------------------------

    private fun buildResearchTools(): List<ToolSpec> = listOf(
        tool(
            "research", ToolKind.RESEARCH, "research",
            "Look up a simple fact through Codex: calories/nutrition of a food (NUTRITION), the meaning of a word (DEFINITION) " +
                "or what kind of app something is (APP_CATEGORY). item is just the food, word or app name.",
            schema(
                "category" to enumProp("Category", RESEARCH_CATEGORIES), "item" to str("Food, word or app name"),
                required = listOf("category", "item"),
            ),
        ) { a ->
            Args(a).run {
                val c = oneOf("category", RESEARCH_CATEGORIES); val i = text("item", 1, 60)
                if (!isResearchItem(i)) fail("item is not a valid lookup name")
                done("category" to c, "item" to i)
            }
        },
        tool(
            "research_question", ToolKind.RESEARCH, "research question",
            "Ask Codex any other question that needs the internet. Tim sees the exact question and must approve it first.",
            schema("text" to str("The question"), required = listOf("text")),
        ) { a -> Args(a).run { val t = text("text", 1, 500); done("text" to t) } },
    )

    // ---- shared checks ----------------------------------------------------------------------------

    private fun isResearchItem(item: String): Boolean = isValidItem(item)

    private fun Args.checkJobInput(kind: String, input: JsonObject): JsonObject {
        val sub = Args(input)
        return when (kind) {
            "GOAL_STEPS" -> sub.run { val g = text("goalId", 1, 200); done("goalId" to g).args }
            "STUDY_SET" -> sub.run { val f = text("fileId", 1, 200); done("fileId" to f).args }
            "STUDY_GUIDE" -> sub.run { val c = text("courseId", 1, 200); done("courseId" to c).args }
            "QUIZ" -> sub.run { val c = text("courseId", 1, 200); done("courseId" to c).args }
            "HW_EXPLAIN" -> sub.run { val c = text("checkId", 1, 200); val p = text("problem", 1, 2_000); done("checkId" to c, "problem" to p).args }
            "MEAL_LOOKUP" -> sub.run { val m = text("mealId", 1, 200); done("mealId" to m).args }
            "MAIL_DRAFT" -> sub.run {
                val g = optText("gmailId", 200); val i = text("instruction", 1, 2_000)
                done("gmailId" to g, "instruction" to i).args
            }
            "IDEAL_WEEK", "APP_LABELS", "MAIL_TRIAGE" -> JsonObject(emptyMap())
            else -> fail("job kind '$kind' is not allowed")
        }
    }

    /** [start] (yyyy-MM-ddTHH:mm, Jerusalem) plus [minutes], in the same format. */
    private fun plusMinutes(start: String, minutes: Int): String {
        val ms = kotlinx.datetime.LocalDateTime.parse(start).toInstant(JERUSALEM).toEpochMilliseconds() + minutes * 60_000L
        return Instant.fromEpochMilliseconds(ms).toLocalDateTime(JERUSALEM).toString().take(16)
    }

    private fun Args.checkEvent(): ToolCheck {
        val title = text("title", 1, 300)
        val allDay = optBool("allDay") ?: false
        val start = dateOrDateTime("start", allDay)
        val end = when {
            has("end") -> dateOrDateTime("end", allDay)
            allDay -> start
            has("durationMinutes") -> plusMinutes(start, intIn("durationMinutes", 1, 1_440))
            else -> fail("Missing end or durationMinutes - ask Tim how long the event lasts")
        }
        if (end < start) fail("end must not be before start")
        val rule = oneOfOr("repeatRule", REPEAT_RULES, "NONE")
        val until = optDate("repeatUntil")
        if (until != null) {
            if (rule == "NONE") fail("repeatUntil needs a repeatRule")
            if (until < start.take(10)) fail("repeatUntil must not be before the start date")
        }
        val note = optText("note", 5_000)
        val location = optText("location", 500)
        val color = optOneOf("color", EVENT_COLORS)
        return done(
            "title" to title, "start" to start, "end" to end, "allDay" to allDay, "repeatRule" to rule,
            "repeatUntil" to until, "note" to note, "location" to location, "color" to color,
        )
    }
}

// ---- schema builders ---------------------------------------------------------------------------

private fun tool(
    name: String, kind: ToolKind, label: String, description: String, parameters: JsonObject,
    check: (JsonObject) -> ToolCheck,
) = ToolSpec(name, kind, label, description, parameters) { args -> guard { check(args) } }

private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { for ((k, v) in props) put(k, v) })
    put("required", buildJsonArray { for (r in required) add(JsonPrimitive(r)) })
}

private fun str(description: String): JsonObject = buildJsonObject { put("type", "string"); put("description", description) }
private fun int(description: String, min: Int, max: Int): JsonObject =
    buildJsonObject { put("type", "integer"); put("description", description); put("minimum", min); put("maximum", max) }
private fun boolProp(description: String): JsonObject = buildJsonObject { put("type", "boolean"); put("description", description) }
private fun objProp(description: String): JsonObject = buildJsonObject { put("type", "object"); put("description", description) }
private fun enumProp(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { for (v in values) add(JsonPrimitive(v)) })
}

// ---- argument reader ---------------------------------------------------------------------------

private class BadArgs(val reason: String) : Exception(reason)

/** What a validator says for an absent required argument; the system prompt tells the model to ask Tim then. */
private fun missingText(name: String) = "Missing $name - ask Tim"

/**
 * Reads and checks arguments; any problem throws [BadArgs] which [Args.run]-style validators turn into [ToolCheck.Bad].
 * Models often send numbers as strings or leave optional fields as null/empty, so those are tolerated.
 */
private class Args(private val a: JsonObject) {
    fun fail(reason: String): Nothing = throw BadArgs(reason)

    /** A required argument is absent: the model must ask Tim instead of guessing a value. */
    fun missing(name: String): Nothing = throw BadArgs(missingText(name))

    fun has(name: String): Boolean = !primitiveText(name).isNullOrBlank()

    /** A whole number in [min]..[max] that must be given. */
    fun intIn(name: String, min: Int, max: Int): Int {
        val v = primitiveText(name)?.trim()
        if (v.isNullOrEmpty()) missing(name)
        return checkInt(name, v, min, max)
    }

    fun optIntIn(name: String, min: Int, max: Int): Int? {
        val v = primitiveText(name)?.trim()
        if (v.isNullOrEmpty()) return null
        return checkInt(name, v, min, max)
    }

    private fun checkInt(name: String, v: String, min: Int, max: Int): Int {
        val n = v.toIntOrNull() ?: fail("$name must be a whole number")
        if (n !in min..max) fail("$name must be between $min and $max")
        return n
    }

    /** An amount in shekels ("12.50"), returned normalised ("12.50"); must be given and above 0. */
    fun amount(name: String): String {
        val v = primitiveText(name)?.trim()
        if (v.isNullOrEmpty()) missing(name)
        return normAmount(name, v)
    }

    fun optAmount(name: String): String? {
        val v = primitiveText(name)?.trim()
        if (v.isNullOrEmpty()) return null
        return normAmount(name, v)
    }

    private fun normAmount(name: String, v: String): String {
        val agorot = parseAgorot(v) ?: fail("$name must be an amount above 0 like 12.50")
        return formatAgorot(agorot)
    }

    /** An update must change something: at least one of [values] was given. */
    fun requireChange(vararg values: Any?) {
        if (values.all { it == null }) fail("Missing what to change - ask Tim")
    }

    private fun raw(name: String): JsonElement? = a[name]?.takeIf { it !is JsonNull }

    private fun primitiveText(name: String): String? = (raw(name) as? JsonPrimitive)?.content

    fun text(name: String, min: Int, max: Int): String {
        val v = primitiveText(name)?.trim() ?: missing(name)
        if (v.length < min) missing(name)
        if (v.length > max) fail("$name is longer than $max characters")
        return v
    }

    fun optText(name: String, max: Int): String? {
        val v = primitiveText(name)?.trim()
        if (v.isNullOrEmpty()) return null
        if (v.length > max) fail("$name is longer than $max characters")
        return v
    }

    fun oneOf(name: String, values: List<String>): String {
        val v = primitiveText(name)?.trim()?.uppercaseIfEnum(values) ?: missing(name)
        if (v !in values) fail("$name must be one of ${values.joinToString()}")
        return v
    }

    fun optOneOf(name: String, values: List<String>): String? =
        if (primitiveText(name).isNullOrBlank()) null else oneOf(name, values)

    fun oneOfOr(name: String, values: List<String>, default: String): String = optOneOf(name, values) ?: default

    /** Log kinds are lower case, the other enums upper case: match case-insensitively and return the canonical value. */
    private fun String.uppercaseIfEnum(values: List<String>): String = values.firstOrNull { it.equals(this, ignoreCase = true) } ?: this

    fun days(): Int {
        val v = primitiveText("days")?.trim() ?: return Tools.DEFAULT_DAYS
        val n = v.toIntOrNull() ?: fail("days must be a whole number")
        if (n !in 1..Tools.MAX_DAYS) fail("days must be between 1 and ${Tools.MAX_DAYS}")
        return n
    }

    fun optBool(name: String): Boolean? {
        val p = raw(name) as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> fail("$name must be true or false")
        }
    }

    fun obj(name: String): JsonObject = raw(name) as? JsonObject ?: fail("$name must be an object")

    fun date(name: String): String {
        val v = primitiveText(name)?.trim() ?: missing(name)
        return checkDate(name, v)
    }

    fun optDate(name: String): String? {
        val v = primitiveText(name)?.trim()
        if (v.isNullOrEmpty()) return null
        return checkDate(name, v)
    }

    private fun checkDate(name: String, v: String): String {
        if (!DATE_RE.matches(v)) fail("$name must look like yyyy-MM-dd")
        try {
            LocalDate.parse(v)
        } catch (e: IllegalArgumentException) {
            fail("$name is not a real date")
        }
        return v
    }

    fun optTime(name: String): String? {
        val v = primitiveText(name)?.trim()
        if (v.isNullOrEmpty()) return null
        if (!TIME_RE.matches(v)) fail("$name must look like HH:mm")
        val h = v.substring(0, 2).toInt()
        val m = v.substring(3, 5).toInt()
        if (h > 23 || m > 59) fail("$name is not a real time")
        return v
    }

    /** yyyy-MM-dd when [allDay], else yyyy-MM-ddTHH:mm; returned unchanged so strings compare in time order. */
    fun dateOrDateTime(name: String, allDay: Boolean): String {
        val v = primitiveText(name)?.trim() ?: missing(name)
        if (allDay) return checkDate(name, v)
        if (!DATETIME_RE.matches(v)) fail("$name must look like yyyy-MM-ddTHH:mm")
        try {
            LocalDateTime.parse(v)
        } catch (e: IllegalArgumentException) {
            fail("$name is not a real date and time")
        }
        return v
    }

    fun email(name: String): String {
        val v = text(name, 3, 320)
        if (!EMAIL_RE.matches(v)) fail("$name is not an email address")
        return v
    }

    fun done(vararg fields: Pair<String, Any?>): ToolCheck.Ok = ToolCheck.Ok(buildJsonObject {
        for ((k, v) in fields) {
            when (v) {
                null -> {}
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                is JsonObject -> put(k, v)
                else -> error("unsupported value for $k")
            }
        }
    })

    companion object {
        private val DATE_RE = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val TIME_RE = Regex("\\d{2}:\\d{2}")
        private val DATETIME_RE = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
        private val EMAIL_RE = Regex("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+")
    }
}

/** Validators throw [BadArgs]; every tool is built through [tool], which turns that into [ToolCheck.Bad]. */
private inline fun guard(block: () -> ToolCheck): ToolCheck =
    try {
        block()
    } catch (e: BadArgs) {
        ToolCheck.Bad(e.reason)
    }
