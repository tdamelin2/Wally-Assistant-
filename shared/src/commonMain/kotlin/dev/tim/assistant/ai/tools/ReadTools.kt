package dev.tim.assistant.ai.tools

import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.data.Repository
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.forms.formatAgorot
import dev.tim.assistant.usage.DEVICE_LAPTOP
import dev.tim.assistant.usage.DEVICE_PHONE
import dev.tim.assistant.usage.Label
import dev.tim.assistant.usage.dayTotals
import dev.tim.assistant.usage.label
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The 13 read tools: each answers at once from the local database with plain text of at most
 * [Tools.TOOL_OUTPUT_MAX_CHARS] characters. Only Tim's own non-dream data is read; screen time is per app and per label,
 * never window titles. Arguments must already have been through [Tools.validate].
 */
class ReadTools(
    private val repo: Repository,
    private val now: () -> Long,
) {
    private val today: String get() = localDateOf(Instant.fromEpochMilliseconds(now()))

    fun run(name: String, args: JsonObject): String =
        when (name) {
            "get_tasks" -> tasks()
            "get_goals" -> goals()
            "search_docs" -> searchDocs(str(args, "query"))
            "read_doc" -> readDoc(str(args, "id"))
            "get_study" -> study(args["courseId"]?.jsonPrimitive?.content)
            "get_exams" -> exams()
            "get_screentime" -> screentime(days(args))
            "get_listening" -> listening(days(args))
            "get_unread_mail" -> unreadMail()
            "get_hw_checks" -> hwChecks()
            "get_research" -> research(str(args, "query"))
            "get_log" -> log(str(args, "kind"), days(args))
            "get_events" -> events(days(args))
            "find" -> find(args).first
            else -> "Error: '$name' is not a read tool"
        }

    /**
     * The `find` tool: names matching the query across courses, study files, goals, open tasks, notes, calendar events, exams and
     * unread mail. Returns the text for the model and the matches (chips carry an empty token; the server stamps it).
     */
    fun find(args: JsonObject): Pair<String, List<ChoiceDto>> {
        val kind = args["kind"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val query = str(args, "query")
        val matches = findMatches(candidates(), kind, query)
        val q = cut(query, 60)
        val text = when {
            matches.isEmpty() -> "No match for '$q'" + (kind?.let { " among $it" } ?: "") + "."
            matches.size == 1 -> matches[0].let { "1 match: ${it.kind} '${cut(it.name, 150)}' [id ${it.id}]" }
            else -> "${matches.size} matches for '$q' - Tim is shown buttons to pick one, ask him to choose: " +
                matches.joinToString("; ") { "${it.kind} '${cut(it.name, 100)}'" }
        }
        return text to matches
    }

    private fun candidates(): List<ChoiceDto> {
        val out = ArrayList<ChoiceDto>()
        val courses = repo.courses()
        for (c in courses) out += ChoiceDto("course", c.id, c.name, "")
        for (c in courses) for (f in repo.studyFiles(c.id)) if (f.deleted == 0L) out += ChoiceDto("study_doc", f.id, f.name, "")
        for (g in repo.goals()) out += ChoiceDto("goal", g.id, g.title, "")
        for (tk in repo.openTasks()) out += ChoiceDto("task", tk.id, tk.title, "")
        for (d in repo.docs()) out += ChoiceDto("doc", d.id, d.title, "")
        val from = now() - 24L * 60 * 60 * 1000
        for (e in repo.calendarEvents()) if (e.endAt >= from) out += ChoiceDto("event", e.id, e.title, "")
        for (x in repo.exams()) out += ChoiceDto("exam", x.id, x.title, "")
        for (m in repo.unreadMail()) out += ChoiceDto("mail", m.gmailId, m.subject, "")
        return out
    }

    private fun str(args: JsonObject, key: String): String = args[key]?.jsonPrimitive?.content.orEmpty()

    private fun days(args: JsonObject): Int = args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: Tools.DEFAULT_DAYS

    private fun cut(text: String, max: Int): String {
        val one = text.replace('\r', ' ').replace('\n', ' ').trim()
        return if (one.length <= max) one else ChatContext.safeTake(one, max) + "..."
    }

    private fun date(ms: Long): String = localDateOf(Instant.fromEpochMilliseconds(ms))

    /** [header] then as many whole [lines] as fit; the rest is summarised as "(N more not shown)". */
    private fun capped(header: String, lines: List<String>, empty: String): String {
        if (lines.isEmpty()) return empty
        val limit = Tools.TOOL_OUTPUT_MAX_CHARS
        val out = StringBuilder(header).append('\n')
        var shown = 0
        for (line in lines) {
            val l = if (line.length > limit - 100) ChatContext.safeTake(line, limit - 100) else line
            val reserve = 40
            if (out.length + l.length + 1 + reserve > limit) break
            out.append(l).append('\n')
            shown++
        }
        if (shown < lines.size) out.append("(${lines.size - shown} more not shown)")
        return out.toString().trimEnd()
    }

    private fun range(days: Int): Pair<String, String> {
        val t = LocalDate.parse(today)
        return t.minus(DatePeriod(days = days - 1)).toString() to t.toString()
    }

    // ---- tools --------------------------------------------------------------------------------------

    private fun tasks(): String {
        val open = repo.openTasks().sortedWith(compareBy({ it.dueDate ?: "9999" }, { it.title }))
        val weekAgo = now() - 7L * 24 * 60 * 60 * 1000
        val done = repo.allTasksForCharts().filter { it.done != 0L && (it.doneAt ?: 0L) >= weekAgo }
        val lines = open.map { "open ${it.dueDate?.let { d -> "due $d" } ?: "no due date"}: ${cut(it.title, 200)} [id ${it.id}]" } +
            done.map { "done ${date(it.doneAt ?: it.updatedAt)}: ${cut(it.title, 200)}" }
        return capped("Tasks (open, and finished in the last 7 days):", lines, "No tasks.")
    }

    private fun goals(): String {
        val lines = repo.goals().map { g ->
            val p = repo.goalProgress(g.id)
            val why = g.why?.takeIf { it.isNotBlank() }?.let { " why: ${cut(it, 150)}" } ?: ""
            val deadline = g.deadline?.let { " deadline $it" } ?: ""
            "${g.status} ${cut(g.title, 150)}$deadline tasks ${p.done}/${p.total}$why [id ${g.id}]"
        }
        return capped("Goals:", lines, "No goals.")
    }

    private fun searchDocs(query: String): String {
        val docs = repo.searchDocs(query).take(20)
        val lines = docs.map { "${cut(it.title, 120)} [id ${it.id}] ${cut(it.body, 150)}" }
        return capped("Documents matching '${cut(query, 60)}' (use read_doc for the full text):", lines, "No document matches '${cut(query, 60)}'.")
    }

    private fun readDoc(id: String): String {
        val doc = repo.docById(id) ?: return "No document with id '${cut(id, 60)}'."
        val head = "Document: ${cut(doc.title, 150)}\n"
        val room = Tools.TOOL_OUTPUT_MAX_CHARS - head.length - 60
        return if (doc.body.length <= room) head + doc.body
        else head + ChatContext.safeTake(doc.body, room) + "\n(text cut: ${doc.body.length - room} more characters)"
    }

    private fun study(courseId: String?): String {
        val courses = repo.courses().filter { courseId == null || it.id == courseId }
        if (courses.isEmpty()) return if (courseId == null) "No study courses." else "No course with id '${cut(courseId, 60)}'."
        val lines = courses.map { c ->
            val files = repo.studyFiles(c.id)
            val cards = files.sumOf { repo.cardCount(it.id) }
            val scores = repo.quizzesForCourse(c.id).mapNotNull { it.score }
            val quiz = if (scores.isEmpty()) "no quiz taken" else "quiz scores ${scores.takeLast(5).joinToString(", ")}"
            "${cut(c.name, 120)} [id ${c.id}] ${if (c.enabled != 0L) "checked" else "not checked"}, ${files.size} files, $cards flashcards, $quiz"
        }
        return capped("Study courses:", lines, "No study courses.")
    }

    private fun exams(): String {
        val t = today
        val lines = repo.exams().filter { it.localDate >= t }.sortedWith(compareBy({ it.localDate }, { it.time ?: "" }))
            .map { "${it.localDate}${it.time?.let { tm -> " $tm" } ?: ""} ${cut(it.title, 150)}" }
        return capped("Upcoming exams:", lines, "No upcoming exams.")
    }

    private fun screentime(days: Int): String {
        val (from, to) = range(days)
        val dates = (0 until days).map { LocalDate.parse(to).minus(DatePeriod(days = it)).toString() }
        val sessions = repo.usageSessions(from, to)
        val rules = repo.appRules()
        val lines = ArrayList<String>()
        for (device in listOf(DEVICE_PHONE, DEVICE_LAPTOP)) {
            val own = sessions.filter { it.deleted == 0L && it.device == device }
            if (own.isEmpty()) continue
            val totals = dayTotals(sessions, rules, device, dates)
            val byLabel = Label.entries.associateWith { l -> totals.values.sumOf { it[l] ?: 0L } }
            lines += "$device by label (minutes): " + Label.entries.joinToString(", ") { "${it.name.lowercase()} ${byLabel[it]}" }
            val perApp = own.groupBy { it.app }.map { (app, list) ->
                val labelMs = HashMap<Label, Long>()
                for (s in list) labelMs[label(s, rules)] = (labelMs[label(s, rules)] ?: 0L) + (s.endAt - s.startAt)
                val top = labelMs.entries.maxByOrNull { it.value }?.key ?: Label.UNSORTED
                Triple(app, list.sumOf { it.endAt - it.startAt }, top)
            }.sortedWith(compareByDescending<Triple<String, Long, Label>> { it.second }.thenBy { it.first }).take(15)
            for ((app, ms, l) in perApp) lines += "$device ${cut(app, 100)}: ${ms / 60_000} min (${l.name.lowercase()})"
        }
        return capped("Screen time, last $days days (per app and label):", lines, "No screen time recorded in the last $days days.")
    }

    private fun listening(days: Int): String {
        val (from, to) = range(days)
        val plays = repo.listens(500).filter { it.localDate in from..to }
        val tracks = plays.groupBy { it.trackName to it.artist }.map { (k, v) -> Triple(k.first, k.second, v.size) }
            .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.first }).take(15)
        val artists = plays.groupBy { it.artist }.map { (a, v) -> a to v.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(10)
        val lines = listOf("${plays.size} plays") +
            artists.map { "artist ${cut(it.first, 80)}: ${it.second} plays" } +
            tracks.map { "track ${cut(it.first, 80)} - ${cut(it.second, 60)}: ${it.third} plays" }
        return capped("Listening, last $days days:", lines, "No music plays in the last $days days.")
    }

    private fun unreadMail(): String {
        val lines = repo.unreadMail().sortedByDescending { it.receivedAt }.take(25).map {
            "${date(it.receivedAt)} from ${cut(it.sender, 60)}: ${cut(it.subject, 120)} - ${cut(it.snippet, 100)} [gmailId ${it.gmailId}]"
        }
        return capped("Unread mail (newest first):", lines, "No unread mail.")
    }

    private fun hwChecks(): String {
        val lines = repo.hwChecks().sortedByDescending { it.takenAt }.take(10).map { "${date(it.takenAt)} ${cut(it.summary, 300)}" }
        return capped("Hardware checks (newest first):", lines, "No hardware checks saved.")
    }

    private fun research(query: String): String {
        val lines = repo.searchResearch(query, 10).map { r ->
            "${date(r.askedAt)} ${cut(r.question, 150)} -> ${cut(r.answerText, 400)}"
        }
        return capped("Saved research matching '${cut(query, 60)}':", lines, "No saved research matches '${cut(query, 60)}'.")
    }

    private fun log(kind: String, days: Int): String {
        val (from, to) = range(days)
        val lines: List<String> = when (kind) {
            "checkins" -> repo.checkins().filter { it.localDate in from..to }.sortedByDescending { it.localDate }.map {
                val note = it.note?.takeIf { n -> n.isNotBlank() }?.let { n -> " note: ${cut(n, 200)}" } ?: ""
                "${it.localDate} mood ${it.mood} energy ${it.energy} sleep ${it.sleepQuality} stress ${it.stress}$note"
            }
            "spending" -> repo.spending().filter { it.localDate in from..to }.sortedByDescending { it.localDate }.map {
                val note = it.note?.takeIf { n -> n.isNotBlank() }?.let { n -> " ${cut(n, 150)}" } ?: ""
                "${it.localDate} ${formatAgorot(it.amountAgorot)} shekels ${it.category}$note"
            }
            "meals" -> repo.meals().filter { it.localDate in from..to }.sortedByDescending { it.eatenAt }.map {
                val est = it.kcal?.let { k -> " ~$k kcal P ${it.proteinG ?: 0} C ${it.carbsG ?: 0} F ${it.fatG ?: 0}" } ?: ""
                "${it.localDate} ${it.type} ${cut(it.text, 200)}$est"
            }
            "journal" -> repo.journal().filter { date(it.createdAt) in from..to }.sortedByDescending { it.createdAt }.map {
                "${date(it.createdAt)} ${cut(it.text, 300)}"
            }
            "health" -> repo.healthDays().filter { it.localDate in from..to }.sortedByDescending { it.localDate }.mapNotNull { h ->
                val parts = buildList {
                    h.sleepMinutes?.let { add("sleep ${it / 60}h${(it % 60).toString().padStart(2, '0')}") }
                    h.steps?.let { add("steps $it") }
                    h.restingHr?.let { add("resting HR $it") }
                }
                if (parts.isEmpty()) null else "${h.localDate} ${parts.joinToString(" ")}"
            }
            else -> return "Error: unknown log kind '${cut(kind, 30)}'"
        }
        return capped("Log '$kind', last $days days (newest first):", lines, "No '$kind' entries in the last $days days.")
    }

    private fun events(days: Int): String {
        val from = today
        val to = LocalDate.parse(from).plus(DatePeriod(days = days)).toString()
        val lines = repo.calendarEvents().filter { date(it.startAt) in from..to }.sortedBy { it.startAt }.map { e ->
            val time = if (e.allDay != 0L) "all day" else "${hhmm(e.startAt)}-${hhmm(e.endAt)}"
            val place = e.location?.takeIf { it.isNotBlank() }?.let { " at ${cut(it, 80)}" } ?: ""
            "${date(e.startAt)} $time ${cut(e.title, 150)}$place"
        }
        return capped("Calendar events, next $days days:", lines, "No calendar events in the next $days days.")
    }

    private fun hhmm(ms: Long): String = dev.tim.assistant.google.hhmm(ms)
}
