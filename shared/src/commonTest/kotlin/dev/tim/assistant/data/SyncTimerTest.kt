package dev.tim.assistant.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SyncTimerTest {
    /** A wait that records its duration and then blocks until the test sends a tick. */
    private class Ticks {
        val waits = mutableListOf<Duration>()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val wait: suspend (Duration) -> Unit = { waits += it; gate.receive() }
        fun tick() { gate.trySend(Unit) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun syncsAfterEachWaitOfTenMinutes() {
        val ticks = Ticks()
        var syncs = 0
        val job = scope.launchSyncTimer(10.minutes, ticks.wait) { syncs++ }
        assertEquals(0, syncs)
        assertEquals(listOf(10.minutes), ticks.waits)
        ticks.tick()
        assertEquals(1, syncs)
        ticks.tick()
        assertEquals(2, syncs)
        assertEquals(listOf(10.minutes, 10.minutes, 10.minutes), ticks.waits)
        job.cancel()
    }

    @Test
    fun stopsAfterCancel() {
        val ticks = Ticks()
        var syncs = 0
        val job = scope.launchSyncTimer(10.minutes, ticks.wait) { syncs++ }
        ticks.tick()
        assertEquals(1, syncs)
        job.cancel()
        ticks.tick()
        ticks.tick()
        assertEquals(1, syncs)
        assertTrue(job.isCancelled)
    }

    @Test
    fun aThrowingSyncDoesNotEndTheLoop() {
        val ticks = Ticks()
        var calls = 0
        val job = scope.launchSyncTimer(10.minutes, ticks.wait) {
            calls++
            if (calls == 1) throw IllegalStateException("boom")
        }
        ticks.tick()
        ticks.tick()
        assertEquals(2, calls)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun workResultMapsEveryStatus() {
        assertEquals(WorkOutcome.Success, workResultFor(SyncStatus.Synced(1L)))
        assertEquals(WorkOutcome.Retry, workResultFor(SyncStatus.Offline(3)))
        assertEquals(WorkOutcome.Retry, workResultFor(SyncStatus.Syncing))
        assertEquals(WorkOutcome.Failure, workResultFor(SyncStatus.WrongToken))
        assertEquals(WorkOutcome.Failure, workResultFor(SyncStatus.NotConfigured))
    }
}
