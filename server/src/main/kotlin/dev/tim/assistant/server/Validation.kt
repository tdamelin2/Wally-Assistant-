package dev.tim.assistant.server

import dev.tim.assistant.model.SyncRow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val SPENDING_CATEGORIES = listOf("Food", "Transport", "Shopping", "Bills", "Fun", "Health", "Other")
val SERVER_WRITTEN_TABLES = setOf("cal_event", "mail_item", "briefing", "course", "study_file", "listen")
val CLIENT_TABLES = listOf(
    "checkin", "spending", "journal", "task", "health_day", "workout", "meal",
    "doc_folder", "doc", "ai_job", "dream", "dream_pattern", "goal", "card", "quiz", "exam",
    "usage_session", "app_rule", "mail_draft", "mail_action", "hw_check", "approval", "research",
)
val SYNCED_TABLES = CLIENT_TABLES + SERVER_WRITTEN_TABLES

const val REJECT_READ_ONLY = "read-only table"
const val REJECT_TABLE = "invalid: table"

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

private enum class Kind { STR, INT, BOOL }

private class Field(val name: String, val kind: Kind, val nullable: Boolean = false)

private val SPECS: Map<String, List<Field>> = mapOf(
    "checkin" to listOf(
        Field("localDate", Kind.STR), Field("mood", Kind.INT), Field("energy", Kind.INT),
        Field("sleepQuality", Kind.INT), Field("stress", Kind.INT), Field("note", Kind.STR, true),
    ),
    "spending" to listOf(
        Field("amountAgorot", Kind.INT), Field("category", Kind.STR),
        Field("note", Kind.STR, true), Field("localDate", Kind.STR),
    ),
    "journal" to listOf(Field("text", Kind.STR), Field("createdAt", Kind.INT)),
    "task" to listOf(
        Field("title", Kind.STR), Field("dueDate", Kind.STR, true),
        Field("done", Kind.BOOL), Field("doneAt", Kind.INT, true),
    ),
    "health_day" to listOf(
        Field("localDate", Kind.STR), Field("sleepMinutes", Kind.INT, true),
        Field("steps", Kind.INT, true), Field("restingHr", Kind.INT, true),
    ),
    "workout" to listOf(
        Field("hcRecordId", Kind.STR), Field("type", Kind.STR), Field("startAt", Kind.INT),
        Field("endAt", Kind.INT), Field("distanceMeters", Kind.INT, true),
    ),
    "meal" to listOf(
        Field("type", Kind.STR), Field("text", Kind.STR), Field("eatenAt", Kind.INT), Field("localDate", Kind.STR),
        Field("kcal", Kind.INT, true), Field("proteinG", Kind.INT, true), Field("carbsG", Kind.INT, true),
        Field("fatG", Kind.INT, true), Field("estimateModel", Kind.STR, true),
    ),
    "doc_folder" to listOf(Field("name", Kind.STR)),
    "doc" to listOf(
        Field("folderId", Kind.STR, true), Field("title", Kind.STR), Field("body", Kind.STR), Field("source", Kind.STR),
    ),
    "ai_job" to listOf(
        Field("kind", Kind.STR), Field("inputJson", Kind.STR), Field("status", Kind.STR), Field("resultJson", Kind.STR, true),
        Field("error", Kind.STR, true), Field("attempts", Kind.INT), Field("createdAt", Kind.INT), Field("startedAt", Kind.INT, true),
    ),
    "dream" to listOf(
        Field("localDate", Kind.STR), Field("text", Kind.STR), Field("mood", Kind.INT), Field("lucid", Kind.BOOL),
        Field("tags", Kind.STR), Field("analysis", Kind.STR, true), Field("analysisModel", Kind.STR, true),
    ),
    "dream_pattern" to listOf(Field("localDate", Kind.STR), Field("text", Kind.STR), Field("model", Kind.STR)),
    "goal" to listOf(
        Field("title", Kind.STR), Field("why", Kind.STR, true), Field("deadline", Kind.STR, true), Field("status", Kind.STR),
    ),
    "card" to listOf(
        Field("fileId", Kind.STR), Field("front", Kind.STR), Field("back", Kind.STR),
        Field("lastResult", Kind.STR, true), Field("lastReviewedAt", Kind.INT, true),
    ),
    "quiz" to listOf(
        Field("courseId", Kind.STR), Field("questionsJson", Kind.STR),
        Field("score", Kind.INT, true), Field("takenAt", Kind.INT, true),
    ),
    "exam" to listOf(
        Field("courseId", Kind.STR, true), Field("title", Kind.STR), Field("localDate", Kind.STR),
        Field("time", Kind.STR, true),
    ),
    "usage_session" to listOf(
        Field("device", Kind.STR), Field("app", Kind.STR), Field("title", Kind.STR, true),
        Field("startAt", Kind.INT), Field("endAt", Kind.INT), Field("localDate", Kind.STR),
    ),
    "app_rule" to listOf(Field("matchType", Kind.STR), Field("pattern", Kind.STR), Field("label", Kind.STR)),
    "mail_draft" to listOf(
        Field("toAddr", Kind.STR), Field("subject", Kind.STR), Field("body", Kind.STR),
        Field("replyToGmailId", Kind.STR, true), Field("threadId", Kind.STR, true), Field("status", Kind.STR),
        Field("sentAt", Kind.INT, true), Field("error", Kind.STR, true),
    ),
    "mail_action" to listOf(
        Field("gmailId", Kind.STR), Field("action", Kind.STR), Field("labelId", Kind.STR, true),
        Field("labelName", Kind.STR, true), Field("reason", Kind.STR), Field("status", Kind.STR),
    ),
    "hw_check" to listOf(Field("takenAt", Kind.INT), Field("resultJson", Kind.STR), Field("summary", Kind.STR)),
    "approval" to listOf(
        Field("tool", Kind.STR), Field("argsJson", Kind.STR), Field("summary", Kind.STR), Field("status", Kind.STR),
        Field("createdAt", Kind.INT), Field("resultText", Kind.STR, true),
    ),
    "research" to listOf(
        Field("category", Kind.STR), Field("questionKey", Kind.STR), Field("question", Kind.STR),
        Field("answerText", Kind.STR), Field("answerJson", Kind.STR), Field("sources", Kind.STR),
        Field("askedAt", Kind.INT), Field("expiresAt", Kind.INT, true),
    ),
)

