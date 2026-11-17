package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.tools.findMatches
import dev.tim.assistant.data.Repository
import dev.tim.assistant.model.ChoiceDto
import dev.tim.assistant.ui.forms.formatAgorot
import java.time.Instant
import java.time.ZoneId

/** The outcome of looking for the thing Tim named. */
sealed interface Resolved {
    data class One(val choice: ChoiceDto) : Resolved
    /** Up to [Targets.LIMIT] choices Tim picks from. */
    data class Many(val choices: List<ChoiceDto>) : Resolved
    data object None : Resolved
}

/**
 * The things a code reply or the HP form may change, by name (PLAN 18.5 step 2). The executor's own name resolution covers only
 * goal, course and mail inputs, so the ladder builds its own lists from the [Repository]. Tokens are empty (the server stamps them).
 */
class Targets(private val repo: Repository, private val clock: () -> Long) {
    companion object {
        const val LIMIT = 5
        val KINDS = listOf("task", "exam", "goal", "doc", "meal", "spending", "checkin", "journal")
        private val LOG_KINDS = setOf("meal", "spending", "checkin", "journal")
        private val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
        private val GENERIC = setOf(
            "that", "it", "this", "that one", "this one", "the last one", "last one", "the last", "last", "the latest", "latest",
            "the latest one", "the newest", "newest", "the newest one",
        )
        private val KIND_WORDS = mapOf(
            "meal" to setOf("meal", "snack", "food", "the meal", "the snack", "my meal"),
            "spending" to setOf("spending", "expense", "the spending", "the expense", "my spending", "the payment", "payment"),
            "checkin" to setOf("checkin", "check in", "check-in", "the checkin", "the check-in", "my check-in", "my checkin"),
            "journal" to setOf("journal", "entry", "journal entry", "the journal", "the entry", "my journal", "the journal entry"),
        )
    }

    private fun todayIso(): String = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate().toString()

    private fun one(text: String, max: Int): String {
        val clean = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (clean.length <= max) return clean
        var end = max
        if (end > 0 && clean[end - 1].isHighSurrogate()) end--
        return clean.substring(0, end)
    }

    /** The existing items of [kind] Tim may mean; an unknown kind has none. */
    fun candidates(kind: String): List<ChoiceDto> {
        val today = todayIso()
        return when (kind) {
            "task" -> repo.openTasks().map { ChoiceDto("task", it.id, it.title, "") }
            "exam" -> repo.exams().filter { it.deleted == 0L && it.localDate >= today }
                .sortedWith(compareBy({ it.localDate }, { it.time ?: "" }, { it.title }))
                .map { ChoiceDto("exam", it.id, it.title, "") }
            "goal" -> repo.goals().map { ChoiceDto("goal", it.id, it.title, "") }
            "doc" -> repo.docs().map { ChoiceDto("doc", it.id, it.title, "") }
            "meal" -> repo.meals().filter { it.localDate == today }
                .map { ChoiceDto("meal", it.id, one(it.text, 60), "") }
            "spending" -> repo.spending().filter { it.localDate == today }
                .sortedByDescending { it.updatedAt }
                .map {
                    val note = it.note?.let { n -> one(n, 40) }?.takeIf { n -> n.isNotEmpty() }
                    ChoiceDto("spending", it.id, "${it.category} ${formatAgorot(it.amountAgorot)} ₪" + (note?.let { n -> " $n" } ?: ""), "")
                }
            "checkin" -> listOfNotNull(repo.checkinForDate(today)).map { ChoiceDto("checkin", it.id, "Check-in ${it.localDate}", "") }
            "journal" -> repo.journal()
                .filter { Instant.ofEpochMilli(it.createdAt).atZone(ZONE).toLocalDate().toString() == today }
                .sortedByDescending { it.createdAt }
                .map { ChoiceDto("journal", it.id, one(it.text, 60), "") }
            else -> emptyList()
        }
    }

    private fun normalise(query: String): String =
        query.lowercase().replace(Regex("[^\\p{L}\\p{N} -]"), " ").replace(Regex("\\s+"), " ").trim()

    /** One, up to five, or no choice for the name [query] among the items of [kind]. */
    fun resolve(kind: String, query: String): Resolved {
        val all = candidates(kind)
        if (all.isEmpty()) return Resolved.None
        if (kind !in LOG_KINDS) return pick(findMatches(all, null, query, LIMIT))
        val matches = findMatches(all, null, query, LIMIT)
        if (matches.isNotEmpty()) return pick(matches)
        val q = normalise(query)
        if (q.isEmpty() || q in GENERIC || q in (KIND_WORDS[kind] ?: emptySet())) return Resolved.One(all.first())
        // a name that matches nothing: Tim picks, even when only one candidate exists
        return Resolved.Many(all.take(LIMIT))
    }

    private fun pick(matches: List<ChoiceDto>): Resolved = when (matches.size) {
        0 -> Resolved.None
        1 -> Resolved.One(matches[0])
        else -> Resolved.Many(matches.take(LIMIT))
    }
}
