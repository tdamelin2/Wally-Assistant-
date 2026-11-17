package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.tools.ToolCheck
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.data.SPENDING_CATEGORIES
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One action the handler can offer: [tool] and [args] are what a tap runs (`ask` and `update_task` are the two special tools, see
 * [SlotFill]), [label] says in plain words what the tap does, [missing] names the one argument Tim still has to pick (or null).
 */
data class Filled(val tool: String, val args: Map<String, String>, val label: String, val missing: String?)

/**
 * Reads the arguments of one intent from free wording (PLAN 20.6 step 3) and holds the ONE label builder and argument projection that
 * both the handler's suggestions and the home-server model's suggest-only answers use, so a label always says exactly what its tap does.
 * Pure: the day comes in as a parameter.
 */
object SlotFill {
    /** Free text (a note, a meal, a journal text, a task title) is shown as its first 40 characters followed by an ellipsis. */
    const val TEXT_CUT = 40

    private val I = RegexOption.IGNORE_CASE
    private const val ELLIPSIS = "…"
    private val ADD_TOOLS = setOf("create_task", "add_spending", "add_meal", "add_checkin", "add_journal")
    private val TASK_STARTS = Regex(
        "^(?:i\\s+need\\s+to|i\\s+have\\s+to|i\\s+ought\\s+to|i\\s+should|i\\s+must|i\\s+gotta|i\\s+want\\s+to|i\\s+wanna|(?:i'm|i\\s+am)\\s+(?:going\\s+to|gonna)|i\\s+plan\\s+to|i'd\\s+like\\s+to|i\\s+would\\s+like\\s+to|remember\\s+to|remind\\s+me\\s+to|todo)(?![\\p{L}\\p{N}])[\\s,:;-]*",
        I,
    )
    private val LEAD_IN = Regex("^(?:(?:please|hey\\s+wally|hey)[\\s,]+)+", I)
    private val AMOUNT_TOKEN = Regex("(?:₪\\s*)?\\d+(?:[.,]\\d+)*(?:\\s*(?:shekels?|nis|₪))?", I)
    private val SPENDING_FILLERS = Regex("(?<![\\p{L}\\p{N}])(?:i|just|spent|paid|cost|me|on|for)(?![\\p{L}\\p{N}])", I)
    private val MEAL_LEAD = Regex("^(?:i\\s+)?(?:just\\s+)?(?:had|ate|snacked(?:\\s+on)?)(?![\\p{L}\\p{N}])\\s*", I)
    private val SNACKED = Regex("^(?:i\\s+)?(?:just\\s+)?snacked(?![\\p{L}\\p{N}])", I)
    private val ENDS_FOR_NUMBER = Regex("(?<![\\p{L}\\p{N}])for\\s+(?:\u20AA\\s*)?\\d+(?:[.,]\\d+)?\\s*$", I)
    private val MEAL_FOR = Regex("(?<![\\p{L}\\p{N}])(?:for|as)\\s+(?:an?\\s+)?(?:breakfast|lunch|dinner|snack|meal)(?![\\p{L}\\p{N}])", I)
    private val ZONE_SPACE = Regex("\\s+")

