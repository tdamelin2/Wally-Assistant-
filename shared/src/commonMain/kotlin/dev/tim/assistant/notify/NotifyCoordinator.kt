package dev.tim.assistant.notify

import dev.tim.assistant.ai.ChatKv
import dev.tim.assistant.data.ChatApi
import dev.tim.assistant.data.HpResult
import dev.tim.assistant.model.NotificationDto
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.time.JERUSALEM
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What the platform does for the coordinator: put a notification on screen, or ask the clock to do so later. */
interface NotifySink {
    fun show(row: NotificationDto)
    fun schedule(row: NotificationDto, at: Long)
}

const val NOTIFY_CURSOR_KEY = "notify.cursor"
const val NOTIFY_LEDGER_KEY = "notify.ledger"
const val NOTIFY_LEDGER_KEEP_MS = 3L * 24 * 60 * 60 * 1000

const val LEDGER_SCHEDULED = "SCHEDULED"
const val LEDGER_SHOWN = "SHOWN"
const val LEDGER_SKIPPED = "SKIPPED"

/** One reminder the phone has dealt with: scheduled for later, shown, or skipped. [at] is when that happened or will. */
@Serializable
data class LedgerEntry(val row: NotificationDto, val state: String, val at: Long)

/** The ledger kept in local kv: pure list functions plus JSON in and out. */
object NotifyLedger {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LedgerEntry.serializer())

    fun decode(text: String?): List<LedgerEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encode(entries: List<LedgerEntry>): String = json.encodeToString(serializer, entries)

    fun has(entries: List<LedgerEntry>, id: Long) = entries.any { it.row.id == id }

    /** Replaces the entry of this row (or adds it). */
    fun put(entries: List<LedgerEntry>, entry: LedgerEntry): List<LedgerEntry> =
        entries.filter { it.row.id != entry.row.id } + entry

    /** Drops entries more than three days old, so the ledger stays small. */
    fun prune(entries: List<LedgerEntry>, now: Long): List<LedgerEntry> =
        entries.filter { now - maxOf(it.at, it.row.fireAt) <= NOTIFY_LEDGER_KEEP_MS }

    private fun day(at: Long) = Instant.fromEpochMilliseconds(at).toLocalDateTime(JERUSALEM).date

    /** Reminders already shown on the Jerusalem day of [now] (the daily cap counts these). */
    fun shownToday(entries: List<LedgerEntry>, now: Long): Int =
        entries.count { it.state == LEDGER_SHOWN && day(it.at) == day(now) }
}

/**
 * Turns the HP's reminder rows into notifications on the phone: fetches rows after the cursor, asks [NotifyPlan] what to do
 * with each, hands the result to [sink], records it in the ledger and only then moves `notify.cursor` forward.
 */
class NotifyCoordinator(
    private val api: ChatApi,
    private val kv: ChatKv,
    private val sink: NotifySink,
    private val checkinDoneToday: () -> Boolean,
    private val clock: () -> Long,
) {
    private val lock = Mutex()

    private suspend fun settings(): NotifySettingsDto =
        (api.notifySettings() as? HpResult.Ok)?.value ?: NotifySettingsDto()

    private fun ledger(): List<LedgerEntry> = NotifyLedger.decode(kv.get(NOTIFY_LEDGER_KEY))

    private fun saveLedger(entries: List<LedgerEntry>) {
        kv.put(NOTIFY_LEDGER_KEY, NotifyLedger.encode(NotifyLedger.prune(entries, clock())))
    }

    /** Fetches rows after the cursor and acts on each once. Offline or not set up: nothing changes and the next sync retries. */
    suspend fun fetch() = lock.withLock {
        val cursor = kv.get(NOTIFY_CURSOR_KEY)?.toLongOrNull() ?: 0L
        val items = (api.notifications(cursor) as? HpResult.Ok)?.value?.items ?: return@withLock
        if (items.isEmpty()) {
            rescheduleLocked()
            return@withLock
        }
        val cfg = settings()
        var entries = ledger()
        var next = cursor
        val now = clock()
        for (row in items.sortedBy { it.id }) {
            if (!NotifyLedger.has(entries, row.id)) {
                val decision = NotifyPlan.plan(row, now, checkinDoneToday(), cfg, NotifyLedger.shownToday(entries, now))
                entries = NotifyLedger.put(entries, apply(row, decision, now))
            }
            next = maxOf(next, row.id)
        }
        saveLedger(entries)
        kv.put(NOTIFY_CURSOR_KEY, next.toString())
        rescheduleLocked()
    }

    private fun apply(row: NotificationDto, decision: NotifyDecision, now: Long): LedgerEntry = when (decision) {
        NotifyDecision.ShowNow -> { sink.show(row); LedgerEntry(row, LEDGER_SHOWN, now) }
        is NotifyDecision.ScheduleAt -> { sink.schedule(row, decision.at); LedgerEntry(row, LEDGER_SCHEDULED, decision.at) }
        NotifyDecision.Skip -> LedgerEntry(row, LEDGER_SKIPPED, now)
    }

    /** The clock fired for [id]: decide again with the facts of now (a check-in done since, quiet hours, the cap). */
    suspend fun onAlarm(id: Long) = lock.withLock {
        val entries = ledger()
        val entry = entries.firstOrNull { it.row.id == id && it.state == LEDGER_SCHEDULED } ?: return@withLock
        saveLedger(settle(entries, entry, settings()))
    }

    private fun settle(entries: List<LedgerEntry>, entry: LedgerEntry, cfg: NotifySettingsDto): List<LedgerEntry> {
        val now = clock()
        val decision = NotifyPlan.plan(entry.row, now, checkinDoneToday(), cfg, NotifyLedger.shownToday(entries, now))
        return NotifyLedger.put(entries, apply(entry.row, decision, now))
    }

    /** After app start: asks the clock again for every reminder still waiting (alarms are lost when the phone restarts). */
    suspend fun rescheduleAll() = lock.withLock { rescheduleLocked() }

    private suspend fun rescheduleLocked() {
        val entries = ledger()
        val waiting = entries.filter { it.state == LEDGER_SCHEDULED }
        if (waiting.isEmpty()) return
        val now = clock()
        val due = waiting.filter { it.row.fireAt <= now }
        var updated = entries
        if (due.isNotEmpty()) {
            val cfg = settings()
            for (entry in due) updated = settle(updated, entry, cfg)
        }
        for (entry in waiting) if (entry.row.fireAt > now) sink.schedule(entry.row, entry.row.fireAt)
        if (updated !== entries) saveLedger(updated)
    }
}
