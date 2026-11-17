package dev.tim.assistant.server.chat.ladder

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ported from the Phase 17 eval script: text that shows an id, an id-like token or a tool name. Case-sensitive, as there. */
val ID_LIKE = Regex("[0-9a-f]{8}-[0-9a-f]{4}|\\b[a-z_]+:[0-9a-f-]{6,}|\\bid[: ]|\\b(get|add|create|update)_[a-z]+")

/**
 * Every reply a code rung or the validator writes itself, in the voice of wally-voice.md (casual, first person, short). A template
 * never contains an id or a tool name: the executor's own result text is written for a model and is never shown.
 */
object ReplyTemplates {
    val ADD_ACTIONS = listOf("create_task", "add_spending", "add_meal", "add_checkin", "add_journal", "add_dream")

    private fun variant(messageId: String) = Math.floorMod(messageId.hashCode(), 3)

    private fun one(text: String, max: Int): String {
        val clean = text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.length <= max) return clean
        var end = max
        if (end > 0 && clean[end - 1].isHighSurrogate()) end--
        return clean.substring(0, end).trimEnd()
    }

    /** A name that is safe to say: an id-like or empty value becomes [fallback]. */
    private fun safe(text: String, max: Int, fallback: String): String {
        val clean = one(text, max).trimEnd('.', '!', '?', ',', ';', ':')
        return if (clean.isEmpty() || ID_LIKE.containsMatchIn(clean)) fallback else clean
    }

    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    private val dayYearFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

    /** The exact day for a card: "Sunday 15 November", plus the year when it is not [today]'s year; an unreadable date is returned as is. */
    fun fullDay(date: String, today: LocalDate): String {
        val d = try { LocalDate.parse(date) } catch (_: Exception) { return date }
        return d.format(if (d.year == today.year) dayFormat else dayYearFormat)
    }

    /** "today", "tomorrow", "Monday 16 November" or the plain date when [today] is unknown. */
    fun dayWord(date: String, today: LocalDate?): String {
        val d = try { LocalDate.parse(date) } catch (_: Exception) { return date }
        if (today == null) return date
        return when (d) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> d.format(dayFormat)
        }
    }

    /** One of three wordings for each of the six adds, chosen by `messageId.hashCode() mod 3`. */
    fun confirm(action: String, slots: Map<String, String>, messageId: String, today: LocalDate? = null): String {
        val v = variant(messageId)
        fun s(key: String) = slots[key].orEmpty()
        return when (action) {
            "create_task" -> {
                val title = safe(s("title"), 120, "that task")
                val due = s("dueDate").takeIf { it.isNotBlank() }?.let { dayWord(it, today) }
                when (v) {
                    0 -> "Added: $title" + (due?.let { ", due $it" } ?: "") + "."
                    1 -> "Got it. $title is on your list" + (due?.let { " for $it" } ?: "") + "."
                    else -> "Done, $title is saved" + (due?.let { " for $it" } ?: "") + "."
                }
            }
            "add_spending" -> {
                val amount = safe(s("amount"), 20, "that amount")
                val category = safe(s("category"), 30, "other").lowercase()
                val note = s("note").takeIf { it.isNotBlank() }?.let { safe(it, 60, "") }?.takeIf { it.isNotEmpty() }
                val withNote = note?.let { " ($it)" } ?: ""
                when (v) {
                    0 -> "Added: $amount ₪ on $category$withNote."
                    1 -> "Got it. $amount ₪ for $category$withNote is logged."
                    else -> "Done, I logged $amount ₪ under $category$withNote."
                }
            }
            "add_meal" -> {
                val text = safe(s("text"), 120, "that")
                val kind = if (s("type").equals("SNACK", ignoreCase = true)) "snack" else "meal"
                when (v) {
                    0 -> "Added to your food log: $text."
                    1 -> "Got it. I logged that $kind: $text."
                    else -> "Done, $text is saved as a $kind."
                }
            }
            "add_checkin" -> {
                val scores = "mood ${s("mood")}, energy ${s("energy")}, sleep ${s("sleepQuality")}, stress ${s("stress")}"
                when (v) {
                    0 -> "Added your check-in: $scores."
                    1 -> "Got it. Your check-in is saved: $scores."
                    else -> "Done, I logged today's check-in: $scores."
                }
            }
            "add_journal" -> when (v) {
                0 -> "Added to your journal."
                1 -> "Got it. That is in your journal now."
                else -> "Done, your journal entry is saved."
            }
            "add_dream" -> when (v) {
                0 -> "Added your dream to the dream log."
                1 -> "Got it. Your dream is saved."
                else -> "Done, I saved that dream."
            }
            else -> sayAgain()
        }
    }

    fun card(): String = "I've put that on a card. Tap Yes to confirm."

    fun whichOne(): String = "Which one do you mean?"

    fun found(name: String): String = "Found it: ${safe(name, 120, "that one")}."

    fun foundSeveral(): String = "I found a few. Which one?"

    fun notFound(query: String): String {
        val q = safe(query, 60, "")
        return if (q.isEmpty()) "I couldn't find that." else "I couldn't find anything called $q."
    }

    fun notFoundList(names: List<String>): String {
        val list = names.map { safe(it, 60, "") }.filter { it.isNotEmpty() }.take(5)
        return if (list.isEmpty()) "I couldn't find that." else "I couldn't find that. Here's what you have: ${list.joinToString(", ")}."
    }

    fun nothingToday(): String = "Nothing logged today yet."

    fun needsLaptop(): String = "That needs the laptop to be on."

    fun sayAgain(): String = "I didn't get that. Can you say it another way?"

    fun whichCategory(): String = "Which category?"

    fun mealOrSnack(): String = "Was that a meal or a snack?"

    /** The one-suggestion question; [label] comes from the shared label builder and is never an id. */
    fun didYouMean(label: String): String = "Did you mean: $label?"

    fun didYouMeanSeveral(): String = "Did you mean one of these?"

    fun questionClosed(): String = "That question isn't open any more."

    fun codexNotSetUp(): String = "Codex isn't set up on the home server."

    fun codexNoDreams(): String = "I can't send dream text to Codex."

    fun codexTooLong(): String = "That's too long for Codex (limit 2,000 characters)."

    fun codexAlreadyAsked(): String = "Codex is already working on that. Ask again in a few minutes to see the answer."

    /** Exactly the text a client waits for before it starts polling for the answer. */
    fun codexAsked(): String = dev.tim.assistant.model.CODEX_ASKED_TEXT

    fun codexCouldNotQueue(): String = "I couldn't send that to Codex. Try again in a minute."

    fun tooLong(): String = "That one is too long for me right now. It needs the laptop on."
}
