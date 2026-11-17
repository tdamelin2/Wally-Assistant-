package dev.tim.assistant.ui

import dev.tim.assistant.ai.JobRig
import dev.tim.assistant.usage.Label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UndoNewTablesTest {
    private val rig = JobRig()
    private val repo = rig.repo

    @Test
    fun committing_a_doc_delete_removes_the_doc() = runBlocking<Unit> {
        val id = repo.saveDoc(null, null, "Notes", "body")
        commitPendingAction(repo, PendingAction(1, UndoTables.DOC, id, UndoKind.Delete))
        assertTrue(repo.docs().none { it.id == id })
    }

    @Test
    fun committing_an_exam_delete_removes_the_exam() = runBlocking<Unit> {
        val id = repo.saveExam(null, null, "Calculus", "2030-01-10", null)
        assertEquals(1, repo.exams().count { it.id == id })
        commitPendingAction(repo, PendingAction(1, UndoTables.EXAM, id, UndoKind.Delete))
        assertTrue(repo.exams().none { it.id == id })
    }

    @Test
    fun committing_a_rule_delete_removes_the_rule() = runBlocking<Unit> {
        val id = repo.saveRule("EXE", "code.exe", Label.PRODUCTIVE)!!
        assertEquals(1, repo.appRules().count { it.id == id })
        commitPendingAction(repo, PendingAction(1, UndoTables.APP_RULE, id, UndoKind.Delete))
        assertTrue(repo.appRules().none { it.id == id })
    }
}
