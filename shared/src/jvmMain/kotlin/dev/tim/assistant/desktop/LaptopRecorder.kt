package dev.tim.assistant.desktop

import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.logWarning
import dev.tim.assistant.usage.DEVICE_LAPTOP
import dev.tim.assistant.usage.MIN_SESSION_MS
import dev.tim.assistant.usage.UsageRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SAMPLE_INTERVAL_MS = 5_000L
const val CHECKPOINT_MS = 60_000L

private fun LaptopSession.toRow() = UsageRow(id, DEVICE_LAPTOP, exe, title, startAt, endAt, localDate)

/**
 * Feeds samples to a [LaptopTracker] and saves the sessions: closed ones at once, the open one every
 * [CHECKPOINT_MS] once it is long enough (same id, growing end). Rows that fail to save are kept and retried.
 */
class LaptopRecorder(
    private val repo: Repository,
    private val tracker: LaptopTracker = LaptopTracker(),
) {
    private val unsaved = ArrayList<UsageRow>()
    private var lastCheckpoint = Long.MIN_VALUE
    private var loop: Job? = null

    /** Handles one sample. A failed save keeps the rows for the next call. */
    suspend fun record(sample: Sample) {
        unsaved += tracker.onSample(sample).map { it.toRow() }
        if (lastCheckpoint == Long.MIN_VALUE || sample.at - lastCheckpoint >= CHECKPOINT_MS) {
            lastCheckpoint = sample.at
            tracker.open()?.takeIf { it.endAt - it.startAt >= MIN_SESSION_MS }?.let { unsaved += it.toRow() }
        }
        persist()
    }

    private suspend fun persist() {
        if (unsaved.isEmpty()) return
        // Newest version of an id wins when a checkpoint and a close are both waiting.
        val batch = unsaved.associateBy { it.id }.values.toList()
        try {
            repo.saveUsageRows(batch)
            unsaved.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("usage save failed: ${e::class.simpleName}")
        }
    }

    /** Starts sampling every [SAMPLE_INTERVAL_MS]. [sampler] must not throw for an unreadable window. */
    fun start(scope: CoroutineScope, sampler: suspend () -> Sample): Job {
        val job = scope.launch {
            while (true) {
                try {
                    record(sampler())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logWarning("usage sample failed: ${e::class.simpleName}")
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
        loop = job
        return job
    }

    /** Stops sampling, closes the open session and saves everything. Used by Quit: call it once, then exit. */
    suspend fun stopAndFlush() {
        loop?.cancelAndJoin()
        loop = null
        unsaved += tracker.flush().map { it.toRow() }
        persist()
    }
}