    /** The first 40 characters of [text] followed by an ellipsis when it is longer; the text itself otherwise. */
    fun cut(text: String): String {
        val clean = text.replace(ZONE_SPACE, " ").trim()
        if (clean.length <= TEXT_CUT) return clean
        var end = TEXT_CUT
        if (clean[end - 1].isHighSurrogate()) end--
        return clean.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** How a typed answer and a button label are compared: lower case, spaces collapsed, one closing `.` or `!` removed. */
    fun typedKey(text: String): String {
        var s = text.lowercase().replace(ZONE_SPACE, " ").trim()
        if (s.endsWith(".") || s.endsWith("!")) s = s.dropLast(1).trimEnd()
        return s
    }

    private fun toJson(args: Map<String, String>): JsonObject = buildJsonObject { for ((k, v) in args) put(k, v) }

    /** The arguments a tool of this name keeps: only its own fields, no `id`, no `goalId`, blank values dropped and values trimmed. */
    fun project(tool: String, args: Map<String, String>): Map<String, String> {
        if (tool == "ask") return args.filterValues { it.isNotBlank() }
        if (tool == "update_task") return args.filterKeys { it == "name" || it == "done" }.filterValues { it.isNotBlank() }
        val props = (Tools.find(tool)?.parameters?.get("properties") as? JsonObject)?.keys ?: emptySet()
        val banned = setOf("id", "goalId")
        val out = LinkedHashMap<String, String>()
        for ((k, v) in args) if (k in props && k !in banned && v.isNotBlank()) out[k] = v.trim()
        return out
    }

    /** The one argument an add still lacks (`category` for a spending, `type` for a meal), or null when it has what it needs. */
    fun missingOf(tool: String, args: Map<String, String>): String? = when (tool) {
        "add_spending" -> if (args["category"].isNullOrBlank()) "category" else null
        "add_meal" -> if (args["type"].isNullOrBlank()) "type" else null
        else -> null
    }

    /** The fixed values a missing argument may take. */
    fun valuesFor(arg: String): List<String> = when (arg) {
        "category" -> SPENDING_CATEGORIES
        "type" -> Tools.MEAL_TYPES
        else -> emptyList()
    }

    /** True for the tools that write a new row (a tap on them is only honoured on the day of the message). */
    fun isAdd(tool: String): Boolean = tool in ADD_TOOLS

    /** True when a fully-given add passes the tool's own argument check. */
    fun valid(tool: String, args: Map<String, String>): Boolean = Tools.validate(tool, toJson(args)) is ToolCheck.Ok

    /** The period of an ask option, rebuilt for [today] with the lean of its intent; null when it does not resolve. */
    private fun periodOf(intent: IntentName, args: Map<String, String>, today: LocalDate): Period? =
        Periods.resolve(args["period"] ?: "today", today, AskSlots.lean(intent))

    /**
     * The label of one action, the same for the handler and the HP model: what a tap does. Null when the arguments cannot be named
     * (an unknown tool, an add with nothing to say, an ask whose period does not resolve).
     */
    fun labelFor(tool: String, args: Map<String, String>, today: LocalDate): String? {
        fun s(key: String) = args[key].orEmpty()
        return when (tool) {
            "create_task" -> {
                val title = s("title").ifBlank { return null }
                val due = s("dueDate").takeIf { it.isNotBlank() }?.let { ", due " + ReplyTemplates.dayWord(it, today) } ?: ""
                "Add task \"${cut(title)}\"$due"
            }
            "add_spending" -> {
                val amount = s("amount").ifBlank { return null }
                val category = s("category").takeIf { it.isNotBlank() }?.let { ", " + it.lowercase() } ?: ""
                val note = s("note").takeIf { it.isNotBlank() }?.let { " (" + cut(it) + ")" } ?: ""
                "Log spending: $amount ₪$category$note"
            }
            "add_meal" -> {
                val text = s("text").ifBlank { return null }
                val head = when (s("type").uppercase()) { "MEAL" -> "Log meal"; "SNACK" -> "Log snack"; else -> "Log food" }
                "$head: ${cut(text)}"
            }
            "add_journal" -> "Journal note: " + cut(s("text").ifBlank { return null })
            "add_checkin" -> {
                val keys = listOf("mood", "energy", "sleepQuality", "stress")
                if (keys.any { s(it).isBlank() }) return null
                "Check-in: mood ${s("mood")}, energy ${s("energy")}, sleep ${s("sleepQuality")}, stress ${s("stress")}"
            }
            "update_task" -> "Mark done: " + cut(s("name").ifBlank { return null })
            "ask" -> askLabel(args, today)
            else -> null
        }
    }

    private fun askLabel(args: Map<String, String>, today: LocalDate): String? {
        val intent = IntentName.fromWire(args["intent"].orEmpty())?.takeIf { it.isAsk } ?: return null
        val period = periodOf(intent, args, today)
        val phrase = when (intent) {
            IntentName.ASK_TODAY -> "today's plan"
            IntentName.ASK_TASKS -> if (args["period"] == null) "my open tasks" else "tasks due " + (period?.label ?: return null)
            IntentName.ASK_EVENTS -> "events " + (period?.label ?: return null)
            IntentName.ASK_SPENDING_WEEK -> "spending this week"
            IntentName.ASK_SPENDING -> {
                val p = period?.label ?: return null
                args["category"]?.takeIf { it.isNotBlank() }?.let { "${it.lowercase()} spending $p" } ?: "spending $p"
            }
            IntentName.ASK_EXAM -> "next exams"
            IntentName.ASK_HEALTH -> {
                val p = period?.label ?: return null
                when (args["metric"]) { "sleep" -> "sleep $p"; "steps" -> "steps $p"; "heart" -> "resting heart rate $p"; else -> return null }
            }
            IntentName.ASK_MEALS -> (if (args["what"] == "calories") "calories " else "meals ") + (period?.label ?: return null)
            IntentName.ASK_CHECKIN -> "check-in " + (period?.label ?: return null)
            else -> return null
        }
        return "Show: $phrase"
    }

    /** A [Filled] for [tool] with [args] (projected): its label, and the missing argument; null when it cannot be labelled or an add fails the tool's check. */
    fun filled(tool: String, args: Map<String, String>, today: LocalDate): Filled? {
        val clean = project(tool, args)
        val missing = missingOf(tool, clean)
        if (isAdd(tool) && missing == null && !valid(tool, clean)) return null
        if (tool == "add_spending" && missing != null && clean["amount"].isNullOrBlank()) return null
        val label = labelFor(tool, clean, today) ?: return null
        return Filled(tool, clean, label, missing)
    }

    /** Drops duplicates of the same tool and arguments and any action whose label collides with an earlier one after [typedKey] (Decision 31). */
    fun distinct(list: List<Filled>): List<Filled> {
        val seenActions = HashSet<Pair<String, Map<String, String>>>()
        val seenLabels = HashSet<String>().apply { add(typedKey(SOMETHING_ELSE)); add(typedKey("Yes")) }
        val out = ArrayList<Filled>()
        for (f in list) {
            if (!seenActions.add(f.tool to f.args)) continue
            if (!seenLabels.add(typedKey(f.label))) continue
            out += f
        }
        return out
    }

    // ---- reading arguments from free wording ----

    private fun normName(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun otherThanToday(text: String, today: LocalDate): Boolean {
        if (Guards.otherDay(text)) return true
        return when (val scan = Periods.find(text, today, Lean.PAST)) {
            Scan.None -> false
            Scan.Many -> true
            is Scan.One -> scan.found.period.word != "today"
        }
    }

    /**
     * The arguments of [intent] read from [text] (PLAN 20.6 step 3). Null when they cannot be read safely, when the message is about
     * another day than today (an add suggestion always saves for today), or for the intents that are never suggested.
     */
    fun forIntent(intent: IntentName, text: String, today: LocalDate, targets: Targets): Filled? {
        val t = text.replace('’', '\'').replace('‘', '\'').trim()
        if (t.isEmpty()) return null
        return when (intent) {
            IntentName.CREATE_TASK -> task(t, today)
            IntentName.ADD_SPENDING -> if (addBlocked(t, today)) null else spending(t, today)
            IntentName.ADD_MEAL -> if (addBlocked(t, today)) null else meal(t, today)
            IntentName.ADD_JOURNAL -> if (addBlocked(t, today)) null else filled("add_journal", mapOf("text" to t), today)
            IntentName.ADD_CHECKIN -> if (addBlocked(t, today)) null else SlotParsers.checkinScores(t)?.let { filled("add_checkin", it, today) }
            IntentName.MARK_TASK_DONE -> markDone(t, targets, today)
            IntentName.ASK_TODAY, IntentName.ASK_TASKS, IntentName.ASK_EVENTS, IntentName.ASK_SPENDING_WEEK, IntentName.ASK_SPENDING,
            IntentName.ASK_EXAM, IntentName.ASK_HEALTH, IntentName.ASK_MEALS, IntentName.ASK_CHECKIN -> ask(intent, t, today)
            IntentName.ADD_DREAM, IntentName.MOVE_TASK, IntentName.MOVE_EXAM, IntentName.FIND, IntentName.NONE -> null
        }
    }

    private fun addBlocked(t: String, today: LocalDate): Boolean = otherThanToday(t, today) || IntentRouter.thisWeekOrTotal(t)

    private fun task(t: String, today: LocalDate): Filled? {
        var body = LEAD_IN.replace(t, "").trim().trimEnd('.', '!', '?', ' ')
        body = TASK_STARTS.replace(body, "")
        if (SlotParsers.hasTime(body)) return null
        val scan = SlotParsers.scanDate(body, today)
        if (!scan.ok) return null
        val title = IntentRouter.taskTitle(scan.rest) ?: return null
        val args = linkedMapOf("title" to title)
        scan.date?.let { args["dueDate"] = SlotParsers.isoDate(it) }
        return filled("create_task", args, today)
    }

    private fun spending(t: String, today: LocalDate): Filled? {
        val amount = SlotParsers.amount(t) ?: return null
        val category = SlotParsers.spendingCategory(t)
        val note = SPENDING_FILLERS.replace(AMOUNT_TOKEN.replace(t, " "), " ").replace(ZONE_SPACE, " ").trim(' ', ',', '.', ';', ':', '!', '?', '-')
        val args = linkedMapOf("amount" to amount)
        category?.let { args["category"] = it }
        if (note.isNotEmpty()) args["note"] = note
        return filled("add_spending", args, today)
    }

    private fun meal(t: String, today: LocalDate): Filled? {
        val food = MEAL_FOR.replace(MEAL_LEAD.replace(t, ""), " ").replace(ZONE_SPACE, " ").trim(' ', ',', '.', ';', ':', '!', '?', '-')
        if (food.length < 2 || !IntentRouter.mealTextOk(food)) return null
        // "for <number>" at the end is a price, not food
        if (ENDS_FOR_NUMBER.containsMatchIn(food)) return null
        val args = linkedMapOf("text" to food)
        (SlotParsers.mealType(t) ?: if (SNACKED.containsMatchIn(t)) "SNACK" else null)?.let { args["type"] = it }
        return filled("add_meal", args, today)
    }

    private fun markDone(t: String, targets: Targets, today: LocalDate): Filled? {
        val text = " " + normName(t) + " "
        val hits = targets.candidates("task").filter { c ->
            val n = normName(c.name)
            n.length >= 2 && text.contains(" $n ")
        }
        val one = hits.singleOrNull() ?: return null
        return filled("update_task", mapOf("name" to one.name, "done" to "true"), today)
    }

    private fun ask(intent: IntentName, t: String, today: LocalDate): Filled? {
        val (resolved, slots) = AskSlots.read(intent, t, today) ?: return null
        return filled("ask", mapOf("intent" to resolved.wire) + slots, today)
    }

    const val SOMETHING_ELSE = "Something else"
}
