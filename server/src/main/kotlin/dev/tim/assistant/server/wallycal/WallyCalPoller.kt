package dev.tim.assistant.server.wallycal

import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

const val WALLYCAL_INTERVAL_MS = 15L * 60 * 1000
const val WALLYCAL_CALENDAR_ID = "wallycal"
const val WALLYCAL_ID_PREFIX = "wcal:"
private const val DAYS_BACK = 30L
private const val DAYS_AHEAD = 365L
private val JERUSALEM: ZoneId = ZoneId.of("Asia/Jerusalem")
private val pollJson = Json { ignoreUnknownKeys = true }

/** One validated occurrence from WallyCalendar. */
private class Occurrence(
    val eventId: String,
    val date: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean,
    val location: String?,
)

/** The answer was not a valid events envelope. */
private class InvalidEnvelope : Exception()

private fun JsonObject.string(key: String): String {
    val p = this[key] as? JsonPrimitive ?: throw InvalidEnvelope()
    if (!p.isString) throw InvalidEnvelope()
    return p.content
}

private fun JsonObject.long(key: String): Long {
    val p = this[key] as? JsonPrimitive ?: throw InvalidEnvelope()
    if (p.isString || p is JsonNull) throw InvalidEnvelope()
    return p.content.toLongOrNull() ?: throw InvalidEnvelope()
}

private fun JsonObject.bool(key: String): Boolean {
    val p = this[key] as? JsonPrimitive ?: throw InvalidEnvelope()
    if (p.isString || p is JsonNull) throw InvalidEnvelope()
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> throw InvalidEnvelope()
    }
}

private fun JsonObject.nullableString(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: throw InvalidEnvelope()
    if (p is JsonNull) return null
    if (!p.isString) throw InvalidEnvelope()
    return p.content
}

/** Decodes and validates the whole answer before anything is written. Throws [InvalidEnvelope]. */
private fun parseEnvelope(text: String): List<Occurrence> {
    val parsed = try {
        pollJson.parseToJsonElement(text)
    } catch (e: Exception) {
        throw InvalidEnvelope()
    }
    val root = parsed as? JsonObject ?: throw InvalidEnvelope()
    val events = root["events"] as? JsonArray ?: throw InvalidEnvelope()
    return events.map { el ->
        val o = el as? JsonObject ?: throw InvalidEnvelope()
        val id = o.string("id")
        if (id.isEmpty()) throw InvalidEnvelope()
        val date = o.string("occurrenceDate")
        try {
            if (LocalDate.parse(date).toString() != date) throw InvalidEnvelope()
        } catch (e: DateTimeParseException) {
            throw InvalidEnvelope()
        }
        val startAt = o.long("startAt")
        val endAt = o.long("endAt")
        if (endAt < startAt) throw InvalidEnvelope()
        Occurrence(id, date, o.string("title"), startAt, endAt, o.bool("allDay"), o.nullableString("location"))
    }
}

/**
 * Mirrors WallyCalendar's occurrences into cal_event rows (`wcal:<id>:<date>`). One process-owned
 * instance: the run Mutex is held from the fetch through the commit for every caller (the scheduler
 * and the create route's [pollNow]). Logs carry counts only.
 */
class WallyCalPoller(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val client: WallyCalClient,
    private val status: WallyCalStatus,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(WallyCalPoller::class.java)
    private val db get() = sdb.db
    private val runLock = Mutex()

    suspend fun run() = runLock.withLock { poll() }

    /** Same as [run]; the create route calls it right after WallyCalendar accepted a new event. */
    suspend fun pollNow() = run()

    private suspend fun poll() {
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(JERUSALEM).toLocalDate()
        val from = today.minusDays(DAYS_BACK).toString()
        val to = today.plusDays(DAYS_AHEAD).toString()
        val occurrences = try {
            val reply = client.events(from, to)
            if (reply.status != 200) {
                fail("http_${reply.status}")
                return
            }
            parseEnvelope(reply.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WallyCalUnreachable) {
            fail(e.kind)
            return
        } catch (e: InvalidEnvelope) {
            fail("malformed")
            return
        }

        var written = 0
        var deleted = 0
        sdb.writeLock.withLock {
            db.transaction {
                val seen = HashSet<String>()
                for (o in occurrences) {
                    val id = "$WALLYCAL_ID_PREFIX${o.eventId}:${o.date}"
                    seen += id
                    val stored = db.cal_eventQueries.selectById(id).executeAsOneOrNull()
                    val row = toSyncRow(
                        Cal_event(
                            id = id, updatedAt = 0, deleted = 0, serverSeq = 0, syncState = "SYNCED",
                            calendarId = WALLYCAL_CALENDAR_ID, eventId = o.eventId, title = o.title,
                            startAt = o.startAt, endAt = o.endAt, allDay = if (o.allDay) 1 else 0, location = o.location,
                        ),
                    )
                    if (stored != null && stored.deleted == 0L && toSyncRow(stored).data == row.data) continue
                    store.writeIfNewer(row.copy(updatedAt = stamp(now, stored?.updatedAt)))
                    written++
                }
                for (stored in db.cal_eventQueries.selectAll().executeAsList()) {
                    if (!stored.id.startsWith(WALLYCAL_ID_PREFIX) || stored.deleted != 0L || stored.id in seen) continue
                    store.writeIfNewer(toSyncRow(stored).copy(deleted = true, updatedAt = stamp(now, stored.updatedAt)))
                    deleted++
                }
            }
        }
        status.set(true)
        log.info("wallycal poll ok: occurrences={} written={} deleted={}", occurrences.size, written, deleted)
    }

    private fun fail(kind: String) {
        status.set(false)
        log.warn("wallycal poll failed: {}", kind)
    }
}

private fun stamp(now: Long, storedUpdatedAt: Long?): Long =
    if (storedUpdatedAt == null) now else maxOf(now, storedUpdatedAt + 1)

/**
 * Start-up cleanup: soft-deletes every live cal_event that is not a `wcal:` row (the old Google
 * copies). Idempotent; runs on every start before any scheduler. Returns the number removed.
 */
suspend fun removeNonWallyCalEvents(sdb: ServerDb, store: SyncStore, now: Long): Int {
    var removed = 0
    sdb.writeLock.withLock {
        sdb.db.transaction {
            for (stored in sdb.db.cal_eventQueries.selectAll().executeAsList()) {
                if (stored.deleted != 0L || stored.id.startsWith(WALLYCAL_ID_PREFIX)) continue
                store.writeIfNewer(toSyncRow(stored).copy(deleted = true, updatedAt = stamp(now, stored.updatedAt)))
                removed++
            }
        }
    }
    if (removed > 0) LoggerFactory.getLogger(WallyCalPoller::class.java).info("removed {} non-wallycal calendar rows", removed)
    return removed
}
