package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.google.hhmm
import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.ui.forms.formatAgorot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.time.Instant

/**
 * The small "today only" context of the HP brain: the date, today's calendar events, a few open tasks, today's
 * check-in, meal and spending counts. Dream text is added only when [includeDreams] is true (Tim's message mentions dreams).
 * The result never passes [maxChars].
 */
object LiteContext {
    private const val LINE_MAX = 80
    private const val TASKS_MAX = 6
    private const val EVENTS_MAX = 6

    private fun cut(text: String, max: Int): String {
        val clean = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (clean.length <= max) return clean
        var end = max
        if (end > 0 && clean[end - 1].isHighSurrogate()) end--
        return clean.substring(0, end)
    }

    /** HP: today only, in about 1,200 characters. PHONE: the next seven days, open tasks, meals and spending with amounts. */
    enum class Profile { HP, PHONE }

    private val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
    private const val PHONE_LINE_MAX = 80
    private const val PHONE_DAYS = 7
    private const val PHONE_TASKS_MAX = 12
    private const val DREAM_MAX = 300
    // per-section character caps of the phone profile; they are spent in order out of what the fixed lines leave
    private const val EVENTS_CAP = 650
    private const val EXAMS_CAP = 250
    private const val TASKS_CAP = 650
    private const val MEALS_CAP = 350
    private const val SPENDING_CAP = 350

    fun build(db: AssistantDb, nowMs: Long, includeDreams: Boolean, maxChars: Int, profile: Profile = Profile.HP): String =
        if (profile == Profile.PHONE) buildPhone(db, nowMs, includeDreams, maxChars) else buildHp(db, nowMs, includeDreams, maxChars)

    private fun money(agorot: Long): String = formatAgorot(agorot).removeSuffix(".00") + " ₪"

    /** A section: its [head] line is always kept whole (it carries the counts and totals), body lines are kept whole while they fit [cap]. */
    private fun section(head: String, lines: List<String>, cap: Int): String {
        if (cap <= head.length + 1) return ""
        val out = StringBuilder(head).append('\n')
        var shown = 0
        for (line in lines) {
            if (out.length + line.length + 1 > cap) break
            out.append(line).append('\n')
            shown++
        }
        if (shown < lines.size && out.length + 20 <= cap) out.append("- and ${lines.size - shown} more\n")
        return out.toString()
    }

    private fun buildPhone(db: AssistantDb, nowMs: Long, includeDreams: Boolean, maxChars: Int): String {
        val today = localDateOf(Instant.fromEpochMilliseconds(nowMs))
        val todayJ = LocalDate.parse(today.toString())
        val windowStart = todayJ.atStartOfDay(ZONE).toInstant().toEpochMilli()
        val windowEnd = todayJ.plusDays(PHONE_DAYS.toLong()).atStartOfDay(ZONE).toInstant().toEpochMilli()
        val lastDay = todayJ.plusDays(PHONE_DAYS - 1L).toString()
        // the week is the Sunday-start week used by the ladder's "since Sunday" answer
        val weekStart = todayJ.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toString()

        val weekday = todayJ.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val header = "Today is $weekday $today (Israel time).\n"
        var checkinLine = ""
        var eventLines = emptyList<String>()
        var examLines = emptyList<String>()
        var taskLines = emptyList<String>()
        var mealLines = emptyList<String>()
        var spendLines = emptyList<String>()
        var spendTotal = 0L
        var weekTotal = 0L
        db.transaction {
            eventLines = db.cal_eventQueries.selectAll().executeAsList()
                .filter { it.deleted == 0L && it.startAt < windowEnd && it.endAt > windowStart }
                .sortedWith(compareBy({ it.startAt }, { it.title }))
                .map { e ->
                    val day = java.time.Instant.ofEpochMilli(e.startAt).atZone(ZONE).toLocalDate()
                    val time = if (e.allDay != 0L) "all day" else "${hhmm(e.startAt)}-${hhmm(e.endAt)}"
                    "- ${dayShort(day)} $day $time ${cut(e.title, PHONE_LINE_MAX)}"
                }
            examLines = db.examQueries.selectAll().executeAsList()
                .filter { it.deleted == 0L && it.localDate >= today.toString() && it.localDate <= lastDay }
                .sortedWith(compareBy({ it.localDate }, { it.time ?: "" }, { it.title }))
                .map { x -> "- ${x.localDate}${x.time?.let { " $it" } ?: ""} ${cut(x.title, PHONE_LINE_MAX)}" }
            taskLines = db.taskQueries.selectAll().executeAsList()
                .filter { it.deleted == 0L && it.done == 0L }
                .sortedWith(compareBy({ it.dueDate ?: "9999" }, { it.updatedAt })).take(PHONE_TASKS_MAX)
                .map { t -> "- " + (t.dueDate?.let { "due $it " } ?: "") + cut(t.title, PHONE_LINE_MAX) }
            val checkin = db.checkinQueries.selectAll().executeAsList().firstOrNull { it.deleted == 0L && it.localDate == today.toString() }
            checkinLine =
                if (checkin != null) "Check-in today: mood ${checkin.mood} energy ${checkin.energy} sleep ${checkin.sleepQuality} stress ${checkin.stress}\n"
                else "Check-in today: none yet\n"
            mealLines = db.mealQueries.selectAll().executeAsList()
                .filter { it.deleted == 0L && it.localDate == today.toString() }.sortedBy { it.eatenAt }
                .map { m -> "- ${m.type.lowercase()} ${cut(m.text, PHONE_LINE_MAX)}" }
            val spending = db.spendingQueries.selectAll().executeAsList().filter { it.deleted == 0L }
            val todays = spending.filter { it.localDate == today.toString() }
            spendTotal = todays.sumOf { it.amountAgorot }
            spendLines = todays.sortedBy { it.updatedAt }
                .map { s -> "- ${money(s.amountAgorot)} ${s.category}" + (s.note?.takeIf { it.isNotBlank() }?.let { " ${cut(it, 40)}" } ?: "") }
            weekTotal = spending.filter { it.localDate >= weekStart && it.localDate <= today.toString() }.sumOf { it.amountAgorot }
        }
        val weekLine = "Spending this week (Sunday to today): ${money(weekTotal)}\n"
        val dreamLine = if (includeDreams) {
            // the dream tables are read in one place only (ChatContext, behind the dream wall): take its newest dream line
            val full = ChatContext.build(db, today, nowMs, ChatContext.MAX_CHARS, includeDreams = true)
            val section = full.substringAfter("## Dreams", "").substringAfter('\n', "")
            val first = section.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("## ") }
            if (first != null) "Last dream: ${cut(first, DREAM_MAX)}\n" else ""
        } else ""

