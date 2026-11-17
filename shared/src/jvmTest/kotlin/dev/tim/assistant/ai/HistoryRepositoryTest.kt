package dev.tim.assistant.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class HistoryRepositoryTest {
    private val rig = JobRig()
    private val repo = rig.repo
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun a_past_day_check_in_has_its_own_id_and_never_touches_today() = runBlocking<Unit> {
        val past = repo.saveCheckin("2026-10-01", 4, 4, 4, 4, "then")
        assertEquals("checkin:2026-10-01", past)
        repo.saveCheckin("2026-10-02", 1, 1, 1, 1, null)
        assertEquals(4L, repo.checkinForDate("2026-10-01")?.mood)
        assertEquals("2026-10-01", repo.checkinForDate("2026-10-01")?.localDate)
        assertNull(repo.checkinForDate("2026-09-30"))
        assertEquals(listOf("2026-10-02", "2026-10-01"), repo.checkinsIn(d("2026-10-01"), d("2026-10-31")).map { it.localDate })
        repo.saveCheckin("2026-10-01", 2, 2, 2, 2, null)
        assertEquals(2L, repo.checkinForDate("2026-10-01")?.mood)
        assertEquals(2, repo.checkinsIn(d("2026-10-01"), d("2026-10-31")).size)
    }

    @Test
    fun the_in_range_reads_include_both_ends_and_nothing_else() = runBlocking<Unit> {
        repo.saveSpending("a", 100, "Food", null, "2026-10-31")
        repo.saveSpending("b", 100, "Food", null, "2026-11-01")
        repo.saveSpending("c", 100, "Food", null, "2026-11-30")
        repo.saveSpending("e", 100, "Food", null, "2026-12-01")
        assertEquals(setOf("b", "c"), repo.spendingIn(d("2026-11-01"), d("2026-11-30")).map { it.id }.toSet())
    }

    @Test
    fun journal_is_filtered_by_the_jerusalem_day_of_its_creation() = runBlocking<Unit> {
        // 2026-10-31 22:30 UTC is already 2026-11-01 in Jerusalem (UTC+2)
        rig.clock = Instant.parse("2026-10-31T22:30:00Z").toEpochMilliseconds()
        val id = repo.saveJournal(null, "late entry")
        assertEquals(listOf(id), repo.journalIn(d("2026-11-01"), d("2026-11-30")).map { it.id })
        assertTrue(repo.journalIn(d("2026-10-01"), d("2026-10-31")).isEmpty())
    }

    @Test
    fun completed_tasks_come_from_every_month_by_their_done_day() = runBlocking<Unit> {
        rig.clock = Instant.parse("2026-09-10T09:00:00Z").toEpochMilliseconds()
        val old = repo.saveTask(null, "old one", null, true)
        rig.clock = Instant.parse("2026-11-10T09:00:00Z").toEpochMilliseconds()
        val recent = repo.saveTask(null, "recent one", null, true)
        repo.saveTask(null, "still open", null, false)
        rig.clock = Instant.parse("2026-11-20T09:00:00Z").toEpochMilliseconds()
        assertEquals(listOf(old), repo.completedTasksIn(d("2026-09-01"), d("2026-09-30")).map { it.id })
        assertEquals(listOf(recent), repo.completedTasksIn(d("2026-11-01"), d("2026-11-30")).map { it.id })
        assertNotNull(repo.taskById(old))
    }
}