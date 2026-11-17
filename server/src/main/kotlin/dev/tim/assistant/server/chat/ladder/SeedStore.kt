package dev.tim.assistant.server.chat.ladder

import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** One example sentence of an intent. */
data class Seed(val intent: IntentName, val text: String)

/** A complete, immutable set of seeds with unit-length vectors of one shared size. */
class SeedSnapshot(val seeds: List<Seed>, val vectors: List<FloatArray>) {
    val dimension: Int get() = vectors.first().size
}

/** The "ladder" scheduler tick: its job is to call [SeedStore.startSeeding] again while the seeds are not ready. */
const val LADDER_TICK_MS = 30_000L

/** The embedding timeout for the seed run: the first call may load the model. */
const val SEED_EMBED_TIMEOUT_MS = 10_000L

/**
 * Keeps the seed vectors in memory only. [startSeeding] runs at most one background coroutine; only when every
 * seed was embedded with a valid vector does it publish a snapshot (all or nothing) and set [ready]. It never
 * blocks the caller, takes no generation lock, and is cancelled with [scope] at shutdown.
 */
class SeedStore(
    private val embedder: TextEmbedder,
    private val scope: CoroutineScope,
    private val seeds: List<Seed> = loadSeeds(),
) {
    private val log = LoggerFactory.getLogger(SeedStore::class.java)
    private val lock = Any()
    private var job: Job? = null

    @Volatile
    private var published: SeedSnapshot? = null

    val ready: Boolean get() = published != null

    /** The complete snapshot, or null while not ready. */
    fun snapshot(): SeedSnapshot? = published

    /** Starts seeding unless it is ready or a run is already going. Returns at once. */
    fun startSeeding() {
        synchronized(lock) {
            if (published != null || job?.isActive == true) return
            job = scope.launch {
                val snap = embedAll()
                if (snap != null) {
                    published = snap
                    log.info("sorter seeds ready: {} vectors", snap.vectors.size)
                } else {
                    log.info("sorter seeding did not finish, will retry")
                }
            }
        }
    }

    private suspend fun embedAll(): SeedSnapshot? {
        val vectors = ArrayList<FloatArray>(seeds.size)
        var dim = -1
        for (seed in seeds) {
            val raw = embedder.embed(seed.text, SEED_EMBED_TIMEOUT_MS) ?: return null
            val unit = normalized(raw) ?: return null
            if (dim == -1) dim = unit.size else if (unit.size != dim) return null
            vectors += unit
        }
        if (vectors.isEmpty()) return null
        return SeedSnapshot(seeds.toList(), vectors.toList())
    }

    companion object {
        /** Reads `/intent-seeds.jsonl` (one `{"intent":..., "text":...}` per line). */
        fun loadSeeds(): List<Seed> {
            val raw = checkNotNull(SeedStore::class.java.getResourceAsStream("/intent-seeds.jsonl")) { "intent-seeds.jsonl missing" }
                .bufferedReader(Charsets.UTF_8).readText()
            return raw.lines().filter { it.isNotBlank() }.map { line ->
                val o = Json.parseToJsonElement(line).jsonObject
                val name = o.getValue("intent").jsonPrimitive.content
                Seed(checkNotNull(IntentName.fromWire(name)) { "unknown intent $name" }, o.getValue("text").jsonPrimitive.content)
            }
        }
    }
}

/** The vector scaled to length 1, or null when it is empty, has a non-finite value or has zero length. */
fun normalized(v: FloatArray): FloatArray? {
    if (v.isEmpty()) return null
    var sum = 0.0
    for (x in v) {
        if (!x.isFinite()) return null
        sum += x.toDouble() * x
    }
    if (sum <= 0.0) return null
    val norm = sqrt(sum).toFloat()
    if (norm == 0f || !norm.isFinite()) return null
    return FloatArray(v.size) { v[it] / norm }
}
