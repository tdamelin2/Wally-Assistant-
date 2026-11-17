package dev.tim.assistant.server.notify

import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.server.ai.jerusalemMs
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotifyRulesTest {
    private val settings = NotifySettingsDto()
    private val day = "2026-11-11"

    private fun at(hour: Int, minute: Int = 0) = jerusalemMs(day, hour, minute)

    @Test
    fun a_reminder_in_the_daytime_is_allowed() {
        for (kind in listOf(KIND_CHECKIN, KIND_MORNING, KIND_HEADS_UP, KIND_IDEA, KIND_WEEKLY)) {
            assertTrue(allowed(kind, at(12), 0, settings), kind)
        }
    }

    @Test
    fun quiet_hours_cross_midnight_and_block_the_edges_correctly() {
        assertFalse(allowed(KIND_CHECKIN, at(23, 0), 0, settings), "23:00 starts the quiet window")
        assertFalse(allowed(KIND_CHECKIN, at(23, 30), 0, settings))
        assertFalse(allowed(KIND_CHECKIN, at(2, 0), 0, settings))
        assertFalse(allowed(KIND_CHECKIN, at(6, 59), 0, settings))
        assertTrue(allowed(KIND_CHECKIN, at(7, 0), 0, settings), "07:00 ends it")
        assertTrue(allowed(KIND_CHECKIN, at(22, 59), 0, settings))
    }

    @Test
    fun quiet_hours_use_the_jerusalem_clock_not_utc() {
        // 21:30 UTC is 23:30 in Jerusalem in November
        val utc2130 = java.time.Instant.parse("2026-11-11T21:30:00Z").toEpochMilli()
        assertFalse(allowed(KIND_IDEA, utc2130, 0, settings))
        // 05:30 UTC is 07:30 in Jerusalem
        val utc0530 = java.time.Instant.parse("2026-11-11T05:30:00Z").toEpochMilli()
        assertTrue(allowed(KIND_IDEA, utc0530, 0, settings))
    }

    @Test
    fun a_window_inside_one_day_blocks_only_its_hours() {
        val s = settings.copy(quietStart = "13:00", quietEnd = "14:00")
        assertFalse(allowed(KIND_IDEA, at(13, 30), 0, s))
        assertTrue(allowed(KIND_IDEA, at(14, 0), 0, s))
        assertTrue(allowed(KIND_IDEA, at(23, 30), 0, s))
    }

    @Test
    fun six_a_day_is_the_cap() {
        assertTrue(allowed(KIND_IDEA, at(12), 5, settings))
        assertFalse(allowed(KIND_IDEA, at(12), 6, settings))
        assertFalse(allowed(KIND_IDEA, at(12), 9, settings))
    }

    @Test
    fun a_switched_off_kind_is_never_allowed() {
        assertFalse(allowed(KIND_CHECKIN, at(12), 0, settings.copy(checkin = false)))
        assertFalse(allowed(KIND_MORNING, at(12), 0, settings.copy(morningList = false)))
        assertFalse(allowed(KIND_HEADS_UP, at(12), 0, settings.copy(headsUp = false)))
        assertFalse(allowed(KIND_IDEA, at(12), 0, settings.copy(ideas = false)))
        assertFalse(allowed(KIND_WEEKLY, at(12), 0, settings.copy(weeklyReport = false)))
        assertTrue(allowed(KIND_IDEA, at(12), 0, settings.copy(checkin = false)), "the other switches stay independent")
        assertFalse(allowed("SOMETHING_ELSE", at(12), 0, settings))
    }

    @Test
    fun settings_default_when_nothing_is_stored() {
        val sdb = openDb(tempDbPath())
        try {
            assertEquals(NotifySettingsDto(), loadNotifySettings(sdb.db))
        } finally {
            sdb.close()
        }
    }

    @Test
    fun stored_settings_are_read_and_malformed_values_fall_back() {
        val sdb = openDb(tempDbPath())
        try {
            val kv = sdb.db.kvQueries
            kv.put(KV_CHECKIN_TIME, "20:15")
            kv.put(KV_QUIET_START, "22:30")
            kv.put(KV_QUIET_END, "banana")
            kv.put(KV_KINDS, "checkin, ideas")
            val s = loadNotifySettings(sdb.db)
            assertEquals("20:15", s.checkinTime)
            assertEquals("22:30", s.quietStart)
            assertEquals("07:00", s.quietEnd)
            assertTrue(s.checkin && s.ideas)
            assertFalse(s.morningList || s.headsUp || s.weeklyReport)
            kv.put(KV_KINDS, "")
            val none = loadNotifySettings(sdb.db)
            assertFalse(none.checkin || none.morningList || none.headsUp || none.ideas || none.weeklyReport)
        } finally {
            sdb.close()
        }
    }

    @Test
    fun the_tap_target_of_every_kind_follows_the_contract() {
        assertEquals("screen:checkin-add", targetOf(KIND_CHECKIN, 1, null))
        assertEquals("screen:today", targetOf(KIND_MORNING, 2, null))
        assertEquals("exam:e1", targetOf(KIND_HEADS_UP, 3, "exam:e1"))
        assertEquals("event:wcal:9", targetOf(KIND_HEADS_UP, 3, "event:wcal:9"))
        assertEquals("idea:42", targetOf(KIND_IDEA, 42, null))
        assertEquals("chat:weekly-report", targetOf(KIND_WEEKLY, 5, "weekly-report"))
    }

    @Test
    fun jerusalem_helpers_follow_the_local_clock() {
        val date = LocalDate.parse(day)
        assertEquals(jerusalemMs(day, 7, 30), jerusalemMillis(date, "07:30"))
        assertNull(jerusalemMillis(date, "25:00"))
        val (from, to) = jerusalemDayBounds(date)
        assertEquals(jerusalemMs(day, 0), from)
        assertEquals(24L * 3600 * 1000, to - from)
        assertEquals(date, jerusalemDate(jerusalemMs(day, 23, 59)))
        assertEquals(date.plusDays(1), jerusalemDate(jerusalemMs(day, 23, 59) + 2 * 60_000L))
    }
}
