package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.mentionsDreams
import dev.tim.assistant.data.SPENDING_CATEGORIES
import dev.tim.assistant.server.chat.OTHER_DAY_MAX_BACK
import java.time.LocalDate

/**
 * Rung 1 of the brain ladder: a narrow, rule-based router (PLAN 18.3 step 5, SPEC section 4).
 * A missed command costs one slower answer, a wrong save costs Tim's trust (B4), so every add row is strict
 * and a [RouteResult.Match] needs exactly one matching row with every required slot parsed.
 * Pure: no database, clock or network (today is a parameter).
 */
object IntentRouter {
    private val I = RegexOption.IGNORE_CASE

    private val leadIn = Regex("^(?:(?:please|hey\\s+wally|hey)[\\s,]+)+", I)
    private val trailingMarks = Regex("[?!.\\s]+$")

    private fun re(p: String) = Regex(p, I)
    private val W = "(?<![\\p{L}\\p{N}])"

    // ---- rows ----
    private val addTask = re("^(?:add|create|make|put|enter)\\s+(?:an?\\s+)?(?:new\\s+)?task(?:\\s*[:,]\\s*|\\s+)(.+)$")
    private val newTask = re("^new\\s+task\\s*[:,]\\s*(.+)$")
    private val todo = re("^todo\\s*[:,]\\s*(.+)$")
    private val remindMe = re("^remind\\s+me\\s+to\\s+(.+)$")

    private val spentOn = re("^(?:i\\s+)?(?:just\\s+)?(?:spent|paid)\\s+(.+?)\\s+(?:on|for)\\s+(.+)$")
    private val costMe = re("^(.+?)\\s+cost\\s+me\\s+(.+)$")
    private val amountToken = re("^(?:\u20AA\\s*)?(\\d+(?:\\.\\d+)?)(?:\\s*(?:shekels?|nis|\u20AA))?$")

    private val mealTypeWord = "(meal|snack|breakfast|lunch|dinner)"
    private val logMeal = re("^log\\s+(?:an?\\s+)?$mealTypeWord(?:\\s*[:,]\\s*|\\s+)(.+)$")
    private val addMealRe = re("^add\\s+(?:an?\\s+)?$mealTypeWord\\s*[:,]\\s*(.+)$")
    private val ateFor = re("^(?:i\\s+)?(?:just\\s+)?(?:ate|had)\\s+(.+?)\\s+(?:for|as)\\s+(?:an?\\s+)?(breakfast|lunch|dinner|snack|meal)$")
    private val mealRejectStart = re("^(?:with|at|to)(?![\\p{L}\\p{N}])")
    private val mealRejectWords = re("${W}(?:calendar|event|meeting|appointment|call|class)(?![\\p{L}\\p{N}])")
    private val hostedWords = re("${W}(?:guests?|friends?|people|family|visitors?|company|relatives?|parents|colleagues|neighbou?rs|someone|anyone|everyone)(?![\\p{L}\\p{N}])")
    private val particleWords = setOf(
        "out", "in", "early", "late", "well", "badly", "alone", "together", "again", "here", "there", "much", "little", "lot",
        "too", "a", "enough", "already", "outside", "at", "home", "so", "more", "less", "something", "nothing", "everything", "it", "that", "this", "them",
    )


    private val journal = re("^(?:(?:new\\s+)?(?:journal|diary)(?:\\s+(?:entry|note))?|note\\s+to\\s+self)\\s*[:,]\\s*(.+)$")
    private val journalVerb = re("^(?:add|write|put|jot\\s+down|record)\\s+(?:(?:this|it|something|a\\s+note|an\\s+entry)\\s+)?(?:to|in|into|on)\\s+(?:my\\s+|the\\s+)?journal(?:\\s*[:,]\\s*|\\s+)(?:that\\s+)?(.+)$")

    private val dreamWithConnector = re("^(?:last\\s+night\\s+)?i\\s+(?:dreamt|dreamed)\\s+(that|about|of|i)\\s+(.+)$")
    private val dreamLastNight = re("^last\\s+night\\s+i\\s+(?:dreamt|dreamed)\\s+(.+)$")

    private val markDone = re(
        "^(mark|check\\s+off|tick\\s+off)\\s+(?:the\\s+|my\\s+)?(.+?)(\\s+task)?(\\s+as)?(?:\\s+(done|complete|completed|finished))?$",
    )
    private val completeTask = re("^(?:complete|finish)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+task$")
    private val iFinishedTask = re("^i\\s+(?:finished|completed)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+task$")
    private val badNames = setOf("done", "complete", "completed", "finished", "it", "this", "that", "my", "the", "a")

    private val moveTo = "(?:back\\s+|over\\s+|forward\\s+)?(?:to|until|till|for)"
    private val moveTaskRe = re("^(?:move|reschedule|push|postpone|change|shift|bump|delay)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+task\\s+$moveTo\\s+(.+)$")
    private val moveTaskFirstRe = re("^(?:move|reschedule|push|postpone|change|shift|bump|delay)\\s+(?:the\\s+|my\\s+)?task\\s+(?:(?:called|named)\\s+)?(.+?)\\s+$moveTo\\s+(.+)$")
    private val moveLooseRe = re("^(?:move|reschedule|push|postpone|shift|bump|delay)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+$moveTo\\s+(.+)$")
    private val moveExamRe = re("^(?:move|reschedule|change|shift|set|update|push|postpone|bump|delay)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+exam\\s+$moveTo\\s+(.+?)(?:\\s+at\\s+(.+))?$")
    private val fromDate = re("^(.+?)\\s+from\\s+(.+)$")

