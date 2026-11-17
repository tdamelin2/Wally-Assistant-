package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.ai.mentionsDreams

/** Raised in 0.02 steps by SorterCalibrationTest until no sentence of the dev set is accepted with a wrong intent. */
const val SORTER_MIN_SCORE = 0.88
const val SORTER_MIN_MARGIN = 0.10

/** From the Phase 18 gate (reports/phase-18-measure.md): Hebrew ranked 7 of 10, so Hebrew text may use the sorter. */
const val SORTER_HEBREW = true

/** The only intents the sorter may answer: read-only ones (B33). A wrong choice gives a wrong answer, never a wrong save. */
val SORTER_ACTS: Set<IntentName> = IntentName.entries.filter { it.isAsk }.toSet()

/** The embedding timeout for one message. */
const val SORTER_EMBED_TIMEOUT_MS = 2_000L

/** What the nearest seeds say about one vector. [accepted] is non-null only when every rule passed. */
data class SorterVerdict(val intent: IntentName, val topScore: Double, val margin: Double, val accepted: IntentName?)

/** The best score per intent (highest first) for one message, and the verdict the sorter's own rules give for the same vector. */
data class Ranked(val scores: List<Pair<IntentName, Double>>, val verdict: SorterVerdict?)

/**
 * Rung 2: picks one of the read-only ask intents for a reworded question by nearest seeds. Read-only by design:
 * the caller never gets anything it could save. [embedder] null means the sorter is off.
 */
class Sorter(
    private val store: SeedStore,
    private val embedder: TextEmbedder?,
    private val hebrewAllowed: Boolean = SORTER_HEBREW,
    private val minScore: Double = SORTER_MIN_SCORE,
    private val minMargin: Double = SORTER_MIN_MARGIN,
) {
    /** The intent to answer, or null (off, not ready, dream text, Hebrew switched off, no vector, or a rule failed). */
    suspend fun classify(text: String): IntentName? = rank(text)?.verdict?.accepted

    /**
     * One embedding call for [text]: the best score of every intent (highest first) and the [decide] verdict for the same vector.
     * Null when the sorter is off, not ready, the text mentions dreams, Hebrew is switched off, or the embedding call fails.
     */
    suspend fun rank(text: String): Ranked? {
        val emb = embedder ?: return null
        val snap = store.snapshot() ?: return null
        if (mentionsDreams(text)) return null
        if (!hebrewAllowed && Guards.hasHebrew(text)) return null
        val raw = emb.embed(text, SORTER_EMBED_TIMEOUT_MS) ?: return null
        val query = normalized(raw) ?: return null
        if (query.size != snap.dimension) return null
        val best = LinkedHashMap<IntentName, Double>()
        for (i in snap.vectors.indices) {
            val s = dot(query, snap.vectors[i])
            val intent = snap.seeds[i].intent
            if (s > (best[intent] ?: Double.NEGATIVE_INFINITY)) best[intent] = s
        }
        return Ranked(best.entries.sortedByDescending { it.value }.map { it.key to it.value }, decide(query, snap))
    }

    /** Scores one unit-length vector against the snapshot; null when the snapshot is empty. */
    fun decide(query: FloatArray, snap: SeedSnapshot): SorterVerdict? {
        if (snap.vectors.size < 3) return null
        val scores = DoubleArray(snap.vectors.size) { i -> dot(query, snap.vectors[i]) }
        val order = scores.indices.sortedByDescending { scores[it] }
        val top3 = order.take(3).map { snap.seeds[it].intent }
        val intent = top3[0]
        val family = familyOf(intent)
        val top = scores[order[0]]
        // ask_today and ask_events are one family (both answers list events), and so are the two spending intents: they never count against each other
        val bestOther = order.firstOrNull { familyOf(snap.seeds[it].intent) != family }?.let { scores[it] } ?: -1.0
        val margin = top - bestOther
        val accepted = intent.takeIf {
            top3.all { t -> familyOf(t) == family } && top >= minScore && margin >= minMargin && intent in SORTER_ACTS
        }
        return SorterVerdict(intent, top, margin, accepted)
    }

    private fun familyOf(i: IntentName): IntentName = when (i) {
        IntentName.ASK_EVENTS -> IntentName.ASK_TODAY
        IntentName.ASK_SPENDING_WEEK -> IntentName.ASK_SPENDING
        else -> i
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s
    }
}