        // the fixed lines (date, check-in, this week's total, the dream) are never cut; the sections share what is left
        var left = maxChars - header.length - checkinLine.length - weekLine.length - dreamLine.length
        fun share(head: String, lines: List<String>, cap: Int): String {
            val text = section(head, lines, minOf(cap, left))
            left -= text.length
            return text
        }
        val events = share("Events, next $PHONE_DAYS days:", eventLines, EVENTS_CAP)
        val exams = if (examLines.isEmpty()) "" else share("Exams, next $PHONE_DAYS days:", examLines, EXAMS_CAP)
        val tasks = if (taskLines.isEmpty()) "" else share("Open tasks:", taskLines, TASKS_CAP)
        val meals = share("Meals today (${mealLines.size}):", mealLines, MEALS_CAP)
        val spend = share("Spending today (${spendLines.size} entries, total ${money(spendTotal)}):", spendLines, SPENDING_CAP)
        val out = header + events + exams + tasks + checkinLine + meals + spend + weekLine + dreamLine
        if (out.length <= maxChars) return out
        var end = maxChars
        if (end > 0 && out[end - 1].isHighSurrogate()) end--
        return out.substring(0, end)
    }

    private fun dayShort(day: LocalDate): String = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    private fun buildHp(db: AssistantDb, nowMs: Long, includeDreams: Boolean, maxChars: Int): String {
        val today = localDateOf(Instant.fromEpochMilliseconds(nowMs))
        val out = StringBuilder("Today is $today (Israel time).\n")
        db.transaction {
            val events = db.cal_eventQueries.selectAll().executeAsList()
                .filter { it.deleted == 0L && localDateOf(Instant.fromEpochMilliseconds(it.startAt)) == today }
                .sortedBy { it.startAt }.take(EVENTS_MAX)
            if (events.isNotEmpty()) {
                out.append("Events today:\n")
                for (e in events) {
                    val time = if (e.allDay != 0L) "all day" else "${hhmm(e.startAt)}-${hhmm(e.endAt)}"
                    out.append("- $time ${cut(e.title, LINE_MAX)}\n")
                }
            }
            val tasks = db.taskQueries.selectAll().executeAsList()
                .filter { it.deleted == 0L && it.done == 0L }
                .sortedWith(compareBy({ it.dueDate ?: "9999" }, { it.updatedAt })).take(TASKS_MAX)
            if (tasks.isNotEmpty()) {
                out.append("Open tasks:\n")
                for (t in tasks) out.append("- ").append(t.dueDate?.let { "due $it " } ?: "").append(cut(t.title, LINE_MAX)).append('\n')
            }
            val checkin = db.checkinQueries.selectAll().executeAsList().firstOrNull { it.deleted == 0L && it.localDate == today }
            out.append(
                if (checkin != null) "Check-in today: mood ${checkin.mood} energy ${checkin.energy} sleep ${checkin.sleepQuality} stress ${checkin.stress}\n"
                else "Check-in today: none yet\n",
            )
            val meals = db.mealQueries.selectAll().executeAsList().count { it.deleted == 0L && it.localDate == today }
            val spending = db.spendingQueries.selectAll().executeAsList().count { it.deleted == 0L && it.localDate == today }
            out.append("Logged today: $meals meals, $spending spending entries\n")
        }
        if (includeDreams) {
            // the dream tables are read in one place only (ChatContext, behind the dream wall): take its newest dream line
            val full = ChatContext.build(db, today, nowMs, ChatContext.MAX_CHARS, includeDreams = true)
            val section = full.substringAfter("## Dreams", "").substringAfter('\n', "")
            val first = section.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("## ") }
            if (first != null) out.append("Last dream: ").append(cut(first, 300)).append('\n')
        }
        return if (out.length <= maxChars) out.toString() else out.substring(0, maxChars)
    }
}