    // a move of several items or a pronoun target: code never does it, the message goes to a model (Phase 20 R2)
    private val multiMoveRe = re(
        "^(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?(?:move|reschedule|push|postpone|shift|bump|delay|change)\\s+" +
            "(?:(?:all|both|each|every|them|these|those|everything)(?![\\p{L}\\p{N}])|" +
            "(?:(?:the|my)\\s+)?(?:[\\p{L}\\p{N}'-]+\\s+){0,3}?(?:tasks|exams|events|items|things)(?![\\p{L}\\p{N}]))",
    )

    // typo tolerance for command words only (Phase 20 R2): a fixed small list, never fuzzy matching
    private val adTypo = re(
        "^ad\\s+(?=(?:(?:an?|new)\\s+)*(?:task|meal|snack|breakfast|lunch|dinner)(?![\\p{L}\\p{N}])|" +
            "(?:(?:this|it|something|a\\s+note|an\\s+entry)\\s+)?(?:to|in|into|on)\\s+(?:my\\s+|the\\s+)?journal(?![\\p{L}\\p{N}]))",
    )
    private val doubledCmd = re("^((?:(?:add|create|make|put|enter|an?|new)\\s+)*)(add|create|make|put|enter|task|new|an?)\\s+\\2(?![\\p{L}\\p{N}])")
    private val butTitle = re("^but\\s+(.+)$")
    private val butClauseStarts = setOf(
        "i", "i'm", "i'll", "i've", "i'd", "you", "we", "he", "she", "they", "it", "it's", "its", "that", "this", "these", "those", "there",
        "then", "not", "now", "also", "if", "when", "why", "what", "how", "so", "still", "just", "maybe", "as", "because", "since",
        "nothing", "no", "nobody", "everyone", "someone", "anyone", "yet", "even", "only", "all", "both", "but", "and", "or",
    )
    private val butVerbish = setOf(
        "is", "are", "was", "were", "am", "be", "been", "being", "do", "does", "did", "have", "has", "had", "can", "will", "would", "should",
        "could", "think", "thought", "feel", "felt", "know", "want", "need", "like", "love", "hate", "say", "says", "said", "got",
    )

    // "everything today" style day overviews: the period phrase is already taken out of the text
    private val everythingRow = re(
        "^(?:(?:show|give|tell)\\s+me\\s+)?(?:what(?:'s|\\s+is)\\s+)?(?:my\\s+)?" +
            "(?:everything(?:\\s+(?:i\\s+have\\s+got|i\\s+have|i\\s+got|i've\\s+got))?|all\\s+(?:i\\s+have\\s+got|i\\s+have|i\\s+got|i've\\s+got))" +
            "(?:\\s+(?:on|planned|scheduled|going\\s+on))?(?:\\s+(?:on|for))?$",
    )

    private val findRe = re("^(?:find|open|show\\s+me|search(?:\\s+for)?|look\\s+for)\\s+(?:my\\s+|the\\s+)?(.+?)\\s+(course|notes?|goal|task|exam|e?mail|event)$")

    private val askTodayRes = listOf(
        re("^what(?:'s|\\s+is)\\s+on\\s+today$"),
        re("^what\\s+do\\s+i\\s+have\\s+today$"),
        re("^what(?:'s|\\s+is)\\s+left\\s+today$"),
    )
    private val askTasksRes = listOf(re("^what\\s+are\\s+my\\s+tasks$"), re("^show\\s+(?:me\\s+)?my\\s+tasks$"))
    private val askSpending = re("^how\\s+much\\s+(?:money\\s+)?(?:have\\s+i|did\\s+i|do\\s+i)\\s+(?:spen[dt]|blow|blown|blew|waste|wasted|burn|burned|burnt|drop|dropped|splurge|splurged)(?:\\s+so\\s+far)?\\s+this\\s+week(?:\\s+so\\s+far)?$")
    private val askSpendingNoun = re("^(?:(?:show|give|tell)\\s+me\\s+)?(?:my\\s+)?(?:(?:this\\s+week'?s|weekly|total)\\s+)?(?:spending|expenses|spend)(?:\\s+(?:total|so\\s+far|summary))?(?:\\s+(?:for\\s+)?this\\s+week)?$")

