package dev.tim.assistant.ui

import dev.tim.assistant.db.Ai_job
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.ui.lists.MealStatus
import dev.tim.assistant.ui.lists.MealTotal
import dev.tim.assistant.ui.lists.TaskGroup
import dev.tim.assistant.ui.lists.checkinDots
import dev.tim.assistant.ui.lists.groupThousands
import dev.tim.assistant.ui.lists.mealDayTotal
import dev.tim.assistant.ui.lists.mealStatus
import dev.tim.assistant.ui.lists.taskGroups
import dev.tim.assistant.ui.lists.totalLine
import dev.tim.assistant.ui.lists.weekByCategory
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogLogicTest {
    private val hour = 60L * 60 * 1000

    private fun spend(id: String, cat: String, agorot: Long, date: String, deleted: Long = 0) =
        Spending(id, 1, deleted, 0, "SYNCED", agorot, cat, null, date)

    private fun meal(id: String, kcal: Long?, updatedAt: Long = 0, protein: Long? = null, carbs: Long? = null, fat: Long? = null) =
        Meal(id, updatedAt, 0, 0, "SYNCED", "MEAL", "food", 0, "2026-11-11", kcal, protein, carbs, fat, null)

    private fun job(status: String) =
        Ai_job("j", 1, 0, 0, "SYNCED", "MEAL_LOOKUP", """{"mealId":"m"}""", status, null, null, 0, 1, null)

    private fun task(id: String, due: String?) = Task(id, 1, 0, 0, "SYNCED", id, due, 0, null, null)

    // ---- checkinDots ----

    @Test
    fun dots_are_clamped_to_one_through_five() {
        assertEquals(1, checkinDots(-3))
        assertEquals(1, checkinDots(0))
        assertEquals(1, checkinDots(1))
        assertEquals(3, checkinDots(3))
        assertEquals(5, checkinDots(5))
        assertEquals(5, checkinDots(9))
    }

    // ---- weekByCategory ----

    @Test
    fun week_starts_on_sunday_and_sums_per_category_largest_first() {
        // 2026-11-11 is a Wednesday; its week runs Sunday 8 Nov to Saturday 14 Nov.
        val rows = listOf(
            spend("a", "Food", 4800, "2026-11-08"),
            spend("b", "Food", 2000, "2026-11-11"),
            spend("c", "Shopping", 12000, "2026-11-14"),
            spend("d", "Transport", 7600, "2026-11-09"),
            spend("e", "Bills", 100, "2026-11-07"),
            spend("f", "Bills", 100, "2026-11-15"),
            spend("g", "Food", 999, "2026-11-10", deleted = 1),
        )
        val week = weekByCategory(rows, LocalDate(2026, 11, 11))
        assertEquals(listOf("Shopping" to 12000L, "Transport" to 7600L, "Food" to 6800L), week)
    }

    @Test
    fun a_sunday_belongs_to_the_week_it_starts_and_a_saturday_to_the_week_before() {
        val rows = listOf(spend("a", "Food", 100, "2026-11-08"), spend("b", "Food", 50, "2026-11-07"))
        assertEquals(listOf("Food" to 100L), weekByCategory(rows, LocalDate(2026, 11, 8)))
        assertEquals(listOf("Food" to 50L), weekByCategory(rows, LocalDate(2026, 11, 7)))
    }

    @Test
    fun week_with_no_rows_is_empty_and_ties_sort_by_name() {
        assertTrue(weekByCategory(emptyList(), LocalDate(2026, 11, 11)).isEmpty())
        val rows = listOf(spend("a", "B", 100, "2026-11-11"), spend("b", "A", 100, "2026-11-11"))
        assertEquals(listOf("A" to 100L, "B" to 100L), weekByCategory(rows, LocalDate(2026, 11, 11)))
    }

    // ---- mealDayTotal ----

    @Test
    fun total_skips_unestimated_meals_and_counts_them_as_missing() {
        val total = mealDayTotal(listOf(meal("a", 420, protein = 24, carbs = 48, fat = 12), meal("b", 720, protein = 38, carbs = 82, fat = 26), meal("c", null), meal("d", null)))
        assertEquals(MealTotal(1140, 62, 130, 38, 2), total)
        assertEquals("≈ 1,140 kcal (2 not estimated)", totalLine(total))
    }

    @Test
    fun total_of_nothing_is_zero_and_complete_totals_have_no_missing_note() {
        assertEquals(MealTotal(0, 0, 0, 0, 0), mealDayTotal(emptyList()))
        assertEquals("≈ 420 kcal", totalLine(mealDayTotal(listOf(meal("a", 420)))))
        assertEquals("1,234,567", groupThousands(1234567))
        assertEquals("999", groupThousands(999))
    }

    // ---- mealStatus ----

    @Test
    fun a_meal_with_kcal_is_estimated_whatever_the_job_says() {
        assertEquals(MealStatus.Estimated, mealStatus(meal("a", 100), job("FAILED"), 10 * hour))
        assertEquals(MealStatus.Estimated, mealStatus(meal("a", 100), null, 10 * hour))
    }

    @Test
    fun queued_or_running_lookup_means_estimating_even_on_an_old_meal() {
        assertEquals(MealStatus.Estimating, mealStatus(meal("a", null, updatedAt = 0), job("QUEUED"), 3 * hour))
        assertEquals(MealStatus.Estimating, mealStatus(meal("a", null, updatedAt = 0), job("RUNNING"), 3 * hour))
    }

    @Test
    fun failed_lookup_means_failed_even_on_a_new_meal() {
        assertEquals(MealStatus.Failed, mealStatus(meal("a", null, updatedAt = 10 * hour), job("FAILED"), 10 * hour))
    }

    @Test
    fun without_a_lookup_the_meal_fails_only_after_two_hours() {
        val m = meal("a", null, updatedAt = 1_000)
        assertEquals(MealStatus.Estimating, mealStatus(m, null, 1_000))
        assertEquals(MealStatus.Estimating, mealStatus(m, null, 1_000 + 2 * hour))
        assertEquals(MealStatus.Failed, mealStatus(m, null, 1_000 + 2 * hour + 1))
    }

    @Test
    fun a_meal_changed_in_the_future_is_still_estimating() {
        assertEquals(MealStatus.Estimating, mealStatus(meal("a", null, updatedAt = 50 * hour), null, 1 * hour))
    }

    // ---- taskGroups ----

    @Test
    fun tasks_are_grouped_by_due_date_and_a_no_due_task_is_never_overdue() {
        val today = LocalDate(2026, 11, 11)
        val groups = taskGroups(
            listOf(
                task("late", "2026-11-09"), task("now", "2026-11-11"), task("tomorrow", "2026-11-12"),
                task("sixAhead", "2026-11-17"), task("sevenAhead", "2026-11-18"), task("none", null), task("junk", "soon"),
            ),
            today,
        )
        assertEquals(TaskGroup.entries.toList(), groups.keys.toList())
        assertEquals(listOf("late"), groups.getValue(TaskGroup.Overdue).map { it.id })
        assertEquals(listOf("now"), groups.getValue(TaskGroup.Today).map { it.id })
        assertEquals(listOf("tomorrow", "sixAhead"), groups.getValue(TaskGroup.ThisWeek).map { it.id })
        assertEquals(listOf("sevenAhead"), groups.getValue(TaskGroup.Later).map { it.id })
        assertEquals(listOf("none", "junk"), groups.getValue(TaskGroup.NoDate).map { it.id })
    }

    @Test
    fun groups_keep_the_order_of_the_input() {
        val groups = taskGroups(listOf(task("b", "2026-11-01"), task("a", "2026-11-02")), LocalDate(2026, 11, 11))
        assertEquals(listOf("b", "a"), groups.getValue(TaskGroup.Overdue).map { it.id })
    }
}
