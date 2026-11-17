package dev.tim.assistant.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Meal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class RepositoryMealTest {
    private var clock = 1_000_000L
    private val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
    private val store = LocalStore(db)
    private val repo = Repository(store, now = { clock }, today = { "2023-11-14" })

    // 2023-11-14 22:13:20 UTC = 2023-11-15 00:13:20 in Asia/Jerusalem (UTC+2)
    private val eatenAt = 1_700_000_000_000L

    private fun row(id: String) = db.mealQueries.selectById(id).executeAsOne()

    /** Gives the meal an estimate the way the server would (same updatedAt, nutrition filled in). */
    private fun estimate(id: String) {
        val m = row(id)
        db.mealQueries.upsert(m.copy(kcal = 650, proteinG = 30, carbsG = 70, fatG = 20, estimateModel = "llama", syncState = SYNCED))
    }

    private fun Meal.nutrition() = listOf(kcal, proteinG, carbsG, fatG, estimateModel)

    @Test
    fun new_meals_are_pending_with_null_nutrition_and_a_jerusalem_local_date() = runBlocking<Unit> {
        val id = repo.saveMeal(null, "MEAL", "  pasta  ", eatenAt)
        val m = row(id)
        assertEquals(PENDING, m.syncState)
        assertEquals("pasta", m.text)
        assertEquals("2023-11-15", m.localDate)
        assertEquals(eatenAt, m.eatenAt)
        assertEquals(listOf(null, null, null, null, null), m.nutrition())
        assertEquals(1, repo.pendingCount())
    }

    @Test
    fun editing_the_text_clears_the_five_nutrition_fields() = runBlocking<Unit> {
        val id = repo.saveMeal(null, "MEAL", "pasta", eatenAt)
        estimate(id)
        clock += 10
        repo.saveMeal(id, "MEAL", "pizza", eatenAt)
        val m = row(id)
        assertEquals("pizza", m.text)
        assertEquals(listOf(null, null, null, null, null), m.nutrition())
        assertEquals(PENDING, m.syncState)
    }

    @Test
    fun editing_only_the_time_keeps_the_nutrition() = runBlocking<Unit> {
        val id = repo.saveMeal(null, "MEAL", "pasta", eatenAt)
        estimate(id)
        clock += 10
        repo.saveMeal(id, "MEAL", "pasta", eatenAt + 3_600_000)
        val m = row(id)
        assertEquals(listOf<Any?>(650L, 30L, 70L, 20L, "llama"), m.nutrition())
        assertEquals(eatenAt + 3_600_000, m.eatenAt)
        assertEquals(PENDING, m.syncState)
    }

    @Test
    fun editing_only_the_type_keeps_the_nutrition() = runBlocking<Unit> {
        val id = repo.saveMeal(null, "MEAL", "pasta", eatenAt)
        estimate(id)
        clock += 10
        repo.saveMeal(id, "SNACK", "pasta", eatenAt)
        val m = row(id)
        assertEquals("SNACK", m.type)
        assertEquals(listOf<Any?>(650L, 30L, 70L, 20L, "llama"), m.nutrition())
    }

    @Test
    fun a_nutrition_value_pulled_after_the_form_opened_survives_a_time_only_edit() = runBlocking<Unit> {
        val id = repo.saveMeal(null, "MEAL", "pasta", eatenAt)
        // the form was opened on this version (no nutrition); then the estimate arrived
        estimate(id)
        // the form saves with the same text: the stored (latest) nutrition must be kept
        repo.saveMeal(id, "MEAL", "pasta", eatenAt + 60_000)
        assertEquals(650L, row(id).kcal)
    }

    @Test
    fun updatedAt_beats_the_previous_version_and_delete_makes_a_pending_tombstone() = runBlocking<Unit> {
        val id = repo.saveMeal(null, "MEAL", "pasta", eatenAt)
        val first = row(id).updatedAt
        repo.saveMeal(id, "MEAL", "pasta!", eatenAt)
        assertEquals(first + 1, row(id).updatedAt)
        repo.deleteMeal(id)
        val m = row(id)
        assertEquals(1L, m.deleted)
        assertEquals(PENDING, m.syncState)
        assertEquals(0, repo.meals().size)
    }

    @Test
    fun meals_are_newest_first_and_hide_deleted() = runBlocking<Unit> {
        val old = repo.saveMeal(null, "MEAL", "old", eatenAt)
        repo.saveMeal(null, "SNACK", "new", eatenAt + 1000)
        val gone = repo.saveMeal(null, "MEAL", "gone", eatenAt + 2000)
        repo.deleteMeal(gone)
        assertEquals(listOf("new", "old"), repo.meals().map { it.text })
        assertEquals(old, repo.meals().last().id)
    }

    @Test
    fun allTasksForCharts_includes_tasks_finished_long_ago_but_not_deleted_ones() = runBlocking<Unit> {
        val a = repo.saveTask(null, "old done", null, true)
        val b = repo.saveTask(null, "deleted", null, true)
        repo.deleteTask(b)
        clock += 60L * 24 * 3600 * 1000
        assertEquals(0, repo.tasksForLog().size)
        assertEquals(listOf(a), repo.allTasksForCharts().map { it.id })
        assertNull(repo.allTasksForCharts().single().dueDate)
    }
}