    // ---- day-question rows (Phase 20): each runs on the text left after Periods.find removed the period phrase ----
    private val spendRows = listOf(
        re("^how\\s+much\\s+(?:money\\s+)?(?:have\\s+i|did\\s+i|do\\s+i)\\s+spen[dt](?:\\s+on\\s+(.+))?$"),
        re("^(my\\s+)?(?:spending|expenses)(?:\\s+on\\s+(.+))?$"),
        re("^what\\s+did\\s+i\\s+spend(?:\\s+on\\s+(.+))?$"),
    )
    private val spendNounRows = listOf(
        re("^what(?:'s|\\s+is|\\s+was)\\s+(my|the)\\s+(?:total\\s+)?(?:(.+?)\\s+)?(?:spending|expenses|spend)(?:\\s+total)?$"),
        re("^(?:(?:show|give|tell)\\s+me\\s+)?(my\\s+)?(?:total\\s+)?(?:(.+?)\\s+)?(?:spending|expenses)(?:\\s+total)?$"),
    )
    private val eventRows = listOf(
        re("^what(?:'s|\\s+is)(?:\\s+on)?(?:\\s+my\\s+calendar)?$"),
        re("^any\\s+events$"),
        re("^(?:my\\s+)?(?:(?:calendar|schedule)\\s+)?(?:events?|schedule|calendar|agenda|plans?)$"),
    )
    private val dueRows = listOf(
        re("^what(?:'s|\\s+is)\\s+due$"), re("^(?:my\\s+)?tasks\\s+due$"),
        re("^what\\s+(?:tasks\\s+)?do\\s+i\\s+have\\s+due$"), re("^(?:do\\s+i\\s+have\\s+)?anything\\s+due$"),
    )
    private val examNextRows = listOf(re("^when(?:'s|\\s+is)\\s+my\\s+next\\s+exam$"), re("^my\\s+(?:next\\s+|upcoming\\s+)?exams$"))
    private val examNamedRows = listOf(
        re("^when(?:'s|\\s+is)\\s+(?:my\\s+|the\\s+)?(.+?)\\s+exam$"),
        re("^what\\s+(?:date|day)\\s+(?:is|'s)\\s+(?:my\\s+|the\\s+)?(.+?)\\s+exam(?:\\s+on)?$"),
        re("^when\\s+do\\s+i\\s+have\\s+(?:my\\s+|the\\s+)?(.+?)\\s+exam$"),
        re("^when\\s+does\\s+(?:my\\s+|the\\s+)?(.+?)\\s+exam\\s+(?:take\\s+place|happen|start|begin)$"),
    )
    private val examBadNames = setOf("next", "upcoming", "my", "the")
    private val sleepRows = listOf(re("^how\\s+(?:did\\s+i\\s+sleep|much\\s+did\\s+i\\s+sleep|long\\s+did\\s+i\\s+sleep)$"), re("^(my\\s+)?sleep$"))
    private val stepsRows = listOf(re("^how\\s+many\\s+steps(?:\\s+did\\s+i\\s+(?:do|take|walk))?$"), re("^(my\\s+)?steps$"))
    private val heartRow = re("^((?:what\\s+was\\s+|what's\\s+)?(?:my\\s+)?)resting\\s+(?:heart\\s+rate|hr)$")
    private val mealListRows = listOf(re("^what\\s+did\\s+i\\s+(?:eat|have)$"), re("^(my\\s+)?meals$"))
    private val calorieRows = listOf(re("^how\\s+many\\s+calories(?:\\s+did\\s+i\\s+(?:eat|have))?$"), re("^(my\\s+)?calories$"))
    private val checkinAskRows = listOf(re("^how\\s+was\\s+my\\s+(?:mood|energy|stress|day)$"), re("^(my\\s+)?check-?in$"), re("^(my\\s+)?(?:mood|energy|stress)$"))
    private val boughtFor = re("^(?:i\\s+)?(?:just\\s+)?(?:bought|purchased|got)\\s+(?:(?:a|an|some|new|the)\\s+)*(.+?)\\s+for\\s+(.+)$")
    private val shekelAmount = re("^(?:\u20AA\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:shekels?|nis|\u20AA))$")
    private val findAnyRe = re("^(?:find|open|show\\s+me|pull\\s+up|look\\s+up|search(?:\\s+for)?|look\\s+for)\\s+(?:(?:my|the)\\s+)?(.+)$")
    private val findAnyGeneric = setOf("tasks", "calendar", "spending", "schedule", "day", "week", "list", "todo", "today", "events", "exams", "everything", "goals", "notes", "docs", "emails", "mail", "inbox", "agenda", "plans", "goal", "task", "exam", "course", "note", "event", "email", "courses", "events")

    private val weekWord = re("${W}(?:weekly|this\\s+week(?:'s)?)(?![\\p{L}\\p{N}])")
    private val tomorrowWord = re("${W}tomorrow(?![\\p{L}\\p{N}])")
    private val thisWeekOrTotal = re("${W}this\\s+week(?![\\p{L}\\p{N}])|${W}in\\s+total(?![\\p{L}\\p{N}])")

    private fun match(intent: IntentName, vararg slots: Pair<String, String>) = RouteResult.Match(intent, slots.toMap())

