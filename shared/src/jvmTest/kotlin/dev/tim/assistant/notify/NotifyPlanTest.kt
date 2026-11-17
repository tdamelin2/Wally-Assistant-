package dev.tim.assistant.notify

import dev.tim.assistant.model.NotificationDto
import dev.tim.assistant.model.NotifySettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NotifyPlanTest {
    private val settings = NotifySettingsDto()

    /** Jerusalem is UTC+2 in November: "HH:mm" local on 2026-11-11 as epoch ms. */
    private fun at(hhmm: String, date: String = "2026-11-11"): Long {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return Instant.parse("${date}T00:00:00Z").toEpochMilliseconds() + ((h - 2) * 60L + m) * 60_000
    }

    private fun row(kind: String = "CHECKIN", fireAt: Long) = NotificationDto(1, kind, "t", "b", "screen:today", fireAt, "k")

    private fun plan(row: NotificationDto, now: Long, done: Boolean = false, s: NotifySettingsDto = settings, shown: Int = 0) =
        NotifyPlan.plan(row, now, done, s, shown)

    @Test
    fun a_row_whose_time_has_come_is_shown_now() {
        assertEquals(NotifyDecision.ShowNow, plan(row(fireAt = at("21:00")), now = at("21:05")))
    }

    @Test
    fun a_future_row_is_scheduled_at_its_time() {
        val fire = at("21:00")
        assertEquals(NotifyDecision.ScheduleAt(fire), plan(row(fireAt = fire), now = at("20:00")))
    }

    @Test
    fun a_done_checkin_is_skipped_but_other_kinds_are_not() {
        assertEquals(NotifyDecision.Skip, plan(row("CHECKIN", at("21:00")), at("21:01"), done = true))
        assertEquals(NotifyDecision.ShowNow, plan(row("MORNING", at("21:00")), at("21:01"), done = true))
    }

    @Test
    fun a_row_older_than_24_hours_is_skipped() {
        val fire = at("12:00", "2026-11-10")
        assertEquals(NotifyDecision.Skip, plan(row("MORNING", fire), at("12:01")))
        assertEquals(NotifyDecision.ShowNow, plan(row("MORNING", fire), at("11:59")))
    }

    @Test
    fun a_disabled_kind_is_skipped() {
        val now = at("12:05")
        val fire = at("12:00")
        assertEquals(NotifyDecision.Skip, plan(row("CHECKIN", fire), now, s = settings.copy(checkin = false)))
        assertEquals(NotifyDecision.Skip, plan(row("MORNING", fire), now, s = settings.copy(morningList = false)))
        assertEquals(NotifyDecision.Skip, plan(row("HEADS_UP", fire), now, s = settings.copy(headsUp = false)))
        assertEquals(NotifyDecision.Skip, plan(row("IDEA", fire), now, s = settings.copy(ideas = false)))
        assertEquals(NotifyDecision.Skip, plan(row("WEEKLY", fire), now, s = settings.copy(weeklyReport = false)))
        assertEquals(NotifyDecision.ShowNow, plan(row("IDEA", fire), now))
    }

    @Test
    fun quiet_hours_crossing_midnight_skip_at_display_time() {
        // 23:30 and 02:00 are quiet; 07:00 and 22:59 are not
        assertEquals(NotifyDecision.Skip, plan(row("IDEA", at("23:20")), at("23:30")))
        assertEquals(NotifyDecision.Skip, plan(row("IDEA", at("01:50", "2026-11-12")), at("02:00", "2026-11-12")))
        assertEquals(NotifyDecision.ShowNow, plan(row("IDEA", at("06:50", "2026-11-12")), at("07:00", "2026-11-12")))
        assertEquals(NotifyDecision.ShowNow, plan(row("IDEA", at("22:40")), at("22:59")))
        // a future row due inside quiet hours is skipped, an old row displayed late inside quiet hours too
        assertEquals(NotifyDecision.Skip, plan(row("IDEA", at("23:30")), at("20:00")))
        assertEquals(NotifyDecision.Skip, plan(row("IDEA", at("22:00")), at("23:10")))
    }

    @Test
    fun the_sixth_reminder_of_a_day_is_allowed_and_the_seventh_is_not() {
        val r = row("IDEA", at("12:00"))
        assertEquals(NotifyDecision.ShowNow, plan(r, at("12:01"), shown = 5))
        assertEquals(NotifyDecision.Skip, plan(r, at("12:01"), shown = 6))
    }
}
