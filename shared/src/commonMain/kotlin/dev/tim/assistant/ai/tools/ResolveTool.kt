package dev.tim.assistant.ai.tools

import dev.tim.assistant.model.ChoiceDto

/** The kinds the `find` tool searches. */
val FIND_KINDS = listOf("course", "study_doc", "goal", "task", "doc", "event", "exam", "mail")

/** At most this many matches are returned (and shown as chips). */
const val FIND_LIMIT = 5

private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

private fun tokensOf(text: String): List<String> = text.lowercase().split(SPLIT).filter { it.isNotEmpty() }

/**
 * Names Tim might mean by [query]: [candidates] (optionally only of [kind]) whose name equals the query, contains it
 * (case-insensitive) or contains every word of it. Best first (exact, contains, all words; then name, kind, id), duplicates
 * (same kind and id) dropped, at most [limit]. A blank query matches nothing.
 */
fun findMatches(candidates: List<ChoiceDto>, kind: String?, query: String, limit: Int = FIND_LIMIT): List<ChoiceDto> {
    val q = query.trim().lowercase()
    if (q.isEmpty() || limit <= 0) return emptyList()
    val words = tokensOf(q)
    val scored = ArrayList<Pair<Int, ChoiceDto>>()
    val seen = HashSet<Pair<String, String>>()
    for (c in candidates) {
        if (kind != null && c.kind != kind) continue
        val name = c.name.trim().lowercase()
        val score = when {
            name == q -> 0
            name.contains(q) -> 1
            words.isNotEmpty() && words.all { name.contains(it) } -> 2
            else -> continue
        }
        if (seen.add(c.kind to c.id)) scored += score to c
    }
    return scored
        .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }, { it.second.kind }, { it.second.id }))
        .take(limit)
        .map { it.second }
}
