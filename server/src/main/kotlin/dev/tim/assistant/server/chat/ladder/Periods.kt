package dev.tim.assistant.server.chat.ladder

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A day or a run of days named in a question. [word] is the stable name stored for later ("today", "last_week", "weekday:friday",
 * "date:2026-11-13"...); [label] is the wording used in replies ("yesterday", "last month", "on Friday 13 November").
 */
data class Period(val from: LocalDate, val to: LocalDate, val word: String, val label: String) {
    val singleDay: Boolean get() = from == to
}

/** Which way a bare weekday, a bare date and a running week or month lean. */
enum class Lean { PAST, FUTURE }

/** The period phrase found in a text and what is left of the text without it. [lead] is the on/for/in/during word that stood before it. */
data class Found(val period: Period, val rest: String, val lead: String?)

/**
 * No period phrase ([None]); exactly one ([One]); or more than one, or one that names no real day ([Many]).
 * "Finds a period" means [One] or [Many]; [Many] is never read as today.
 */
sealed interface Scan {
    data object None : Scan
    data class One(val found: Found) : Scan
    data object Many : Scan
}

/**
 * Pure period parser (PLAN 20.3): no database, clock or network; today is a parameter. Weeks run Sunday to Saturday.
 * Bare weekdays and `next <weekday>` resolve exactly as [SlotParsers.scanDate] resolves them when the lean is FUTURE.
 */
