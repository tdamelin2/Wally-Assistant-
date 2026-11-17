package dev.tim.assistant.ui

import dev.tim.assistant.data.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncBarTest {
    private val now = 10_000_000L

    @Test
    fun under_a_minute_reads_just_now() {
        assertEquals("Synced just now", syncBarText(SyncStatus.Synced(now - 30_000), now))
        assertEquals("Synced just now", syncBarText(SyncStatus.Synced(now - 59_999), now))
    }

    @Test
    fun minutes_after_that() {
        assertEquals("Synced 1 min ago", syncBarText(SyncStatus.Synced(now - 60_000), now))
        assertEquals("Synced 5 min ago", syncBarText(SyncStatus.Synced(now - 5 * 60_000), now))
    }

    @Test
    fun other_statuses_keep_their_texts() {
        assertEquals("Offline — 3 waiting", syncBarText(SyncStatus.Offline(3), now))
        assertEquals("Wrong token — check Settings", syncBarText(SyncStatus.WrongToken, now))
        assertEquals("Not set up — open Settings", syncBarText(SyncStatus.NotConfigured, now))
        assertEquals("Syncing…", syncBarText(SyncStatus.Syncing, now))
    }
}