private val TOOL_NAME = Regex("[a-z_]{1,40}")
private val RESEARCH_ID = Regex("research:(NUTRITION|DEFINITION|APP_CATEGORY|OTHER):[0-9a-f]{64}")
private val APPROVAL_STATUSES = listOf("PENDING", "APPROVED", "REJECTED", "EXPIRED", "DONE", "FAILED")
private val RESEARCH_CATEGORIES = listOf("NUTRITION", "DEFINITION", "APP_CATEGORY", "OTHER")

private const val MAX_SESSION_MS = 2L * 24 * 60 * 60 * 1000

private val CLOCK_TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")

private fun isRealDate(text: String): Boolean =
    DATE.matches(text) && runCatching { java.time.LocalDate.parse(text) }.isSuccess

/** Task rows from 1.5.0 apps have no goalId key; only task rows may leave it out. */
private const val TASK_GOAL_ID = "goalId"
private val JOB_KIND = Regex("[A-Z_]{1,40}")

/** Returns the first field name whose presence or JSON type is wrong, or null when all are fine. */
private fun typeProblem(data: JsonObject, fields: List<Field>): String? {
    for (f in fields) {
        val e: JsonElement = data[f.name] ?: return f.name
        if (e is JsonNull) {
            if (!f.nullable) return f.name
            continue
        }
        if (e !is JsonPrimitive) return f.name
        val ok = when (f.kind) {
            Kind.STR -> e.isString
            Kind.INT -> !e.isString && e.content.toLongOrNull() != null
            Kind.BOOL -> !e.isString && (e.content == "true" || e.content == "false")
        }
        if (!ok) return f.name
    }
    return null
}