    /** The text is normalised here (apostrophes, lead-in words, closing punctuation) before the rows run. */
    fun route(text: String, today: LocalDate, typedToday: Boolean = true): RouteResult {
        val raw = text.replace('\u2019', '\'').replace('\u2018', '\'').trim()
        if (raw.isEmpty() || Guards.hasHebrew(raw) || Guards.blocked(raw)) return RouteResult.NoMatch
        val plain = trailingMarks.replace(leadIn.replace(raw, ""), "").trim()
        if (plain.isEmpty() || multiMoveRe.containsMatchIn(plain)) return RouteResult.NoMatch
        val t = fixCommands(plain)

        val dream = mentionsDreams(t)
        val question = Guards.isQuestion(raw)
        val otherDay = Guards.otherDay(t)
        val tomorrow = tomorrowWord.containsMatchIn(t)
        val weekOrTotal = thisWeekOrTotal.containsMatchIn(t)

        val found = ArrayList<RouteResult.Match>(2)
        fun add(m: RouteResult.Match?) { if (m != null) found += m }

        if (dream) {
            if (!question) add(addDream(raw))
        } else {
            val logOk = !otherDay && !tomorrow && !weekOrTotal
            if (!question) {
                add(createTask(t, today))
                // a spending or a timed meal for a past day named at an edge of the message (PLAN 20.5); skipped when typed on an earlier day
                val edge = if (typedToday) edgeDayLog(t, today) else null
                if (edge != null) add(edge)
                if (logOk) {
                    if (edge == null) {
                        add(addSpending(t))
                        add(addMeal(t))
                    }
                    add(addCheckin(t))
                    add(addJournal(t))
                }
                add(markTaskDone(t))
                add(moveTask(t, today))
                add(moveExam(t, today))
                add(findRow(t))
            }
            // the four kept ask rows run first on the full text; the new day-question rows run only when none of them matched
            val kept = ArrayList<RouteResult.Match>(2)
            if (!otherDay) {
                if (!tomorrow) {
                    askToday(t)?.let { kept += it }
                    askSpendingWeek(t)?.let { kept += it }
                }
                askTasks(t)?.let { kept += it }
            }
            found += kept
            if (kept.isEmpty()) {
                add(askSpendingNew(t, today))
                add(askEventsNew(t, today))
                add(askTasksNew(t, today))
                add(askExamNew(t, today))
                add(askHealthNew(t, today))
                add(askMealsNew(t, today))
                add(askCheckinNew(t, today))
                add(askEverythingNew(t, today))
            }
        }
        return if (found.size == 1) found.single() else RouteResult.NoMatch
    }

    /** True for a move of several items or a pronoun target ("move them ...", "move all my tasks ..."): code never acts on it. */
    fun multiMove(text: String): Boolean = multiMoveRe.containsMatchIn(rowText(cleanRaw(text)))

    /** The fixed typo list for command words (never journal, dream or free text): a leading "ad" read as "add", a doubled command word collapsed. */
    private fun fixCommands(t: String): String = doubledCmd.replace(adTypo.replace(t, "add "), "$1$2")

    /** A task title whose first word "but" is followed by an object (not a clause) is read as "buy". */
    private fun fixBut(title: String): String {
        val rest = butTitle.find(title)?.groupValues?.get(1)?.trim() ?: return title
        val words = rest.split(Regex("\\s+")).map { it.lowercase().trimEnd(',', '.', ';') }
        if (words.size > 6 || words[0] in butClauseStarts || words.any { it in butVerbish }) return title
        return (if (title[0].isUpperCase()) "Buy " else "buy ") + rest
    }

    /** The text of a message as the rows see it: apostrophes made plain, lead-in words and closing marks removed. */
    private fun rowText(raw: String): String = trailingMarks.replace(leadIn.replace(raw, ""), "").trim()

    private fun cleanRaw(text: String): String = text.replace('’', '\'').replace('‘', '\'').trim()

    /**
     * The Phase 18 add rows (`create_task`, `add_spending`, `add_meal`, `add_journal`, `add_checkin`) on a message held back only by a
     * "not a real event" word: exactly those rows, with only the `notRealEvent` check skipped, never an edit, ask or edge-day row.
     * It does not run the edge-day logic, so it computes `logOk` from the full text and never returns a `date` slot.
     */
    fun routeUnguarded(text: String, today: LocalDate): RouteResult {
        val raw = cleanRaw(text)
        if (raw.isEmpty() || Guards.hasHebrew(raw) || (Guards.blocked(raw) && !Guards.softBlocked(raw))) return RouteResult.NoMatch
        if (Guards.isQuestion(raw)) return RouteResult.NoMatch
        val t = fixCommands(rowText(raw))
        if (t.isEmpty() || mentionsDreams(t)) return RouteResult.NoMatch
        val found = ArrayList<RouteResult.Match>(2)
        fun add(m: RouteResult.Match?) { if (m != null) found += m }
        add(createTask(t, today))
        if (!Guards.otherDay(t) && !tomorrowWord.containsMatchIn(t) && !thisWeekOrTotal.containsMatchIn(t)) {
            add(addSpending(t))
            add(addMeal(t))
            add(addCheckin(t))
            add(addJournal(t))
        }
        return if (found.size == 1) found.single() else RouteResult.NoMatch
    }

    private val spentAmountOnly = re("^(?:i\\s+)?(?:just\\s+)?(?:spent|paid)\\s+(.+)$")
    private val ateAnything = re("^(?:i\\s+)?(?:just\\s+)?ate\\s+(.+)$")
    private val mealTypeAnywhere = re("${W}(?:meal|snack|breakfast|lunch|dinner)(?![\\p{L}\\p{N}])")
    private val foodJoiners = listOf(" and ", " but ", " so ", " because ")

