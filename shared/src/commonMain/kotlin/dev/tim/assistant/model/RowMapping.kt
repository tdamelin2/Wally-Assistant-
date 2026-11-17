package dev.tim.assistant.model

import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.Research
import dev.tim.assistant.db.Briefing
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.Card
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Course
import dev.tim.assistant.db.Doc
import dev.tim.assistant.db.Doc_folder
import dev.tim.assistant.db.Dream
import dev.tim.assistant.db.Dream_pattern
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Goal
import dev.tim.assistant.db.Health_day
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Hw_check
import dev.tim.assistant.db.Listen
import dev.tim.assistant.db.Mail_action
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Quiz
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Study_file
import dev.tim.assistant.db.Task
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.db.Workout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A row for a table this build does not know (a newer app or server wrote it). */
class UnknownTableException(val table: String) : IllegalArgumentException("unknown table '$table'")

// ---- DB row -> SyncRow -------------------------------------------------------------------

private fun s(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
private fun n(v: Long?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
private fun b(v: Boolean): JsonElement = JsonPrimitive(v)

private fun syncRow(table: String, id: String, updatedAt: Long, deleted: Long, vararg fields: Pair<String, JsonElement>) =
    SyncRow(table, id, updatedAt, deleted != 0L, JsonObject(linkedMapOf(*fields)))

fun toSyncRow(row: Checkin): SyncRow = syncRow(
    "checkin", row.id, row.updatedAt, row.deleted,
    "localDate" to s(row.localDate), "mood" to n(row.mood), "energy" to n(row.energy),
    "sleepQuality" to n(row.sleepQuality), "stress" to n(row.stress), "note" to s(row.note),
)

fun toSyncRow(row: Spending): SyncRow = syncRow(
    "spending", row.id, row.updatedAt, row.deleted,
    "amountAgorot" to n(row.amountAgorot), "category" to s(row.category),
    "note" to s(row.note), "localDate" to s(row.localDate),
)

fun toSyncRow(row: Journal): SyncRow = syncRow(
    "journal", row.id, row.updatedAt, row.deleted,
    "text" to s(row.text), "createdAt" to n(row.createdAt),
)

fun toSyncRow(row: Task): SyncRow = syncRow(
    "task", row.id, row.updatedAt, row.deleted,
    "title" to s(row.title), "dueDate" to s(row.dueDate),
    "done" to b(row.done != 0L), "doneAt" to n(row.doneAt), "goalId" to s(row.goalId),
)

fun toSyncRow(row: Health_day): SyncRow = syncRow(
    "health_day", row.id, row.updatedAt, row.deleted,
    "localDate" to s(row.localDate), "sleepMinutes" to n(row.sleepMinutes),
    "steps" to n(row.steps), "restingHr" to n(row.restingHr),
)

fun toSyncRow(row: Workout): SyncRow = syncRow(
    "workout", row.id, row.updatedAt, row.deleted,
    "hcRecordId" to s(row.hcRecordId), "type" to s(row.type), "startAt" to n(row.startAt),
    "endAt" to n(row.endAt), "distanceMeters" to n(row.distanceMeters),
)

fun toSyncRow(row: Cal_event): SyncRow = syncRow(
    "cal_event", row.id, row.updatedAt, row.deleted,
    "calendarId" to s(row.calendarId), "eventId" to s(row.eventId), "title" to s(row.title),
    "startAt" to n(row.startAt), "endAt" to n(row.endAt), "allDay" to b(row.allDay != 0L),
    "location" to s(row.location),
)

fun toSyncRow(row: Mail_item): SyncRow = syncRow(
    "mail_item", row.id, row.updatedAt, row.deleted,
    "gmailId" to s(row.gmailId), "sender" to s(row.sender), "subject" to s(row.subject),
    "snippet" to s(row.snippet), "receivedAt" to n(row.receivedAt),
)

fun toSyncRow(row: Briefing): SyncRow = syncRow(
    "briefing", row.id, row.updatedAt, row.deleted,
    "localDate" to s(row.localDate), "text" to s(row.text), "model" to s(row.model),
)

fun toSyncRow(row: Meal): SyncRow = syncRow(
    "meal", row.id, row.updatedAt, row.deleted,
    "type" to s(row.type), "text" to s(row.text), "eatenAt" to n(row.eatenAt),
    "localDate" to s(row.localDate), "kcal" to n(row.kcal), "proteinG" to n(row.proteinG),
    "carbsG" to n(row.carbsG), "fatG" to n(row.fatG), "estimateModel" to s(row.estimateModel),
)

fun toSyncRow(row: Doc_folder): SyncRow = syncRow("doc_folder", row.id, row.updatedAt, row.deleted, "name" to s(row.name))

fun toSyncRow(row: Doc): SyncRow = syncRow(
    "doc", row.id, row.updatedAt, row.deleted,
    "folderId" to s(row.folderId), "title" to s(row.title), "body" to s(row.body), "source" to s(row.source),
)

fun toSyncRow(row: Ai_job): SyncRow = syncRow(
    "ai_job", row.id, row.updatedAt, row.deleted,
    "kind" to s(row.kind), "inputJson" to s(row.inputJson), "status" to s(row.status),
    "resultJson" to s(row.resultJson), "error" to s(row.error), "attempts" to n(row.attempts),
    "createdAt" to n(row.createdAt), "startedAt" to n(row.startedAt),
)

fun toSyncRow(row: Dream): SyncRow = syncRow(
    "dream", row.id, row.updatedAt, row.deleted,
    "localDate" to s(row.localDate), "text" to s(row.text), "mood" to n(row.mood),
    "lucid" to b(row.lucid != 0L), "tags" to s(row.tags), "analysis" to s(row.analysis),
    "analysisModel" to s(row.analysisModel),
)

fun toSyncRow(row: Dream_pattern): SyncRow = syncRow(
    "dream_pattern", row.id, row.updatedAt, row.deleted,
    "localDate" to s(row.localDate), "text" to s(row.text), "model" to s(row.model),
)

fun toSyncRow(row: Goal): SyncRow = syncRow(
    "goal", row.id, row.updatedAt, row.deleted,
    "title" to s(row.title), "why" to s(row.why), "deadline" to s(row.deadline), "status" to s(row.status),
)

fun toSyncRow(row: Course): SyncRow = syncRow("course", row.id, row.updatedAt, row.deleted, "moodleId" to n(row.moodleId), "name" to s(row.name), "enabled" to b(row.enabled != 0L))

fun toSyncRow(row: Study_file): SyncRow = syncRow(
    "study_file", row.id, row.updatedAt, row.deleted,
    "courseId" to s(row.courseId), "name" to s(row.name), "origin" to s(row.origin),
    "sizeBytes" to n(row.sizeBytes), "textStatus" to s(row.textStatus), "studySetDocId" to s(row.studySetDocId),
)

fun toSyncRow(row: Card): SyncRow = syncRow(
    "card", row.id, row.updatedAt, row.deleted,
    "fileId" to s(row.fileId), "front" to s(row.front), "back" to s(row.back),
    "lastResult" to s(row.lastResult), "lastReviewedAt" to n(row.lastReviewedAt),
)

fun toSyncRow(row: Quiz): SyncRow = syncRow(
    "quiz", row.id, row.updatedAt, row.deleted,
    "courseId" to s(row.courseId), "questionsJson" to s(row.questionsJson),
    "score" to n(row.score), "takenAt" to n(row.takenAt),
)

fun toSyncRow(row: Exam): SyncRow = syncRow(
    "exam", row.id, row.updatedAt, row.deleted,
    "courseId" to s(row.courseId), "title" to s(row.title), "localDate" to s(row.localDate), "time" to s(row.time),
)

fun toSyncRow(row: Usage_session): SyncRow = syncRow(
    "usage_session", row.id, row.updatedAt, row.deleted,
    "device" to s(row.device), "app" to s(row.app), "title" to s(row.title),
    "startAt" to n(row.startAt), "endAt" to n(row.endAt), "localDate" to s(row.localDate),
)

fun toSyncRow(row: App_rule): SyncRow = syncRow(
    "app_rule", row.id, row.updatedAt, row.deleted,
    "matchType" to s(row.matchType), "pattern" to s(row.pattern), "label" to s(row.label),
)

fun toSyncRow(row: Listen): SyncRow = syncRow(
    "listen", row.id, row.updatedAt, row.deleted,
    "trackName" to s(row.trackName), "artist" to s(row.artist), "durationMs" to n(row.durationMs),
    "playedAt" to n(row.playedAt), "localDate" to s(row.localDate),
)

fun toSyncRow(row: Mail_draft): SyncRow = syncRow(
    "mail_draft", row.id, row.updatedAt, row.deleted,
    "toAddr" to s(row.toAddr), "subject" to s(row.subject), "body" to s(row.body),
    "replyToGmailId" to s(row.replyToGmailId), "threadId" to s(row.threadId), "status" to s(row.status),
    "sentAt" to n(row.sentAt), "error" to s(row.error),
)

fun toSyncRow(row: Mail_action): SyncRow = syncRow(
    "mail_action", row.id, row.updatedAt, row.deleted,
    "gmailId" to s(row.gmailId), "action" to s(row.action), "labelId" to s(row.labelId),
    "labelName" to s(row.labelName), "reason" to s(row.reason), "status" to s(row.status),
)

fun toSyncRow(row: Hw_check): SyncRow = syncRow(
    "hw_check", row.id, row.updatedAt, row.deleted,
    "takenAt" to n(row.takenAt), "resultJson" to s(row.resultJson), "summary" to s(row.summary),
)

fun toSyncRow(row: Approval): SyncRow = syncRow(
    "approval", row.id, row.updatedAt, row.deleted,
    "tool" to s(row.tool), "argsJson" to s(row.argsJson), "summary" to s(row.summary),
    "status" to s(row.status), "createdAt" to n(row.createdAt), "resultText" to s(row.resultText),
)

fun toSyncRow(row: Research): SyncRow = syncRow(
    "research", row.id, row.updatedAt, row.deleted,
    "category" to s(row.category), "questionKey" to s(row.questionKey), "question" to s(row.question),
    "answerText" to s(row.answerText), "answerJson" to s(row.answerJson), "sources" to s(row.sources),
    "askedAt" to n(row.askedAt), "expiresAt" to n(row.expiresAt),
)

// ---- SyncRow -> DB -----------------------------------------------------------------------

private class Fields(private val table: String, private val data: JsonObject) {
    private fun element(name: String): JsonElement =
        data[name] ?: throw IllegalArgumentException("table '$table': missing field '$name'")

    private fun bad(name: String, expected: String): Nothing =
        throw IllegalArgumentException("table '$table': field '$name' must be $expected")

    fun str(name: String): String {
        val e = element(name)
        if (e !is JsonPrimitive || e is JsonNull || !e.isString) bad(name, "a string")
        return e.content
    }

    fun strOrNull(name: String): String? =
        if (element(name) is JsonNull) null else str(name)

    fun long(name: String): Long {
        val e = element(name)
        if (e !is JsonPrimitive || e is JsonNull || e.isString) bad(name, "a number")
        return e.content.toLongOrNull() ?: bad(name, "an integer")
    }

    fun longOrNull(name: String): Long? =
        if (element(name) is JsonNull) null else long(name)

    fun has(name: String): Boolean = data.containsKey(name)

    fun flag(name: String): Long {
        val e = element(name)
        if (e !is JsonPrimitive || e is JsonNull || e.isString) bad(name, "a boolean")
        return when (e.content) {
            "true" -> 1L
            "false" -> 0L
            else -> bad(name, "a boolean")
        }
    }
}

/**
 * Writes [row] into the matching table (INSERT OR REPLACE). [serverSeq] and [syncState] are stored
 * as given. Throws [UnknownTableException] for an unknown table and [IllegalArgumentException] for a missing / mistyped field.
 */
fun upsertFromSyncRow(db: AssistantDb, row: SyncRow, serverSeq: Long = 0, syncState: String = "SYNCED") {
    val f = Fields(row.table, row.data)
    val deleted = if (row.deleted) 1L else 0L
    when (row.table) {
        "checkin" -> db.checkinQueries.upsert(
            Checkin(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("localDate"), f.long("mood"), f.long("energy"), f.long("sleepQuality"),
                f.long("stress"), f.strOrNull("note"),
            ),
        )
        "spending" -> db.spendingQueries.upsert(
            Spending(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.long("amountAgorot"), f.str("category"), f.strOrNull("note"), f.str("localDate"),
            ),
        )
        "journal" -> db.journalQueries.upsert(
            Journal(row.id, row.updatedAt, deleted, serverSeq, syncState, f.str("text"), f.long("createdAt")),
        )
        "task" -> {
            // Only task rows may omit goalId (1.5.0 apps): absent keeps the stored link, null clears, a string sets.
            val goalId = if (f.has("goalId")) f.strOrNull("goalId") else db.taskQueries.selectById(row.id).executeAsOneOrNull()?.goalId
            db.taskQueries.upsert(
                Task(
                    row.id, row.updatedAt, deleted, serverSeq, syncState,
                    f.str("title"), f.strOrNull("dueDate"), f.flag("done"), f.longOrNull("doneAt"), goalId,
                ),
            )
        }
        "health_day" -> db.health_dayQueries.upsert(
            Health_day(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("localDate"), f.longOrNull("sleepMinutes"), f.longOrNull("steps"), f.longOrNull("restingHr"),
            ),
        )
        "workout" -> db.workoutQueries.upsert(
            Workout(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("hcRecordId"), f.str("type"), f.long("startAt"), f.long("endAt"),
                f.longOrNull("distanceMeters"),
            ),
        )
        "cal_event" -> db.cal_eventQueries.upsert(
            Cal_event(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("calendarId"), f.str("eventId"), f.str("title"), f.long("startAt"),
                f.long("endAt"), f.flag("allDay"), f.strOrNull("location"),
            ),
        )
        "mail_item" -> db.mail_itemQueries.upsert(
            Mail_item(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("gmailId"), f.str("sender"), f.str("subject"), f.str("snippet"), f.long("receivedAt"),
            ),
        )
        "briefing" -> db.briefingQueries.upsert(
            Briefing(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("localDate"), f.str("text"), f.str("model"),
            ),
        )
        "meal" -> db.mealQueries.upsert(
            Meal(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("type"), f.str("text"), f.long("eatenAt"), f.str("localDate"),
                f.longOrNull("kcal"), f.longOrNull("proteinG"), f.longOrNull("carbsG"),
                f.longOrNull("fatG"), f.strOrNull("estimateModel"),
            ),
        )
        "doc_folder" -> db.doc_folderQueries.upsert(Doc_folder(row.id, row.updatedAt, deleted, serverSeq, syncState, f.str("name")))
        "doc" -> db.docQueries.upsert(
            Doc(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.strOrNull("folderId"), f.str("title"), f.str("body"), f.str("source"),
            ),
        )
        "ai_job" -> db.ai_jobQueries.upsert(
            Ai_job(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("kind"), f.str("inputJson"), f.str("status"), f.strOrNull("resultJson"),
                f.strOrNull("error"), f.long("attempts"), f.long("createdAt"), f.longOrNull("startedAt"),
            ),
        )
        "dream" -> db.dreamQueries.upsert(
            Dream(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("localDate"), f.str("text"), f.long("mood"), f.flag("lucid"), f.str("tags"),
                f.strOrNull("analysis"), f.strOrNull("analysisModel"),
            ),
        )
        "dream_pattern" -> db.dream_patternQueries.upsert(
            Dream_pattern(row.id, row.updatedAt, deleted, serverSeq, syncState, f.str("localDate"), f.str("text"), f.str("model")),
        )
        "goal" -> db.goalQueries.upsert(
            Goal(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("title"), f.strOrNull("why"), f.strOrNull("deadline"), f.str("status"),
            ),
        )
        "course" -> db.courseQueries.upsert(Course(row.id, row.updatedAt, deleted, serverSeq, syncState, f.longOrNull("moodleId"), f.str("name"), f.flag("enabled")))
        "study_file" -> db.study_fileQueries.upsert(
            Study_file(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("courseId"), f.str("name"), f.str("origin"), f.long("sizeBytes"),
                f.str("textStatus"), f.strOrNull("studySetDocId"),
            ),
        )
        "card" -> db.cardQueries.upsert(
            Card(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("fileId"), f.str("front"), f.str("back"), f.strOrNull("lastResult"), f.longOrNull("lastReviewedAt"),
            ),
        )
        "quiz" -> db.quizQueries.upsert(
            Quiz(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("courseId"), f.str("questionsJson"), f.longOrNull("score"), f.longOrNull("takenAt"),
            ),
        )
        "exam" -> db.examQueries.upsert(
            Exam(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.strOrNull("courseId"), f.str("title"), f.str("localDate"), f.strOrNull("time"),
            ),
        )
        "usage_session" -> db.usage_sessionQueries.upsert(
            Usage_session(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("device"), f.str("app"), f.strOrNull("title"), f.long("startAt"), f.long("endAt"), f.str("localDate"),
            ),
        )
        "app_rule" -> db.app_ruleQueries.upsert(
            App_rule(row.id, row.updatedAt, deleted, serverSeq, syncState, f.str("matchType"), f.str("pattern"), f.str("label")),
        )
        "listen" -> db.listenQueries.upsert(
            Listen(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("trackName"), f.str("artist"), f.long("durationMs"), f.long("playedAt"), f.str("localDate"),
            ),
        )
        "mail_draft" -> db.mail_draftQueries.upsert(
            Mail_draft(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("toAddr"), f.str("subject"), f.str("body"), f.strOrNull("replyToGmailId"),
                f.strOrNull("threadId"), f.str("status"), f.longOrNull("sentAt"), f.strOrNull("error"),
            ),
        )
        "mail_action" -> db.mail_actionQueries.upsert(
            Mail_action(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("gmailId"), f.str("action"), f.strOrNull("labelId"), f.strOrNull("labelName"),
                f.str("reason"), f.str("status"),
            ),
        )
        "hw_check" -> db.hw_checkQueries.upsert(
            Hw_check(row.id, row.updatedAt, deleted, serverSeq, syncState, f.long("takenAt"), f.str("resultJson"), f.str("summary")),
        )
        "approval" -> db.approvalQueries.upsert(
            Approval(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("tool"), f.str("argsJson"), f.str("summary"), f.str("status"), f.long("createdAt"), f.strOrNull("resultText"),
            ),
        )
        "research" -> db.researchQueries.upsert(
            Research(
                row.id, row.updatedAt, deleted, serverSeq, syncState,
                f.str("category"), f.str("questionKey"), f.str("question"), f.str("answerText"),
                f.str("answerJson"), f.str("sources"), f.long("askedAt"), f.longOrNull("expiresAt"),
            ),
        )
        else -> throw UnknownTableException(row.table)
    }
}
