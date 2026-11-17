package dev.tim.assistant.charts

import dev.tim.assistant.data.SPENDING_CATEGORIES
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.time.JERUSALEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

class ChartDataTest {
    private fun checkin(date: String, mood: Long, deleted: Long = 0, updatedAt: Long = 1) =
        Checkin("checkin:$date", updatedAt, deleted, 0, "SYNCED", date, mood, mood, mood, mood, null)

    private fun spend(id: String, date: String, agorot: Long, category: String = "Food", deleted: Long = 0) =
        Spending(id, 1, deleted, 0, "SYNCED", agorot, category, null, date)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(JERUSALEM).toEpochMilliseconds()

    private fun task(id: String, doneAt: Long?, deleted: Long = 0, done: Long = 1) =
        Task(id, 1, deleted, 0, "SYNCED", id, null, done, doneAt, null)

    @Test
    fun checkinSeries_has_30_days_oldest_first_today_last_and_missing_days_are_null() {
        val s = checkinSeries(listOf(checkin("2026-11-10", 4), checkin("2026-10-12", 2)), "2026-11-10")
        assertEquals(30, s.labels.size)
        assertEquals("2026-10-12", s.labels.first())
        assertEquals("2026-11-10", s.labels.last())
        assertEquals(4, s.mood.last())
        assertEquals(2, s.mood.first())
        assertEquals(2, s.mood.filterNotNull().size)
        assertEquals(listOf(4), s.stress.filterNotNull().takeLast(1))
        assertTrue(s.energy.size == 30 && s.sleepQuality.size == 30 && s.stress.size == 30)
    }

    @Test
    fun checkinSeries_ignores_deleted_rows_and_days_outside_the_window() {
        val s = checkinSeries(
            listOf(checkin("2026-11-10", 4, deleted = 1), checkin("2026-10-11", 5), checkin("2026-11-11", 5)),
            "2026-11-10",
        )
        assertTrue(s.mood.all { it == null })
    }

    @Test
    fun checkinSeries_crosses_the_year_boundary() {
        val s = checkinSeries(listOf(checkin("2025-12-31", 3)), "2026-01-05")
        assertEquals("2025-12-07", s.labels.first())
        assertEquals("2026-01-05", s.labels.last())
        assertEquals(3, s.mood[s.labels.indexOf("2025-12-31")])
    }

    @Test
    fun spendingByCategory_lists_all_seven_categories_for_the_current_month_only() {
        val rows = listOf(
            spend("a", "2026-11-01", 1000, "Food"),
            spend("b", "2026-11-09", 250, "Food"),
            spend("c", "2026-11-03", 5000, "Bills"),
            spend("d", "2026-10-31", 9999, "Food"),
            spend("e", "2026-11-04", 777, "Fun", deleted = 1),
        )
        val r = spendingByCategory(rows, "2026-11-10")
        assertEquals(SPENDING_CATEGORIES, r.map { it.first })
        assertEquals(1250L, r.first { it.first == "Food" }.second)
        assertEquals(5000L, r.first { it.first == "Bills" }.second)
        assertEquals(0L, r.first { it.first == "Fun" }.second)
        assertEquals(6250L, r.sumOf { it.second })
    }

    @Test
    fun monthlyTotals_has_six_months_and_splits_on_the_local_date() {
        val rows = listOf(
            spend("a", "2026-10-31", 100),
            spend("b", "2026-11-01", 200),
            spend("c", "2026-06-15", 50),
            spend("d", "2026-05-31", 999),
            spend("e", "2026-11-02", 5, deleted = 1),
        )
        val r = monthlyTotals(rows, "2026-11-10")
        assertEquals(listOf("2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11"), r.map { it.first })
        assertEquals(listOf(50L, 0L, 0L, 0L, 100L, 200L), r.map { it.second })
    }

    @Test
    fun monthlyTotals_crosses_the_year_boundary() {
        val r = monthlyTotals(listOf(spend("a", "2025-12-20", 70)), "2026-02-03")
        assertEquals(listOf("2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02"), r.map { it.first })
        assertEquals(70L, r[3].second)
    }

    @Test
    fun tasksDonePerWeek_has_eight_sunday_weeks_and_splits_at_local_midnight() {
        // 2026-11-01 and 2026-11-08 are Sundays
        val tasks = listOf(
            task("a", at(2026, 10, 31, 23, 30)),
            task("b", at(2026, 11, 1, 0, 10)),
            task("c", at(2026, 11, 10, 9, 0)),
            task("d", at(2026, 9, 21, 12, 0)),
            task("e", at(2026, 11, 3, 12, 0), deleted = 1),
            task("f", null, done = 0),
        )
        val r = tasksDonePerWeek(tasks, "2026-11-10")
        assertEquals(8, r.size)
        assertEquals("2026-11-08", r.last().first)
        assertEquals("2026-09-20", r.first().first)
        val byWeek = r.toMap()
        assertEquals(1, byWeek["2026-10-25"])
        assertEquals(1, byWeek["2026-11-01"])
        assertEquals(1, byWeek["2026-11-08"])
        // a task done about 7 weeks ago is still counted
        assertEquals(1, byWeek["2026-09-20"])
        assertEquals(4, r.sumOf { it.second })
    }

    @Test
    fun tasksDonePerWeek_counts_a_task_done_six_weeks_ago() {
        val r = tasksDonePerWeek(listOf(task("a", at(2026, 9, 30, 12, 0))), "2026-11-10")
        assertEquals(1, r.sumOf { it.second })
        assertEquals(1, r.toMap()["2026-09-27"])
    }

    @Test
    fun empty_input_gives_all_zero_or_null_series() {
        val today = "2026-11-10"
        val s = checkinSeries(emptyList(), today)
        assertEquals(30, s.labels.size)
        assertTrue(listOf(s.mood, s.energy, s.sleepQuality, s.stress).all { list -> list.size == 30 && list.all { it == null } })
        assertTrue(spendingByCategory(emptyList(), today).all { it.second == 0L })
        assertEquals(7, spendingByCategory(emptyList(), today).size)
        assertTrue(monthlyTotals(emptyList(), today).let { it.size == 6 && it.all { m -> m.second == 0L } })
        assertTrue(tasksDonePerWeek(emptyList(), today).let { it.size == 8 && it.all { w -> w.second == 0 } })
    }
}