    /**
     * A message that is one of a few log shapes with a single piece missing, under the same conditions the real log rows need (not blocked,
     * not a question, no other-day word, no `tomorrow`, no `this week` / `in total`): a spending with an amount and no category, or
     * "I ate ..." with no meal-type word. The result's [Filled.missing] names the piece; null in every other case.
     */
    fun partial(text: String, today: LocalDate): Filled? {
        val raw = cleanRaw(text)
        if (raw.isEmpty() || Guards.hasHebrew(raw) || Guards.blocked(raw) || Guards.isQuestion(raw)) return null
        val t = rowText(raw)
        if (t.isEmpty() || mentionsDreams(t) || Guards.otherDay(t) || tomorrowWord.containsMatchIn(t) || thisWeekOrTotal.containsMatchIn(t)) return null
        partialSpending(t)?.let { return SlotFill.filled("add_spending", it, today) }
        partialMeal(t)?.let { return SlotFill.filled("add_meal", it, today) }
        return null
    }

    private fun partialSpending(t: String): Map<String, String>? {
        val bought = boughtFor.find(t)?.let { it.groupValues[2] to it.groupValues[1] }
        if (bought != null && !shekelAmount.matches(bought.first.trim())) return null
        val (amountText, what) = spentOn.find(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: costMe.find(t)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: bought
            ?: spentAmountOnly.find(t)?.let { it.groupValues[1] to "" }
            ?: return null
        if (!amountToken.matches(amountText.trim())) return null
        val amount = SlotParsers.amount(amountText) ?: return null
        val note = what.replace(Regex("\\s+today$", I), "").trim()
        if (note.isNotEmpty() && SlotParsers.spendingCategory(note) != null) return null
        return linkedMapOf("amount" to amount).also { if (note.isNotEmpty()) it["note"] = note }
    }

    private fun partialMeal(t: String): Map<String, String>? {
        val food = ateAnything.find(t)?.groupValues?.get(1)?.trim() ?: return null
        if (mealTypeAnywhere.containsMatchIn(food) || !mealTextOk(food)) return null
        if (food.split(Regex("\\s+")).size > 6 || foodJoiners.any { food.lowercase().contains(it) }) return null
        return mapOf("text" to food)
    }

    // ---- rows ----

    private fun createTask(t: String, today: LocalDate): RouteResult.Match? {
        val body = listOf(addTask, newTask, todo, remindMe).firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1) } ?: return null
        if (SlotParsers.hasTime(body)) return null
        val scan = SlotParsers.scanDate(body, today)
        if (!scan.ok) return null
        val title = fixBut(taskTitle(scan.rest) ?: return null)
        val slots = linkedMapOf("title" to title)
        scan.date?.let { slots["dueDate"] = SlotParsers.isoDate(it) }
        return RouteResult.Match(IntentName.CREATE_TASK, slots)
    }

    /** The task title left after the date phrase was taken out, tidied; null when it is shorter than 2 characters or reads as "force...". */
    internal fun taskTitle(rest: String): String? {
        val title = rest.replace(Regex("^to\\s+", I), "").replace(Regex("\\s+(?:on|for|by|until|due|at)$", I), "").trim()
        if (title.length < 2 || Regex("^force(?![\\p{L}\\p{N}])", I).containsMatchIn(title)) return null
        return title
    }

    /** True when the text has `this week` or `in total` (the real log rows refuse such a message). */
    internal fun thisWeekOrTotal(text: String): Boolean = thisWeekOrTotal.containsMatchIn(text)

    /** The four Phase 18 meal rejections on a meal text: a start with with/at/to, a time, a calendar word, only filler words. */
    internal fun mealTextOk(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 2 || mealRejectStart.containsMatchIn(clean) || SlotParsers.hasTime(clean) || mealRejectWords.containsMatchIn(clean)) return false
        return !clean.split(Regex("\\s+")).all { it.lowercase() in particleWords }
    }

    private fun addSpending(t: String): RouteResult.Match? {
        val bought = boughtFor.find(t)?.let { it.groupValues[2] to it.groupValues[1] }
        // bought/purchased/got ... for <amount>: only a shekel word or sign counts, a bare number could be a head count
        if (bought != null && !shekelAmount.matches(bought.first.trim())) return null
        val (amountText, what) = spentOn.find(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: costMe.find(t)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: bought
            ?: return null
        if (!amountToken.matches(amountText.trim())) return null
        val amount = SlotParsers.amount(amountText) ?: return null
        val note = what.replace(Regex("\\s+today$", I), "").trim()
        if (note.isEmpty()) return null
        val category = SlotParsers.spendingCategory(note) ?: return null
        return match(IntentName.ADD_SPENDING, "amount" to amount, "category" to category, "note" to note)
    }

    /**
     * "spent 35 on lunch yesterday", "13/11 log lunch: salad at 13:00": the spending and meal rows on the text left after a single past
     * day at the start or end of the message. Only `yesterday`, `last <weekday>` and a written date count (never a bare weekday), the lead
     * word is none or `on`, the day is before today and at most [OTHER_DAY_MAX_BACK] days back, and no other day word is left. A meal needs
     * exactly one clock time. Null in every other case, so the message is handled as it was before.
     */
    private fun edgeDayLog(t: String, today: LocalDate): RouteResult.Match? {
        val spendingFound = Periods.findAtEdge(t, today, Lean.PAST)?.takeIf { acceptedEdge(it, today) }
        if (spendingFound != null) addSpending(spendingFound.rest)?.let { return withDate(it, spendingFound.period.from) }
        val noTime = SlotParsers.withoutTime(t) ?: return null
        val mealFound = Periods.findAtEdge(noTime, today, Lean.PAST)?.takeIf { acceptedEdge(it, today) } ?: return null
        val meal = addMeal(mealFound.rest) ?: return null
        val time = SlotParsers.time(t) ?: return null
        return withDate(RouteResult.Match(meal.intent, meal.slots + ("time" to SlotParsers.formatTime(time))), mealFound.period.from)
    }

    private fun withDate(m: RouteResult.Match, day: LocalDate) = RouteResult.Match(m.intent, m.slots + ("date" to SlotParsers.isoDate(day)))

    private fun acceptedEdge(f: Found, today: LocalDate): Boolean {
        val w = f.period.word
        if (!(w == "yesterday" || w.startsWith("last_weekday:") || w.startsWith("date:"))) return false
        val day = f.period.from
        if (!day.isBefore(today) || day.isBefore(today.minusDays(OTHER_DAY_MAX_BACK.toLong()))) return false
        if (f.lead != null && f.lead != "on") return false
        return !Guards.otherDay(f.rest)
    }

    private fun addMeal(t: String): RouteResult.Match? {
        val (typeWord, text) = (logMeal.find(t) ?: addMealRe.find(t))?.let { it.groupValues[1] to it.groupValues[2] }
            ?: ateFor.find(t)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: return null
        val clean = text.trim()
        if (!mealTextOk(clean)) return null
        // "had guests for dinner": people are hosted, not eaten
        if (hostedWords.containsMatchIn(clean)) return null
        val type = SlotParsers.mealType(typeWord) ?: return null
        return match(IntentName.ADD_MEAL, "type" to type, "text" to clean)
    }

    private fun addCheckin(t: String): RouteResult.Match? {
        val scores = SlotParsers.checkinScores(t) ?: return null
        return RouteResult.Match(IntentName.ADD_CHECKIN, scores)
    }

    private fun addJournal(t: String): RouteResult.Match? {
        val verbText = journalVerb.find(t)?.groupValues?.get(1)?.trim()
        if (verbText != null) {
            if (verbText.split(Regex("\\s+")).size < 2) return null
            return match(IntentName.ADD_JOURNAL, "text" to verbText)
        }
        val text = journal.find(t)?.groupValues?.get(1)?.trim() ?: return null
        if (text.length < 2) return null
        return match(IntentName.ADD_JOURNAL, "text" to text)
    }

    private fun addDream(raw: String): RouteResult.Match? {
        val rest = dreamWithConnector.find(raw)?.let { m ->
            val connector = m.groupValues[1].lowercase()
            val body = m.groupValues[2]
            if (connector == "of" && Regex("^\\p{L}+ing(?![\\p{L}\\p{N}])", I).containsMatchIn(body)) return null
            body
        } ?: dreamLastNight.find(raw)?.groupValues?.get(1) ?: return null
        if (rest.trim().length < 3) return null
        return match(IntentName.ADD_DREAM, "text" to raw)
    }

    private fun markTaskDone(t: String): RouteResult.Match? {
        val name = markDone.find(t)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val hasTaskWord = m.groupValues[3].isNotEmpty()
            val hasDoneWord = m.groupValues[5].isNotEmpty()
            // The bare verb "mark" is too common ("mark my words"): it needs the word task or a done word.
            if (verb == "mark" && !hasTaskWord && !hasDoneWord) null else m.groupValues[2]
        } ?: completeTask.find(t)?.groupValues?.get(1) ?: iFinishedTask.find(t)?.groupValues?.get(1) ?: return null
        val clean = name.trim()
        if (clean.length < 2 || clean.lowercase() in badNames) return null
        return match(IntentName.MARK_TASK_DONE, "name" to clean)
    }

    private fun onlyDate(text: String, today: LocalDate): LocalDate? {
        if (SlotParsers.hasTime(text)) return null
        val scan = SlotParsers.scanDate(text, today)
        return if (scan.found && scan.rest.isEmpty()) scan.date else null
    }

    /** "buy milk from monday": the name before a "from <day>" that holds only a date; else the name unchanged. */
    private fun withoutFrom(name: String, today: LocalDate): String {
        val f = fromDate.find(name) ?: return name
        return if (onlyDate(f.groupValues[2], today) != null) f.groupValues[1].trim() else name
    }

    private fun moveTask(t: String, today: LocalDate): RouteResult.Match? {
        val m = moveTaskRe.find(t) ?: moveTaskFirstRe.find(t)
        if (m == null) {
            // no kind word: Ladder moves it only for exactly one open task with a whole-name match and no exam of that name
            val l = moveLooseRe.find(t) ?: return null
            val looseName = withoutFrom(l.groupValues[1].trim(), today)
            if (looseName.length < 2 || looseName.lowercase() in badNames || Regex("${W}(?:task|exam|tasks|exams)$", I).containsMatchIn(looseName)) return null
            val looseDate = onlyDate(l.groupValues[2], today) ?: return null
            return match(IntentName.MOVE_TASK, "name" to looseName, "date" to SlotParsers.isoDate(looseDate), "loose" to "1")
        }
        val name = withoutFrom(m.groupValues[1].trim(), today)
        if (name.length < 2 || name.lowercase() in badNames) return null
        val date = onlyDate(m.groupValues[2], today) ?: return null
        return match(IntentName.MOVE_TASK, "name" to name, "date" to SlotParsers.isoDate(date))
    }

    private fun moveExam(t: String, today: LocalDate): RouteResult.Match? {
        val m = moveExamRe.find(t) ?: return null
        val name = m.groupValues[1].trim()
        if (name.length < 2) return null
        val date = onlyDate(m.groupValues[2], today) ?: return null
        val timeText = m.groupValues[3]
        val slots = linkedMapOf("name" to name, "date" to SlotParsers.isoDate(date))
        if (timeText.isNotEmpty()) {
            val only = SlotParsers.time(timeText)
            if (only == null || !Regex("^[\\d:\\sapm]+$", I).matches(timeText)) return null
            slots["time"] = SlotParsers.formatTime(only)
        }
        return RouteResult.Match(IntentName.MOVE_EXAM, slots)
    }

    private fun findRow(t: String): RouteResult.Match? {
        val m = findRe.find(t) ?: return findAny(t)
        val name = m.groupValues[1].trim()
        if (name.length < 2 || name.lowercase() in badNames) return null
        val kind = when (val k = m.groupValues[2].lowercase()) {
            "note", "notes" -> "doc"
            "email", "mail" -> "mail"
            else -> k
        }
        return match(IntentName.FIND, "name" to name, "kind" to kind)
    }

    /** "find my X" with no kind word: Ladder answers only when exactly one task, exam, goal or doc matches. */
    private fun findAny(t: String): RouteResult.Match? {
        val name = findAnyRe.find(t)?.groupValues?.get(1)?.trim() ?: return null
        val lower = name.lowercase()
        if (name.length < 2 || lower in badNames || lower in findAnyGeneric || Guards.otherDay(name) || tomorrowWord.containsMatchIn(name)) return null
        if (Regex("${W}(?:tasks|events|exams|goals|notes|spending|calendar|today)(?![\\p{L}\\p{N}])", I).containsMatchIn(lower)) return null
        return match(IntentName.FIND, "name" to name, "kind" to "any")
    }

    private fun askToday(t: String): RouteResult.Match? =
        if (askTodayRes.any { it.matches(t) }) RouteResult.Match(IntentName.ASK_TODAY, emptyMap()) else null

    private fun askTasks(t: String): RouteResult.Match? =
        if (askTasksRes.any { it.matches(t) }) RouteResult.Match(IntentName.ASK_TASKS, emptyMap()) else null

    /** The period of [t] for a day-question row: null for two period phrases, else the period (null when none) and the text without it. */
    private class Prep(val period: Period?, val rest: String)

    private fun prep(t: String, today: LocalDate, lean: Lean): Prep? = when (val s = Periods.find(t, today, lean)) {
        Scan.None -> Prep(null, t)
        is Scan.One -> Prep(s.found.period, s.found.rest)
        Scan.Many -> null
    }

    /** A period every row of a past-looking question may use: not "last night" (sleep only unless allowed), not wholly in the future. */
    private fun pastOk(p: Period?, today: LocalDate, lastNightOk: Boolean = false): Boolean =
        p == null || (!p.from.isAfter(today) && (lastNightOk || p.word != "last_night"))

    private fun dayOrWeek(p: Period?): Boolean = p == null || p.singleDay || p.word == "this_week" || p.word == "last_week"

    private fun categoryOf(text: String): String? {
        val c = text.trim().replace(Regex("^(?:the|my)\\s+", I), "")
        return SPENDING_CATEGORIES.firstOrNull { it.equals(c, ignoreCase = true) } ?: SlotParsers.spendingCategory(c)
    }

    private fun askSpendingNew(t: String, today: LocalDate): RouteResult.Match? {
        val p = prep(t, today, Lean.PAST) ?: return null
        if (!pastOk(p.period, today)) return null
        for ((i, row) in spendRows.withIndex()) {
            val m = row.find(p.rest) ?: continue
            // "spending" and "expenses" alone are bare nouns: they need "my" or a period phrase
            if (i == 1 && m.groupValues[1].isEmpty() && p.period == null) return null
            val categoryText = m.groupValues.last()
            val slots = linkedMapOf("period" to (p.period?.word ?: "this_week"))
            if (categoryText.isNotEmpty()) slots["category"] = categoryOf(categoryText) ?: return null
            return RouteResult.Match(IntentName.ASK_SPENDING, slots)
        }
        for ((i, row) in spendNounRows.withIndex()) {
            val m = row.find(p.rest) ?: continue
            // a bare noun with only a category word before it still needs "my" or a period phrase
            if (i == 1 && m.groupValues[1].isEmpty() && p.period == null) continue
            val categoryText = m.groupValues[2]
            val slots = linkedMapOf("period" to (p.period?.word ?: "this_week"))
            if (categoryText.isNotEmpty()) slots["category"] = categoryOf(categoryText) ?: continue
            return RouteResult.Match(IntentName.ASK_SPENDING, slots)
        }
        return null
    }

    private fun askEventsNew(t: String, today: LocalDate): RouteResult.Match? {
        val p = prep(t, today, Lean.FUTURE) ?: return null
        val period = p.period ?: return null
        if (period.word == "last_night" || eventRows.none { it.matches(p.rest) }) return null
        return if (period.singleDay) match(IntentName.ASK_EVENTS, "day" to SlotParsers.isoDate(period.from))
        else match(IntentName.ASK_EVENTS, "period" to period.word)
    }

    private fun askTasksNew(t: String, today: LocalDate): RouteResult.Match? {
        val p = prep(t, today, Lean.FUTURE) ?: return null
        val period = p.period ?: return null
        if (period.word == "last_night" || dueRows.none { it.matches(p.rest) }) return null
        val whenWord = when (period.word) { "today" -> "today"; "tomorrow" -> "tomorrow"; "this_week" -> "week"; else -> null }
        return if (whenWord != null) match(IntentName.ASK_TASKS, "when" to whenWord) else match(IntentName.ASK_TASKS, "period" to period.word)
    }

    private fun askExamNew(t: String, today: LocalDate): RouteResult.Match? {
        // "next exam" is not a day word: only a period phrase turns the question into exams by date, which stay with a model
        if (Periods.find(t, today, Lean.FUTURE) != Scan.None) return null
        if (examNextRows.any { it.matches(t) }) return RouteResult.Match(IntentName.ASK_EXAM, emptyMap())
        val rawName = examNamedRows.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.trim() } ?: return null
        if (rawName.lowercase() == "next" || rawName.lowercase() == "upcoming") return RouteResult.Match(IntentName.ASK_EXAM, emptyMap())
        if (Regex("(?:^|\\s)(?:and|or|exams?)(?:\\s|$)|,", I).containsMatchIn(rawName)) return null
        val name = rawName.replace(Regex("^(?:next|upcoming)\\s+", I), "")
        if (name.isEmpty() || name.lowercase() in examBadNames) return null
        return match(IntentName.ASK_EXAM, "name" to name)
    }

    private fun askHealthNew(t: String, today: LocalDate): RouteResult.Match? {
        val p = prep(t, today, Lean.PAST) ?: return null
        val rest = p.rest
        val metric = when {
            sleepRows.any { r -> r.find(rest)?.let { m -> r != sleepRows[1] || m.groupValues[1].isNotEmpty() || p.period != null } == true } -> "sleep"
            stepsRows.any { r -> r.find(rest)?.let { m -> r != stepsRows[1] || m.groupValues[1].isNotEmpty() || p.period != null } == true } -> "steps"
            heartRow.find(rest)?.let { it.groupValues[1].isNotEmpty() || p.period != null } == true -> "heart"
            else -> return null
        }
        if (!pastOk(p.period, today, lastNightOk = metric == "sleep") || !dayOrWeek(p.period)) return null
        return match(IntentName.ASK_HEALTH, "metric" to metric, "period" to (p.period?.word ?: "today"))
    }

    private fun askMealsNew(t: String, today: LocalDate): RouteResult.Match? {
        val p = prep(t, today, Lean.PAST) ?: return null
        if (!pastOk(p.period, today) || p.period?.singleDay == false) return null
        val what = when {
            mealListRows.any { r -> r.find(p.rest)?.let { m -> r != mealListRows[1] || m.groupValues[1].isNotEmpty() || p.period != null } == true } -> "list"
            calorieRows.any { r -> r.find(p.rest)?.let { m -> r != calorieRows[1] || m.groupValues[1].isNotEmpty() || p.period != null } == true } -> "calories"
            else -> return null
        }
        return match(IntentName.ASK_MEALS, "what" to what, "period" to (p.period?.word ?: "today"))
    }

    private fun askCheckinNew(t: String, today: LocalDate): RouteResult.Match? {
        val p = prep(t, today, Lean.PAST) ?: return null
        if (!pastOk(p.period, today) || !dayOrWeek(p.period)) return null
        val hit = checkinAskRows.withIndex().firstNotNullOfOrNull { (i, r) -> r.find(p.rest)?.let { i to it } } ?: return null
        // bare nouns ("check-in", "mood") need "my" or a period phrase
        if (hit.first > 0 && hit.second.groupValues[1].isEmpty() && p.period == null) return null
        return match(IntentName.ASK_CHECKIN, "period" to (p.period?.word ?: "today"))
    }

    /** "everything today", "all I have tomorrow": today gives the plain overview, another single day (today or later) a `day` slot. */
    private fun askEverythingNew(t: String, today: LocalDate): RouteResult.Match? {
        val p = prep(t, today, Lean.FUTURE) ?: return null
        val period = p.period ?: return null
        if (!period.singleDay || period.from.isBefore(today) || period.word == "last_night" || !everythingRow.matches(p.rest)) return null
        return if (period.from == today) match(IntentName.ASK_TODAY) else match(IntentName.ASK_TODAY, "day" to SlotParsers.isoDate(period.from))
    }

    private fun askSpendingWeek(t: String): RouteResult.Match? =
        if (askSpending.matches(t) || (askSpendingNoun.matches(t) && weekWord.containsMatchIn(t))) {
            RouteResult.Match(IntentName.ASK_SPENDING_WEEK, emptyMap())
        } else {
            null
        }
}
