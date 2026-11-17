package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Task
import dev.tim.assistant.google.hhmm
import dev.tim.assistant.ui.forms.formatAgorot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The read-only answers of the code rungs (PLAN 18.5 step 3, "ask_*"). Each reads through the [Repository] and builds its own
 * short text from names, times and numbers; it never reuses the read tools' text, which contains ids. The clock is Jerusalem time.
 */
class AskAnswers(private val repo: Repository, private val clock: () -> Long) {
    companion object {
        const val TASKS_MAX = 8
        const val EVENTS_MAX = 12
        private val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
        private val DAY_FORMAT = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH)
    }

    private fun todayDate(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate()

    private fun clean(text: String, max: Int): String {
        val c = text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        if (c.length <= max) return c
        var end = max
        if (end > 0 && c[end - 1].isHighSurrogate()) end--
        return c.substring(0, end).trimEnd() + "..."
    }

    private fun money(agorot: Long): String = formatAgorot(agorot).removeSuffix(".00") + " ₪"

    private fun eventsOn(day: LocalDate, all: List<Cal_event> = repo.calendarEvents()): List<Cal_event> {
        val start = day.atStartOfDay(ZONE).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        return all.filter { it.startAt < end && it.endAt > start }.sortedWith(compareBy({ it.allDay == 0L }, { it.startAt }, { it.title }))
    }

    private fun eventLine(e: Cal_event): String =
        (if (e.allDay != 0L) "all day" else "${hhmm(e.startAt)}-${hhmm(e.endAt)}") + " " + clean(e.title, 80)

    private fun dayName(day: LocalDate): String = ReplyTemplates.dayWord(day.toString(), todayDate())

    /** Today's events with times, the open task count and the next one due, whether today's check-in exists. */
    fun todayOverview(): String {
        val t = todayDate()
        val events = eventsOn(t)
        val tasks = repo.openTasks()
        val out = StringBuilder()
        if (events.isEmpty()) out.append("Nothing on your calendar today.")
        else out.append("Today: ").append(events.take(8).joinToString("; ") { eventLine(it) }).append(if (events.size > 8) " and ${events.size - 8} more." else ".")
        if (tasks.isEmpty()) out.append(" You have no open tasks.")
        else {
            out.append(" You have ${tasks.size} open task${if (tasks.size == 1) "" else "s"}")
            val next = tasks.firstOrNull { it.dueDate != null }
            if (next != null) out.append("; the next one due is ${clean(next.title, 80)} (${ReplyTemplates.dayWord(next.dueDate!!, t)}).") else out.append(".")
        }
        out.append(if (repo.checkinForDate(t.toString()) != null) " Today's check-in is done." else " You haven't done today's check-in yet.")
        return out.toString()
    }

    /** One day's overview in the shape of [todayOverview]: that day's events with times, then the open tasks due on it. Today gives the today overview. */
    fun dayOverview(dayIso: String): String {
        val day = try { LocalDate.parse(dayIso) } catch (_: Exception) { return todayOverview() }
        if (day == todayDate()) return todayOverview()
        val name = dayName(day)
        val on = if (name == "today" || name == "tomorrow") name else "on $name"
        val events = eventsOn(day)
        val due = repo.openTasks().filter { it.dueDate == day.toString() }
        val out = StringBuilder()
        if (events.isEmpty()) out.append("Nothing on your calendar $on.")
        else out.append(cap(name.takeIf { it == "tomorrow" } ?: "On $name")).append(": ").append(events.take(8).joinToString("; ") { eventLine(it) })
            .append(if (events.size > 8) " and ${events.size - 8} more." else ".")
        if (due.isEmpty()) out.append(" No tasks due $on.")
        else {
            out.append(" Tasks due $on: ").append(due.take(TASKS_MAX).joinToString("; ") { clean(it.title, 80) })
            out.append(if (due.size > TASKS_MAX) ". And ${due.size - TASKS_MAX} more." else ".")
        }
        return out.toString()
    }

    private fun taskLine(task: Task): String = clean(task.title, 80) + (task.dueDate?.let { " (due ${ReplyTemplates.dayWord(it, todayDate())})" } ?: "")

    /** Up to [TASKS_MAX] open tasks by due date; [whenWord] is "today", "tomorrow" or "week" (due through the coming Saturday). */
    fun tasks(whenWord: String?): String {
        val t = todayDate()
        val open = repo.openTasks()
        val (list, label) = when (whenWord) {
            "today" -> open.filter { it.dueDate == t.toString() } to "due today"
            "tomorrow" -> open.filter { it.dueDate == t.plusDays(1).toString() } to "due tomorrow"
            "week" -> {
                val saturday = t.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                open.filter { d -> d.dueDate?.let { it >= t.toString() && it <= saturday.toString() } == true } to "due this week"
            }
            else -> open to "open"
        }
        if (list.isEmpty()) return if (whenWord == null) "You have no open tasks." else "You have no tasks $label."
        val shown = list.take(TASKS_MAX)
        val head = if (whenWord == null) "Your open tasks:" else "Tasks $label:"
        val more = if (list.size > shown.size) " And ${list.size - shown.size} more." else ""
        return "$head " + shown.joinToString("; ") { taskLine(it) } + "." + more
    }

    /** The events of [dayIso] (yyyy-MM-dd), including events that span or last all day. */
    fun events(dayIso: String): String {
        val day = try { LocalDate.parse(dayIso) } catch (_: Exception) { todayDate() }
        val name = dayName(day)
        val events = eventsOn(day)
        if (events.isEmpty()) return if (name == "today" || name == "tomorrow") "Nothing on your calendar $name." else "Nothing on your calendar on $name."
        val shown = events.take(8)
        val lead = when (name) { "today" -> "Today"; "tomorrow" -> "Tomorrow"; else -> "On $name" }
        return "$lead: " + shown.joinToString("; ") { eventLine(it) } + "." + if (events.size > shown.size) " And ${events.size - shown.size} more." else ""
    }

    /** The total and per-category spending since Sunday (through today), in shekels. */
    fun spendingWeek(): String {
        val t = todayDate()
        val sunday = t.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val rows = repo.spending().filter { it.localDate >= sunday.toString() && it.localDate <= t.toString() }
        if (rows.isEmpty()) return "You haven't spent anything since Sunday."
        val total = rows.sumOf { it.amountAgorot }
        val byCategory = rows.groupBy { it.category }.map { (c, r) -> c to r.sumOf { it.amountAgorot } }.sortedByDescending { it.second }
        return "Since Sunday you've spent ${money(total)}: " + byCategory.joinToString(", ") { (c, a) -> "${c.lowercase()} ${money(a)}" } + "."
    }

    // ---- day questions (Phase 20, SPEC section 6): the period carries its own words for the reply ----

    private fun kd(d: LocalDate) = kotlinx.datetime.LocalDate.parse(d.toString())

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** "on Friday 13 November" and "in November" read badly after "for": the lead word goes. */
    private fun forLabel(label: String) = label.removePrefix("on ").removePrefix("in ")

    private fun fullDay(d: LocalDate): String = d.format(DAY_FORMAT)

    /** What was spent in [period]: a total, then the top four categories, or one category's total. */
    fun spending(period: Period, category: String? = null): String {
        val rows = repo.spendingIn(kd(period.from), kd(period.to)).filter { category == null || it.category.equals(category, ignoreCase = true) }
        if (rows.isEmpty()) return "No spending logged ${period.label}" + (category?.let { " on ${it.lowercase()}" } ?: "") + "."
        val total = rows.sumOf { it.amountAgorot }
        val head = "${cap(period.label)} you spent ${money(total)}"
        if (category != null) return "$head on ${category.lowercase()}."
        val byCategory = rows.groupBy { it.category }.map { (c, r) -> c to r.sumOf { it.amountAgorot } }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }).take(4)
        return "$head: " + byCategory.joinToString(", ") { (c, a) -> "${c.lowercase()} ${money(a)}" } + "."
    }

    /** One line per day of [period] that has events, at most [EVENTS_MAX] events in all. */
    fun events(period: Period): String {
        val all = repo.calendarEvents()
        val lines = ArrayList<String>()
        var total = 0
        var shown = 0
        var d = period.from
        var guard = 0
        while (!d.isAfter(period.to) && guard++ < 400) {
            val ev = eventsOn(d, all)
            if (ev.isNotEmpty()) {
                total += ev.size
                val take = ev.take((EVENTS_MAX - shown).coerceAtLeast(0))
                shown += take.size
                if (take.isNotEmpty()) lines += cap(ReplyTemplates.dayWord(d.toString(), todayDate())) + ": " + take.joinToString("; ") { eventLine(it) } + "."
            }
            d = d.plusDays(1)
        }
        if (total == 0) return "Nothing on your calendar ${period.label}."
        return "On your calendar ${period.label}:\n" + lines.joinToString("\n") + if (total > shown) "\nAnd ${total - shown} more." else ""
    }

    /** Open tasks due inside [period]. */
    fun tasksDue(period: Period): String {
        val list = repo.openTasks().filter { t -> t.dueDate?.let { it >= period.from.toString() && it <= period.to.toString() } == true }
        if (list.isEmpty()) return "Nothing due ${period.label}."
        val shown = list.take(TASKS_MAX)
        val more = if (list.size > shown.size) " And ${list.size - shown.size} more." else ""
        return "Tasks due ${period.label}: " + shown.joinToString("; ") { taskLine(it) } + "." + more
    }

    private fun normName(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun examWhen(e: dev.tim.assistant.db.Exam): String {
        val day = ReplyTemplates.dayWord(e.localDate, todayDate())
        val at = e.time?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
        return clean(e.title, 80) + " is " + (if (day == "today" || day == "tomorrow") day else "on $day") + at
    }

    /** One exam by name, the first three that match, or the next three exams when [name] is null. */
    fun exam(name: String?): String {
        val today = todayDate().toString()
        val upcoming = repo.exams().filter { it.deleted == 0L && it.localDate >= today }
            .sortedWith(compareBy({ it.localDate }, { it.time ?: "" }, { it.title }))
        val q = name?.let { normName(it) }.orEmpty()
        val hits = if (name == null) upcoming.take(3) else {
            if (q.isEmpty()) emptyList() else upcoming.filter { e -> normName(e.title).let { n -> n.isNotEmpty() && (n.contains(q) || q.contains(n)) } }
        }
        return when {
            hits.isEmpty() -> "I don't see an exam like that."
            hits.size == 1 -> examWhen(hits[0]) + "."
            name == null -> "Your next exams: " + hits.joinToString("; ") { examWhen(it) } + "."
            else -> "A few exams match: " + hits.take(3).joinToString("; ") { examWhen(it) } + "."
        }
    }

    private fun duration(minutes: Long): String = "${minutes / 60} h ${minutes % 60} min"

    /** Sleep, steps or resting heart rate for a day or a week. A missing value is skipped, never counted as zero. */
    fun health(metric: String, period: Period): String {
        val from = period.from.toString()
        val to = period.to.toString()
        val values = repo.healthDays().filter { it.localDate in from..to }.mapNotNull { h ->
            val v = when (metric) {
                "sleep" -> h.sleepMinutes?.takeIf { it > 0 }
                "steps" -> h.steps
                else -> h.restingHr?.takeIf { it > 0 }
            }
            v?.let { LocalDate.parse(h.localDate) to it }
        }.sortedBy { it.first }
        val noun = when (metric) { "sleep" -> "sleep"; "steps" -> "steps"; else -> "heart rate" }
        if (values.isEmpty()) return "No $noun data for ${forLabel(period.label)}."
        fun show(v: Long) = if (metric == "sleep") duration(v) else v.toString()
        if (period.singleDay) {
            val v = values[0].second
            return when (metric) {
                "sleep" -> "You slept ${show(v)} ${period.label}."
                "steps" -> "$v steps ${period.label}."
                else -> "Resting heart rate $v ${period.label}."
            }
        }
        val avg = Math.round(values.sumOf { it.second }.toDouble() / values.size)
        val top = values.maxByOrNull { it.second }!!
        val low = values.minByOrNull { it.second }!!
        val (best, worst) = if (metric == "heart") low to top else top to low
        val head = when (metric) { "sleep" -> "Sleep"; "steps" -> "Steps"; else -> "Resting heart rate" }
        val unit = if (metric == "steps") " a day" else ""
        return "$head ${period.label}: average ${show(avg)}$unit over ${values.size} day${if (values.size == 1) "" else "s"}, " +
            "best ${fullDay(best.first)} ${show(best.second)}, worst ${fullDay(worst.first)} ${show(worst.second)}."
    }

    /** What was eaten ([what] = "list") or the calories ([what] = "calories") of a day. */
    fun meals(what: String, period: Period): String {
        val meals = repo.mealsIn(kd(period.from), kd(period.to)).sortedBy { it.eatenAt }
        if (meals.isEmpty()) return "No meals logged ${period.label}."
        if (what == "calories") {
            val estimated = meals.filter { it.kcal != null }
            return "About ${estimated.sumOf { it.kcal ?: 0L }} kcal ${period.label} (${estimated.size} of ${meals.size} meals estimated)."
        }
        val shown = meals.take(8)
        val more = if (meals.size > shown.size) " And ${meals.size - shown.size} more." else ""
        return "${cap(period.label)} you ate: " + shown.joinToString("; ") { clean(it.text, 60) } + "." + more
    }

    /** The four scores of a day, or their averages over a week. */
    fun checkin(period: Period): String {
        val list = repo.checkinsIn(kd(period.from), kd(period.to)).sortedBy { it.localDate }
        if (list.isEmpty()) return "No check-in ${period.label}."
        if (period.singleDay) {
            val c = list[0]
            return "Check-in ${period.label}: mood ${c.mood}, energy ${c.energy}, sleep ${c.sleepQuality}, stress ${c.stress}."
        }
        fun avg(f: (dev.tim.assistant.db.Checkin) -> Long) = String.format(java.util.Locale.ROOT, "%.1f", list.sumOf(f).toDouble() / list.size)
        return "Check-in averages ${period.label} (${list.size} check-in${if (list.size == 1) "" else "s"}): " +
            "mood ${avg { it.mood }}, energy ${avg { it.energy }}, sleep ${avg { it.sleepQuality }}, stress ${avg { it.stress }}."
    }
}
