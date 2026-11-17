package dev.tim.assistant.ui

import dev.tim.assistant.ai.JobRig
import dev.tim.assistant.data.PENDING
import dev.tim.assistant.data.SYNCED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class UndoBufferTest {
    private fun action(token: Long, id: String = "t$token", kind: UndoKind = UndoKind.Delete) =
        PendingAction(token, UndoTables.TASK, id, kind, if (kind == UndoKind.Complete) true else null)

    @Test
    fun undo_writes_nothing_and_clears_the_hidden_row() {
        val buffer = UndoBuffer()
        assertNull(buffer.hold(action(1)))
        assertEquals(setOf(UndoTables.TASK to "t1"), buffer.hidden)
        assertTrue(buffer.undo(1))
        assertTrue(buffer.hidden.isEmpty())
        assertNull(buffer.expire(1))
    }

    @Test
    fun expire_returns_the_action_once() {
        val buffer = UndoBuffer()
        buffer.hold(action(1))
        assertEquals(action(1), buffer.expire(1))
        assertNull(buffer.expire(1))
        assertFalse(buffer.undo(1))
    }

    @Test
    fun a_second_hold_returns_the_first_to_commit() {
        val buffer = UndoBuffer()
        buffer.hold(action(1))
        assertEquals(action(1), buffer.hold(action(2)))
        assertEquals(setOf(UndoTables.TASK to "t2"), buffer.hidden)
    }

    @Test
    fun a_stale_token_never_resolves_a_newer_action() {
        val buffer = UndoBuffer()
        buffer.hold(action(1))
        buffer.hold(action(2))
        assertFalse(buffer.undo(1))
        assertNull(buffer.expire(1))
        assertEquals(action(2), buffer.current)
        assertTrue(buffer.undo(2))
    }

    @Test
    fun a_held_delete_leaves_the_row_untouched_until_committed() = runBlocking<Unit> {
        val rig = JobRig()
        val id = rig.repo.saveTask(null, "Pay rent", "2026-11-12", false)
        rig.db.taskQueries.upsert(rig.db.taskQueries.selectById(id).executeAsOne().copy(syncState = SYNCED))
        val before = rig.db.taskQueries.selectById(id).executeAsOne()

        val buffer = UndoBuffer()
        buffer.hold(PendingAction(1, UndoTables.TASK, id, UndoKind.Delete))

        // While held: the row is present, not deleted, and nothing is PENDING to push.
        val during = rig.db.taskQueries.selectById(id).executeAsOne()
        assertEquals(before, during)
        assertEquals(0L, during.deleted)
        assertTrue(rig.db.taskQueries.selectAll().executeAsList().none { it.syncState == PENDING })
        assertEquals(1, rig.repo.openTasks().size)

        commitPendingAction(rig.repo, buffer.expire(1)!!)
        val after = rig.db.taskQueries.selectById(id).executeAsOne()
        assertEquals(1L, after.deleted)
        assertEquals(PENDING, after.syncState)
    }

    @Test
    fun setTaskDone_changes_only_the_done_fields() = runBlocking<Unit> {
        val rig = JobRig()
        val id = rig.repo.saveTask(null, "Call Dana", "2026-11-12", false)
        rig.clock += 1000
        assertTrue(rig.repo.setTaskDone(id, true))
        val row = rig.db.taskQueries.selectById(id).executeAsOne()
        assertEquals(1L, row.done)
        assertNotNull(row.doneAt)
        assertEquals("Call Dana", row.title)
        assertEquals("2026-11-12", row.dueDate)
        assertEquals(PENDING, row.syncState)
        // Same state again: no-op.
        assertFalse(rig.repo.setTaskDone(id, true))
        assertTrue(rig.repo.setTaskDone(id, false))
        assertNull(rig.db.taskQueries.selectById(id).executeAsOne().doneAt)
    }

    @Test
    fun setTaskDone_keeps_the_goal_link() = runBlocking<Unit> {
        val rig = JobRig()
        val id = rig.repo.saveTask(null, "Chapter 3", null, false, "goal-1")
        assertTrue(rig.repo.setTaskDone(id, true))
        assertEquals("goal-1", rig.db.taskQueries.selectById(id).executeAsOne().goalId)
    }

    @Test
    fun setTaskDone_does_nothing_on_a_deleted_or_missing_row() = runBlocking<Unit> {
        val rig = JobRig()
        val id = rig.repo.saveTask(null, "Gone", null, false)
        rig.clock += 1000
        rig.repo.deleteTask(id)
        val before = rig.db.taskQueries.selectById(id).executeAsOne()
        assertFalse(rig.repo.setTaskDone(id, true))
        assertEquals(before, rig.db.taskQueries.selectById(id).executeAsOne())
        assertFalse(rig.repo.setTaskDone("nope", true))
        assertNull(rig.db.taskQueries.selectById("nope").executeAsOneOrNull())
    }

    @Test
    fun controller_commits_after_the_deadline_and_undo_prevents_it() = runBlocking<Unit> {
        val committed = mutableListOf<PendingAction>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = UndoController(scope, commit = { committed += it }, deadlineMs = 150)

        controller.delete(UndoTables.TASK, "a")
        waitFor { controller.pending.value != null }
        val token = controller.pending.value!!.token
        controller.undo(token)
        waitFor { controller.pending.value == null }
        delay(300)
        assertTrue(committed.isEmpty())

        controller.complete("b")
        waitFor { committed.size == 1 }
        assertEquals("b", committed.single().id)
        assertEquals(UndoKind.Complete, committed.single().kind)
        waitFor { controller.hidden.value.isEmpty() }
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test
    fun controller_flush_commits_the_pending_action_now() = runBlocking<Unit> {
        val committed = mutableListOf<PendingAction>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = UndoController(scope, commit = { committed += it }, deadlineMs = 60_000)
        controller.delete(UndoTables.TASK, "a")
        waitFor { controller.pending.value != null }
        controller.flush()
        assertEquals(listOf("a"), committed.map { it.id })
        assertNull(controller.pending.value)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(25)
        }
        assertTrue(condition(), "timed out")
    }
}