private fun str(data: JsonObject, name: String): String? =
    (data[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun int(data: JsonObject, name: String): Long? =
    (data[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

/** task.goalId: absent (old app) or null or a string of 1..100 characters; any other type is a problem. */
private fun goalIdProblem(data: JsonObject): String? {
    val e = data[TASK_GOAL_ID] ?: return null
    if (e is JsonNull) return null
    if (e !is JsonPrimitive || !e.isString || e.content.length !in 1..100) return TASK_GOAL_ID
    return null
}

private fun invalid(field: String) = "invalid: $field"

/**
 * Validates a client-writable row. Returns null when valid, else the rejection reason
 * (`read-only table`, `invalid: table` or `invalid: <field>`). Types and presence are checked
 * strictly before any value rule, so the mapper never sees a mistyped field.
 */
fun validateRow(row: SyncRow): String? {
    if (row.table in SERVER_WRITTEN_TABLES) return REJECT_READ_ONLY
    val fields = SPECS[row.table] ?: return REJECT_TABLE
    if (row.id.isBlank()) return invalid("id")
    if (row.updatedAt < 0) return invalid("updatedAt")
    typeProblem(row.data, fields)?.let { return invalid(it) }
    val d = row.data
    when (row.table) {
        "checkin" -> {
            for (f in listOf("mood", "energy", "sleepQuality", "stress")) {
                if (int(d, f)!! !in 1L..5L) return invalid(f)
            }
            val date = str(d, "localDate")!!
            if (!DATE.matches(date)) return invalid("localDate")
            if (row.id != "checkin:$date") return invalid("id")
        }
        "spending" -> {
            if (int(d, "amountAgorot")!! <= 0L) return invalid("amountAgorot")
            if (str(d, "category") !in SPENDING_CATEGORIES) return invalid("category")
            if (!DATE.matches(str(d, "localDate")!!)) return invalid("localDate")
        }
        "journal" -> {
            if (str(d, "text")!!.length !in 1..10_000) return invalid("text")
        }
        "task" -> {
            if (str(d, "title")!!.length !in 1..200) return invalid("title")
            str(d, "dueDate")?.let { if (!DATE.matches(it)) return invalid("dueDate") }
            goalIdProblem(d)?.let { return invalid(it) }
        }
        "health_day" -> {
            val date = str(d, "localDate")!!
            if (!DATE.matches(date)) return invalid("localDate")
            for (f in listOf("sleepMinutes", "steps", "restingHr")) {
                int(d, f)?.let { if (it < 0) return invalid(f) }
            }
            if (row.id != "health:$date") return invalid("id")
        }
        "workout" -> {
            if (int(d, "endAt")!! < int(d, "startAt")!!) return invalid("endAt")
            int(d, "distanceMeters")?.let { if (it < 0) return invalid("distanceMeters") }
            if (row.id != "workout:${str(d, "hcRecordId")}") return invalid("id")
        }
        "meal" -> {
            if (str(d, "type") !in listOf("MEAL", "SNACK")) return invalid("type")
            if (str(d, "text")!!.length !in 1..1000) return invalid("text")
            if (!DATE.matches(str(d, "localDate")!!)) return invalid("localDate")
            for (f in listOf("kcal", "proteinG", "carbsG", "fatG")) {
                int(d, f)?.let { if (it < 0) return invalid(f) }
            }
        }
        "doc_folder" -> {
            if (str(d, "name")!!.length !in 1..100) return invalid("name")
        }
        "doc" -> {
            if (str(d, "title")!!.length !in 1..200) return invalid("title")
            if (str(d, "body")!!.length > 200_000) return invalid("body")
            if (str(d, "source") !in listOf("USER", "AI")) return invalid("source")
            str(d, "folderId")?.let { if (it.isEmpty() || it.length > 100) return invalid("folderId") }
        }
        "ai_job" -> {
            if (!JOB_KIND.matches(str(d, "kind")!!)) return invalid("kind")
            if (str(d, "status") !in listOf("QUEUED", "RUNNING", "DONE", "FAILED")) return invalid("status")
            if (str(d, "inputJson")!!.length > 50_000) return invalid("inputJson")
            str(d, "resultJson")?.let { if (it.length > 100_000) return invalid("resultJson") }
            str(d, "error")?.let { if (it.length > 1_000) return invalid("error") }
            if (int(d, "attempts")!! !in 0L..3L) return invalid("attempts")
            if (int(d, "createdAt")!! < 0) return invalid("createdAt")
            int(d, "startedAt")?.let { if (it < 0) return invalid("startedAt") }
        }
        "dream" -> {
            if (!DATE.matches(str(d, "localDate")!!)) return invalid("localDate")
            if (str(d, "text")!!.length !in 1..20_000) return invalid("text")
            if (int(d, "mood")!! !in 1L..5L) return invalid("mood")
            if (str(d, "tags")!!.length > 500) return invalid("tags")
            str(d, "analysis")?.let { if (it.length > 100_000) return invalid("analysis") }
            str(d, "analysisModel")?.let { if (it.length > 200) return invalid("analysisModel") }
        }
        "dream_pattern" -> {
            val date = str(d, "localDate")!!
            if (!DATE.matches(date)) return invalid("localDate")
            if (row.id != "dreampattern:$date") return invalid("id")
            if (str(d, "text")!!.length > 100_000) return invalid("text")
            if (str(d, "model")!!.length > 200) return invalid("model")
        }
        "goal" -> {
            if (str(d, "title")!!.length !in 1..200) return invalid("title")
            str(d, "why")?.let { if (it.length > 5_000) return invalid("why") }
            str(d, "deadline")?.let { if (!DATE.matches(it)) return invalid("deadline") }
            if (str(d, "status") !in listOf("ACTIVE", "DONE", "DROPPED")) return invalid("status")
        }
        "card" -> {
            if (str(d, "fileId")!!.length !in 1..200) return invalid("fileId")
            if (str(d, "front")!!.length !in 1..2000) return invalid("front")
            if (str(d, "back")!!.length !in 1..2000) return invalid("back")
            str(d, "lastResult")?.let { if (it != "KNEW" && it != "DIDNT") return invalid("lastResult") }
        }
        "quiz" -> {
            if (str(d, "courseId")!!.length !in 1..200) return invalid("courseId")
            if (str(d, "questionsJson")!!.length > 50_000) return invalid("questionsJson")
            int(d, "score")?.let { if (it !in 0L..10L) return invalid("score") }
        }
        "exam" -> {
            str(d, "courseId")?.let { if (it.length !in 1..200) return invalid("courseId") }
            if (str(d, "title")!!.length !in 1..200) return invalid("title")
            if (!isRealDate(str(d, "localDate")!!)) return invalid("localDate")
            str(d, "time")?.let { if (!CLOCK_TIME.matches(it)) return invalid("time") }
        }
        "usage_session" -> {
            val device = str(d, "device")!!
            if (device != "PHONE" && device != "LAPTOP") return invalid("device")
            if (str(d, "app")!!.length !in 1..300) return invalid("app")
            str(d, "title")?.let { if (it.length > 200) return invalid("title") }
            val span = int(d, "endAt")!! - int(d, "startAt")!!
            if (span < 0 || span > MAX_SESSION_MS) return invalid("endAt")
            if (!isRealDate(str(d, "localDate")!!)) return invalid("localDate")
            val prefix = if (device == "PHONE") "use:phone:" else "use:laptop:"
            if (!row.id.startsWith(prefix)) return invalid("id")
        }
        "app_rule" -> {
            val matchType = str(d, "matchType")!!
            if (matchType != "PACKAGE" && matchType != "EXE" && matchType != "TITLE_CONTAINS") return invalid("matchType")
            val pattern = str(d, "pattern")!!
            if (pattern.length !in 1..200 || pattern != pattern.trim().lowercase()) return invalid("pattern")
            if (str(d, "label") !in listOf("PRODUCTIVE", "UNPRODUCTIVE", "NEUTRAL")) return invalid("label")
            if (row.id != "rule:$matchType:$pattern") return invalid("id")
        }
        "mail_draft" -> {
            if (str(d, "toAddr")!!.length > 320) return invalid("toAddr")
            if (str(d, "subject")!!.length > 500) return invalid("subject")
            if (str(d, "body")!!.length > 50_000) return invalid("body")
            str(d, "replyToGmailId")?.let { if (it.length > 200) return invalid("replyToGmailId") }
            str(d, "threadId")?.let { if (it.length > 200) return invalid("threadId") }
            // a client may only ever save or approve a draft; SENDING, SENT, FAILED and UNKNOWN are the server's
            if (str(d, "status") !in listOf("DRAFT", "APPROVED")) return invalid("status")
            if (int(d, "sentAt") != null) return invalid("sentAt")
            if (str(d, "error") != null) return invalid("error")
        }
        "mail_action" -> {
            if (str(d, "gmailId")!!.length !in 1..200) return invalid("gmailId")
            if (str(d, "action") !in listOf("LABEL", "ARCHIVE")) return invalid("action")
            str(d, "labelId")?.let { if (it.length > 200) return invalid("labelId") }
            str(d, "labelName")?.let { if (it.length > 200) return invalid("labelName") }
            if (str(d, "reason")!!.length > 500) return invalid("reason")
            if (str(d, "status") !in listOf("SUGGESTED", "APPROVED", "REJECTED")) return invalid("status")
        }
        "hw_check" -> {
            if (int(d, "takenAt")!! < 0) return invalid("takenAt")
            if (str(d, "resultJson")!!.length > 100_000) return invalid("resultJson")
            if (str(d, "summary")!!.length > 300) return invalid("summary")
        }
        "approval" -> {
            if (!TOOL_NAME.matches(str(d, "tool")!!)) return invalid("tool")
            if (str(d, "argsJson")!!.length > 20_000) return invalid("argsJson")
            if (str(d, "summary")!!.length !in 1..2_000) return invalid("summary")
            if (str(d, "status") !in APPROVAL_STATUSES) return invalid("status")
            if (int(d, "createdAt")!! < 0) return invalid("createdAt")
            str(d, "resultText")?.let { if (it.length > 10_000) return invalid("resultText") }
        }
        "research" -> {
            val category = str(d, "category")!!
            if (category !in RESEARCH_CATEGORIES) return invalid("category")
            if (!RESEARCH_ID.matches(row.id) || !row.id.startsWith("research:$category:")) return invalid("id")
            if (str(d, "question")!!.length !in 1..4_000) return invalid("question")
            if (str(d, "questionKey")!!.length > 4_000) return invalid("questionKey")
            if (str(d, "answerText")!!.length > 20_000) return invalid("answerText")
            if (str(d, "answerJson")!!.length > 50_000) return invalid("answerJson")
            if (str(d, "sources")!!.length > 10_000) return invalid("sources")
            if (int(d, "askedAt")!! < 0) return invalid("askedAt")
        }
    }
    return null
}
