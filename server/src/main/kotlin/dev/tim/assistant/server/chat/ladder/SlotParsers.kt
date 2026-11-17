package dev.tim.assistant.server.chat.ladder

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pure slot parsers for the code router (SPEC section 4, PLAN 18.3 step 4).
 * Every date function takes `today` as a parameter: no clock, database or network here.
 */
object SlotParsers {
    private val I = RegexOption.IGNORE_CASE

    /** Result of looking for a date phrase. [found] false means none; [found] true with a null [date] means "could not parse it". */
    data class DateScan(val found: Boolean, val date: LocalDate?, val rest: String) {
        val ok: Boolean get() = !found || date != null
    }

    private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase() }
    private val monthNumbers = MONTHS.withIndex().associate { (i, m) -> m to i + 1 }
    private val weekdayAlt = weekdays.keys.joinToString("|")
    private val monthAlt = MONTHS.joinToString("|")
    private val lead = "(?:(?:on|by|due|until)\\s+)?"

    private data class DatePattern(val regex: Regex, val resolve: (MatchResult, LocalDate) -> LocalDate?)

    private fun nextWeekday(day: DayOfWeek, today: LocalDate): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek != day) d = d.plusDays(1)
        return d
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        try { LocalDate.of(year, month, day) } catch (_: java.time.DateTimeException) { null }

    // Longest and most specific phrases first, so "next friday" is not read as "friday".
    private val datePatterns: List<DatePattern> = listOf(
        DatePattern(Regex("(?<![\\p{L}\\p{N}])${lead}next\\s+week(?![\\p{L}\\p{N}])", I)) { _, t -> t.plusDays(7) },
        DatePattern(Regex("(?<![\\p{L}\\p{N}])${lead}next\\s+($weekdayAlt)(?![\\p{L}\\p{N}])", I)) { m, t ->
            val day = weekdays.getValue(m.groupValues[1].lowercase())
            val nextMonday = t.plusDays((8 - t.dayOfWeek.value).toLong())
            nextMonday.plusDays((day.value - 1).toLong())
        },
        DatePattern(Regex("(?<![\\p{L}\\p{N}])${lead}in\\s+(\\d{1,3})\\s+days?(?![\\p{L}\\p{N}])", I)) { m, t ->
            val n = m.groupValues[1].toLong()
            if (n >= 1) t.plusDays(n) else null
        },
        DatePattern(Regex("(?<![\\p{L}\\p{N}-])${lead}(\\d{4})-(\\d{2})-(\\d{2})(?![\\p{L}\\p{N}-])")) { m, t ->
            safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.takeIf { !it.isBefore(t) }
        },
        DatePattern(Regex("(?<![\\p{L}\\p{N}/.:-])${lead}(\\d{1,2})/(\\d{1,2})(?![\\p{L}\\p{N}/:-])")) { m, t ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val thisYear = safeDate(t.year, mo, d)
            when {
                thisYear == null -> if (mo == 2 && d == 29) safeDate(t.year + 1, mo, d) ?: safeDate(t.year + 4, mo, d) else null
                thisYear.isBefore(t) -> safeDate(t.year + 1, mo, d)
                else -> thisYear
            }
        },
        DatePattern(Regex("(?<![\\p{L}\\p{N}/.:-])${lead}(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?($monthAlt)(?![\\p{L}\\p{N}])", I)) { m, t ->
            val d = m.groupValues[1].toInt()
            val mo = monthNumbers.getValue(m.groupValues[2].lowercase())
            val thisYear = safeDate(t.year, mo, d)
            when {
                thisYear == null -> null
                thisYear.isBefore(t) -> safeDate(t.year + 1, mo, d)
                else -> thisYear
            }
        },
        DatePattern(Regex("(?<![\\p{L}\\p{N}])${lead}($monthAlt)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![\\p{L}\\p{N}:])", I)) { m, t ->
            val mo = monthNumbers.getValue(m.groupValues[1].lowercase())
            val d = m.groupValues[2].toInt()
            val thisYear = safeDate(t.year, mo, d)
            when {
                thisYear == null -> null
                thisYear.isBefore(t) -> safeDate(t.year + 1, mo, d)
                else -> thisYear
            }
        },
        DatePattern(Regex("(?<![\\p{L}\\p{N}])${lead}($weekdayAlt)(?![\\p{L}\\p{N}])", I)) { m, t ->
            nextWeekday(weekdays.getValue(m.groupValues[1].lowercase()), t)
        },
        DatePattern(Regex("(?<![\\p{L}\\p{N}])${lead}(today|tonight)(?![\\p{L}\\p{N}])", I)) { _, t -> t },
        DatePattern(Regex("(?<![\\p{L}\\p{N}])${lead}tomorrow(?![\\p{L}\\p{N}])", I)) { _, t -> t.plusDays(1) },
    )

    private val extraSpaces = Regex("\\s+")
    private val trailingPunct = Regex("^[\\s,;:.-]+|[\\s,;:.-]+$")

    private fun tidy(s: String): String = trailingPunct.replace(extraSpaces.replace(s, " "), "")

    /**
     * Finds one date phrase. The phrase is removed from [DateScan.rest]. A message with a second date phrase,
     * or with any other time-of-day-other-than-today word left over, is reported as found-but-unparsed.
     */
    fun scanDate(text: String, today: LocalDate): DateScan {
        for (p in datePatterns) {
            val m = p.regex.find(text) ?: continue
            val rest = tidy(text.removeRange(m.range))
            val date = p.resolve(m, today)
            val leftover = Guards.otherDay(rest) ||
                Regex("(?<![\\p{L}\\p{N}])(?:today|tonight|tomorrow)(?![\\p{L}\\p{N}])", I).containsMatchIn(rest)
            return DateScan(true, if (leftover) null else date, rest)
        }
        // No parseable phrase: leftover date words (yesterday, last week, a bad date...) mean "unparsed".
        val stray = Guards.otherDay(text)
        return DateScan(stray, null, tidy(text))
    }

    fun date(text: String, today: LocalDate): LocalDate? = scanDate(text, today).takeIf { it.found }?.date

    fun isoDate(d: LocalDate): String = d.format(DateTimeFormatter.ISO_LOCAL_DATE)

    private val timeRegexes = listOf(
        Regex("(?<![\\p{L}\\p{N}:])(\\d{1,2}):(\\d{2})\\s*(am|pm)?(?![\\p{L}\\p{N}:])", I),
        Regex("(?<![\\p{L}\\p{N}:])(\\d{1,2})\\s*(am|pm)(?![\\p{L}\\p{N}])", I),
    )

    private fun timeMatches(text: String): List<LocalTime?> {
        val out = ArrayList<LocalTime?>()
        for ((i, r) in timeRegexes.withIndex()) {
            for (m in r.findAll(text)) {
                val h = m.groupValues[1].toInt()
                val min = if (i == 0) m.groupValues[2].toInt() else 0
                val mer = m.groupValues[if (i == 0) 3 else 2].lowercase()
                out += toTime(h, min, mer)
            }
        }
        return out
    }

    private fun toTime(h: Int, min: Int, mer: String): LocalTime? {
        if (min !in 0..59) return null
        val hour = when (mer) {
            "am" -> if (h in 1..12) h % 12 else return null
            "pm" -> if (h in 1..12) h % 12 + 12 else return null
            else -> if (h in 0..23) h else return null
        }
        return LocalTime.of(hour, min)
    }

    /** True when the text holds any clock time (HH:mm, 9am, 6:30pm), parseable or not. */
    fun hasTime(text: String): Boolean = timeRegexes.any { it.containsMatchIn(text) }

    /** The one clock time in the text; null when there is none, more than one, or it does not parse. */
    fun time(text: String): LocalTime? = timeMatches(text).singleOrNull()

    /** The text without its one clock time and the `at` directly before it; null when [time] finds no single time. */
    fun withoutTime(text: String): String? {
        if (time(text) == null) return null
        var out = text
        for (r in timeRegexes) {
            val withAt = Regex("(?:(?<![\\p{L}\\p{N}])at\\s+)?" + r.pattern, I)
            if (withAt.containsMatchIn(out)) { out = withAt.replaceFirst(out, " "); break }
        }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    fun formatTime(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("HH:mm"))

    /** "today" or "tomorrow" in the text; today by default. */
    fun day(text: String, today: LocalDate): LocalDate =
        if (Regex("(?<![\\p{L}\\p{N}])tomorrow(?![\\p{L}\\p{N}])", I).containsMatchIn(text)) today.plusDays(1) else today

    private val numberToken = Regex("(?<![\\p{L}\\p{N}.,/:-])(\\d+(?:,\\d{3})*(?:\\.\\d+)?)(?![\\d/:-]|[.,]\\d)")
    private val moneyWords = setOf("shekels", "shekel", "nis")
    private val currencySignBefore = Regex("[$€£]\\s*$")

    /**
     * Exactly one positive number, alone or with shekels/shekel/nis/the shekel sign. A number followed or preceded
     * by any other unit, currency word or sign (minutes, km, dollars, $, euros...) is not an amount. Returns the
     * plain number text ("35", "18.5") or null.
     */
    fun amount(text: String): String? {
        val cleaned = stripDateAndTime(text)
        val matches = numberToken.findAll(cleaned).toList()
        if (matches.size != 1) return null
        val m = matches.single()
        val before = cleaned.substring(0, m.range.first)
        val after = cleaned.substring(m.range.last + 1)
        if (currencySignBefore.containsMatchIn(before)) return null
        val next = Regex("^\\s*(\\S+)").find(after)?.groupValues?.get(1)?.trim('.', ',', ';', ':', '!', '?')
        if (next != null) {
            val nextLower = next.lowercase()
            val isShekel = nextLower in moneyWords || next.startsWith("₪")
            if (!isShekel && isUnitWord(next, nextLower)) return null
        }
        if (Regex("^\\s*[%$€£]").containsMatchIn(after)) return null
        val value = BigDecimal(m.groupValues[1].replace(",", ""))
        if (value.signum() <= 0) return null
        return value.stripTrailingZeros().toPlainString()
    }

    private val unitWords = setOf(
        "minutes", "minute", "mins", "min", "hours", "hour", "hrs", "hr", "seconds", "sec", "days", "day", "weeks", "week",
        "months", "years", "km", "kms", "kilometers", "kilometres", "m", "meters", "metres", "miles", "mile", "kg", "kgs",
        "g", "grams", "l", "liters", "litres", "ml", "dollars", "dollar", "usd", "bucks", "euros", "euro", "eur", "pounds",
        "pound", "gbp", "cents", "percent", "calories", "kcal", "cal", "steps", "points", "pages", "times", "x", "am", "pm",
        "people", "items", "tickets", "reps", "sets",
    )

    private fun isUnitWord(raw: String, lower: String): Boolean =
        lower in unitWords || (raw.isNotEmpty() && raw[0] in setOf('$', '€', '£', '%'))

    private fun stripDateAndTime(text: String): String {
        var s = text
        for (p in datePatterns) s = p.regex.replace(s, " ")
        for (r in timeRegexes) s = r.replace(s, " ")
        return s
    }

    /** `snack` gives SNACK; `meal`, `breakfast`, `lunch`, `dinner` give MEAL (the values of MEAL_TYPES); both kinds or none give null. */
    fun mealType(text: String): String? {
        val snack = Regex("(?<![\\p{L}\\p{N}])snack(?![\\p{L}\\p{N}])", I).containsMatchIn(text)
        val meal = Regex("(?<![\\p{L}\\p{N}])(?:meal|breakfast|lunch|dinner)(?![\\p{L}\\p{N}])", I).containsMatchIn(text)
        return when {
            snack && !meal -> "SNACK"
            meal && !snack -> "MEAL"
            else -> null
        }
    }

    // Categories exactly as SPENDING_CATEGORIES in Repository.kt; code never chooses Other.
    private val categoryKeywords: Map<String, List<String>> = linkedMapOf(
        "Food" to listOf(
            "lunch", "dinner", "breakfast", "brunch", "coffee", "tea", "snack", "snacks", "groceries", "supermarket", "restaurant", "cafe", "food", "pizza",
            "takeaway", "sushi", "burger", "burgers", "sandwich", "sandwiches", "salad", "pasta", "falafel", "shawarma", "hummus", "soup", "steak",
            "noodles", "juice", "smoothie", "cake", "dessert", "ice cream", "bagel", "croissant", "pastry", "burrito", "schnitzel", "shakshuka", "fries", "bakery",
        ),
        "Transport" to listOf("bus", "train", "taxi", "cab", "uber", "fuel", "petrol", "parking", "metro", "tram", "toll", "bus fare", "train fare"),
        "Shopping" to listOf(
            "clothes", "clothing", "shoes", "sneakers", "boots", "gift", "present", "shirt", "shirts", "t-shirt", "jeans", "jacket", "coat", "dress", "sweater",
            "hoodie", "pants", "trousers", "socks", "backpack",
        ),
        "Bills" to listOf("rent", "electricity", "internet", "phone bill", "water bill", "gas bill", "utilities", "insurance", "phone plan", "arnona"),
        "Fun" to listOf("movie", "movies", "cinema", "concert", "beer", "theatre", "theater", "museum", "bowling", "karaoke", "video game"),
        "Health" to listOf("pharmacy", "doctor", "dentist", "medicine", "gym", "vitamins", "therapy", "therapist", "prescription", "physio", "clinic"),
    )

    /** The category whose keywords appear as whole words; null with no keyword or with keywords from two categories. */
    fun spendingCategory(text: String): String? {
        val hits = categoryKeywords.filter { (_, words) ->
            words.any { w ->
                Regex("(?<![\\p{L}\\p{N}])" + w.split(" ").joinToString("\\s+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])", I)
                    .containsMatchIn(text)
            }
        }.keys
        return hits.singleOrNull()
    }

    private val scoreLabels = linkedMapOf(
        "mood" to "mood",
        "energy" to "energy",
        "sleepQuality" to "sleep(?:\\s+quality)?",
        "stress" to "stress",
    )

    /** mood, energy, sleepQuality, stress, each 1-5, each named once, and no other number in the text; else null. */
    fun checkinScores(text: String): Map<String, String>? {
        if (Regex("\\d+").findAll(text).count() != 4) return null
        val out = LinkedHashMap<String, String>()
        for ((key, label) in scoreLabels) {
            val r = Regex("(?<![\\p{L}\\p{N}])$label(?![\\p{L}\\p{N}])\\s*[:=]?\\s*([1-5])(?![\\d.:/])", I)
            val found = r.findAll(text).toList()
            if (found.size != 1) return null
            out[key] = found.single().groupValues[1]
        }
        return out
    }
}
