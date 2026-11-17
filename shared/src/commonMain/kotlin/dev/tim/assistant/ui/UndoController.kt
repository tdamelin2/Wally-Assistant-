package dev.tim.assistant.ui

import dev.tim.assistant.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Table names used by [PendingAction.table]. */
object UndoTables {
    const val CHECKIN = "checkin"
    const val SPENDING = "spending"
    const val JOURNAL = "journal"
    const val MEAL = "meal"
    const val TASK = "task"
    const val DREAM = "dream"
    const val DOC = "doc"
    const val EXAM = "exam"
    const val APP_RULE = "app_rule"
}

/** Writes a pending action to the database. Complete only exists for tasks. */
suspend fun commitPendingAction(repo: Repository, action: PendingAction) {
    when (action.kind) {
        UndoKind.Complete -> repo.setTaskDone(action.id, action.done ?: true)
        UndoKind.Delete -> when (action.table) {
            UndoTables.CHECKIN -> repo.deleteCheckin(action.id)
            UndoTables.SPENDING -> repo.deleteSpending(action.id)
            UndoTables.JOURNAL -> repo.deleteJournal(action.id)
            UndoTables.MEAL -> repo.deleteMeal(action.id)
            UndoTables.TASK -> repo.deleteTask(action.id)
            UndoTables.DREAM -> repo.deleteDream(action.id)
            UndoTables.DOC -> repo.deleteDoc(action.id)
            UndoTables.EXAM -> repo.deleteExam(action.id)
            UndoTables.APP_RULE -> repo.deleteRule(action.id)
            else -> error("unknown table ${action.table}")
        }
    }
}

const val UNDO_COMMIT_ERROR = "Could not save that change"

/**
 * The one process-wide keeper of the swipe-then-Undo window. Lives on the app scope, so leaving a screen never
 * loses or commits early. The database is written only when the window ends or a newer action replaces this one;
 * a failed write leaves the row as it was (it simply stops being hidden) and reports an error.
 */
class UndoController(
    private val scope: CoroutineScope,
    private val commit: suspend (PendingAction) -> Unit,
    private val onCommitted: () -> Unit = {},
    private val deadlineMs: Long = Motion.UndoMs,
) {
    private val lock = Mutex()
    private val buffer = UndoBuffer()
    private var lastToken = 0L
    private var timer: Job? = null
    private val committing = MutableStateFlow<Set<Pair<String, String>>>(emptySet())

    private val _pending = MutableStateFlow<PendingAction?>(null)
    /** The action waiting for Undo, for the snackbar. */
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    private val _hidden = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    /** Rows lists must hide: waiting for Undo or being written. */
    val hidden: StateFlow<Set<Pair<String, String>>> = _hidden.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    fun delete(table: String, id: String) = hold(table, id, UndoKind.Delete, null)

    fun complete(id: String, done: Boolean = true) = hold(UndoTables.TASK, id, UndoKind.Complete, done)

    /** Cancels [token] if it is still the pending action; a stale token does nothing. */
    fun undo(token: Long) {
        scope.launch {
            lock.withLock {
                if (buffer.undo(token)) {
                    timer?.cancel()
                    publish()
                }
            }
        }
    }

    /** Commits the pending action now (orderly quit). */
    suspend fun flush() {
        val action = lock.withLock {
            timer?.cancel()
            val current = buffer.current?.let { buffer.expire(it.token) }
            publish()
            current
        }
        action?.let { commitSafely(it) }
    }

    private fun hold(table: String, id: String, kind: UndoKind, done: Boolean?) {
        scope.launch {
            val replaced = lock.withLock {
                val action = PendingAction(++lastToken, table, id, kind, done)
                val old = buffer.hold(action)
                timer?.cancel()
                old?.let { markCommitting(it) }
                publish()
                timer = scope.launch {
                    delay(deadlineMs)
                    expire(action.token)
                }
                old
            }
            replaced?.let { commitSafely(it, alreadyMarked = true) }
        }
    }

    private suspend fun expire(token: Long) {
        val action = lock.withLock {
            val expired = buffer.expire(token)
            expired?.let { markCommitting(it) }
            publish()
            expired
        }
        action?.let { commitSafely(it, alreadyMarked = true) }
    }

    private fun markCommitting(action: PendingAction) {
        committing.update { it + (action.table to action.id) }
    }

    private fun publish() {
        _pending.value = buffer.current
        _hidden.value = buffer.hidden + committing.value
    }

    private suspend fun commitSafely(action: PendingAction, alreadyMarked: Boolean = false) {
        if (!alreadyMarked) {
            lock.withLock { markCommitting(action); publish() }
        }
        var ok = false
        try {
            // Must finish even if a newer swipe cancels the timer job that got us here.
            withContext(NonCancellable) { commit(action) }
            ok = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errors.tryEmit(UNDO_COMMIT_ERROR)
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    committing.update { it - (action.table to action.id) }
                    publish()
                }
            }
        }
        if (ok) onCommitted()
    }
}
