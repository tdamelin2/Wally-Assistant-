package dev.tim.assistant.server.chat.ladder

/**
 * Pure text guards for the code rungs of the brain ladder (SPEC section 7, B4, B33).
 * No database, clock or network: every function depends only on its argument.
 */
object Guards {
    private val I = RegexOption.IGNORE_CASE

    private fun words(vararg w: String): Regex =
        Regex(
            "(?<![\\p{L}\\p{N}'])(?:" + w.joinToString("|") { p -> p.split(" ").joinToString("\\s+") { Regex.escape(it) } } +
                ")(?![\\p{L}\\p{N}])",
            I,
        )

    private val negation = Regex(
        "(?<![\\p{L}\\p{N}])(?:not|never|no|nothing|none|without|skipped|almost|nearly|cancel|undo|cannot)(?![\\p{L}\\p{N}])|n't(?![\\p{L}])",
        I,
    )

    private val notRealEvent = Regex(
        "'ll(?![\\p{L}])|(?<![\\p{L}\\p{N}'])(?:will|would|might|should|going\\s+to|gonna|want\\s+to|need\\s+to|have\\s+to|had\\s+to|" +
            "if|costs|budget|refund|refunded|owe|owes|lent|paid\\s+for\\s+me)(?![\\p{L}\\p{N}])",
        I,
    )

    private val sentenceBreak = Regex("[.!?…]+\\s+\\S|\\n\\s*\\S")

    private val dateTokens = listOf(
        Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
        Regex("\\b\\d{1,2}/\\d{1,2}\\b"),
        Regex("\\bon\\s+the\\s+\\d{1,2}(?:st|nd|rd|th)\\b", I),
        Regex("\\b(?:" + MONTHS.joinToString("|") + ")\\s+\\d{1,2}(?:st|nd|rd|th)?\\b", I),
        Regex("\\bin\\s+\\d+\\s+days?\\b", I),
        Regex("\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:" + MONTHS.joinToString("|") + ")\\b", I),
    )
    private val timeTokens = listOf(
        Regex("\\b\\d{1,2}:\\d{2}\\s*(?:am|pm)?", I),
        Regex("\\b\\d{1,2}\\s*(?:am|pm)\\b", I),
    )
    private val scoreDigit = Regex("(?<![\\d.,/:-])[1-5](?![\\d/:-])(?![.,]\\d)")

    private val patternVerbs = listOf(
        "add", "create", "new", "todo", "remind", "spent", "paid", "cost", "log", "ate", "journal", "note", "dreamt", "dreamed",
        "mark", "check off", "tick off", "complete", "finish", "finished", "completed", "move", "reschedule", "push",
        "postpone", "find", "open", "show",
    )
    private val verbAlt = patternVerbs.joinToString("|") { it.replace(" ", "\\s+") }
    private val twoVerbs = Regex(
        "(?<![\\p{L}\\p{N}])(?:$verbAlt)(?![\\p{L}\\p{N}]).*(?<![\\p{L}\\p{N}])(?:and|then)\\s+(?:i\\s+|just\\s+|also\\s+)?(?:$verbAlt)(?![\\p{L}\\p{N}])",
        setOf(I, RegexOption.DOT_MATCHES_ALL),
    )

    private val questionStart = Regex(
        "^(?:what|when|where|who|why|how|which|can|could|should|do|does|did|is|are|am|any)(?![\\p{L}\\p{N}'])",
        I,
    )
    private val leadIn = Regex("^(?:(?:please|hey\\s+wally|hey)[\\s,]+)+", I)

    private val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val otherDayRegexes: List<Regex> = listOf(
        words("yesterday", "the day before", "ago", "later", "earlier this week", "the other day", "all time", "ever"),
        Regex("(?<![\\p{L}\\p{N}])(?:last|next)\\s+\\p{L}+", I),
        Regex("(?<![\\p{L}\\p{N}])(?:weekend|month|year)\\p{L}*", I),
        words(*weekdays.toTypedArray()),
        words(*MONTHS.toTypedArray()),
        Regex("\\bon\\s+the\\s+\\d{1,2}(?:st|nd|rd|th)\\b", I),
        Regex("\\b\\d{1,2}/\\d{1,2}\\b"),
        Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
    )
    private val soFar = words("so far")
    private val currentPeriod = words("this week", "today", "this morning", "tonight")
    private val hebrewOtherDay = listOf("מחר", "אתמול", "הבא", "הבאה", "שעבר", "שעברה", "החודש", "השנה")

    /** Unicode apostrophes become plain ones so that "I'll" and "don't" are seen the same way. */
    private fun norm(text: String): String = text.replace('’', '\'').replace('‘', '\'').trim()

    fun blocked(text: String): Boolean {
        val t = norm(text)
        if (t.length > 200) return true
        if (negation.containsMatchIn(t)) return true
        if (notRealEvent.containsMatchIn(t)) return true
        if (sentenceBreak.containsMatchIn(t)) return true
        if (moneyAmounts(t) >= 2) return true
        if (twoVerbs.containsMatchIn(t)) return true
        return false
    }

    /** True when the text holds a negation word ("not", "never", "n't" ...). */
    fun negated(text: String): Boolean = negation.containsMatchIn(norm(text))

    /**
     * Blocked only by a "not a real event" word ("need to", "should", "budget" ...): the text has one, and none of a negation, a
     * sentence break, two money amounts, two verbs or more than 200 characters. Such a message may get a question, never a save.
     */
    fun softBlocked(text: String): Boolean {
        val t = norm(text)
        if (!notRealEvent.containsMatchIn(t)) return false
        return t.length <= 200 && !negation.containsMatchIn(t) && !sentenceBreak.containsMatchIn(t) && moneyAmounts(t) < 2 && !twoVerbs.containsMatchIn(t)
    }

    private fun moneyAmounts(t: String): Int {
        var s = t
        for (r in dateTokens) s = r.replace(s, " ")
        for (r in timeTokens) s = r.replace(s, " ")
        if (Regex("^check-?\\s?in", I).containsMatchIn(leadIn.replace(s.trimStart(), ""))) {
            var left = 4
            s = scoreDigit.replace(s) { if (left-- > 0) " " else it.value }
        }
        // a message that names all four scores (mood, energy, sleep, stress with one digit each) holds scores, not money, with or without a lead
        if (namedScoreDigits.all { r -> r.findAll(s).count() == 1 }) {
            for (r in namedScoreDigits) s = r.replace(s) { m -> m.value.dropLast(1) + " " }
        }
        return Regex("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?").findAll(s).count()
    }

    private val namedScoreDigits = listOf("mood", "energy", "sleep(?:\\s+quality)?", "stress").map {
        Regex("(?<![\\p{L}\\p{N}])$it(?![\\p{L}\\p{N}])\\s*[:=]?\\s*[1-5](?![\\d.:/-])(?![.,]\\d)", I)
    }

    fun isQuestion(text: String): Boolean {
        val t = leadIn.replace(norm(text), "")
        return t.endsWith("?") || questionStart.containsMatchIn(t)
    }

    fun otherDay(text: String): Boolean {
        val t = norm(text)
        if (otherDayRegexes.any { it.containsMatchIn(t) }) return true
        // "so far" reaches back unless it is tied to the current period ("this week so far", "so far today")
        if (soFar.containsMatchIn(t) && !currentPeriod.containsMatchIn(t)) return true
        return hebrewOtherDay.any { t.contains(it) }
    }

    fun hasHebrew(text: String): Boolean = text.any { it in '֐'..'׿' }
}

internal val MONTHS = listOf(
    "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
)