object Periods {
    private val I = RegexOption.IGNORE_CASE
    private val B = "(?<![\\p{L}\\p{N}])"
    private val E = "(?![\\p{L}\\p{N}])"
    private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }
    private val dayAlt = WEEKDAYS.keys.joinToString("|")
    private val monthAlt = MONTHS.joinToString("|")
    private val monthNumbers = MONTHS.withIndex().associate { (i, m) -> m to i + 1 }
    private val lead = "(?:(on|for|in|during)\\s+(?:the\\s+)?)?"
    private val possessive = "(?:'s)?"
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val dayYearFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

    /** A phrase pattern: group 1 is the lead word, the next groups feed [word]; [word] null means the phrase names no real day. */
    private class Pat(val regex: Regex, val word: (MatchResult) -> String?)

    private fun pat(body: String, word: (MatchResult) -> String?) = Pat(Regex("$B$lead$body$possessive$E", I), word)

    private val pats: List<Pat> = listOf(
        pat("last\\s+night") { "last_night" },
        pat("(\\d{1,3})\\s+days?\\s+ago") { m -> m.groupValues[2].toInt().takeIf { it >= 1 }?.let { "days_ago:$it" } },
        pat("last\\s+week") { "last_week" },
        pat("next\\s+week") { "next_week" },
        pat("this\\s+week") { "this_week" },
        pat("last\\s+month") { "last_month" },
        pat("this\\s+month") { "this_month" },
        pat("last\\s+($dayAlt)") { m -> "last_weekday:${m.groupValues[2].lowercase()}" },
        pat("next\\s+($dayAlt)") { m -> "next_weekday:${m.groupValues[2].lowercase()}" },
        Pat(Regex("(?<![\\p{L}\\p{N}-])$lead(\\d{4})-(\\d{2})-(\\d{2})(?![\\p{L}\\p{N}-])$possessive", I)) { m ->
            safeDate(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())?.let { "date:$it" }
        },
        Pat(Regex("(?<![\\p{L}\\p{N}/.:-])$lead(\\d{1,2})/(\\d{1,2})(?![\\p{L}\\p{N}/:-])", I)) { m -> "dm:${m.groupValues[2]}/${m.groupValues[3]}" },
        Pat(Regex("(?<![\\p{L}\\p{N}/.:-])$lead(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?($monthAlt)$E", I)) { m ->
            "dm:${m.groupValues[2]}/${monthNumbers.getValue(m.groupValues[3].lowercase())}"
        },
        Pat(Regex("$B$lead($monthAlt)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![\\p{L}\\p{N}:])", I)) { m ->
            "dm:${m.groupValues[3]}/${monthNumbers.getValue(m.groupValues[2].lowercase())}"
        },
        pat("($dayAlt)") { m -> "weekday:${m.groupValues[2].lowercase()}" },
        pat("(today|tonight)") { "today" },
        pat("yesterday") { "yesterday" },
        pat("tomorrow") { "tomorrow" },
        pat("($monthAlt)") { m -> "month:${m.groupValues[2].lowercase()}" },
    )

    private class Hit(val range: IntRange, val lead: String?, val word: String?)

    private val extraSpaces = Regex("\\s+")
    private val edgePunct = Regex("^[\\s,;:.-]+|[\\s,;:.-]+$")
    private fun tidy(s: String) = edgePunct.replace(extraSpaces.replace(s, " "), "")

    private fun hits(text: String): List<Hit> {
        val accepted = ArrayList<Hit>()
        for (p in pats) {
            for (m in p.regex.findAll(text)) {
                // "may" is also a verb: it names a month only after a lead word
                if (m.groupValues.getOrNull(2)?.lowercase() == "may" && m.groupValues[1].isEmpty() && p.word(m)?.startsWith("month:") == true) continue
                if (accepted.any { it.range.first <= m.range.last && m.range.first <= it.range.last }) continue
                accepted += Hit(m.range, m.groupValues[1].lowercase().ifEmpty { null }, p.word(m))
            }
        }
        return accepted.sortedBy { it.range.first }
    }

    /** The period phrase of [text]. A phrase that cannot be placed (31/2, a date a lean cannot reach) counts as [Scan.Many]. */
    fun find(text: String, today: LocalDate, lean: Lean): Scan {
        val hs = hits(text)
        if (hs.isEmpty()) return Scan.None
        if (hs.size > 1) return Scan.Many
        val h = hs.single()
        val period = h.word?.let { resolve(it, today, lean) } ?: return Scan.Many
        val rest = tidy(text.removeRange(h.range))
        return Scan.One(Found(period, rest, h.lead))
    }

    /** [find]'s One case, only when the phrase (with its lead word) is the first words or the last words of the text. */
    fun findAtEdge(text: String, today: LocalDate, lean: Lean): Found? {
        val t = text.trim()
        val hs = hits(t)
        val h = hs.singleOrNull() ?: return null
        val atStart = t.substring(0, h.range.first).isBlank()
        val atEnd = t.substring(h.range.last + 1).all { it.isWhitespace() || it in ",;:.!?-" }
        if (!atStart && !atEnd) return null
        val one = find(t, today, lean) as? Scan.One ?: return null
        return one.found
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (_: java.time.DateTimeException) { null }

    private fun label(d: LocalDate, today: LocalDate) = "on " + d.format(if (d.year == today.year) dayFmt else dayYearFmt)

    private fun single(word: String, d: LocalDate, label: String) = Period(d, d, word, label)

    private fun sunday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    private fun nextWeekday(day: DayOfWeek, today: LocalDate): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek != day) d = d.plusDays(1)
        return d
    }

    private fun lastWeekday(day: DayOfWeek, today: LocalDate): LocalDate = sunday(today).minusDays(7).plusDays((day.value % 7).toLong())

    private fun nextNamedWeekday(day: DayOfWeek, today: LocalDate): LocalDate =
        today.plusDays((8 - today.dayOfWeek.value).toLong()).plusDays((day.value - 1).toLong())

    private fun recentWeekday(day: DayOfWeek, today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(day))

    /** Rebuilds a period from its stable [word]; null when the word is unknown or names no real day. */
    fun resolve(word: String, today: LocalDate, lean: Lean): Period? {
        val past = lean == Lean.PAST
        val arg = word.substringAfter(':', "")
        return when (word.substringBefore(':')) {
            "today" -> single(word, today, "today")
            "yesterday" -> single(word, today.minusDays(1), "yesterday")
            "tomorrow" -> single(word, today.plusDays(1), "tomorrow")
            "last_night" -> single(word, today, "last night")
            "this_week" -> {
                val s = sunday(today)
                Period(s, if (past) today else s.plusDays(6), word, "this week")
            }
            "last_week" -> sunday(today).minusDays(7).let { Period(it, it.plusDays(6), word, "last week") }
            "next_week" -> sunday(today).plusDays(7).let { Period(it, it.plusDays(6), word, "next week") }
            "this_month" -> YearMonth.from(today).let { Period(it.atDay(1), if (past) today else it.atEndOfMonth(), word, "this month") }
            "last_month" -> YearMonth.from(today).minusMonths(1).let { Period(it.atDay(1), it.atEndOfMonth(), word, "last month") }
            "weekday" -> WEEKDAYS[arg]?.let { d -> (if (past) recentWeekday(d, today) else nextWeekday(d, today)).let { single(word, it, label(it, today)) } }
            "last_weekday" -> WEEKDAYS[arg]?.let { d -> lastWeekday(d, today).let { single(word, it, label(it, today)) } }
            "next_weekday" -> WEEKDAYS[arg]?.let { d -> nextNamedWeekday(d, today).let { single(word, it, label(it, today)) } }
            "month" -> monthNumbers[arg]?.let { mo ->
                var year = today.year
                if (past && mo > today.monthValue) year-- else if (!past && mo < today.monthValue) year++
                val ym = YearMonth.of(year, mo)
                Period(ym.atDay(1), ym.atEndOfMonth(), word, "in " + MONTHS[mo - 1].replaceFirstChar { it.uppercase() } + if (year != today.year) " $year" else "")
            }
            "date" -> try { LocalDate.parse(arg) } catch (_: Exception) { null }?.let { single(word, it, label(it, today)) }
            "days_ago" -> arg.toLongOrNull()?.takeIf { it >= 1 }?.let { today.minusDays(it) }?.let { single(word, it, label(it, today)) }
            // a day and month with no year: the stored word is the date it was resolved to, so it never moves
            "dm" -> resolveDayMonth(arg, today, past)?.let { single("date:$it", it, label(it, today)) }
            else -> null
        }
    }

    private fun resolveDayMonth(arg: String, today: LocalDate, past: Boolean): LocalDate? {
        val d = arg.substringBefore('/').toIntOrNull() ?: return null
        val mo = arg.substringAfter('/').toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        if (past) {
            for (y in today.year downTo today.year - 8) safeDate(y, mo, d)?.takeIf { !it.isAfter(today) }?.let { return it }
            return null
        }
        for (y in today.year..today.year + 8) safeDate(y, mo, d)?.takeIf { !it.isBefore(today) }?.let { return it }
        return null
    }
}
